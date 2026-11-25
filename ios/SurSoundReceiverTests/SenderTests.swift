import CryptoKit
import Foundation
import XCTest
@testable import SurSoundReceiver

final class SenderTests: XCTestCase {
    func testEncoderSequencesAndAuthentication() throws {
        let key = try XCTUnwrap(LanPacket.key("00112233445566778899aabbccddeeff"))
        var encoder = LanPacket.Encoder(key: key)
        let pcm = Data(repeating: 0x12, count: LanPacket.pcmBytes)
        let first = try XCTUnwrap(LanPacket.decode(try encoder.encode(pcm), key: key))
        let second = try XCTUnwrap(LanPacket.decode(try encoder.encode(pcm), key: key))
        XCTAssertEqual(first.session, second.session)
        XCTAssertEqual(first.sequence, 0)
        XCTAssertEqual(second.sequence, 1)
        XCTAssertEqual(second.pcm, pcm)
        XCTAssertThrowsError(try encoder.encode(Data()))
    }

    func testStereoPacketizerAndReset() throws {
        var packetizer = StereoPacketizer()
        for _ in 0..<(LanPacket.frames - 1) {
            XCTAssertNil(packetizer.append(left: -1, right: 1))
        }
        let packet = try XCTUnwrap(packetizer.append(left: -1, right: 1))
        XCTAssertEqual(packet.count, 960)
        XCTAssertEqual(Array(packet.prefix(4)), [0, 128, 255, 127])
        XCTAssertEqual(Array(packet.suffix(4)), [0, 128, 255, 127])
        XCTAssertNil(packetizer.append(left: .nan, right: .infinity))
        packetizer.clear()
        for _ in 0..<(LanPacket.frames - 1) {
            XCTAssertNil(packetizer.append(left: 0, right: 0))
        }
        let silence = try XCTUnwrap(packetizer.append(left: 0, right: 0))
        XCTAssertEqual(silence, Data(repeating: 0, count: 960))
    }

    func testPairingRequestExpiresAndRejectsPublicPeers() {
        let key = "00112233445566778899aabbccddeeff"
        XCTAssertTrue(BroadcastRequest(peer: "192.168.1.20", secret: key, expiresAt: Date().addingTimeInterval(60)).valid)
        XCTAssertFalse(BroadcastRequest(peer: "192.168.1.20", secret: key, expiresAt: Date().addingTimeInterval(-1)).valid)
        XCTAssertFalse(BroadcastRequest(peer: "192.168.1.20", secret: key, expiresAt: Date().addingTimeInterval(600)).valid)
        XCTAssertFalse(BroadcastRequest(peer: "8.8.8.8", secret: key, expiresAt: Date().addingTimeInterval(60)).valid)
        XCTAssertFalse(BroadcastRequest(peer: "192.168.1.20", secret: "bad", expiresAt: Date().addingTimeInterval(60)).valid)
    }
}
