"""WinMM group sink with paused prefill and driver-reported sample position."""
import ctypes as C
import ctypes.wintypes as W
import struct
import time
from dataclasses import dataclass
from .winmm import WinMMOutput, Header

class MMTime(C.Structure):
    _fields_ = [("type", W.UINT), ("data", C.c_ubyte*8)]

@dataclass(frozen=True)
class Position:
    frames: int
    sampled_at: int
    uncertainty_ns: int

class GroupOutput(WinMMOutput):
    def __init__(self):
        super().__init__()
        self.paused = False
        self.frames_submitted = 0
        try:
            for name in ("waveOutPause", "waveOutRestart"):
                fn = getattr(self.win, name)
                fn.argtypes = [C.c_void_p]
                fn.restype = W.UINT
            self.win.waveOutGetPosition.argtypes = [C.c_void_p, C.POINTER(MMTime), W.UINT]
            self.win.waveOutGetPosition.restype = W.UINT
            self.check(self.win.waveOutPause(self.handle), "waveOutPause")
            self.paused = True
        except Exception:
            self.close()
            raise

    def restart(self):
        if not self.paused or not self.q:
            raise RuntimeError("Group output must be paused and prefilled")
        before = time.perf_counter_ns()
        self.check(self.win.waveOutRestart(self.handle), "waveOutRestart")
        after = time.perf_counter_ns()
        self.paused = False
        return before, after

    def write(self, pcm):
        self.reap()
        if len(self.q) >= 6:
            self.dropped += 1
            return False
        if len(pcm)%4 or not 4 <= len(pcm) <= 3844:
            raise ValueError("Expected bounded stereo PCM16")
        data = C.create_string_buffer(pcm)
        hdr = Header()
        hdr.data, hdr.length = C.addressof(data), len(pcm)
        self.check(self.win.waveOutPrepareHeader(self.handle, C.byref(hdr),
                                                  C.sizeof(hdr)), "waveOutPrepareHeader")
        self.q.append((data, hdr))
        self.check(self.win.waveOutWrite(self.handle, C.byref(hdr), C.sizeof(hdr)), "waveOutWrite")
        self.submitted += 1
        self.frames_submitted += len(pcm)//4
        return True

    def position(self):
        # TIME_SAMPLES means samples per channel (frames); reject alternate formats.
        value = MMTime()
        value.type = 2
        before = time.perf_counter_ns()
        result = self.win.waveOutGetPosition(self.handle, C.byref(value), C.sizeof(value))
        after = time.perf_counter_ns()
        if result or value.type != 2:
            return None
        frames = struct.unpack_from("<I", bytes(value.data))[0]
        if frames > self.frames_submitted:
            return None
        return Position(frames, (before+after)//2, (after-before)//2)
