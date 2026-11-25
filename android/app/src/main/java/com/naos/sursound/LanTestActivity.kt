package com.naos.sursound

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
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
    private lateinit var receive: Button
    private lateinit var stop: Button
    private lateinit var generate: Button
    private lateinit var discovery: PeerDiscovery
    private lateinit var discoveredPeers: LinearLayout
    private lateinit var discover: Button
    private var receiving = false

    private val refresh = object : Runnable {
        override fun run() {
            session?.status?.let { state ->
                status.text = state.message + "\nPackets: " + state.packets + "\nMissing/rejected/output drops: " + state.dropped
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
                "\nEnter the other device's private IPv4 address on each phone. Share a fresh pairing key privately. Start receiver first, then sender. Test stops when leaving this screen. The sender here emits only a test tone. The receiver also supports explicitly shared permitted app audio."
        })
        peer = EditText(this).apply {
            hint = "Other device IP (e.g. 192.168.1.20)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            isSaveEnabled = false
        }
        key = EditText(this).apply {
            hint = "32-character pairing key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            isSaveEnabled = false
        }
        generate = Button(this).apply {
            text = "Generate fresh pairing key"
            setOnClickListener { key.setText(LanPacketCodec.generateKey()) }
        }
        send = Button(this).apply { text = "Send test tone (30 s)"; setOnClickListener { startTest(true) } }
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
        listOf(peer, key, generate, discover, discoveredPeers, receive, send, stop, status).forEach { root.addView(it) }
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
        key.text.clear()
        super.onStop()
    }

    private fun startTest(sender: Boolean) {
        if (session != null) return
        try {
            val address = LanPacketCodec.localPeer(peer.text.toString())
            val bytes = LanPacketCodec.parseKey(key.text.toString())
            try {
                discovery.stopBrowsing()
                receiving = !sender
                session = if (sender) LanTestSession.sender(address, bytes) else LanTestSession.receiver(address, bytes)
            } finally { bytes.fill(0) }
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
        peer.isEnabled = ready
        key.isEnabled = ready
        generate.isEnabled = ready
        discover.isEnabled = ready
        send.isEnabled = ready
        receive.isEnabled = ready
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
