# iOS LAN receiver — Phase 0

Native SwiftUI diagnostic, iOS 16+. No iOS capture or screen recording. Build on macOS with Xcode and XcodeGen:

```sh
cd ios
xcodegen generate
xcodebuild -project SurSoundReceiver.xcodeproj -scheme SurSoundReceiver -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

For a physical iPhone, open the generated project, select your own development signing team and device, and Run. CI simulator tests are not a signed iPhone installation or real-device audio validation.

On the same Wi-Fi network as Android, enter the Android sender’s private IPv4 address and a fresh 32-character hexadecimal key shared privately between devices. Tap Listen, grant local-network access if prompted, then start Android's explicit Share permitted app audio using the iPhone's IP address (Settings → Wi-Fi → current network). The receiver accepts only authenticated packets from the entered IP and first authenticated session, rejects replay and malformed/wrong-key packets, and plays stereo PCM through AVAudioEngine. Listen is limited to 45 seconds; backgrounding or interruption stops playback. No captured audio is stored. Test with a fresh key for each attempt. Both physical devices, denied-source cases and audible output remain unverified.

The Android and iOS protocol tests use the same deterministic AES-GCM golden-vector digest, separate from live networking. The current diagnostic does not perform timestamp alignment, jitter buffering, discovery or production pairing.
