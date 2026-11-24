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
  - [ ] Pass Android build, lint and unit tests; record CI result.
  - [ ] Verify actual capture on a physical Android device and source app matrix.
- [ ] iOS permitted audio capture: verify SDK/API availability, consent and source limits; establish macOS build/signing.
- [ ] Local Wi-Fi transport with a test signal and authenticated pairing design.
- [ ] Android/iOS receiver playback and bidirectional physical-device tests.
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
- Phase 0 native code and tests added on feature/android-audio-capture. No physical-device capture, streaming or synchronization has been verified.
