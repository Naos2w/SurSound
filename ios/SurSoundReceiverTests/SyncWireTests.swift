import CryptoKit
import Foundation
import XCTest
@testable import SurSoundReceiver

final class SyncWireTests: XCTestCase {
    private let key = LanPacket.key("00112233445566778899aabbccddeeff")!
    private let session: UInt64 = 0x0102030405060708
    private let pcm = Data((0..<960).map { UInt8($0 % 251) })
    private func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

    func testCrossPlatformVectorsAndAuthentication() throws {
        let wire = SyncWire(key: key)
        let data = try wire.sealAudio(session: session, sequence: 42, origin: 250_000_000, pcm: pcm)
        XCTAssertEqual(data.count, 1001)
        XCTAssertEqual(hex(Data(SHA256.hash(data: data))), "c9281166d6b1a89940f2c910905e5cf6bec094a5d1903b9825900938a2eb9f83")
        let decoded = try XCTUnwrap(wire.openAudio(data))
        XCTAssertEqual(decoded.session, session)
        XCTAssertEqual(decoded.sequence, 42)
        XCTAssertEqual(decoded.origin, 250_000_000)
        XCTAssertEqual(decoded.pcm, pcm)
        XCTAssertNil(LanPacket.decode(data, key: key))
        XCTAssertNil(SyncWire(key: SymmetricKey(data: Data(repeating: 0, count: 16))).openAudio(data))
        for index in [5,16,17,25,data.count-1] {
            var changed = data
            changed[index] ^= 1
            XCTAssertNil(wire.openAudio(changed))
        }
        let request = SyncWire.Clock(kind: SyncWire.request, session: session, nonce: 0x1112131415161718,
                                     t1: 1_000_000_000, t2: 0, t3: 0)
        let clock = wire.sealClock(request)
        XCTAssertEqual(clock.count, 77)
        XCTAssertEqual(hex(clock), "535343320101020304050607081112131415161718000000003b9aca0000000000000000000000000000000000e2895c6ec4161c010a76eb566e0ea48ee9c83cc456d67d9297c93ee7a0daadec")
        XCTAssertEqual(wire.openClock(clock)?.nonce, request.nonce)
        XCTAssertNil(SyncWire(key: SymmetricKey(data: Data(repeating: 0, count: 16))).openClock(clock))
        var changed = clock
        changed[29] = 1
        XCTAssertNil(wire.openClock(changed))
    }

    func testTwoReceiverClocksMapSamePresentationAndRejectReplay() throws {
        let wire = SyncWire(key: key)
        let origin: Int64 = 1_340_000_000
        for (arrival, offset) in [(UInt64(1_000_000_000), Int64(100_000_000)), (UInt64(500_000_000), Int64(600_000_000))] {
            var group = GroupPlayout(wire: wire)
            XCTAssertTrue(group.offer(.init(session: session, sequence: 0, origin: origin, pcm: pcm), now: arrival))
            XCTAssertTrue(group.offer(.init(session: session, sequence: 2, origin: origin, pcm: pcm), now: arrival + 1_000_000))
            XCTAssertFalse(group.offer(.init(session: session, sequence: 2, origin: origin, pcm: pcm), now: arrival + 1_000_000))
            XCTAssertFalse(group.offer(.init(session: session, sequence: 1, origin: origin + 1, pcm: pcm), now: arrival + 1_000_000))
            XCTAssertNil(group.poll(now: arrival + 2_000_000))
            for index in 0..<3 {
                let t1 = arrival + UInt64(index) * 30_000_000
                let probe = try XCTUnwrap(group.nextProbe(now: t1))
                let req = try XCTUnwrap(wire.openClock(probe))
                let t2 = Int64(t1) + offset + 2_000_000
                let t3 = t2 + 1_000_000
                XCTAssertFalse(group.acceptReply(.init(kind: SyncWire.reply, session: session, nonce: req.nonce ^ 1,
                    t1: req.t1, t2: t2, t3: t3), now: t1 + 5_000_000))
                let reply = SyncWire.Clock(kind: SyncWire.reply, session: session, nonce: req.nonce,
                                           t1: req.t1, t2: t2, t3: t3)
                XCTAssertTrue(group.acceptReply(reply, now: t1 + 5_000_000))
                XCTAssertFalse(group.acceptReply(reply, now: t1 + 6_000_000))
            }
            XCTAssertTrue(group.ready)
            XCTAssertEqual(try XCTUnwrap(group.roundTripNanos), 4_000_000, accuracy: 0.01)
            let local = UInt64(origin - offset)
            XCTAssertNil(group.poll(now: local - 1))
            XCTAssertEqual(group.poll(now: local)?.pcm, pcm)
            XCTAssertNil(try XCTUnwrap(group.poll(now: local + 5_000_000)).pcm)
            XCTAssertEqual(group.poll(now: local + 10_000_000)?.pcm, pcm)
            XCTAssertEqual(group.missing, 1)
        }
    }

    func testNoClockAndBoundedPreClockWindow() {
        let wire = SyncWire(key: key)
        var group = GroupPlayout(wire: wire)
        for sequence in 0..<64 {
            XCTAssertTrue(group.offer(.init(session: session, sequence: UInt32(sequence), origin: 240_000_000, pcm: pcm), now: 0))
        }
        XCTAssertFalse(group.offer(.init(session: session, sequence: 64, origin: 240_000_000, pcm: pcm), now: 0))
        for index in 0..<12 { XCTAssertNotNil(group.nextProbe(now: UInt64(index) * 100_000_000)) }
        XCTAssertNil(group.poll(now: 1_300_000_000))
        XCTAssertFalse(group.ready)
        XCTAssertNil(group.nextProbe(now: 1_400_000_000))
    }

    func testFinalSlotStops() throws {
        let wire = SyncWire(key: key)
        var group = GroupPlayout(wire: wire)
        let seq = SyncWire.maxPackets - 1
        let origin = Int64(240_000_000) - Int64(seq) * 5_000_000
        XCTAssertTrue(group.offer(.init(session: session, sequence: seq, origin: origin, pcm: pcm), now: 0))
        for i in 0..<3 {
            let t1 = UInt64(i) * 30_000_000
            let req = try XCTUnwrap(wire.openClock(try XCTUnwrap(group.nextProbe(now: t1))))
            XCTAssertTrue(group.acceptReply(.init(kind: SyncWire.reply, session: session, nonce: req.nonce,
                t1: req.t1, t2: req.t1 + 2_000_000, t3: req.t1 + 3_000_000), now: t1 + 5_000_000))
        }
        XCTAssertEqual(group.poll(now: 240_000_000)?.sequence, Int64(seq))
        XCTAssertNil(group.poll(now: 245_000_000))
        XCTAssertNil(group.poll(now: 250_000_000))
    }
}
