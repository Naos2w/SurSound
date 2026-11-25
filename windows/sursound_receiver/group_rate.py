"""Position-based bounded sample adjustment; never treats driver position as acoustic time."""
from collections import deque
from dataclasses import dataclass
import struct
import math

RATE = 48000
NS = 1_000_000_000
MAX_PPM = 1000.0

@dataclass(frozen=True)
class Segment:
    device_start: int
    device_frames: int
    source_start: int
    source_frames: int

class FrameMap:
    def __init__(self):
        self.segments = deque(maxlen=32)
        self.submitted = 0

    def append(self, source_start, source_frames, device_frames):
        if not 0 < source_frames <= 960 or not 0 < device_frames <= 961:
            raise ValueError("Invalid output segment")
        self.segments.append(Segment(self.submitted, device_frames, source_start, source_frames))
        self.submitted += device_frames

    def source_at(self, played):
        if played < 0 or played > self.submitted:
            return None
        while len(self.segments)>1 and played >= self.segments[0].device_start+self.segments[0].device_frames:
            self.segments.popleft()
        if not self.segments:
            return None
        segment = self.segments[0]
        distance = played-segment.device_start
        if not 0 <= distance <= segment.device_frames:
            return None
        return segment.source_start + distance*segment.source_frames/segment.device_frames

class RateController:
    """Track phase relative to an initial driver-position baseline; absolute latency unknown."""
    def __init__(self):
        self.baseline = self.phase = self.last_time = self.last_position = None
        self.ppm = self.fraction = 0.0
        self.observations = 0
        self.adjusted_frames = 0

    def observe(self, played, source_frame, sampled_at, uncertainty_ns, origin, offset):
        if source_frame is None or not 0 <= uncertainty_ns <= 5_000_000:
            return False
        if self.last_time is not None and (sampled_at-self.last_time < 200_000_000 or played <= self.last_position):
            return False
        phase = sampled_at - (origin-offset+source_frame*NS/RATE)
        if not math.isfinite(phase) or abs(phase) > NS:
            return False
        if self.baseline is None:
            self.baseline = phase
        residual = phase-self.baseline
        if abs(residual) > 100_000_000:
            return False
        self.last_time, self.last_position = sampled_at, played
        self.phase = residual
        # Positive lag -> shorten subsequent PCM slightly; negative lag -> lengthen.
        target = max(-MAX_PPM,min(MAX_PPM,residual/2_000_000_000*1_000_000))
        self.ppm = 0.75*self.ppm + 0.25*target
        self.observations += 1
        return True

    def frames_for(self, n, now):
        if not 0 < n <= 960:
            raise ValueError("Invalid input frames")
        # No valid recent driver position: do not extrapolate a stale correction.
        ppm = self.ppm if self.last_time is not None and now-self.last_time <= 2*NS else 0.0
        self.fraction += -n*ppm/1_000_000
        adjustment = int(self.fraction)
        adjustment = max(-1,min(1,adjustment))
        self.fraction -= adjustment
        self.adjusted_frames += adjustment
        return n+adjustment

def resample(pcm, target):
    """Small per-chunk stereo linear adjustment; bounded ±1 output frame."""
    n = len(pcm)//4
    if len(pcm)%4 or not 1 <= n <= 960 or not 1 <= target <= 961 or abs(target-n)>1:
        raise ValueError("Invalid bounded stereo resample")
    if target == n:
        return pcm
    source = list(struct.iter_unpack("<hh", pcm))
    if n == 1 or target == 1:
        return struct.pack("<hh", *source[0])*target
    result = bytearray()
    for i in range(target):
        pos = i*(n-1)/(target-1)
        a = int(pos)
        b = min(a+1,n-1)
        f = pos-a
        result.extend(struct.pack("<hh", *(round(source[a][ch]*(1-f)+source[b][ch]*f) for ch in range(2))))
    return bytes(result)
