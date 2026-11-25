"""Authenticated, generation-separated live mode; never accepts legacy audio before a probe."""
from dataclasses import dataclass
import hmac
import struct

from .playout import Playout
from .protocol import V1Decoder
from .session import Session

PROBE, ACK, STOP = 1, 2, 3
MAX_GENERATION = 4095
MAX_COUNTER = 2**63 - 1
HEADER = struct.Struct(">4sBQIQ")
CONTROL_BYTES = HEADER.size + 32
PROBE_TIMEOUT = 1.5
RECOVERY_TIMEOUT = 10.0
LIVE_LIMIT = 60 * 60

@dataclass(frozen=True)
class Control:
    kind: int
    session: int
    generation: int
    counter: int

def valid(c):
    return (c.kind in (PROBE, ACK, STOP) and 0 <= c.session < 2**64
            and 0 <= c.generation <= MAX_GENERATION and 0 < c.counter <= MAX_COUNTER)

class LiveWire:
    def __init__(self, key):
        if len(key) != 16:
            raise ValueError("Fresh 128-bit key required")
        self.root = key
        self.control_key = hmac.digest(key, b"SurSound/live/control/v1", "sha256")

    def seal(self, c):
        if not valid(c):
            raise ValueError("Invalid live control")
        header = HEADER.pack(b"SLC1", c.kind, c.session, c.generation, c.counter)
        return header + hmac.digest(self.control_key, header, "sha256")

    def open(self, packet):
        if len(packet) != CONTROL_BYTES:
            return None
        header, tag = packet[:HEADER.size], packet[HEADER.size:]
        if not hmac.compare_digest(hmac.digest(self.control_key, header, "sha256"), tag):
            return None
        magic, kind, session, generation, counter = HEADER.unpack(header)
        c = Control(kind, session, generation, counter)
        return c if magic == b"SLC1" and valid(c) else None

    def audio_key(self, session, generation):
        if not 0 <= generation <= MAX_GENERATION:
            raise ValueError("Invalid generation")
        return hmac.digest(self.root, b"SurSound/live/audio/v1" + struct.pack(">QI", session, generation), "sha256")[:16]

class LiveSession:
    """One explicitly chosen IP, one source port/control session, strictly increasing generations."""
    def __init__(self, sender, key):
        self.sender = sender
        self.wire = LiveWire(key)
        self.audio = Session(sender, key)
        self.endpoint = self.session_id = self.started = self.last_probe = self.paused_at = None
        self.generation = -1
        self.counter = 0
        self.control_rejected = self.control_accepted = self.blocked_audio = 0
        self.discarded = self.missing = self.rebuffer = self.recoveries = 0
        self.flush_requested = False
        self.closed = False
        self.reason = "Waiting for authenticated sender"

    @property
    def ready(self):
        return not self.closed and self.started is not None and self.paused_at is None

    @property
    def stats(self):
        return self.audio.stats

    @property
    def playout(self):
        return self.audio.playout

    def _reset_audio(self, key=None):
        self.discarded += len(self.audio.playout.pending)
        self.missing += self.audio.playout.missing
        self.rebuffer += self.audio.playout.rebuffer
        self.audio.playout = Playout()
        self.audio.session = self.audio.started = self.audio.last_valid = None
        self.audio.endpoint = self.endpoint
        self.audio.decoder = V1Decoder(key) if key is not None else None
        self.flush_requested = True

    def consume_flush(self):
        result = self.flush_requested
        self.flush_requested = False
        return result

    def close(self, reason):
        if not self.closed:
            self.closed = True
            self.reason = reason
            self._reset_audio()

    def tick(self, now):
        if self.closed or self.started is None:
            return
        if now - self.started >= LIVE_LIMIT:
            self.close("60-minute live test limit reached")
        elif self.paused_at is not None:
            if now - self.paused_at >= RECOVERY_TIMEOUT:
                self.close("Recovery timeout; fresh pairing required")
        elif (now - self.last_probe >= PROBE_TIMEOUT
              or (self.audio.last_valid is not None and now - self.audio.last_valid >= PROBE_TIMEOUT)):
            self.paused_at = now
            self.reason = "Paused; waiting for a fresh authenticated generation"
            self._reset_audio()

    def receive(self, data, address, now):
        self.tick(now)
        if self.closed:
            return None
        if data[:4] == b"SLC1":
            c = self.wire.open(data)
            if (c is None or address[0] != self.sender or
                    (self.endpoint is not None and address != self.endpoint) or
                    c.kind not in (PROBE, STOP) or c.counter <= self.counter or
                    (self.session_id is not None and c.session != self.session_id) or
                    c.generation < self.generation):
                self.control_rejected += 1
                return None
            if c.kind == STOP:
                if self.endpoint is None:
                    self.control_rejected += 1
                    return None
                self.counter = c.counter
                self.control_accepted += 1
                self.close("Authenticated sender stop")
                return None
            # A paused receiver must see a fresh generation before it acknowledges again.
            if self.paused_at is not None and c.generation == self.generation:
                self.control_rejected += 1
                return None
            if self.endpoint is None:
                self.endpoint, self.session_id, self.started = address, c.session, now
            if c.generation > self.generation:
                if self.generation >= 0:
                    self.recoveries += 1
                self.generation = c.generation
                self._reset_audio(self.wire.audio_key(c.session, c.generation))
            self.counter = c.counter
            self.last_probe = now
            self.paused_at = None
            self.reason = "Authenticated live receiver"
            self.control_accepted += 1
            return self.wire.seal(Control(ACK, c.session, c.generation, c.counter))
        if not self.ready or address != self.endpoint:
            self.blocked_audio += 1
            return None
        self.audio.receive(data, address, now)
        return None

    def record_output(self, pcm):
        self.audio.record_output(pcm)

    def summary(self, output):
        return dict(self.audio.summary(output),
                    missing=self.missing + self.playout.missing,
                    rebuffer=self.rebuffer + self.playout.rebuffer,
                    control_accepted=self.control_accepted, control_rejected=self.control_rejected,
                    blocked_audio=self.blocked_audio, discarded_pending=self.discarded,
                    recoveries=self.recoveries, generation=self.generation, state=self.reason)
