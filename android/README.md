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

No network permission, audio files, microphone input, virtual display or screen frames. PCM is kept only in a reusable in-memory buffer and cleared after statistics are computed. Timestamp is the local read observation, not a presentation timestamp or sync measurement.

Sources:
- https://developer.android.com/media/platform/av-capture
- https://developer.android.com/develop/background-work/services/fgs/service-types
