package com.screenlink.pro.capture

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.WindowManager
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.screenlink.pro.R
import com.screenlink.pro.control.RemoteControlAccessibilityService
import com.screenlink.pro.network.ScreenServer
import com.screenlink.pro.util.Pairing

/** Owns exactly one MediaProjection session and one VirtualDisplay per service lifetime. */
class CaptureService : Service() {
    companion object {
        const val START = "start"; const val STOP = "stop"; const val CODE = "code"; const val DATA = "data"; const val RESULT = "result"
        private const val TAG = "ScreenLinkCapture"; private const val CHANNEL = "screenlink"; private const val NOTIFICATION_ID = 7; private const val PORT = 47821
    }
    private data class EncoderSetup(val codec: MediaCodec, val surface: Surface, val width: Int, val height: Int)

    private var projection: MediaProjection? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var server: ScreenServer? = null
    private var encoderThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { stopAll(); stopSelfResult(startId) }
            START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (running || stopping) return
        try {
            val result = intent.getIntExtra(RESULT, Activity.RESULT_CANCELED)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(DATA)
            require(result == Activity.RESULT_OK && data != null) { "Screen permission was not granted" }

            // API 34 requires the approved token before promoting this service with the projection type.
            if (Build.VERSION.SDK_INT < 34) startForegroundSafely()
            val manager = getSystemService(MediaProjectionManager::class.java) ?: error("MediaProjection unavailable")
            val activeProjection = manager.getMediaProjection(result, data!!) ?: error("MediaProjection unavailable")
            projection = activeProjection
            activeProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { Log.i(TAG, "Projection stopped by system"); stopAll(); stopSelf() }
            }, Handler(Looper.getMainLooper()))
            if (Build.VERSION.SDK_INT >= 34) startForegroundSafely()

            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val screenWidth = metrics.widthPixels; val screenHeight = metrics.heightPixels
            require(screenWidth > 1 && screenHeight > 1) { "Invalid display size" }
            val scale = minOf(1f, 1280f / maxOf(screenWidth, screenHeight))
            val requestedWidth = (screenWidth * scale).toInt().and(1.inv())
            val requestedHeight = (screenHeight * scale).toInt().and(1.inv())
            val setup = createEncoderWithFallback(requestedWidth, requestedHeight)
            codec = setup.codec
            inputSurface = setup.surface
            display = activeProjection.createVirtualDisplay(
                "ScreenLink", setup.width, setup.height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, setup.surface, null, null
            ) ?: error("Virtual display unavailable")

            val newServer = ScreenServer(PORT, intent.getStringExtra(CODE) ?: Pairing.generate(), setup.width, setup.height)
            newServer.onControl = { payload -> RemoteControlAccessibilityService.dispatch(payload) }
            newServer.start(); server = newServer
            running = true
            encoderThread = Thread({ drainEncoder(setup.codec, newServer) }, "ScreenLinkEncoder").also { it.start() }
            startPlaybackAudio(activeProjection, newServer)
        } catch (error: Throwable) {
            Log.e(TAG, "Capture startup failed", error)
            stopAll(); stopSelf()
        }
    }

    /**
     * Returns true when [info] is a software (CPU-only) codec. On weak budget chipsets
     * (e.g. Unisoc T615/T616 found in entry-level Tecno/Infinix/itel devices) the platform's
     * codec list can surface a software AVC codec before — or instead of — the vendor's
     * hardware one. Software encoding saturates an already-weak CPU and is the most common
     * cause of capture-side lag that only shows up on low-end devices.
     */
    private fun isSoftwareCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return !info.isHardwareAccelerated
        val name = info.name.lowercase()
        return name.startsWith("omx.google.") || name.startsWith("c2.android.")
    }

    private fun startPlaybackAudio(activeProjection: MediaProjection, output: ScreenServer) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(maxOf(minimum, 6_400))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return }
            record.startRecording()
            audioRecord = record
            audioThread = Thread({
                val buffer = ByteArray(640)
                while (running && !Thread.currentThread().isInterrupted) {
                    val count = try { record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) } catch (_: Exception) { -1 }
                    if (count > 0) output.sendAudio(if (count == buffer.size) buffer.copyOf() else buffer.copyOf(count))
                }
            }, "ScreenLinkPlaybackAudio").also { it.start() }
        } catch (error: Exception) { Log.w(TAG, "Playback audio capture unavailable", error) }
    }

    private fun createEncoderWithFallback(requestedWidth: Int, requestedHeight: Int): EncoderSetup {
        val sizes = linkedSetOf(
            requestedWidth to requestedHeight,
            (requestedWidth * 0.85f).toInt().and(1.inv()) to (requestedHeight * 0.85f).toInt().and(1.inv()),
            (requestedWidth * 0.67f).toInt().and(1.inv()) to (requestedHeight * 0.67f).toInt().and(1.inv()),
            720 to 1280,
            480 to 854
        ).filter { it.first >= 2 && it.second >= 2 }
        val infos = try { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate codecs", e); emptyList()
        }
        val candidates = infos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                try {
                    val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                } catch (_: Exception) { false }
        }.sortedBy { if (isSoftwareCodec(it)) 1 else 0 }
        // Exhaust every hardware-accelerated encoder across all fallback sizes first;
        // only fall back to a software encoder (last in the sorted list) if no hardware
        // codec supports any of the candidate sizes at all.
        for (info in candidates) for ((width, height) in sizes) {
            var candidate: MediaCodec? = null
            var surface: Surface? = null
            try {
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val video = caps.videoCapabilities ?: continue
                if (!video.isSizeSupported(width, height)) continue
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (width * height > 700_000) 2_500_000 else 1_200_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 20)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                }
                candidate = MediaCodec.createByCodecName(info.name)
                candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = candidate.createInputSurface()
                candidate.start()
                Log.i(TAG, "Using AVC encoder ${info.name} (hw=${!isSoftwareCodec(info)}) at ${width}x$height")
                return EncoderSetup(candidate, surface, width, height)
            } catch (error: Exception) {
                Log.w(TAG, "Rejected encoder ${info.name} at ${width}x$height", error)
                try { candidate?.reset() } catch (_: Exception) {}
                try { candidate?.release() } catch (_: Exception) {}
                try { surface?.release() } catch (_: Exception) {}
            }
        }
        error("No compatible surface H.264 encoder found")
    }

    private fun startForegroundSafely() {
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.capture_title)).setContentText(getString(R.string.capture_text))
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun drainEncoder(encoder: MediaCodec, output: ScreenServer) {
        val info = MediaCodec.BufferInfo()
        while (running && !Thread.currentThread().isInterrupted) try {
            val index = encoder.dequeueOutputBuffer(info, 10_000)
            if (index >= 0) {
                encoder.getOutputBuffer(index)?.let { buffer ->
                    if (info.size > 0 && info.offset >= 0 && info.offset + info.size <= buffer.capacity()) {
                        val frame = ByteArray(info.size); buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(frame); output.send(frame, info.flags)
                    }
                }
                encoder.releaseOutputBuffer(index, false)
            }
        } catch (error: Exception) { if (running) Log.e(TAG, "Encoder loop stopped", error); break }
    }

    private fun stopAll() {
        if (stopping) return
        stopping = true; running = false
        val thread = encoderThread
        if (Thread.currentThread() !== thread) try { thread?.join(300) } catch (_: Exception) {}
        encoderThread = null
        audioThread?.interrupt(); audioThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { display?.release() } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        try { server?.stop() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        display = null; codec = null; inputSurface = null; server = null; projection = null
        if (Build.VERSION.SDK_INT >= 24) try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopping = false
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
