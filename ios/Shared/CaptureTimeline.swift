import Foundation
import Network

/// Reserve slots at PCM packetization, not after network queue admission.
struct CaptureTimeline {
    struct Slot {
        let sequence: UInt32
        let origin: Int64
        var deadline: UInt64 {
            UInt64(origin) + UInt64(sequence) * 5_000_000
        }
        func expired(now: UInt64) -> Bool { now >= deadline }
    }

    private var origin: Int64?
    private var next: UInt32 = 0

    mutating func reserve(now: UInt64) -> Slot? {
        guard next < SyncWire.maxPackets,
              now <= UInt64(Int64.max) - SyncWire.leadNanos - UInt64(SyncWire.maxPackets) * 5_000_000
        else { return nil }
        let epoch = origin ?? Int64(now + SyncWire.leadNanos)
        origin = epoch
        let slot = Slot(sequence: next, origin: epoch)
        next += 1
        return slot
    }
}

func capturePeers(_ text: String, group: Bool) -> [IPv4Address]? {
    let parts = group ? text.split(separator: ",", omittingEmptySubsequences: false).map(String.init) : [text]
    guard (1...4).contains(parts.count) else { return nil }
    let addresses = parts.compactMap { LanPacket.localPeer($0) }
    guard addresses.count == parts.count,
          Set(addresses.map { String(describing: $0) }).count == addresses.count else { return nil }
    return addresses
}
