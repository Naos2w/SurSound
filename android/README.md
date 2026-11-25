# Android playback capture — Phase 0

Standalone native Kotlin diagnostic, not the production UI. Android 10+ (API 29), compile/target API 35, JDK 17, Gradle 8.9, AGP 8.7.3.

## Build
From android/: `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` with the stated Gradle/SDK installed. CI provisions Gradle explicitly. Debug APK: app/build/outputs/apk/debug/app-debug.apk.

## Device acceptance (not yet verified)
1. Install the debug APK on an Android 10+ device. Start capture, grant audio permission and confirm the OS MediaProjection prompt. No screen image is obtained.
2. Play a permitted, non-protected audio source in another app. Return to the diagnostic screen: frames and non-zero samples should increase. A zero-only signal is inconclusive (silence, capture policy or source limitations).
3. Stop playback, check the peak falls, then stop capture from the app or notification. Confirm the session stops, OS sharing indicator disappears and counters freeze.
4. Repeat consent, cancellation, permission denial, OS revocation, notification stop, backgrounding and lock screen. Test protected/denied sources without bypassing policy.
5. Record Android model, OS version, source, result and failures in [Android device capture acceptance (#11)](https://github.com/Naos2w/SurSound/issues/11).

The capture-only diagnostic does not transmit audio. Neither mode persists audio files, uses microphone input, creates a virtual display or reads screen frames. PCM is kept only in a reusable in-memory buffer and cleared after statistics are computed. Timestamp is the local read observation, not a presentation timestamp or sync measurement.

## LAN test-tone acceptance (not yet verified on devices)
1. Connect two Android phones to the same Wi-Fi or hotspot. Open LAN test tone and note each local IPv4 address. Enter the other phone’s private address on each device.
2. Generate a fresh 128-bit pairing key, share it privately with the other device and enter the same key. Do not reuse a key across tests. Start Listen first, then Send. Receiver should play a quiet 440 Hz synthetic tone; packet counts should increase. No captured audio is sent.
3. Stop either test or leave the screen. Sender is bounded to 30 seconds, listener to 45 seconds. Repeat with a wrong key; playback should not begin. Verify both phones on real Wi-Fi and hotspot.
4. Record the device/OS, connectivity, packet counts, drops and actual audible result in [LAN playback acceptance (#22)](https://github.com/Naos2w/SurSound/issues/22).

LAN PoC uses UDP port 39771, private/link-local IPv4 peers, AES-128-GCM with authenticated headers, per-session nonce prefix and packet sequence. Each packet contains 5 ms of stereo PCM16, 48 kHz (993 bytes including header/tag). First authenticated session is locked; stale, duplicate and foreign packets are rejected. Manual fresh-key pairing is a test mechanism, not a production discovery or key exchange protocol. Receiver uses AudioTrack with no jitter buffer or clock synchronization yet. The app needs INTERNET permission only for explicit LAN sessions; no cloud connection or audio storage is implemented.

## Share permitted app audio (device acceptance pending)
1. Open the receiver on a second Android phone or the native iOS receiver. Enter the Android sender's private IPv4 address and a fresh 32-character hexadecimal pairing key. Start Listen.
2. On the Android sender, choose Share permitted app audio (30 s), enter the receiver's private IPv4 address and the same key, and grant audio-capture and notification consent. Switch to a permitted source app and play audio. The capture-only button never sends audio.
3. Verify the receiving device plays the source and the sender reports transmitted packets. Test the wrong key, a source that forbids capture, revocation, stop notification and timeout. Sharing stops after 30 seconds; receiver listening stops after 45 seconds. No automatic reconnect.
4. The sender packetizes PCM into the existing authenticated 5 ms format with a bounded 12-packet memory queue. The receiver currently plays arrival-order packets, without synchronized clocks, retransmission or jitter correction. UDP send counts do not prove delivery.

Sources:
- https://developer.android.com/media/platform/av-capture
- https://developer.android.com/develop/background-work/services/fgs/service-types

## Discover receivers
Android advertises a random-named `_sursound._udp` Bonjour service only while its receiver is listening. The LAN diagnostic and the app-audio sharing dialog offer a 15-second explicit browse; select an advertised private IPv4 address or enter it manually. Discovery does not authenticate the peer; retain a fresh matching 128-bit key. Test Android/iOS discovery, service loss, local permission and hotspot behavior on physical devices. Receiving from the iOS synthetic tone or legacy ReplayKit sender uses the same packet format, but neither reverse-direction device playback nor iOS capture is yet verified.
