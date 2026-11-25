import Foundation
import CryptoKit
import Network

/// Short-lived, single-use handoff. Only the two signed App Group targets can read it.
struct BroadcastRequest: Codable {
    static let group = "group.com.naos.sursound"
    let peer: String
    let secret: String
    let expiresAt: Date

    var valid: Bool {
        let remaining = expiresAt.timeIntervalSinceNow
        return remaining > 0 && remaining <= 120 && LanPacket.localPeer(peer) != nil && LanPacket.key(secret) != nil
    }

    private static func file() throws -> URL {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else {
            throw NSError(domain: "SurSound.Broadcast", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "The App Group entitlement is not provisioned."
            ])
        }
        return container.appendingPathComponent("pending-broadcast.json")
    }

    static func prepare(peer: String, secret: String) throws {
        let request = BroadcastRequest(peer: peer, secret: secret, expiresAt: Date().addingTimeInterval(90))
        guard request.valid else {
            throw NSError(domain: "SurSound.Broadcast", code: 2, userInfo: [NSLocalizedDescriptionKey: "Invalid peer or fresh key."])
        }
        let url = try file()
        try JSONEncoder().encode(request).write(to: url, options: [.atomic, .completeFileProtection])
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutable = url
        try mutable.setResourceValues(values)
    }

    static func take() throws -> BroadcastRequest {
        let url = try file()
        // Rename first to claim atomically; never reuse a previously prepared request.
        let claimed = url.deletingLastPathComponent().appendingPathComponent("claimed-" + UUID().uuidString)
        try FileManager.default.moveItem(at: url, to: claimed)
        defer { try? FileManager.default.removeItem(at: claimed) }
        let request = try JSONDecoder().decode(Self.self, from: Data(contentsOf: claimed))
        guard request.valid else {
            throw NSError(domain: "SurSound.Broadcast", code: 3, userInfo: [NSLocalizedDescriptionKey: "Pairing request expired. Prepare a new session in SurSound."])
        }
        return request
    }

    static func cancel() {
        if let url = try? file() { try? FileManager.default.removeItem(at: url) }
    }
}
