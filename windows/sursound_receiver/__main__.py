"""Explicit v1 diagnostic or bounded live receiving on the selected local interface."""
import argparse
import json
import secrets
import select
import socket
import sys
import time

from .protocol import local_ipv4, PORT
from .session import Session
from .live import LiveSession

def run(bind, sender, wait_seconds=600, discover=False, show_qr=False, live=False):
    from .winmm import WinMMOutput
    from .pairing import Advertisement, PairingOffer, PairingWindow
    key = secrets.token_bytes(16)
    session = LiveSession(sender, key) if live else Session(sender, key)
    mode = 'live' if live else 'v1'
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
                    advertisement = Advertisement(bind, mode=mode)
                    print("Discovery advertised on selected local interface (no key).", flush=True)
                except Exception:
                    print("Discovery unavailable; direct QR/manual pairing remains available.", flush=True)
            deadline = time.monotonic()+wait_seconds
            if show_qr:
                try:
                    offer = PairingOffer.create(bind, key, int(time.time()), wait_seconds, mode=mode)
                    pairing_window = PairingWindow(offer)
                except Exception:
                    print("Unable to show local pairing QR. Restart without --qr for manual entry.", flush=True)
                    return
                print(f"READY {mode} | IPv4={bind} | UDP={PORT} | QR shown on local desktop | wait={wait_seconds}s", flush=True)
            else:
                print(f"READY {mode} | IPv4={bind} | UDP={PORT} | pairing key={key.hex()} | wait={wait_seconds}s | unity gain | 160ms prebuffer", flush=True)
            print("Use this fresh key only for the selected sender; Ctrl+C stops.", flush=True)
            last_print = last_gui = time.monotonic()
            while True:
                now = time.monotonic()
                if pairing_window is not None and not pairing_window.closed and now-last_gui >= .1:
                    pairing_window.poll()
                    last_gui = now
                if session.started is None and (now >= deadline or
                        (pairing_window is not None and pairing_window.closed)):
                    break
                if live and session.started is not None:
                    if pairing_window is not None and pairing_window.closed:
                        session.close("Receiver stopped by user")
                    session.tick(now)
                    if session.consume_flush():
                        output.flush()
                    if session.closed:
                        break
                if session.started is not None and advertisement is not None:
                    advertisement.withdraw_async()
                if session.started is not None and pairing_window is not None and not pairing_window.closed:
                    if live:
                        pairing_window.activate(session.reason)
                    else:
                        pairing_window.close()
                if not live and session.started is not None and now-session.started >= 35:
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
                        reply = session.receive(data, address, received_at)
                        if live and reply is not None:
                            try:
                                sock.sendto(reply, address)
                            except OSError:
                                pass  # sender must receive a fresh ACK before transmitting
                output.reap()
                now = time.monotonic()
                if live:
                    session.tick(now)
                    if session.consume_flush():
                        output.flush()
                    if session.closed:
                        break
                    ended = False
                else:
                    ended = session.last_valid is not None and now-session.last_valid >= .7
                while (not live or session.ready) and len(output.q) < session.playout.OUTPUT_TARGET:
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
            if live:
                session.close("Receiver stopped")
                output.flush()
            else:
                until = time.monotonic()+.5
                while output.q and time.monotonic()<until:
                    output.reap()
                    time.sleep(.01)
        except KeyboardInterrupt:
            if live:
                session.close("Receiver stopped by user")
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
    p = argparse.ArgumentParser(description="SurSound local Windows receiver (diagnostic or live)")
    p.add_argument("--bind", required=True, type=local_ipv4, help="This computer's private IPv4")
    p.add_argument("--sender", required=True, type=local_ipv4, help="Explicit sender's private IPv4")
    p.add_argument("--wait", type=int, choices=range(1, 601), default=600, metavar="SECONDS")
    p.add_argument("--discover", action="store_true", help="Advertise receiver on selected private interface")
    p.add_argument("--qr", action="store_true", help="Show private QR on local desktop instead of logging key")
    p.add_argument("--live", action="store_true", help="Opt in to authenticated live sharing, max 60 minutes")
    args = p.parse_args()
    if sys.platform != "win32":
        p.error("WinMM playback requires Windows.")
    if args.bind == args.sender:
        p.error("Select a different local sending device.")
    run(args.bind, args.sender, args.wait, args.discover, args.qr, args.live)

if __name__ == "__main__":
    main()
