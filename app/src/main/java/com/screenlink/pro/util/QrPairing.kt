package com.screenlink.pro.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

data class PairingInfo(val host: String, val port: Int, val code: String)

object QrPairing {
    fun payload(host: String, port: Int, code: String): String = "SLP1|$host|$port|$code"

    fun parse(raw: String): PairingInfo? {
        val parts = raw.trim().split('|')
        if (parts.size != 4 || parts[0] != "SLP1") return null
        val port = parts[2].toIntOrNull() ?: return null
        val code = Pairing.normalize(parts[3])
        if (parts[1].isBlank() || port !in 1..65535 || !Pairing.valid(code)) return null
        return PairingInfo(parts[1], port, code)
    }

    fun createBitmap(value: String, size: Int = 720): Bitmap {
        val matrix: BitMatrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
        return bitmap
    }
}
