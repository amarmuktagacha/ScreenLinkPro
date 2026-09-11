package com.screenlink.pro.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class ScreenClient {
    var onConnected: ((Int, Int) -> Unit)? = null
    var onFrame: ((ByteArray, Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    @Volatile private var running = false
    private var socket: Socket? = null

    fun connect(host: String, port: Int, code: String) {
        if (running) return
        running = true
        Thread {
            var s: Socket? = null
            try {
                s = Socket().also { it.connect(InetSocketAddress(host, port), 8_000); socket = it }
                val out = DataOutputStream(s.getOutputStream()); val bytes = code.toByteArray()
                out.writeInt(bytes.size); out.write(bytes); out.flush()
                val input = DataInputStream(s.getInputStream())
                if (input.readByte().toInt() != 1) { onError?.invoke("Pairing code does not match"); return@Thread }
                val w = input.readInt(); val h = input.readInt(); onConnected?.invoke(w, h)
                while (running) { val flags = input.readInt(); val size = input.readInt(); if (size !in 1..5_000_000) break; onFrame?.invoke(ByteArray(size).also { input.readFully(it) }, flags) }
            } catch (e: Exception) { if (running) onError?.invoke(e.message ?: "Could not connect")
            } finally { val notify = running; running = false; try { s?.close() } catch (_: Exception) {}; if (notify) onDisconnected?.invoke() }
        }.start()
    }
    fun close() { running = false; try { socket?.close() } catch (_: Exception) {} }
}
