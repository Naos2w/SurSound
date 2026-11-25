import Foundation
import XCTest
@testable import SurSoundReceiver

final class PlaybackTimingTests: XCTestCase {
    private func pcm(_ value: UInt8 = 0) -> Data { Data(repeating: value, count: LanPacket.pcmBytes) }

    func testClockOffsetAndMinimumRttWindow() throws {
        var clock = ClockOffsetEstimator(capacity: 2)
        XCTAssertNil(clock.estimate())
        XCTAssertTrue(clock.add(.init(t1: 0, t2: 102_000_000, t3: 103_000_000, t4: 5_000_000)))
        let first = try XCTUnwrap(clock.estimate())
        XCTAssertEqual(first.senderMinusReceiverNanos, 100_000_000, accuracy: 0.01)
        XCTAssertEqual(first.roundTripNanos, 4_000_000, accuracy: 0.01)
        XCTAssertEqual(first.pathUncertaintyNanos, 2_000_000, accuracy: 0.01)
        XCTAssertEqual(first.receiverTimeFor(senderTimeNanos: 150_000_000), 50_000_000, accuracy: 0.01)
        XCTAssertTrue(clock.add(.init(t1: 10_000_000, t2: 112_000_000, t3: 113_000_000, t4: 21_000_000)))
        XCTAssertEqual(clock.estimate()?.senderMinusReceiverNanos, first.senderMinusReceiverNanos)
        XCTAssertFalse(clock.add(.init(t1: 22_000_000, t2: 125_000_000, t3: 135_000_000, t4: 23_000_000)))
        XCTAssertFalse(clock.add(.init(t1: 1, t2: 2, t3: 3, t4: 4)))
        XCTAssertTrue(clock.add(.init(t1: 30_000_000, t2: 133_000_000, t3: 134_000_000, t4: 37_000_000)))
        XCTAssertEqual(clock.estimate()?.roundTripNanos, 6_000_000)
    }

    func testReorderBeforeDeadlineAndConcealMissing() throws {
        var buffer = ReceiverPlayoutBuffer()
        XCTAssertTrue(buffer.offer(sessionId: 7, sequence: 100, pcm: pcm(1), nowNanos: 0))
        XCTAssertTrue(buffer.offer(sessionId: 7, sequence: 102, pcm: pcm(3), nowNanos: 1_000_000))
        XCTAssertTrue(buffer.offer(sessionId: 7, sequence: 101, pcm: pcm(2), nowNanos: 2_000_000))
        XCTAssertFalse(buffer.offer(sessionId: 7, sequence: 101, pcm: pcm(), nowNanos: 3_000_000))
        XCTAssertFalse(buffer.offer(sessionId: 8, sequence: 103, pcm: pcm(), nowNanos: 3_000_000))
        XCTAssertNil(buffer.poll(nowNanos: 39_999_999))
        XCTAssertEqual(buffer.poll(nowNanos: 40_000_000)?.pcm, pcm(1))
        XCTAssertEqual(buffer.poll(nowNanos: 45_000_000)?.pcm, pcm(2))
        XCTAssertEqual(buffer.poll(nowNanos: 50_000_000)?.pcm, pcm(3))
        XCTAssertNil(try XCTUnwrap(buffer.poll(nowNanos: 55_000_000)).pcm)
        XCTAssertEqual(buffer.missing, 1)
        XCTAssertFalse(buffer.offer(sessionId: 7, sequence: 103, pcm: pcm(), nowNanos: 56_000_000))
    }

    func testBoundedQueueAndSchedulerStallNeverBurst() throws {
        var buffer = ReceiverPlayoutBuffer(windowPackets: 4)
        for i in 0..<4 {
            XCTAssertTrue(buffer.offer(sessionId: 5, sequence: UInt32(i), pcm: pcm(UInt8(i)), nowNanos: 0))
        }
        XCTAssertFalse(buffer.offer(sessionId: 5, sequence: 4, pcm: pcm(), nowNanos: 0))
        XCTAssertEqual(buffer.queued, 4)
        let block = try XCTUnwrap(buffer.poll(nowNanos: 55_000_000))
        XCTAssertEqual(block.sequence, 3)
        XCTAssertEqual(buffer.schedulerSkipped, 3)
        XCTAssertEqual(block.pcm, pcm(3))
        XCTAssertNil(buffer.poll(nowNanos: 55_000_000))
        XCTAssertTrue(buffer.offer(sessionId: 5, sequence: 4, pcm: pcm(), nowNanos: 56_000_000))
        buffer.discardPending()
        XCTAssertEqual(buffer.queued, 0)
        XCTAssertFalse(buffer.offer(sessionId: 5, sequence: 2, pcm: pcm(), nowNanos: 57_000_000))
    }
}
