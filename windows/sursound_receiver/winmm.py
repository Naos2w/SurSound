"""Windows WaveOut PCM sink. No recording, file output, or system volume changes."""
import ctypes as C
import ctypes.wintypes as W
from collections import deque

class Format(C.Structure):
    _pack_ = 1
    _fields_ = [("tag", W.WORD), ("channels", W.WORD), ("rate", W.DWORD),
                ("avg", W.DWORD), ("align", W.WORD), ("bits", W.WORD), ("extra", W.WORD)]

class Header(C.Structure):
    _fields_ = [("data", C.c_void_p), ("length", W.DWORD), ("recorded", W.DWORD),
                ("user", C.c_size_t), ("flags", W.DWORD), ("loops", W.DWORD),
                ("next", C.c_void_p), ("reserved", C.c_size_t)]

class WinMMOutput:
    def __init__(self):
        self.win = C.WinDLL("winmm")
        self.handle = C.c_void_p()
        self.q = deque()
        self.submitted = 0
        self.finished = 0
        self.dropped = 0
        self.closed = False
        self.format = Format(1, 2, 48000, 192000, 4, 16, 0)
        self.win.waveOutOpen.argtypes = [C.POINTER(C.c_void_p), W.UINT, C.POINTER(Format),
                                        C.c_size_t, C.c_size_t, W.DWORD]
        self.win.waveOutOpen.restype = W.UINT
        for name in ("waveOutPrepareHeader", "waveOutWrite", "waveOutUnprepareHeader"):
            fn = getattr(self.win, name)
            fn.argtypes = [C.c_void_p, C.POINTER(Header), W.UINT]
            fn.restype = W.UINT
        for name in ("waveOutReset", "waveOutClose"):
            fn = getattr(self.win, name)
            fn.argtypes = [C.c_void_p]
            fn.restype = W.UINT
        self.check(self.win.waveOutOpen(C.byref(self.handle), 0xffffffff,
                   C.byref(self.format), 0, 0, 0), "waveOutOpen")

    @staticmethod
    def check(result, label):
        if result:
            raise RuntimeError(f"{label}: WinMM error {result}")

    def reap(self):
        while self.q and self.q[0][1].flags & 1:  # WHDR_DONE
            data, hdr = self.q[0]
            self.check(self.win.waveOutUnprepareHeader(self.handle, C.byref(hdr),
                                                       C.sizeof(hdr)), "waveOutUnprepareHeader")
            self.q.popleft()
            self.finished += 1

    def write(self, pcm):
        self.reap()
        if len(self.q) >= 6:
            self.dropped += 1
            return False
        if len(pcm) != 3840:
            raise ValueError("Expected 20 ms stereo PCM16 at 48 kHz")
        # Keep PCM at unity gain; Windows volume remains under owner control.
        data = C.create_string_buffer(pcm)
        hdr = Header()
        hdr.data = C.addressof(data)
        hdr.length = len(pcm)
        self.check(self.win.waveOutPrepareHeader(self.handle, C.byref(hdr),
                                                  C.sizeof(hdr)), "waveOutPrepareHeader")
        self.q.append((data, hdr))
        self.check(self.win.waveOutWrite(self.handle, C.byref(hdr), C.sizeof(hdr)), "waveOutWrite")
        self.submitted += 1
        return True

    def flush(self):
        if self.closed:
            return
        self.reap()
        self.check(self.win.waveOutReset(self.handle), "waveOutReset")
        # Reset returns outstanding buffers. They are not evidence of audible completion.
        while self.q:
            data, hdr = self.q[0]
            self.check(self.win.waveOutUnprepareHeader(self.handle, C.byref(hdr),
                                                       C.sizeof(hdr)), "waveOutUnprepareHeader")
            self.q.popleft()
            self.dropped += 1
    def close(self):
        if self.closed:
            return
        self.flush()
        self.check(self.win.waveOutClose(self.handle), "waveOutClose")
        self.closed = True
