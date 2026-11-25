"""Bounded, output-buffer-paced v1 reorder buffer (not a shared-clock scheduler)."""
from .protocol import PCM_BYTES, SILENCE, MAX_SEQUENCE

class Playout:
    CHUNK_PACKETS = 4  # 20 ms
    WINDOW_PACKETS = 128
    START_PACKETS = 32  # 160 ms source timeline span
    OUTPUT_TARGET = 4  # up to 80 ms of submitted PCM

    def __init__(self):
        self.expected = None
        self.highest = None
        self.pending = {}
        self.primed = False
        self.missing = 0
        self.rebuffer = 0

    def admit(self, sequence, pcm):
        if len(pcm) != PCM_BYTES or not 0 <= sequence < MAX_SEQUENCE:
            return False
        if self.expected is None:
            self.expected = self.highest = sequence
        if (sequence < self.expected or sequence >= self.expected + self.WINDOW_PACKETS
                or sequence in self.pending):
            return False
        self.pending[sequence] = pcm
        self.highest = max(self.highest, sequence)
        return True

    def drained(self):
        return self.expected is not None and self.expected > self.highest

    def next_chunk(self, queued, ended=False):
        """Return up to one 20 ms chunk, or wait. Never advance beyond highest received slot."""
        if self.expected is None or queued >= self.OUTPUT_TARGET:
            return None
        span = self.highest - self.expected + 1
        if span <= 0:
            if self.primed and not ended and queued == 0:
                self.primed = False
                self.rebuffer += 1
            return None
        if not self.primed:
            if span < self.START_PACKETS and not ended:
                return None
            self.primed = True
        if span < self.CHUNK_PACKETS and not ended:
            return None
        n = min(self.CHUNK_PACKETS, span)
        if not ended and queued >= 2 and any(self.expected + i not in self.pending for i in range(n)):
            return None
        blocks = []
        for _ in range(n):
            pcm = self.pending.pop(self.expected, None)
            if pcm is None:
                pcm = SILENCE
                self.missing += 1
            blocks.append(pcm)
            self.expected += 1
        # Final partial chunk is padded, without advancing nonexistent sequence slots.
        return b"".join(blocks) + SILENCE * (self.CHUNK_PACKETS - n)
