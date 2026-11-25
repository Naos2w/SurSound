import Foundation
import XCTest
@testable import SurSoundReceiver

final class CaptureTimelineTests: XCTestCase {
    func testReservationsPreserveDroppedSlotsAndCommonEpoch() throws {
        var timeline = CaptureTimeline()
        let first = try XCTUnwrap(timeline.reserve(now: 1_000_000_000))
        let discarded = try XCTUnwrap(timeline.reserve(now: 1_010_000_000))
        let third = try XCTUnwrap(timeline.reserve(now: 1_020_000_000))
        XCTAssertEqual(first.sequence, 0)
        XCTAssertEqual(discarded.sequence, 1)
        XCTAssertEqual(third.sequence, 2)
        XCTAssertEqual(first.origin, 1_240_000_000)
        XCTAssertEqual(third.origin, first.origin)
        XCTAssertEqual(third.deadline - first.deadline, 10_000_000)
        XCTAssertFalse(third.expired(now: third.deadline - 1))
        XCTAssertTrue(third.expired(now: third.deadline))
    }

    func testSequenceLimitAndPacketizerGap() throws {
        var timeline = CaptureTimeline()
        var packetizer = StereoPacketizer()
        var admitted: [UInt32] = []
        for index in 0..<(LanPacket.frames * 3) {
            if let pcm = packetizer.append(left: 0.1, right: -0.1) {
                XCTAssertEqual(pcm.count, LanPacket.pcmBytes)
                let slot = try XCTUnwrap(timeline.reserve(now: 0))
                if index / LanPacket.frames != 1 { admitted.append(slot.sequence) }
            }
        }
        XCTAssertEqual(admitted, [0, 2])
        for sequence in 3..<SyncWire.maxPackets {
            XCTAssertEqual(timeline.reserve(now: 0)?.sequence, sequence)
        }
        XCTAssertNil(timeline.reserve(now: 0))
    }

    func testGroupRequestValidationAndLegacyDecode() throws {
        let key = "00112233445566778899aabbccddeeff"
        let expires = Date().addingTimeInterval(60)
        let list = "192.168.1.2, 192.168.1.3, 10.0.0.4, 172.16.1.5"
        XCTAssertEqual(capturePeers(list, group: true)?.count, 4)
        XCTAssertTrue(BroadcastRequest(peer: list, secret: key, expiresAt: expires, groupMode: true).valid)
        XCTAssertFalse(BroadcastRequest(peer: list, secret: key, expiresAt: expires).valid)
        for invalid in ["192.168.1.2,192.168.1.2", "192.168.1.2,8.8.8.8", "192.168.1.2,",
                        "192.168.1.2,192.168.1.3,192.168.1.4,192.168.1.5,192.168.1.6", ""] {
            XCTAssertNil(capturePeers(invalid, group: true))
        }
        struct Legacy: Encodable {
            let peer: String
            let secret: String
            let expiresAt: Date
        }
        let encoded = try JSONEncoder().encode(Legacy(peer: "192.168.1.2", secret: key, expiresAt: expires))
        let decoded = try JSONDecoder().decode(BroadcastRequest.self, from: encoded)
        XCTAssertNil(decoded.groupMode)
        XCTAssertTrue(decoded.valid)
        let group = BroadcastRequest(peer: list, secret: key, expiresAt: expires, groupMode: true)
        let roundtrip = try JSONDecoder().decode(BroadcastRequest.self, from: JSONEncoder().encode(group))
        XCTAssertEqual(roundtrip.groupMode, true)
        XCTAssertTrue(roundtrip.valid)
    }
}
