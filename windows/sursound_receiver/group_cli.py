"""Run a single bounded V2 group diagnostic; intentionally separate from V1 and live mode."""
import argparse
import getpass
import json
import re
import secrets
import select
import socket
import sys
import time

from .protocol import local_ipv4, PORT
from .group_wire import MAX_PACKETS
from .group_receiver import GroupReceiver
from .group_rate import RateController, FrameMap, resample
from .start_wait import StartWait, NEAR_START_NS

def run(bind, sender, key, wait=600, *, on_ready=None, on_final=None):
    from .group_output import GroupOutput
    receiver = GroupReceiver(sender, key)
    timeline = receiver.timeline
    rate, frame_map = RateController(), FrameMap()
    start_error = None
    last_position_query = 0
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 512*1024)
        sock.bind((bind, PORT))
        sock.setblocking(False)
        output = GroupOutput()
        waiter = StartWait()
        try:
            print("READY V2 group | selected local interface | no playback until authenticated clock", flush=True)
            wait_end = time.perf_counter_ns()+wait*1_000_000_000
            last_print = time.perf_counter_ns()
            if on_ready is not None:
                on_ready()
            while True:
                now = time.perf_counter_ns()
                if timeline.first_arrival is None:
                    if now >= wait_end:
                        timeline.fail("No authenticated audio before offer expiry")
                        break
                elif now-timeline.first_arrival >= 35_000_000_000:
                    timeline.fail("Group test duration exceeded")
                    break
                if timeline.failure:
                    break
                # A Windows socket timeout can oversleep by a scheduler quantum. Once
                # safely prefilled, use a bounded dedicated timer for the one-shot start.
                if timeline.ready and not timeline.started and len(output.q) >= 2 and now >= timeline.start_deadline-NEAR_START_NS:
                    waiter.wait_until(timeline.start_deadline)
                    now = time.perf_counter_ns()
                    timeline.settle(now)
                    if timeline.start_due(now,len(output.q)):
                        before, after = output.restart()
                        start_error = before-timeline.start_deadline
                        print("START driver request error ns:", start_error, "call duration ns:", after-before,
                              "wait method:", waiter.method, flush=True)
                    if timeline.failure:
                        break
                readable, _, _ = select.select([sock], [], [], .001 if timeline.ready else .005)
                if readable:
                    for _ in range(64):
                        try:
                            packet, address = sock.recvfrom(2048)
                        except BlockingIOError:
                            break
                        received = time.perf_counter_ns()
                        if timeline.first_arrival is None and received >= wait_end:
                            break
                        receiver.receive(packet, address, received)
                output.reap()
                now = time.perf_counter_ns()
                probe = receiver.probe(now)
                if probe:
                    try:
                        sock.sendto(*probe)
                    except OSError:
                        pass  # clock acquisition/staleness policy fails closed
                timeline.settle(now)
                if timeline.failure:
                    break
                if timeline.started and now-last_position_query >= 200_000_000:
                    last_position_query = now
                    position = output.position()
                    if position:
                        source_frame = frame_map.source_at(position.frames)
                        rate.observe(position.frames, source_frame, position.sampled_at,
                                     position.uncertainty_ns, timeline.origin, timeline.offset)
                while not timeline.failure:
                    chunk = timeline.next_chunk(time.perf_counter_ns(), len(output.q))
                    if chunk is None:
                        break
                    n = chunk.packets*240
                    count = rate.frames_for(n, time.perf_counter_ns())
                    if not output.write(resample(chunk.pcm, count)):
                        timeline.fail("Output queue rejected group audio")
                        break
                    frame_map.append(chunk.sequence*240, n, count)
                now = time.perf_counter_ns()
                if timeline.start_due(now, len(output.q)):
                    before, after = output.restart()
                    start_error = before-timeline.start_deadline
                    print("START driver request error ns:", start_error,
                          "call duration ns:", after-before, "wait method: receive_loop", flush=True)
                if timeline.failure:
                    break
                if timeline.started and not output.q:
                    if timeline.expected >= MAX_PACKETS:
                        break
                    timeline.fail("Group output drained before end of timeline")
                    break
                if now-last_print >= 1_000_000_000:
                    print("STATS "+json.dumps(receiver.summary(output, rate, start_error, now)), flush=True)
                    last_print = now
        except KeyboardInterrupt:
            timeline.fail("Stopped by user")
        finally:
            waiter.close()
            output.reap()
            output.close()
            timeline.pending.clear()
            summary = receiver.summary(output, rate, start_error, time.perf_counter_ns())
            summary['start_timer'] = waiter.method
            print("FINAL "+json.dumps(summary), flush=True)
            if on_final is not None:
                on_final(summary)
    return timeline.failure is None and timeline.started

def main():
    p = argparse.ArgumentParser(description="SurSound V2 shared-clock Windows test receiver")
    p.add_argument("--bind", required=True, type=local_ipv4)
    p.add_argument("--sender", required=True, type=local_ipv4)
    p.add_argument("--wait", type=int, choices=range(1,601), default=600, metavar="SECONDS")
    args = p.parse_args()
    if sys.platform != "win32":
        p.error("Windows group output requires WinMM")
    if args.bind == args.sender:
        p.error("Sender must be a different local device")
    # Never place an actual group key in a command-line argument or persistent file.
    key_text = getpass.getpass("Enter fresh 32-hex group key (empty to generate one): ").strip()
    if not key_text:
        key = secrets.token_bytes(16)
        print("Private group key (share only with selected receivers): "+key.hex(), flush=True)
    elif re.fullmatch(r"[0-9a-fA-F]{32}", key_text):
        key = bytes.fromhex(key_text)
    else:
        p.error("Invalid group key")
    if not run(args.bind, args.sender, key, args.wait):
        raise SystemExit(1)

if __name__ == "__main__":
    main()
