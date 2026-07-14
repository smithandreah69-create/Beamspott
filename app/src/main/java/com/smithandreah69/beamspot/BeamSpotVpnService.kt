package com.smithandreah69.beamspot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * BeamSpot Smart Bridge VPN Service
 *
 * How it works:
 *  1. Host's phone is connected to home WiFi (internet source)
 *  2. This VPN creates a virtual tun0 network interface
 *  3. ALL traffic on the phone flows through tun0, including traffic
 *     from devices that connected to the BeamSpot guest hotspot
 *  4. For each IP packet, we check: is this device's IP authorized (paid)?
 *      - YES → forward the packet to the real internet
 *      - NO  → redirect HTTP traffic to the local BeamSpot captive portal page
 *
 * Per-device enforcement:
 *  - When a guest connects to the hotspot, they get an IP from the local
 *    DHCP range (192.168.43.x on Android hotspot)
 *  - We read /proc/net/arp to map that IP → MAC address
 *  - The MAC is the device's unique hardware identifier
 *  - Authorized MACs are stored in `authorizedMacs` set
 *  - Even if a guest shares the WiFi password, the new device has a
 *    different MAC → different IP → hits the captive portal immediately
 *
 * Traffic isolation:
 *  - Host's own traffic (their own IP packets) is passed through without
 *    restriction — they always have free internet on their device
 *  - Only hotspot client traffic is subject to session checks
 */
class BeamSpotVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Authorized devices: MAC address → session expiry timestamp (ms)
    private val authorizedMacs = mutableMapOf<String, Long>()

    // Fallback for IP-based authorization when MAC is unavailable
    private val authorizedIps = mutableMapOf<String, Long>()

    // Track active client IP addresses and their last activity timestamp
    private val activeClientIps = mutableMapOf<String, Long>()

    // Captive portal IP — packets from unauthorized devices are redirected here
    private val captivePortalIp = "10.8.0.1"

    // Active TCP and UDP sessions to handle userspace NAT
    private val tcpSessions = java.util.concurrent.ConcurrentHashMap<String, TcpSession>()
    private val udpSessions = java.util.concurrent.ConcurrentHashMap<String, UdpSession>()
    private val ipToMacMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    companion object {
        const val ACTION_START = "com.smithandreah69.beamspot.VPN_START"
        const val ACTION_STOP  = "com.smithandreah69.beamspot.VPN_STOP"
        const val CHANNEL_ID   = "beamspot_vpn"
        const val NOTIF_ID     = 1001
        var isRunning = false
            private set

        // Expose credentials and status to UI
        var actualHotspotSsid = ""
            private set
        var actualHotspotPassword = ""
            private set
        var activeLocalClientsCount = 0
            private set
        var isStaApSupported = false
            private set
        const val MAX_GUESTS = 30 // Safe conservative hardware connection threshold
        var activeListingId = "1"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                activeListingId = intent.getStringExtra("EXTRA_LISTING_ID") ?: "1"
                startVpn()
            }
            ACTION_STOP  -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("BeamSpot VPN active", "Guest sessions are being managed"))

        val builder = Builder()
            .setSession("BeamSpot Smart Bridge")
            .addAddress(captivePortalIp, 24)       // VPN's own IP: 10.8.0.1/24
            .addRoute("192.168.0.0", 16)           // Route hotspot subnet through VPN
            .addRoute("0.0.0.0", 0)                // Route all traffic through VPN
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .setBlocking(false)
            .setMtu(1500)
            .allowFamily(OsConstants.AF_INET)

        // Host's own app is allowed to bypass the VPN for its own API calls
        builder.addAllowedApplication(packageName)

        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            stopSelf(); return
        }

        isRunning = true

        // Start Local Only Hotspot
        startHotspot()

        // Start pending session backend polling loop for Smart Bridge phone mode
        startSessionPolling()

        // Start packet processing loop
        serviceScope.launch { processPackets() }

        // Periodically check for expired sessions and evict them
        serviceScope.launch { evictExpiredSessions() }

        // Periodically evict silent/inactive client IPs
        serviceScope.launch {
            while (isRunning) {
                delay(5000)
                val cutoff = System.currentTimeMillis() - 120_000 // 2 minutes inactivity
                synchronized(activeClientIps) {
                    val originalSize = activeClientIps.size
                    val toRemove = activeClientIps.filter { it.value < cutoff }.keys
                    toRemove.forEach { activeClientIps.remove(it) }
                    if (activeClientIps.size != originalSize) {
                        activeLocalClientsCount = activeClientIps.size
                        updateNotification("$activeLocalClientsCount guest(s) active on local network")
                    }
                }
            }
        }
    }

    private fun extractIpFromDeviceId(deviceId: String): String? {
        val parts = deviceId.split("|")
        return if (parts.size >= 2) parts[1] else null
    }

    private fun startSessionPolling() {
        serviceScope.launch {
            while (isRunning) {
                try {
                    val pendingList = RetrofitClient.apiService.getPendingSessions()
                    for (session in pendingList) {
                        val guestIp = session.guestIp ?: extractIpFromDeviceId(session.guestDeviceId)
                        if (guestIp != null) {
                            val mac = ipToMacMap[guestIp] ?: getMacForIp(guestIp)
                            if (mac != null) {
                                authorizeDevice(mac, session.durationMin * 60 * 1000L)
                            }
                            authorizeDeviceByIp(guestIp, session.durationMin * 60 * 1000L)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BeamSpotVpnService", "Error polling pending sessions", e)
                }
                delay(5000)
            }
        }
    }

    private fun startHotspot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.System.canWrite(applicationContext)) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(intent)
                    Handler(Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(applicationContext, "Please allow Write Settings permission to enable Hotspot", android.widget.Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BeamSpotVpnService", "Failed to launch write settings activity", e)
                }
                stopVpn()
                return
            }
        }

        val prefs = getSharedPreferences("beamspot_prefs", Context.MODE_PRIVATE)
        val hasSetup = prefs.getBoolean("has_done_hotspot_setup", false)
        if (!hasSetup) {
            prefs.edit().putBoolean("has_done_hotspot_setup", true).apply()
            val intent = Intent().apply {
                action = "android.settings.PORTABLE_HOTSPOT_SETTINGS"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(applicationContext, "Configure your Hotspot name and password, then return to start BeamSpot.", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(fallbackIntent)
                } catch (_: Exception) {}
            }
            stopVpn()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+) uses TetheringManager / startTethering reflection
            try {
                val tm = applicationContext.getSystemService("tethering")
                if (tm != null) {
                    val tmClass = tm.javaClass
                    val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
                    val executorClass = java.util.concurrent.Executor::class.java
                    
                    val startTetheringMethod = tmClass.getMethod(
                        "startTethering",
                        Int::class.javaPrimitiveType,
                        executorClass,
                        callbackClass
                    )
                    
                    val callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
                        callbackClass.classLoader,
                        arrayOf(callbackClass),
                        object : java.lang.reflect.InvocationHandler {
                            override fun invoke(proxy: Any?, method: java.lang.reflect.Method?, args: Array<out Any>?): Any? {
                                if (method?.name == "onTetheringStarted") {
                                    android.util.Log.i("BeamSpotVpnService", "Tethering started successfully!")
                                    actualHotspotSsid = "BeamSpot Hotspot"
                                    actualHotspotPassword = "Check phone hotspot settings"
                                } else if (method?.name == "onTetheringFailed") {
                                    val error = args?.get(0) as? Int ?: -1
                                    android.util.Log.e("BeamSpotVpnService", "Tethering failed with error code: $error")
                                }
                                return null
                            }
                        }
                    )
                    
                    val mainExecutor = applicationContext.mainExecutor
                    startTetheringMethod.invoke(tm, 0, mainExecutor, callbackProxy) // 0 is TETHERING_WIFI
                    isStaApSupported = true
                    android.util.Log.i("BeamSpotVpnService", "Tethering started via TetheringManager on Android 11+.")
                    return
                }
            } catch (e: Exception) {
                android.util.Log.e("BeamSpotVpnService", "Error starting tethering on Android 11+, trying LocalOnlyHotspot fallback", e)
            }
        }

        // Fallback or Legacy: Android 8.0 to 10 (API 26-29)
        // Try calling the hidden WifiManager.setWifiApEnabled (no callback needed)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifiManager != null) {
                    val setWifiApEnabledMethod = wifiManager.javaClass.getMethod(
                        "setWifiApEnabled",
                        android.net.wifi.WifiConfiguration::class.java,
                        Boolean::class.javaPrimitiveType
                    )
                    setWifiApEnabledMethod.invoke(wifiManager, null, true)
                    actualHotspotSsid = "BeamSpot Hotspot"
                    actualHotspotPassword = "Check phone hotspot settings"
                    android.util.Log.i("BeamSpotVpnService", "Legacy tethering enabled via WifiManager.")
                    return
                }
            } catch (e: Exception) {
                android.util.Log.e("BeamSpotVpnService", "Error starting legacy tethering, trying standard LocalOnlyHotspot fallback", e)
            }
        }

        // Standard LocalOnlyHotspot fallback if reflection methods fail or for older devices (API 26+)
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        isStaApSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wifiManager.isStaApConcurrencySupported
        } else {
            false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                            super.onStarted(reservation)
                            hotspotReservation = reservation
                            val config = reservation.wifiConfiguration
                            actualHotspotSsid = config?.SSID ?: ""
                            actualHotspotPassword = config?.preSharedKey ?: ""
                            android.util.Log.i("BeamSpotVpnService", "LocalOnlyHotspot started. SSID: $actualHotspotSsid, password: $actualHotspotPassword")
                        }

                        override fun onStopped() {
                            super.onStopped()
                            hotspotReservation = null
                            actualHotspotSsid = ""
                            actualHotspotPassword = ""
                            android.util.Log.i("BeamSpotVpnService", "LocalOnlyHotspot stopped")
                        }

                        override fun onFailed(reason: Int) {
                            super.onFailed(reason)
                            hotspotReservation = null
                            actualHotspotSsid = ""
                            actualHotspotPassword = ""
                            android.util.Log.e("BeamSpotVpnService", "LocalOnlyHotspot failed to start with reason code: $reason")
                        }
                    }, Handler(Looper.getMainLooper()))
                } else {
                    android.util.Log.w("BeamSpotVpnService", "Cannot start LocalOnlyHotspot: ACCESS_FINE_LOCATION permission not granted.")
                }
            } catch (e: SecurityException) {
                android.util.Log.e("BeamSpotVpnService", "SecurityException starting LocalOnlyHotspot", e)
            } catch (e: Exception) {
                android.util.Log.e("BeamSpotVpnService", "General Exception starting LocalOnlyHotspot", e)
            }
        }
    }

    private fun stopHotspot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val tm = applicationContext.getSystemService("tethering")
                if (tm != null) {
                    val stopTetheringMethod = tm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
                    stopTetheringMethod.invoke(tm, 0) // 0 is TETHERING_WIFI
                    android.util.Log.i("BeamSpotVpnService", "Tethering stopped successfully on Android 11+")
                }
            } catch (e: Exception) {
                android.util.Log.e("BeamSpotVpnService", "Error stopping tethering on Android 11+", e)
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifiManager != null) {
                    val setWifiApEnabledMethod = wifiManager.javaClass.getMethod(
                        "setWifiApEnabled",
                        android.net.wifi.WifiConfiguration::class.java,
                        Boolean::class.javaPrimitiveType
                    )
                    setWifiApEnabledMethod.invoke(wifiManager, null, false)
                    android.util.Log.i("BeamSpotVpnService", "Legacy tethering disabled via WifiManager.")
                }
            } catch (e: Exception) {
                android.util.Log.e("BeamSpotVpnService", "Error stopping legacy tethering", e)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                hotspotReservation?.close()
            }
        } catch (e: Exception) {
            android.util.Log.e("BeamSpotVpnService", "Exception closing LocalOnlyHotspot reservation", e)
        } finally {
            hotspotReservation = null
            actualHotspotSsid = ""
            actualHotspotPassword = ""
        }
    }

    private fun trackClientIpActivity(ip: String) {
        if (ip == captivePortalIp || ip == "127.0.0.1" || ip == "0.0.0.0" || ip.isBlank()) return
        if (ip.startsWith("192.168.") || ip.startsWith("10.8.0.")) {
            synchronized(activeClientIps) {
                activeClientIps[ip] = System.currentTimeMillis()
                activeLocalClientsCount = activeClientIps.size
            }
        }
    }

    // ─── Packet processing loop ───────────────────────────────────────────
    // Reads every IP packet from the tun interface, decides to forward or drop.
    private suspend fun processPackets() {
        val vpnFd = vpnInterface?.fileDescriptor ?: return
        val inStream  = FileInputStream(vpnFd)
        val outStream = FileOutputStream(vpnFd)
        val buffer    = ByteBuffer.allocate(32767)

        while (isRunning) {
            buffer.clear()
            val len = withContext(Dispatchers.IO) {
                try { inStream.channel.read(buffer) } catch (_: Exception) { -1 }
            }
            if (len <= 0) {
                delay(10); continue
            }
            buffer.flip()

            // Parse source IP and destination IP from the IPv4 header (bytes 12–19)
            if (buffer.limit() >= 20 && (buffer.get(0).toInt() and 0xF0) == 0x40) {
                val protocol = buffer.get(9).toInt() and 0xFF
                val totalLen = ((buffer.get(2).toInt() and 0xFF) shl 8) or (buffer.get(3).toInt() and 0xFF)
                val ihl = (buffer.get(0).toInt() and 0x0F) * 4

                val srcIpBytes = ByteArray(4) { buffer.get(12 + it) }
                val dstIpBytes = ByteArray(4) { buffer.get(16 + it) }
                val srcIp = InetAddress.getByAddress(srcIpBytes).hostAddress ?: ""
                val dstIp = InetAddress.getByAddress(dstIpBytes).hostAddress ?: ""

                // Record local IP → MAC mapping in real-time
                val mac = getMacForIp(srcIp)
                if (mac != null) {
                    ipToMacMap[srcIp] = mac
                }

                // Track active client activity
                trackClientIpActivity(srcIp)

                // Enforce guest threshold/cap
                val isAlreadyAuthorized = (mac != null && isAuthorized(mac)) || isIpAuthorizedFallback(srcIp)
                val isNewGuest = !isAlreadyAuthorized && !activeClientIps.containsKey(srcIp)
                val isCapped = activeLocalClientsCount >= MAX_GUESTS

                when {
                    // Host's own device IP (10.8.0.1 is our VPN self) — always pass
                    srcIp == captivePortalIp -> forwardPacketDirect(buffer, outStream)

                    // Authorized guest (paid, session not expired) — pass through (NATed forwarding)
                    isAlreadyAuthorized -> forwardPacketRelayed(buffer, outStream, srcIp, dstIp, protocol, ihl, totalLen)

                    // If we are already capped, drop packets from any new guest
                    isNewGuest && isCapped -> {
                        // Silent drop to enforce limit
                    }

                    // Unauthorized guest — redirect to captive portal
                    else -> redirectToCaptivePortal(buffer, outStream, srcIp, dstIp, protocol, ihl, totalLen)
                }
            }
            buffer.clear()
        }
    }

    private fun forwardPacketDirect(buffer: ByteBuffer, out: FileOutputStream) {
        try { out.write(buffer.array(), 0, buffer.limit()) } catch (_: Exception) {}
    }

    private fun forwardPacketRelayed(
        buffer: ByteBuffer,
        out: FileOutputStream,
        srcIp: String,
        dstIp: String,
        protocol: Int,
        ihl: Int,
        totalLen: Int
    ) {
        if (protocol == 17) { // UDP
            val srcPort = ((buffer.get(ihl).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 1).toInt() and 0xFF)
            val dstPort = ((buffer.get(ihl + 2).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 3).toInt() and 0xFF)
            val udpLen = ((buffer.get(ihl + 4).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 5).toInt() and 0xFF)
            val payloadStart = ihl + 8
            val payloadLen = udpLen - 8
            if (payloadLen > 0 && buffer.limit() >= payloadStart + payloadLen) {
                val payload = ByteArray(payloadLen)
                buffer.position(payloadStart)
                buffer.get(payload, 0, payloadLen)

                val key = "$srcIp:$srcPort -> $dstIp:$dstPort"
                var session = udpSessions[key]
                if (session == null) {
                    session = UdpSession(srcIp, srcPort, dstIp, dstPort, this, out) {
                        udpSessions.remove(key)
                    }
                    udpSessions[key] = session
                }
                session.send(payload)
            }
        } else if (protocol == 6) { // TCP
            val srcPort = ((buffer.get(ihl).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 1).toInt() and 0xFF)
            val dstPort = ((buffer.get(ihl + 2).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 3).toInt() and 0xFF)
            
            val seq = (((buffer.get(ihl + 4).toLong() and 0xFF) shl 24) or
                       ((buffer.get(ihl + 5).toLong() and 0xFF) shl 16) or
                       ((buffer.get(ihl + 6).toLong() and 0xFF) shl 8) or
                       (buffer.get(ihl + 7).toLong() and 0xFF))
                       
            val ack = (((buffer.get(ihl + 8).toLong() and 0xFF) shl 24) or
                       ((buffer.get(ihl + 9).toLong() and 0xFF) shl 16) or
                       ((buffer.get(ihl + 10).toLong() and 0xFF) shl 8) or
                       (buffer.get(ihl + 11).toLong() and 0xFF))
                       
            val dataOffset = ((buffer.get(ihl + 12).toInt() and 0xF0) ushr 4) * 4
            val flags = buffer.get(ihl + 13).toInt() and 0xFF
            val payloadStart = ihl + dataOffset
            val payloadLen = totalLen - ihl - dataOffset

            val payload = if (payloadLen > 0 && buffer.limit() >= payloadStart + payloadLen) {
                val p = ByteArray(payloadLen)
                buffer.position(payloadStart)
                buffer.get(p, 0, payloadLen)
                p
            } else {
                ByteArray(0)
            }

            val key = "$srcIp:$srcPort -> $dstIp:$dstPort"
            var session = tcpSessions[key]
            if (session == null) {
                session = TcpSession(srcIp, srcPort, dstIp, dstPort, this, out) {
                    tcpSessions.remove(key)
                }
                tcpSessions[key] = session
            }
            session.handleClientPacket(seq, ack, flags, payload)
        }
    }

    private fun redirectToCaptivePortal(
        buffer: ByteBuffer,
        out: FileOutputStream,
        srcIp: String,
        dstIp: String,
        protocol: Int,
        ihl: Int,
        totalLen: Int
    ) {
        if (protocol == 6) { // TCP
            val srcPort = ((buffer.get(ihl).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 1).toInt() and 0xFF)
            val dstPort = ((buffer.get(ihl + 2).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 3).toInt() and 0xFF)

            // Whitelist Secure HTTPS (Port 443) and Local CAPTIVE PORTAL traffic
            if (dstPort == 443) {
                forwardPacketRelayed(buffer, out, srcIp, dstIp, protocol, ihl, totalLen)
                return
            }

            // HTTP Port 80: Intercept and perform an instant 302 redirect to our payment/captive portal
            if (dstPort == 80) {
                val seq = (((buffer.get(ihl + 4).toLong() and 0xFF) shl 24) or
                           ((buffer.get(ihl + 5).toLong() and 0xFF) shl 16) or
                           ((buffer.get(ihl + 6).toLong() and 0xFF) shl 8) or
                           (buffer.get(ihl + 7).toLong() and 0xFF))
                           
                val ack = (((buffer.get(ihl + 8).toLong() and 0xFF) shl 24) or
                           ((buffer.get(ihl + 9).toLong() and 0xFF) shl 16) or
                           ((buffer.get(ihl + 10).toLong() and 0xFF) shl 8) or
                           (buffer.get(ihl + 11).toLong() and 0xFF))
                           
                val dataOffset = ((buffer.get(ihl + 12).toInt() and 0xF0) ushr 4) * 4
                val flags = buffer.get(ihl + 13).toInt() and 0xFF
                val payloadStart = ihl + dataOffset
                val payloadLen = totalLen - ihl - dataOffset

                val payload = if (payloadLen > 0 && buffer.limit() >= payloadStart + payloadLen) {
                    val p = ByteArray(payloadLen)
                    buffer.position(payloadStart)
                    buffer.get(p, 0, payloadLen)
                    p
                } else {
                    ByteArray(0)
                }

                val key = "redirect:$srcIp:$srcPort"
                var redirector = tcpSessions[key]
                if (redirector == null) {
                    redirector = TcpSession(srcIp, srcPort, dstIp, dstPort, this, out) {
                        tcpSessions.remove(key)
                    }
                    redirector.isCaptivePortalRedirector = true
                    tcpSessions[key] = redirector
                }
                redirector.handleClientPacket(seq, ack, flags, payload)
                return
            }
        } else if (protocol == 17) { // UDP (Allow UDP DNS on Port 53 so guests can resolve backend API/payment domain)
            val dstPort = ((buffer.get(ihl + 2).toInt() and 0xFF) shl 8) or (buffer.get(ihl + 3).toInt() and 0xFF)
            if (dstPort == 53) {
                forwardPacketRelayed(buffer, out, srcIp, dstIp, protocol, ihl, totalLen)
                return
            }
        }
    }

    // ─── Session authorization ────────────────────────────────────────────
    fun authorizeDevice(mac: String, sessionDurationMs: Long) {
        authorizedMacs[mac] = System.currentTimeMillis() + sessionDurationMs
        updateNotification("$activeLocalClientsCount guest(s) active on local network")
    }

    fun authorizeDeviceByIp(ip: String, sessionDurationMs: Long) {
        authorizedIps[ip] = System.currentTimeMillis() + sessionDurationMs
        updateNotification("$activeLocalClientsCount guest(s) active on local network")
    }

    fun revokeDevice(mac: String) {
        authorizedMacs.remove(mac)
    }

    private fun isAuthorized(mac: String): Boolean {
        val expiry = authorizedMacs[mac] ?: return false
        return System.currentTimeMillis() < expiry
    }

    private fun isIpAuthorizedFallback(ip: String): Boolean {
        val expiry = authorizedIps[ip] ?: return false
        return System.currentTimeMillis() < expiry
    }

    // ─── ARP table: maps IP address → MAC address ─────────────────────────
    // /proc/net/arp is readable without root and shows all devices
    // that are currently connected to any local network interface including hotspot
    private fun getMacForIp(ip: String): String? {
        return try {
            File("/proc/net/arp").readLines()
                .drop(1) // skip header line
                .firstOrNull { line ->
                    val parts = line.trim().split("\\s+".toRegex())
                    parts.size >= 4 && parts[0] == ip
                }
                ?.trim()?.split("\\s+".toRegex())
                ?.getOrNull(3) // MAC is column index 3
                ?.takeIf { it != "00:00:00:00:00:00" }
        } catch (_: Exception) { null }
    }

    // ─── Evict expired sessions ─────────────────────────
    private suspend fun evictExpiredSessions() {
        while (isRunning) {
            delay(30_000)
            val now = System.currentTimeMillis()
            val expiredMacs = authorizedMacs.filter { (_, expiry) -> now >= expiry }.keys
            expiredMacs.forEach { mac -> authorizedMacs.remove(mac) }

            val expiredIps = authorizedIps.filter { (_, expiry) -> now >= expiry }.keys
            expiredIps.forEach { ip -> authorizedIps.remove(ip) }

            // Clean up inactive TCP sessions
            val tcpKeys = tcpSessions.keys()
            while (tcpKeys.hasMoreElements()) {
                val key = tcpKeys.nextElement()
                val sess = tcpSessions[key]
                if (sess != null && sess.isExpired(60000)) {
                    sess.close()
                    tcpSessions.remove(key)
                }
            }

            // Clean up inactive UDP sessions
            val udpKeys = udpSessions.keys()
            while (udpKeys.hasMoreElements()) {
                val key = udpKeys.nextElement()
                val sess = udpSessions[key]
                if (sess != null && sess.isExpired(30000)) {
                    sess.close()
                    udpSessions.remove(key)
                }
            }

            if (expiredMacs.isNotEmpty() || expiredIps.isNotEmpty()) {
                updateNotification("$activeLocalClientsCount guest(s) active on local network")
            }
        }
    }

    // ─── Stop ─────────────────────────────────────────────────────────────
    private fun stopVpn() {
        isRunning = false
        stopHotspot()
        serviceScope.cancel()
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        stopHotspot()
        serviceScope.cancel()
        vpnInterface?.close()
        super.onDestroy()
    }

    // ─── Notification helpers ─────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "BeamSpot VPN", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Smart Bridge VPN session management" }
            (getSystemService(NotificationManager::class.java))
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotification(text: String) {
        (getSystemService(NotificationManager::class.java))
            .notify(NOTIF_ID, buildNotification("BeamSpot VPN active", text))
    }
}

