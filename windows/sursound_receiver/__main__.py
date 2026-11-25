"""Run one local v1 diagnostic session, then stop. Never auto-reconnect or capture."""
import argparse
import json
import secrets
import select
import socket
import sys
import time

from .protocol import local_ipv4, PORT
from .session import Session

def run(bind, sender, wait_seconds=600):
    from .winmm import WinMMOutput
    key = secrets.token_bytes(16)
    session = Session(sender, key)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 512*1024)
        sock.bind((bind, PORT))
        sock.setblocking(False)
        output = WinMMOutput()
        try:
            print(f"READY v1 | IPv4={bind} | UDP={PORT} | pairing key={key.hex()} | wait={wait_seconds}s | unity gain | 160ms prebuffer", flush=True)
            print("Use this fresh key only for the selected sender; Ctrl+C stops.", flush=True)
            deadline = time.monotonic()+wait_seconds
            last_print = time.monotonic()
            while True:
                now = time.monotonic()
                if session.started is None and now >= deadline:
                    break
                if session.started is not None and now-session.started >= 35:
                    break
                readable, _, _ = select.select([sock], [], [], .005)
                if readable:
                    for _ in range(128):
                        try:
                            data, address = sock.recvfrom(2048)
                        except BlockingIOError:
                            break
                        session.receive(data, address, time.monotonic())
                output.reap()
                now = time.monotonic()
                ended = session.last_valid is not None and now-session.last_valid >= .7
                while len(output.q) < session.playout.OUTPUT_TARGET:
                    pcm = session.playout.next_chunk(len(output.q), ended)
                    if pcm is None:
                        break
                    if output.write(pcm):
                        session.record_output(pcm)
                if ended and session.playout.drained() and not output.q:
                    break
                if now-last_print >= (1 if session.stats.raw else 5):
                    print("STATS " + json.dumps(session.summary(output)), flush=True)
                    last_print = now
            until = time.monotonic()+.5
            while output.q and time.monotonic()<until:
                output.reap()
                time.sleep(.01)
        except KeyboardInterrupt:
            print("Stopped by user.", flush=True)
        finally:
            # Report completed buffers before reset; cancelled buffers are counted separately.
            output.reap()
            output.close()
            print("FINAL " + json.dumps(session.summary(output)), flush=True)

def main():
    p = argparse.ArgumentParser(description="SurSound local v1 Windows receiver (diagnostic)")
    p.add_argument("--bind", required=True, type=local_ipv4, help="This computer's private IPv4")
    p.add_argument("--sender", required=True, type=local_ipv4, help="Explicit sender's private IPv4")
    p.add_argument("--wait", type=int, choices=range(1, 601), default=600, metavar="SECONDS")
    args = p.parse_args()
    if sys.platform != "win32":
        p.error("WinMM playback requires Windows.")
    if args.bind == args.sender:
        p.error("Select a different local sending device.")
    run(args.bind, args.sender, args.wait)

if __name__ == "__main__":
    main()
