import Foundation

/// A v2 receiver waits for authenticated clock samples; never silently switches to v1.
struct GroupPlayout {
    let wire: SyncWire
    private var session: UInt64?
    private var origin: Int64 = 0
    private var firstSequence: UInt32 = 0
    private var firstArrival: UInt64 = 0
    private var lastProbe: UInt64?
    private var outstanding: SyncWire.Clock?
    private var attempts = 0
    private var sampleCount = 0
    private var failed = false
    private var ended = false
    private var estimator = ClockOffsetEstimator()
    private var refinement = ClockOffsetEstimator(capacity: 4)
    private var targetOffset = 0.0
    private var appliedOffset = 0.0
    private var lastGoodReply: UInt64?
    private(set) var refinementCount = 0
    private var pending: [UInt32: Data] = [:]
    private var buffer = ReceiverPlayoutBuffer()
    var started: Bool { session != nil }
    var ready: Bool { buffer.started }
    var missing: UInt64 { buffer.missing }
    var schedulerSkipped: UInt64 { buffer.schedulerSkipped }
    var maxDispatchLatenessNanos: UInt64 { buffer.maxDispatchLatenessNanos }
    var residualOffsetNanos: Double { targetOffset - appliedOffset }
    func clockAgeNanos(now: UInt64) -> UInt64? { lastGoodReply.map { now >= $0 ? now - $0 : 0 } }
    private(set) var roundTripNanos: Double?

    init(wire: SyncWire) { self.wire = wire }

    mutating func offer(_ packet: SyncWire.Audio, now: UInt64) -> Bool {
        guard !failed, !ended, packet.sequence < SyncWire.maxPackets, packet.pcm.count == LanPacket.pcmBytes else { return false }
        if session == nil {
            session = packet.session
            origin = packet.origin
            firstSequence = packet.sequence
            firstArrival = now
        }
        guard session == packet.session, origin == packet.origin else { return false }
        if ready {
            return buffer.offer(sessionId: packet.session, sequence: packet.sequence, pcm: packet.pcm, nowNanos: now)
        }
        guard packet.sequence >= firstSequence, packet.sequence - firstSequence < 64,
              pending[packet.sequence] == nil else { return false }
        pending[packet.sequence] = packet.pcm
        return true
    }

    mutating func nextProbe(now: UInt64) -> Data? {
        guard let id = session, now <= UInt64(Int64.max) else { return nil }
        settle(now: now)
        guard !failed, !ended, ready || attempts < 12 else { return nil }
        let interval: UInt64 = ready ? 1_000_000_000 : 25_000_000
        if let previous = lastProbe, now < previous || now - previous < interval { return nil }
        if let request = outstanding, now - UInt64(request.t1) < 80_000_000 { return nil }
        let request = SyncWire.Clock(kind: SyncWire.request, session: id,
            nonce: UInt64.random(in: UInt64.min...UInt64.max), t1: Int64(now), t2: 0, t3: 0)
        lastProbe = now
        outstanding = request
        if !ready { attempts += 1 }
        return wire.sealClock(request)
    }

    mutating func acceptReply(_ reply: SyncWire.Clock, now: UInt64) -> Bool {
        guard let request = outstanding, !failed, !ended, now <= UInt64(Int64.max),
              reply.kind == SyncWire.reply, reply.session == session, reply.session == request.session,
              reply.nonce == request.nonce, reply.t1 == request.t1 else { return false }
        outstanding = nil
        let exchange = ClockExchange(t1: reply.t1, t2: reply.t2, t3: reply.t3, t4: Int64(now))
        let rtt = (Double(now) - Double(reply.t1)) - (Double(reply.t3) - Double(reply.t2))
        guard now >= UInt64(request.t1), now - UInt64(request.t1) <= 100_000_000,
              (0...100_000_000).contains(rtt) else { return false }
        if ready {
            let observed = ((Double(reply.t2) - Double(reply.t1)) + (Double(reply.t3) - Double(now))) / 2
            guard rtt <= 20_000_000, abs(observed - targetOffset) <= 10_000_000,
                  refinement.add(exchange), let estimate = refinement.estimate() else { return false }
            targetOffset = estimate.senderMinusReceiverNanos
            roundTripNanos = estimate.roundTripNanos
            refinementCount += 1
        } else {
            guard estimator.add(exchange) else { return false }
            sampleCount += 1
        }
        lastGoodReply = now
        settle(now: now)
        return true
    }

    private mutating func settle(now: UInt64) {
        guard let id = session, !ready, !failed else { return }
        if sampleCount == 0 {
            if attempts >= 12, outstanding.map({ now - UInt64($0.t1) >= 80_000_000 }) ?? true {
                failed = true
                clear()
            }
            return
        }
        guard sampleCount >= 3 || now - firstArrival >= 120_000_000,
              let estimate = estimator.estimate() else { return }
        let deadline = estimate.receiverTimeFor(senderTimeNanos: origin) +
            Double(firstSequence) * Double(ReceiverPlayoutBuffer.frameNanos)
        let lead = deadline - Double(now)
        guard deadline.isFinite, (-500_000_000...1_000_000_000).contains(lead),
              deadline >= 0, deadline < Double(Int64.max) else {
            failed = true
            clear()
            return
        }
        for (sequence, pcm) in pending.sorted(by: { $0.key < $1.key }) {
            _ = buffer.offer(sessionId: id, sequence: sequence, pcm: pcm, nowNanos: now,
                             firstDeadlineNanos: UInt64(deadline))
        }
        pending.removeAll()
        outstanding = nil
        roundTripNanos = estimate.roundTripNanos
        targetOffset = estimate.senderMinusReceiverNanos
        appliedOffset = targetOffset
    }

    mutating func poll(now: UInt64) -> ReceiverPlayoutBuffer.Block? {
        guard !ended else { return nil }
        settle(now: now)
        let limit = Double(ReceiverPlayoutBuffer.maxSlewNanos)
        let step = Int64(max(-limit, min(limit, residualOffsetNanos)))
        guard let block = buffer.poll(nowNanos: now, nextIntervalAdjustmentNanos: -step) else { return nil }
        if block.sequence >= Int64(SyncWire.maxPackets) {
            buffer.discardPending()
            ended = true
            return nil
        }
        appliedOffset += Double(step)
        return block
    }

    mutating func clear() { pending.removeAll(); buffer.discardPending() }
}
