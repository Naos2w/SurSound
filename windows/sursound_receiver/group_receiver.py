"""Explicit V2 receiver session: endpoint lock, authenticated clock, no legacy fallback."""
from dataclasses import dataclass, asdict
from .group_wire import GroupWire
from .group_timing import GroupTimeline

@dataclass
class GroupCounters:
    raw: int = 0
    wrong_source: int = 0
    wrong_format: int = 0
    authentication_failed: int = 0
    clock_rejected: int = 0
    clock_accepted: int = 0

class GroupReceiver:
    def __init__(self, sender, key):
        self.sender = sender
        self.wire = GroupWire(key)
        self.timeline = GroupTimeline(self.wire)
        self.endpoint = None
        self.stats = GroupCounters()

    def receive(self, packet, address, now):
        s = self.stats
        s.raw += 1
        if address[0] != self.sender or (self.endpoint is not None and address != self.endpoint):
            s.wrong_source += 1
            return False
        if packet[:5] == b"SSP2\x02":
            audio = self.wire.open_audio(packet)
            if audio is None:
                s.authentication_failed += 1
                return False
            accepted = self.timeline.offer(audio, now)
            if accepted and self.endpoint is None:
                self.endpoint = address
            return accepted
        if packet[:4] == b"SSC2":
            clock = self.wire.open_clock(packet)
            if clock is None:
                s.authentication_failed += 1
                return False
            if self.endpoint is None or not self.timeline.reply(clock, now):
                s.clock_rejected += 1
                return False
            s.clock_accepted += 1
            return True
        s.wrong_format += 1
        return False

    def probe(self, now):
        if self.endpoint is None:
            return None
        data = self.timeline.probe(now)
        return (data, self.endpoint) if data is not None else None

    def summary(self, output, rate, start_error, now):
        t = self.timeline
        return dict(asdict(self.stats), admitted=t.accepted, sequence_rejected=t.rejected,
                    missing=t.missing, startup_skipped=t.start_skipped, clock_rtt_ns=t.rtt,
                    clock_age_ns=None if t.last_clock is None else now-t.last_clock,
                    refinement_count=t.refinements, software_start_error_ns=start_error,
                    driver_phase_baseline_ns=rate.baseline, relative_driver_phase_ns=rate.phase,
                    rate_adjustment_ppm=round(rate.ppm, 2),
                    position_observations=rate.observations,
                    adjusted_frames=rate.adjusted_frames, output_submitted=output.submitted,
                    output_completed=output.finished, output_dropped=output.dropped,
                    played=t.started, failure=t.failure)
