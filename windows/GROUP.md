# Windows V2 group diagnostic — Issue #66

This is independent of the existing v1 and live modes. It interoperates with the Android/iOS **Send shared-clock group test (30 s)** action and the 30-second group app-audio source, using the unchanged SSP2/SSC2 wire format. A valid authenticated clock is mandatory; there is no v1 fallback. At most 6,000 source packets per session. This is an engineering test receiver, not a production Windows app.

## Local preparation

Use the existing Windows Python 3.12 environment and requirements. On Windows, from the `windows` directory:

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m sursound_receiver.group_smoke
.\.venv\Scripts\python.exe -m sursound_receiver.group_cli --bind <PC_PRIVATE_IPV4> --sender <HOST_PRIVATE_IPV4>
```

The smoke command transmits only silent synthetic samples on localhost, exercises actual WinMM paused prefill, driver start, output position and teardown. It does not use a microphone or measure separate speakers. The group command asks interactively for a fresh 32-hex group key (or generates one if blank). Share it privately with the selected sender and other receivers; never put the real key in a command line, log, issue or QR screenshot. Group CLI deliberately uses manual group-key entry, not the V1/live QR ticket. Start receivers before the sender; use explicit sender/receiver IPs. Only selected local IPv4 addresses are supported; no changes to firewall or Windows master volume.

## Implementation and limitations

- Authenticated fixed wire formats and session/endpoint lock; 128-slot reorder window, bounded clock samples, matching nonce/timestamps, minimum RTT startup and periodic updates. Invalid/no/stale clock, invalid epoch, inactive sender or output underrun fails closed.
- Start on a future 20 ms source boundary with a paused WinMM output, prefill at most four 20 ms buffers, then call restart at the mapped local presentation deadline. Record the software start request error and API duration. Missing due slots become silence. No unbounded burst/catch-up.
- Query WinMM `waveOutGetPosition` in TIME_SAMPLES format, validating returned type and submitted frame bounds; the API reports sample frames for one channel. Map variable device frames back to source frames. Track relative driver phase against an initial baseline, adjust future stereo chunks by at most one frame (bounded control target ±1,000 ppm), and disable stale rate adjustment. This is experimental driver-position feedback, not a calibrated DAC/speaker timestamp. It does not remove constant device latency. See Microsoft's [playback-position](https://learn.microsoft.com/en-us/windows/win32/multimedia/retrieving-the-current-playback-position) and [pause/restart](https://learn.microsoft.com/en-us/windows/win32/multimedia/stopping-pausing-and-restarting-playback) documentation. Microsoft recommends WASAPI for new production code; this diagnostic reuses the verified WinMM path.
- Unit tests check exact Android/iOS vectors, replay/endpoint locks, two simulated receiver clocks, missing/stale/bounded timeline, synthetic UDP and rate adjustment. Actual device clocks, Windows driver precision, audio quality and acoustic alignment remain physical acceptance tasks. No ≤5 ms claim from these counters.

## Physical timing acceptance

Use one host and at least two independent physical receiving speakers (e.g. Windows plus a properly signed iPhone/iPad test receiver, or a second Android). Prefer a common simultaneous two-channel recorder with each channel isolated to its designated speaker. Match speaker distances and measure recorder/path skew and uncertainty independently. Do not infer speaker offset from sender RTT, a mixed microphone track, separate unsynchronized recordings, or driver positions.

Run the synthetic 440 Hz pulses, not captured application media, over Wi-Fi and hotspot. If you have a calibrated 16-bit stereo PCM recording (48–192 kHz, 20–30 pulses) with independent channels, the optional offline tool is:

```powershell
.\.venv\Scripts\python.exe -m sursound_receiver.measure_pulses <SYNTHETIC_PULSE_RECORDING.wav> --calibration-ms <MEASURED_B_MINUS_A_SKEW> --uncertainty-ms <CONSERVATIVE_BOUND> --isolated-channels
```

It rejects ambiguous counts/spacing/clipping/weak signals and reports median signed and absolute error, p95, max and a conditional uncertainty result. The 1 ms envelope grid adds a conservative 2 ms quantization bound; the supplied uncertainty must include channel/path and detector uncertainty. The helper cannot establish channel isolation or calibrate hardware automatically. Use independently identifiable pairs and record calibration/method/uncertainty. No audio upload or recording by the program. Report real measurements (not synthetic software tests) to #31. Keep raw audio and private keys out of GitHub.
