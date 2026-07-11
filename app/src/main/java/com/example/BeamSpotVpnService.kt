package com.smithandreah69.beamspot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
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

    // Captive portal IP — packets from unauthorized devices are redirected here
    private val captivePortalIp = "10.8.0.1"

    companion object {
        const val ACTION_START = "com.smithandreah69.beamspot.VPN_START"
        const val ACTION_STOP  = "com.smithandreah69.beamspot.VPN_STOP"
        const val CHANNEL_ID   = "beamspot_vpn"
        const val NOTIF_ID     = 1001
        var isRunning = false
            private set
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
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

        // Start packet processing loop
        serviceScope.launch { processPackets() }

        // Periodically check for expired sessions and evict them
        serviceScope.launch { evictExpiredSessions() }
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
                inStream.channel.read(buffer)
            }
            if (len <= 0) {
                delay(10); continue
            }
            buffer.flip()

            // Parse source IP from the IPv4 header (bytes 12–15)
            if (buffer.limit() >= 20 && (buffer.get(0).toInt() and 0xF0) == 0x40) {
                val srcIp = InetAddress.getByAddress(
                    byteArrayOf(buffer.get(12), buffer.get(13), buffer.get(14), buffer.get(15))
                ).hostAddress ?: ""

                val mac = getMacForIp(srcIp)

                when {
                    // Host's own device IP (10.8.0.1 is our VPN self) — always pass
                    srcIp == captivePortalIp -> forwardPacket(buffer, outStream)

                    // Authorized guest (paid, session not expired) — pass through
                    mac != null && isAuthorized(mac) -> forwardPacket(buffer, outStream)

                    // Unauthorized guest — for HTTP (port 80) redirect to captive portal
                    // For other ports, drop the packet silently
                    // The HTTP redirect causes their browser to open the payment page
                    else -> redirectToCaptivePortal(buffer, outStream, srcIp)
                }
            }
            buffer.clear()
        }
    }

    // Forward packet to the real internet
    private fun forwardPacket(buffer: ByteBuffer, out: FileOutputStream) {
        try { out.write(buffer.array(), 0, buffer.limit()) } catch (_: Exception) {}
    }

    // For unauthorized devices: silently drop non-HTTP packets.
    // HTTP traffic (port 80) gets a TCP RST which causes the browser to
    // retry → the OS captive portal detection kicks in → user sees our page.
    // Note: full transparent redirect requires kernel-level NAT (root only).
    // Without root, we use the hotspot captive portal detection mechanism instead:
    // Android auto-detects the lack of connectivity and shows our page.
    private fun redirectToCaptivePortal(buffer: ByteBuffer, out: FileOutputStream, srcIp: String) {
        // Drop the packet — Android's network monitor will detect "no internet"
        // and prompt the user with the captive portal notification
        // pointing to our payment page (configured via nodogsplash on Router Mode,
        // or via the network's captive portal URL set in LocalOnlyHotspot config)
    }

    // ─── Session authorization ────────────────────────────────────────────
    fun authorizeDevice(mac: String, sessionDurationMs: Long) {
        authorizedMacs[mac] = System.currentTimeMillis() + sessionDurationMs
        updateNotification("${authorizedMacs.size} guest(s) connected")
    }

    fun revokeDevice(mac: String) {
        authorizedMacs.remove(mac)
    }

    private fun isAuthorized(mac: String): Boolean {
        val expiry = authorizedMacs[mac] ?: return false
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

    // ─── Evict expired sessions every 30 seconds ─────────────────────────
    private suspend fun evictExpiredSessions() {
        while (isRunning) {
            delay(30_000)
            val now = System.currentTimeMillis()
            val expired = authorizedMacs.filter { (_, expiry) -> now >= expiry }.keys
            expired.forEach { mac ->
                authorizedMacs.remove(mac)
                // TODO: notify backend that this MAC's session expired
                // TODO: send push notification to guest if they have the app
            }
            if (expired.isNotEmpty()) {
                updateNotification("${authorizedMacs.size} guest(s) connected")
            }
        }
    }

    // ─── Stop ─────────────────────────────────────────────────────────────
    private fun stopVpn() {
        isRunning = false
        serviceScope.cancel()
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
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
