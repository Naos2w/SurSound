import Foundation
import Network

/// Main-queue-only group test transport, with per-peer queue limits and a shared ciphertext.
final class GroupToneTransport {
    private final class Peer {
        let connection: NWConnection
        var ready = false
        var pending = 0
        var lastReply: UInt64?
        init(address: IPv4Address) {
            connection = NWConnection(host: .ipv4(address), port: NWEndpoint.Port(rawValue: LanPacket.port)!, using: .udp)
        }
    }

    private let wire: SyncWire
    private let session = UInt64.random(in: UInt64.min...UInt64.max)
    private let peers: [Peer]
    private var stopped = false
    private(set) var sent: UInt64 = 0
    private(set) var dropped: UInt64 = 0
    private(set) var replies: UInt64 = 0
    var peerCount: Int { peers.count }

    init(addresses: [IPv4Address], wire: SyncWire) {
        precondition((1...4).contains(addresses.count))
        self.wire = wire
        peers = addresses.map { Peer(address: $0) }
        for peer in peers {
            peer.connection.stateUpdateHandler = { [weak self, weak peer] state in
                guard let self = self, let peer = peer, !self.stopped else { return }
                switch state {
                case .ready:
                    if !peer.ready {
                        peer.ready = true
                        self.receive(peer)
                    }
                case .failed, .cancelled: peer.ready = false
                default: break
                }
            }
            peer.connection.start(queue: .main)
        }
    }

    func send(sequence: UInt32, origin: Int64, pcm: Data, skipped: UInt32) throws {
        guard !stopped else { return }
        dropped += UInt64(skipped) * UInt64(peers.count)
        let packet = try wire.sealAudio(session: session, sequence: sequence, origin: origin, pcm: pcm)
        for peer in peers {
            guard peer.ready, peer.pending < 12 else { dropped += 1; continue }
            transmit(packet, to: peer, audio: true)
        }
    }

    private func transmit(_ packet: Data, to peer: Peer, audio: Bool) {
        guard !stopped, peer.ready, peer.pending < 12 else { return }
        peer.pending += 1
        peer.connection.send(content: packet, completion: .contentProcessed { [weak self, weak peer] error in
            guard let self = self, let peer = peer, !self.stopped else { return }
            peer.pending = max(0, peer.pending - 1)
            if audio {
                if error == nil { self.sent += 1 }
                else { self.dropped += 1 }
            } else if error == nil { self.replies += 1 }
        })
    }

    private func receive(_ peer: Peer) {
        peer.connection.receiveMessage { [weak self, weak peer] data, _, _, error in
            guard let self = self, let peer = peer, !self.stopped, peer.ready else { return }
            if error != nil { peer.ready = false; peer.connection.cancel(); return }
            let now = DispatchTime.now().uptimeNanoseconds
            if let data = data, let request = self.wire.openClock(data),
               request.kind == SyncWire.request, request.session == self.session,
               now <= UInt64(Int64.max), peer.pending < 12,
               peer.lastReply.map({ now - $0 >= 10_000_000 }) ?? true {
                peer.lastReply = now
                let reply = SyncWire.Clock(kind: SyncWire.reply, session: request.session,
                    nonce: request.nonce, t1: request.t1, t2: Int64(now),
                    t3: Int64(DispatchTime.now().uptimeNanoseconds))
                self.transmit(self.wire.sealClock(reply), to: peer, audio: false)
            }
            self.receive(peer)
        }
    }

    func stop() {
        guard !stopped else { return }
        stopped = true
        for peer in peers { peer.connection.cancel() }
    }

    deinit { for peer in peers { peer.connection.cancel() } }
}
