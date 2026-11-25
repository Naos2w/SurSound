"""Explicit silent Windows/UDP loopback diagnostic, no physical synchronization claim."""
import socket
import select
import sys
import threading
import time
from dataclasses import replace

from .group_wire import AUDIO, FRAME_NS, MAX_PACKETS, GroupWire, REQUEST, REPLY
from .group_cli import run
from .protocol import PORT

# Public synthetic key and all-zero PCM. No user media or credentials.
KEY = bytes.fromhex("00112233445566778899aabbccddeeff")
SESSION = 0x0102030405060708
COUNT = 400

def main():
    if sys.platform != "win32":
        raise SystemExit("This diagnostic requires Windows WinMM.")
    ready = threading.Event()
    errors = []
    final = {}
    sent_count = [0]
    max_late_ns = [0]

    def send():
        try:
            if not ready.wait(5):
                raise RuntimeError("Receiver did not open")
            wire = GroupWire(KEY)
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                sock.bind(("127.0.0.1", 0))
                sock.setblocking(False)
                first = MAX_PACKETS-COUNT
                seq = first
                start = time.perf_counter_ns()+100_000_000
                origin = start+240_000_000-first*FRAME_NS
                end = start+COUNT*FRAME_NS+500_000_000
                while time.perf_counter_ns()<end:
                    now = time.perf_counter_ns()
                    # Batch due synthetic packets to tolerate coarse Windows timers.
                    # No fabricated delivery counts: abort on large scheduling stalls.
                    for _ in range(16):
                        if seq >= MAX_PACKETS:
                            break
                        due = start+(seq-first)*FRAME_NS
                        if now < due:
                            break
                        late = now-due
                        max_late_ns[0] = max(max_late_ns[0], late)
                        if late > 100_000_000:
                            raise RuntimeError("Synthetic sender missed >100 ms")
                        header = AUDIO.pack(b"SSP2", 2, SESSION, seq, origin)
                        sock.sendto(header+wire.audio.encrypt(header[5:17], bytes(960), header),
                                    ("127.0.0.1", PORT))
                        seq += 1
                        sent_count[0] += 1
                    readable, _, _ = select.select([sock], [], [], .001)
                    if readable:
                        for _ in range(16):
                            try:
                                data, address = sock.recvfrom(2048)
                            except BlockingIOError:
                                break
                            t2 = time.perf_counter_ns()
                            request = wire.open_clock(data)
                            if request and request.kind == REQUEST and request.session == SESSION:
                                reply = replace(request, kind=REPLY, t2=t2, t3=time.perf_counter_ns())
                                sock.sendto(wire.seal_clock(reply), address)
        except Exception as exc:
            errors.append(str(exc))

    worker = threading.Thread(target=send, name="SurSound synthetic loopback sender")
    worker.start()
    try:
        ok = run("127.0.0.1", "127.0.0.1", KEY, wait=3,
                 on_ready=ready.set, on_final=final.update)
    finally:
        ready.set()
        worker.join()
    print("SYNTHETIC sent=", sent_count[0], "max_sender_dispatch_late_ns=", max_late_ns[0], flush=True)
    if errors:
        raise RuntimeError("; ".join(errors))
    if (not ok or sent_count[0] != COUNT or final["admitted"] < COUNT-4
            or final["missing"] > 4 or final["authentication_failed"] != 0
            or final["clock_accepted"] < 3 or final["output_dropped"] != 0
            or final["output_submitted"] != final["output_completed"]):
        raise RuntimeError("Silent loopback acceptance failed; inspect actual metrics")
    print("PASS: real localhost UDP + WinMM output (silent synthetic data, one clock/device).", flush=True)
    print("No cross-device acoustic measurement.", flush=True)

if __name__ == "__main__":
    main()
