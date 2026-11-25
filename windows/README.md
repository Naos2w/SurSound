# SurSound Windows receiver (local diagnostic)

This is the maintained v1 single-receiver counterpart of the temporary Python/WinMM diagnostic used for the successful 2026-11-25 Android → MSI synthetic tone and permitted app audio acceptance. The repository refactor must be retested on hardware. No capture, cloud, recordings, production GUI, automatic reconnect or group timing claims.

## Run (Windows, Python 3.12)

From this directory, create an isolated virtual environment and install the pinned dependency:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m sursound_receiver --bind <PC_PRIVATE_IPV4> --sender <PHONE_PRIVATE_IPV4>
```

Replace both address placeholders with the devices' actual private addresses. The receiver opens UDP 39771 on only the selected local interface and generates a fresh 128-bit pairing key. Keep that key private; do not redirect or publish the READY line. Enter the PC IP and displayed key on the phone. For Android, use LAN TEST TONE → Send test tone, or Share permitted app audio and explicitly grant system consent. For iOS, use the compatible v1 synthetic sender or a properly signed, explicitly authorised full capture app. Set Windows volume to a comfortable level yourself. The receiver does not alter the system volume or firewall.

The receiver waits up to ten minutes (configurable with --wait), then accepts a single 30-second sender test, with a 35-second cap after first valid packet. It stops on inactivity after draining known slots, timeout, or Ctrl+C. A new receiver invocation creates a fresh key; no automatic replay/reconnect or persistent session. Use only the sender address you chose. The receiver refuses v2 group packets instead of silently playing them unsynchronised.

## QR pairing and automatic discovery

For a private interactive Windows session, launch:

```powershell
.\.venv\Scripts\python.exe -m sursound_receiver --bind <PC_PRIVATE_IPV4> --sender <PHONE_PRIVATE_IPV4> --discover --qr
```

The optional discovery announcement is scoped to the selected IPv4 interface and contains only a random service name, receiver address/port and protocol version, never the key. On Android, opening the share dialog or LAN test screen starts a bounded receiver browse; choose the PC or use **Scan receiver QR**. The local Windows window shows a private expiring v1 ticket. Scanning only fills the selected address/key; press Send test tone or explicitly continue through Android capture consent. A scanned v1 offer cannot start a group test. QR closes and discovery withdraws on the first authenticated packet; the receiver binds to one sender endpoint/session. Closing the QR window while waiting cancels the receiver. Fresh offer for every invocation; default lifetime is ten minutes. Keep both device clocks accurate.

A QR is an out-of-band bearer credential, not proof of receiver identity: only scan a QR displayed by the intended trusted receiver, and do not share screenshots. No QR image files or automatic clipboard access. QR mode does not print the key in console output; the private window also provides manual entry fallback. Without --qr the existing manual console key workflow remains. If multicast or camera permission is unavailable, use the explicit IP/key path. No automatic capture, persistent pairing or reconnect.

## Timing and diagnostics

- v1 AES-128-GCM header authentication, session/endpoint lock, bounded 128-packet reorder window, replay rejection.
- 160 ms initial source timeline span and at most four queued 20 ms output buffers (hard sink bound six). The hardware-buffer completion drives submissions, not a 5 ms Python timer. Missing known sequence slots are rendered as silence; no unbounded catch-up or silence after the last observed slot. These design values are not measured acoustic latency.
- Unity PCM gain, no recording or media persistence. Periodic JSON counters distinguish raw datagrams, authentication, sequence admission, missing slots, output PCM nonzero/peak and submitted/completed/dropped buffers. Output completion alone is not proof of audible playback. Test with an actual listener.
- Unit tests exercise the Android/iOS v1 golden vector, tampering, wrong key, endpoint/session lock, reordering, bounded buffer and silence accounting. CI runs portable tests; only Windows with a functioning endpoint can test WinMM output.

See [device acceptance](../docs/device-acceptance.md) and [Issue #60](https://github.com/Naos2w/SurSound/issues/60). V2 shared-clock Windows reception, hardware timestamps, sample-rate compensation, calibrated acoustic offset, production identity authentication, group/iOS QR pairing and longer sessions remain separate work.

## Long-duration live mode (Android sender, experimental)

Launch the receiver with `--live --discover --qr` in addition to its selected `--bind` / `--sender` addresses. Use Android **Live audio share (Windows)** and scan the live QR or enter its fresh key manually. Review the receiver and grant the normal Android MediaProjection consent. The ordinary v1 and v2 test buttons are unchanged and cannot use a live ticket. The live session lasts until stopped, with a 60-minute experimental safety ceiling. In QR mode a local Stop receiving control replaces the private QR after the first authenticated probe; console Ctrl+C also stops. Android's notification/App Stop and projection revocation stop capture. A missing STOP datagram is covered by timeout.

The sender waits for a fresh HMAC-authenticated ACK before any audio transmission and probes every 500 ms. If ACKs cease for 1.5 s, it pauses and discards queued PCM; retries use a new generation with a separately derived audio key. The receiver drops queued output and rejects old-generation data after probe/audio loss. Recovery is bounded to 10 s of waiting on each endpoint. It requires the same authorized Android Wi-Fi network, on-link IPv4, selected receiver and sender socket/session; a changed network/address, process restart, revoked consent, exceeded limit or prolonged interruption requires a fresh pairing and authorization. No discovery-based automatic peer switch. A stopped receiver plays no further data; the sender detects missing ACKs and ends after its bounded recovery period. This is a same-session transient-loss recovery, not arbitrary network-roaming or persistent pairing.

Live control `SLC1`: big-endian magic(4), kind(1: probe/ack/stop), session u64, generation u32 (0–4095), positive counter u64 (<=2^63−1), HMAC-SHA256(32). Control key is HMAC(pairingKey, UTF8("SurSound/live/control/v1")); audio key is the first 16 bytes of HMAC(pairingKey, UTF8("SurSound/live/audio/v1") || session || generation). Existing SSP0 AES-GCM frames use this derived key and a new encoder session per generation. ACK echoes the exact outstanding probe; no replay or implicit legacy fallback. Fresh per-invocation pairing key is required. Control and audio counters contain no raw media; no audio file is created. The 160 ms receiver prebuffer and hardware output queue remain uncalibrated. Live mode is single-receiver only and must not be counted toward the 5 ms target.
