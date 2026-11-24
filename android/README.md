# Android playback capture — Phase 0

Standalone native Kotlin diagnostic, not the production UI. Android 10+ (API 29), compile/target API 35, JDK 17, Gradle 8.9, AGP 8.7.3.

## Build
From android/: `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` with the stated Gradle/SDK installed. CI provisions Gradle explicitly. Debug APK: app/build/outputs/apk/debug/app-debug.apk.

## Device acceptance (not yet verified)
1. Install the debug APK on an Android 10+ device. Start capture, grant audio permission and confirm the OS MediaProjection prompt. No screen image is obtained.
2. Play a permitted, non-protected audio source in another app. Return to the diagnostic screen: frames and non-zero samples should increase. A zero-only signal is inconclusive (silence, capture policy or source limitations).
3. Stop playback, check the peak falls, then stop capture from the app or notification. Confirm the session stops, OS sharing indicator disappears and counters freeze.
4. Repeat consent, cancellation, permission denial, OS revocation, notification stop, backgrounding and lock screen. Test protected/denied sources without bypassing policy.
5. Record Android model, OS version, source, result and failures in the existing TODO.

The capture path does not transmit audio, persist audio files, use microphone input, create a virtual display or read screen frames. PCM is kept only in a reusable in-memory buffer and cleared after statistics are computed. Timestamp is the local read observation, not a presentation timestamp or sync measurement.

## LAN test-tone acceptance (not yet verified on devices)
1. Connect two Android phones to the same Wi-Fi or hotspot. Open LAN test tone and note each local IPv4 address. Enter the other phone’s private address on each device.
2. Generate a fresh 128-bit pairing key, share it privately with the other device and enter the same key. Do not reuse a key across tests. Start Listen first, then Send. Receiver should play a quiet 440 Hz synthetic tone; packet counts should increase. No captured audio is sent.
3. Stop either test or leave the screen. Sender is bounded to 30 seconds, listener to 45 seconds. Repeat with a wrong key; playback should not begin. Verify both phones on real Wi-Fi and hotspot.
4. Record the device/OS, connectivity, packet counts, drops and actual audible result in tasks/todo.md.

LAN PoC uses UDP port 39771, private/link-local IPv4 peers, AES-128-GCM with authenticated headers, per-session nonce prefix and packet sequence. Each packet contains 5 ms of stereo PCM16, 48 kHz (993 bytes including header/tag). First authenticated session is locked; stale, duplicate and foreign packets are rejected. Manual fresh-key pairing is a test mechanism, not a production discovery or key exchange protocol. Receiver uses AudioTrack with no jitter buffer or clock synchronization yet. The app needs INTERNET permission for LAN test only; no cloud connection, storage or captured audio transmission is implemented.

Sources:
- https://developer.android.com/media/platform/av-capture
- https://developer.android.com/develop/background-work/services/fgs/service-types
