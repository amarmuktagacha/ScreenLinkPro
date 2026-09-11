package com.screenlink.pro.capture

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue

data class EncodedFrame(val bytes: ByteArray, val flags: Int)

class H264Decoder(private val surface: Surface, private val width: Int, private val height: Int) {
    private var codec: MediaCodec? = null
    private val frames = LinkedBlockingQueue<EncodedFrame>(60)
    @Volatile private var running = false
    private var feedThread: Thread? = null
    private var drainThread: Thread? = null

    fun start() {
        require(surface.isValid && width > 1 && height > 1)
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            decoder.configure(format, surface, null, 0)
            decoder.start()
        } catch (error: Exception) {
            try { decoder.release() } catch (_: Exception) {}
            throw error
        }
        codec = decoder; running = true
        feedThread = Thread({
            while (running && !Thread.currentThread().isInterrupted) try {
                val frame = frames.take()
                val index = decoder.dequeueInputBuffer(10_000)
                if (index >= 0) decoder.getInputBuffer(index)?.let { input ->
                    input.clear()
                    if (frame.bytes.size <= input.remaining()) {
                        input.put(frame.bytes)
                        decoder.queueInputBuffer(index, 0, frame.bytes.size, System.nanoTime() / 1000, frame.flags)
                    } else decoder.queueInputBuffer(index, 0, 0, 0, 0)
                }
            } catch (_: InterruptedException) { break }
              catch (_: Exception) { if (!running) break }
        }, "ScreenLinkDecoderInput").also { it.start() }
        drainThread = Thread({
            val info = MediaCodec.BufferInfo()
            while (running && !Thread.currentThread().isInterrupted) try {
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) decoder.releaseOutputBuffer(index, true)
            } catch (_: InterruptedException) { break }
              catch (_: Exception) { if (!running) break }
        }, "ScreenLinkDecoderOutput").also { it.start() }
    }

    fun feed(data: ByteArray, flags: Int = 0) {
        if (!running || data.isEmpty()) return
        if (frames.remainingCapacity() == 0) frames.poll()
        frames.offer(EncodedFrame(data, flags))
    }

    fun stop() {
        running = false
        feedThread?.interrupt(); drainThread?.interrupt()
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null; frames.clear()
    }
}
