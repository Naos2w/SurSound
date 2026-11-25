"""Bounded one-shot Windows start wait; never changes global timer resolution."""
import ctypes as C
import ctypes.wintypes as W
import time

NEAR_START_NS = 40_000_000
SPIN_NS = 300_000

class StartWait:
    def __init__(self):
        self.handle = None
        self.method = "python_sleep"
        try:
            kernel = C.WinDLL("kernel32", use_last_error=True)
            kernel.CreateWaitableTimerExW.argtypes = [C.c_void_p, W.LPCWSTR, W.DWORD, W.DWORD]
            kernel.CreateWaitableTimerExW.restype = W.HANDLE
            kernel.SetWaitableTimer.argtypes = [W.HANDLE, C.POINTER(C.c_longlong), W.LONG,
                                               C.c_void_p, C.c_void_p, W.BOOL]
            kernel.SetWaitableTimer.restype = W.BOOL
            kernel.WaitForSingleObject.argtypes = [W.HANDLE, W.DWORD]
            kernel.WaitForSingleObject.restype = W.DWORD
            kernel.CloseHandle.argtypes = [W.HANDLE]
            kernel.CloseHandle.restype = W.BOOL
            self.handle = kernel.CreateWaitableTimerExW(None, None, 2, 0x100002)
            if self.handle:
                self.kernel = kernel
                self.method = "high_resolution_waitable_timer"
        except (AttributeError, OSError):
            pass

    def wait_until(self, deadline):
        remaining = deadline-time.perf_counter_ns()
        if remaining > NEAR_START_NS:
            raise ValueError("Start wait must be within the bounded prefilled window")
        if remaining <= 0:
            return
        if self.handle and remaining > SPIN_NS:
            due = C.c_longlong(-max(1,(remaining-SPIN_NS)//100))
            if (not self.kernel.SetWaitableTimer(self.handle,C.byref(due),0,None,None,False)
                    or self.kernel.WaitForSingleObject(self.handle,60) != 0):
                self.method = "python_sleep_fallback"
        # Recompute after native wait. Only the last 0.3 ms may busy-wait once.
        remaining = deadline-time.perf_counter_ns()
        if remaining > SPIN_NS:
            time.sleep((remaining-SPIN_NS)/1e9)
        while time.perf_counter_ns() < deadline:
            pass

    def close(self):
        if self.handle:
            self.kernel.CloseHandle(self.handle)
            self.handle = None
