# iOS bidirectional LAN diagnostic — Phase 0

Native SwiftUI diagnostic with iOS 16+ receiver and test-tone sender, plus an experimental legacy ReplayKit broadcast upload extension. No production UI or verified third-party-source compatibility.

## Build

On macOS with Xcode and XcodeGen:

```sh
cd ios
xcodegen generate
xcodebuild -project SurSoundReceiver.xcodeproj -scheme SurSoundReceiver -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

CI builds the app, embedded extension and simulator unit tests. It does not validate device signing, App Groups, the system broadcast picker or real network/audio behavior.

For physical devices, sign both app and broadcast extension with the same development team and provision App Group `group.com.naos.sursound`. Register equivalent identifiers and update entitlements and picker identifier if the original identifiers are unavailable to your team. App Group eligibility/provisioning must be confirmed; no signing credentials are stored in Git. The current target identifiers are `com.naos.sursound.receiver` and `com.naos.sursound.receiver.broadcast`.

## Receive and send

1. On the same Wi-Fi/hotspot, start the receiver with the other device's private IPv4 address and a fresh 128-bit pairing key. The receiver advertises a temporary Bonjour service `_sursound._udp` while listening.
2. On the sender, use Find local receivers (15 s), select the receiver IP or enter it manually, and supply the same fresh key. Discovering a device does not authorize audio sharing.
3. Use Send test tone for 30 seconds; test audible playback on Android and iOS receivers. Use wrong keys, stop, backgrounding, service disappearance and hotspot tests. The receiver stops at 45 seconds.
4. To test iOS app audio, grant Local Network access using discovery in the foreground, then Prepare single-device share (30 s). Tap the actual system broadcast control and confirm Start Broadcast. Switch to a permitted audio source. Stop via the system recording indicator; the extension also ends on timeout or pause. Only `.audioApp` PCM is converted to 48 kHz stereo PCM16, packetized and encrypted; microphone and video samples are discarded. No media is written to disk. A protected, short-lived, single-use App Group file carries only the explicit peer/key/mode configuration and is consumed by the extension (or expires and is cleaned up on cancellation/app return). Do not bypass a denied source.
5. Record device OS versions, signing, permissions, packet counts, audible result and failure cases in [iOS device acceptance (#15)](https://github.com/Naos2w/SurSound/issues/15) and [bidirectional device acceptance (#28)](https://github.com/Naos2w/SurSound/issues/28).

Apple now deprecates the ReplayKit broadcast APIs in favor of ScreenCaptureKit; Apple's iOS ScreenCaptureKit sample requires iOS 27+. This implementation is a compatibility PoC, not a claim that legacy broadcast works on iOS 27. Migrate to the newer system content-sharing picker/capture APIs on an appropriate SDK in a subsequent task. Neither simulator tests nor CI prove arbitrary app audio capture.

References:
- https://developer.apple.com/documentation/replaykit/rpbroadcastsamplehandler
- https://developer.apple.com/documentation/replaykit/rpsystembroadcastpickerview
- https://developer.apple.com/documentation/screencapturekit/capturing-screen-content-on-ios
- https://developer.apple.com/documentation/Technotes/tn3179-understanding-local-network-privacy

Remaining: real-device broadcast/discovery/duplex-direction tests, production pairing, ongoing authenticated clock refinement, adaptive drift/buffer correction and host output compensation.

The receiver uses a bounded 40 ms initial local playout buffer with reordering, silence for missing blocks and skipped overdue scheduler slots. The v1 mode uses only a local clock; v2 group mode maps the sender epoch using authenticated probes. Neither is a measured acoustic delay. See [synchronization staging](../docs/audio-synchronization.md).

## Shared-clock group pulse diagnostic
Use the explicit group test with 1–4 receiver IPs and one fresh shared test key. V2 receivers wait for authenticated clock samples; all use the same sender presentation timeline. The group test action sends only synthetic tone; the separate app-audio group action uses the same v2 wire. See [protocol and acceptance](../docs/audio-synchronization.md).

## Captured app-audio group diagnostic
In the app, enter 1–4 distinct comma-separated receiver IPs and a fresh shared key, start all selected receivers, then **Prepare group app-audio share (30 s)** and explicitly start the system broadcast. The extension uses the group transport on a dedicated serial network queue, only processing permitted .audioApp PCM. Slot numbers are reserved before per-peer send admission; expired blocks are discarded. V1 single-device sharing remains available. Stop with the system broadcast control; already transmitted buffered audio may briefly finish playing. Legacy ReplayKit physical signing, permissions, source compatibility, timing and drift remain unverified. Record actual device evidence in [Issue #30](https://github.com/Naos2w/SurSound/issues/30) and [Issue #31](https://github.com/Naos2w/SurSound/issues/31).

## Windows-only device testing (free Apple Account)

The separate `SurSoundDeviceTest` target uses the same LAN receiver, discovery, v1/v2 test-tone senders and shared-clock group playback, but intentionally excludes the ReplayKit upload extension and App Group entitlement. It cannot capture other apps' audio on iOS. The complete `SurSoundReceiver` app with its extension is unchanged and still needs compatible provisioning/signing and real-device validation.

GitHub Actions on the public repository builds a device (iphoneos arm64) version and uploads `sursound-ios-device-test-unsigned`. Unzip that Actions artifact to obtain `SurSoundDeviceTest-unsigned.ipa` and its SHA-256 checksum. This is **unsigned**, not directly installable. Verify the checksum and use a trusted local Windows sideloading tool with your own Apple Account and device; never send passwords, certificates or two-factor codes to the agent or public CI. Free personal provisioning typically expires after seven days and allows three active sideloaded apps. App Group capability is not assumed available under free provisioning. The test target deliberately avoids it rather than stripping entitlements from the full app.

See [Windows and physical device acceptance](../docs/device-acceptance.md). The CI IPA and simulator reports are not evidence of successful physical installation or synchronized speaker output.

For the strictly Apple-supported Personal Team route, Xcode on a trusted Mac must provision a paired device. GitHub Actions produces the unsigned device build only; no official Windows-only Personal Team installer was verified. Third-party Windows tools are optional and not Apple official signing clients. See the signing assessment in docs/device-acceptance.md.
