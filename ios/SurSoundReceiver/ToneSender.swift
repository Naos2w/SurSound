import Combine
import Foundation

final class ToneSender: ObservableObject {
    @Published private(set) var active = false
    @Published private(set) var status = "Ready"
    private var timer: DispatchSourceTimer?
    private var sender: PacketSender?
    private var groupSender: GroupToneTransport?

    func start(peer: String, secret: String) {
        guard !active, let address = LanPacket.localPeer(peer), let key = LanPacket.key(secret) else {
            status = "Enter a private IPv4 address and a fresh key."; return
        }
        let output = PacketSender(peer: address, key: key)
        sender = output
        let t = DispatchSource.makeTimerSource(queue: .main)
        let deadline = Date().addingTimeInterval(30)
        var phase: Double = 0
        var packetizer = StereoPacketizer()
        active = true
        status = "Sending synthetic tone (30 s)"
        t.schedule(deadline: .now(), repeating: .milliseconds(5), leeway: .milliseconds(1))
        t.setEventHandler { [weak self] in
            guard let self = self, self.active else { return }
            let stats = output.snapshot()
            if let failure = stats.failure { self.stop("Send failed: " + failure); return }
            if Date() >= deadline { self.stop("Tone test ended."); return }
            for _ in 0..<LanPacket.frames {
                let value = Float(sin(phase) * 0.15)
                phase += 2 * Double.pi * 440 / 48_000
                if phase >= 2 * Double.pi { phase -= 2 * Double.pi }
                if let packet = packetizer.append(left: value, right: value) { output.offer(packet) }
            }
            if stats.sent % 50 == 0 {
                self.status = "Sent: " + String(stats.sent) + " / dropped: " + String(stats.dropped)
            }
        }
        timer = t
        t.resume()
    }

    func startGroup(peer: String, secret: String) {
        guard !active, let key = LanPacket.key(secret) else {
            status = "Enter a fresh pairing key."; return
        }
        let parts = peer.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
        let addresses = parts.compactMap { LanPacket.localPeer($0) }
        guard (1...4).contains(parts.count), addresses.count == parts.count,
              Set(addresses.map { String(describing: $0) }).count == addresses.count else {
            status = "Enter one to four distinct private IPv4 addresses."; return
        }
        let output = GroupToneTransport(addresses: addresses, wire: SyncWire(key: key))
        groupSender = output
        let t = DispatchSource.makeTimerSource(queue: .main)
        let start = DispatchTime.now().uptimeNanoseconds
        let origin = Int64(start + SyncWire.leadNanos)
        var nextSequence: UInt32 = 0
        var lastPublish: UInt64 = 0
        active = true
        status = "Starting shared-clock group test"
        t.schedule(deadline: .now(), repeating: .milliseconds(2), leeway: .milliseconds(1))
        t.setEventHandler { [weak self] in
            guard let self = self, self.active else { return }
            let now = DispatchTime.now().uptimeNanoseconds
            let slot = (now - start) / ReceiverPlayoutBuffer.frameNanos
            if slot >= UInt64(SyncWire.maxPackets) { self.stop("Group test ended."); return }
            let sequence = UInt32(slot)
            guard sequence >= nextSequence else { return }
            let skipped = sequence - nextSequence
            nextSequence = sequence + 1
            var packetizer = StereoPacketizer()
            do {
                for index in 0..<LanPacket.frames {
                    let frame = UInt64(sequence) * UInt64(LanPacket.frames) + UInt64(index)
                    let amplitude = frame % 48_000 < 2400 ? 2400.0 / 32768 : 0
                    let value = Float(sin(2 * Double.pi * 440 * Double(frame) / 48_000) * amplitude)
                    if let pcm = packetizer.append(left: value, right: value) {
                        try output.send(sequence: sequence, origin: origin, pcm: pcm, skipped: skipped)
                    }
                }
            } catch { self.stop("Group packet encoding failed."); return }
            if now - lastPublish >= 250_000_000 {
                lastPublish = now
                self.status = "Group: " + String(output.peerCount) + " receivers • sent: " + String(output.sent)
                    + " • dropped: " + String(output.dropped) + " • clock replies: " + String(output.replies)
            }
        }
        timer = t
        t.resume()
    }

    func stop(_ reason: String = "Stopped.") {
        timer?.cancel()
        timer = nil
        sender?.stop()
        sender = nil
        groupSender?.stop()
        groupSender = nil
        active = false
        status = reason
    }

    deinit { timer?.cancel(); sender?.stop(); groupSender?.stop() }
}
