package com.smithandreah69.beamspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,               // raw signal (e.g. -55 dBm)
    val signalBars: Int,         // 1–5 for display
    val frequencyMhz: Int,
    val isSecured: Boolean,
    val capabilities: String,
    val isVerified: Boolean = false,
    val listingId: String = "",
    val pricePerMin: Double = 0.0,
    val hostName: String = "",
    val activeGuests: Int = 0
)

data class ConnectionStats(
    val ssid: String,
    val signalBars: Int,
    val rssiDbm: Int,
    val linkSpeedMbps: Int,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val distanceMeters: Double,  // estimated from RSSI
    val frequencyMhz: Int
)

class WifiScanHelper(private val context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // ─── Scan nearby networks ───────────────────────────────────────────────
    // Returns actual nearby WiFi networks with real RSSI, SSID, security type.
    // Requires ACCESS_FINE_LOCATION permission to be granted before calling.
    suspend fun scanNetworks(): List<WifiNetwork> =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                    val raw = try {
                        wifiManager.scanResults ?: emptyList()
                    } catch (e: SecurityException) {
                        android.util.Log.e("WifiScanHelper", "SecurityException reading scan results in receiver", e)
                        emptyList()
                    }
                    val networks = raw
                        .filter { it.SSID.isNotBlank() }
                        .distinctBy { it.SSID }
                        .sortedByDescending { it.level } // strongest first
                        .map { result -> result.toWifiNetwork() }
                    if (cont.isActive) cont.resume(networks)
                }
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        receiver,
                        IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    context.registerReceiver(
                        receiver,
                        IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("WifiScanHelper", "Failed to register scan results receiver", e)
            }

            val started = try {
                wifiManager.startScan()
            } catch (e: SecurityException) {
                android.util.Log.e("WifiScanHelper", "SecurityException during startScan()", e)
                false
            }

            if (!started) {
                // startScan() may return false on some devices but still triggers
                // the broadcast. Fall back to cached results after a short wait.
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                val cached = try {
                    wifiManager.scanResults?.filter { it.SSID.isNotBlank() }
                        ?.distinctBy { it.SSID }
                        ?.sortedByDescending { it.level }
                        ?.map { it.toWifiNetwork() } ?: emptyList()
                } catch (e: SecurityException) {
                    android.util.Log.e("WifiScanHelper", "SecurityException reading cached scan results", e)
                    emptyList()
                }
                if (cont.isActive) cont.resume(cached)
            }
        }

    // ─── Current connection statistics (real values) ──────────────────────
    // Call this on a background thread — measureSpeeds() blocks for ~2 seconds.
    suspend fun getConnectionStats(): ConnectionStats? {
        @Suppress("DEPRECATION")
        val info: WifiInfo = try {
            wifiManager.connectionInfo ?: return null
        } catch (e: Exception) {
            android.util.Log.e("WifiScanHelper", "Exception reading connectionInfo", e)
            return null
        }
        if (info.networkId == -1) return null // not connected

        val rssi = info.rssi
        val freq = info.frequency
        val bars = WifiManager.calculateSignalLevel(rssi, 5)
        val linkSpeed = info.linkSpeed              // Mbps (from 802.11 negotiation)
        val (dl, ul) = measureSpeeds()
        val distance = estimateDistanceMeters(rssi, freq)

        @Suppress("DEPRECATION")
        val ssid = info.ssid?.trim('"') ?: ""

        return ConnectionStats(
            ssid = ssid,
            signalBars = bars,
            rssiDbm = rssi,
            linkSpeedMbps = linkSpeed,
            downloadMbps = dl,
            uploadMbps = ul,
            distanceMeters = distance,
            frequencyMhz = freq
        )
    }

    // ─── Speed measurement using Android TrafficStats ─────────────────────
    // Measures actual bytes transferred over a 2-second window.
    // This is real throughput, not the link speed negotiated with the router.
    private suspend fun measureSpeeds(): Pair<Double, Double> {
        val startRx = TrafficStats.getTotalRxBytes()
        val startTx = TrafficStats.getTotalTxBytes()
        val t0 = System.currentTimeMillis()
        delay(2_000)
        val elapsedSec = (System.currentTimeMillis() - t0) / 1_000.0
        val rxBytes = TrafficStats.getTotalRxBytes() - startRx
        val txBytes = TrafficStats.getTotalTxBytes() - startTx
        val dl = if (elapsedSec > 0) (rxBytes * 8) / (elapsedSec * 1_000_000) else 0.0
        val ul = if (elapsedSec > 0) (txBytes * 8) / (elapsedSec * 1_000_000) else 0.0
        return Pair(dl.coerceAtLeast(0.0), ul.coerceAtLeast(0.0))
    }

    // ─── Distance estimation from RSSI (free-space path loss model) ──────
    // This is an approximation. Walls, interference, etc. affect real distance.
    // Typical accuracy: ±30% in open space, less accurate through walls.
    private fun estimateDistanceMeters(rssiDbm: Int, frequencyMhz: Int): Double {
        // FSPL formula: d = 10^((27.55 - 20*log10(f) + |RSSI|) / 20)
        val exponent = (27.55 - (20.0 * log10(frequencyMhz.toDouble())) + abs(rssiDbm)) / 20.0
        return 10.0.pow(exponent)
    }

    // ─── Map ScanResult → WifiNetwork ─────────────────────────────────────
    private fun ScanResult.toWifiNetwork() = WifiNetwork(
        ssid = this.SSID,
        bssid = this.BSSID,
        rssi = this.level,
        signalBars = WifiManager.calculateSignalLevel(this.level, 5),
        frequencyMhz = this.frequency,
        isSecured = this.capabilities.contains("WPA") ||
                    this.capabilities.contains("WEP") ||
                    this.capabilities.contains("PSK"),
        capabilities = this.capabilities
    )
}