// ─── Helper Classes for Userspace NAT ──────────────────────────────────

class TcpSession(
    val clientIp: String,
    val clientPort: Int,
    val serverIp: String,
    val serverPort: Int,
    val vpnService: VpnService,
    val outStream: FileOutputStream,
    val onClosed: () -> Unit
) {
    private var socket: java.net.Socket? = null
    private var isRunning = true
    private var lastActivity = System.currentTimeMillis()
    
    var isCaptivePortalRedirector = false
    
    // Sequence tracking
    private var clientSeq: Long = 0
    private var serverSeq: Long = 1000
    private var clientAck: Long = 0
    
    private val packetQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

    init {
        Thread {
            try {
                if (isCaptivePortalRedirector) {
                    // Complete handshake locally for captive portal
                    sendTcpControlPacket(0x12) // SYN-ACK
                } else {
                    val s = java.net.Socket()
                    vpnService.protect(s)
                    s.connect(java.net.InetSocketAddress(serverIp, serverPort), 10000)
                    socket = s
                    
                    // Respond with SYN-ACK
                    sendTcpControlPacket(0x12) // SYN-ACK
                    
                    val readerThread = Thread {
                        val recvBuf = ByteArray(16384)
                        val inputStream = s.getInputStream()
                        while (isRunning) {
                            try {
                                val count = inputStream.read(recvBuf)
                                if (count < 0) break
                                if (count > 0) {
                                    lastActivity = System.currentTimeMillis()
                                    sendTcpDataPacket(recvBuf.copyOf(count))
                                }
                            } catch (_: Exception) {
                                break
                            }
                        }
                        close()
                    }
                    readerThread.start()
                }

                val os = socket?.getOutputStream()
                while (isRunning) {
                    val payload = packetQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (payload != null) {
                        lastActivity = System.currentTimeMillis()
                        if (isCaptivePortalRedirector) {
                            // HTTP 302 Found instant response
                            var base = BuildConfig.API_BASE_URL
                            if (!base.endsWith("/")) {
                                base += "/"
                            }
                            val httpRedirect = "HTTP/1.1 302 Found\r\n" +
                                    "Location: ${base}connect.html?listingId=${BeamSpotVpnService.activeListingId}\r\n" +
                                    "Connection: close\r\n" +
                                    "Content-Length: 0\r\n\r\n"
                            sendTcpDataPacket(httpRedirect.toByteArray(Charsets.US_ASCII))
                            sendTcpControlPacket(0x11) // FIN-ACK
                            close()
                        } else {
                            os?.write(payload)
                            os?.flush()
                            sendTcpControlPacket(0x10) // ACK
                        }
                    }
                }
            } catch (e: Exception) {
                sendTcpControlPacket(0x04) // RST
                close()
            }
        }.start()
    }

    fun handleClientPacket(seq: Long, ack: Long, flags: Int, payload: ByteArray) {
        lastActivity = System.currentTimeMillis()
        if ((flags and 0x02) != 0) { // SYN
            clientSeq = seq
            clientAck = seq + 1
        } else {
            clientSeq = seq
            clientAck = seq + payload.size
            if (payload.isNotEmpty()) {
                packetQueue.add(payload)
            }
            if ((flags and 0x01) != 0) { // FIN
                clientAck += 1
                sendTcpControlPacket(0x11) // FIN-ACK
                close()
            }
            if ((flags and 0x04) != 0) { // RST
                close()
            }
        }
    }

    fun isExpired(timeoutMs: Long): Boolean {
        return System.currentTimeMillis() - lastActivity > timeoutMs
    }

    fun close() {
        isRunning = false
        try { socket?.close() } catch (_: Exception) {}
        onClosed()
    }

    private fun sendTcpControlPacket(flags: Int) {
        sendTcpPacket(
            outStream = outStream,
            srcIp = serverIp,
            srcPort = serverPort,
            dstIp = clientIp,
            dstPort = clientPort,
            seq = serverSeq,
            ack = clientAck,
            flags = flags,
            payload = ByteArray(0)
        )
        if ((flags and 0x02) != 0) { // SYN
            serverSeq++
        }
        if ((flags and 0x01) != 0) { // FIN
            serverSeq++
        }
    }

    private fun sendTcpDataPacket(payload: ByteArray) {
        sendTcpPacket(
            outStream = outStream,
            srcIp = serverIp,
            srcPort = serverPort,
            dstIp = clientIp,
            dstPort = clientPort,
            seq = serverSeq,
            ack = clientAck,
            flags = 0x18, // PSH-ACK
            payload = payload
        )
        serverSeq += payload.size
    }
}

