import CryptoKit
import Foundation

/// V2 uses independently derived audio and clock keys. Wire fields are big endian.
struct SyncWire {
    static let request = UInt8(1)
    static let reply = UInt8(2)
    static let maxPackets: UInt32 = 6000
    static let leadNanos: UInt64 = 240_000_000
    static let audioBytes = 1001
    static let clockBytes = 77
    private let audioKey: SymmetricKey
    private let controlKey: SymmetricKey

    struct Audio {
        let session: UInt64
        let sequence: UInt32
        let origin: Int64
        let pcm: Data
    }

    struct Clock {
        let kind: UInt8
        let session: UInt64
        let nonce: UInt64
        let t1: Int64
        let t2: Int64
        let t3: Int64
    }

    init(key: SymmetricKey) {
        audioKey = SymmetricKey(data: Data(HMAC<SHA256>.authenticationCode(
            for: Data("SurSound/sync/audio/v2".utf8), using: key)).prefix(16))
        controlKey = SymmetricKey(data: Data(HMAC<SHA256>.authenticationCode(
            for: Data("SurSound/sync/control/v2".utf8), using: key)))
    }

    private static func append(_ value: UInt64, bytes: Int, to data: inout Data) {
        for shift in stride(from: (bytes - 1) * 8, through: 0, by: -8) {
            data.append(UInt8(truncatingIfNeeded: value >> shift))
        }
    }

    private static func value(_ bytes: [UInt8], _ start: Int, _ count: Int) -> UInt64 {
        bytes[start..<(start + count)].reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
    }

    /// Seal once per sequence and fan out the exact same authenticated datagram.
    func sealAudio(session: UInt64, sequence: UInt32, origin: Int64, pcm: Data) throws -> Data {
        guard sequence < Self.maxPackets, pcm.count == LanPacket.pcmBytes else {
            throw NSError(domain: "SurSound.SyncWire", code: 1)
        }
        var header = Data([0x53, 0x53, 0x50, 0x32, 2])
        Self.append(session, bytes: 8, to: &header)
        Self.append(UInt64(sequence), bytes: 4, to: &header)
        Self.append(UInt64(bitPattern: origin), bytes: 8, to: &header)
        let nonce = try AES.GCM.Nonce(data: header[5..<17])
        let box = try AES.GCM.seal(pcm, using: audioKey, nonce: nonce, authenticating: header)
        return header + box.ciphertext + box.tag
    }

    func openAudio(_ data: Data) -> Audio? {
        guard data.count == Self.audioBytes else { return nil }
        let b = Array(data)
        guard Array(b[0..<5]) == [0x53, 0x53, 0x50, 0x32, 2] else { return nil }
        let session = Self.value(b, 5, 8)
        let sequence = UInt32(Self.value(b, 13, 4))
        let origin = Int64(bitPattern: Self.value(b, 17, 8))
        guard sequence < Self.maxPackets else { return nil }
        do {
            let nonce = try AES.GCM.Nonce(data: Data(b[5..<17]))
            let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: Data(b[25..<985]), tag: Data(b[985..<1001]))
            let pcm = try AES.GCM.open(box, using: audioKey, authenticating: Data(b[0..<25]))
            return Audio(session: session, sequence: sequence, origin: origin, pcm: pcm)
        } catch { return nil }
    }

    func sealClock(_ clock: Clock) -> Data {
        precondition(clock.kind == Self.request || clock.kind == Self.reply)
        precondition(clock.kind != Self.request || (clock.t2 == 0 && clock.t3 == 0))
        var header = Data([0x53, 0x53, 0x43, 0x32, clock.kind])
        for value in [clock.session, clock.nonce, UInt64(bitPattern: clock.t1),
                      UInt64(bitPattern: clock.t2), UInt64(bitPattern: clock.t3)] {
            Self.append(value, bytes: 8, to: &header)
        }
        return header + Data(HMAC<SHA256>.authenticationCode(for: header, using: controlKey))
    }

    func openClock(_ data: Data) -> Clock? {
        guard data.count == Self.clockBytes else { return nil }
        let b = Array(data)
        guard HMAC<SHA256>.isValidAuthenticationCode(Data(b[45..<77]), authenticating: Data(b[0..<45]), using: controlKey),
              Array(b[0..<4]) == [0x53, 0x53, 0x43, 0x32],
              b[4] == Self.request || b[4] == Self.reply else { return nil }
        let clock = Clock(kind: b[4], session: Self.value(b, 5, 8), nonce: Self.value(b, 13, 8),
                          t1: Int64(bitPattern: Self.value(b, 21, 8)), t2: Int64(bitPattern: Self.value(b, 29, 8)),
                          t3: Int64(bitPattern: Self.value(b, 37, 8)))
        guard clock.kind != Self.request || (clock.t2 == 0 && clock.t3 == 0) else { return nil }
        return clock
    }
}
