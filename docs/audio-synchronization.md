# Synchronization — staged implementation (Issue #30)

## Stage A: receiver playout foundation

Keep wire protocol v1 (AES-GCM, 5 ms PCM16 stereo blocks, 48 kHz). After authentication and explicit peer selection, lock to the first session, anchor to the first observed sequence and wait 40 ms on the local monotonic clock. Accept a bounded window of 64 upcoming sequence numbers; reject duplicates, consumed/late blocks, foreign sessions and packets outside the window. Render a missing due block as silence. If the scheduler stalls, skip overdue slots instead of emitting a burst. The queue remains bounded to 61,440 PCM bytes (plus object/output overhead); 40 ms is initial buffering, not an acoustic latency claim.

The pure Kotlin/Swift clock offset estimator accepts four monotonic timestamps with receiver t1/t4 and sender t2/t3. Round trip = (t4-t1) - (t3-t2); sender-minus-receiver offset = ((t2-t1)+(t3-t4))/2. Reject malformed, stale or excessively long exchanges. Use the minimum-RTT sample from a bounded rolling window; half the RTT is a path-asymmetry uncertainty bound under ideal timestamp assumptions, not a guarantee of clock or acoustic accuracy. Stage B now feeds the estimator with authenticated clock replies for the separate synthetic group test.

## Stage B: authenticated group clock and presentation timeline

- Sender uses a fresh group pairing key and a random session identifier; select one to four distinct local IPv4 peers explicitly. This is a shared-key test group, not individually authenticated production pairing. All receivers use the same key; do not reuse it across tests. The receiver still verifies the chosen sender endpoint. Existing v1 app audio and single-device test tone remain unchanged.
- V2 audio datagram: 25-byte big-endian header (magic SSP2, version 2, session u64, sequence u32, signed sender monotonic epoch i64), 960 PCM bytes encrypted with AES-128-GCM, 16-byte tag. The entire header is authenticated. Derive audio key as the first 16 bytes of HMAC-SHA256(pairingKey, UTF8("SurSound/sync/audio/v2")); nonce is session || sequence. Encrypt once per slot and fan out identical ciphertext.
- V2 clock datagram: 45-byte header (magic SSC2, kind 1 request / 2 reply, session u64, random nonce u64, t1/t2/t3 signed i64); append 32-byte HMAC-SHA256 using HMAC-SHA256(pairingKey, UTF8("SurSound/sync/control/v2")) as key. Request has t2=t3=0. Reply echoes session, nonce and t1. Accept only the outstanding request from the selected endpoint; validate RTT and timestamps. Send/reply rate and queue bounds apply.
- Sender uses a 240 ms presentation lead: presentation = epoch + sequence * 5 ms. Slots are derived from monotonic elapsed time; late scheduler slots and peer queue drops are not retransmitted, so missing sequence slots remain silence. Every second begins with a short 440 Hz pulse to support acoustic comparison. The synthetic sender is capped at 6,000 slots (30 seconds); all v2 receivers stop scheduling after that timeline. Pre-clock buffer is bounded to 64 slots; no valid clock means no v2 playback. Minimum-RTT sample chosen after three valid exchanges or 120 ms with at least one valid sample, freeze offset for the test. No drift correction yet.
- Deterministic unit tests share an independently computed wire vector and simulate two receiver clocks with distinct offsets. They do not validate OS UDP behavior, actual audible output or <=5 ms acoustic accuracy.

## Physical acceptance (still pending)

Start Android/iOS listeners with the sender's private IP and the same fresh key. On the sender, enter receiver IPs separated by commas and select **Send shared-clock group test**. Verify authenticated clock replies, pulse audio, dropped slots, wrong key rejection, no audio without a valid clock, and stop behavior. Test both sender directions on Wi-Fi and hotspot. Record models, OS, network, RTT, real acoustic offset and end-to-end delay in Issues #28/#31. Do not mark acceptance from packet counters alone.

## Remaining work

- Validate the authenticated v2 protocol on actual Android/iOS peers; production rekeying, reconnect and individual peer authentication.
- Extend the shared presentation timeline to explicitly consented captured app audio. Add adaptive bounded clock/sample-rate drift correction and robust group membership.
- Platform hardware-output scheduling and device-specific latency calibration. Main-loop timers and AudioTrack/AVAudioEngine queues alone cannot establish <=5 ms acoustic offset.
- Real-device Wi-Fi/hotspot tests, measurable end-to-end delay and receiver-to-receiver offset under loss/jitter, host playback synchronization or receiver-only fallback (Issues #31/#32). No unverified measurement is recorded as a pass.
