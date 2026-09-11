package com.screenlink.pro.network

import android.media.MediaCodec
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

data class StreamFrame(val bytes: ByteArray, val flags: Int)

class ScreenServer(private val port: Int, private val code: String, private val width: Int, private val height: Int) {
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    private val queue = LinkedBlockingQueue<StreamFrame>(45)
    @Volatile private var latestConfig: StreamFrame? = null
    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var writerThread: Thread? = null

    fun start() {
        running = true
        acceptThread = Thread {
            try {
                server = ServerSocket(port)
                while (running) accept(server!!.accept())
            } catch (e: Exception) { if (running) onError?.invoke(e.message ?: "Server stopped") }
        }.also { it.start() }
    }

    private fun accept(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            val input = DataInputStream(socket.getInputStream())
            val length = input.readInt()
            if (length !in 1..32) { socket.close(); return }
            val received = ByteArray(length).also { input.readFully(it) }.toString(Charsets.UTF_8)
            val output = DataOutputStream(socket.getOutputStream())
            if (received != code) { output.writeByte(0); output.flush(); socket.close(); return }
            output.writeByte(1); output.writeInt(width); output.writeInt(height); output.flush()
            socket.soTimeout = 0
            client?.close(); client = socket; queue.clear(); latestConfig?.let { queue.offer(it) }; onConnected?.invoke()
            writerThread?.interrupt()
            writerThread = Thread {
                try {
                    val out = DataOutputStream(socket.getOutputStream())
                    while (running && client === socket) {
                        val frame = queue.take()
                        out.writeInt(frame.flags); out.writeInt(frame.bytes.size); out.write(frame.bytes); out.flush()
                    }
                } catch (_: Exception) {
                } finally { if (client === socket) { client = null; onDisconnected?.invoke() }; try { socket.close() } catch (_: Exception) {} }
            }.also { it.start() }
        } catch (_: Exception) { try { socket.close() } catch (_: Exception) {} }
    }

    fun send(frame: ByteArray, flags: Int = 0) {
        if (!running || frame.isEmpty()) return
        val item = StreamFrame(frame, flags)
        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) latestConfig = item
        if (queue.remainingCapacity() == 0) queue.poll()
        queue.offer(item)
    }

    fun stop() { running = false; try { server?.close() } catch (_: Exception) {}; try { client?.close() } catch (_: Exception) {}; acceptThread?.interrupt(); writerThread?.interrupt(); queue.clear(); latestConfig = null; client = null }
}
