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

def run(bind, sender, wait_seconds=600, discover=False, show_qr=False):
    from .winmm import WinMMOutput
    from .pairing import Advertisement, PairingOffer, PairingWindow
    key = secrets.token_bytes(16)
    session = Session(sender, key)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 512*1024)
        sock.bind((bind, PORT))
        sock.setblocking(False)
        output = WinMMOutput()
        advertisement = pairing_window = None
        try:
            if discover:
                try:
                    advertisement = Advertisement(bind)
                    print("Discovery advertised on selected local interface (no key).", flush=True)
                except Exception:
                    print("Discovery unavailable; direct QR/manual pairing remains available.", flush=True)
            deadline = time.monotonic()+wait_seconds
            if show_qr:
                try:
                    offer = PairingOffer.create(bind, key, int(time.time()), wait_seconds)
                    pairing_window = PairingWindow(offer)
                except Exception:
                    print("Unable to show local pairing QR. Restart without --qr for manual entry.", flush=True)
                    return
                print(f"READY v1 | IPv4={bind} | UDP={PORT} | QR shown on local desktop | wait={wait_seconds}s", flush=True)
            else:
                print(f"READY v1 | IPv4={bind} | UDP={PORT} | pairing key={key.hex()} | wait={wait_seconds}s | unity gain | 160ms prebuffer", flush=True)
            print("Use this fresh key only for the selected sender; Ctrl+C stops.", flush=True)
            last_print = last_gui = time.monotonic()
            while True:
                now = time.monotonic()
                if session.started is None:
                    if pairing_window is not None and now-last_gui >= .1:
                        pairing_window.poll()
                        last_gui = now
                    if now >= deadline or (pairing_window is not None and pairing_window.closed):
                        break
                if session.started is not None and advertisement is not None:
                    advertisement.withdraw_async()
                if session.started is not None and pairing_window is not None and not pairing_window.closed:
                    pairing_window.close()
                if session.started is not None and now-session.started >= 35:
                    break
                readable, _, _ = select.select([sock], [], [], .005)
                if readable:
                    for _ in range(128):
                        try:
                            data, address = sock.recvfrom(2048)
                        except BlockingIOError:
                            break
                        received_at = time.monotonic()
                        if session.started is None and received_at >= deadline:
                            break
                        session.receive(data, address, received_at)
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
            # Close the private offer and withdraw discovery regardless of exit path.
            if pairing_window is not None:
                pairing_window.close()
            if advertisement is not None:
                advertisement.close()
            # Report completed buffers before reset; cancelled buffers are counted separately.
            output.reap()
            output.close()
            print("FINAL " + json.dumps(session.summary(output)), flush=True)

def main():
    p = argparse.ArgumentParser(description="SurSound local v1 Windows receiver (diagnostic)")
    p.add_argument("--bind", required=True, type=local_ipv4, help="This computer's private IPv4")
    p.add_argument("--sender", required=True, type=local_ipv4, help="Explicit sender's private IPv4")
    p.add_argument("--wait", type=int, choices=range(1, 601), default=600, metavar="SECONDS")
    p.add_argument("--discover", action="store_true", help="Advertise receiver on selected private interface")
    p.add_argument("--qr", action="store_true", help="Show private QR on local desktop instead of logging key")
    args = p.parse_args()
    if sys.platform != "win32":
        p.error("WinMM playback requires Windows.")
    if args.bind == args.sender:
        p.error("Select a different local sending device.")
    run(args.bind, args.sender, args.wait, args.discover, args.qr)

if __name__ == "__main__":
    main()
