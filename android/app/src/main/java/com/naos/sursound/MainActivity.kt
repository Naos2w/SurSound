package com.naos.sursound

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Temporary native diagnostic screen, not the final product UI. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private var pending = false
    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pending = savedInstanceState?.getBoolean("pending") ?: false
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
        root.addView(stop)
        root.addView(Button(this).apply {
            setText(R.string.lan_test)
            setOnClickListener { startActivity(Intent(this@MainActivity, LanTestActivity::class.java)) }
        })
        root.addView(status)
        setContentView(root)
        render()
    }

    override fun onStart() {
        super.onStart()
        main.post(refresh)
    }

    override fun onStop() {
        main.removeCallbacks(refresh)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending", pending)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val s = CaptureState.snapshot
        start.isEnabled = !pending && !s.active
        stop.isEnabled = s.phase == CaptureState.Phase.STARTING || s.phase == CaptureState.Phase.CAPTURING
        status.text = buildString {
            appendLine(s.phase.name)
            appendLine(s.message)
            appendLine("PCM16 • 48,000 Hz • stereo")
            appendLine("Frames: " + s.reading.frames)
            appendLine("Non-zero samples: " + s.reading.nonZeroSamples)
            appendLine("Last block peak: " + s.reading.peak + " / 32768")
            if (s.phase == CaptureState.Phase.CAPTURING && s.reading.nonZeroSamples == 0L) {
                appendLine("No audible signal observed. Source may be silent or disallow capture.")
            }
            append("Read timestamp (local monotonic ns): " + s.lastReadNanos)
        }
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
        else launchConsent()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launchConsent()
        } else {
            pending = false
            CaptureState.reportConsentFailure("Playback-capture audio permission denied.")
            render()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchConsent() {
        try {
            val manager = requireNotNull(getSystemService(MediaProjectionManager::class.java))
            startActivityForResult(manager.createScreenCaptureIntent(), CONSENT)
        } catch (e: RuntimeException) {
            pending = false
            CaptureState.reportConsentFailure("Unable to request capture consent: " + e.javaClass.simpleName)
            render()
        }
    }

    @Deprecated("Used for platform consent in this standalone PoC")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CONSENT) return
        pending = false
        if (resultCode != RESULT_OK || data == null) {
            CaptureState.reportConsentFailure("Capture consent was not granted.")
        } else if (!CaptureState.snapshot.active) {
            try {
                startForegroundService(Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_RESULT_DATA, data))
            } catch (e: RuntimeException) {
                CaptureState.reportConsentFailure("Unable to start capture: " + e.javaClass.simpleName)
            }
        }
        render()
    }

    companion object {
        private const val PERMISSIONS = 10
        private const val CONSENT = 11
    }
}
