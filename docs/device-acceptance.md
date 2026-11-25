# Windows-only physical acceptance — SurSound

## Scope and constraints

Free test target: iOS 16+ arm64 receiver, discovery, single and shared-clock group synthetic tone sending. No iOS system/app capture, broadcast extension, App Group entitlement or provisioning bypass. The full app remains independently built/tested in CI. Use only a permitted source, explicit pairing and platform consent. Record the actual device model, OS and artifact commit for each test. Keep personal IPs, pairing keys, raw recordings, device identifiers and Apple credentials out of public issues.

## Prepare devices

1. Download the debug Android APK from the successful CI run's `sursound-android-capture-debug` artifact. With USB debugging enabled and device authorization granted, use `adb devices -l` and `adb install -r app-debug.apk`; launch SurSound and grant only the requested permissions.
2. Download the `sursound-ios-device-test-unsigned` artifact from the same commit's successful CI. Extract it and verify the IPA hash in PowerShell (`Get-FileHash .\SurSoundDeviceTest-unsigned.ipa -Algorithm SHA256`) against SHA256SUMS.txt. Do not install from a third-party IPA mirror.
3. On Windows, obtain Sideloadly from https://sideloadly.io/ or AltStore Classic from https://altstore.io/. Follow the official Windows prerequisites and local Apple Account signing instructions. A separate free test Apple Account is preferable. Connect iPhone/iPad by USB, unlock, trust the computer, sign the IPA locally, and complete any Apple verification yourself. Enable Developer Mode and trust the developer profile if iOS requests them. A free signature normally expires after seven days. Do not share account credentials or accept an unknown enterprise certificate. Free signing success is unverified until the actual device opens the app.
4. Put devices on the same Wi-Fi or hotspot (disable client isolation if needed on a network you control). Grant Local Network access on iOS. Start each receiver before the sender; enter only the private sender IP and the same fresh 128-bit pairing key. Do not reuse the key across attempts.

## Acceptance matrix — evidence required, not pre-passed

| Test | Procedure and acceptance | Issue |
|---|---|---|
| Android capture | Play a permitted, non-protected source; PCM meter shows nonzero samples; cancel/deny/revoke/stop capture and verify indicator disappears. | #11, #21 |
| Free iOS install | Sign locally and open SurSound Test on iPhone and iPad; grant local-network permission; no broadcast capture control is present. | #15 (partial) |
| V1 tone in both directions | Android → iOS and iOS → Android; audible tone, packet counter rises; repeat with fresh mismatched keys, confirm no audible output. | #22, #28 |
| V2 multi-receiver synthetic pulse | One sender, at least two receivers; authenticated clock replies and audible pulses; repeat over hotspot and Wi-Fi; stop and timeout. | #28, #30 |
| Android captured group audio | Sender explicitly consents to permitted playback sharing; two receivers play the content; test wrong key, denied source, stop/revocation, disconnect; no app audio before consent. | #21, #22, #28, #30 |
| Full iOS capture | Requires signed full app with working App Group and ReplayKit or supported replacement. Never mark from free test target. | #15, #16 |
| Acoustic timing | Use the synthetic 440 Hz pulse diagnostic. Place both receiving speakers next to a single recorder at approximately equal distance; preferably use independent channels or repeat with each source isolated to identify the onsets. Record at 48 kHz or greater, compare onset timestamps for at least 20 identifiable paired pulses, report median and max/p95 absolute offset and measurement uncertainty. Repeat under Wi-Fi/hotspot. A single mixed waveform may be ambiguous; label such data inconclusive. Target <=5 ms receiver-to-receiver offset; distinguish network RTT, presentation lead, software scheduling, acoustic offset and host latency. Do not publish raw captured app audio. | #31 |
| Host sync | Measure host separately; if host playback cannot be controlled, document receiver-only fallback. | #32 |

## Record results

For each run: test identifier, commit/CI artifact, models/OS, network topology without private addresses, test mode, authenticated packet/drop counts, audible outcome, measured offset with method and uncertainty where applicable, pass/fail/inconclusive, and blockers. Update existing issues after evidence; do not close hardware-dependent tasks on simulator/build success. Free install and complete iOS capture are separate acceptance scopes.
