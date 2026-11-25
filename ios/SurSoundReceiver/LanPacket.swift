import CryptoKit
import Foundation
import Network

enum LanPacket {
    static let port: UInt16 = 39771
    static let frames = 240
    static let pcmBytes = 960
    static let packetBytes = 993

    struct Decoded {
        let session: UInt64
        let sequence: UInt32
        let pcm: Data
    }

    static func key(_ value: String) -> SymmetricKey? {
        let chars = Array(value.trimmingCharacters(in: .whitespacesAndNewlines).utf8)
        guard chars.count == 32 else { return nil }
        func digit(_ x: UInt8) -> UInt8? {
            switch x {
            case 48...57: return x - 48
            case 65...70: return x - 55
            case 97...102: return x - 87
            default: return nil
            }
        }
        var bytes = [UInt8]()
        for i in stride(from: 0, to: chars.count, by: 2) {
            guard let a = digit(chars[i]), let b = digit(chars[i+1]) else { return nil }
            bytes.append(a * 16 + b)
        }
        return SymmetricKey(data: bytes)
    }

    static func localPeer(_ value: String) -> IPv4Address? {
        let parts = value.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4,
              parts.allSatisfy({ (1...3).contains($0.utf8.count) && $0.utf8.allSatisfy { (48...57).contains($0) } }) else { return nil }
        let a = parts.compactMap { UInt8($0) }
        guard a.count == 4,
              a[0] == 10 || (a[0] == 172 && (16...31).contains(a[1])) ||
                (a[0] == 192 && a[1] == 168) || (a[0] == 169 && a[1] == 254)
        else { return nil }
        return IPv4Address(a.map(String.init).joined(separator: "."))
    }

    static func decode(_ data: Data, key: SymmetricKey) -> Decoded? {
        guard data.count == packetBytes else { return nil }
        let bytes = Array(data)
        guard Array(bytes[0..<5]) == [0x53, 0x53, 0x50, 0x30, 1] else { return nil }
        let session = bytes[5..<13].reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        let sequence = bytes[13..<17].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard sequence < UInt32(Int32.max) else { return nil }
        do {
            let nonce = try AES.GCM.Nonce(data: Data(bytes[5..<17]))
            let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: Data(bytes[17..<977]), tag: Data(bytes[977..<993]))
            let pcm = try AES.GCM.open(box, using: key, authenticating: Data(bytes[0..<17]))
            guard pcm.count == pcmBytes else { return nil }
            return Decoded(session: session, sequence: sequence, pcm: pcm)
        } catch { return nil }
    }

    static func seal(_ pcm: Data, key: SymmetricKey, session: UInt64, sequence: UInt32) throws -> Data {
        guard pcm.count == pcmBytes, sequence < UInt32(Int32.max) else {
            throw NSError(domain: "SurSound.Packet", code: 1)
        }
        var header = Data([0x53, 0x53, 0x50, 0x30, 1])
        for shift in stride(from: 56, through: 0, by: -8) {
            header.append(UInt8(truncatingIfNeeded: session >> shift))
        }
        for shift in stride(from: 24, through: 0, by: -8) {
            header.append(UInt8(truncatingIfNeeded: sequence >> shift))
        }
        let nonce = try AES.GCM.Nonce(data: header.dropFirst(5))
        let box = try AES.GCM.seal(pcm, using: key, nonce: nonce, authenticating: header)
        return header + box.ciphertext + box.tag
    }

    struct Encoder {
        let key: SymmetricKey
        let session = UInt64.random(in: UInt64.min...UInt64.max)
        private var sequence: UInt32 = 0

        init(key: SymmetricKey) { self.key = key }

        mutating func encode(_ pcm: Data) throws -> Data {
            let result = try LanPacket.seal(pcm, key: key, session: session, sequence: sequence)
            sequence += 1
            return result
        }
    }

    struct ReplayGuard {
        private var session: UInt64?
        private var highest: UInt32 = 0
        private(set) var missing: UInt64 = 0
        mutating func accept(_ packet: Decoded) -> Bool {
            guard let locked = session else {
                session = packet.session
                highest = packet.sequence
                return true
            }
            guard locked == packet.session, packet.sequence > highest else { return false }
            missing += UInt64(packet.sequence) - UInt64(highest) - 1
            highest = packet.sequence
            return true
        }
    }
}