class UdpSession(
    val clientIp: String,
    val clientPort: Int,
    val serverIp: String,
    val serverPort: Int,
    val vpnService: VpnService,
    val outStream: FileOutputStream,
    val onClosed: () -> Unit
) {
    private var datagramSocket: java.net.DatagramSocket? = null
    private var isRunning = true
    var lastActivity = System.currentTimeMillis()

    init {
        try {
            val ds = java.net.DatagramSocket()
            vpnService.protect(ds)
            datagramSocket = ds

            Thread {
                val recvBuf = ByteArray(32767)
                while (isRunning) {
                    try {
                        val packet = java.net.DatagramPacket(recvBuf, recvBuf.size)
                        ds.receive(packet)
                        lastActivity = System.currentTimeMillis()
                        sendBackToClient(recvBuf.copyOf(packet.length))
                    } catch (e: Exception) {
                        break
                    }
                }
                close()
            }.start()
        } catch (e: Exception) {
            close()
        }
    }

    fun send(payload: ByteArray) {
        lastActivity = System.currentTimeMillis()
        Thread {
            try {
                val packet = java.net.DatagramPacket(payload, payload.size, java.net.InetAddress.getByName(serverIp), serverPort)
                datagramSocket?.send(packet)
            } catch (_: Exception) {}
        }.start()
    }

    fun isExpired(timeoutMs: Long): Boolean {
        return System.currentTimeMillis() - lastActivity > timeoutMs
    }

    fun close() {
        isRunning = false
        try { datagramSocket?.close() } catch (_: Exception) {}
        onClosed()
    }

    private fun sendBackToClient(payload: ByteArray) {
        val payloadLen = payload.size
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val totalLen = ipHeaderLen + udpHeaderLen + payloadLen

        val packet = ByteArray(totalLen)

        // IPv4 Header
        packet[0] = 0x45
        packet[1] = 0
        packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0
        packet[5] = 0
        packet[6] = 0x40
        packet[7] = 0
        packet[8] = 64
        packet[9] = 17 // UDP
        packet[10] = 0
        packet[11] = 0

        // Source IP (remote server)
        val srcParts = serverIp.split(".")
        for (i in 0..3) {
            packet[12 + i] = srcParts[i].toInt().toByte()
        }

        // Destination IP (client device)
        val dstParts = clientIp.split(".")
        for (i in 0..3) {
            packet[16 + i] = dstParts[i].toInt().toByte()
        }

        // IP Checksum
        val ipChecksum = calculateChecksum(packet, 0, ipHeaderLen)
        packet[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header
        packet[20] = ((serverPort ushr 8) and 0xFF).toByte()
        packet[21] = (serverPort and 0xFF).toByte()
        packet[22] = ((clientPort ushr 8) and 0xFF).toByte()
        packet[23] = (clientPort and 0xFF).toByte()
        
        val udpLen = udpHeaderLen + payloadLen
        packet[24] = ((udpLen ushr 8) and 0xFF).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0
        packet[27] = 0

        // Payload
        System.arraycopy(payload, 0, packet, ipHeaderLen + udpHeaderLen, payloadLen)

        try {
            outStream.write(packet)
            outStream.flush()
        } catch (_: Exception) {}
    }
}

// ─── Mathematical Checksum Helpers ─────────────────────────────────────

fun sendTcpPacket(
    outStream: FileOutputStream,
    srcIp: String,
    srcPort: Int,
    dstIp: String,
    dstPort: Int,
    seq: Long,
    ack: Long,
    flags: Int,
    payload: ByteArray
) {
    val payloadLen = payload.size
    val ipHeaderLen = 20
    val tcpHeaderLen = 20
    val totalLen = ipHeaderLen + tcpHeaderLen + payloadLen

    val packet = ByteArray(totalLen)

    // IPv4 Header
    packet[0] = 0x45
    packet[1] = 0
    packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
    packet[3] = (totalLen and 0xFF).toByte()
    packet[4] = 0
    packet[5] = 0
    packet[6] = 0x40
    packet[7] = 0
    packet[8] = 64
    packet[9] = 6 // TCP
    packet[10] = 0
    packet[11] = 0

    val srcParts = srcIp.split(".")
    for (i in 0..3) {
        packet[12 + i] = srcParts[i].toInt().toByte()
    }

    val dstParts = dstIp.split(".")
    for (i in 0..3) {
        packet[16 + i] = dstParts[i].toInt().toByte()
    }

    val ipChecksum = calculateChecksum(packet, 0, ipHeaderLen)
    packet[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
    packet[11] = (ipChecksum and 0xFF).toByte()

    // TCP Header
    packet[20] = ((srcPort ushr 8) and 0xFF).toByte()
    packet[21] = (srcPort and 0xFF).toByte()
    packet[22] = ((dstPort ushr 8) and 0xFF).toByte()
    packet[23] = (dstPort and 0xFF).toByte()

    packet[24] = ((seq ushr 24) and 0xFF).toByte()
    packet[25] = ((seq ushr 16) and 0xFF).toByte()
    packet[26] = ((seq ushr 8) and 0xFF).toByte()
    packet[27] = (seq and 0xFF).toByte()

    packet[28] = ((ack ushr 24) and 0xFF).toByte()
    packet[29] = ((ack ushr 16) and 0xFF).toByte()
    packet[30] = ((ack ushr 8) and 0xFF).toByte()
    packet[31] = (ack and 0xFF).toByte()

    packet[32] = 0x50
    packet[33] = flags.toByte()
    packet[34] = 0x20
    packet[35] = 0
    packet[36] = 0
    packet[37] = 0
    packet[38] = 0
    packet[39] = 0

    if (payloadLen > 0) {
        System.arraycopy(payload, 0, packet, ipHeaderLen + tcpHeaderLen, payloadLen)
    }

    val tcpChecksum = calculateTcpUdpChecksum(packet, ipHeaderLen, tcpHeaderLen + payloadLen, srcParts, dstParts, 6)
    packet[36] = ((tcpChecksum ushr 8) and 0xFF).toByte()
    packet[37] = (tcpChecksum and 0xFF).toByte()

    try {
        outStream.write(packet)
        outStream.flush()
    } catch (_: Exception) {}
}

fun calculateChecksum(buf: ByteArray, offset: Int, length: Int): Int {
    var sum = 0
    var i = offset
    val end = offset + length - 1
    while (i < end) {
        val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
        sum += word
        i += 2
    }
    if (i == end) {
        sum += (buf[i].toInt() and 0xFF) shl 8
    }
    while ((sum ushr 16) != 0) {
        sum = (sum and 0xFFFF) + (sum ushr 16)
    }
    return (sum.inv()) and 0xFFFF
}

fun calculateTcpUdpChecksum(
    buf: ByteArray,
    offset: Int,
    length: Int,
    srcIpParts: List<String>,
    dstIpParts: List<String>,
    protocol: Int
): Int {
    var sum = 0
    for (i in 0..3 step 2) {
        val srcWord = ((srcIpParts[i].toInt() and 0xFF) shl 8) or (srcIpParts[i + 1].toInt() and 0xFF)
        sum += srcWord
        val dstWord = ((dstIpParts[i].toInt() and 0xFF) shl 8) or (dstIpParts[i + 1].toInt() and 0xFF)
        sum += dstWord
    }
    sum += protocol
    sum += length

    var i = offset
    val end = offset + length - 1
    while (i < end) {
        val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
        sum += word
        i += 2
    }
    if (i == end) {
        sum += (buf[i].toInt() and 0xFF) shl 8
    }
    while ((sum ushr 16) != 0) {
        sum = (sum and 0xFFFF) + (sum ushr 16)
    }
    return (sum.inv()) and 0xFFFF
}
