package com.naos.sursound

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/** Diagnostic controls only; no production design or background transmission. */
@SuppressLint("SetTextI18n")
class LanTestActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var session: LanTestSession? = null
    private lateinit var peer: EditText
    private lateinit var key: EditText
    private lateinit var status: TextView
    private lateinit var send: Button
    private lateinit var groupSend: Button
    private lateinit var receive: Button
    private lateinit var stop: Button
    private lateinit var generate: Button
    private lateinit var discovery: PeerDiscovery
    private lateinit var discoveredPeers: LinearLayout
    private lateinit var discover: Button
    private lateinit var scan: Button
    private lateinit var clearPairing: Button
    private var scannedTicket: PairingTicket? = null
    private var scanRequested = false
    private var receiving = false

    private val refresh = object : Runnable {
        override fun run() {
            session?.status?.let { state ->
                status.text = state.message + "\nPackets: " + state.packets + "\nMissing/rejected/scheduler/output drops: " + state.dropped
                if (!state.running) {
                    session = null
                    receiving = false
                    discovery.stopAdvertising()
                } else if (receiving && (state.message.startsWith("Listening") || state.message.startsWith("Playing"))) {
                    discovery.advertise()
                }
            }
            updateButtons()
            main.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanRequested = savedInstanceState?.getBoolean("scanRequested") ?: false
        val padding = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 2, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = "SurSound — LAN test tone"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "Local addresses: " + localAddresses() +
                "\nEnter the other device's private IPv4 address on each phone. Share a fresh pairing key privately. Start receiver first, then sender. Test stops when leaving this screen. The sender here emits only a test tone. The receiver also supports explicitly shared permitted app audio. For a shared-clock pulse test, enter up to four comma-separated receiver IPs and use a fresh key shared only with the selected group."
        })
        peer = EditText(this).apply {
            hint = "Peer IPv4; group: up to 4 comma-separated IPs"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            isSaveEnabled = false
        }
        key = EditText(this).apply {
            hint = "32-character pairing key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            isSaveEnabled = false
        }
        generate = Button(this).apply {
            text = "Generate fresh pairing key"
            setOnClickListener { key.setText(LanPacketCodec.generateKey()) }
        }
        send = Button(this).apply { text = "Send test tone (30 s)"; setOnClickListener { startTest(true) } }
        groupSend = Button(this).apply {
            text = "Send shared-clock group test (30 s)"
            setOnClickListener { startTest(sender = true, group = true) }
        }
        receive = Button(this).apply { text = "Listen (45 s)"; setOnClickListener { startTest(false) } }
        stop = Button(this).apply { text = "Stop test"; setOnClickListener { stopTest() } }
        status = TextView(this).apply { text = "Ready" }
        discoveredPeers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        discovery = PeerDiscovery(this) { addresses ->
            discoveredPeers.removeAllViews()
            for (address in addresses) {
                discoveredPeers.addView(Button(this).apply {
                    text = "Receiver: " + address
                    setOnClickListener {
                        peer.setText(address)
                        discovery.stopBrowsing()
                    }
                })
            }
        }
        discover = Button(this).apply {
            text = "Find local receivers (15 s)"
            setOnClickListener { discovery.browse() }
        }
        scan = Button(this).apply {
            setText(R.string.pair_scan)
            setOnClickListener {
                if (session != null) return@setOnClickListener
                clearScannedPairing()
                scanRequested = true
                try { PairingScan.launch(this@LanTestActivity) } catch (_: RuntimeException) {
                    scanRequested = false
                    status.setText(R.string.pair_scan_failed)
                }
            }
        }
        clearPairing = Button(this).apply {
            setText(R.string.pair_clear)
            setOnClickListener { clearScannedPairing(); status.text = "Ready" }
        }
        listOf(peer, key, generate, scan, clearPairing, discover, discoveredPeers, receive, send, groupSend, stop, status).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root); fitsSystemWindows = true })
        updateButtons()
    }

    override fun onStart() {
        super.onStart()
        main.post(refresh)
    }

    override fun onStop() {
        main.removeCallbacks(refresh)
        stopTest()
        discovery.close()
        scannedTicket = null
        key.text.clear()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("scanRequested", scanRequested)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Used for local QR scan in the diagnostic Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PairingScan.REQUEST || !scanRequested) return
        scanRequested = false
        if (resultCode != RESULT_OK || session != null) return
        val ticket = PairingScan.parse(resultCode, data)
        if (ticket == null) {
            status.setText(R.string.pair_invalid)
        } else {
            discovery.stopBrowsing()
            scannedTicket = ticket
            peer.setText(ticket.peer)
            key.setText(ticket.keyHex)
            status.setText(R.string.pair_tone_review)
        }
        updateButtons()
    }

    private fun clearScannedPairing() {
        scannedTicket = null
        peer.text.clear()
        key.text.clear()
        updateButtons()
    }

    private fun startTest(sender: Boolean, group: Boolean = false) {
        if (session != null) return
        try {
            scannedTicket?.let {
                require(sender && !group && it.isValidAt(System.currentTimeMillis() / 1000)) {
                    getString(R.string.pair_expired)
                }
            }
            val addresses = if (group) peer.text.toString().split(',').map { LanPacketCodec.localPeer(it) }
                else listOf(LanPacketCodec.localPeer(peer.text.toString()))
            require(addresses.size in 1..4 && addresses.distinct().size == addresses.size) {
                "Enter one to four distinct private IPv4 addresses."
            }
            val bytes = LanPacketCodec.parseKey(key.text.toString())
            try {
                discovery.stopBrowsing()
                receiving = !sender
                session = when {
                    group -> LanTestSession.groupSender(addresses, bytes)
                    sender -> LanTestSession.sender(addresses.single(), bytes)
                    else -> LanTestSession.receiver(addresses.single(), bytes)
                }
            } finally { bytes.fill(0) }
            scannedTicket = null
            key.text.clear()
            status.text = "Starting"
        } catch (e: IllegalArgumentException) {
            status.text = e.message ?: "Invalid peer or key"
        }
        updateButtons()
    }

    private fun stopTest() {
        receiving = false
        discovery.stopAdvertising()
        session?.let { current ->
            current.stop()
            status.text = "Stopping"
        }
        updateButtons()
    }

    private fun updateButtons() {
        val ready = session == null
        val manual = ready && scannedTicket == null
        peer.isEnabled = manual
        key.isEnabled = manual
        generate.isEnabled = manual
        discover.isEnabled = manual
        scan.isEnabled = ready
        clearPairing.isEnabled = ready && scannedTicket != null
        send.isEnabled = ready
        groupSend.isEnabled = manual
        receive.isEnabled = manual
        stop.isEnabled = !ready
    }

    private fun localAddresses(): String = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter { runCatching { LanPacketCodec.localPeer(it) }.isSuccess }
            .distinct().joinToString().ifBlank { "Unavailable" }
    }.getOrDefault("Unavailable")
}
