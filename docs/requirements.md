# Product requirements

## Confirmed
- Name: SurSound; selected logo is the coral-orange S integrated with sound-wave curves; flat single-colour, no gradient.
- General-purpose system audio sharing; no built-in media player.
- Android and iOS devices can act as host or receiver where supported.
- Core modes: same audio (party), separate left/right (stereo), and multi-channel surround.
- Prioritize Wi-Fi LAN and personal hotspot; BLE can assist discovery/pairing.
- Core local functionality should not require sign-in, internet or a cloud backend.
- Respect platform permissions and content capture restrictions.

## Phase 0
Validate Android AudioPlaybackCapture and iOS ReplayKit/available capture APIs separately; demonstrate permitted audio capture, local PCM transmission, receiver playback, then measure delay. Source app compatibility is not guaranteed.

Host playback may not be controllable; receiver-only synchronization is a fallback, and genuine surround requires a multi-channel source. Stereo-derived surround must be labelled as simulated.

## Later
Clock drift correction, latency calibration, channel mapping, reconnect, background handling, secure pairing and acoustic validation.
