"""Single explicit sender/session; packet counters contain no addresses, keys or media."""
import struct
from dataclasses import dataclass, asdict
from .playout import Playout
from .protocol import V1Decoder

@dataclass
class Counters:
    raw: int = 0
    wrong_source: int = 0
    wrong_format: int = 0
    group_v2: int = 0
    authentication_failed: int = 0
    sequence_rejected: int = 0
    admitted: int = 0
    nonzero: int = 0
    output_nonzero: int = 0
    output_peak: int = 0

def signal_stats(pcm):
    samples = struct.iter_unpack("<h", pcm)
    count = peak = 0
    for (sample,) in samples:
        if sample:
            count += 1
            peak = max(peak, abs(sample))
    return count, peak

class Session:
    def __init__(self, sender, key):
        self.sender = sender
        self.decoder = V1Decoder(key)
        self.playout = Playout()
        self.stats = Counters()
        self.endpoint = self.session = None
        self.started = self.last_valid = None

    def receive(self, data, address, now):
        s = self.stats
        s.raw += 1
        if address[0] != self.sender or (self.endpoint is not None and address != self.endpoint):
            s.wrong_source += 1
            return False
        if data[:5] == b"SSP2\x02":
            s.group_v2 += 1
            return False
        if len(data) != 993 or data[:5] != b"SSP0\x01":
            s.wrong_format += 1
            return False
        packet = self.decoder.decode(data)
        if packet is None:
            s.authentication_failed += 1
            return False
        if self.session is not None and packet.session != self.session:
            s.sequence_rejected += 1
            return False
        if not self.playout.admit(packet.sequence, packet.pcm):
            s.sequence_rejected += 1
            return False
        if self.session is None:
            self.session, self.endpoint, self.started = packet.session, address, now
        self.last_valid = now
        s.admitted += 1
        s.nonzero += signal_stats(packet.pcm)[0]
        return True

    def record_output(self, pcm):
        count, peak = signal_stats(pcm)
        self.stats.output_nonzero += count
        self.stats.output_peak = max(self.stats.output_peak, peak)

    def summary(self, output):
        return dict(asdict(self.stats), missing=self.playout.missing,
                    rebuffer=self.playout.rebuffer, output_submitted=output.submitted,
                    output_completed=output.finished, output_dropped=output.dropped)
