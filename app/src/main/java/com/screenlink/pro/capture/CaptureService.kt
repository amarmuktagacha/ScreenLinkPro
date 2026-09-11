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
import androidx.core.app.NotificationCompat
import com.screenlink.pro.R
import com.screenlink.pro.network.ScreenServer
import com.screenlink.pro.util.Pairing

/** Owns exactly one MediaProjection session and one VirtualDisplay per service lifetime. */
class CaptureService : Service() {
    companion object {
        const val START = "start"; const val STOP = "stop"; const val CODE = "code"; const val DATA = "data"; const val RESULT = "result"
        private const val TAG = "ScreenLinkCapture"; private const val CHANNEL = "screenlink"; private const val NOTIFICATION_ID = 7; private const val PORT = 47821
    }

    private var projection: MediaProjection? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var server: ScreenServer? = null
    private var encoderThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
            )
        }
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
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(DATA)
            }
            require(result == Activity.RESULT_OK && data != null) { "Screen permission was not granted" }

            // Android 11-13 permits foreground promotion before obtaining the token.
            // Android 14 requires the user-approved projection token before the typed FGS.
            val approved = if (Build.VERSION.SDK_INT >= 34) null else Unit
            if (approved != null) startForegroundSafely()
            val manager = getSystemService(MediaProjectionManager::class.java)
                ?: error("MediaProjection service unavailable")
            val activeProjection = manager.getMediaProjection(result, data!!)
                ?: error("MediaProjection unavailable")
            projection = activeProjection
            activeProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { Log.i(TAG, "Projection stopped by system"); stopAll(); stopSelf() }
            }, Handler(Looper.getMainLooper()))
            if (Build.VERSION.SDK_INT >= 34) startForegroundSafely()

            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            var width = metrics.widthPixels
            var height = metrics.heightPixels
            require(width > 1 && height > 1) { "Invalid display size" }
            val scale = 1280f / maxOf(width, height)
            if (scale < 1f) { width = (width * scale).toInt(); height = (height * scale).toInt() }
            width = width and 1.inv(); height = height and 1.inv()
            require(width >= 2 && height >= 2) { "Invalid encoder size" }

            val encoder = createEncoder(width, height)
            codec = encoder
            val surface = encoder.createInputSurface()
            encoder.start()
            display = activeProjection.createVirtualDisplay(
                "ScreenLink", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            ) ?: error("Virtual display unavailable")

            val newServer = ScreenServer(PORT, intent.getStringExtra(CODE) ?: Pairing.generate(), width, height)
            newServer.start(); server = newServer
            running = true
            encoderThread = Thread({ drainEncoder(encoder, newServer) }, "ScreenLinkEncoder").also { it.start() }
        } catch (error: Throwable) {
            Log.e(TAG, "Capture startup failed", error)
            stopAll()
            stopSelf()
        }
    }

    private fun createEncoder(width: Int, height: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        for (info in candidates) {
            try {
                val codec = MediaCodec.createByCodecName(info.name)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return codec
            } catch (error: Exception) { Log.w(TAG, "Skipping encoder ${info.name}", error) }
        }
        error("No compatible H.264 hardware encoder found")
    }

    private fun startForegroundSafely() {
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.capture_title))
            .setContentText(getString(R.string.capture_text))
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun drainEncoder(encoder: MediaCodec, output: ScreenServer) {
        val info = MediaCodec.BufferInfo()
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                val index = encoder.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    encoder.getOutputBuffer(index)?.let { buffer ->
                        if (info.size > 0) {
                            val frame = ByteArray(info.size)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(frame)
                            output.send(frame)
                        }
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
            } catch (error: Exception) { if (running) Log.e(TAG, "Encoder loop stopped", error); break }
        }
    }

    private fun stopAll() {
        if (stopping) return
        stopping = true; running = false
        val thread = encoderThread
        if (Thread.currentThread() !== thread) try { thread?.join(400) } catch (_: Exception) {}
        encoderThread = null
        try { display?.release() } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { server?.stop() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        display = null; codec = null; server = null; projection = null
        if (Build.VERSION.SDK_INT >= 24) try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopping = false
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
