"""Bounded shared-clock timeline and software output timing (not acoustic calibration)."""
from collections import deque
from dataclasses import dataclass
import secrets
from .group_wire import ClockPacket, REQUEST, REPLY, FRAME_NS, MAX_PACKETS, PCM_BYTES

SILENCE = bytes(PCM_BYTES)
NS = 1_000_000_000
WINDOW = 128
CHUNK = 4   # four 5 ms packets
QUEUE_TARGET = 4
PREPARE_NS = 40_000_000
QUEUE_AHEAD_NS = 90_000_000
CLOCK_STALE_NS = 3 * NS
DATA_STALE_NS = NS

@dataclass(frozen=True)
class Estimate:
    offset: float
    rtt: int

class ClockEstimator:
    def __init__(self, capacity):
        self.samples = deque(maxlen=capacity)
        self.last = None

    def add(self, c, t4, max_rtt=100_000_000):
        if t4 < c.t1 or c.t3 < c.t2 or (self.last is not None and t4 <= self.last):
            return None
        elapsed = t4 - c.t1
        rtt = elapsed - (c.t3 - c.t2)
        if elapsed > 100_000_000 or not 0 <= rtt <= max_rtt:
            return None
        result = Estimate(((c.t2-c.t1)+(c.t3-t4))/2, rtt)
        self.samples.append(result)
        self.last = t4
        return result

    def best(self):
        return min(reversed(self.samples), key=lambda x: x.rtt) if self.samples else None

@dataclass(frozen=True)
class Chunk:
    sequence: int
    packets: int
    pcm: bytes

class GroupTimeline:
    def __init__(self, wire):
        self.wire = wire
        self.session = self.origin = self.expected = self.first_arrival = None
        self.last_audio = self.last_clock = self.last_probe = self.outstanding = None
        self.initial = ClockEstimator(12)
        self.refinement = ClockEstimator(4)
        self.offset = self.rtt = None
        self.attempts = self.refinements = 0
        self.pending = {}
        self.start_sequence = self.start_deadline = None
        self.started = False
        self.failure = None
        self.missing = self.start_skipped = self.accepted = self.rejected = 0
        self.last_received = None

    @property
    def ready(self):
        return self.start_deadline is not None and self.failure is None

    def deadline(self, seq):
        return int(self.origin - self.offset + seq * FRAME_NS)

    def fail(self, reason):
        if self.failure is None:
            self.failure = reason
            self.pending.clear()

    def offer(self, audio, now):
        if self.failure or not 0 <= audio.sequence < MAX_PACKETS or len(audio.pcm) != PCM_BYTES:
            self.rejected += 1
            return False
        if self.session is None:
            self.session, self.origin, self.expected = audio.session, audio.origin, audio.sequence
            self.first_arrival = now
        if (audio.session != self.session or audio.origin != self.origin or
                audio.sequence < self.expected or audio.sequence >= self.expected + WINDOW or
                audio.sequence in self.pending):
            self.rejected += 1
            return False
        self.pending[audio.sequence] = audio.pcm
        self.last_received = max(self.last_received if self.last_received is not None else -1, audio.sequence)
        self.last_audio = now
        self.accepted += 1
        return True

    def settle(self, now):
        if self.session is None or self.failure:
            return
        if self.ready:
            if now - self.last_clock > CLOCK_STALE_NS:
                self.fail("Authenticated clock stale")
            elif self.expected < MAX_PACKETS and now - self.last_audio > DATA_STALE_NS:
                self.fail("Sender audio inactive")
            return
        if not self.initial.samples:
            if self.attempts >= 12 and (self.outstanding is None or now-self.outstanding.t1 >= 80_000_000):
                self.fail("No authenticated clock")
            return
        if len(self.initial.samples) < 3 and now-self.first_arrival < 120_000_000:
            return
        estimate = self.initial.best()
        self.offset, self.rtt = estimate.offset, estimate.rtt
        lead = self.deadline(self.expected) - now
        if not -500_000_000 <= lead <= NS:
            self.fail("Invalid presentation epoch")
            return
        # Select a future 20 ms boundary; never burst late slots.
        skip = max(0, (PREPARE_NS - lead + FRAME_NS - 1) // FRAME_NS)
        start = ((self.expected + skip + CHUNK - 1) // CHUNK) * CHUNK
        if start >= MAX_PACKETS:
            self.fail("No future presentation slot")
            return
        self.start_skipped = start-self.expected
        self.expected = self.start_sequence = start
        self.pending = {i: pcm for i, pcm in self.pending.items() if i >= start}
        self.start_deadline = self.deadline(start)
        self.outstanding = None

    def probe(self, now):
        self.settle(now)
        if self.session is None or self.failure or self.expected >= MAX_PACKETS:
            return None
        interval = NS if self.ready else 25_000_000
        if self.last_probe is not None and now-self.last_probe < interval:
            return None
        if self.outstanding is not None and now-self.outstanding.t1 < 80_000_000:
            return None
        if not self.ready and self.attempts >= 12:
            return None
        self.outstanding = ClockPacket(REQUEST, self.session, secrets.randbits(64), now, 0, 0)
        self.last_probe = now
        if not self.ready:
            self.attempts += 1
        return self.wire.seal_clock(self.outstanding)

    def reply(self, reply, now):
        c = self.outstanding
        if (self.failure or c is None or reply is None or reply.kind != REPLY or
                (reply.session, reply.nonce, reply.t1) != (c.session, c.nonce, c.t1)):
            return False
        self.outstanding = None
        if self.ready:
            observed = ((reply.t2-reply.t1)+(reply.t3-now))/2
            if abs(observed-self.offset) > 10_000_000:
                return False
            estimate = self.refinement.add(reply, now, 20_000_000)
            if estimate is None:
                return False
            best = self.refinement.best()
            self.offset, self.rtt = best.offset, best.rtt
            self.refinements += 1
        else:
            if self.initial.add(reply, now) is None:
                return False
        self.last_clock = now
        self.settle(now)
        return True

    def next_chunk(self, now, queued):
        self.settle(now)
        if not self.ready or self.expected >= MAX_PACKETS or queued >= QUEUE_TARGET:
            return None
        due = self.deadline(self.expected)
        if due-now > QUEUE_AHEAD_NS:
            return None
        if self.started and queued == 0 and now-due > 40_000_000:
            self.fail("Output underrun; restart group test")
            return None
        n = min(CHUNK, MAX_PACKETS-self.expected)
        indices = range(self.expected, self.expected+n)
        # Wait for reordering while there is time; silence only at the submission deadline.
        if any(i not in self.pending for i in indices) and due-now > 20_000_000:
            return None
        sequence = self.expected
        blocks = []
        for i in indices:
            pcm = self.pending.pop(i, None)
            if pcm is None:
                self.missing += 1
                pcm = SILENCE
            blocks.append(pcm)
        self.expected += n
        return Chunk(sequence, n, b"".join(blocks))

    def start_due(self, now, queued):
        if not self.ready or self.started or now < self.start_deadline:
            return False
        if not queued or now-self.start_deadline > 20_000_000:
            self.fail("Missed software start window; restart group test")
            return False
        self.started = True
        return True
