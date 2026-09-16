package com.screenlink.pro.webrtc

import android.content.Context
import org.webrtc.PeerConnectionFactory

/**
 * PeerConnectionFactory.initialize(...) must run exactly once per process before any
 * PeerConnectionFactory is built — skipping it throws immediately (which looked like "sharing
 * turns off the instant it starts"). Both the host service and the viewer screen call this first.
 */
object WebRtcInit {
    @Volatile private var initialized = false

    @Synchronized
    fun ensure(context: Context) {
        if (initialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        initialized = true
    }
}
