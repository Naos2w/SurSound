# Architecture — proposed, not yet validated

Host captures permitted app audio using native platform APIs, timestamps frames and sends them over a local network. Receivers buffer and schedule audio against a synchronized monotonic clock.

- Android: Kotlin; AudioPlaybackCapture / MediaProjection (Android 10+), Oboe/AAudio to evaluate.
- iOS: Swift; ReplayKit broadcast extension and other supported capture APIs to evaluate, AVAudioEngine playback.
- UI: React Native + TypeScript (after PoC).
- Shared native core: C++ where useful; initially PCM, evaluate Opus later.
- Transport target: UDP/RTP-style timestamped frames, authenticated control channel; mDNS discovery first, BLE later.
- Android LAN PoC: synthetic 440 Hz tone and opt-in permitted playback sharing (30 seconds), 5 ms stereo PCM16 frames at 48 kHz, AES-GCM authenticated packets, fresh manual 128-bit pairing key, private IPv4 peer, sequence/replay checks and AudioTrack output. The capture-sharing path uses a bounded 12-packet sender queue. iOS receiver uses CryptoKit, Network.framework and AVAudioEngine, with a 45-second foreground session and bounded playback queue. No timestamp alignment, jitter buffer, discovery, iOS capture or production pairing yet.
- Synchronization: clock-offset estimation, jitter buffer, output-latency and sample-clock drift correction.
- No server/account/database for local MVP. Do not store or upload captured audio by default.
- Validate actual audible timing, not only network RTT. Target <=5 ms receiver-to-receiver acoustic offset, not a promise. Host + receivers and end-to-end latency are separate measurements.
- Windows can develop Android; iOS build/signing/physical-device validation needs an appropriate macOS environment.
