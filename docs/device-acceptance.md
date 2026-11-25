# Windows-only physical acceptance — SurSound

## Scope and constraints

Free test target: iOS 16+ arm64 receiver, discovery, single and shared-clock group synthetic tone sending. No iOS system/app capture, broadcast extension, App Group entitlement or provisioning bypass. The full app remains independently built/tested in CI. Use only a permitted source, explicit pairing and platform consent. Record the actual device model, OS and artifact commit for each test. Keep personal IPs, pairing keys, raw recordings, device identifiers and Apple credentials out of public issues.

## Prepare devices

1. Download the successful CI run's `sursound-android-capture-debug` artifact. Verify the versioned APK against SHA256SUMS.txt and BUILD-INFO.json. Use the authorized Wi-Fi ADB or USB connection only to transfer the APK to Download, verify the device checksum, and let the owner install it manually. Do not run adb install or uninstall. Launch SurSound after the owner confirms installation.
2. Download the `sursound-ios-device-test-unsigned` artifact from the same commit's successful CI. Extract the versioned unsigned IPA and verify its hash in PowerShell against SHA256SUMS.txt and BUILD-INFO.json. Do not install from a third-party IPA mirror.
3. If third-party local signing is explicitly acceptable, on Windows obtain Sideloadly from https://sideloadly.io/ or AltStore Classic from https://altstore.io/. Follow the official Windows prerequisites and local Apple Account signing instructions. A separate free test Apple Account is preferable. Connect iPhone/iPad by USB, unlock, trust the computer, sign the IPA locally, and complete any Apple verification yourself. Enable Developer Mode and trust the developer profile if iOS requests them. A free signature normally expires after seven days. Do not share account credentials or accept an unknown enterprise certificate. Free signing success is unverified until the actual device opens the app.
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

## Windows single-receiver diagnostic

See [Windows receiver](../windows/README.md) for the reproducible local-only implementation. V1 synthetic and consented Android app-audio sessions were audibly confirmed on the earlier temporary MSI implementation; repeat acceptance with the committed source. The Windows v1 receiver uses a 160 ms prebuffer and output-buffer-driven pacing; it is not a v2 clock receiver and does not establish acoustic synchronization. Android diagnostics retain the local UDP send-call and drop statistics after stop or timeout, without recording keys or audio. A UDP send count alone does not prove delivery.

## QR pairing acceptance (Issue #62)

Use the Windows receiver's optional --discover --qr mode. Verify the Android receiver list, scan the local QR, review the address, and explicitly start either the test tone or system-consented app-audio sharing. Verify no transmission or capture starts merely by scanning; test QR expiry, wrong format/public IP, camera denial/cancel, manual fallback, QR window closure, and discovery withdrawal when the session starts/stops. Do not publish the private QR/key or raw audio. This acceptance applies to the V1 single receiver only; group and iOS QR flows remain pending.

## Record results

For each run: test identifier, commit/CI artifact, models/OS, network topology without private addresses, test mode, authenticated packet/drop counts, audible outcome, measured offset with method and uncertainty where applicable, pass/fail/inconclusive, and blockers. Update existing issues after evidence; do not close hardware-dependent tasks on simulator/build success. Free install and complete iOS capture are separate acceptance scopes.

## Apple-supported signing assessment (Windows-only)

- GitHub-hosted macOS runners can compile an unsigned iphoneos arm64 application; standard runners for public repositories are free (https://docs.github.com/en/actions/reference/runners/github-hosted-runners). The runner is ephemeral and cannot pair with the USB devices on MSI.
- Apple officially supports free personal-device provisioning through Xcode's Personal Team. App IDs (up to 10), devices (up to 3) and provisioning profiles expire after 7 days; at most 3 apps per device. See https://developer.apple.com/help/account/basics/about-your-developer-account/ and https://developer.apple.com/documentation/xcode/running-your-app-on-simulated-or-physical-devices. A signed development build must include the device and matching entitlements. User-confirmed Developer Mode may be needed.
- An unsigned IPA is not an installed or signed application. No fully Apple-supported Windows-only Personal Team provisioning/USB-install workflow has been verified. For a strictly Apple-supported route, use a trusted Mac with Xcode and a paired personal device. If the user explicitly permits a third-party local signing client, it still requires Apple-issued development signing and user verification; it is not an Apple official client. Do not place Apple credentials or reusable signing material in public CI.
- Full legacy ReplayKit capture requires a functioning signed broadcast upload extension and App Group. Do not assume the free Personal Team has those capabilities. The separate device test target excludes these components and cannot capture other iOS apps.
- Apple documents ScreenCaptureKit for iOS/iPadOS 27+ with the system content-sharing picker and no broadcast extension (https://developer.apple.com/documentation/screencapturekit/capturing-screen-content-on-ios and https://developer.apple.com/documentation/ScreenCaptureKit). Evaluate with the appropriate SDK and physical OS, explicitly permitted audio and no microphone/video transmission. It is not a verified free signing or source-compatibility result.

## Live Android → Windows acceptance (Issue #64)

Use the new `--live` receiver and matching Android Live audio share action. Verify no data before an authenticated ACK; play permitted audio for at least five minutes and a longer run below the 60-minute ceiling; record elapsed time, counter summaries, receiver output stability and any queue drops. Temporarily interrupt only the selected LAN traffic while keeping Android's original network identity (test setup on an authorized network); confirm playback pauses, old buffered data is discarded and fresh-generation playback resumes without a new key. Verify a changed Android network, prolonged loss, Android Stop, receiver Stop, system consent revocation, process death and disabled notifications fail closed. Verify old controls/audio, wrong key, wrong IP/port and mismatched QR mode do not open sharing. Never disable firewall or system permission controls as a workaround. Distinguish synthetic loopback and deterministic state tests from actual Windows audible playback and cross-device recovery. No raw app audio, personal addresses or keys in public issues.
