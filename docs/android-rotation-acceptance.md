# Android rotation regression — Issue #70

The native diagnostic screens are built programmatically inside scrollable layouts and do not require orientation-specific resource reinflation. Keep the same Activity/view hierarchy during size-only configuration changes (orientation, screen size/layout, smallest screen size and keyboard visibility). This preserves the in-memory QR review, form fields, pending explicit Android consent and foreground LAN session without serializing a pairing key or token.

Actual navigation/backgrounding of LAN Test still stops its diagnostic session and clears its key. Process death or another configuration requiring recreation is not an automatic reconnect; missing transient consent/keys must fail closed. MediaProjection still owns its real foreground service lifecycle, system indicators and Stop actions. No global orientation lock, no automatic camera permission or capture.

## Acceptance on physical Android
1. Launch LAN Test, enter a disposable manual test address/key; rotate portrait → landscape → portrait. Verify fields and scroll position/controls remain usable.
2. Scan an unexpired QR, return to the selected receiver review, rotate both ways. Verify receiver selection is retained and key remains masked. Start a fresh 30-second tone; rotate while it is playing and verify receiver counters continue. Do not expose the key or QR in logs/screenshots.
3. Open the single/live share dialog, rotate both ways while entering data or reviewing a scanned QR. Confirm fields scroll in landscape with the keyboard shown. Confirm expired QR is still rejected and no capture starts until explicit Android consent.
4. Cancel scanner and share dialog; navigate back from LAN Test during a session; explicitly Stop. Confirm the prior fail-closed behavior. Reopen and verify old key not reused.
5. Rotate during an authorized live session, then stop through notification; verify counters and cleanup.
6. Repeat actual discovery, long-duration and recovery acceptance separately. No acoustic timing claim.

CI checks the actual APK manifest for the two Activities' exact size-only config mask and no fixed orientation, plus scanner DEX definition coverage. This is a packaging guard, not an emulator or physical rotation test.
