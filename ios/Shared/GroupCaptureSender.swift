import CryptoKit
import Foundation
import Network

/// Called by ReplayKit's capture callback. Network state and sends are confined to
/// a separate serial queue. No unbounded async PCM work is submitted.
final class GroupCaptureSender {
    private let queue = DispatchQueue(label: "sursound.capture.group")
    private var transport: GroupToneTransport?
    private var timeline = CaptureTimeline()
    private var skipped: UInt32 = 0
    private var failed: String?

    init(addresses: [IPv4Address], key: SymmetricKey) {
        queue.sync {
            transport = GroupToneTransport(addresses: addresses, wire: SyncWire(key: key), queue: queue)
        }
    }

    func offer(_ pcm: Data) {
        queue.sync {
            guard let output = transport, failed == nil else { return }
            let now = DispatchTime.now().uptimeNanoseconds
            guard let slot = timeline.reserve(now: now) else {
                failed = "Group capture timeline limit reached."
                output.stop()
                return
            }
            // Even if we discard this block, its reserved slot must not be reused.
            if slot.expired(now: now) {
                skipped += 1
                return
            }
            do {
                try output.send(sequence: slot.sequence, origin: slot.origin, pcm: pcm, skipped: skipped)
                skipped = 0
            } catch {
                failed = "Group audio packet encoding failed."
                output.stop()
            }
        }
    }

    func failure() -> String? { queue.sync { failed } }

    func stop() {
        queue.sync {
            transport?.stop()
            transport = nil
        }
    }
}
