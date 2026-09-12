package com.screenlink.pro.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue

class PlaybackAudioPlayer {
    private var track: AudioTrack? = null
    private val packets = LinkedBlockingQueue<ByteArray>(40)
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        val min = AudioTrack.getMinBufferSize(16_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, 6_400))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = player
        player.play()
        running = true
        thread = Thread({
            while (running && !Thread.currentThread().isInterrupted) try { val packet = packets.take(); player.write(packet, 0, packet.size) } catch (_: Exception) { break }
        }, "ScreenLinkAudioPlayer").also { it.start() }
    }

    fun feed(packet: ByteArray) {
        if (!running || packet.isEmpty()) return
        if (packets.remainingCapacity() == 0) packets.poll()
        packets.offer(packet)
    }

    fun stop() {
        running = false; thread?.interrupt(); thread = null
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null; packets.clear()
    }
}
