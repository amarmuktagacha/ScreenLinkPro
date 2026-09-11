package com.screenlink.pro.util

import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build

class WifiConnector(private val context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun connect(ssid: String, password: String, onResult: (Boolean, String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onResult(false, "Automatic Wi‑Fi joining needs Android 10 or newer")
            return
        }
        disconnect()
        try {
            val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
                if (password.isNotBlank()) setWpa2Passphrase(password)
            }.build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connectivity.bindProcessToNetwork(network)
                    onResult(true, "Connected to $ssid")
                }
                override fun onUnavailable() { onResult(false, "Could not connect to Wi‑Fi $ssid") }
                override fun onLost(network: Network) { connectivity.bindProcessToNetwork(null) }
            }
            callback = networkCallback
            connectivity.requestNetwork(request, networkCallback)
        } catch (error: Exception) {
            onResult(false, error.message ?: "Wi‑Fi connection failed")
        }
    }

    fun disconnect() {
        callback?.let { try { connectivity.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
        try { connectivity.bindProcessToNetwork(null) } catch (_: Exception) {}
    }
}
