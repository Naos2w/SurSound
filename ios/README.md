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
4. To test iOS app audio, grant Local Network access using discovery in the foreground, then Prepare 30-second share. Tap the actual system broadcast control and confirm Start Broadcast. Switch to a permitted audio source. Stop via the system recording indicator; the extension also ends on timeout or pause. Only `.audioApp` PCM is converted to 48 kHz stereo PCM16, packetized and encrypted; microphone and video samples are discarded. No media is written to disk. A protected, short-lived, single-use App Group file carries only the peer/key and is consumed by the extension (or expires and is cleaned up on cancellation/app return). Do not bypass a denied source.
5. Record device OS versions, signing, permissions, packet counts, audible result and failure cases in [iOS device acceptance (#15)](https://github.com/Naos2w/SurSound/issues/15) and [bidirectional device acceptance (#28)](https://github.com/Naos2w/SurSound/issues/28).

Apple now deprecates the ReplayKit broadcast APIs in favor of ScreenCaptureKit; Apple's iOS ScreenCaptureKit sample requires iOS 27+. This implementation is a compatibility PoC, not a claim that legacy broadcast works on iOS 27. Migrate to the newer system content-sharing picker/capture APIs on an appropriate SDK in a subsequent task. Neither simulator tests nor CI prove arbitrary app audio capture.

References:
- https://developer.apple.com/documentation/replaykit/rpbroadcastsamplehandler
- https://developer.apple.com/documentation/replaykit/rpsystembroadcastpickerview
- https://developer.apple.com/documentation/screencapturekit/capturing-screen-content-on-ios
- https://developer.apple.com/documentation/Technotes/tn3179-understanding-local-network-privacy

Remaining: real-device broadcast/discovery/duplex-direction tests, production pairing, clock synchronization, jitter correction and host output compensation.

The receiver uses a bounded 40 ms initial local playout buffer with reordering, silence for missing blocks and skipped overdue scheduler slots. This is not yet shared-clock synchronization or a measured acoustic delay. See [synchronization staging](../docs/audio-synchronization.md).
