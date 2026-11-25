# Synchronization — staged implementation (Issue #30)

## Stage A: receiver playout foundation

Keep wire protocol v1 (AES-GCM, 5 ms PCM16 stereo blocks, 48 kHz). After authentication and explicit peer selection, lock to the first session, anchor to the first observed sequence and wait 40 ms on the local monotonic clock. Accept a bounded window of 64 upcoming sequence numbers; reject duplicates, consumed/late blocks, foreign sessions and packets outside the window. Render a missing due block as silence. If the scheduler stalls, skip overdue slots instead of emitting a burst. The queue remains bounded to 61,440 PCM bytes (plus object/output overhead); 40 ms is initial buffering, not an acoustic latency claim.

The pure Kotlin/Swift clock offset estimator accepts four monotonic timestamps with receiver t1/t4 and sender t2/t3. Round trip = (t4-t1) - (t3-t2); sender-minus-receiver offset = ((t2-t1)+(t3-t4))/2. Reject malformed, stale or excessively long exchanges. Use the minimum-RTT sample from a bounded rolling window; half the RTT is a path-asymmetry uncertainty bound under ideal timestamp assumptions, not a guarantee of clock or acoustic accuracy. The estimator is not yet fed by authenticated network probes.

## Remaining work

- Authenticated versioned clock request/reply and shared sender presentation timeline with nonce separation and session validation; handle rekeying/reconnect.
- Multiple explicitly paired receivers, a common scheduled playout time, sender queue drops represented on the presentation timeline, and bounded clock/sample-rate drift correction.
- Platform hardware-output scheduling and device-specific latency calibration. Main-loop timers and AudioTrack/AVAudioEngine queues alone cannot establish <=5 ms acoustic offset.
- Real-device Wi-Fi/hotspot tests, measurable end-to-end delay and receiver-to-receiver offset under loss/jitter, host playback synchronization or receiver-only fallback (Issues #31/#32). No unverified measurement is recorded as a pass.
