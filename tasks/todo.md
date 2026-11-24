# SurSound — single task list

## Foundation
- [x] Product name and selected logo direction confirmed.
- [x] General-purpose audio product direction confirmed.
- [x] Finish repository bootstrap; verify main/develop, CI and active remote branch protection.
- [ ] Preserve selected logo as project asset from approved reference (no redesign).

## Phase 0: technical feasibility
- [ ] Inspect Android SDK/JDK/Gradle toolchain and physical test device.
- [ ] Android permitted audio capture: consent, PCM frames, lifecycle, denied-source handling.
  - [x] Implement Kotlin diagnostic app, permission/consent flow, mediaProjection foreground service, PCM meter, stop/revoke/error handling, and meter/state unit tests.
  - [x] Pass Android build, lint and unit tests; APK uploaded for capture-only commit ae47c29 (CI 45406889480).
  - [ ] Verify actual capture on a physical Android device and source app matrix.
- [ ] iOS permitted audio capture: verify SDK/API availability, consent and source limits; establish macOS build/signing.
- [ ] Local Wi-Fi transport with a test signal and authenticated pairing design.
  - [x] Implement Android synthetic tone sender, authenticated UDP packet codec, manual fresh-key pairing, receiver AudioTrack output and JVM codec/UDP loopback tests.
  - [x] Verify Android test-tone build, lint, unit tests and APK in CI (3ee64de; run 45407714646).
  - [x] Implement explicit consent-based capture-to-LAN sender, bounded PCM packetizer/queue, notification stop and 30-second timeout.
  - [ ] Verify current sharing implementation and real-device stop/revocation behavior.
  - [ ] Verify audible sender/receiver playback and wrong-key rejection on two physical devices over Wi-Fi and hotspot.
- [ ] Android/iOS receiver playback and bidirectional physical-device tests.
  - [x] Implement native iOS foreground UDP/AES-GCM receiver and AVAudioEngine playback; add shared wire-format vector and simulator CI.
  - [ ] Verify updated Android and iOS CI, install on actual phones, validate cross-platform audio and wrong-key rejection.
- [ ] Measure end-to-end latency and receiver-to-receiver acoustic offset; target <=5 ms.
- [ ] Test host playback synchronization or document receiver-only fallback.
- [ ] Left/right channel assignment.
- [ ] Test source compatibility (Spotify, YouTube, Apple Music, etc.) without bypassing content restrictions.
- [ ] Background/lock screen, hotspot, discovery, reconnect, privacy and feedback prevention.
- [ ] Record results and go/no-go for UI/MVP.

## Later
- [ ] Approve UI preview and implement cross-platform application.
- [ ] Party/stereo/surround, calibration, release tests and store preparation.

## Evidence
- Bootstrap commit 08d2894; CI run 45389017929 passed; active rulesets 27984200, 27984202 and 27984206 confirmed on GitHub.
- MSI preflight: ANDROID_HOME=D:\\Android\\Sdk, Java and Gradle not on PATH. Local toolchain/device validation incomplete; CI provisions JDK 17/Gradle 8.9/SDK 35.
- Capture-only CI run 45406889480 for ae47c29: bootstrap, Android SDK setup, unit tests, lint, assembly and APK upload passed. PR #1 remains open; physical capture is not verified.
- Added LAN test-tone code and tests on the same pending Phase 0 feature branch to avoid duplicating the unmerged Android baseline. Test-tone CI 45407714646 passed for 3ee64de; device validation pending.
- Added opt-in Android captured PCM LAN sharing and iOS receiver source; new CI and physical-device verification pending. No iOS capture, acoustic synchronization or production pairing yet.
