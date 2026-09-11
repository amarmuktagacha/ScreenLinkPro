package com.screenlink.pro.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

data class PairingInfo(val host: String, val port: Int, val code: String, val ssid: String = "", val password: String = "")

object QrPairing {
    fun payload(host: String, port: Int, code: String): String = "SLP1|$host|$port|$code"

    fun payload(host: String, port: Int, code: String, ssid: String, password: String): String =
        listOf("SLP2", host, port.toString(), code, encode(ssid), encode(password)).joinToString("|")

    fun parse(raw: String): PairingInfo? {
        val parts = raw.trim().split('|')
        if (parts.size != 4 && parts.size != 6) return null
        if (parts[0] != "SLP1" && parts[0] != "SLP2") return null
        val port = parts[2].toIntOrNull() ?: return null
        val code = Pairing.normalize(parts[3])
        if (parts[1].isBlank() || port !in 1..65535 || !Pairing.valid(code)) return null
        val ssid = if (parts.size == 6) decode(parts[4]) else ""
        val password = if (parts.size == 6) decode(parts[5]) else ""
        return PairingInfo(parts[1], port, code, ssid, password)
    }

    private fun encode(value: String): String = android.util.Base64.encodeToString(value.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
    private fun decode(value: String): String = try { String(android.util.Base64.decode(value, android.util.Base64.URL_SAFE)) } catch (_: Exception) { "" }

    fun createBitmap(value: String, size: Int = 720): Bitmap {
        val matrix: BitMatrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
        return bitmap
    }
}
