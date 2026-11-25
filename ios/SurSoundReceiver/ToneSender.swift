import Combine
import Foundation

final class ToneSender: ObservableObject {
    @Published private(set) var active = false
    @Published private(set) var status = "Ready"
    private var timer: DispatchSourceTimer?
    private var sender: PacketSender?

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

    func stop(_ reason: String = "Stopped.") {
        timer?.cancel()
        timer = nil
        sender?.stop()
        sender = nil
        active = false
        status = reason
    }

    deinit { timer?.cancel(); sender?.stop() }
}
