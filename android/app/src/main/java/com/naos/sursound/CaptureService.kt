package com.naos.sursound

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock

/** Owns the consent token and the recording lifecycle; never records screen pixels or microphone. */
class CaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private var session: Session? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("SurSoundCapture", Process.THREAD_PRIORITY_AUDIO).apply { start() }
        worker = Handler(thread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val current = session
            if (current == null) stopSelf() else current.requestStop()
            return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY
        val consent = intent?.let { getConsent(it) }
        if (intent == null || intent.action != ACTION_START || consent == null ||
            intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) != Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }

        val owner = CaptureState.begin()
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val current = Session(owner)
            session = current
            worker.post { current.start(consent) }
        } catch (e: RuntimeException) {
            CaptureState.update(owner, CaptureState.Snapshot(CaptureState.Phase.ERROR,
                "Foreground service rejected: " + e.javaClass.simpleName))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val current = session
        session = null
        worker.post {
            current?.finish("Capture stopped.")
            thread.quitSafely()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun getConsent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_RESULT_DATA)

    private fun notification(): Notification {
        requireNotNull(getSystemService(NotificationManager::class.java)).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_delete, getString(R.string.stop_capture), stop).build())
            .build()
    }

    /** All projection/record operations and callbacks run on the same worker looper. */
    private inner class Session(private val owner: Long) {
        private var projection: MediaProjection? = null
        private var record: AudioRecord? = null
        private var finished = false
        private val meter = PcmMeter(CHANNELS)
        private val pcm = ShortArray(SAMPLE_RATE / 50 * CHANNELS) // 20 ms at 48 kHz stereo
        private var latest = PcmMeter.Reading()
        private var lastRead = 0L
        private var lastPublish = 0L
        private val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                finish("Capture permission revoked or projection ended.")
                main.post { stopSelf() }
            }
        }
        private val readNext = object : Runnable {
            override fun run() {
                if (finished) return
                try {
                    val input = record ?: error("AudioRecord unavailable")
                    val count = input.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                    if (count < 0) {
                        fail("AudioRecord read failed: " + count)
                        return
                    }
                    if (count > 0) {
                        latest = meter.consume(pcm, count)
                        lastRead = SystemClock.elapsedRealtimeNanos()
                        if (lastRead - lastPublish >= 100_000_000L) {
                            lastPublish = lastRead
                            publish(CaptureState.Phase.CAPTURING, "Reading permitted playback audio")
                        }
                        pcm.fill(0, 0, count)
                    }
                    worker.postDelayed(this, 10)
                } catch (e: RuntimeException) {
                    fail("Audio read interrupted: " + e.javaClass.simpleName)
                }
            }
        }

        fun start(consent: Intent) {
            if (finished) return
            try {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    fail("Audio permission missing")
                    return
                }
                val p = requireNotNull(getSystemService(MediaProjectionManager::class.java))
                    .getMediaProjection(Activity.RESULT_OK, consent) ?: error("No MediaProjection")
                projection = p
                p.registerCallback(callback, worker)
                val config = AudioPlaybackCaptureConfiguration.Builder(p)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .excludeUid(Process.myUid())
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "Unsupported PCM configuration" }
                val input = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minimum * 2, pcm.size * 4))
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                record = input
                check(input.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
                input.startRecording()
                check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Recording not started" }
                publish(CaptureState.Phase.CAPTURING, "Waiting for permitted playback samples")
                worker.post(readNext)
            } catch (e: RuntimeException) {
                fail("Capture setup failed: " + e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
        }

        fun requestStop() {
            worker.post {
                if (!finished) {
                    publish(CaptureState.Phase.STOPPING, "Stopping capture")
                    finish("Capture stopped.")
                    main.post { stopSelf() }
                }
            }
        }

        private fun fail(message: String) {
            finish(message, error = true)
            main.post { stopSelf() }
        }

        private fun publish(phase: CaptureState.Phase, message: String) {
            CaptureState.update(owner, CaptureState.Snapshot(phase, message, latest, lastRead))
        }

        fun finish(message: String, error: Boolean = false) {
            if (finished) return
            finished = true
            worker.removeCallbacks(readNext)
            record?.let { input ->
                runCatching { if (input.recordingState == AudioRecord.RECORDSTATE_RECORDING) input.stop() }
                runCatching { input.release() }
            }
            record = null
            projection?.let { p ->
                runCatching { p.unregisterCallback(callback) }
                runCatching { p.stop() }
            }
            projection = null
            pcm.fill(0)
            publish(if (error) CaptureState.Phase.ERROR else CaptureState.Phase.STOPPED, message)
        }
    }

    companion object {
        const val ACTION_START = "com.naos.sursound.START"
        const val ACTION_STOP = "com.naos.sursound.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
    }
}
