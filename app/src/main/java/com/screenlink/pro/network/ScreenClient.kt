package com.screenlink.pro.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScreenClient {
    var onConnected: ((Int, Int) -> Unit)? = null
    var onFrame: ((ByteArray, Int) -> Unit)? = null
    var onAudio: ((ByteArray) -> Unit)? = null
    var onVideoSizeChanged: ((Int, Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    @Volatile private var running = false
    private var socket: Socket? = null
    private var output: DataOutputStream? = null

    fun connect(host: String, port: Int, code: String) {
        if (running) return
        running = true
        Thread {
            var s: Socket? = null
            try {
                s = Socket().also { it.connect(InetSocketAddress(host, port), 8_000); socket = it }
                val out = DataOutputStream(s.getOutputStream()); output = out; val bytes = code.toByteArray()
                out.writeInt(bytes.size); out.write(bytes); out.flush()
                val input = DataInputStream(s.getInputStream())
                if (input.readByte().toInt() != 1) { onError?.invoke("Pairing code does not match"); return@Thread }
                val w = input.readInt(); val h = input.readInt(); onConnected?.invoke(w, h)
                while (running) { val kind = input.readInt(); val flags = input.readInt(); val size = input.readInt(); if (kind !in 0..3 || size !in 1..5_000_000) break; val packet = ByteArray(size).also { input.readFully(it) }; when (kind) { 1 -> onAudio?.invoke(packet); 3 -> if (size == 8) { val b = java.nio.ByteBuffer.wrap(packet); onVideoSizeChanged?.invoke(b.int, b.int) }; else -> onFrame?.invoke(packet, flags) } }
            } catch (e: Exception) { if (running) onError?.invoke(e.message ?: "Could not connect")
            } finally { val notify = running; running = false; try { s?.close() } catch (_: Exception) {}; if (notify) onDisconnected?.invoke() }
        }.start()
    }
    fun close() { running = false; try { socket?.close() } catch (_: Exception) {} }

    @Synchronized fun sendTouch(action: Int, x: Float, y: Float, endX: Float, endY: Float, durationMs: Long) {
        if (!running) return
        val payload = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN).putInt(action).putFloat(x).putFloat(y).putFloat(endX).putFloat(endY).putLong(durationMs).array()
        try { output?.writeInt(2); output?.writeInt(payload.size); output?.write(payload); output?.flush() } catch (_: Exception) { }
    }
}
