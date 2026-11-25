import AVFoundation
import Combine
import CryptoKit
import Foundation
import Network

/// Foreground-only diagnostic. All state and callbacks are serialized on the main queue.
final class Receiver: ObservableObject {
    @Published private(set) var active = false
    @Published private(set) var status = "Ready"
    @Published private(set) var packetCount: UInt64 = 0
    @Published private(set) var drops: UInt64 = 0

    private var listener: NWListener?
    private var connections: [ObjectIdentifier: NWConnection] = [:]
    private var selected: ObjectIdentifier?
    private var expected: IPv4Address?
    private var key: SymmetricKey?
    private var playout = ReceiverPlayoutBuffer()
    private var playoutTimer: DispatchSourceTimer?
    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var format: AVAudioFormat?
    private var timeout: DispatchWorkItem?
    private var interruption: NSObjectProtocol?
    private var generation = UUID()
    private var pendingBuffers = 0
    private var count: UInt64 = 0
    private var rejected: UInt64 = 0
    private var lastPublish = Date.distantPast

    func start(peer: String, secret: String) {
        guard !active else { return }
        guard let address = LanPacket.localPeer(peer), let pairingKey = LanPacket.key(secret) else {
            status = "Enter a private IPv4 address and a 32-character hexadecimal pairing key."
            return
        }
        let id = UUID()
        generation = id
        expected = address
        key = pairingKey
        playout = ReceiverPlayoutBuffer()
        count = 0
        rejected = 0
        packetCount = 0
        drops = 0
        pendingBuffers = 0
        lastPublish = .distantPast
        active = true
        status = "Starting local receiver..."
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playback)
            try audioSession.setActive(true)
            let e = AVAudioEngine()
            let p = AVAudioPlayerNode()
            guard let f = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2) else {
                stop("Unable to configure stereo PCM."); return
            }
            e.attach(p)
            e.connect(p, to: e.mainMixerNode, format: f)
            p.volume = 0.25
            engine = e
            player = p
            format = f
            try e.start()
            let local = try NWListener(using: .udp, on: NWEndpoint.Port(rawValue: LanPacket.port)!)
            local.service = NWListener.Service(name: "SurSound-" + String(UUID().uuidString.prefix(8)), type: "_sursound._udp")
            listener = local
            local.stateUpdateHandler = { [weak self] state in
                guard let self = self, self.active, self.generation == id else { return }
                switch state {
                case .ready: self.status = "Listening for authenticated local audio..."
                case .failed(let error): self.stop("Listener failed: " + error.localizedDescription)
                case .waiting(let error): self.status = "Waiting for local network: " + error.localizedDescription
                default: break
                }
            }
            local.newConnectionHandler = { [weak self] connection in
                guard let self = self, self.active, self.generation == id else { connection.cancel(); return }
                self.accept(connection, generation: id)
            }
            interruption = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: audioSession, queue: .main) { [weak self] note in
                guard let self = self, self.generation == id, self.active,
                      let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                      raw == AVAudioSession.InterruptionType.began.rawValue else { return }
                self.stop("Audio interrupted; start a fresh session.")
            }
            local.start(queue: .main)
            let timer = DispatchWorkItem { [weak self] in
                guard let self = self, self.active, self.generation == id else { return }
                self.stop("45-second receiver limit reached.")
            }
            timeout = timer
            DispatchQueue.main.asyncAfter(deadline: .now() + 45, execute: timer)
        } catch {
            stop("Receiver setup failed: " + error.localizedDescription)
        }
    }

    private func accept(_ connection: NWConnection, generation id: UUID) {
        guard case .hostPort(let host, _) = connection.endpoint,
              case .ipv4(let address) = host, address == expected,
              selected == nil, connections.count < 4 else {
            connection.cancel()
            return
        }
        let token = ObjectIdentifier(connection)
        connections[token] = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self = self, let connection = connection,
                  self.active, self.generation == id else { return }
            switch state {
            case .ready: self.read(connection, generation: id)
            case .failed, .cancelled:
                self.connections.removeValue(forKey: token)
                if self.selected == token { self.stop("Sender disconnected; start with a fresh pairing key.") }
            default: break
            }
        }
        connection.start(queue: .main)
    }

    private func read(_ connection: NWConnection, generation id: UUID) {
        let token = ObjectIdentifier(connection)
        connection.receiveMessage { [weak self, weak connection] data, _, _, error in
            guard let self = self, let connection = connection, self.active,
                  self.generation == id, self.connections[token] != nil else { return }
            if error != nil {
                connection.cancel()
                return
            }
            if let data = data, let key = self.key, let packet = LanPacket.decode(data, key: key),
               (self.selected == nil || self.selected == token),
               self.playout.offer(sessionId: packet.session, sequence: packet.sequence, pcm: packet.pcm,
                                  nowNanos: DispatchTime.now().uptimeNanoseconds) {
                if self.selected == nil {
                    self.selected = token
                    for (other, candidate) in Array(self.connections) where other != token {
                        self.connections.removeValue(forKey: other)
                        candidate.cancel()
                    }
                }
                self.count += 1
                self.startPlayoutTimer(generation: id)
                self.publish("Buffering authenticated PCM audio")
            } else {
                self.rejected += 1
                self.publish("Listening for authenticated local audio...")
            }
            self.read(connection, generation: id)
        }
    }

    private func startPlayoutTimer(generation id: UUID) {
        guard playoutTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now(), repeating: .milliseconds(2), leeway: .milliseconds(1))
        timer.setEventHandler { [weak self] in
            guard let self = self, self.active, self.generation == id else { return }
            guard let block = self.playout.poll(nowNanos: DispatchTime.now().uptimeNanoseconds) else { return }
            let pcm = block.pcm ?? Data(repeating: 0, count: LanPacket.pcmBytes)
            if !self.play(pcm, generation: id) { self.rejected += 1 }
            self.publish("Playing buffered authenticated PCM audio")
        }
        playoutTimer = timer
        timer.resume()
    }

    private func play(_ pcm: Data, generation id: UUID) -> Bool {
        guard pendingBuffers < 12, let player = player, let format = format,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(LanPacket.frames)),
              let channels = buffer.floatChannelData else { return false }
        buffer.frameLength = AVAudioFrameCount(LanPacket.frames)
        let bytes = Array(pcm)
        for frame in 0..<LanPacket.frames {
            for channel in 0..<2 {
                let i = frame * 4 + channel * 2
                let bits = UInt16(bytes[i]) | (UInt16(bytes[i + 1]) << 8)
                channels[channel][frame] = Float(Int16(bitPattern: bits)) / 32768
            }
        }
        pendingBuffers += 1
        player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            DispatchQueue.main.async {
                guard let self = self, self.generation == id else { return }
                self.pendingBuffers = max(0, self.pendingBuffers - 1)
            }
        }
        if !player.isPlaying { player.play() }
        return true
    }

    private func publish(_ message: String, force: Bool = false) {
        guard force || Date().timeIntervalSince(lastPublish) >= 0.25 else { return }
        lastPublish = Date()
        status = message
        packetCount = count
        drops = rejected + playout.missing + playout.schedulerSkipped
    }

    func stop(_ message: String = "Stopped.") {
        guard active else { return }
        active = false
        generation = UUID()
        timeout?.cancel()
        timeout = nil
        playoutTimer?.cancel()
        playoutTimer = nil
        playout.discardPending()
        listener?.cancel()
        listener = nil
        for connection in connections.values { connection.cancel() }
        connections.removeAll()
        selected = nil
        expected = nil
        key = nil
        if let interruption = interruption { NotificationCenter.default.removeObserver(interruption) }
        interruption = nil
        player?.stop()
        engine?.stop()
        player = nil
        engine = nil
        format = nil
        pendingBuffers = 0
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        publish(message, force: true)
    }
}
