# SurSound — single task list

## Foundation
- [x] Product name and selected logo direction confirmed.
- [x] General-purpose audio product direction confirmed.
- [ ] Finish repository bootstrap; verify main/develop, CI and active remote branch protection.
- [ ] Preserve selected logo as project asset from approved reference (no redesign).

## Phase 0: technical feasibility
- [ ] Inspect Android SDK/JDK/Gradle toolchain and physical test device.
- [ ] Android permitted audio capture: consent, PCM frames, lifecycle, denied-source handling.
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
No capture, streaming, device synchronization or application build has yet been validated.
