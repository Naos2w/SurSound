import CryptoKit
import Foundation
import XCTest
@testable import SurSoundReceiver

final class LanPacketTests: XCTestCase {
    func testAuthenticatedPacketAndReplayGuard() throws {
        let key = try XCTUnwrap(LanPacket.key("00112233445566778899aabbccddeeff"))
        let header = Data([0x53,0x53,0x50,0x30,1,1,2,3,4,5,6,7,8,0,0,0,42])
        let pcm = Data((0..<960).map { UInt8($0 % 251) })
        let nonce = try AES.GCM.Nonce(data: header.dropFirst(5))
        let sealed = try AES.GCM.seal(pcm, using: key, nonce: nonce, authenticating: header)
        let packet = header + sealed.ciphertext + sealed.tag
        XCTAssertEqual(packet.count, 993)
        XCTAssertEqual(try LanPacket.seal(pcm, key: key, session: 0x0102030405060708, sequence: 42), packet)
        let digest = SHA256.hash(data: packet).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(digest, "0d9cb39a5141757e2f4903d4ba8762840cbb82416acb5ccdea12496bc2259535")
        let decoded = try XCTUnwrap(LanPacket.decode(packet, key: key))
        XCTAssertEqual(decoded.session, 0x0102030405060708)
        XCTAssertEqual(decoded.sequence, 42)
        XCTAssertEqual(decoded.pcm, pcm)
        var guardState = LanPacket.ReplayGuard()
        XCTAssertTrue(guardState.accept(decoded))
        XCTAssertFalse(guardState.accept(decoded))
        XCTAssertTrue(guardState.accept(.init(session: decoded.session, sequence: 45, pcm: pcm)))
        XCTAssertEqual(guardState.missing, 2)
        XCTAssertFalse(guardState.accept(.init(session: 9, sequence: 46, pcm: pcm)))
        var tampered = packet
        tampered[20] ^= 1
        XCTAssertNil(LanPacket.decode(tampered, key: key))
        XCTAssertNil(LanPacket.decode(Data(packet.dropLast()), key: key))
        XCTAssertNil(LanPacket.decode(packet, key: SymmetricKey(size: .bits128)))
    }

    func testPairingValidation() {
        XCTAssertNil(LanPacket.key("1234"))
        XCTAssertNil(LanPacket.key(String(repeating: "g", count: 32)))
        XCTAssertNotNil(LanPacket.key(String(repeating: "A", count: 32)))
        XCTAssertNotNil(LanPacket.localPeer("192.168.1.10"))
        XCTAssertNotNil(LanPacket.localPeer("172.16.0.1"))
        XCTAssertNil(LanPacket.localPeer("8.8.8.8"))
        XCTAssertNil(LanPacket.localPeer("127.0.0.1"))
        XCTAssertNil(LanPacket.localPeer("example.com"))
    }
}
