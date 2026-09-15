package com.screenlink.pro.webrtc

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.ListenerRegistration
import com.screenlink.pro.R
import com.screenlink.pro.cloud.CloudSync
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Hosts this device's screen as a WebRTC video track and answers incoming view requests routed
 * through Firestore (see WebRtcSignaling). Independent of CaptureService (local Wi-Fi mode) —
 * this is what "Go live online" on the home screen starts.
 *
 * Video only for now; system-audio-over-WebRTC is a separate future addition.
 */
class WebRtcHostService : Service() {
    companion object {
        const val START = "start"; const val STOP = "stop"; const val DATA = "data"; const val RESULT = "result"
        private const val TAG = "WebRtcHost"; private const val CHANNEL = "screenlink_online"; private const val NOTIFICATION_ID = 21
    }

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var peerConnection: PeerConnection? = null
    private var offerListener: ListenerRegistration? = null
    private var candidatesListener: ListenerRegistration? = null
    private var hostUid: String? = null
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Online sharing", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelfResult(startId); return START_NOT_STICKY }
        when (intent.action) {
            STOP -> { stopAll(); stopSelfResult(startId) }
            START -> startHosting(intent)
        }
        return START_NOT_STICKY
    }

    private fun startHosting(intent: Intent) {
        if (running) return
        val uid = CloudSync.currentUid()
        if (uid == null) { stopSelf(); return }
        hostUid = uid
        try {
            val result = intent.getIntExtra(RESULT, Activity.RESULT_CANCELED)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(DATA)
            require(result == Activity.RESULT_OK && data != null) { "Screen permission was not granted" }

            // Same ordering requirement as CaptureService: promote to foreground with the
            // mediaProjection type BEFORE requesting the projection token (Android 14+).
            startForegroundSafely()

            val manager = getSystemService(MediaProjectionManager::class.java) ?: error("MediaProjection unavailable")
            val activeProjection = manager.getMediaProjection(result, data!!) ?: error("MediaProjection unavailable")
            projection = activeProjection
            activeProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { Log.i(TAG, "Projection stopped by system"); stopAll(); stopSelf() }
            }, Handler(Looper.getMainLooper()))

            val eb = EglBase.create()
            eglBase = eb
            val pcFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eb.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eb.eglBaseContext))
                .createPeerConnectionFactory()
            factory = pcFactory

            val helper = SurfaceTextureHelper.create("ScreenLinkCapture", eb.eglBaseContext)
            surfaceTextureHelper = helper
            val source = pcFactory.createVideoSource(true)
            videoSource = source

            val screenCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() { Log.i(TAG, "Screen capturer stopped"); stopAll(); stopSelf() }
            })
            capturer = screenCapturer
            val metrics = resources.displayMetrics
            screenCapturer.initialize(helper, applicationContext, source.capturerObserver)
            screenCapturer.startCapture(metrics.widthPixels, metrics.heightPixels, 20)

            val track = pcFactory.createVideoTrack("screenlink_video_$uid", source)
            videoTrack = track

            running = true
            CloudSync.setLive(true)
            offerListener = WebRtcSignaling.listenForOffer(uid) { offer, viewerUid ->
                Handler(Looper.getMainLooper()).post { answerCall(uid, pcFactory, track, offer, viewerUid) }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Online hosting failed to start", error)
            stopAll(); stopSelf()
        }
    }

    private fun answerCall(uid: String, pcFactory: PeerConnectionFactory, track: VideoTrack, offer: SessionDescription, viewerUid: String) {
        try { peerConnection?.close() } catch (_: Exception) {}
        candidatesListener?.remove()

        val rtcConfig = PeerConnection.RTCConfiguration(WebRtcSignaling.iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = pcFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) { WebRtcSignaling.addHostCandidate(uid, candidate) }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) { Log.i(TAG, "ICE state: $state") }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }) ?: return
        peerConnection = pc
        pc.addTrack(track, listOf("screenlink_stream_$uid"))

        pc.setRemoteDescription(SdpObserverAdapter(), offer)
        pc.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                pc.setLocalDescription(SdpObserverAdapter(), sdp)
                WebRtcSignaling.sendAnswer(uid, sdp) {}
            }
        }, MediaConstraints())

        candidatesListener = WebRtcSignaling.listenForViewerCandidates(uid) { candidate -> pc.addIceCandidate(candidate) }
    }

    private fun startForegroundSafely() {
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Sharing online")
            .setContentText("Your screen is live on the ScreenLink Pro website")
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } catch (first: Exception) {
                Log.w(TAG, "Typed foreground promotion rejected; retrying legacy mode", first)
                startForeground(NOTIFICATION_ID, notification)
            }
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopAll() {
        if (!running && hostUid == null) return
        running = false
        hostUid?.let { CloudSync.setLive(false); WebRtcSignaling.endCall(it) }
        offerListener?.remove(); offerListener = null
        candidatesListener?.remove(); candidatesListener = null
        try { peerConnection?.close() } catch (_: Exception) {}
        peerConnection = null
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        capturer = null
        try { videoTrack?.dispose() } catch (_: Exception) {}
        videoTrack = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
        surfaceTextureHelper = null
        try { factory?.dispose() } catch (_: Exception) {}
        factory = null
        try { eglBase?.release() } catch (_: Exception) {}
        eglBase = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        hostUid = null
        if (Build.VERSION.SDK_INT >= 24) try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
