package com.screenlink.pro.util

import java.util.Locale
import kotlin.random.Random

object Pairing {
    fun generate(): String = "%06d".format(Locale.US, Random.nextInt(0, 1_000_000))
    fun normalize(value: String): String = value.filter(Char::isDigit).take(6)
    fun valid(value: String): Boolean = value.length == 6 && value.all(Char::isDigit)
}

object NetworkInfo {
    fun addresses(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            .mapNotNull { it.hostAddress }.distinct()
    } catch (_: Exception) { emptyList() }
}
