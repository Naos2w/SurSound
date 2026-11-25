package com.naos.sursound

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView

/** Temporary native diagnostic screen, not the final product UI. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var share: Button
    private lateinit var groupShare: Button
    private lateinit var liveShare: Button
    private var shareLive = false
    private var scanLive = false
    private var shareGroup = false
    private var shareRequested = false
    private var sharePeer: String? = null
    private var shareKey: ByteArray? = null
    private var pending = false
    private var shareDiscovery: PeerDiscovery? = null
    private var scanRequested = false
    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pending = savedInstanceState?.getBoolean("pending") ?: false
        shareRequested = savedInstanceState?.getBoolean("shareRequested") ?: false
        scanRequested = savedInstanceState?.getBoolean("scanRequested") ?: false
        scanLive = savedInstanceState?.getBoolean("scanLive") ?: false
        val padding = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 2, padding, padding)
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            setText(R.string.title)
            textSize = 22f
        })
        root.addView(TextView(this).apply { setText(R.string.description) })
        start = Button(this).apply {
            setText(R.string.start_capture)
            setOnClickListener { requestCapture() }
        }
        stop = Button(this).apply {
            setText(R.string.stop_capture)
            setOnClickListener { startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)) }
        }
        status = TextView(this).apply { textSize = 16f }
        root.addView(start)
        share = Button(this).apply {
            setText(R.string.share_capture)
            setOnClickListener { showShareDialog() }
        }
        root.addView(share)
        liveShare = Button(this).apply {
            setText(R.string.share_live)
            setOnClickListener { showShareDialog(live = true) }
        }
        root.addView(liveShare)
        groupShare = Button(this).apply {
            setText(R.string.share_group_capture)
            setOnClickListener { showShareDialog(group = true) }
        }
        root.addView(groupShare)
        root.addView(stop)
        root.addView(Button(this).apply {
            setText(R.string.lan_test)
            setOnClickListener { startActivity(Intent(this@MainActivity, LanTestActivity::class.java)) }
        })
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root); fitsSystemWindows = true })
        render()
    }

    override fun onStart() {
        super.onStart()
        main.post(refresh)
    }

    override fun onStop() {
        main.removeCallbacks(refresh)
        shareDiscovery?.close()
        shareDiscovery = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending", pending)
        outState.putBoolean("shareRequested", shareRequested)
        outState.putBoolean("scanRequested", scanRequested)
        outState.putBoolean("scanLive", scanLive)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val s = CaptureState.snapshot
        start.isEnabled = !pending && !s.active
        share.isEnabled = start.isEnabled
        groupShare.isEnabled = start.isEnabled
        liveShare.isEnabled = start.isEnabled
        stop.isEnabled = s.phase == CaptureState.Phase.STARTING || s.phase == CaptureState.Phase.CAPTURING
        status.text = buildString {
            appendLine(s.phase.name)
            appendLine(s.message)
            appendLine("PCM16 • 48,000 Hz • stereo")
            appendLine("Frames: " + s.reading.frames)
            appendLine("Non-zero samples: " + s.reading.nonZeroSamples)
            appendLine("Last block peak: " + s.reading.peak + " / 32768")
            s.share?.let { share ->
                appendLine("LAN mode: " + when {
                    share.live -> "live single receiver"
                    share.group -> "v2 group"
                    else -> "v1 single peer"
                })
                share.connection?.let { appendLine("Connection: " + it) }
                if (share.live) appendLine("Recovered connections: " + share.recoveries)
                appendLine("Selected receivers: " + share.peers)
                appendLine("UDP send calls accepted: " + share.sent)
                appendLine("Sender drops: " + share.dropped)
                appendLine("Queued datagrams discarded on teardown: " + share.discardedQueued)
                appendLine("Receiver delivery is not confirmed by send counts.")
                if (!s.active && !share.senderFinished) appendLine("Sender teardown snapshot is provisional.")
            }
            if (s.phase == CaptureState.Phase.CAPTURING && s.reading.nonZeroSamples == 0L) {
                appendLine("No audible signal observed. Source may be silent or disallow capture.")
            }
            append("Read timestamp (local monotonic ns): " + s.lastReadNanos)
        }
    }

    private fun showShareDialog(group: Boolean = false, ticket: PairingTicket? = null, live: Boolean = false) {
        if (pending || CaptureState.snapshot.active) return
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, 0, p, 0)
        }
        val peer = EditText(this).apply {
            setHint(if (group) R.string.share_group_peers else R.string.share_peer)
            if (ticket != null) setText(ticket.peer)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            isSaveEnabled = false
        }
        val key = EditText(this).apply {
            setHint(R.string.share_key)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (ticket != null) setText(ticket.keyHex)
            setSingleLine(true)
            isSaveEnabled = false
        }
        peer.isEnabled = ticket == null
        key.isEnabled = ticket == null
        fields.addView(peer)
        fields.addView(key)
        if (ticket != null) fields.addView(TextView(this).apply { setText(R.string.pair_review) })
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val discovery = PeerDiscovery(this) { addresses ->
            results.removeAllViews()
            addresses.take(5).forEach { address ->
                results.addView(Button(this).apply {
                    text = address
                    setOnClickListener {
                        if (group && peer.text.isNotBlank()) {
                            val current = peer.text.toString().split(',').map { it.trim() }
                            if (address !in current && current.size < 4) peer.setText(current.joinToString(",") + "," + address)
                        } else peer.setText(address)
                        if (!group) shareDiscovery?.stopBrowsing()
                    }
                })
            }
        }
        shareDiscovery?.close()
        shareDiscovery = discovery
        if (ticket == null) {
            fields.addView(Button(this).apply {
                setText(R.string.find_receivers)
                setOnClickListener { discovery.browse() }
            })
            fields.addView(results)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(when {
                live -> R.string.share_live
                group -> R.string.share_group_capture
                else -> R.string.share_capture
            })
            .setMessage(when {
                live -> R.string.share_live_warning
                group -> R.string.share_group_warning
                else -> R.string.share_warning
            })
            .setView(fields)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.share_continue, null)
            .apply { if (!group) setNeutralButton(R.string.pair_scan, null) }
            .create()
        dialog.setOnDismissListener {
            key.text.clear()
            discovery.close()
            if (shareDiscovery === discovery) shareDiscovery = null
        }
        dialog.setOnShowListener {
            if (ticket == null) discovery.browse()
            if (!group) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                scanRequested = true
                scanLive = live
                try { PairingScan.launch(this) } catch (_: RuntimeException) {
                    scanRequested = false
                    CaptureState.reportConsentFailure(getString(R.string.pair_scan_failed))
                    render()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    if (ticket != null && (ticket.live != live || !ticket.isValidAt(System.currentTimeMillis() / 1000))) {
                        throw IllegalArgumentException(getString(R.string.pair_expired))
                    }
                    val addresses = capturePeers(peer.text.toString(), group)
                    val secret = LanPacketCodec.parseKey(key.text.toString())
                    clearShare()
                    sharePeer = addresses.joinToString(",") { requireNotNull(it.hostAddress) }
                    shareGroup = group
                    shareLive = live
                    shareKey = secret
                    shareRequested = true
                    dialog.dismiss()
                    requestCapture()
                } catch (e: IllegalArgumentException) {
                    key.error = e.message ?: getString(R.string.share_invalid)
                }
            }
        }
        dialog.show()
    }

    private fun clearShare() {
        shareKey?.fill(0)
        shareKey = null
        sharePeer = null
        shareGroup = false
        shareLive = false
        shareRequested = false
    }

    override fun onDestroy() {
        shareDiscovery?.close()
        shareDiscovery = null
        clearShare()
        super.onDestroy()
    }

    private fun requestCapture() {
        if (pending || CaptureState.snapshot.active) return
        pending = true
        render()
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), PERMISSIONS)
        else continueConsent()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            continueConsent()
        } else {
            pending = false
            clearShare()
            CaptureState.reportConsentFailure("Playback-capture audio permission denied.")
            render()
        }
    }

    private fun continueConsent() {
        if (shareRequested && (shareKey == null || sharePeer == null ||
                (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED))) {
            pending = false
            clearShare()
            CaptureState.reportConsentFailure(getString(R.string.share_permission_required))
            render()
            return
        }
        launchConsent()
    }

    @Suppress("DEPRECATION")
    private fun launchConsent() {
        try {
            val manager = requireNotNull(getSystemService(MediaProjectionManager::class.java))
            startActivityForResult(manager.createScreenCaptureIntent(), CONSENT)
        } catch (e: RuntimeException) {
            pending = false
            clearShare()
            CaptureState.reportConsentFailure("Unable to request capture consent: " + e.javaClass.simpleName)
            render()
        }
    }

    @Deprecated("Used for platform consent in this standalone PoC")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PairingScan.REQUEST) {
            if (!scanRequested) return
            scanRequested = false
            if (resultCode == RESULT_OK && !pending && !CaptureState.snapshot.active) {
                val ticket = PairingScan.parse(resultCode, data)
                if (ticket == null || ticket.live != scanLive) {
                    CaptureState.reportConsentFailure(getString(R.string.pair_invalid))
                    render()
                } else {
                    // A scan only fills a review dialog. The user must continue to Android consent.
                    showShareDialog(ticket = ticket, live = scanLive)
                }
            }
            return
        }
        if (requestCode != CONSENT || !pending) return
        pending = false
        try {
            if (resultCode != RESULT_OK || data == null) {
                CaptureState.reportConsentFailure("Capture consent was not granted.")
            } else if (shareRequested && (shareKey == null || sharePeer == null)) {
                CaptureState.reportConsentFailure(getString(R.string.share_permission_required))
            } else if (!CaptureState.snapshot.active) {
                val request = Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                if (shareRequested) {
                    request.putExtra(CaptureService.EXTRA_SHARE_PEER, sharePeer)
                    request.putExtra(CaptureService.EXTRA_SHARE_KEY, shareKey?.copyOf())
                    request.putExtra(CaptureService.EXTRA_SHARE_GROUP, shareGroup)
                    request.putExtra(CaptureService.EXTRA_SHARE_LIVE, shareLive)
                }
                startForegroundService(request)
            }
        } catch (e: RuntimeException) {
            CaptureState.reportConsentFailure("Unable to start capture: " + e.javaClass.simpleName)
        } finally {
            clearShare()
        }
        render()
    }

    companion object {
        private const val PERMISSIONS = 10
        private const val CONSENT = 11
    }
}
