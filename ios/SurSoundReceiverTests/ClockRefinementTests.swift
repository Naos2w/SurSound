import CryptoKit
import Foundation
import XCTest
@testable import SurSoundReceiver

final class ClockRefinementTests: XCTestCase {
    private let wire = SyncWire(key: SymmetricKey(data: Data((0..<16).map { UInt8($0) })))
    private let session: UInt64 = 27
    private let arrival: UInt64 = 1_000_000_000
    private let baseOffset: Int64 = 100_000_000
    private let origin: Int64 = 1_340_000_000
    private var localStart: UInt64 { UInt64(origin - baseOffset) }

    private func reply(_ request: SyncWire.Clock, offset: Int64, path: Int64 = 2_000_000) -> SyncWire.Clock {
        SyncWire.Clock(kind: SyncWire.reply, session: request.session, nonce: request.nonce,
            t1: request.t1, t2: request.t1 + offset + path, t3: request.t1 + offset + path + 1_000_000)
    }

    private func initial() throws -> GroupPlayout {
        var group = GroupPlayout(wire: wire)
        XCTAssertTrue(group.offer(.init(session: session, sequence: 0, origin: origin,
            pcm: Data(repeating: 0, count: LanPacket.pcmBytes)), now: arrival))
        for i in 0..<3 {
            let t = arrival + UInt64(i) * 30_000_000
            let request = try XCTUnwrap(wire.openClock(try XCTUnwrap(group.nextProbe(now: t))))
            XCTAssertTrue(group.acceptReply(reply(request, offset: baseOffset), now: t + 5_000_000))
        }
        XCTAssertTrue(group.ready)
        return group
    }

    func testPositiveAndNegativeClockCorrectionIsBoundedAndPreservesSlots() throws {
        for change in [Int64(1_000_000), Int64(-1_000_000)] {
            var group = try initial()
            let probeTime = arrival + 1_060_000_000
            XCTAssertNil(group.nextProbe(now: probeTime - 1))
            let request = try XCTUnwrap(wire.openClock(try XCTUnwrap(group.nextProbe(now: probeTime))))
            let updated = reply(request, offset: baseOffset + change)
            let replyTime = probeTime + 5_000_000
            var t = localStart
            var sequence: Int64 = 0
            while t < replyTime {
                XCTAssertEqual(group.poll(now: t)?.sequence, sequence)
                sequence += 1
                t += ReceiverPlayoutBuffer.frameNanos
            }
            XCTAssertTrue(group.acceptReply(updated, now: replyTime))
            XCTAssertFalse(group.acceptReply(updated, now: replyTime + 1))
            XCTAssertEqual(group.refinementCount, 1)
            XCTAssertEqual(group.residualOffsetNanos, Double(change), accuracy: 0.01)
            let step: Int64 = change > 0 ? 10_000 : -10_000
            for _ in 0..<100 {
                XCTAssertNil(group.poll(now: t - 1))
                XCTAssertEqual(group.poll(now: t)?.sequence, sequence)
                sequence += 1
                XCTAssertNil(group.poll(now: t))
                t += UInt64(Int64(ReceiverPlayoutBuffer.frameNanos) - step)
            }
            XCTAssertEqual(group.residualOffsetNanos, 0, accuracy: 0.01)
            XCTAssertEqual(group.schedulerSkipped, 0)
            XCTAssertEqual(group.maxDispatchLatenessNanos, 0)
            XCTAssertEqual(group.poll(now: t)?.sequence, sequence)
        }
    }

    func testHighRttAndOutlierDoNotResetClockAgeOrTarget() throws {
        var group = try initial()
        let t = arrival + 1_060_000_000
        let slow = try XCTUnwrap(wire.openClock(try XCTUnwrap(group.nextProbe(now: t))))
        XCTAssertFalse(group.acceptReply(reply(slow, offset: baseOffset, path: 15_000_000), now: t + 31_000_000))
        let t2 = t + 1_000_000_000
        let bad = try XCTUnwrap(wire.openClock(try XCTUnwrap(group.nextProbe(now: t2))))
        XCTAssertFalse(group.acceptReply(reply(bad, offset: baseOffset + 30_000_000), now: t2 + 5_000_000))
        XCTAssertEqual(group.refinementCount, 0)
        XCTAssertEqual(group.residualOffsetNanos, 0, accuracy: 0.01)
        XCTAssertEqual(group.clockAgeNanos(now: t2 + 5_000_000), t2 + 5_000_000 - (arrival + 65_000_000))
    }

    func testDispatchStallIsReportedWithoutBurst() throws {
        var group = try initial()
        XCTAssertEqual(group.poll(now: localStart)?.sequence, 0)
        XCTAssertEqual(group.poll(now: localStart + 27_000_000)?.sequence, 5)
        XCTAssertEqual(group.maxDispatchLatenessNanos, 22_000_000)
        XCTAssertEqual(group.schedulerSkipped, 4)
        XCTAssertNil(group.poll(now: localStart + 27_000_000))
    }
}
