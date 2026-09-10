package com.screenlink.pro.capture

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue

class H264Decoder(private val surface: Surface, private val width: Int, private val height: Int) {
    private var codec: MediaCodec? = null
    private val frames = LinkedBlockingQueue<ByteArray>(30)
    @Volatile private var running = false
    private var feed: Thread? = null
    private var drain: Thread? = null
    fun start() {
        require(surface.isValid && width > 0 && height > 0)
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try { c.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height), surface, null, 0); c.start() }
        catch (e: Exception) { try { c.release() } catch (_: Exception) {}; throw e }
        codec = c; running = true
        feed = Thread { while (running) try { val data = frames.take(); val i = c.dequeueInputBuffer(10_000); if (i >= 0) c.getInputBuffer(i)?.let { it.clear(); it.put(data); c.queueInputBuffer(i, 0, data.size, System.nanoTime()/1000, 0) } } catch (_: Exception) { if (!running) break } }.also { it.start() }
        drain = Thread { val info = MediaCodec.BufferInfo(); while (running) try { val i = c.dequeueOutputBuffer(info, 10_000); if (i >= 0) c.releaseOutputBuffer(i, true) } catch (_: Exception) { if (!running) break } }.also { it.start() }
    }
    fun feed(data: ByteArray) { if (running) { if (frames.remainingCapacity() == 0) frames.poll(); frames.offer(data) } }
    fun stop() { running = false; feed?.interrupt(); drain?.interrupt(); try { codec?.stop() } catch (_: Exception) {}; try { codec?.release() } catch (_: Exception) {}; codec = null; frames.clear() }
}
