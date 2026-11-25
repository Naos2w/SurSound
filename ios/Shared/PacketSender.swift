import CryptoKit
import Foundation
import Network

/// One explicit local peer. Bound outstanding datagrams; no disk or retry queue.
final class PacketSender {
    private let connection: NWConnection
    private let lock = NSLock()
    private var encoder: LanPacket.Encoder
    private var ready = false
    private var closed = false
    private var pending = 0
    private var sent: UInt64 = 0
    private var dropped: UInt64 = 0
    private var failure: String?

    init(peer: IPv4Address, key: SymmetricKey) {
        encoder = LanPacket.Encoder(key: key)
        connection = NWConnection(host: .ipv4(peer), port: NWEndpoint.Port(rawValue: LanPacket.port)!, using: .udp)
        connection.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            self.lock.lock()
            defer { self.lock.unlock() }
            guard !self.closed else { return }
            switch state {
            case .ready: self.ready = true
            case .failed(let error):
                self.ready = false
                self.failure = error.localizedDescription
            case .waiting(let error):
                self.ready = false
                self.failure = error.localizedDescription
            case .cancelled: self.ready = false
            default: break
            }
        }
        connection.start(queue: DispatchQueue(label: "sursound.packet.sender"))
    }

    func offer(_ pcm: Data) {
        lock.lock()
        guard !closed, ready, pending < 12 else { dropped += 1; lock.unlock(); return }
        let packet: Data
        do { packet = try encoder.encode(pcm) }
        catch { failure = "Packet encoding failed"; dropped += 1; lock.unlock(); return }
        pending += 1
        lock.unlock()
        connection.send(content: packet, completion: .contentProcessed { [weak self] error in
            guard let self = self else { return }
            self.lock.lock()
            defer { self.lock.unlock() }
            self.pending = max(0, self.pending - 1)
            if let error = error { self.dropped += 1; self.failure = error.localizedDescription }
            else { self.sent += 1 }
        })
    }

    func snapshot() -> (sent: UInt64, dropped: UInt64, failure: String?) {
        lock.lock()
        defer { lock.unlock() }
        return (sent, dropped, failure)
    }

    func stop() {
        lock.lock()
        closed = true
        ready = false
        lock.unlock()
        connection.cancel()
    }

    deinit { connection.cancel() }
}

/// PCM16 little-endian, two interleaved channels, exactly 240 frames per packet.
struct StereoPacketizer {
    private var pending = Data()
    mutating func append(left: Float, right: Float) -> Data? {
        for value in [left, right] {
            let finite = value.isFinite ? value : 0
            let scaled = Int16(max(-32768, min(32767, Int((max(-1, min(1, finite)) * 32768).rounded()))))
            let bits = UInt16(bitPattern: scaled)
            pending.append(UInt8(truncatingIfNeeded: bits))
            pending.append(UInt8(truncatingIfNeeded: bits >> 8))
        }
        guard pending.count == LanPacket.pcmBytes else { return nil }
        let result = pending
        pending = Data()
        return result
    }
    mutating func clear() { pending = Data() }
}
