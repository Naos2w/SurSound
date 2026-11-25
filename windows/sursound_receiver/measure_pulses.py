"""Offline timing helper for separately recorded synthetic receiver pulses only.

Do not use a mixed microphone track or uncalibrated channel pair to claim acoustic accuracy.
No capture, upload, or output audio file. This helper never records microphone audio.
"""
import argparse
from array import array
import json
import math
import statistics
import sys
import wave

def pulse_onsets(samples, rate):
    # 1 ms RMS bins, then a three-bin envelope. The detector is intentionally conservative.
    step = round(rate/1000)
    if not samples or len(samples) < rate*20:
        raise ValueError("Need at least 20 seconds of identifiable pulses")
    energy = [sum(x*x for x in samples[i:i+step])/step
              for i in range(0, len(samples)-step+1, step)]
    envelope = [math.sqrt(sum(energy[i:i+3])/3) for i in range(len(energy)-2)]
    if not envelope:
        raise ValueError("Empty envelope")
    baseline = statistics.median(envelope)
    peak = max(envelope)
    if peak < max(30, 8*baseline):
        raise ValueError("Pulse signal is too weak or noisy")
    if max(map(abs, samples)) >= 32760:
        raise ValueError("Clipping may invalidate onset timing")
    threshold = max(4*baseline, peak*.20, 10)
    starts = []
    start = end = None
    for i, value in enumerate(envelope):
        if value >= threshold:
            if start is None:
                start = i
            elif i-end > 3:
                if not 20 <= end-start+1 <= 90:
                    raise ValueError("Unexpected burst length; recording is ambiguous")
                starts.append(start*step/rate)
                start = i
            end = i
    if start is not None:
        if not 20 <= end-start+1 <= 90:
            raise ValueError("Unexpected final burst length")
        starts.append(start*step/rate)
    if not 20 <= len(starts) <= 30:
        raise ValueError("Need 20–30 identifiable pulses per channel")
    if any(not .94 <= b-a <= 1.06 for a,b in zip(starts,starts[1:])):
        raise ValueError("Pulse spacing is inconsistent; missing or extra pulses")
    return starts

def analyze(left, right, rate, calibration_ms, uncertainty_ms):
    if rate < 48000 or len(left) != len(right):
        raise ValueError("Require equal-length channels sampled at 48 kHz or greater")
    if not math.isfinite(calibration_ms) or not math.isfinite(uncertainty_ms) or uncertainty_ms < 0:
        raise ValueError("Supply a finite channel calibration and nonnegative uncertainty")
    a, b = pulse_onsets(left,rate), pulse_onsets(right,rate)
    if len(a) != len(b) or any(abs(y-x)>.1 for x,y in zip(a,b)):
        raise ValueError("Pulse matching ambiguous; use an aligned, isolated stereo recording")
    offsets = [(y-x)*1000-calibration_ms for x,y in zip(a,b)]
    absolute = sorted(map(abs,offsets))
    # Each channel uses a 1 ms grid. The envelope/detection and acoustic path
    # uncertainty must also be included in the supplied calibration uncertainty.
    bound = uncertainty_ms + 2*round(rate/1000)/rate*1000
    maximum = max(absolute)
    candidate = ("within_target_with_supplied_bound" if maximum+bound <= 5
                 else "outside_target_with_supplied_bound" if maximum-bound > 5
                 else "inconclusive")
    return {"matched_pulses":len(offsets),
            "median_signed_ms":round(statistics.median(offsets),3),
            "median_absolute_ms":round(statistics.median(absolute),3),
            "p95_absolute_ms":round(absolute[math.ceil(.95*len(absolute))-1],3),
            "max_absolute_ms":round(maximum,3),
            "supplied_plus_grid_bound_ms":round(bound,3),
            "conditional_result":candidate,
            "caution":"Conditional on independently isolated channels and valid calibration; not a device certification."}

def main():
    p = argparse.ArgumentParser(description="Analyze simultaneous isolated stereo recordings of SurSound synthetic pulses")
    p.add_argument("wav", help="Local 16-bit stereo PCM WAV; no upload")
    p.add_argument("--calibration-ms", type=float, required=True, help="Measured B-minus-A channel/path skew")
    p.add_argument("--uncertainty-ms", type=float, required=True, help="Conservative calibration/detection/path uncertainty excluding 1 ms grid")
    p.add_argument("--isolated-channels", action="store_true", required=True,
                   help="Confirm each channel independently records its designated receiver")
    args=p.parse_args()
    try:
        with wave.open(args.wav,"rb") as f:
            rate=f.getframerate()
            if f.getnchannels()!=2 or f.getsampwidth()!=2 or f.getcomptype()!="NONE" or not 48000<=rate<=192000:
                raise ValueError("Require 16-bit stereo PCM WAV at 48–192 kHz")
            if f.getnframes()>rate*90:
                raise ValueError("Use a synthetic test recording no longer than 90 seconds")
            data=array("h",f.readframes(f.getnframes()))
            if sys.byteorder!="little":
                data.byteswap()
        print(json.dumps(analyze(data[::2],data[1::2],rate,args.calibration_ms,args.uncertainty_ms),indent=2))
    except (ValueError,OSError,wave.Error) as exc:
        print(json.dumps({"conditional_result":"inconclusive","reason":str(exc)}))
        raise SystemExit(2)

if __name__ == "__main__":
    main()
