import Foundation

/// Four monotonic timestamps: t1/t4 are on the receiver, t2/t3 on the sender.
struct ClockExchange {
    let t1: Int64
    let t2: Int64
    let t3: Int64
    let t4: Int64
}

struct ClockEstimate {
    let senderMinusReceiverNanos: Double
    let roundTripNanos: Double
    var pathUncertaintyNanos: Double { roundTripNanos / 2 }
    func receiverTimeFor(senderTimeNanos: Int64) -> Double {
        Double(senderTimeNanos) - senderMinusReceiverNanos
    }
}

/// Bounded minimum-RTT filter; not an acoustic synchronization measurement.
struct ClockOffsetEstimator {
    private let capacity: Int
    private var samples: [ClockEstimate] = []
    private var lastReceive: Int64?

    init(capacity: Int = 12) {
        precondition((1...64).contains(capacity))
        self.capacity = capacity
    }

    @discardableResult
    mutating func add(_ exchange: ClockExchange) -> Bool {
        let (t1, t2, t3, t4) = (exchange.t1, exchange.t2, exchange.t3, exchange.t4)
        guard t4 >= t1, t3 >= t2, lastReceive.map({ t4 > $0 }) ?? true else { return false }
        let local = Double(t4) - Double(t1)
        let remote = Double(t3) - Double(t2)
        let rtt = local - remote
        guard local <= 1_000_000_000, rtt >= 0, rtt <= 1_000_000_000 else { return false }
        let offset = ((Double(t2) - Double(t1)) + (Double(t3) - Double(t4))) / 2
        if samples.count == capacity { samples.removeFirst() }
        samples.append(ClockEstimate(senderMinusReceiverNanos: offset, roundTripNanos: rtt))
        lastReceive = t4
        return true
    }

    func estimate() -> ClockEstimate? {
        samples.reversed().min { $0.roundTripNanos < $1.roundTripNanos }
    }
}

/// Local-clock playout of authenticated 5 ms PCM blocks. Bounded reorder window.
struct ReceiverPlayoutBuffer {
    static let frameNanos: UInt64 = 5_000_000
    static let maxSlewNanos: Int64 = 10_000 // at most 2 ms per second of emitted slots
    struct Block {
        let sequence: Int64
        let pcm: Data?
    }

    private let initialDelayNanos: UInt64
    private let windowPackets: Int64
    private var session: UInt64?
    private var expected: Int64 = 0
    private var deadline: UInt64 = 0
    private var pending: [Int64: Data] = [:]
    private(set) var missing: UInt64 = 0
    private(set) var schedulerSkipped: UInt64 = 0
    private(set) var maxDispatchLatenessNanos: UInt64 = 0
    var started: Bool { session != nil }
    var queued: Int { pending.count }

    init(initialDelayNanos: UInt64 = 40_000_000, windowPackets: Int = 64) {
        precondition(initialDelayNanos <= 1_000_000_000 && (1...256).contains(windowPackets))
        self.initialDelayNanos = initialDelayNanos
        self.windowPackets = Int64(windowPackets)
    }

    @discardableResult
    mutating func offer(sessionId: UInt64, sequence: UInt32, pcm: Data, nowNanos: UInt64, firstDeadlineNanos: UInt64? = nil) -> Bool {
        guard sequence < UInt32(Int32.max), pcm.count == LanPacket.pcmBytes else { return false }
        let index = Int64(sequence)
        if session == nil {
            session = sessionId
            expected = index
            deadline = firstDeadlineNanos ?? (nowNanos + initialDelayNanos)
        }
        guard session == sessionId, index >= expected, index - expected < windowPackets,
              pending[index] == nil else { return false }
        pending[index] = pcm
        return true
    }

    /// One due block at most; nil PCM represents a missing frame (render silence).
    mutating func poll(nowNanos: UInt64, nextIntervalAdjustmentNanos: Int64 = 0) -> Block? {
        precondition((-Self.maxSlewNanos...Self.maxSlewNanos).contains(nextIntervalAdjustmentNanos))
        guard started, expected < Int64(Int32.max), nowNanos >= deadline else { return nil }
        maxDispatchLatenessNanos = max(maxDispatchLatenessNanos, nowNanos - deadline)
        let skipped = (nowNanos - deadline) / Self.frameNanos
        if skipped > 0 {
            expected += Int64(skipped)
            deadline += skipped * Self.frameNanos
            schedulerSkipped += skipped
            pending = pending.filter { $0.key >= expected }
        }
        guard expected < Int64(Int32.max) else { discardPending(); return nil }
        let sequence = expected
        expected += 1
        deadline += UInt64(Int64(Self.frameNanos) + nextIntervalAdjustmentNanos)
        let pcm = pending.removeValue(forKey: sequence)
        if pcm == nil { missing += 1 }
        return Block(sequence: sequence, pcm: pcm)
    }

    mutating func discardPending() { pending.removeAll() }
}
