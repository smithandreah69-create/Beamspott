package com.smithandreah69.beamspot

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class WifiConnectHelper(private val context: Context) {

    fun connect(
        ssid: String,
        password: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val isFinished = AtomicBoolean(false)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifier = try {
                WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .apply {
                        if (password.isNotEmpty()) {
                            setWpa2Passphrase(password)
                        }
                    }
                    .build()
            } catch (e: Exception) {
                onFailure("Failed to configure connection specifier: ${e.localizedMessage}")
                return
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val handler = Handler(Looper.getMainLooper())
            var callback: ConnectivityManager.NetworkCallback? = null

            val timeoutRunnable = Runnable {
                if (isFinished.compareAndSet(false, true)) {
                    callback?.let { connectivityManager.unregisterNetworkCallback(it) }
                    onFailure("Connection timed out. Please check the password and make sure you are in range.")
                }
            }

            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    if (isFinished.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeoutRunnable)
                        connectivityManager.bindProcessToNetwork(network)
                        onSuccess()
                    }
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    if (isFinished.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeoutRunnable)
                        onFailure("Network unavailable. Wrong password or network is out of range.")
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                }
            }

            connectivityManager.requestNetwork(request, callback)
            // 15 second timeout for connection
            handler.postDelayed(timeoutRunnable, 15000)

        } else {
            // Legacy fallback for API < 29
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    if (password.isNotEmpty()) {
                        preSharedKey = "\"$password\""
                    } else {
                        allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                    }
                }
                
                @Suppress("DEPRECATION")
                val netId = wifiManager.addNetwork(wifiConfig)
                if (netId == -1) {
                    onFailure("Failed to add WiFi network configuration.")
                    return
                }
                
                @Suppress("DEPRECATION")
                wifiManager.disconnect()
                @Suppress("DEPRECATION")
                val enabled = wifiManager.enableNetwork(netId, true)
                @Suppress("DEPRECATION")
                val reconnected = wifiManager.reconnect()
                
                if (enabled && reconnected) {
                    onSuccess()
                } else {
                    onFailure("Failed to connect to network.")
                }
            } catch (e: Exception) {
                onFailure("Legacy connection error: ${e.localizedMessage}")
            }
        }
    }
}
