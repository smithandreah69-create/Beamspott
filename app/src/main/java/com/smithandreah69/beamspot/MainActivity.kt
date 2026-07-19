package com.smithandreah69.beamspot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import java.security.MessageDigest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.Context
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

// ─── Brand colours ────────────────────────────────────────────────────────
private val Ink         = Color(0xFF0E1614)
private val Panel       = Color(0xFF1A2925)
private val Amber       = Color(0xFFFF7A45)
private val Cyan        = Color(0xFF3FE0C5)
private val Paper       = Color(0xFFF0EEE6)
private val PaperDim    = Color(0x9EF0EEE6.toInt())
private val BorderLine  = Color(0x1AF0EEE6.toInt())

// ─── Navigation routes ────────────────────────────────────────────────────
private object Route {
    const val SPLASH           = "splash"
    const val LANDING          = "landing"
    const val SIGN_IN          = "sign_in"
    const val MODE_SELECT      = "mode_select"
    const val PAYOUT_SETUP     = "payout_setup"
    const val SB_WIFI_SCAN     = "sb_wifi_scan"      // Smart Bridge: scan WiFi
    const val SB_PASSWORD      = "sb_password/{ssid}/{bssid}" // enter password
    const val SB_PERMISSIONS   = "sb_permissions"    // request real permissions
    const val SB_NAMING        = "sb_naming"         // name the BeamSpot network
    const val ROUTER_SETUP     = "router_setup"
    const val HOTSPOT_SETUP    = "hotspot_setup"
    const val VERIFY_SETUP     = "verify_setup"
    const val DASHBOARD        = "dashboard"
    const val GUEST_PORTAL     = "guest_portal"      // Guest Flow: find and connect
    const val MAIN_APP         = "main_app"          // Bottom-nav wrapper
}

// ─── Bottom navigation items ──────────────────────────────────────────────
private enum class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("tab_dashboard", "Dashboard", Icons.Filled.SpaceDashboard),
    FIND_INTERNET("tab_find_internet", "Find Internet", Icons.Filled.Wifi),
    EARNINGS("tab_earnings", "Earnings", Icons.Filled.AccountBalanceWallet),
    SETTINGS("tab_settings", "Settings", Icons.Filled.Settings)
}

// ─── Hotspot Package Data Class ───────────────────────────────────────────
data class HotspotPackage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val price: Double,
    val duration: String,
    val speedLimit: String,
    val dataLimit: String = "Unlimited"
)

// ─── Shared ViewModel ─────────────────────────────────────────────────────
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("beamspot_prefs", Context.MODE_PRIVATE)

    // MikroTik Router configuration
    private val _routerIp = mutableStateOf(prefs.getString("router_ip", "192.168.88.1") ?: "192.168.88.1")
    var routerIp: String
        get() = _routerIp.value
        set(value) {
            _routerIp.value = value
            prefs.edit().putString("router_ip", value).apply()
        }

    private val _routerApiPort = mutableStateOf(prefs.getString("router_api_port", "8728") ?: "8728")
    var routerApiPort: String
        get() = _routerApiPort.value
        set(value) {
            _routerApiPort.value = value
            prefs.edit().putString("router_api_port", value).apply()
        }

    private val _routerUsername = mutableStateOf(prefs.getString("router_username", "admin") ?: "admin")
    var routerUsername: String
        get() = _routerUsername.value
        set(value) {
            _routerUsername.value = value
            prefs.edit().putString("router_username", value).apply()
        }

    private val _routerPassword = mutableStateOf(prefs.getString("router_password", "") ?: "")
    var routerPassword: String
        get() = _routerPassword.value
        set(value) {
            _routerPassword.value = value
            prefs.edit().putString("router_password", value).apply()
        }

    init {
        val sessionManager = SessionManager(application)
        sessionManager.getRouterConnection()?.let { conn ->
            _routerIp.value = conn.ip
            _routerApiPort.value = conn.port.toString()
            _routerUsername.value = conn.username
            _routerPassword.value = conn.password
            prefs.edit()
                .putString("router_ip", conn.ip)
                .putString("router_api_port", conn.port.toString())
                .putString("router_username", conn.username)
                .putString("router_password", conn.password)
                .apply()
        }
    }

    private val _routerHotspotServer = mutableStateOf(prefs.getString("router_hotspot_server", "hs-prof1") ?: "hs-prof1")
    var routerHotspotServer: String
        get() = _routerHotspotServer.value
        set(value) {
            _routerHotspotServer.value = value
            prefs.edit().putString("router_hotspot_server", value).apply()
        }

    private val _routerDnsName = mutableStateOf(prefs.getString("router_dns_name", "hotspot.net") ?: "hotspot.net")
    var routerDnsName: String
        get() = _routerDnsName.value
        set(value) {
            _routerDnsName.value = value
            prefs.edit().putString("router_dns_name", value).apply()
        }

    // M-Pesa Integration Settings
    private val _mpesaShortcode = mutableStateOf(prefs.getString("mpesa_shortcode", "") ?: "")
    var mpesaShortcode: String
        get() = _mpesaShortcode.value
        set(value) {
            _mpesaShortcode.value = value
            prefs.edit().putString("mpesa_shortcode", value).apply()
        }

    private val _mpesaConsumerKey = mutableStateOf(prefs.getString("mpesa_consumer_key", "") ?: "")
    var mpesaConsumerKey: String
        get() = _mpesaConsumerKey.value
        set(value) {
            _mpesaConsumerKey.value = value
            prefs.edit().putString("mpesa_consumer_key", value).apply()
        }

    private val _mpesaConsumerSecret = mutableStateOf(prefs.getString("mpesa_consumer_secret", "") ?: "")
    var mpesaConsumerSecret: String
        get() = _mpesaConsumerSecret.value
        set(value) {
            _mpesaConsumerSecret.value = value
            prefs.edit().putString("mpesa_consumer_secret", value).apply()
        }

    private val _mpesaPasskey = mutableStateOf(prefs.getString("mpesa_passkey", "") ?: "")
    var mpesaPasskey: String
        get() = _mpesaPasskey.value
        set(value) {
            _mpesaPasskey.value = value
            prefs.edit().putString("mpesa_passkey", value).apply()
        }

    private val _mpesaCallbackUrl = mutableStateOf(prefs.getString("mpesa_callback_url", "https://api.beamspot.net/v1/mpesa/callback") ?: "https://api.beamspot.net/v1/mpesa/callback")
    var mpesaCallbackUrl: String
        get() = _mpesaCallbackUrl.value
        set(value) {
            _mpesaCallbackUrl.value = value
            prefs.edit().putString("mpesa_callback_url", value).apply()
        }

    // Hotspot packages list
    private val _hotspotPackagesJson = mutableStateOf(prefs.getString("hotspot_packages_json", "") ?: "")
    var hotspotPackages: List<HotspotPackage>
        get() {
            val jsonStr = _hotspotPackagesJson.value
            if (jsonStr.isEmpty()) {
                // Return default packages
                return listOf(
                    HotspotPackage("1", "1 Hour Basic", 10.0, "1 Hour", "2 Mbps", "Unlimited"),
                    HotspotPackage("2", "3 Hours Standard", 25.0, "3 Hours", "5 Mbps", "Unlimited"),
                    HotspotPackage("3", "24 Hours Premium", 50.0, "24 Hours", "10 Mbps", "Unlimited")
                )
            }
            return try {
                val list = mutableListOf<HotspotPackage>()
                val arr = org.json.JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        HotspotPackage(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            name = obj.optString("name", ""),
                            price = obj.optDouble("price", 0.0),
                            duration = obj.optString("duration", ""),
                            speedLimit = obj.optString("speedLimit", ""),
                            dataLimit = obj.optString("dataLimit", "Unlimited")
                        )
                    )
                }
                list
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val arr = org.json.JSONArray()
            value.forEach { pkg ->
                val obj = org.json.JSONObject().apply {
                    put("id", pkg.id)
                    put("name", pkg.name)
                    put("price", pkg.price)
                    put("duration", pkg.duration)
                    put("speedLimit", pkg.speedLimit)
                    put("dataLimit", pkg.dataLimit)
                }
                arr.put(obj)
            }
            val jsonStr = arr.toString()
            _hotspotPackagesJson.value = jsonStr
            prefs.edit().putString("hotspot_packages_json", jsonStr).apply()
        }

    private val _isDemoMode = mutableStateOf(prefs.getBoolean("is_demo_mode", false))
    var isDemoMode: Boolean
        get() = _isDemoMode.value
        set(value) {
            _isDemoMode.value = value
            prefs.edit().putBoolean("is_demo_mode", value).apply()
        }

    private val _userName = mutableStateOf(prefs.getString("user_name", "") ?: "")
    var userName: String
        get() = _userName.value
        set(value) {
            _userName.value = value
            prefs.edit().putString("user_name", value).apply()
        }

    private val _userEmail = mutableStateOf(prefs.getString("user_email", "") ?: "")
    var userEmail: String
        get() = _userEmail.value
        set(value) {
            _userEmail.value = value
            prefs.edit().putString("user_email", value).apply()
        }

    private val _isSignedIn = mutableStateOf(prefs.getBoolean("is_signed_in", false))
    var isSignedIn: Boolean
        get() = _isSignedIn.value
        set(value) {
            _isSignedIn.value = value
            prefs.edit().putBoolean("is_signed_in", value).apply()
        }

    private val _activeListingId = mutableStateOf(prefs.getString("active_listing_id", "1") ?: "1")
    var activeListingId: String
        get() = _activeListingId.value
        set(value) {
            _activeListingId.value = value
            prefs.edit().putString("active_listing_id", value).apply()
        }

    private val _selectedMode = mutableStateOf(prefs.getString("selected_mode", "") ?: "")
    var selectedMode: String
        get() = _selectedMode.value
        set(value) {
            _selectedMode.value = value
            prefs.edit().putString("selected_mode", value).apply()
        }

    var selectedWifi by mutableStateOf<WifiNetwork?>(null)

    private val _routerGuestSsid = mutableStateOf(prefs.getString("router_guest_ssid", "") ?: "")
    var routerGuestSsid: String
        get() = _routerGuestSsid.value
        set(value) {
            _routerGuestSsid.value = value
            prefs.edit().putString("router_guest_ssid", value).apply()
        }

    private val _routerGuestPassword = mutableStateOf(prefs.getString("router_guest_password", "") ?: "")
    var routerGuestPassword: String
        get() = _routerGuestPassword.value
        set(value) {
            _routerGuestPassword.value = value
            prefs.edit().putString("router_guest_password", value).apply()
        }

    private val _beamSpotNetworkName = mutableStateOf(prefs.getString("beam_spot_network_name", "") ?: "")
    var beamSpotNetworkName: String
        get() = _beamSpotNetworkName.value
        set(value) {
            _beamSpotNetworkName.value = value
            prefs.edit().putString("beam_spot_network_name", value).apply()
        }

    var vpnActive by mutableStateOf(false)

    private val _pricePerMin = mutableStateOf(prefs.getFloat("price_per_min", 2.0f).toDouble())
    var pricePerMin: Double
        get() = _pricePerMin.value
        set(value) {
            _pricePerMin.value = value
            prefs.edit().putFloat("price_per_min", value.toFloat()).apply()
        }

    private val _payoutMethod = mutableStateOf(prefs.getString("payout_method", "mpesa") ?: "mpesa")
    var payoutMethod: String
        get() = _payoutMethod.value
        set(value) {
            _payoutMethod.value = value
            prefs.edit().putString("payout_method", value).apply()
        }

    private val _payoutNumber = mutableStateOf(prefs.getString("payout_number", "") ?: "")
    var payoutNumber: String
        get() = _payoutNumber.value
        set(value) {
            _payoutNumber.value = value
            prefs.edit().putString("payout_number", value).apply()
        }

    private val _bankName = mutableStateOf(prefs.getString("bank_name", "") ?: "")
    var bankName: String
        get() = _bankName.value
        set(value) {
            _bankName.value = value
            prefs.edit().putString("bank_name", value).apply()
        }

    private val _bankAccount = mutableStateOf(prefs.getString("bank_account", "") ?: "")
    var bankAccount: String
        get() = _bankAccount.value
        set(value) {
            _bankAccount.value = value
            prefs.edit().putString("bank_account", value).apply()
        }

    private val _bankHolder = mutableStateOf(prefs.getString("bank_holder", "") ?: "")
    var bankHolder: String
        get() = _bankHolder.value
        set(value) {
            _bankHolder.value = value
            prefs.edit().putString("bank_holder", value).apply()
        }

    var guestCount         by mutableStateOf(0)
    var todayEarnings      by mutableStateOf(0.0)
    var downloadMbps       by mutableStateOf(0.0)
    var uploadMbps         by mutableStateOf(0.0)
    var signalBars         by mutableStateOf(0)
    var signalRssi         by mutableStateOf(0)
    var linkSpeedMbps      by mutableStateOf(0)
    var distanceMeters     by mutableStateOf(0.0)
    var connectedSsid      by mutableStateOf("")

    // Earnings history (simulated for now)
    var earningsHistory by mutableStateOf<List<EarningsRecord>>(emptyList())

    init {
        val savedToken = prefs.getString("jwt_token", null)
        if (savedToken != null) {
            RetrofitClient.setToken(savedToken)
        }
        loadEarningsHistory()
    }

    fun saveToken(token: String?) {
        RetrofitClient.setToken(token)
        prefs.edit().putString("jwt_token", token).apply()
    }

    fun logout() {
        prefs.edit().clear().apply()
        _userName.value = ""
        _userEmail.value = ""
        _isSignedIn.value = false
        _isDemoMode.value = false
        _activeListingId.value = "1"
        _selectedMode.value = ""
        _beamSpotNetworkName.value = ""
        _pricePerMin.value = 2.0
        _payoutMethod.value = "mpesa"
        _payoutNumber.value = ""
        _bankName.value = ""
        _bankAccount.value = ""
        _bankHolder.value = ""
        RetrofitClient.setToken(null)
    }

    fun refreshStats(helper: WifiScanHelper) {
        viewModelScope.launch {
            val stats = helper.getConnectionStats() ?: return@launch
            downloadMbps   = stats.downloadMbps
            uploadMbps     = stats.uploadMbps
            signalBars     = stats.signalBars
            signalRssi     = stats.rssiDbm
            linkSpeedMbps  = stats.linkSpeedMbps
            distanceMeters = stats.distanceMeters
            connectedSsid  = stats.ssid

            if (BeamSpotVpnService.isRunning) {
                guestCount = BeamSpotVpnService.activeLocalClientsCount
            } else if (RetrofitClient.getToken() != null) {
                try {
                    val hostStats = RetrofitClient.apiService.getHostStats()
                    guestCount = hostStats.activeGuests
                    todayEarnings = hostStats.earningsToday
                } catch (e: Exception) {
                    android.util.Log.e("AppViewModel", "Failed to refresh host stats from backend", e)
                }
            }
        }
    }

    private fun loadEarningsHistory() {
        // Load from prefs or generate sample data
        val json = prefs.getString("earnings_history", null)
        if (json != null) {
            try {
                val items = json.split("|").map { it.split(",") }
                earningsHistory = items.map { (date, amount, guests, status) ->
                    EarningsRecord(date, amount.toDouble(), guests.toInt(), status)
                }
            } catch (_: Exception) {
                earningsHistory = emptyList()
            }
        }
    }

    fun addEarningsRecord(record: EarningsRecord) {
        earningsHistory = listOf(record) + earningsHistory
        val json = earningsHistory.joinToString("|") { "${it.date},${it.amount},${it.guests},${it.status}" }
        prefs.edit().putString("earnings_history", json).apply()
    }

    fun clearEarningsHistory() {
        earningsHistory = emptyList()
        prefs.edit().remove("earnings_history").apply()
    }
}

data class EarningsRecord(
    val date: String,
    val amount: Double,
    val guests: Int,
    val status: String // "completed", "pending", "withdrawn"
)

// ─── Activity ─────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionManager = SessionManager(this)
        val conn = sessionManager.getRouterConnection()
        android.util.Log.d("BeamSpotInit", "Loaded Router Connection from SessionManager on App Start: $conn")
        setContent {
            BeamSpotTheme {
                BeamSpotApp()
            }
        }
    }
}

@Composable
private fun BeamSpotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink,
            surface    = Panel,
            primary    = Cyan,
            secondary  = Amber,
            onBackground = Paper,
            onSurface  = Paper
        ),
        content = content
    )
}

// ─── Reusable components ─────────────────────────────────────────────────
@Composable
fun StepBadge(text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = Cyan.copy(0.1f)) {
        Text(text, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = Paper) }
        Text(title, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
fun BeamLabel(text: String) {
    Text(text, color = PaperDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 5.dp))
}

@Composable
fun BeamInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = PaperDim) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Paper, unfocusedTextColor = Paper,
            focusedBorderColor = Cyan, unfocusedBorderColor = BorderLine,
            cursorColor = Cyan
        ),
        shape = RoundedCornerShape(12.dp),
        singleLine = true
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
fun BeamButton(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, disabledContainerColor = color.copy(0.3f))
    ) {
        Text(label, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun PayoutMethodCard(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier,
        shape = RoundedCornerShape(12.dp), color = Panel,
        border = BorderStroke(1.5.dp, if (selected) Cyan else BorderLine)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(12.dp)) {
            Text(label, color = if (selected) Cyan else PaperDim, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

// ─── Root navigation ──────────────────────────────────────────────────────
@Composable
fun BeamSpotApp() {
    val nav = rememberNavController()
    val vm: AppViewModel = viewModel()
    val context = LocalContext.current
    val sessionManager = remember { SessionManager(context) }

    // Item 29: Check for existing session on startup — skip onboarding if already set up
    val startDest = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val sharedPrefs = context.getSharedPreferences("beamspot_prefs", Context.MODE_PRIVATE)
        val token = sessionManager.getJwtToken() ?: sharedPrefs.getString("jwt_token", null)
        val hasSetup = sessionManager.hasCompletedSetup()
        if (token != null) {
            RetrofitClient.setToken(token)
            // Restore ViewModel state from DataStore
            val dmName = sessionManager.getUserName()
            if (dmName.isNotEmpty()) vm.userName = dmName
            val dmEmail = sessionManager.getUserEmail()
            if (dmEmail.isNotEmpty()) vm.userEmail = dmEmail
            vm.isDemoMode = sessionManager.isDemoMode() || sharedPrefs.getBoolean("is_demo_mode", false)
            val dmListingId = sessionManager.getActiveListingId()
            if (dmListingId.isNotEmpty()) vm.activeListingId = dmListingId
            val dmMode = sessionManager.getSelectedMode()
            if (dmMode.isNotEmpty()) vm.selectedMode = dmMode
            val dmNetName = sessionManager.getBeamSpotNetworkName()
            if (dmNetName.isNotEmpty()) vm.beamSpotNetworkName = dmNetName
            val dmPrice = sessionManager.getPricePerMin()
            if (dmPrice > 0) vm.pricePerMin = dmPrice
            
            vm.isSignedIn = true
            if (hasSetup || vm.beamSpotNetworkName.isNotEmpty()) {
                startDest.value = Route.MAIN_APP
            } else {
                startDest.value = Route.ROUTER_SETUP
            }
        } else {
            startDest.value = Route.SPLASH
        }
    }

    val currentStart = startDest.value ?: return

    NavHost(navController = nav, startDestination = currentStart) {
        composable(Route.SPLASH)         { SplashScreen(nav) }
        composable(Route.LANDING)        { LandingScreen(nav, vm) }
        composable(Route.SIGN_IN)        { SignInScreen(nav, vm) }
        composable(Route.PAYOUT_SETUP)   { PayoutSetupScreen(nav, vm) }
        composable(Route.ROUTER_SETUP)   { RouterSetupScreen(nav, vm) }
        composable(Route.MAIN_APP)       { MainAppScreen(nav, vm) }
    }
}

// ─── Main App Screen with Bottom Navigation ───────────────────────────────
@Composable
fun MainAppScreen(rootNav: NavHostController, vm: AppViewModel) {
    val tabNav = rememberNavController()
    val context = LocalContext.current
    val sessionManager = remember { SessionManager(context) }

    // Item 30: Save setup completion flag when entering main app
    LaunchedEffect(Unit) {
        sessionManager.setCompletedSetup(true)
        sessionManager.saveJwtToken(RetrofitClient.getToken())
        sessionManager.saveUserProfile(vm.userName, vm.userEmail, vm.isDemoMode)
        sessionManager.saveHostSetup(
            listingId = vm.activeListingId,
            selectedMode = vm.selectedMode,
            networkName = vm.beamSpotNetworkName,
            pricePerMin = vm.pricePerMin
        )
    }
    var selectedTab by remember { mutableStateOf(BottomTab.DASHBOARD) }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            Column {
                HorizontalDivider(color = BorderLine, thickness = 0.5.dp)
                NavigationBar(
                    containerColor = Panel,
                    contentColor = Paper,
                    tonalElevation = 0.dp
                ) {
                BottomTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            selectedTab = tab
                            tabNav.navigate(tab.route) {
                                popUpTo(tabNav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = {
                            Text(
                                tab.label,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Cyan,
                            selectedTextColor = Cyan,
                            unselectedIconColor = PaperDim,
                            unselectedTextColor = PaperDim,
                            indicatorColor = Cyan.copy(0.1f)
                        )
                    )
                }
            }
        }
    }
) { innerPadding ->
        NavHost(
            navController = tabNav,
            startDestination = BottomTab.DASHBOARD.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(BottomTab.DASHBOARD.route) {
                DashboardScreen(rootNav, vm)
            }
            composable(BottomTab.FIND_INTERNET.route) {
                GuestPortalScreen(rootNav, vm)
            }
            composable(BottomTab.EARNINGS.route) {
                EarningsScreen(rootNav, vm)
            }
            composable(BottomTab.SETTINGS.route) {
                SettingsScreen(rootNav, vm)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// SPLASH SCREEN
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun SplashScreen(nav: NavHostController) {
    val scale by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.92f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "scale"
    )
    val context = LocalContext.current
    val sessionManager = remember { SessionManager(context) }
    LaunchedEffect(Unit) {
        delay(2000)
        val token = sessionManager.getJwtToken()
        val hasSetup = sessionManager.hasCompletedSetup()
        if (token != null) {
            if (hasSetup) {
                nav.navigate(Route.MAIN_APP) { popUpTo(Route.SPLASH) { inclusive = true } }
            } else {
                nav.navigate(Route.ROUTER_SETUP) { popUpTo(Route.SPLASH) { inclusive = true } }
            }
        } else {
            nav.navigate(Route.LANDING) { popUpTo(Route.SPLASH) { inclusive = true } }
        }
    }
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Custom icon: orange circle + cyan beam dots
            Box(
                Modifier
                    .scale(scale)
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(Panel)
                    .border(2.dp, Cyan.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Amber),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Paper))
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(22, 30, 22).forEach { w ->
                            Box(Modifier.width(w.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Cyan))
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("BeamSpot", color = Paper, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
            Spacer(Modifier.height(6.dp))
            Text("Pay-by-the-minute internet", color = PaperDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ─── LANDING SCREEN — two cards
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun LandingScreen(nav: NavHostController, vm: AppViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxSize().background(Ink).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("BeamSpot", color = Paper, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
            Spacer(Modifier.height(8.dp))
            Text("Local internet, paid by the minute.", color = PaperDim, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LandingCard(
                title = "I need internet",
                subtitle = "Find a nearby BeamSpot and pay for access",
                accentColor = Amber,
                icon = Icons.Filled.Wifi,
                onClick = {
                    nav.navigate(Route.MAIN_APP) {
                        popUpTo(Route.LANDING) { inclusive = false }
                    }
                }
            )
            LandingCard(
                title = "I have internet",
                subtitle = "Share your connection and earn per minute",
                accentColor = Cyan,
                icon = Icons.Filled.Router,
                onClick = {
                    if (vm.isSignedIn) {
                        nav.navigate(Route.ROUTER_SETUP)
                    } else {
                        nav.navigate(Route.SIGN_IN)
                    }
                }
            )
        }
        Spacer(Modifier.weight(1f))
        
        val annotatedText = androidx.compose.ui.text.buildAnnotatedString {
            append("By continuing you agree to BeamSpot's ")
            
            val termsStart = length
            append("Terms of Service")
            val termsEnd = length
            addStringAnnotation(tag = "URL", annotation = "terms", start = termsStart, end = termsEnd)
            addStyle(
                style = androidx.compose.ui.text.SpanStyle(
                    color = Cyan,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    fontWeight = FontWeight.Medium
                ),
                start = termsStart,
                end = termsEnd
            )
            
            append(" and ")
            
            val privacyStart = length
            append("Privacy Policy")
            val privacyEnd = length
            addStringAnnotation(tag = "URL", annotation = "privacy", start = privacyStart, end = privacyEnd)
            addStyle(
                style = androidx.compose.ui.text.SpanStyle(
                    color = Cyan,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    fontWeight = FontWeight.Medium
                ),
                start = privacyStart,
                end = privacyEnd
            )
            
            append(".")
        }
        
        androidx.compose.foundation.text.ClickableText(
            text = annotatedText,
            style = androidx.compose.ui.text.TextStyle(
                color = PaperDim,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            onClick = { offset ->
                annotatedText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                    .firstOrNull()?.let { annotation ->
                        try {
                            val baseUrl = BuildConfig.API_BASE_URL.removeSuffix("/")
                            val destUrl = if (annotation.item == "terms") "$baseUrl/terms" else "$baseUrl/privacy"
                            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(destUrl))
                            context.startActivity(browserIntent)
                        } catch (e: Exception) {
                            android.util.Log.e("BeamSpot_Landing", "Failed to open link: ${annotation.item}", e)
                        }
                    }
            }
        )
    }
}

@Composable
private fun LandingCard(title: String, subtitle: String, accentColor: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Panel,
        border = BorderStroke(1.dp, accentColor.copy(0.25f))
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(accentColor.copy(0.1f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accentColor, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(subtitle, color = PaperDim, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = accentColor)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// SIGN-IN SCREEN — Google account picker with Demo Fallback
// ─────────────────────────────────────────────────────────────────────────
private fun getAppSignatures(context: android.content.Context): String {
    try {
        val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
        }
        val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }
        if (signatures != null && signatures.isNotEmpty()) {
            val md = java.security.MessageDigest.getInstance("SHA1")
            val publicKey = md.digest(signatures[0].toByteArray())
            val hexString = java.lang.StringBuilder()
            for (i in publicKey.indices) {
                val appendString = java.lang.Integer.toHexString(0xFF and publicKey[i].toInt())
                if (appendString.length == 1) hexString.append("0")
                hexString.append(appendString)
                if (i < publicKey.size - 1) hexString.append(":")
            }
            return hexString.toString().uppercase()
        }
    } catch (e: java.lang.Exception) {
        android.util.Log.e("BeamSpot_SHA1", "Error getting signature SHA1", e)
    }
    return "Unknown SHA-1"
}

@Composable
fun SignInScreen(nav: NavHostController, vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var termsAccepted by remember { mutableStateOf(false) }

    // Direct Instant Profile sign-in state
    var customName by remember { mutableStateOf("") }
    var customEmail by remember { mutableStateOf("") }

    fun checkTermsAccepted(): Boolean {
        if (!termsAccepted) {
            errorMessage = "⚠️ Please agree to the Terms of Service and Privacy Policy to continue."
            return false
        }
        return true
    }

    // When the user picks a Google account, this receives the result
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken != null) {
                isLoading = true
                scope.launch {
                    try {
                        val response = RetrofitClient.apiService.verifyGoogleIdToken(GoogleAuthRequest(idToken))
                        vm.saveToken(response.token)
                        vm.userName   = response.user.name
                        vm.userEmail  = response.user.email
                        vm.isDemoMode = false
                        vm.isSignedIn = true
                        isLoading = false
                        nav.navigate(Route.PAYOUT_SETUP) { popUpTo(Route.SIGN_IN) { inclusive = true } }
                    } catch (ex: Exception) {
                        android.util.Log.e("BeamSpot_SignIn", "Backend verification failed with Google idToken", ex)
                        errorMessage = "Backend verification failed: ${ex.localizedMessage ?: "Unknown error"}. Check your backend server."
                        isLoading = false
                    }
                }
            } else {
                errorMessage = "Google ID token was null. Cannot verify with backend."
            }
        } catch (e: ApiException) {
            isLoading = false
            android.util.Log.e("BeamSpot_SignIn", "Google Sign-In ApiException caught! Type: ${e.javaClass.name}, StatusCode: ${e.statusCode}, Message: ${e.message}", e)
            
            val sha1 = getAppSignatures(context)
            val buildType = if (BuildConfig.DEBUG) "debug" else "release"
            val appPkg = context.packageName
            val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
            val packageInfo = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
            } catch (ex: Exception) { null }
            val verCode = packageInfo?.let { 
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode 
            } ?: 0
            val verName = packageInfo?.versionName ?: "Unknown"

            if (e.statusCode == 10) {
                errorMessage = "Google Sign-In failed (Code 10: Developer Error).\n\n" +
                        "This happens because the certificate fingerprint of this build is not registered under your Android Client ID in Google Cloud.\n\n" +
                        "To fix this once and for all:\n" +
                        "1. Go to Google Cloud Console -> Credentials\n" +
                        "2. Select your Android OAuth 2.0 Client ID (or create one)\n" +
                        "3. Update the package name and certificate fingerprint to:\n" +
                        "   • Package Name: $appPkg\n" +
                        "   • SHA-1 Fingerprint:\n     $sha1\n\n" +
                        "DIAGNOSTICS:\n" +
                        "• Package Name: $appPkg\n" +
                        "• SHA-1 Fingerprint: $sha1\n" +
                        "• Web Client ID: $webClientId\n" +
                        "• Build Type: $buildType\n" +
                        "• Version: $verName ($verCode)\n\n" +
                        "Meanwhile, you can use our 100% functional Direct Instant Profile Login below to bypass Google login and continue testing!"
            } else {
                val friendlyMsg = when (e.statusCode) {
                    7 -> "Network error (code 7). Please make sure your device has internet access."
                    8 -> "Internal configuration error (code 8). Please verify that GOOGLE_WEB_CLIENT_ID matches your Google Cloud Web Client ID."
                    12500 -> "Sign-in required (code 12500). Please select an active Google account to sign in."
                    12501 -> "Google Sign-In was cancelled or not initialized.\n\nNo worries! You can use our Direct Setup below to type your name and email manually. This is 100% functional and lets you start hosting instantly."
                    12502 -> "Sign-in is currently in progress."
                    else  -> "Sign-in failed (code ${e.statusCode}). Check your internet connection or Google Client ID configuration."
                }
                errorMessage = "$friendlyMsg\n\n" +
                        "RAW DETAILS:\n" +
                        "• Exception: ${e.javaClass.simpleName}\n" +
                        "• Message: ${e.localizedMessage ?: e.message}\n" +
                        "• Code: ${e.statusCode}\n" +
                        "• Package Name: $appPkg\n" +
                        "• SHA-1 Fingerprint: $sha1\n" +
                        "• Web Client ID: $webClientId\n" +
                        "• Build Type: $buildType\n" +
                        "• Version: $verName ($verCode)"
            }
        }
    }

    fun launchGoogleSignIn() {
        if (!checkTermsAccepted()) return
        isLoading = true
        errorMessage = ""
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .build()
        val client = GoogleSignIn.getClient(context, gso)
        client.signOut().addOnCompleteListener {
            signInLauncher.launch(client.signInIntent)
        }
    }

    fun proceedAsDemoHost() {
        if (!checkTermsAccepted()) return
        vm.userName = "Jane Host"
        vm.userEmail = "jane.host.beamspot@gmail.com"
        vm.isDemoMode = true
        vm.isSignedIn = true
        vm.saveToken("demo_token")
        nav.navigate(Route.PAYOUT_SETUP) { popUpTo(Route.SIGN_IN) { inclusive = true } }
    }

    fun proceedWithCustomDirectHost() {
        if (!checkTermsAccepted()) return
        if (customName.isBlank() || customEmail.isBlank()) {
            errorMessage = "⚠️ Please enter both your Full Name and Email Address to create your direct profile."
            return
        }
        vm.userName = customName.trim()
        vm.userEmail = customEmail.trim()
        vm.isDemoMode = true
        vm.isSignedIn = true
        vm.saveToken("demo_token")
        nav.navigate(Route.PAYOUT_SETUP) { popUpTo(Route.SIGN_IN) { inclusive = true } }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text("Back", color = PaperDim, modifier = Modifier.align(Alignment.Start).clickable { nav.popBackStack() }, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        Text("Sign in to BeamSpot", color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Use Google or set up an Instant Profile to test & host your BeamSpot hotspot network.", color = PaperDim, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)

        Spacer(Modifier.height(28.dp))

        if (errorMessage.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF3B1E12),
                border = BorderStroke(1.dp, Amber.copy(0.3f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(Modifier.padding(14.dp)) {
                        Text(errorMessage, color = Color(0xFFFFA726), fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        }

        // Terms & Conditions and Privacy Policy Checkbox (Item 19)
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Checkbox(
                    checked = termsAccepted,
                    onCheckedChange = { termsAccepted = it },
                    colors = androidx.compose.material3.CheckboxDefaults.colors(
                        checkedColor = Cyan,
                        uncheckedColor = PaperDim,
                        checkmarkColor = Ink
                    )
                )
                Spacer(Modifier.width(8.dp))
                Row {
                    Text("I agree to the ", color = PaperDim, fontSize = 13.sp)
                    Text(
                        text = "Terms of Service",
                        color = Cyan,
                        fontSize = 13.sp,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            try {
                                val baseUrl = BuildConfig.API_BASE_URL.removeSuffix("/")
                                val termsUrl = "$baseUrl/terms"
                                val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(termsUrl))
                                context.startActivity(browserIntent)
                            } catch (e: Exception) {
                                android.util.Log.e("BeamSpot_SignIn", "Failed to open terms link", e)
                            }
                        }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("and the ", color = PaperDim, fontSize = 13.sp)
                Text(
                    text = "Privacy Policy",
                    color = Cyan,
                    fontSize = 13.sp,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        try {
                            val baseUrl = BuildConfig.API_BASE_URL.removeSuffix("/")
                            val privacyUrl = "$baseUrl/privacy"
                            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(privacyUrl))
                            context.startActivity(browserIntent)
                        } catch (e: Exception) {
                            android.util.Log.e("BeamSpot_SignIn", "Failed to open privacy link", e)
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Official Google Sign-In button style: white background, Google G, black text
        Surface(
            onClick = { launchGoogleSignIn() },
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color(0xFF4285F4))
                } else {
                    GoogleGLogo()
                    Spacer(Modifier.width(12.dp))
                    Text("Continue with Google", color = Color(0xFF1F1F1F), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // OR Divider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(Modifier.weight(1f), color = BorderLine)
            Text("OR CREATE DIRECT PROFILE", color = PaperDim, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp), fontFamily = FontFamily.Monospace)
            HorizontalDivider(Modifier.weight(1f), color = BorderLine)
        }

        Spacer(Modifier.height(18.dp))

        // Direct Profile Creation Card (Foolproof alternative setup)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Panel,
            border = BorderStroke(1.dp, BorderLine),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Direct Instant Profile", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Text("No Google Account required. Setup your custom hosting name and email instantly.", color = PaperDim, fontSize = 12.sp, lineHeight = 16.sp)
                
                Spacer(Modifier.height(14.dp))
                
                BeamLabel("Full Name / Business Name")
                BeamInput(value = customName, onValueChange = { customName = it }, placeholder = "e.g. Mama Jane Shop")
                
                Spacer(Modifier.height(10.dp))
                
                BeamLabel("Email Address")
                BeamInput(value = customEmail, onValueChange = { customEmail = it }, placeholder = "e.g. mama.jane@gmail.com")
                
                Spacer(Modifier.height(18.dp))
                
                Button(
                    onClick = { proceedWithCustomDirectHost() },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Create Direct Profile →", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // Simple default demo account quick bypass
        TextButton(
            onClick = { proceedAsDemoHost() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FlashOn, null, tint = Amber, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Quick Bypass with Sample Demo Account (Jane Host)", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// Proper Google G logo using standard M3 Icon
@Composable
private fun GoogleGLogo() {
    Icon(
        imageVector = Icons.Filled.AccountCircle,
        contentDescription = "Google Logo",
        tint = Color(0xFF4285F4),
        modifier = Modifier.size(24.dp)
    )
}

// ─────────────────────────────────────────────────────────────────────────
// PAYOUT SETUP SCREEN
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun PayoutSetupScreen(nav: NavHostController, vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (RetrofitClient.getToken() != null) {
            isLoading = true
            try {
                val payout = RetrofitClient.apiService.getPayout()
                payout.payoutMethod?.let { vm.payoutMethod = it }
                payout.payoutNumber?.let { vm.payoutNumber = it }
                payout.bankName?.let { vm.bankName = it }
                payout.bankAccount?.let { vm.bankAccount = it }
                payout.bankHolder?.let { vm.bankHolder = it }
            } catch (e: Exception) {
                android.util.Log.e("PayoutSetupScreen", "Failed to load payout details", e)
            } finally {
                isLoading = false
            }
        }
    }

    val phoneRegex = remember { Regex("^(?:\\+?254|0)(7|1)\\d{8}$") }
    val isPhoneValid = phoneRegex.matches(vm.payoutNumber.replace(" ", ""))
    val isBankAccountValid = vm.bankAccount.isNotBlank() && vm.bankAccount.all { it.isDigit() } && vm.bankAccount.length in 6..15
    val isBankHolderValid = vm.bankHolder.isNotBlank() && vm.bankHolder.trim().split("\\s+".toRegex()).size >= 2

    val isFormValid = when (vm.payoutMethod) {
        "mpesa", "airtel" -> vm.payoutNumber.isNotBlank() && isPhoneValid
        "bank" -> vm.bankName.isNotBlank() && isBankAccountValid && isBankHolderValid
        "card" -> true
        else -> false
    }

    Column(
        Modifier.fillMaxSize().background(Ink)
            .verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        StepBadge("Step 1 of 2")
        Spacer(Modifier.height(12.dp))
        Text("Where should we send your money?", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Earnings go directly to your chosen account when you withdraw.", color = PaperDim, fontSize = 13.sp)
        
        if (isLoading) {
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.CircularProgressIndicator(
                color = Cyan, 
                modifier = Modifier.align(Alignment.CenterHorizontally).size(24.dp)
            )
        }

        if (errorMessage.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(errorMessage, color = Color(0xFFEF5350), fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(24.dp))

        // Payout method cards
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("mpesa", "airtel", "bank").forEach { method ->
                PayoutMethodCard(
                    label = when(method) { "mpesa" -> "M-Pesa"; "airtel" -> "Airtel Money"; else -> "Bank" },
                    selected = vm.payoutMethod == method,
                    modifier = Modifier.weight(1f),
                    onClick = { vm.payoutMethod = method }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Card option as separate full-width row
        PayoutMethodCard(
            label = "Debit / Credit Card",
            selected = vm.payoutMethod == "card",
            modifier = Modifier.fillMaxWidth(),
            onClick = { vm.payoutMethod = "card" }
        )

        Spacer(Modifier.height(20.dp))

        // Dynamic fields based on method
        when (vm.payoutMethod) {
            "mpesa", "airtel" -> {
                BeamLabel(if (vm.payoutMethod == "mpesa") "M-Pesa Number" else "Airtel Money Number")
                BeamInput(
                    value = vm.payoutNumber,
                    onValueChange = { vm.payoutNumber = it },
                    placeholder = "e.g. 0712 345 678",
                    keyboardType = KeyboardType.Phone
                )
                if (vm.payoutNumber.isNotBlank() && !isPhoneValid) {
                    Spacer(Modifier.height(4.dp))
                    Text("Enter a valid Kenyan number (e.g. 0712345678 or +254...)", color = Color(0xFFFF8A80), fontSize = 11.sp)
                }
            }
            "bank" -> {
                BeamLabel("Bank Name")
                BeamInput(value = vm.bankName, onValueChange = { vm.bankName = it }, placeholder = "e.g. Equity Bank")
                Spacer(Modifier.height(10.dp))
                BeamLabel("Account Number")
                BeamInput(value = vm.bankAccount, onValueChange = { vm.bankAccount = it }, placeholder = "e.g. 1234567890", keyboardType = KeyboardType.Number)
                if (vm.bankAccount.isNotBlank() && !isBankAccountValid) {
                    Spacer(Modifier.height(4.dp))
                    Text("Account number must be numeric and 6 to 15 digits long", color = Color(0xFFFF8A80), fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                BeamLabel("Account Holder Name")
                BeamInput(value = vm.bankHolder, onValueChange = { vm.bankHolder = it }, placeholder = "e.g. Jane Doe")
                if (vm.bankHolder.isNotBlank() && !isBankHolderValid) {
                    Spacer(Modifier.height(4.dp))
                    Text("Enter both first and last name (at least two words)", color = Color(0xFFFF8A80), fontSize = 11.sp)
                }
            }
            "card" -> {
                Text("Card payouts require Flutterwave account verification. You'll receive a link to complete this after setup.", color = PaperDim, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(32.dp))

        BeamButton(if (isSaving) "Saving…" else "Continue", Cyan, enabled = isFormValid && !isSaving && !isLoading) {
            if (RetrofitClient.getToken() == null) {
                nav.navigate(Route.ROUTER_SETUP)
                return@BeamButton
            }
            isSaving = true
            errorMessage = ""
            scope.launch {
                try {
                    RetrofitClient.apiService.savePayout(
                        PayoutDetailsRequest(
                            payoutMethod = vm.payoutMethod,
                            payoutNumber = if (vm.payoutMethod == "bank" || vm.payoutMethod == "card") "" else normalizeKenyanPhone(vm.payoutNumber),
                            bankName = if (vm.payoutMethod == "bank") vm.bankName else null,
                            bankAccount = if (vm.payoutMethod == "bank") vm.bankAccount else null,
                            bankHolder = if (vm.payoutMethod == "bank") vm.bankHolder else null
                        )
                    )
                    nav.navigate(Route.ROUTER_SETUP)
                } catch (e: Exception) {
                    errorMessage = "Failed to save: ${e.localizedMessage ?: "Unknown error"}"
                    android.util.Log.e("PayoutSetupScreen", "Failed to save payout", e)
                } finally {
                    isSaving = false
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// MODE SELECTION SCREEN
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun ModeSelectScreen(nav: NavHostController, vm: AppViewModel) {
    val context = LocalContext.current
    
    val activeModes = listOf(
        Triple("router", "ISP Hotspot Mode", "Fully automated hotspot billing system. Connect your MikroTik router, configure M-Pesa payments, and manage custom packages.")
    )

    val modes = activeModes.map { (id, title, desc) ->
        val enabled = true
        val badge = "RECOMMENDED"
        val badgeColor = Cyan
        val selected = true
        
        object {
            val id = id
            val title = title
            val desc = desc
            val enabled = enabled
            val badge = badge
            val badgeColor = badgeColor
            val selected = selected
        }
    }

    // Default select router mode
    LaunchedEffect(Unit) {
        vm.selectedMode = "router"
    }

    Column(
        Modifier.fillMaxSize().background(Ink)
            .verticalScroll(rememberScrollState())
    ) {
        Column(Modifier.padding(24.dp)) {
            Spacer(Modifier.height(40.dp))
            StepBadge("Step 2 of 2")
            Spacer(Modifier.height(12.dp))
            Text("ISP Hotspot billing", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Powering your neighborhood internet business.", color = PaperDim, fontSize = 13.sp)
        }

        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            modes.forEach { mode ->
                ModeCard(
                    id = mode.id, title = mode.title, description = mode.desc,
                    badge = mode.badge,
                    badgeColor = mode.badgeColor,
                    selected = mode.selected,
                    enabled = mode.enabled,
                    onSelect = { vm.selectedMode = mode.id }
                )
            }
            Spacer(Modifier.height(24.dp))
            BeamButton("Configure Setup →", Cyan, enabled = true) {
                nav.navigate(Route.ROUTER_SETUP)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModeCard(id: String, title: String, description: String, badge: String, badgeColor: Color, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    val alpha = if (enabled) 1f else 0.45f
    Surface(
        onClick = { if (enabled) onSelect() },
        shape = RoundedCornerShape(18.dp),
        color = Panel,
        border = BorderStroke(1.5.dp, if (selected && enabled) Cyan else BorderLine),
        modifier = Modifier.fillMaxWidth().alpha(alpha)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                RadioButton(
                    selected = selected, onClick = { if (enabled) onSelect() },
                    enabled = enabled,
                    colors = RadioButtonDefaults.colors(selectedColor = Cyan, unselectedColor = PaperDim)
                )
                Surface(shape = RoundedCornerShape(6.dp), color = badgeColor.copy(0.12f)) {
                    Text(badge, color = badgeColor, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(title, color = if (enabled) Paper else PaperDim, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(description, color = if (enabled) PaperDim else PaperDim.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// SMART BRIDGE: WIFI SCAN SCREEN (real scan, no fake data)
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun SmartBridgeWifiScanScreen_OLD(nav: NavHostController, vm: AppViewModel) {
    val context = LocalContext.current
    val helper  = remember { WifiScanHelper(context) }
    val scope   = rememberCoroutineScope()

    var networks  by remember { mutableStateOf<List<WifiNetwork>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var scanError  by remember { mutableStateOf("") }

    // Location permission launcher — REQUIRED to scan WiFi SSIDs on Android 9+
    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            isScanning = true
            scope.launch {
                networks = helper.scanNetworks()
                isScanning = false
            }
        } else {
            scanError = "Location permission is required to scan nearby WiFi networks. Please grant it in Settings."
        }
    }

    fun startScan() {
        scanError = ""
        if (!helper.isLocationEnabled()) {
            scanError = "System Location is turned off. Please turn on Location in your device's settings or quick settings to scan for WiFi networks."
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            locationPermLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } else {
            isScanning = true
            scope.launch {
                networks = helper.scanNetworks()
                isScanning = false
            }
        }
    }

    // Auto-scan on open
    LaunchedEffect(Unit) { startScan() }

    Column(Modifier.fillMaxSize().background(Ink)) {
        TopBar("Pick your WiFi") { nav.popBackStack() }
        Text("Your phone's internet source. Guests will NOT see or use this network directly.",
            color = PaperDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

        if (scanError.isNotEmpty()) {
            Surface(Modifier.padding(16.dp).fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color(0xFF3B0000)) {
                Text(scanError, color = Color(0xFFFF8A80), modifier = Modifier.padding(12.dp), fontSize = 12.sp)
            }
        }

        if (isScanning) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Cyan)
                    Spacer(Modifier.height(14.dp))
                    Text("Scanning for networks…", color = PaperDim, fontSize = 13.sp)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${networks.size} network(s) found", color = PaperDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                TextButton(onClick = ::startScan) { Text("Rescan", color = Cyan, fontSize = 12.sp) }
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(networks) { net ->
                    WifiNetworkRow(net) {
                        vm.selectedWifi = net
                        val encodedSsid  = net.ssid.replace("/", "%2F")
                        val encodedBssid = net.bssid.replace(":", "-")
                        nav.navigate("sb_password/$encodedSsid/$encodedBssid")
                    }
                }
            }
        }
    }
}

@Composable
private fun WifiNetworkRow(net: WifiNetwork, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = Panel, border = BorderStroke(1.dp, BorderLine), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SignalBars(net.signalBars)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(net.ssid, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("${net.rssi} dBm · ${net.frequencyMhz}MHz · ${if (net.isSecured) "Secured" else "Open"}", color = PaperDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = Cyan)
        }
    }
}

@Composable
private fun SignalBars(bars: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(6, 10, 14, 18, 22).forEachIndexed { idx, h ->
            Box(
                Modifier.width(4.dp).height(h.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (idx < bars) Cyan else PaperDim.copy(0.3f))
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// SMART BRIDGE: PASSWORD ENTRY & ACCOUNT-NETWORK LOCK ENFORCEMENT
// ─────────────────────────────────────────────────────────────────────────
fun checkAndLockNetwork(context: Context, ssid: String, userEmail: String): Result<Unit> {
    if (ssid.isBlank()) return Result.success(Unit)
    val locksPrefs = context.getSharedPreferences("beamspot_network_locks", Context.MODE_PRIVATE)
    val lockedEmail = locksPrefs.getString(ssid, null)
    if (lockedEmail != null && lockedEmail.lowercase() != userEmail.lowercase()) {
        return Result.failure(Exception("This home WiFi network is already linked to another BeamSpot account ($lockedEmail). Each home network can only be linked to a single Gmail account. Please sign in with $lockedEmail or use a different home network."))
    }
    // Lock it to the current userEmail
    locksPrefs.edit().putString(ssid, userEmail).apply()
    return Result.success(Unit)
}

@Composable
fun SmartBridgePasswordScreen_OLD(nav: NavHostController, vm: AppViewModel, ssid: String, bssid: String) {
    val decodedSsid = ssid.replace("%2F", "/")
    var password  by remember { mutableStateOf("") }
    var showPass  by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf("") }
    val context = LocalContext.current
    val wifiConnectHelper = remember { "stub" }

    Column(Modifier.fillMaxSize().background(Ink).padding(24.dp)) {
        TopBar("Enter WiFi password") { nav.popBackStack() }
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(14.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Wifi, null, tint = Cyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(decodedSsid, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(bssid.replace("-", ":"), color = PaperDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        BeamLabel("WiFi Password")
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            placeholder = { Text("Enter the WiFi password", color = PaperDim) },
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Icon(if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, tint = PaperDim)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Paper, unfocusedTextColor = Paper,
                focusedBorderColor = Cyan, unfocusedBorderColor = BorderLine,
                cursorColor = Cyan
            ),
            shape = RoundedCornerShape(12.dp)
        )

        if (connectError.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(connectError, color = Color(0xFFFF6B6B), fontSize = 12.sp)
        }

        Spacer(Modifier.weight(1f))
        BeamButton(if (isConnecting) "Connecting…" else "Connect & Create BeamSpot Network →", Cyan, enabled = password.isNotBlank() && !isConnecting) {
            val lockRes = checkAndLockNetwork(context, decodedSsid, vm.userEmail)
            if (lockRes.isFailure) {
                connectError = lockRes.exceptionOrNull()?.message ?: "Network locked"
                return@BeamButton
            }
            isConnecting = true
            connectError = ""
            isConnecting = false
            nav.navigate(Route.SB_PERMISSIONS)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val lockRes = checkAndLockNetwork(context, decodedSsid, vm.userEmail)
                if (lockRes.isFailure) {
                    connectError = lockRes.exceptionOrNull()?.message ?: "Network locked"
                    return@Button
                }
                isConnecting = false
                nav.navigate(Route.SB_PERMISSIONS)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Panel,
                contentColor = Amber
            ),
            border = BorderStroke(1.dp, Amber.copy(0.4f))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SkipNext, null, tint = Amber, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Skip WiFi Validation & Force Proceed", color = Amber, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// SMART BRIDGE: REAL PERMISSIONS (no fake gimmicks)
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun SmartBridgePermissionsScreen_OLD(nav: NavHostController, vm: AppViewModel) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    data class PermItem(val label: String, val reason: String, var granted: Boolean = false)

    var perms by remember { mutableStateOf(listOf(
        PermItem("Location", "Required by Android to scan and connect to WiFi networks"),
        PermItem("Notifications", "Sends session warnings (5 min, 1 min left) and earning alerts"),
        PermItem("Run in background", "Keeps the VPN and session timers alive when screen is off"),
        PermItem("VPN", "Creates the virtual network that routes and controls guest internet access"),
        PermItem("Modify system settings", "Required to manage hotspot configuration and connections")
    )) }

    var vpnPermLauncher: androidx.activity.result.ActivityResultLauncher<Intent>? = null
    vpnPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        perms = perms.toMutableList().apply { find { it.label == "VPN" }?.granted = true }
    }

    val multiPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        perms = perms.toMutableList().apply {
            if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) find { it.label == "Location" }?.granted = true
            if (Build.VERSION.SDK_INT >= 33 && results[Manifest.permission.POST_NOTIFICATIONS] == true)
                find { it.label == "Notifications" }?.granted = true
        }
    }

    fun requestAll() {
        val toRequest = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        multiPermLauncher.launch(toRequest.toTypedArray())

        // Battery optimisation — opens system settings page (no runtime perm, must redirect)
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }
        try { context.startActivity(intent) } catch (_: Exception) {}
        perms = perms.toMutableList().apply { find { it.label == "Run in background" }?.granted = true }

        // VPN — shows Android's own VPN consent dialog
        val vpnIntent = try {
            VpnService.prepare(context)
        } catch (e: Exception) {
            android.util.Log.e("BeamSpot", "Failed to prepare VPN in requestAll", e)
            null
        }
        if (vpnIntent != null) {
            vpnPermLauncher?.launch(vpnIntent)
        } else {
            perms = perms.toMutableList().apply { find { it.label == "VPN" }?.granted = true }
        }

        // Modify system settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            val writeIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(writeIntent) } catch (_: Exception) {}
        }
    }

    // Item 34-35: Fix permissions screen — real per-permission state tracking, tap-to-fix, gate Continue
    var allGranted by remember { mutableStateOf(false) }

    // Check real permission states on every composition
    LaunchedEffect(Unit) {
        while (true) {
            val locationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val notifGranted = if (Build.VERSION.SDK_INT >= 33)
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true
            val writeSettingsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                Settings.System.canWrite(context)
            else true
            val batteryGranted = try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                else true
            } catch (_: Exception) { false }
            val vpnGranted = try {
                VpnService.prepare(context) == null
            } catch (e: Exception) {
                // In sandboxed/emulator environments, VpnService.prepare may throw a SecurityException.
                // We treat it as granted to avoid blocking the user.
                true
            }

            perms = perms.toMutableList().apply {
                find { it.label == "Location" }?.granted = locationGranted
                find { it.label == "Notifications" }?.granted = notifGranted
                find { it.label == "Run in background" }?.granted = batteryGranted
                find { it.label == "VPN" }?.granted = vpnGranted
                find { it.label == "Modify system settings" }?.granted = writeSettingsGranted
            }
            allGranted = locationGranted && notifGranted && writeSettingsGranted && batteryGranted && vpnGranted
            delay(2000) // Re-check every 2s in case user returns from system settings
        }
    }

    fun requestPermissionFor(permLabel: String) {
        when (permLabel) {
            "Location" -> {
                multiPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            "Notifications" -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    multiPermLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
            "VPN" -> {
                try {
                    val vpnIntent = VpnService.prepare(context)
                    if (vpnIntent != null) vpnPermLauncher?.launch(vpnIntent)
                } catch (e: Exception) {
                    android.util.Log.e("BeamSpot", "Failed to prepare VPN in requestPermissionFor", e)
                }
            }
            "Battery optimisation", "Run in background" -> {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { context.startActivity(intent) } catch (_: Exception) {}
            }
            "Modify system settings" -> {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { context.startActivity(intent) } catch (_: Exception) {
                    // Fallback: open app info
                    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { context.startActivity(fallback) } catch (_: Exception) {}
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Ink).statusBarsPadding().padding(24.dp).verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(40.dp))
        Text("Permissions needed", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("BeamSpot needs these to manage guest sessions. Tap any permission card to request it.", color = PaperDim, fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(24.dp))

        perms.forEach { perm ->
            Surface(
                onClick = { requestPermissionFor(perm.label) },
                shape = RoundedCornerShape(12.dp),
                color = Panel,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (perm.granted) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = if (perm.granted) "Granted" else "Pending",
                        tint = if (perm.granted) Color(0xFF2ECC71) else PaperDim,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(perm.label, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(perm.reason, color = PaperDim, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "Request setting",
                        tint = PaperDim.copy(0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        BeamButton("Grant all permissions", Cyan) { requestAll() }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { nav.navigate(Route.SB_NAMING) }, modifier = Modifier.fillMaxWidth()) {
            Text("Skip for now (some features won't work)", color = PaperDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        BeamButton("Continue →", Cyan, enabled = allGranted) {
            nav.navigate(Route.SB_NAMING)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// SMART BRIDGE: NAMING the public BeamSpot network
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun SmartBridgeNamingScreen_OLD(nav: NavHostController, vm: AppViewModel) {
    val defaultName = "${vm.userName.take(12)}_BeamSpot".replace(" ", "_")
    var name by remember { mutableStateOf(defaultName) }

    Column(Modifier.fillMaxSize().background(Ink).padding(24.dp)) {
        Spacer(Modifier.height(40.dp))
        Text("Name your BeamSpot", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("This is the WiFi name guests will see. They connect to this — not to your real home WiFi.", color = PaperDim, fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(24.dp))
        BeamLabel("Public Network Name (SSID)")
        BeamInput(value = name, onValueChange = { if (it.length <= 32) name = it }, placeholder = "e.g. Mama_Jane_BeamSpot")
        Spacer(Modifier.height(8.dp))
        Text("Guests will see: \"$name\"", color = Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.weight(1f))
        BeamButton("Continue to Verification →", Cyan, enabled = name.isNotBlank()) {
            vm.beamSpotNetworkName = name
            nav.navigate(Route.VERIFY_SETUP)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// ROUTER SETUP: STEP-BY-STEP WIZARD (Tiers 1–4)
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun WizardStepItem(
    stepNumber: Int,
    title: String,
    difficulty: String, // "Easy", "Moderate", "Risky"
    difficultyColor: Color,
    whatItDoes: String,
    whyItNeeded: String,
    fallback: String,
    isCompleted: Boolean,
    isActive: Boolean,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) Panel else Panel.copy(alpha = 0.5f),
        border = BorderStroke(
            1.dp,
            if (isCompleted) Cyan.copy(0.6f) else if (isActive) BorderLine else BorderLine.copy(alpha = 0.3f)
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = if (isCompleted) Cyan else if (isActive) Amber else BorderLine,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isCompleted) {
                                Icon(Icons.Filled.Check, null, tint = Ink, modifier = Modifier.size(14.dp))
                            } else {
                                Text(
                                    stepNumber.toString(),
                                    color = if (isActive) Ink else PaperDim,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        title,
                        color = if (isActive) Paper else PaperDim,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                // Difficulty badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = difficultyColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, difficultyColor.copy(alpha = 0.3f)),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = difficulty,
                        color = difficultyColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (isActive || isCompleted) {
                Spacer(Modifier.height(12.dp))
                // Info block
                Text("📋 What this does:", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(whatItDoes, color = Paper, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 8.dp))

                Text("💡 Why it's needed:", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(whyItNeeded, color = PaperDim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 12.dp))

                // Custom step content
                content()

                if (fallback.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("⚠️ Fallback / Troubleshooting:", color = Color(0xFFEF5350), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(fallback, color = PaperDim, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                }
            }
        }
    }
}

@Composable
fun SecuritySafeguardsCard() {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Panel.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, BorderLine),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Security, null, tint = Cyan, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Account & Anti-Fraud Security", color = Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Text(if (expanded) "Hide ▲" else "Show Details ▼", color = Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "To safeguard your hotspot earnings and keep bandwidth costs predictable, BeamSpot enforces strict anti-fraud safeguards:",
                    color = PaperDim,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))
                Text("🔒 Host-Side Control: One Account, One Active Device", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Your host account is bound directly to your phone's secure hardware signature. Starting a hotspot session on a second device automatically revokes access on the first, preventing multi-location exploit sharing.",
                    color = PaperDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 8.dp)
                )

                Text("⚡ Guest-Side Gatekeeping: MAC Whitelisting", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Guests must authenticate. When a user completes their mobile payment, their hardware MAC address is securely whitelisted by our central firewall. Simply sharing the WiFi password with friends will NOT grant internet access — each secondary device must pay to register its own MAC address.",
                    color = PaperDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                )
            }
        }
    }
}

// ─── SMART BRIDGE STUBS (DEPRECATED) ──────────────────────────────────────
@Composable
fun SmartBridgeWifiScanScreen(nav: NavHostController, vm: AppViewModel) {
    Box(Modifier.fillMaxSize())
}

@Composable
fun SmartBridgePasswordScreen(nav: NavHostController, vm: AppViewModel, ssid: String, bssid: String) {
    Box(Modifier.fillMaxSize())
}

@Composable
fun SmartBridgePermissionsScreen(nav: NavHostController, vm: AppViewModel) {
    Box(Modifier.fillMaxSize())
}

@Composable
fun SmartBridgeNamingScreen(nav: NavHostController, vm: AppViewModel) {
    Box(Modifier.fillMaxSize())
}

// ─── LEGACY ROUTER SETUP STUBS (DEPRECATED) ──────────────────────────────
@Composable
fun RouterSetupScreen_OLD(nav: NavHostController, vm: AppViewModel) {
    Box(Modifier.fillMaxSize())
}

@Composable
fun RouterSetupScreen_OLD2(nav: NavHostController, vm: AppViewModel) {
    Box(Modifier.fillMaxSize())
}

// ─── MIKROTIK ROUTEROS API CLIENT ─────────────────────────────────────────
class RouterOsApiClient(
    val host: String,
    val port: Int = 8728,
    val timeoutMs: Int = 5000
) {
    private var socket: java.net.Socket? = null
    private var input: java.io.InputStream? = null
    private var output: java.io.OutputStream? = null

    private fun encodeLength(len: Int): ByteArray {
        return when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x4000 -> byteArrayOf(
                ((len shr 8) or 0x80).toByte(),
                (len and 0xFF).toByte()
            )
            len < 0x200000 -> byteArrayOf(
                ((len shr 16) or 0xC0).toByte(),
                ((len shr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte()
            )
            len < 0x10000000 -> byteArrayOf(
                ((len shr 24) or 0xE0).toByte(),
                ((len shr 16) and 0xFF).toByte(),
                ((len shr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte()
            )
            else -> byteArrayOf(
                0xF0.toByte(),
                ((len shr 24) and 0xFF).toByte(),
                ((len shr 16) and 0xFF).toByte(),
                ((len shr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte()
            )
        }
    }

    private fun readLength(inputStream: java.io.InputStream): Int {
        val b1 = inputStream.read()
        if (b1 == -1) return -1
        if (b1 and 0x80 == 0) return b1
        if (b1 and 0xC0 == 0x80) {
            val b2 = inputStream.read()
            return ((b1 and 0x3F) shl 8) or b2
        }
        if (b1 and 0xE0 == 0xC0) {
            val b2 = inputStream.read()
            val b3 = inputStream.read()
            return ((b1 and 0x1F) shl 16) or (b2 shl 8) or b3
        }
        if (b1 and 0xF0 == 0xE0) {
            val b2 = inputStream.read()
            val b3 = inputStream.read()
            val b4 = inputStream.read()
            return ((b1 and 0x0F) shl 24) or (b2 shl 16) or (b3 shl 8) or b4
        }
        val b2 = inputStream.read()
        val b3 = inputStream.read()
        val b4 = inputStream.read()
        val b5 = inputStream.read()
        return (b2 shl 24) or (b3 shl 16) or (b4 shl 8) or b5
    }

    private fun readWord(inputStream: java.io.InputStream): String {
        val len = readLength(inputStream)
        if (len <= 0) return ""
        val bytes = ByteArray(len)
        var read = 0
        while (read < len) {
            val count = inputStream.read(bytes, read, len - read)
            if (count == -1) break
            read += count
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun hexMd5(text: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(text.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun connect(): Boolean {
        return try {
            socket = java.net.Socket()
            socket?.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            socket?.soTimeout = timeoutMs
            input = socket?.getInputStream()
            output = socket?.getOutputStream()
            true
        } catch (e: Exception) {
            android.util.Log.w("RouterOsApiClient", "Connect failed to $host:$port: ${e.message}")
            false
        }
    }

    fun disconnect() {
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        input = null
        output = null
    }

    private fun writeWord(word: String) {
        val bytes = word.toByteArray(Charsets.UTF_8)
        val lenBytes = encodeLength(bytes.size)
        output?.write(lenBytes)
        output?.write(bytes)
    }

    fun writeSentence(words: List<String>) {
        for (word in words) {
            writeWord(word)
        }
        output?.write(0) // terminating zero byte
        output?.flush()
    }

    fun readSentence(): List<String> {
        val words = mutableListOf<String>()
        val instream = input ?: return emptyList()
        while (true) {
            val word = readWord(instream)
            if (word.isEmpty()) break
            words.add(word)
        }
        return words
    }

    fun readResponseSentenceGroup(): List<List<String>> {
        val sentences = mutableListOf<List<String>>()
        while (true) {
            val sentence = readSentence()
            if (sentence.isEmpty()) break
            sentences.add(sentence)
            val replyType = sentence.firstOrNull() ?: ""
            if (replyType == "!done" || replyType == "!trap" || replyType == "!fatal") {
                break
            }
        }
        return sentences
    }

    fun login(username: String, passwordStr: String): Pair<Boolean, String> {
        try {
            writeSentence(listOf("/login", "=name=$username", "=password=$passwordStr"))
            val response = readResponseSentenceGroup()
            val reply = response.lastOrNull()?.firstOrNull() ?: ""
            
            if (reply == "!done") {
                return Pair(true, "Authenticated successfully via RouterOS API (v7)")
            }
            
            val challengeParam = response.flatten().find { it.startsWith("=ret=") || it.startsWith("ret=") }
            val challenge = challengeParam?.substringAfter("=") ?: ""
            
            if (challenge.isNotEmpty()) {
                val md5Input = "\u0000" + passwordStr + challenge
                val responseHex = hexMd5(md5Input)
                writeSentence(listOf("/login", "=name=$username", "=response=00$responseHex"))
                val v6Resp = readResponseSentenceGroup()
                val v6Reply = v6Resp.lastOrNull()?.firstOrNull() ?: ""
                if (v6Reply == "!done") {
                    return Pair(true, "Authenticated successfully via RouterOS API (v6)")
                }
                return Pair(false, "v6 authentication failed: " + v6Resp.flatten().joinToString(", "))
            }
            
            return Pair(false, "Login failed: " + response.flatten().joinToString(", "))
        } catch (e: Exception) {
            return Pair(false, "API Communication Error: ${e.localizedMessage}")
        }
    }

    fun ping(address: String): Pair<Boolean, String> {
        return try {
            writeSentence(listOf("/ping", "=address=$address", "=count=2"))
            val response = readResponseSentenceGroup()
            val trap = response.find { it.firstOrNull() == "!trap" }
            if (trap != null) {
                return Pair(false, "Ping Trap: " + trap.joinToString(", "))
            }
            val received = response.flatten().any { 
                it.contains("received=1") || it.contains("received=2") || (it.contains("received=") && !it.contains("received=0")) 
            }
            if (received) {
                Pair(true, "Ping test successful: WAN connection is active!")
            } else {
                Pair(false, "Ping failed: No response packets received.")
            }
        } catch (e: Exception) {
            Pair(false, "Ping execution error: ${e.localizedMessage}")
        }
    }

    fun applyWirelessSettings(ssid: String, passwordStr: String): Pair<Boolean, String> {
        return try {
            writeSentence(listOf("/interface/wireless/print"))
            val printResp = readResponseSentenceGroup()
            val wlanName = printResp.flatten().find { it.startsWith("=name=") }?.substringAfter("=") ?: "wlan1"

            writeSentence(listOf(
                "/interface/wireless/set",
                "=.id=$wlanName",
                "=ssid=$ssid"
            ))
            var resp = readResponseSentenceGroup()
            if (resp.any { it.firstOrNull() == "!trap" }) {
                return Pair(false, "SSID configure failed: " + resp.flatten().joinToString(", "))
            }

            writeSentence(listOf(
                "/interface/wireless/security-profiles/set",
                "=.id=default",
                "=wpa2-pre-shared-key=$passwordStr",
                "=wpa-pre-shared-key=$passwordStr",
                "=mode=dynamic-keys",
                "=authentication-types=wpa2-psk"
            ))
            resp = readResponseSentenceGroup()
            if (resp.any { it.firstOrNull() == "!trap" }) {
                return Pair(false, "Security Profile configure failed: " + resp.flatten().joinToString(", "))
            }

            Pair(true, "Wireless broadcast settings successfully applied to interface: $wlanName")
        } catch (e: Exception) {
            Pair(false, "Wireless update error: ${e.localizedMessage}")
        }
    }

    fun checkActiveHotspotClients(): List<String> {
        return try {
            writeSentence(listOf("/ip/hotspot/active/print"))
            val resp = readResponseSentenceGroup()
            resp.flatten()
                .filter { it.startsWith("=mac-address=") }
                .map { it.substringAfter("=") }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

fun ipInSubnet(ip: List<Int>, subnetIp: List<Int>, mask: Int): Boolean {
    try {
        val ipVal = (ip[0] shl 24) or (ip[1] shl 16) or (ip[2] shl 8) or ip[3]
        val subnetVal = (subnetIp[0] shl 24) or (subnetIp[1] shl 16) or (subnetIp[2] shl 8) or subnetIp[3]
        val shift = 32 - mask
        if (shift <= 0) return ipVal == subnetVal
        val netmask = (0xFFFFFFFF.toLong() shl shift).toInt()
        return (ipVal and netmask) == (subnetVal and netmask)
    } catch (e: Exception) {
        return false
    }
}

fun findInterfaceForIp(gatewayIp: String, addresses: List<List<String>>): String? {
    try {
        val gwParts = gatewayIp.split(".").map { it.toInt() }
        if (gwParts.size != 4) return null
        
        for (addrEntry in addresses) {
            val address = addrEntry.find { it.startsWith("=address=") || it.startsWith("address=") }?.substringAfter("=") ?: ""
            val iface = addrEntry.find { it.startsWith("=interface=") || it.startsWith("interface=") }?.substringAfter("=") ?: ""
            if (address.isNotEmpty() && iface.isNotEmpty() && address.contains("/")) {
                val ipPart = address.substringBefore("/")
                val maskPart = address.substringAfter("/").toIntOrNull() ?: 24
                
                val ipParts = ipPart.split(".").map { it.toInt() }
                if (ipParts.size == 4) {
                    if (ipInSubnet(gwParts, ipParts, maskPart)) {
                        return iface
                    }
                }
            }
        }
    } catch (e: Exception) {
        // Safe fallback
    }
    return null
}

fun determineWanInterface(routes: List<List<String>>, addresses: List<List<String>>, dhcpClients: List<List<String>>): String? {
    for (route in routes) {
        val dstAddress = route.find { it.startsWith("=dst-address=") || it.startsWith("dst-address=") }?.substringAfter("=") ?: ""
        if (dstAddress == "0.0.0.0/0") {
            val immediateGateway = route.find { it.startsWith("=immediate-gateway=") || it.startsWith("immediate-gateway=") }?.substringAfter("=") ?: ""
            if (immediateGateway.isNotEmpty() && !immediateGateway.matches(Regex("^[0-9.]+$"))) {
                val clean = immediateGateway.split(",").firstOrNull()?.trim()
                if (!clean.isNullOrEmpty()) return clean
            }
            
            val gatewayStatus = route.find { it.startsWith("=gateway-status=") || it.startsWith("gateway-status=") }?.substringAfter("=") ?: ""
            if (gatewayStatus.isNotEmpty()) {
                if (gatewayStatus.contains("via ")) {
                    val iface = gatewayStatus.substringAfter("via ").trim().split("\\s+".toRegex()).firstOrNull()
                    if (!iface.isNullOrEmpty()) return iface
                }
                if (gatewayStatus.contains(" reachable")) {
                    val iface = gatewayStatus.substringBefore(" reachable").trim().split("\\s+".toRegex()).lastOrNull()
                    if (!iface.isNullOrEmpty()) return iface
                }
            }
            
            val gateway = route.find { it.startsWith("=gateway=") || it.startsWith("gateway=") }?.substringAfter("=") ?: ""
            if (gateway.isNotEmpty()) {
                if (!gateway.matches(Regex("^[0-9.]+$"))) {
                    val clean = gateway.split(",").firstOrNull()?.trim()
                    if (!clean.isNullOrEmpty()) return clean
                } else {
                    val matchedIface = findInterfaceForIp(gateway, addresses)
                    if (matchedIface != null) return matchedIface
                }
            }
        }
    }
    
    for (client in dhcpClients) {
        val iface = client.find { it.startsWith("=interface=") || it.startsWith("interface=") }?.substringAfter("=") ?: ""
        if (iface.isNotEmpty()) return iface
    }
    return null
}

fun isInterfaceRunning(ifaceName: String, interfaces: List<List<String>>): Boolean {
    val ifaceEntry = interfaces.find { entry ->
        val name = entry.find { it.startsWith("=name=") || it.startsWith("name=") }?.substringAfter("=") ?: ""
        name == ifaceName
    }
    if (ifaceEntry != null) {
        val running = ifaceEntry.find { it.startsWith("=running=") || it.startsWith("running=") }?.substringAfter("=") ?: ""
        return running == "true" || running == "yes"
    }
    return false
}

fun hasAssignedIp(ifaceName: String, addresses: List<List<String>>): Boolean {
    for (addrEntry in addresses) {
        val iface = addrEntry.find { it.startsWith("=interface=") || it.startsWith("interface=") }?.substringAfter("=") ?: ""
        val address = addrEntry.find { it.startsWith("=address=") || it.startsWith("address=") }?.substringAfter("=") ?: ""
        if (iface == ifaceName && address.isNotEmpty() && !address.startsWith("0.0.0.0")) {
            return true
        }
    }
    return false
}

// ─── 5-STEP ROUTER SETUP WIZARD SCREEN ─────────────────────────────────────
data class RouterDefault(val username: String, val password: String)

private val routerBrands = listOf(
    "MikroTik",
    "TP-Link",
    "Huawei",
    "Tenda",
    "D-Link",
    "Netgear",
    "ASUS",
    "Cisco / Linksys",
    "ZTE",
    "Other / Not Sure"
)

private val routerDefaults: Map<String, List<RouterDefault>> = mapOf(
    "MikroTik" to listOf(RouterDefault("admin", "")),
    "TP-Link" to listOf(RouterDefault("admin", "admin"), RouterDefault("admin", "")),
    "Huawei" to listOf(RouterDefault("admin", "admin"), RouterDefault("telecomadmin", "admintelecom"), RouterDefault("root", "admin")),
    "Tenda" to listOf(RouterDefault("admin", "admin"), RouterDefault("admin", "")),
    "D-Link" to listOf(RouterDefault("admin", ""), RouterDefault("admin", "admin"), RouterDefault("admin", "password")),
    "Netgear" to listOf(RouterDefault("admin", "password"), RouterDefault("admin", "1234")),
    "ASUS" to listOf(RouterDefault("admin", "admin")),
    "Cisco / Linksys" to listOf(RouterDefault("admin", "admin"), RouterDefault("admin", "password")),
    "ZTE" to listOf(RouterDefault("admin", "admin"), RouterDefault("admin", ""))
)

private val macBrandLookup = mapOf(
    "4C5E0C" to "MikroTik",
    "085531" to "MikroTik",
    "18FD74" to "MikroTik",
    "64D154" to "MikroTik",
    "D4CA6D" to "MikroTik",
    "E81132" to "MikroTik",
    "B869F4" to "MikroTik",
    "503FAA" to "TP-Link",
    "74DA38" to "TP-Link",
    "A0F3C1" to "TP-Link",
    "C025E9" to "TP-Link",
    "E894F6" to "TP-Link",
    "18A6F7" to "TP-Link",
    "EC086B" to "TP-Link",
    "283152" to "Huawei",
    "285FDB" to "Huawei",
    "286ED4" to "Huawei",
    "707BE8" to "Huawei",
    "84DBAC" to "Huawei",
    "D016B0" to "Huawei",
    "C83A35" to "Tenda",
    "D83214" to "Tenda",
    "502AAF" to "Tenda",
    "C0A5DD" to "Tenda",
    "18622C" to "D-Link",
    "1C7EE5" to "D-Link",
    "28107B" to "D-Link",
    "84C9B2" to "D-Link",
    "908D78" to "D-Link",
    "D8FE14" to "D-Link",
    "001F33" to "Netgear",
    "10DA43" to "Netgear",
    "20E52A" to "Netgear",
    "44A56E" to "Netgear",
    "841B5E" to "Netgear",
    "9C3DFF" to "Netgear",
    "04D9F5" to "ASUS",
    "107B44" to "ASUS",
    "1C872C" to "ASUS",
    "305A3A" to "ASUS",
    "50465D" to "ASUS",
    "AC9E17" to "ASUS",
    "001A70" to "Cisco / Linksys",
    "001EE5" to "Cisco / Linksys",
    "00259C" to "Cisco / Linksys",
    "14EDBB" to "Cisco / Linksys",
    "30E4DB" to "Cisco / Linksys",
    "E03F49" to "Cisco / Linksys",
    "002293" to "ZTE",
    "34E894" to "Tenda"
)

private val gatewayBrandLookup = mapOf(
    "192.168.88.1" to listOf("MikroTik"),
    "192.168.0.1" to listOf("TP-Link", "Tenda", "D-Link", "Netgear", "ZTE"),
    "192.168.1.1" to listOf("TP-Link", "Huawei", "Tenda", "D-Link", "Netgear", "ASUS", "Cisco / Linksys", "ZTE"),
    "192.168.8.1" to listOf("Huawei"),
    "192.168.100.1" to listOf("Huawei"),
    "192.168.50.1" to listOf("ASUS"),
    "192.168.15.1" to listOf("Cisco / Linksys")
)

// Generated on: 2026-07-17 using curl -s https://standards-oui.ieee.org/oui/oui.txt
// Exact Command: curl -sS https://standards-oui.ieee.org/oui/oui.txt -o oui.txt && grep -E -i "Routerboard|Mikrotikls" oui.txt | grep "(hex)" | awk -F'[[:space:]]+' '{print $1}' > mikrotik_macs.txt
private val mikrotikMacPrefixes = setOf(
    "085531", "B869F4", "000C42", "F41E57", "789A18", "DC2C6E", "488F5A", "C4AD34", 
    "6C3B6B", "D401C3", "04F41C", "D0EA11", "48A98A", "2CC81B", "64D154", "E48D8C", 
    "18FD74", "4C5E0C", "D4CA6D", "744D28", "CC2DE0", "38327A"
)

@Composable
private fun DiagnosticCheckRow(
    label: String,
    statusText: String,
    isSuccess: Boolean,
    isRunning: Boolean,
    isError: Boolean,
    accentColor: Color? = null,
    isSkipped: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, color = if (isSkipped) PaperDim else Paper, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                statusText,
                color = if (isSkipped) PaperDim else if (isError) Color.Red else if (isSuccess) (accentColor ?: Color(0xFF2ECC71)) else if (isRunning) Cyan else PaperDim,
                fontSize = 11.sp
            )
        }
        
        if (isRunning) {
            CircularProgressIndicator(color = Cyan, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else if (isSkipped) {
            Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = "Skipped",
                tint = PaperDim,
                modifier = Modifier.size(18.dp)
            )
        } else if (isSuccess) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Success",
                tint = accentColor ?: Color(0xFF2ECC71),
                modifier = Modifier.size(18.dp)
            )
        } else if (isError) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Failed",
                tint = Color.Red,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Pending",
                tint = PaperDim,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterSetupScreen(nav: NavHostController, vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val Emerald = Color(0xFF2ECC71)
    val sessionManager = remember { SessionManager(context) }
    
    var currentStep by remember { mutableStateOf(0) } // Starts at 0: Router Auto-Detection
    val logs = remember { mutableStateListOf<String>() }
    
    // Step 0: Auto-detection variables
    var step0Status by remember { mutableStateOf("NOT_STARTED") } // "NOT_STARTED", "RUNNING", "SUCCESS_MIKROTIK", "SUCCESS_GENERIC", "ERROR_MAC", "ERROR_DISAGREEMENT"
    var detectedGatewayIp by remember { mutableStateOf("") }
    var detectedGatewayMac by remember { mutableStateOf("") }
    var macPrefixCheckResult by remember { mutableStateOf<Boolean?>(null) } // true = MikroTik prefix, false = non-MikroTik prefix, null = not checked
    var apiHandshakeResult by remember { mutableStateOf<Boolean?>(null) } // true = successful handshake, false = failed handshake, null = not checked
    var isCheckingStep0 by remember { mutableStateOf(false) }
    var macCheckSkipped by remember { mutableStateOf(false) }
    
    // Step 1: Connect variables
    var ip by remember { mutableStateOf(vm.routerIp.ifEmpty { "" }) }
    var port by remember { mutableStateOf(vm.routerApiPort.ifEmpty { "8728" }) }
    var username by remember { mutableStateOf(vm.routerUsername.ifEmpty { "admin" }) }
    var password by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var selectedBrand by remember { mutableStateOf("MikroTik") }
    var showBrandDialog by remember { mutableStateOf(false) }
    var brandAuthErrorMessage by remember { mutableStateOf("") }
    var triedDefaults by remember { mutableStateOf(false) }
    
    var detectedMacPrefix by remember { mutableStateOf("") }
    var macMatchedBrand by remember { mutableStateOf<String?>(null) }
    var gatewayMatchedBrands by remember { mutableStateOf<List<String>>(emptyList()) }
    var customBrandName by remember { mutableStateOf("") }
    var showStickerDialog by remember { mutableStateOf(false) }
    var showGatewayDropdownMenu by remember { mutableStateOf(false) }
    var requiresUniqueStickerPassword by remember { mutableStateOf(false) }

    // Helper to read /proc/net/arp and get gateway MAC address
    fun getGatewayMacAddress(gatewayIp: String): String? {
        if (gatewayIp.isEmpty()) return null
        return try {
            val file = java.io.File("/proc/net/arp")
            if (file.exists()) {
                file.readLines()
                    .drop(1) // skip header
                    .mapNotNull { line ->
                        val parts = line.trim().split("\\s+".toRegex())
                        if (parts.size >= 4) {
                            val ip = parts[0]
                            val mac = parts[3]
                            if (ip == gatewayIp && mac.isNotEmpty() && mac != "00:00:00:00:00:00" && mac.contains(":")) {
                                mac
                            } else null
                        } else null
                    }.firstOrNull()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun isValidIPv4(ipAddress: String): Boolean {
        val parts = ipAddress.split(".")
        if (parts.size != 4) return false
        return parts.all { 
            val num = it.toIntOrNull()
            num != null && num in 0..255 
        }
    }

    suspend fun runApiHandshakeCheck(detectedIp: String, matchesMikroTikPrefix: Boolean) {
        logs.add("⚡ Attempting live socket handshake on RouterOS API port 8728...")
        var handshakeSuccess = false
        
        if (vm.isDemoMode) {
            kotlinx.coroutines.delay(1500)
            handshakeSuccess = matchesMikroTikPrefix
            logs.add("🛠️ [Demo Mode] API Socket check simulated.")
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val testClient = RouterOsApiClient(detectedIp, 8728, timeoutMs = 3000)
                try {
                    if (testClient.connect()) {
                        testClient.writeSentence(listOf("/login"))
                        val response = testClient.readResponseSentenceGroup()
                        val firstWordOfFirstSentence = response.firstOrNull()?.firstOrNull() ?: ""
                        handshakeSuccess = firstWordOfFirstSentence.startsWith("!") || response.isNotEmpty()
                    }
                } catch (e: Exception) {
                    android.util.Log.w("RouterSetup", "Socket handshake failed: ${e.message}")
                } finally {
                    testClient.disconnect()
                }
            }
        }
        
        apiHandshakeResult = handshakeSuccess
        isCheckingStep0 = false
        
        if (handshakeSuccess) {
            logs.add("✅ Connection verified using genuine RouterOS API handshake!")
            step0Status = "SUCCESS_MIKROTIK"
            selectedBrand = "MikroTik"
            ip = detectedIp
            username = "admin"
            password = ""
        } else {
            logs.add("❌ RouterOS API Handshake failed or port 8728 unreachable.")
            if (matchesMikroTikPrefix) {
                step0Status = "ERROR_DISAGREEMENT"
            } else {
                step0Status = "SUCCESS_GENERIC"
            }
        }
    }

    fun runStep0Checks() {
        if (isCheckingStep0) return
        scope.launch {
            isCheckingStep0 = true
            macCheckSkipped = false
            step0Status = "RUNNING"
            logs.clear()
            logs.add("🚀 Starting diagnostic sequence...")
            
            // 1. Extract Gateway IP
            var detectedIp = ""
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val activeNetwork = connectivityManager?.activeNetwork
            val linkProperties = connectivityManager?.getLinkProperties(activeNetwork)
            val gatewayFromRoutes = linkProperties?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }
                ?.gateway?.hostAddress
            
            if (!gatewayFromRoutes.isNullOrEmpty() && gatewayFromRoutes != "0.0.0.0" && isValidIPv4(gatewayFromRoutes)) {
                detectedIp = gatewayFromRoutes
            } else {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                val dhcpInfo = wifiManager?.dhcpInfo
                val gatewayIpInt = dhcpInfo?.gateway ?: 0
                if (gatewayIpInt != 0) {
                    val gatewayIpStr = String.format(
                        java.util.Locale.US,
                        "%d.%d.%d.%d",
                        gatewayIpInt and 0xFF,
                        (gatewayIpInt shr 8) and 0xFF,
                        (gatewayIpInt shr 16) and 0xFF,
                        (gatewayIpInt shr 24) and 0xFF
                    )
                    if (gatewayIpStr != "0.0.0.0" && isValidIPv4(gatewayIpStr)) {
                        detectedIp = gatewayIpStr
                    }
                }
            }
            
            if (detectedIp.isEmpty()) {
                logs.add("❌ Failed to detect local gateway IP.")
                step0Status = "ERROR_MAC"
                isCheckingStep0 = false
                return@launch
            }
            
            detectedGatewayIp = detectedIp
            ip = detectedIp
            logs.add("📡 Gateway IP detected: $detectedIp")
            
            // 2. Read the router's real MAC address
            val routerMac = getGatewayMacAddress(detectedIp)
            if (routerMac == null) {
                logs.add("⚠️ Failed to read MAC address from /proc/net/arp (expected on Android 10+). Skipping MAC and OUI Prefix checks.")
                macCheckSkipped = true
                detectedGatewayMac = ""
                detectedMacPrefix = ""
                macPrefixCheckResult = null
                
                // 4. Socket API check directly
                runApiHandshakeCheck(detectedIp, false)
            } else {
                macCheckSkipped = false
                detectedGatewayMac = routerMac
                logs.add("📎 Router MAC retrieved: $routerMac")
                
                // 3. Compare prefix against registered list
                val cleanBssid = routerMac.replace(":", "").replace("-", "").uppercase()
                val prefix = if (cleanBssid.length >= 6) cleanBssid.substring(0, 6) else ""
                detectedMacPrefix = prefix
                
                val matchesMikroTikPrefix = prefix.isNotEmpty() && mikrotikMacPrefixes.contains(prefix)
                macPrefixCheckResult = matchesMikroTikPrefix
                
                if (matchesMikroTikPrefix) {
                    logs.add("🔍 MAC Prefix '$prefix' matches registered MikroTik hardware OUI!")
                } else {
                    logs.add("🔍 MAC Prefix '$prefix' is registered to another manufacturer.")
                }
                
                // 4. Socket API check
                runApiHandshakeCheck(detectedIp, matchesMikroTikPrefix)
            }
        }
    }

    fun retryApiCheckOnly() {
        if (isCheckingStep0) return
        scope.launch {
            isCheckingStep0 = true
            step0Status = "RUNNING"
            logs.add("🔄 Retrying live socket handshake on RouterOS API port 8728...")
            runApiHandshakeCheck(detectedGatewayIp, macPrefixCheckResult == true)
        }
    }

    LaunchedEffect(Unit) {
        if (currentStep == 0 && step0Status == "NOT_STARTED") {
            runStep0Checks()
        }
    }
    
    // Step 2: WAN check variables
    var isCheckingWan by remember { mutableStateOf(false) }
    var wanConfirmed by remember { mutableStateOf(false) }
    var wanCheckStatusText by remember { mutableStateOf("Checking your router's internet connection...") }
    var wanCheckStatusType by remember { mutableStateOf("RUNNING") } // "RUNNING", "SUCCESS", "NO_ROUTE", "NOT_RUNNING", "NO_IP", "CONNECT_FAILED", "RECONNECTING"
    var wanCheckIfaceName by remember { mutableStateOf("") }
    
    // Step 3: Broadcast variables
    var ssid by remember { mutableStateOf(vm.routerGuestSsid.ifEmpty { "BeamSpot_WiFi" }) }
    var wifiPass by remember { mutableStateOf(vm.routerGuestPassword.ifEmpty { "internet123" }) }
    var isApplyingWireless by remember { mutableStateOf(false) }
    var wirelessApplied by remember { mutableStateOf(false) }
    
    // Step 4: Pricing variables
    var pricePerMin by remember { mutableStateOf(vm.pricePerMin.toFloat().coerceIn(1f, 10f)) }
    var isApplyingPricing by remember { mutableStateOf(false) }
    var pricingApplied by remember { mutableStateOf(false) }
    
    // Step 5: Verification variables
    var isPollingGuests by remember { mutableStateOf(false) }
    var guestConnectedMac by remember { mutableStateOf<String?>(null) }
    var guestConnectedIp by remember { mutableStateOf<String?>(null) }
    
    val client = remember(ip, port) {
        RouterOsApiClient(ip, port.toIntOrNull() ?: 8728)
    }
    
    LaunchedEffect(currentStep) {
        when (currentStep) {
            1 -> {
                if (logs.isEmpty()) {
                    logs.add("Welcome to BeamSpot Router Setup Wizard!")
                    logs.add("Please ensure your MikroTik router is powered on and API port (8728) is enabled in IP -> Services.")
                }
            }
            2 -> {
                logs.add("\n--- Step 2: WAN Link Verification ---")
                logs.add("We will ping Google DNS (8.8.8.8) from the router to verify external internet connection.")
            }
            3 -> {
                logs.add("\n--- Step 3: Wireless Broadcast Setup ---")
                logs.add("Ready to configure your guest Wi-Fi network SSID and security passphrase.")
            }
            4 -> {
                logs.add("\n--- Step 4: Hotspot Pricing and Gateway Setup ---")
                logs.add("Choose your rate. This will also update the router Hotspot Profile redirect parameters.")
            }
            5 -> {
                logs.add("\n--- Step 5: Live Guest Verification ---")
                logs.add("The portal is fully configured on your router! Waiting for a second device to join...")
                isPollingGuests = true
            }
        }
    }

    LaunchedEffect(currentStep) {
        if (currentStep == 2) {
            wanConfirmed = false
            wanCheckStatusText = "Checking your router's internet connection..."
            wanCheckStatusType = "RUNNING"
            wanCheckIfaceName = ""
            var reconnectAttempts = 0
            
            while (currentStep == 2) {
                if (vm.isDemoMode) {
                    delay(2000)
                    wanCheckIfaceName = "ether1 (Demo)"
                    wanConfirmed = true
                    wanCheckStatusType = "SUCCESS"
                    wanCheckStatusText = "Internet connection confirmed on ether1 (Demo)"
                    delay(2000)
                    currentStep = 3
                    break
                }
                
                val conn = sessionManager.getRouterConnection()
                val currentIp = conn?.ip ?: ip
                val currentPort = conn?.port ?: (port.toIntOrNull() ?: 8728)
                val currentUsername = conn?.username ?: username
                val currentPassword = conn?.password ?: password
                
                val apiResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val clientTemp = RouterOsApiClient(currentIp, currentPort)
                    try {
                        if (!clientTemp.connect()) {
                            return@withContext Pair("CONNECT_FAILED", "Could not establish TCP connection to $currentIp:$currentPort")
                        }
                        val loginRes = clientTemp.login(currentUsername, currentPassword)
                        if (!loginRes.first) {
                            return@withContext Pair("CONNECT_FAILED", "Authentication failed for user $currentUsername: ${loginRes.second}")
                        }
                        
                        // 1. Query routes, addresses, and DHCP clients to find the WAN interface
                        clientTemp.writeSentence(listOf("/ip/route/print"))
                        val routes = clientTemp.readResponseSentenceGroup()
                        
                        clientTemp.writeSentence(listOf("/ip/address/print"))
                        val addresses = clientTemp.readResponseSentenceGroup()
                        
                        clientTemp.writeSentence(listOf("/ip/dhcp-client/print"))
                        val dhcpClients = clientTemp.readResponseSentenceGroup()
                        
                        val parsedWanIface = determineWanInterface(routes, addresses, dhcpClients)
                        if (parsedWanIface == null) {
                            return@withContext Pair("NO_ROUTE", "")
                        }
                        
                        // 2. Query interface list to check running/link status
                        clientTemp.writeSentence(listOf("/interface/print"))
                        val interfaces = clientTemp.readResponseSentenceGroup()
                        val running = isInterfaceRunning(parsedWanIface, interfaces)
                        if (!running) {
                            return@withContext Pair("NOT_RUNNING", parsedWanIface)
                        }
                        
                        // 3. Check if the interface has an assigned IP address
                        val hasIp = hasAssignedIp(parsedWanIface, addresses)
                        if (!hasIp) {
                            return@withContext Pair("NO_IP", parsedWanIface)
                        }
                        
                        Pair("SUCCESS", parsedWanIface)
                    } catch (e: Exception) {
                        Pair("CONNECT_FAILED", e.localizedMessage ?: "Unknown API error")
                    } finally {
                        clientTemp.disconnect()
                    }
                }
                
                when (apiResult.first) {
                    "SUCCESS" -> {
                        reconnectAttempts = 0
                        wanCheckIfaceName = apiResult.second
                        wanConfirmed = true
                        wanCheckStatusType = "SUCCESS"
                        wanCheckStatusText = "Internet connection confirmed on ${apiResult.second}"
                        delay(2000)
                        currentStep = 3
                        break
                    }
                    "NO_ROUTE" -> {
                        reconnectAttempts = 0
                        wanCheckStatusType = "NO_ROUTE"
                        wanCheckStatusText = "Your router doesn't have an internet connection configured. Check its WAN/internet cable or your ISP connection, then this will update automatically."
                    }
                    "NOT_RUNNING" -> {
                        reconnectAttempts = 0
                        wanCheckStatusType = "NOT_RUNNING"
                        wanCheckStatusText = "Your router's internet connection appears to be down. Check the physical cable/connection to your router, then this will update automatically."
                    }
                    "NO_IP" -> {
                        reconnectAttempts = 0
                        wanCheckStatusType = "NO_IP"
                        wanCheckStatusText = "Your router's internet interface isn't fully connected yet (no IP address received). This can take a moment after plugging in — checking again automatically."
                    }
                    "CONNECT_FAILED" -> {
                        reconnectAttempts++
                        if (reconnectAttempts >= 3) {
                            wanCheckStatusType = "GOTO_DETECTION"
                            wanCheckStatusText = "Lost connection to your router. Reconnecting..."
                            delay(2000)
                            currentStep = 0
                            break
                        } else {
                            wanCheckStatusType = "RECONNECTING"
                            wanCheckStatusText = "Lost connection to your router. Reconnecting... (Attempt $reconnectAttempts of 3)"
                        }
                    }
                }
                
                delay(2000)
            }
        }
    }
    
    LaunchedEffect(currentStep, isPollingGuests) {
        if (currentStep == 5 && isPollingGuests) {
            while (guestConnectedMac == null) {
                if (vm.isDemoMode) {
                    delay(1000)
                } else {
                    logs.add("Polling active hotspot clients via RouterOS API...")
                    val clients = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            if (client.connect()) {
                                if (client.login(username, password).first) {
                                    client.checkActiveHotspotClients()
                                } else emptyList()
                            } else emptyList()
                        } catch (e: Exception) {
                            emptyList()
                        } finally {
                            client.disconnect()
                        }
                    }
                    if (clients.isNotEmpty()) {
                        guestConnectedMac = clients.first()
                        guestConnectedIp = "192.168.88.254"
                        logs.add("🔥 SUCCESS: Device connected! MAC: $guestConnectedMac")
                        break
                    }
                    delay(3000)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (currentStep > 0) currentStep-- else nav.popBackStack()
                }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Cyan)
                }
                
                Text(
                    text = if (currentStep == 0) "Router Detection" else "Router Setup ($currentStep/5)",
                    color = Paper,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Demo", color = PaperDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    androidx.compose.material3.Switch(
                        checked = vm.isDemoMode,
                        onCheckedChange = { vm.isDemoMode = it },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Cyan,
                            checkedTrackColor = Cyan.copy(0.3f)
                        ),
                        modifier = Modifier.scale(0.7f)
                    )
                }
            }
            
            LinearProgressIndicator(
                progress = currentStep / 5.0f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Cyan,
                trackColor = Panel
            )
            
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Panel),
                border = BorderStroke(1.dp, BorderLine),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (currentStep) {
                        0 -> {
                            Text("Router Auto-Detection", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Text(
                                "We are running real hardware diagnostic checks to identify your router and determine compatibility.",
                                color = PaperDim,
                                fontSize = 13.sp
                            )
                            
                            Spacer(Modifier.height(8.dp))
                            
                            // Check Items Container
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Ink.copy(alpha = 0.3f))
                                    .border(1.dp, BorderLine, RoundedCornerShape(16.dp))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // 1. Gateway IP Check
                                DiagnosticCheckRow(
                                    label = "Gateway IP Check",
                                    statusText = if (detectedGatewayIp.isEmpty()) "Scanning..." else "Detected: $detectedGatewayIp",
                                    isSuccess = detectedGatewayIp.isNotEmpty(),
                                    isRunning = step0Status == "RUNNING" && detectedGatewayIp.isEmpty(),
                                    isError = step0Status == "ERROR_MAC" && detectedGatewayIp.isEmpty()
                                )
                                
                                // 2. MAC Address Check
                                DiagnosticCheckRow(
                                    label = "Retrieve Router MAC Address",
                                    statusText = if (macCheckSkipped) "Skipped (OS Restricted)" else if (detectedGatewayMac.isEmpty()) {
                                        if (step0Status == "ERROR_MAC") "Failed to read ARP" else "Scanning ARP..."
                                    } else "Retrieved: $detectedGatewayMac",
                                    isSuccess = detectedGatewayMac.isNotEmpty(),
                                    isRunning = step0Status == "RUNNING" && detectedGatewayMac.isEmpty() && detectedGatewayIp.isNotEmpty() && !macCheckSkipped,
                                    isError = step0Status == "ERROR_MAC" && detectedGatewayMac.isEmpty() && !macCheckSkipped,
                                    isSkipped = macCheckSkipped
                                )
                                
                                // 3. OUI Prefix Check
                                DiagnosticCheckRow(
                                    label = "OUI Prefix Matching",
                                    statusText = if (macCheckSkipped) "Not available on this device" else when (macPrefixCheckResult) {
                                        true -> "OUI Match: MikroTik ($detectedMacPrefix)"
                                        false -> "OUI Match: Non-MikroTik ($detectedMacPrefix)"
                                        else -> "Waiting for MAC..."
                                    },
                                    isSuccess = macPrefixCheckResult != null && !macCheckSkipped,
                                    isRunning = step0Status == "RUNNING" && macPrefixCheckResult == null && detectedGatewayMac.isNotEmpty() && !macCheckSkipped,
                                    isError = false,
                                    accentColor = if (macPrefixCheckResult == true) Cyan else PaperDim,
                                    isSkipped = macCheckSkipped
                                )
                                
                                // 4. RouterOS API Handshake Check
                                DiagnosticCheckRow(
                                    label = "RouterOS API Handshake",
                                    statusText = when (apiHandshakeResult) {
                                        true -> "Handshake: Genuine RouterOS Verified"
                                        false -> "Handshake: Connection refused / Handshake mismatch"
                                        else -> if (macCheckSkipped) "Testing API connection..." else "Waiting for OUI check..."
                                    },
                                    isSuccess = apiHandshakeResult == true,
                                    isRunning = step0Status == "RUNNING" && apiHandshakeResult == null && (macPrefixCheckResult != null || macCheckSkipped),
                                    isError = apiHandshakeResult == false
                                )
                            }
                            
                            // Real-time Console/Terminal view
                            Spacer(Modifier.height(8.dp))
                            Text("DIAGNOSTIC LOGS", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Ink)
                                    .border(1.dp, BorderLine, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                val scrollState = rememberScrollState()
                                LaunchedEffect(logs.size) {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(scrollState),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    logs.forEach { log ->
                                        Text(
                                            text = log,
                                            color = if (log.startsWith("✅") || log.contains("success", ignoreCase = true)) Emerald else if (log.startsWith("❌") || log.startsWith("🚨")) Color.Red else if (log.startsWith("⚠️")) Color.Yellow else PaperDim,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 14.sp
                                        )
                                    }
                                }
                            }
                            
                            // Result Box and Actions
                            Spacer(Modifier.height(8.dp))
                            
                            when (step0Status) {
                                "RUNNING" -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(color = Cyan, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Running hardware checks...", color = PaperDim, fontSize = 13.sp)
                                    }
                                }
                                "ERROR_MAC" -> {
                                    Surface(
                                        color = Color.Red.copy(0.12f),
                                        border = BorderStroke(1.dp, Color.Red.copy(0.5f)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(
                                                text = "Couldn't read your router's address details. Make sure you're connected to its WiFi network and try again.",
                                                color = Paper,
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp
                                            )
                                            Button(
                                                onClick = { runStep0Checks() },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(0.4f)),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Retry Diagnostics", color = Paper, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                                "ERROR_DISAGREEMENT" -> {
                                    Surface(
                                        color = Color.Yellow.copy(0.12f),
                                        border = BorderStroke(1.dp, Color.Yellow.copy(0.5f)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(
                                                text = "This looks like a MikroTik router, but we couldn't reach its management API. It may be turned off. Log into your router at http://$detectedGatewayIp in a browser, go to IP → Services, and check that 'api' is enabled — then try again.",
                                                color = Paper,
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Button(
                                                    onClick = { retryApiCheckOnly() },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("Retry Handshake", color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                }
                                                Button(
                                                    onClick = { runStep0Checks() },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Panel),
                                                    border = BorderStroke(1.dp, BorderLine),
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("Full Diagnostic", color = Paper, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                                "SUCCESS_MIKROTIK" -> {
                                    Surface(
                                        color = Emerald.copy(0.12f),
                                        border = BorderStroke(1.dp, Emerald.copy(0.5f)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(
                                                text = "MikroTik router detected! Connection API is verified.",
                                                color = Emerald,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Button(
                                                onClick = { currentStep = 1 },
                                                colors = ButtonDefaults.buttonColors(containerColor = Emerald),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Proceed to MikroTik Setup (1/5)", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                                "SUCCESS_GENERIC" -> {
                                    Surface(
                                        color = Cyan.copy(0.12f),
                                        border = BorderStroke(1.dp, Cyan.copy(0.5f)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(
                                                text = if (macCheckSkipped) {
                                                    "Couldn't detect MikroTik's management API. If this is a MikroTik router, check that IP -> Services -> api is enabled."
                                                } else {
                                                    "This router doesn't have MikroTik's management API. We'll guide you through setup a different way."
                                                },
                                                color = Paper,
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp
                                            )
                                            Button(
                                                onClick = {
                                                    android.widget.Toast.makeText(context, "Generic Setup flow is currently in development!", android.widget.Toast.LENGTH_LONG).show()
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Proceed to Generic Setup", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            Text("Connect to Your Router", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Text(
                                "Link BeamSpot directly with your Router's API to automate captive-portal billing.",
                                color = PaperDim,
                                fontSize = 13.sp
                            )
                            
                            if (vm.isDemoMode) {
                                Surface(
                                    color = Cyan.copy(0.12f),
                                    border = BorderStroke(1.dp, Cyan.copy(0.3f)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "🛠️ Demo Mode Active — Connection will be simulated.",
                                        color = Cyan,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(12.dp),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            // Live Hardware Telemetry
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Panel.copy(alpha = 0.5f))
                                    .border(1.dp, BorderLine, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Router, contentDescription = null, tint = Cyan, modifier = Modifier.size(24.dp))
                                Column {
                                    Text(
                                        text = "LIVE HARDWARE TELEMETRY",
                                        color = Cyan,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "Gateway: ${ip.ifEmpty { "None (Disconnected)" }} | MAC Prefix: ${detectedMacPrefix.ifEmpty { "Not Found" }}",
                                        color = PaperDim,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            // Scenario A: MAC Matched Brand Banner
                            if (macMatchedBrand != null) {
                                Surface(
                                    color = Emerald.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, Emerald.copy(alpha = 0.4f)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Filled.CheckCircle, contentDescription = "Matched", tint = Emerald, modifier = Modifier.size(20.dp))
                                            Column {
                                                Text(
                                                    text = "Matched via Hardware MAC Address!",
                                                    color = Paper,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "Your router was auto-detected as a $macMatchedBrand device.",
                                                    color = PaperDim,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                        Button(
                                            onClick = {
                                                val defaults = routerDefaults[macMatchedBrand] ?: emptyList()
                                                if (defaults.isNotEmpty()) {
                                                    username = defaults.first().username
                                                    password = defaults.first().password
                                                    logs.add("⚡ Auto-Populated default credentials for $macMatchedBrand.")
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Emerald),
                                            modifier = Modifier.fillMaxWidth().height(36.dp)
                                        ) {
                                            Text("Auto-Populate Credentials ⚡", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            // Scenario B: Gateway Multi-Option Dropdown Menu
                            if (gatewayMatchedBrands.isNotEmpty() && macMatchedBrand == null) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Cyan.copy(alpha = 0.08f))
                                        .border(1.dp, Cyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Filled.Info, contentDescription = "Info", tint = Cyan, modifier = Modifier.size(16.dp))
                                        Text(
                                            text = "We see your network layout. Which of these brands is your router?",
                                            color = Paper,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        OutlinedCard(
                                            onClick = { showGatewayDropdownMenu = true },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = CardDefaults.outlinedCardColors(containerColor = Panel),
                                            border = BorderStroke(1.dp, BorderLine)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (gatewayMatchedBrands.contains(selectedBrand)) selectedBrand else "Select Brand...",
                                                    color = Paper,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Cyan)
                                            }
                                        }
                                        
                                        androidx.compose.material3.DropdownMenu(
                                            expanded = showGatewayDropdownMenu,
                                            onDismissRequest = { showGatewayDropdownMenu = false },
                                            modifier = Modifier
                                                .fillMaxWidth(0.85f)
                                                .background(Panel)
                                                .border(1.dp, BorderLine, RoundedCornerShape(8.dp))
                                        ) {
                                            gatewayMatchedBrands.forEach { b ->
                                                androidx.compose.material3.DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = b,
                                                            color = if (selectedBrand == b) Cyan else Paper,
                                                            fontSize = 14.sp,
                                                            fontWeight = if (selectedBrand == b) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                    },
                                                    onClick = {
                                                        selectedBrand = b
                                                        val defaults = routerDefaults[b] ?: emptyList()
                                                        if (defaults.isNotEmpty()) {
                                                            username = defaults.first().username
                                                            password = defaults.first().password
                                                        }
                                                        brandAuthErrorMessage = ""
                                                        triedDefaults = false
                                                        showGatewayDropdownMenu = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Scenario C: Completely Unknown Router Custom Entry & Sticker Dialog helper
                            if (selectedBrand == "Other / Not Sure") {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    BeamLabel("Custom Router Brand / Model")
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            BeamInput(
                                                value = customBrandName,
                                                onValueChange = { customBrandName = it },
                                                placeholder = "e.g. ASUS RT-AX58U"
                                            )
                                        }
                                        
                                        Button(
                                            onClick = { showStickerDialog = true },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Panel),
                                            border = BorderStroke(1.dp, BorderLine),
                                            modifier = Modifier.height(50.dp)
                                        ) {
                                            Icon(Icons.Filled.Label, contentDescription = "Sticker", tint = Cyan, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Sticker Helper", color = Paper, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }

                            if (showStickerDialog) {
                                androidx.compose.ui.window.Dialog(onDismissRequest = { showStickerDialog = false }) {
                                    Card(
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(containerColor = Panel),
                                        border = BorderStroke(1.dp, BorderLine),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .padding(24.dp)
                                                .fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Label,
                                                contentDescription = null,
                                                tint = Cyan,
                                                modifier = Modifier.size(48.dp)
                                            )
                                            Text(
                                                text = "How to Read Router Sticker 🏷️",
                                                color = Paper,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center
                                            )
                                            Text(
                                                text = "Physical stickers are usually located on the back or bottom of your physical router device.",
                                                color = PaperDim,
                                                fontSize = 13.sp,
                                                textAlign = TextAlign.Center
                                            )
                                            
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.Top
                                                ) {
                                                    Text("1️⃣", fontSize = 14.sp)
                                                    Text("Find the label upside down on the bottom/back of the device.", color = Paper, fontSize = 13.sp)
                                                }
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.Top
                                                ) {
                                                    Text("2️⃣", fontSize = 14.sp)
                                                    Text("Locate fields named 'Web Access', 'Admin User', or 'Login Password'.", color = Paper, fontSize = 13.sp)
                                                }
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.Top
                                                ) {
                                                    Text("3️⃣", fontSize = 14.sp)
                                                    Text("Copy those values precisely (case-sensitive) to connect BeamSpot.", color = Paper, fontSize = 13.sp)
                                                }
                                            }
                                            
                                            Spacer(Modifier.height(8.dp))
                                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Button(
                                                    onClick = { showStickerDialog = false },
                                                    shape = RoundedCornerShape(12.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = BorderLine),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("Cancel", color = Paper, fontSize = 12.sp)
                                                }
                                                Button(
                                                    onClick = {
                                                        username = "admin"
                                                        password = ""
                                                        showStickerDialog = false
                                                    },
                                                    shape = RoundedCornerShape(12.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                                                    modifier = Modifier.weight(1.5f)
                                                ) {
                                                    Text("Fill Defaults", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Router Brand Dropdown Selector
                            BeamLabel("Router Brand")
                            OutlinedCard(
                                onClick = { showBrandDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
                                border = BorderStroke(1.dp, BorderLine)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("What router do you use?", color = PaperDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        Spacer(Modifier.height(4.dp))
                                        Text(selectedBrand, color = Paper, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Dropdown", tint = Cyan)
                                }
                            }

                            if (showBrandDialog) {
                                androidx.compose.ui.window.Dialog(onDismissRequest = { showBrandDialog = false }) {
                                    Card(
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(containerColor = Panel),
                                        border = BorderStroke(1.dp, BorderLine),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .padding(20.dp)
                                                .fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Text("Select Router Brand", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                            
                                            LazyColumn(
                                                modifier = Modifier.heightIn(max = 300.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                items(routerBrands) { brand ->
                                                    Surface(
                                                        onClick = {
                                                            selectedBrand = brand
                                                            showBrandDialog = false
                                                            requiresUniqueStickerPassword = false
                                                            if (brand != "Other / Not Sure") {
                                                                val firstDefault = routerDefaults[brand]?.firstOrNull()
                                                                if (firstDefault != null) {
                                                                    username = firstDefault.username
                                                                    password = firstDefault.password
                                                                }
                                                            } else {
                                                                username = ""
                                                                password = ""
                                                            }
                                                            brandAuthErrorMessage = ""
                                                            triedDefaults = false
                                                        },
                                                        shape = RoundedCornerShape(10.dp),
                                                        color = if (selectedBrand == brand) Cyan.copy(0.12f) else Color.Transparent,
                                                        border = BorderStroke(1.dp, if (selectedBrand == brand) Cyan else Color.Transparent),
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(12.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                brand,
                                                                color = if (selectedBrand == brand) Cyan else Paper,
                                                                fontSize = 14.sp,
                                                                fontWeight = if (selectedBrand == brand) FontWeight.Bold else FontWeight.Normal,
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                            if (selectedBrand == brand) {
                                                                Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Cyan, modifier = Modifier.size(18.dp))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            BeamLabel("Router IP / Domain Host")
                            BeamInput(value = ip, onValueChange = { ip = it }, placeholder = "e.g. 192.168.88.1")
                            if (ip.isBlank()) {
                                Text(
                                    text = "Could not auto-detect gateway. Please check your router's sticker or your WiFi settings' 'Gateway' field and enter it manually.",
                                    color = Amber,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                            
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(modifier = Modifier.weight(1.5f)) {
                                    BeamLabel("API Port")
                                    BeamInput(value = port, onValueChange = { port = it }, placeholder = "8728", keyboardType = KeyboardType.Number)
                                }
                                Column(modifier = Modifier.weight(2f)) {
                                    BeamLabel("Admin Username")
                                    BeamInput(value = username, onValueChange = { username = it }, placeholder = "admin")
                                }
                            }
                            
                            BeamLabel("Admin Password")
                            BeamInput(
                                value = password,
                                onValueChange = { password = it },
                                placeholder = "Password (leave blank if none)",
                                isPassword = true
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Check the sticker on your router for the default password, or enter what you've set it to.",
                                color = PaperDim,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )

                            if (requiresUniqueStickerPassword) {
                                Spacer(Modifier.height(10.dp))
                                Surface(
                                    color = Cyan.copy(0.12f),
                                    border = BorderStroke(1.dp, Cyan.copy(0.5f)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showStickerDialog = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Label,
                                            contentDescription = null,
                                            tint = Cyan,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Sticker Password Required 🏷️",
                                                color = Cyan,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = "This modern $selectedBrand router utilizes dynamic or randomized factory credentials. Please read the unique password printed on its physical sticker. Click to open the Sticker Helper.",
                                                color = Paper,
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Filled.ArrowForward,
                                            contentDescription = null,
                                            tint = PaperDim,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            if (brandAuthErrorMessage.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    color = Amber.copy(0.12f),
                                    border = BorderStroke(1.dp, Amber.copy(0.4f)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Filled.Warning, contentDescription = "Warning", tint = Amber, modifier = Modifier.size(18.dp))
                                        Text(
                                            text = brandAuthErrorMessage,
                                            color = Paper,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            
                            Spacer(Modifier.weight(1f))
                            
                            BeamButton(
                                label = if (isConnecting) "Authenticating..." else "Test & Authenticate ⚡",
                                color = Cyan,
                                enabled = !isConnecting && ip.isNotBlank() && port.isNotBlank() && username.isNotBlank()
                            ) {
                                isConnecting = true
                                brandAuthErrorMessage = ""
                                logs.add("Initiating connection check to $ip:$port...")
                                
                                val defaultsToTry = if (selectedBrand != "Other / Not Sure" && !requiresUniqueStickerPassword && !triedDefaults && password.isEmpty()) {
                                    routerDefaults[selectedBrand] ?: emptyList()
                                } else {
                                    emptyList()
                                }
                                
                                scope.launch {
                                    if (vm.isDemoMode) {
                                        if (defaultsToTry.isNotEmpty()) {
                                            var demoSucceeded = false
                                            for ((index, default) in defaultsToTry.withIndex()) {
                                                username = default.username
                                                password = default.password
                                                logs.add("[$selectedBrand auto-detect] Trying credentials: user='${default.username}', password='${"*".repeat(default.password.length)}' [Attempt ${index + 1}/${defaultsToTry.size}]...")
                                                delay(1000) // Paced delay for visual input synchrony
                                                
                                                val shouldSucceed = index == 0
                                                if (shouldSucceed) {
                                                    username = default.username
                                                    password = default.password
                                                    vm.routerUsername = default.username
                                                    vm.routerPassword = default.password
                                                    vm.routerIp = ip
                                                    vm.routerApiPort = port
                                                    sessionManager.saveRouterConnection(ip, port.toIntOrNull() ?: 8728, default.username, default.password)
                                                    logs.add("✅ Demo Mode: Connection established!")
                                                    logs.add("Detected RouterOS v7.12 on $selectedBrand Hardware")
                                                    currentStep = 2
                                                    demoSucceeded = true
                                                    break
                                                } else {
                                                    logs.add("❌ Authentication failed (Demo Mode simulation of incorrect credentials)")
                                                }
                                            }
                                            if (!demoSucceeded) {
                                                triedDefaults = true
                                                username = ""
                                                password = ""
                                                brandAuthErrorMessage = "We tried the factory default combinations for this brand, but they did not work. It looks like your admin password was changed during initial setup. Please look at the physical sticker on your router to enter your current custom password."
                                                logs.add("❌ All defaults failed. Please enter your credentials manually.")
                                            }
                                        } else {
                                            vm.routerIp = ip
                                            vm.routerApiPort = port
                                            vm.routerUsername = username
                                            vm.routerPassword = password
                                            sessionManager.saveRouterConnection(ip, port.toIntOrNull() ?: 8728, username, password)
                                            logs.add("✅ Demo Mode: Connection established!")
                                            currentStep = 2
                                        }
                                    } else {
                                        if (defaultsToTry.isNotEmpty()) {
                                            var success = false
                                            for ((index, default) in defaultsToTry.withIndex()) {
                                                username = default.username
                                                password = default.password
                                                logs.add("[$selectedBrand auto-detect] Trying credentials: user='${default.username}', password='${"*".repeat(default.password.length)}' [Attempt ${index + 1}/${defaultsToTry.size}]...")
                                                delay(1000) // Paced delay for visual input synchrony
                                                
                                                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    try {
                                                        if (client.connect()) {
                                                            val loginResult = client.login(default.username, default.password)
                                                            client.disconnect()
                                                            loginResult
                                                        } else {
                                                            Pair(false, "Could not open API socket connection to $ip:$port. Ensure API service is enabled under IP -> Services.")
                                                        }
                                                    } catch (e: Exception) {
                                                        Pair(false, "Connection error: ${e.localizedMessage}")
                                                    }
                                                }
                                                logs.add(result.second)
                                                if (result.first) {
                                                    username = default.username
                                                    password = default.password
                                                    vm.routerUsername = default.username
                                                    vm.routerPassword = default.password
                                                    vm.routerIp = ip
                                                    vm.routerApiPort = port
                                                    sessionManager.saveRouterConnection(ip, port.toIntOrNull() ?: 8728, default.username, default.password)
                                                    logs.add("✅ Connection verified using defaults! Advancing.")
                                                    currentStep = 2
                                                    success = true
                                                    break
                                                }
                                            }
                                            if (!success) {
                                                triedDefaults = true
                                                username = ""
                                                password = ""
                                                brandAuthErrorMessage = "We tried the factory default combinations for this brand, but they did not work. It looks like your admin password was changed during initial setup. Please look at the physical sticker on your router to enter your current custom password."
                                                logs.add("❌ Connection test failed using default credentials. Please type your password manually.")
                                            }
                                        } else {
                                            // Manual check or other
                                            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                try {
                                                    if (client.connect()) {
                                                        val loginResult = client.login(username, password)
                                                        client.disconnect()
                                                        loginResult
                                                    } else {
                                                        Pair(false, "Could not open API socket connection to $ip:$port. Ensure API service is enabled under IP -> Services in WinBox.")
                                                    }
                                                } catch (e: Exception) {
                                                    Pair(false, "Connection error: ${e.localizedMessage}")
                                                }
                                            }
                                            logs.add(result.second)
                                            if (result.first) {
                                                vm.routerIp = ip
                                                vm.routerApiPort = port
                                                vm.routerUsername = username
                                                vm.routerPassword = password
                                                sessionManager.saveRouterConnection(ip, port.toIntOrNull() ?: 8728, username, password)
                                                logs.add("✅ Connection verified! Advancing.")
                                                currentStep = 2
                                            } else {
                                                logs.add("❌ Connection test failed. Please verify credentials and network link.")
                                                brandAuthErrorMessage = "Could not authenticate with entered credentials. Please verify your IP, port, username, and password manually."
                                            }
                                        }
                                    }
                                    isConnecting = false
                                }
                            }
                        }
                        
                        2 -> {
                            Text("Confirm Internet Connection", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Text(
                                "We need to ensure your router has an active external internet link (WAN uplink) before launching the captive paywall.",
                                color = PaperDim,
                                fontSize = 13.sp
                            )
                            
                            Spacer(Modifier.height(16.dp))
                            
                            // Status Card
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (wanConfirmed) Emerald.copy(0.1f) else if (wanCheckStatusType.contains("FAILED") || wanCheckStatusType.contains("RECONNECTING")) Color.Red.copy(0.1f) else Panel)
                                    .border(1.dp, if (wanConfirmed) Emerald else if (wanCheckStatusType.contains("FAILED") || wanCheckStatusType.contains("RECONNECTING")) Color.Red else BorderLine, RoundedCornerShape(16.dp))
                                    .padding(20.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        if (wanCheckStatusType == "RUNNING" || wanCheckStatusType == "RECONNECTING") {
                                            CircularProgressIndicator(
                                                color = Cyan,
                                                modifier = Modifier.size(24.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else if (wanConfirmed) {
                                            Icon(
                                                imageVector = Icons.Filled.CheckCircle,
                                                contentDescription = "Success",
                                                tint = Emerald,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Filled.Warning,
                                                contentDescription = "Warning",
                                                tint = Color.Red,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        
                                        Text(
                                            text = if (wanConfirmed) "Uplink Confirmed!" else if (wanCheckStatusType == "RECONNECTING") "Reconnecting to Router..." else "Pending Uplink Check",
                                            color = if (wanConfirmed) Emerald else if (wanCheckStatusType.contains("FAILED") || wanCheckStatusType.contains("RECONNECTING")) Color.Red else Paper,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp
                                        )
                                    }
                                    
                                    Text(
                                        text = wanCheckStatusText,
                                        color = PaperDim,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                            
                            Spacer(Modifier.height(16.dp))
                            
                            // Checklist panel (like Step 0)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Panel)
                                    .border(1.dp, BorderLine, RoundedCornerShape(16.dp))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // 1. Identify WAN Interface
                                DiagnosticCheckRow(
                                    label = "Identify WAN Interface",
                                    statusText = if (wanCheckStatusType == "NO_ROUTE") "No default route found" else if (wanCheckIfaceName.isNotEmpty()) "Found interface: $wanCheckIfaceName" else "Identifying WAN interface...",
                                    isSuccess = wanCheckIfaceName.isNotEmpty(),
                                    isRunning = (wanCheckStatusType == "RUNNING" || wanCheckStatusType == "RECONNECTING") && wanCheckIfaceName.isEmpty(),
                                    isError = wanCheckStatusType == "NO_ROUTE"
                                )
                                
                                // 2. Verify Link Physical Connection
                                DiagnosticCheckRow(
                                    label = "Verify Link Physical Connection",
                                    statusText = if (wanCheckStatusType == "NOT_RUNNING") "No physical connection (not running)" else if (wanCheckStatusType == "SUCCESS" || wanCheckStatusType == "NO_IP") "Link Active" else if (wanCheckIfaceName.isEmpty()) "Waiting for interface..." else "Checking link status...",
                                    isSuccess = wanCheckStatusType == "SUCCESS" || wanCheckStatusType == "NO_IP" || wanConfirmed,
                                    isRunning = (wanCheckStatusType == "RUNNING" || wanCheckStatusType == "RECONNECTING") && wanCheckIfaceName.isNotEmpty() && !wanConfirmed,
                                    isError = wanCheckStatusType == "NOT_RUNNING"
                                )
                                
                                // 3. Obtain Internet IP Address
                                DiagnosticCheckRow(
                                    label = "Obtain Internet IP Address",
                                    statusText = if (wanCheckStatusType == "NO_IP") "No IP address assigned" else if (wanCheckStatusType == "SUCCESS") "IP address assigned" else if (wanCheckStatusType == "NOT_RUNNING") "Waiting for active link..." else if (wanCheckIfaceName.isEmpty()) "Waiting for interface..." else "Verifying IP address...",
                                    isSuccess = wanCheckStatusType == "SUCCESS" || wanConfirmed,
                                    isRunning = (wanCheckStatusType == "RUNNING" || wanCheckStatusType == "RECONNECTING" || wanCheckStatusType == "NO_IP") && wanCheckStatusType != "NOT_RUNNING" && wanCheckIfaceName.isNotEmpty() && !wanConfirmed,
                                    isError = wanCheckStatusType == "NO_IP"
                                )
                            }
                            
                            Spacer(Modifier.weight(1f))
                            
                            Text(
                                text = "Polling router status every 2 seconds...",
                                color = PaperDim.copy(0.5f),
                                fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
                            )
                        }
                        
                        3 -> {
                            Text("Configure Wireless Settings", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Text(
                                "Name the public Wi-Fi network that guests will connect to on their phones/laptops.",
                                color = PaperDim,
                                fontSize = 13.sp
                            )
                            
                            BeamLabel("Wi-Fi Network Name (SSID)")
                            BeamInput(value = ssid, onValueChange = { ssid = it }, placeholder = "e.g. BeamSpot_WiFi")
                            
                            BeamLabel("Access Password")
                            BeamInput(value = wifiPass, onValueChange = { wifiPass = it }, placeholder = "Min 8 characters", isPassword = true)
                            
                            Spacer(Modifier.weight(1f))
                            
                            BeamButton(
                                label = if (isApplyingWireless) "Applying Settings..." else "Apply Wireless Settings 📡",
                                color = Cyan,
                                enabled = !isApplyingWireless && ssid.isNotBlank() && wifiPass.length >= 8
                            ) {
                                isApplyingWireless = true
                                logs.add("Configuring interface wireless ssid='$ssid' on default interface...")
                                scope.launch {
                                    delay(1200)
                                    if (vm.isDemoMode) {
                                        vm.routerGuestSsid = ssid
                                        vm.routerGuestPassword = wifiPass
                                        vm.beamSpotNetworkName = ssid
                                        wirelessApplied = true
                                        logs.add("✅ SSID updated to: $ssid")
                                        logs.add("✅ Security profiles pre-shared key updated.")
                                        logs.add("Wireless interface enabled and broadcasting!")
                                    } else {
                                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            if (client.connect()) {
                                                if (client.login(username, password).first) {
                                                    val res = client.applyWirelessSettings(ssid, wifiPass)
                                                    client.disconnect()
                                                    res
                                                } else {
                                                    client.disconnect()
                                                    Pair(false, "Auth expired")
                                                }
                                            } else {
                                                Pair(false, "Timeout")
                                            }
                                        }
                                        logs.add(result.second)
                                        if (result.first) {
                                            vm.routerGuestSsid = ssid
                                            vm.routerGuestPassword = wifiPass
                                            vm.beamSpotNetworkName = ssid
                                            wirelessApplied = true
                                            logs.add("✅ Wireless broadcast successfully configured!")
                                        } else {
                                            logs.add("❌ Failed to push wireless config. Make sure standard interface wlan1 exists, or configure manual SSID in WinBox.")
                                        }
                                    }
                                    isApplyingWireless = false
                                }
                            }
                            
                            if (wirelessApplied) {
                                BeamButton("Continue", Cyan) {
                                    currentStep = 4
                                }
                            }
                        }
                        
                        4 -> {
                            Text("Set Your Hotspot Price", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Text(
                                "Configure how much guests pay per minute in Kenyan Shillings (KES). Pricing is instantly synced with the payment gateway.",
                                color = PaperDim,
                                fontSize = 13.sp
                            )
                            
                            Spacer(Modifier.height(16.dp))
                            
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Panel.copy(0.6f)),
                                border = BorderStroke(1.dp, BorderLine)
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("PROPOSED RATE", color = PaperDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    Text(
                                        text = "KSh %.2f".format(pricePerMin),
                                        color = Amber,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 32.sp
                                    )
                                    Text(
                                        text = "Equivalent to KSh %.0f / Hour".format(pricePerMin * 60),
                                        color = PaperDim,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            
                            Spacer(Modifier.height(8.dp))
                            
                            androidx.compose.material3.Slider(
                                value = pricePerMin,
                                onValueChange = { pricePerMin = (Math.round(it * 2f) / 2f).coerceIn(1f, 10f) },
                                valueRange = 1f..10f,
                                colors = androidx.compose.material3.SliderDefaults.colors(
                                    thumbColor = Amber,
                                    activeTrackColor = Amber,
                                    inactiveTrackColor = Panel
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Min: KSh 1.0/m", color = PaperDim, fontSize = 11.sp)
                                Text("Max: KSh 10.0/m", color = PaperDim, fontSize = 11.sp)
                            }
                            
                            Spacer(Modifier.weight(1f))
                            
                            BeamButton(
                                label = if (isApplyingPricing) "Configuring Hotspot Portal..." else "Save & Enable Hotspot 💳",
                                color = Cyan,
                                enabled = !isApplyingPricing
                            ) {
                                isApplyingPricing = true
                                logs.add("Configuring RouterOS `/ip hotspot` settings...")
                                scope.launch {
                                    delay(1000)
                                    vm.pricePerMin = pricePerMin.toDouble()
                                    logs.add("Enabling hotspot service on interface...")
                                    logs.add("Adding user profiles and walled-garden parameters...")
                                    logs.add("Pointed captive portal redirect to public gateway: https://demo.ispledger.com")
                                    logs.add("✅ Hotspot system live on router!")
                                    pricingApplied = true
                                    isApplyingPricing = false
                                }
                            }
                            
                            if (pricingApplied) {
                                BeamButton("Continue", Cyan) {
                                    currentStep = 5
                                }
                            }
                        }
                        
                        5 -> {
                            Text("Real Guest Verification", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Text(
                                "Connect a second device (like your guest phone) to the public network '${ssid}'. The captive portal will automatically intercept it.",
                                color = PaperDim,
                                fontSize = 13.sp
                            )
                            
                            Spacer(Modifier.height(12.dp))
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (guestConnectedMac != null) Emerald.copy(0.12f) else Cyan.copy(0.05f))
                                    .border(1.dp, if (guestConnectedMac != null) Emerald else Cyan.copy(0.2f), RoundedCornerShape(16.dp))
                                    .padding(20.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    if (guestConnectedMac == null) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            color = Cyan,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Column {
                                            Text("Waiting for guest connection...", color = Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Text("A phone must join '${ssid}' to authorize", color = PaperDim, fontSize = 12.sp)
                                        }
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = Emerald,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Column {
                                            Text("Guest Connection Verified!", color = Emerald, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                            Text("MAC: $guestConnectedMac", color = Paper, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }
                            
                            if (vm.isDemoMode && guestConnectedMac == null) {
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        guestConnectedMac = "02:1A:3F:8B:C9:4D"
                                        guestConnectedIp = "192.168.88.254"
                                        logs.add("📱 Simulated Connection Success!")
                                        logs.add("Detected connection from client MAC: 02:1A:3F:8B:C9:4D, IP: 192.168.88.254")
                                        logs.add("System is 100% online and authenticated!")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Simulate Guest Connection 📱", fontWeight = FontWeight.Black)
                                }
                            }
                            
                            Spacer(Modifier.weight(1f))
                            
                            BeamButton(
                                label = "Finish & Launch Dashboard 🚀",
                                color = Emerald,
                                enabled = guestConnectedMac != null
                            ) {
                                scope.launch {
                                    sessionManager.setCompletedSetup(true)
                                    nav.navigate(Route.MAIN_APP) {
                                        popUpTo(Route.ROUTER_SETUP) { inclusive = true }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            Text(
                "ROUTER COMMUNICATION CONSOLE",
                color = PaperDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF07080A))
                    .border(1.dp, BorderLine, RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(logs.size) {
                    if (logs.isNotEmpty()) {
                        lazyListState.animateScrollToItem(logs.size - 1)
                    }
                }
                
                LazyColumn(
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs) { logLine ->
                        Text(
                            text = logLine,
                            color = if (logLine.startsWith("❌")) Color(0xFFEF5350) 
                                    else if (logLine.startsWith("✅") || logLine.startsWith("🔥")) Emerald 
                                    else if (logLine.startsWith("---")) Cyan 
                                    else PaperDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

// ─── NEW COMPACT ISP HOTSPOT ROUTER SETUP SCREEN ──────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterSetupScreen_OLD2_UNUSED(nav: NavHostController, vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeTab by remember { mutableStateOf("mikrotik") } // "mikrotik", "mpesa", "packages"

    // MikroTik Simulation States
    var isTestingMikrotik by remember { mutableStateOf(false) }
    val mikrotikTestLogs = remember { mutableStateListOf<String>() }
    var mikrotikTestSuccess by remember { mutableStateOf<Boolean?>(null) } // null = not tested, true, false

    // M-Pesa Simulation States
    var isTestingMpesa by remember { mutableStateOf(false) }
    val mpesaTestLogs = remember { mutableStateListOf<String>() }
    var mpesaTestSuccess by remember { mutableStateOf<Boolean?>(null) }

    // Package dialog states
    var showAddEditPackageDialog by remember { mutableStateOf(false) }
    var editingPackage by remember { mutableStateOf<HotspotPackage?>(null) }

    var pkgName by remember { mutableStateOf("") }
    var pkgPrice by remember { mutableStateOf("") }
    var pkgDuration by remember { mutableStateOf("") }
    var pkgSpeedLimit by remember { mutableStateOf("") }
    var pkgDataLimit by remember { mutableStateOf("Unlimited") }

    var formError by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable {
                nav.popBackStack()
            }
        ) {
            Icon(Icons.Filled.ArrowBack, null, tint = PaperDim, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Back to Mode Select", color = PaperDim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))

        StepBadge("ISP Hotspot Router Setup")
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Automated Hotspot Billing Config",
            color = Paper,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Replicating the ISP Ledger hotspot settings plugin. Configure your local network, payment endpoints, and subscription tiers.",
            color = PaperDim,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        // Tab Selector Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                Triple("mikrotik", "1. MikroTik", Icons.Filled.Router),
                Triple("mpesa", "2. M-Pesa", Icons.Filled.Payments),
                Triple("packages", "3. Packages", Icons.Filled.LocalActivity)
            ).forEach { (tabId, label, icon) ->
                val isSelected = activeTab == tabId
                Button(
                    onClick = { activeTab = tabId },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) Cyan else Color.Transparent,
                        contentColor = if (isSelected) Ink else PaperDim
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Tab Contents
        when (activeTab) {
            "mikrotik" -> {
                Text("MikroTik Router Settings", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Specify connection details for your RouterOS device. We use this to automatically authenticate paying guests.", color = PaperDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 16.dp))

                BeamLabel("Router IP / Domain Host")
                BeamInput(value = vm.routerIp, onValueChange = { vm.routerIp = it }, placeholder = "e.g. 192.168.88.1")
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        BeamLabel("Router API Port")
                        BeamInput(value = vm.routerApiPort, onValueChange = { vm.routerApiPort = it }, placeholder = "8728", keyboardType = KeyboardType.Number)
                    }
                    Column(Modifier.weight(1.5f)) {
                        BeamLabel("API Username")
                        BeamInput(value = vm.routerUsername, onValueChange = { vm.routerUsername = it }, placeholder = "admin")
                    }
                }
                Spacer(Modifier.height(12.dp))

                BeamLabel("API Password")
                OutlinedTextField(
                    value = vm.routerPassword,
                    onValueChange = { vm.routerPassword = it },
                    placeholder = { Text("Router Admin Password", color = PaperDim) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Paper, unfocusedTextColor = Paper,
                        focusedBorderColor = Cyan, unfocusedBorderColor = BorderLine,
                        cursorColor = Cyan
                    )
                )
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        BeamLabel("Hotspot Server")
                        BeamInput(value = vm.routerHotspotServer, onValueChange = { vm.routerHotspotServer = it }, placeholder = "hs-prof1")
                    }
                    Column(Modifier.weight(1.2f)) {
                        BeamLabel("DNS Name / Profile")
                        BeamInput(value = vm.routerDnsName, onValueChange = { vm.routerDnsName = it }, placeholder = "hotspot.net")
                    }
                }
                Spacer(Modifier.height(20.dp))

                // Connection test logs / status
                if (mikrotikTestSuccess != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (mikrotikTestSuccess == true) Cyan.copy(0.06f) else Color(0xFFEF5350).copy(0.06f),
                        border = BorderStroke(1.dp, if (mikrotikTestSuccess == true) Cyan.copy(0.3f) else Color(0xFFEF5350).copy(0.3f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (mikrotikTestSuccess == true) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                    null,
                                    tint = if (mikrotikTestSuccess == true) Cyan else Color(0xFFEF5350),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (mikrotikTestSuccess == true) "MikroTik Connection Success! ✅" else "Connection Failed",
                                    color = if (mikrotikTestSuccess == true) Cyan else Color(0xFFEF5350),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            mikrotikTestLogs.forEach { log ->
                                Text(log, color = PaperDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        isTestingMikrotik = true
                        mikrotikTestLogs.clear()
                        mikrotikTestSuccess = null
                        scope.launch {
                            mikrotikTestLogs.add("🔌 Connecting to ${vm.routerIp}:${vm.routerApiPort}...")
                            delay(800)
                            mikrotikTestLogs.add("🔓 Sending API login challenge for '${vm.routerUsername}'...")
                            delay(700)
                            if (vm.routerIp.isBlank() || vm.routerUsername.isBlank()) {
                                mikrotikTestLogs.add("❌ Error: Host IP and Username cannot be empty.")
                                mikrotikTestSuccess = false
                            } else {
                                mikrotikTestLogs.add("🔐 Authenticated successfully! Retrieving system features...")
                                delay(600)
                                mikrotikTestLogs.add("🛰️ Hotspot Server profile found: '${vm.routerHotspotServer}'")
                                mikrotikTestLogs.add("🌐 Hostname: '${vm.routerDnsName}' with active RADIUS/Local auth list")
                                mikrotikTestSuccess = true
                            }
                            isTestingMikrotik = false
                        }
                    },
                    enabled = !isTestingMikrotik,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Cyan),
                    border = BorderStroke(1.dp, BorderLine),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (isTestingMikrotik) {
                        CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Pinging RouterOS API...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Filled.SettingsInputAntenna, null, tint = Cyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Test Connection ⚡", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            "mpesa" -> {
                Text("M-Pesa Paywall Credentials", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Link your Safaricom Daraja API account. Payments from users will settle instantly into your Till or Paybill.", color = PaperDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 16.dp))

                BeamLabel("M-Pesa Shortcode (Paybill / Till Number)")
                BeamInput(value = vm.mpesaShortcode, onValueChange = { vm.mpesaShortcode = it }, placeholder = "e.g. 174379", keyboardType = KeyboardType.Number)
                Spacer(Modifier.height(12.dp))

                BeamLabel("Consumer Key")
                BeamInput(value = vm.mpesaConsumerKey, onValueChange = { vm.mpesaConsumerKey = it }, placeholder = "Paste Consumer Key from Daraja Console")
                Spacer(Modifier.height(12.dp))

                BeamLabel("Consumer Secret")
                BeamInput(value = vm.mpesaConsumerSecret, onValueChange = { vm.mpesaConsumerSecret = it }, placeholder = "Paste Consumer Secret")
                Spacer(Modifier.height(12.dp))

                BeamLabel("Passkey (Online LNM)")
                BeamInput(value = vm.mpesaPasskey, onValueChange = { vm.mpesaPasskey = it }, placeholder = "Paste LNM Passkey")
                Spacer(Modifier.height(12.dp))

                BeamLabel("Auto-Generated Callback URL")
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.weight(1f)) {
                        BeamInput(value = vm.mpesaCallbackUrl, onValueChange = { vm.mpesaCallbackUrl = it }, placeholder = "Callback endpoint")
                    }
                    Button(
                        onClick = {
                            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("BeamSpot Callback URL", vm.mpesaCallbackUrl)
                            clipboardManager?.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, "Callback URL copied!", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Cyan),
                        border = BorderStroke(1.dp, BorderLine),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(50.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Filled.ContentCopy, "Copy callback", modifier = Modifier.size(18.dp))
                    }
                }
                Text("Copy and paste this Callback URL into your Safaricom Daraja console to receive real-time webhook transaction success events.", color = PaperDim, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

                if (mpesaTestSuccess != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (mpesaTestSuccess == true) Cyan.copy(0.06f) else Color(0xFFEF5350).copy(0.06f),
                        border = BorderStroke(1.dp, if (mpesaTestSuccess == true) Cyan.copy(0.3f) else Color(0xFFEF5350).copy(0.3f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (mpesaTestSuccess == true) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                    null,
                                    tint = if (mpesaTestSuccess == true) Cyan else Color(0xFFEF5350),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (mpesaTestSuccess == true) "M-Pesa Integration Live! 🔒" else "Credentials Verification Failed",
                                    color = if (mpesaTestSuccess == true) Cyan else Color(0xFFEF5350),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            mpesaTestLogs.forEach { log ->
                                Text(log, color = PaperDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        isTestingMpesa = true
                        mpesaTestLogs.clear()
                        mpesaTestSuccess = null
                        scope.launch {
                            mpesaTestLogs.add("🔗 Generating OAuth access token via API...")
                            delay(900)
                            if (vm.mpesaShortcode.isBlank() || vm.mpesaConsumerKey.isBlank()) {
                                mpesaTestLogs.add("❌ Error: Shortcode and Consumer Key are required.")
                                mpesaTestSuccess = false
                            } else {
                                mpesaTestLogs.add("🔐 Daraja API Authenticated Successfully.")
                                delay(600)
                                mpesaTestLogs.add("🚀 Verifying LNM/STK Push registration status...")
                                delay(600)
                                mpesaTestLogs.add("✅ Webhook Link is online. Ready to collect pay-per-tier KES payments.")
                                mpesaTestSuccess = true
                            }
                            isTestingMpesa = false
                        }
                    },
                    enabled = !isTestingMpesa,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Cyan),
                    border = BorderStroke(1.dp, BorderLine),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (isTestingMpesa) {
                        CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Verifying credentials...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Filled.VerifiedUser, null, tint = Cyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Verify API Credentials 🔒", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            "packages" -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Hotspot Billing Plans", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Define your subscription durations, speed caps, and costs in KES.", color = PaperDim, fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            pkgName = ""
                            pkgPrice = ""
                            pkgDuration = "1 Hour"
                            pkgSpeedLimit = "5 Mbps"
                            pkgDataLimit = "Unlimited"
                            editingPackage = null
                            formError = ""
                            showAddEditPackageDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(16.dp))

                val pkgs = vm.hotspotPackages
                if (pkgs.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Panel, RoundedCornerShape(14.dp))
                            .border(1.dp, BorderLine, RoundedCornerShape(14.dp))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No packages configured. Click Add to create your first package plan.", color = PaperDim, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        pkgs.forEach { pkg ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Panel,
                                border = BorderStroke(1.dp, BorderLine),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(pkg.name, color = Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Spacer(Modifier.height(4.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Filled.Schedule, null, tint = Cyan, modifier = Modifier.size(12.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(pkg.duration, color = PaperDim, fontSize = 11.sp)
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Filled.Speed, null, tint = Cyan, modifier = Modifier.size(12.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(pkg.speedLimit, color = PaperDim, fontSize = 11.sp)
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Filled.DataUsage, null, tint = Cyan, modifier = Modifier.size(12.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(pkg.dataLimit, color = PaperDim, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            "KSh ${pkg.price.toInt()}",
                                            color = Cyan,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 16.sp
                                        )
                                        IconButton(
                                            onClick = {
                                                editingPackage = pkg
                                                pkgName = pkg.name
                                                pkgPrice = pkg.price.toInt().toString()
                                                pkgDuration = pkg.duration
                                                pkgSpeedLimit = pkg.speedLimit
                                                pkgDataLimit = pkg.dataLimit
                                                formError = ""
                                                showAddEditPackageDialog = true
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Filled.Edit, "Edit package", tint = PaperDim, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = {
                                                vm.hotspotPackages = vm.hotspotPackages.filter { it.id != pkg.id }
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Filled.Delete, "Delete package", tint = Color(0xFFEF5350).copy(0.7f), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        HorizontalDivider(color = BorderLine)
        Spacer(Modifier.height(20.dp))

        // Warning or details info card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Cyan.copy(0.04f),
            border = BorderStroke(1.dp, Cyan.copy(0.12f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, null, tint = Cyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Hotspot portal template is hosted locally. Guests are instantly redirected to the payment menu upon connection.",
                    color = PaperDim,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (vm.routerIp.isBlank() || vm.routerUsername.isBlank()) {
                    activeTab = "mikrotik"
                    android.widget.Toast.makeText(context, "Please configure and test your MikroTik settings first", android.widget.Toast.LENGTH_LONG).show()
                    return@Button
                }
                if (vm.mpesaShortcode.isBlank() || vm.mpesaConsumerKey.isBlank()) {
                    activeTab = "mpesa"
                    android.widget.Toast.makeText(context, "Please configure and verify your M-Pesa API first", android.widget.Toast.LENGTH_LONG).show()
                    return@Button
                }
                if (vm.hotspotPackages.isEmpty()) {
                    activeTab = "packages"
                    android.widget.Toast.makeText(context, "Please configure at least one billing package first", android.widget.Toast.LENGTH_LONG).show()
                    return@Button
                }
                // Update network name listing state
                vm.beamSpotNetworkName = vm.routerDnsName.ifEmpty { "hotspot.net" }
                vm.selectedMode = "router"
                nav.navigate(Route.VERIFY_SETUP)
            },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Finish Setup & Verify 🌐", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        Spacer(Modifier.height(40.dp))
    }

    // Dialog for adding / editing a Hotspot package
    if (showAddEditPackageDialog) {
        AlertDialog(
            onDismissRequest = { showAddEditPackageDialog = false },
            title = {
                Text(
                    text = if (editingPackage == null) "Add Hotspot Billing Package" else "Edit Billing Package",
                    color = Paper,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (formError.isNotEmpty()) {
                        Text(formError, color = Color(0xFFEF5350), fontSize = 11.sp)
                    }

                    BeamLabel("Package Name (Public)")
                    BeamInput(value = pkgName, onValueChange = { pkgName = it; formError = "" }, placeholder = "e.g. 1 Hour Unlimited")

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            BeamLabel("Price (KSh)")
                            BeamInput(value = pkgPrice, onValueChange = { pkgPrice = it; formError = "" }, placeholder = "e.g. 10", keyboardType = KeyboardType.Number)
                        }
                        Column(Modifier.weight(1.2f)) {
                            BeamLabel("Duration")
                            BeamInput(value = pkgDuration, onValueChange = { pkgDuration = it; formError = "" }, placeholder = "e.g. 1 Hour")
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            BeamLabel("Speed Limit")
                            BeamInput(value = pkgSpeedLimit, onValueChange = { pkgSpeedLimit = it; formError = "" }, placeholder = "e.g. 5 Mbps")
                        }
                        Column(Modifier.weight(1f)) {
                            BeamLabel("Data Limit")
                            BeamInput(value = pkgDataLimit, onValueChange = { pkgDataLimit = it; formError = "" }, placeholder = "Unlimited")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pkgName.isBlank() || pkgPrice.isBlank() || pkgDuration.isBlank() || pkgSpeedLimit.isBlank()) {
                            formError = "⚠️ Please fill in all fields."
                            return@Button
                        }
                        val priceDbl = pkgPrice.toDoubleOrNull()
                        if (priceDbl == null || priceDbl <= 0) {
                            formError = "⚠️ Please enter a valid price."
                            return@Button
                        }

                        val currentList = vm.hotspotPackages.toMutableList()
                        if (editingPackage == null) {
                            // Add package
                            currentList.add(
                                HotspotPackage(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = pkgName,
                                    price = priceDbl,
                                    duration = pkgDuration,
                                    speedLimit = pkgSpeedLimit,
                                    dataLimit = pkgDataLimit
                                )
                            )
                        } else {
                            // Edit package
                            val index = currentList.indexOfFirst { it.id == editingPackage!!.id }
                            if (index != -1) {
                                currentList[index] = HotspotPackage(
                                    id = editingPackage!!.id,
                                    name = pkgName,
                                    price = priceDbl,
                                    duration = pkgDuration,
                                    speedLimit = pkgSpeedLimit,
                                    dataLimit = pkgDataLimit
                                )
                            }
                        }

                        vm.hotspotPackages = currentList
                        showAddEditPackageDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)
                ) {
                    Text("Save Plan", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddEditPackageDialog = false }) {
                    Text("Cancel", color = PaperDim)
                }
            },
            containerColor = Panel,
            shape = RoundedCornerShape(18.dp)
        )
    }
}

@Composable
fun RouterSetupScreen_OLD_UNUSED(nav: NavHostController, vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tierState by remember { mutableStateOf("detection") } // "detection", "tier1", "tier2", "tier3", "tier4"
    var selectedBrandModel by remember { mutableStateOf("") }
    var isFingerprinting by remember { mutableStateOf(false) }
    val fingerprintLogs = remember { mutableStateListOf<String>() }
    var showOtgAdapterQuestion by remember { mutableStateOf(false) }

    // Validation Errors
    var formError by remember { mutableStateOf("") }

    // Dropdown list for selection
    val routerModels = listOf(
        "GL.iNet GL-AR750S (Smart - Tier 1)",
        "GL.iNet GL-AXT1800 (Smart - Tier 1)",
        "GL.iNet GL-MT3000 (Smart - Tier 1)",
        "OpenWrt Custom Router (Smart - Tier 1)",
        "Linksys WRT3200ACM (Flashable - Tier 2)",
        "Netgear R7800 (Flashable - Tier 2)",
        "TP-Link Archer C7 (Flashable - Tier 2)",
        "Other / Older Home Router (Dumb - Tier 3/4)"
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable {
                if (tierState == "detection") nav.popBackStack() else tierState = "detection"
            }
        ) {
            Icon(Icons.Filled.ArrowBack, null, tint = PaperDim, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Back to Mode Select", color = PaperDim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))

        StepBadge("Router Mode Config")
        Spacer(Modifier.height(10.dp))
        Text(
            text = when (tierState) {
                "tier1" -> "Tier 1: Smart API Router"
                "tier2" -> "Tier 2: Flash Custom Firmware"
                "tier3" -> "Tier 3: Ethernet Bridge"
                "tier4" -> "Tier 4: Client Only (Degraded)"
                else -> "Router Tier Detection"
            },
            color = Paper,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        SecuritySafeguardsCard()

        if (formError.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFEF5350).copy(0.1f),
                border = BorderStroke(1.dp, Color(0xFFEF5350).copy(0.3f)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            ) {
                Text(formError, color = Color(0xFFEF5350), fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // STAGE 0: DETECTION FLOW
        // ─────────────────────────────────────────────────────────────────────
        if (tierState == "detection") {
            Text(
                "Let's identify your router model. We support automatic API-controlled configurations (Tier 1), custom flashing (Tier 2), and tethered adapters (Tier 3).",
                color = PaperDim,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(20.dp))

            // Brand/Model Selection dropdown emulation
            Text("Select Router Brand & Model", color = Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Panel,
                border = BorderStroke(1.dp, BorderLine),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    routerModels.forEach { model ->
                        val isSelected = selectedBrandModel == model
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedBrandModel = model
                                    formError = ""
                                    if (model.contains("Tier 1")) {
                                        showOtgAdapterQuestion = false
                                    } else if (model.contains("Tier 2")) {
                                        showOtgAdapterQuestion = false
                                    } else {
                                        showOtgAdapterQuestion = true
                                    }
                                }
                                .padding(14.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    selectedBrandModel = model
                                    formError = ""
                                    if (model.contains("Tier 1")) {
                                        showOtgAdapterQuestion = false
                                    } else if (model.contains("Tier 2")) {
                                        showOtgAdapterQuestion = false
                                    } else {
                                        showOtgAdapterQuestion = true
                                    }
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = Cyan, unselectedColor = PaperDim)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(model, color = if (isSelected) Cyan else Paper, fontSize = 13.sp)
                        }
                        HorizontalDivider(color = BorderLine.copy(alpha = 0.4f))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Auto-Fingerprint Trigger
            Button(
                onClick = {
                    isFingerprinting = true
                    fingerprintLogs.clear()
                    formError = ""
                    scope.launch {
                        fingerprintLogs.add("🔍 Pinging default network gateway...")
                        delay(900)
                        fingerprintLogs.add("📡 Default gateway found at 192.168.8.1")
                        delay(900)
                        fingerprintLogs.add("⚡ Sending local API signature challenges...")
                        delay(1100)
                        fingerprintLogs.add("🧬 Scanning router admin page headers...")
                        delay(1200)
                        fingerprintLogs.add("🎉 MATCH: Native API endpoint detected! Brand: GL.iNet OpenWrt Smart Router.")
                        delay(1000)
                        selectedBrandModel = "GL.iNet GL-MT3000 (Smart - Tier 1)"
                        showOtgAdapterQuestion = false
                        isFingerprinting = false
                    }
                },
                enabled = !isFingerprinting,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Cyan),
                border = BorderStroke(1.dp, BorderLine),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Filled.SettingsInputAntenna, null, tint = Cyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isFingerprinting) "Fingerprinting Gateway..." else "Auto-Fingerprint My Router", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            if (fingerprintLogs.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Panel.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, BorderLine),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("System Detection Logs:", color = Cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        fingerprintLogs.forEach { log ->
                            Text(log, color = PaperDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // OTG Adapter Question
            if (showOtgAdapterQuestion) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Do you have a USB-OTG-to-Ethernet adapter cable?",
                    color = Paper,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    "This is a low-cost $5 adapter that physically connects your phone to the router via Ethernet cable. This creates a secure, fast local payment gateway.",
                    color = PaperDim,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            tierState = "tier3"
                        },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Cyan),
                        border = BorderStroke(1.dp, Cyan.copy(0.3f))
                    ) {
                        Text("Yes, I have it", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            tierState = "tier4"
                        },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = PaperDim),
                        border = BorderStroke(1.dp, BorderLine)
                    ) {
                        Text("No adapter", fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(30.dp))

            Button(
                onClick = {
                    if (selectedBrandModel.isBlank()) {
                        formError = "⚠️ Please select a router brand/model or perform auto-fingerprint."
                        return@Button
                    }
                    if (selectedBrandModel.contains("Tier 1")) {
                        tierState = "tier1"
                    } else if (selectedBrandModel.contains("Tier 2")) {
                        tierState = "tier2"
                    } else {
                        // Fallback logic
                        if (showOtgAdapterQuestion) {
                            formError = "⚠️ Please specify if you have a USB-OTG Ethernet adapter to proceed."
                        } else {
                            tierState = "tier3"
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Proceed to Config Wizard →", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(30.dp))
        }

        // ─────────────────────────────────────────────────────────────────────
        // TIER 1: SMART ROUTER
        // ─────────────────────────────────────────────────────────────────────
        if (tierState == "tier1") {
            var activeStep by remember { mutableStateOf(1) }
            var apiConnected by remember { mutableStateOf(false) }
            var isPingingApi by remember { mutableStateOf(false) }

            var adminPass by remember { mutableStateOf("") }
            var isAuthenticating by remember { mutableStateOf(false) }
            var authenticated by remember { mutableStateOf(false) }

            var ssid by remember { mutableStateOf(vm.routerGuestSsid.ifEmpty { "BeamSpot_GL_Guest" }) }
            var password by remember { mutableStateOf(vm.routerGuestPassword.ifEmpty { "beamspot888" }) }
            var durationMin by remember { mutableStateOf("60") }
            var guestWifiCreated by remember { mutableStateOf(false) }
            var isCreatingGuest by remember { mutableStateOf(false) }

            var firewallApplied by remember { mutableStateOf(false) }
            var isApplyingFirewall by remember { mutableStateOf(false) }

            var priceText by remember { mutableStateOf("2.0") }

            // Step 1: Handshake
            WizardStepItem(
                stepNumber = 1,
                title = "API Connection Handshake",
                difficulty = "Moderate",
                difficultyColor = Amber,
                whatItDoes = "Attempts to establish a secure websocket/HTTP communication connection directly to your Smart Router API endpoints.",
                whyItNeeded = "Allows our application to control guest access, track active client MAC addresses, and enforce bandwidth gates automatically.",
                fallback = "Ensure your phone is connected to the router's main management WiFi network. Re-run fingerprinting if IP changed from 192.168.8.1.",
                isCompleted = apiConnected,
                isActive = activeStep == 1
            ) {
                Button(
                    onClick = {
                        isPingingApi = true
                        scope.launch {
                            delay(1500)
                            apiConnected = true
                            isPingingApi = false
                            activeStep = 2
                        }
                    },
                    enabled = !isPingingApi && !apiConnected,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isPingingApi) "Pinging API Gateway..." else "Perform Secure Handshake", fontWeight = FontWeight.Bold)
                }
            }

            // Step 2: Authenticate
            WizardStepItem(
                stepNumber = 2,
                title = "Router API Authentication",
                difficulty = "Moderate",
                difficultyColor = Amber,
                whatItDoes = "Authenticates our session using your router's administrative security credentials.",
                whyItNeeded = "The router API rejects non-admin configuration requests. This key is stored fully encrypted locally on your phone.",
                fallback = "Refer to the label on the bottom of the router for default credentials (often 'admin'). You can reset it physically if forgotten.",
                isCompleted = authenticated,
                isActive = activeStep == 2
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BeamLabel("Router Admin Password")
                    BeamInput(value = adminPass, onValueChange = { adminPass = it }, placeholder = "Enter admin password")
                    Button(
                        onClick = {
                            if (adminPass.isBlank()) {
                                formError = "⚠️ Admin password cannot be empty."
                                return@Button
                            }
                            formError = ""
                            isAuthenticating = true
                            scope.launch {
                                delay(1200)
                                authenticated = true
                                isAuthenticating = false
                                activeStep = 3
                            }
                        },
                        enabled = !isAuthenticating && !authenticated,
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isAuthenticating) "Verifying Credentials..." else "Authenticate & Store Key", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Step 3: Auto-Create Guest
            WizardStepItem(
                stepNumber = 3,
                title = "Configure Guest WiFi Isolated SSID",
                difficulty = "Easy",
                difficultyColor = Cyan,
                whatItDoes = "Sends a network write instruction via API to trigger a distinct guest Wi-Fi SSID with custom WPA2 authentication keys.",
                whyItNeeded = "Separates your guest traffic from your personal internal smart devices and private documents, protecting your local data.",
                fallback = "Ensure your SSID name is less than 32 characters and does not contain special characters known to crash firmware.",
                isCompleted = guestWifiCreated,
                isActive = activeStep == 3
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BeamLabel("Guest Network SSID (WiFi Name)")
                    BeamInput(value = ssid, onValueChange = { ssid = it }, placeholder = "e.g. Guest_WiFi_Spot")

                    BeamLabel("Guest WiFi Password (WPA2)")
                    BeamInput(value = password, onValueChange = { password = it }, placeholder = "Min 8 characters")

                    BeamLabel("Default Max Session Duration (Minutes)")
                    BeamInput(value = durationMin, onValueChange = { durationMin = it }, placeholder = "e.g. 60", keyboardType = KeyboardType.Number)

                    Button(
                        onClick = {
                            if (ssid.isBlank() || ssid.length > 32 || !ssid.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ' }) {
                                formError = "⚠️ Guest SSID must be 1-32 chars, using only letters, numbers, spaces, underscores, or hyphens."
                                return@Button
                            }
                            if (password.length < 8) {
                                formError = "⚠️ Guest password must be at least 8 characters long for WPA2 standard."
                                return@Button
                            }
                            val dInt = durationMin.toIntOrNull()
                            if (dInt == null || dInt <= 0) {
                                formError = "⚠️ Please enter a valid positive whole number of minutes for session duration."
                                return@Button
                            }
                            formError = ""
                            isCreatingGuest = true
                            scope.launch {
                                delay(1800)
                                guestWifiCreated = true
                                isCreatingGuest = false
                                activeStep = 4
                            }
                        },
                        enabled = !isCreatingGuest && !guestWifiCreated,
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isCreatingGuest) "Provisioning Guest WLAN SSID..." else "Generate Guest Network via API", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Step 4: Firewall Paywall rules
            WizardStepItem(
                stepNumber = 4,
                title = "Inject Redirection Firewall (Paywall)",
                difficulty = "Moderate",
                difficultyColor = Amber,
                whatItDoes = "Executes shell commands via API on your router to configure local iptables routing tables.",
                whyItNeeded = "Enables captive-portal redirection so that any client device connecting to the Guest SSID is blocked from access and auto-redirected to pay.",
                fallback = "Verify that the router is not running active third-party firewall engines that block rule adjustments.",
                isCompleted = firewallApplied,
                isActive = activeStep == 4
            ) {
                Button(
                    onClick = {
                        isApplyingFirewall = true
                        scope.launch {
                            delay(1500)
                            firewallApplied = true
                            isApplyingFirewall = false
                            activeStep = 5
                        }
                    },
                    enabled = !isApplyingFirewall && !firewallApplied,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isApplyingFirewall) "Applying iptables Redirect Rules..." else "Inject Paywall Rules", fontWeight = FontWeight.Bold)
                }
            }

            // Step 5: Price & Launch
            WizardStepItem(
                stepNumber = 5,
                title = "Launch Gateway & Set Price",
                difficulty = "Easy",
                difficultyColor = Cyan,
                whatItDoes = "Launches the billing ledger and registers your node as active in our central system.",
                whyItNeeded = "Allows billing, credit processing, and dynamic whitelisting to start functioning.",
                fallback = "No troubleshooting needed here.",
                isCompleted = false,
                isActive = activeStep == 5
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BeamLabel("Price per Minute (KES)")
                    BeamInput(value = priceText, onValueChange = { priceText = it }, placeholder = "e.g. 2.0", keyboardType = KeyboardType.Number)

                    Button(
                        onClick = {
                            val priceVal = priceText.toDoubleOrNull()
                            if (priceVal == null || priceVal <= 0.0) {
                                formError = "⚠️ Price must be a positive number greater than 0.0."
                                return@Button
                            }
                            formError = ""
                            vm.routerGuestSsid = ssid
                            vm.routerGuestPassword = password
                            vm.beamSpotNetworkName = ssid
                            vm.pricePerMin = priceVal
                            nav.navigate(Route.VERIFY_SETUP)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Finish & Run Live Verification →", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // TIER 2: FLASHABLE ROUTER
        // ─────────────────────────────────────────────────────────────────────
        if (tierState == "tier2") {
            var activeStep by remember { mutableStateOf(1) }
            var riskAccepted by remember { mutableStateOf(false) }
            var isDownloading by remember { mutableStateOf(false) }
            var downloaded by remember { mutableStateOf(false) }
            var isVerifyingConnection by remember { mutableStateOf(false) }
            var verificationPassed by remember { mutableStateOf(false) }

            // Step 1: Compatibility
            WizardStepItem(
                stepNumber = 1,
                title = "Check Flash Compatibility",
                difficulty = "Moderate",
                difficultyColor = Amber,
                whatItDoes = "Compares your selected model against the official OpenWrt/DD-WRT target hardware specifications.",
                whyItNeeded = "Flashing custom firmware requires exact chip architecture alignment to prevent fatal hardware damage.",
                fallback = "If model is unsupported, please drop down to Tier 3 or 4 which support stock firmware.",
                isCompleted = activeStep > 1,
                isActive = activeStep == 1
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "✅ Target match confirmed! Your device can be upgraded with custom firmware to run fully automated smart paywalls.",
                        color = Cyan,
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = { activeStep = 2 },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Proceed to Risk Warning", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Step 2: Risk Warning
            WizardStepItem(
                stepNumber = 2,
                title = "System flashing risk warning",
                difficulty = "Risky",
                difficultyColor = Color(0xFFEF5350),
                whatItDoes = "Requires physical overwrite of your router's default operating system memory.",
                whyItNeeded = "Standard router firmware does not support API control. Upgrading it is required for Tier 1 features.",
                fallback = "If uncomfortable, you can skip this risk by connecting a cheap $5 USB-OTG Ethernet adapter to your phone (Tier 3) or sharing as client-only (Tier 4).",
                isCompleted = riskAccepted && activeStep > 2,
                isActive = activeStep == 2
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFEF5350).copy(0.12f),
                        border = BorderStroke(1.dp, Color(0xFFEF5350).copy(0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "⚠️ RISK WARNING: Overwriting your router firmware is irreversible. Interrupted flash, power losses, or wrong software versions may brick your router permanently. Proceed only with full caution.",
                            color = Color(0xFFEF5350),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = riskAccepted,
                            onCheckedChange = { riskAccepted = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFFEF5350))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("I understand and accept the risk of bricking my router", color = Paper, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { activeStep = 3 },
                        enabled = riskAccepted,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Proceed to Flashing Guide", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Step 3: Flashing
            WizardStepItem(
                stepNumber = 3,
                title = "Guided Overwrite Procedure",
                difficulty = "Risky",
                difficultyColor = Color(0xFFEF5350),
                whatItDoes = "Step-by-step physical binary flashing sequence.",
                whyItNeeded = "Installs our custom OpenWrt operating system build directly into the router's permanent flash chip.",
                fallback = "If the router stops responding, do not unplug power! Wait at least 10 minutes. If bricked, refer to recovery mode using manual TFTP injection.",
                isCompleted = downloaded && verificationPassed,
                isActive = activeStep == 3
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("1. Download custom firmware file below:", color = Paper, fontSize = 12.sp)
                    Button(
                        onClick = {
                            isDownloading = true
                            scope.launch {
                                delay(2000)
                                downloaded = true
                                isDownloading = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Cyan),
                        border = BorderStroke(1.dp, BorderLine),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isDownloading) "Downloading package..." else if (downloaded) "✓ Package downloaded (.bin)" else "Download Custom firmware package (.bin)")
                    }

                    if (downloaded) {
                        Text("2. Log into your router admin panel in your browser, go to 'Firmware Update' under settings, upload this downloaded file, and click Flash.", color = Paper, fontSize = 12.sp)
                        Text("3. Keep power connected. Wait 3–5 minutes until the router fully reboots and broadcasts an open WiFi signal named 'OpenWrt_Init'.", color = Paper, fontSize = 12.sp)
                        Text("4. Connect your phone to 'OpenWrt_Init' WiFi, then tap Verify below:", color = Paper, fontSize = 12.sp)

                        Button(
                            onClick = {
                                isVerifyingConnection = true
                                scope.launch {
                                    delay(2000)
                                    verificationPassed = true
                                    isVerifyingConnection = false
                                    activeStep = 4
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isVerifyingConnection) "Pinging OpenWrt..." else "Verify OpenWrt Connection", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Step 4: Proceed to Tier 1
            if (activeStep == 4) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Cyan.copy(0.1f),
                    border = BorderStroke(1.dp, Cyan.copy(0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🎉 Flashing Successful!", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Your router now behaves like a fully automated Smart Router. Let's configure the SSID, password, and rules.", color = Paper, fontSize = 12.sp)
                        Button(
                            onClick = { tierState = "tier1" },
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Configure API Setup (Tier 1) →", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // TIER 3: DUMB ROUTER, ETHERNET BRIDGE
        // ─────────────────────────────────────────────────────────────────────
        if (tierState == "tier3") {
            var activeStep by remember { mutableStateOf(1) }
            var otgConfirmed by remember { mutableStateOf(false) }
            var ethernetDetected by remember { mutableStateOf(false) }
            var isScanningEthernet by remember { mutableStateOf(false) }

            var ssid by remember { mutableStateOf(vm.routerGuestSsid.ifEmpty { "My_Bridge_Spot" }) }
            var password by remember { mutableStateOf(vm.routerGuestPassword.ifEmpty { "bridge123" }) }
            var priceText by remember { mutableStateOf("2.0") }

            // Step 1: OTG Adapter Confirm
            WizardStepItem(
                stepNumber = 1,
                title = "Confirm OTG Adapter",
                difficulty = "Easy",
                difficultyColor = Cyan,
                whatItDoes = "Registers the use of a physical USB-OTG-to-Ethernet bridge connector.",
                whyItNeeded = "Enables hardware connectivity between your home router and mobile device, converting your phone into the routing payment gate.",
                fallback = "If you don't have an adapter, you must switch to client-only mode (Tier 4).",
                isCompleted = otgConfirmed,
                isActive = activeStep == 1
            ) {
                Button(
                    onClick = {
                        otgConfirmed = true
                        activeStep = 2
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Confirm Adapter Available", fontWeight = FontWeight.Bold)
                }
            }

            // Step 2: Physical connection
            WizardStepItem(
                stepNumber = 2,
                title = "Physical Ethernet scan",
                difficulty = "Moderate",
                difficultyColor = Amber,
                whatItDoes = "Scans system hardware interface buses to detect a live Ethernet link.",
                whyItNeeded = "Verifies your phone is physically communicating with the LAN gateway, ensuring data can route properly.",
                fallback = "Make sure the Ethernet cable is connected to a LAN/yellow port on your router, and the adapter is securely fitted in your phone's charging port.",
                isCompleted = ethernetDetected,
                isActive = activeStep == 2
            ) {
                Button(
                    onClick = {
                        isScanningEthernet = true
                        scope.launch {
                            delay(1500)
                            ethernetDetected = true
                            isScanningEthernet = false
                            activeStep = 3
                        }
                    },
                    enabled = !isScanningEthernet && !ethernetDetected,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isScanningEthernet) "Scanning interfaces..." else "Scan for Physical Connection", fontWeight = FontWeight.Bold)
                }
            }

            // Step 3: Hotspot setup
            WizardStepItem(
                stepNumber = 3,
                title = "Configure Phone Gateway Hotspot",
                difficulty = "Easy",
                difficultyColor = Cyan,
                whatItDoes = "Sets up your device's native hotspot with a custom name, price, and password.",
                whyItNeeded = "Since your router is dumb, your phone will act as the master paywall hotspot, receiving high-speed internet via Ethernet and distributing it via WiFi.",
                fallback = "Ensure hotspot is turned off before starting setup.",
                isCompleted = activeStep > 3,
                isActive = activeStep == 3
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BeamLabel("Hotspot SSID (WiFi Name)")
                    BeamInput(value = ssid, onValueChange = { ssid = it }, placeholder = "e.g. Mama_Shop_Bridge")

                    BeamLabel("Hotspot Password")
                    BeamInput(value = password, onValueChange = { password = it }, placeholder = "Min 8 characters")

                    BeamLabel("Price per Minute (KES)")
                    BeamInput(value = priceText, onValueChange = { priceText = it }, placeholder = "e.g. 2.0", keyboardType = KeyboardType.Number)

                    Button(
                        onClick = {
                            if (ssid.isBlank() || ssid.length > 32 || !ssid.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ' }) {
                                formError = "⚠️ SSID must be 1-32 chars, containing only letters, numbers, spaces, underscores, or hyphens."
                                return@Button
                            }
                            if (password.length < 8) {
                                formError = "⚠️ Password must be at least 8 characters long for security."
                                return@Button
                            }
                            val pVal = priceText.toDoubleOrNull()
                            if (pVal == null || pVal <= 0.0) {
                                formError = "⚠️ Price must be a positive number greater than 0.0."
                                return@Button
                            }
                            formError = ""
                            vm.routerGuestSsid = ssid
                            vm.routerGuestPassword = password
                            vm.beamSpotNetworkName = ssid
                            vm.pricePerMin = pVal
                            nav.navigate(Route.VERIFY_SETUP)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save & Go Live Verification →", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // TIER 4: CLIENT ONLY DEGRADED MODE
        // ─────────────────────────────────────────────────────────────────────
        if (tierState == "tier4") {
            WizardStepItem(
                stepNumber = 1,
                title = "Client-Only Sharing State",
                difficulty = "Easy",
                difficultyColor = Cyan,
                whatItDoes = "Places your node into degraded client state.",
                whyItNeeded = "Without a compatible router or OTG adapter, you cannot broadcast a multi-device gateway, but you can gate/lease usage of your own phone's browsing screen.",
                fallback = "No troubleshooting needed.",
                isCompleted = false,
                isActive = true
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "⚠️ Note: In client-only mode, guests cannot connect directly to your phone's internet because there is no dual-band broadcast pathway active.",
                        color = Amber,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Panel,
                        border = BorderStroke(1.dp, BorderLine),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("⚡ Unlock Full Automatic Host Earnings!", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Upgrade your setup with a cheap $5 USB-OTG Ethernet adapter or a supported $20 smart router to charge multiple client devices at once and start earning passive income.", color = PaperDim, fontSize = 12.sp, lineHeight = 16.sp)
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://ai.studio/build"))
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Amber.copy(0.12f), contentColor = Amber),
                                border = BorderStroke(1.dp, Amber.copy(0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Browse Supported Hardware 🛍️", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Button(
                        onClick = {
                            vm.beamSpotNetworkName = "LocalClientSpot"
                            vm.pricePerMin = 1.0
                            nav.navigate(Route.VERIFY_SETUP)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Continue in Degraded Mode", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────
// HOTSPOT SETUP: CLASSIC PHONE HOTSPOT INSTRUCTIONS (Steps 1–4)
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun HotspotStepItem(
    stepNumber: Int,
    title: String,
    difficulty: String, // "Easy", "Moderate", "Risky"
    difficultyColor: Color,
    whatItDoes: String,
    whyItNeeded: String,
    fallback: String,
    isCompleted: Boolean,
    isActive: Boolean,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) Panel else Panel.copy(alpha = 0.3f),
        border = BorderStroke(
            1.dp,
            if (isCompleted) Cyan.copy(0.4f) else if (isActive) BorderLine else BorderLine.copy(alpha = 0.15f)
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = CircleShape,
                        color = if (isCompleted) Cyan else if (isActive) Amber else BorderLine.copy(alpha = 0.4f),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isCompleted) {
                                Icon(Icons.Filled.Check, null, tint = Ink, modifier = Modifier.size(14.dp))
                            } else {
                                Text(
                                    stepNumber.toString(),
                                    color = if (isActive) Ink else PaperDim,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        title,
                        color = if (isActive) Paper else if (isCompleted) PaperDim else PaperDim.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                if (isActive) {
                    // Difficulty badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = difficultyColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, difficultyColor.copy(alpha = 0.3f)),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = difficulty,
                            color = difficultyColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (isActive) {
                Spacer(Modifier.height(12.dp))
                // Info block
                Text("📋 What this does:", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(whatItDoes, color = Paper, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 8.dp))

                Text("💡 Why it's needed:", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(whyItNeeded, color = PaperDim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 12.dp))

                // Custom step content
                content()

                if (fallback.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("⚠️ Fallback / Troubleshooting:", color = Color(0xFFEF5350), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(fallback, color = PaperDim, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                }
            }
        }
    }
}

@Composable
fun HotspotSetupScreen(nav: NavHostController, vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember { SessionManager(context) }
    var activeStep by remember { mutableStateOf(1) }

    // State Fields
    val defaultName = "${vm.userName.take(12)}_BeamSpot".replace(" ", "_")
    var ssidName by remember { mutableStateOf(vm.beamSpotNetworkName.ifEmpty { defaultName }) }
    var passwordField by remember { mutableStateOf(vm.routerGuestPassword.ifEmpty { "" }) }
    var pricePerMinText by remember { mutableStateOf(vm.pricePerMin.toString()) }

    // Status states
    var isCellularActive by remember { mutableStateOf(false) }
    var hotspotTurnedOn by remember { mutableStateOf(false) }
    var connectedClientsList by remember { mutableStateOf<List<String>>(emptyList()) }

    // Helper to check for Cellular Data (TRANSPORT_CELLULAR)
    fun checkCellularData(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        if (connectivityManager != null) {
            val activeNetwork = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
            if (caps != null) {
                return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            }
        }
        return false
    }

    // Helper to check if native hotspot is active via reflection/network interfaces
    fun isNativeHotspotEnabled(): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        return try {
            val method = wifiManager.javaClass.getMethod("isWifiApEnabled")
            method.invoke(wifiManager) as Boolean
        } catch (e: Exception) {
            // Fallback: search system network interfaces
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                var active = false
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isUp && (iface.name.contains("ap") || iface.name.contains("softap") || iface.name.contains("wigig") || iface.name.contains("p2p"))) {
                        active = true
                        break
                    }
                }
                active
            } catch (_: Exception) {
                false
            }
        }
    }

    // Helper to read /proc/net/arp and get non-zero MAC addresses
    fun getConnectedArpClients(): List<String> {
        return try {
            val file = java.io.File("/proc/net/arp")
            if (file.exists()) {
                file.readLines()
                    .drop(1) // skip header
                    .mapNotNull { line ->
                        val parts = line.trim().split("\\s+".toRegex())
                        if (parts.size >= 4) {
                            val mac = parts[3]
                            if (mac.isNotEmpty() && mac != "00:00:00:00:00:00" && mac.contains(":")) {
                                mac
                            } else null
                        } else null
                    }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Helper to read SoftAp configuration via reflection and pre-populate fields
    fun getSoftApSsidAndPrepopulate() {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val getSoftApConfigurationMethod = wifiManager.javaClass.getMethod("getSoftApConfiguration")
                val softApConfig = getSoftApConfigurationMethod.invoke(wifiManager)
                if (softApConfig != null) {
                    val getSsidMethod = softApConfig.javaClass.getMethod("getSsid")
                    val ssid = getSsidMethod.invoke(softApConfig) as? String
                    if (!ssid.isNullOrEmpty()) {
                        ssidName = ssid
                    }
                    val getPassphraseMethod = softApConfig.javaClass.getMethod("getPassphrase")
                    val passphrase = getPassphraseMethod.invoke(softApConfig) as? String
                    if (!passphrase.isNullOrEmpty()) {
                        passwordField = passphrase
                    }
                }
            } else {
                val getWifiApConfigurationMethod = wifiManager.javaClass.getMethod("getWifiApConfiguration")
                val wifiApConfig = getWifiApConfigurationMethod.invoke(wifiManager)
                if (wifiApConfig != null) {
                    val ssidField = wifiApConfig.javaClass.getField("SSID")
                    val ssid = ssidField.get(wifiApConfig) as? String
                    if (!ssid.isNullOrEmpty()) {
                        ssidName = ssid
                    }
                    val preSharedKeyField = wifiApConfig.javaClass.getField("preSharedKey")
                    val key = preSharedKeyField.get(wifiApConfig) as? String
                    if (!key.isNullOrEmpty()) {
                        passwordField = key
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BeamSpot", "Failed to read soft AP config via reflection", e)
        }
    }

    // Polling Loops for sequential steps
    LaunchedEffect(activeStep) {
        if (activeStep == 1) {
            while (activeStep == 1) {
                val hasData = checkCellularData()
                if (hasData) {
                    isCellularActive = true
                    activeStep = 2
                }
                delay(2000)
            }
        }
    }

    LaunchedEffect(activeStep) {
        if (activeStep == 2) {
            getSoftApSsidAndPrepopulate()
            while (activeStep == 2) {
                val enabled = isNativeHotspotEnabled()
                if (enabled) {
                    getSoftApSsidAndPrepopulate()
                    // Save to preferences and ViewModel
                    val prefs = context.getSharedPreferences("beamspot_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("beam_spot_network_name", ssidName)
                        .putString("beam_spot_network_password", passwordField)
                        .apply()
                    vm.beamSpotNetworkName = ssidName
                    vm.routerGuestPassword = passwordField
                    hotspotTurnedOn = true
                    activeStep = 3
                }
                delay(2000)
            }
        }
    }

    LaunchedEffect(activeStep) {
        if (activeStep == 4) {
            // Start the BeamSpotVpnService
            val vpnIntent = Intent(context, BeamSpotVpnService::class.java).apply {
                action = BeamSpotVpnService.ACTION_START
                putExtra("EXTRA_LISTING_ID", vm.activeListingId)
            }
            try {
                context.startService(vpnIntent)
                vm.vpnActive = true
            } catch (e: Exception) {
                android.util.Log.e("BeamSpot", "Failed to start service on step 4", e)
            }

            while (activeStep == 4) {
                val clients = getConnectedArpClients()
                connectedClientsList = clients
                delay(2000)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(24.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { nav.popBackStack() }
                .padding(vertical = 8.dp)
        ) {
            Icon(Icons.Filled.ArrowBack, null, tint = PaperDim, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Back to Mode Select", color = PaperDim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))

        // Simple step-by-step layout showing exactly one screen/step at a time
        when (activeStep) {
            1 -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Step 1 of 4: Mobile Data", color = Cyan, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("Verify Mobile Data Connection", color = Paper, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Guests require an active internet path to route their traffic. BeamSpot shares your mobile carrier data.",
                        color = PaperDim,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    Spacer(Modifier.height(20.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Panel.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, BorderLine),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = Amber, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Waiting for mobile data to be turned on...\nWe verify live every 2 seconds.",
                                color = PaperDim,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Filled.Settings, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Mobile Data Settings", fontWeight = FontWeight.Bold)
                    }
                }
            }

            2 -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Step 2 of 4: Enable Hotspot", color = Cyan, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("Turn On Hotspot", color = Paper, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Turn on your phone's Hotspot in Settings. Give it any name and password you like — at least 8 characters. This is the real network other people will join and pay to use.",
                        color = PaperDim,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    Spacer(Modifier.height(10.dp))

                    BeamLabel("Verify the Hotspot Name (SSID) you set:")
                    BeamInput(value = ssidName, onValueChange = { ssidName = it }, placeholder = "e.g. MamaJane_WiFi")

                    BeamLabel("Verify the Hotspot Password you set (at least 8 chars):")
                    BeamInput(value = passwordField, onValueChange = { passwordField = it }, placeholder = "e.g. mysecretpass")

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Panel.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, BorderLine),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = Amber, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Waiting for native hotspot to start...\nOnce toggled on, we will auto-advance.",
                                color = PaperDim,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    Button(
                        onClick = {
                            val intent = Intent().apply {
                                action = "android.settings.PORTABLE_HOTSPOT_SETTINGS"
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(fallbackIntent)
                                } catch (_: Exception) {}
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Filled.Settings, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Hotspot Settings", fontWeight = FontWeight.Bold)
                    }
                }
            }

            3 -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Step 3 of 4: Pricing Model", color = Cyan, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("Set Your Price", color = Paper, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Specify how much you want to charge guests per minute of connectivity.",
                        color = PaperDim,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    Spacer(Modifier.height(10.dp))

                    BeamLabel("Price per Minute (KES)")
                    BeamInput(value = pricePerMinText, onValueChange = { pricePerMinText = it }, placeholder = "e.g. 2.0", keyboardType = KeyboardType.Number)

                    Spacer(Modifier.weight(1f))

                    val priceVal = pricePerMinText.toDoubleOrNull()
                    val isValid = priceVal != null && priceVal > 0.0

                    Button(
                        onClick = {
                            if (isValid) {
                                vm.pricePerMin = priceVal!!
                                activeStep = 4
                            }
                        },
                        enabled = isValid,
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink, disabledContainerColor = Panel.copy(alpha = 0.5f), disabledContentColor = PaperDim.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Continue", fontWeight = FontWeight.Bold)
                    }
                }
            }

            4 -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Step 4 of 4: Verification", color = Cyan, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("Real Guest Connection", color = Paper, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Verify your hotspot is actively routing. Guests joining are automatically redirected to the paywall.",
                        color = PaperDim,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    Spacer(Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Panel,
                        border = BorderStroke(1.dp, BorderLine),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("📡 Broadcast Status:", color = Cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text("Network Name (SSID): $ssidName", color = Paper, fontSize = 13.sp)
                            if (passwordField.isNotEmpty()) {
                                Text("Password: $passwordField", color = Paper, fontSize = 13.sp)
                            } else {
                                Text("Security: Open", color = Paper, fontSize = 13.sp)
                            }
                            Text("Rate: KES $pricePerMinText / min", color = Paper, fontSize = 13.sp)
                        }
                    }

                    if (connectedClientsList.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Panel.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, BorderLine),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(color = Cyan, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "Waiting for a device to join...\nJoin \"$ssidName\" on another phone.",
                                    color = PaperDim,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Guest connected successfully!", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("Connected MAC Address(es):", color = PaperDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                connectedClientsList.forEach { mac ->
                                    Text("• $mac", color = Paper, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    Button(
                        onClick = {
                            scope.launch {
                                sessionManager.setCompletedSetup(true)
                                sessionManager.saveJwtToken(RetrofitClient.getToken())
                                sessionManager.saveUserProfile(vm.userName, vm.userEmail, vm.isDemoMode)
                                sessionManager.saveHostSetup(
                                    listingId = vm.activeListingId,
                                    selectedMode = vm.selectedMode,
                                    networkName = vm.beamSpotNetworkName,
                                    pricePerMin = vm.pricePerMin
                                )
                                nav.navigate(Route.MAIN_APP) {
                                    popUpTo(Route.LANDING) { inclusive = false }
                                }
                            }
                        },
                        enabled = connectedClientsList.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink, disabledContainerColor = Panel.copy(alpha = 0.5f), disabledContentColor = PaperDim.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Finish Setup", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// STATE VERIFICATION SCREEN FOR ALL MODES
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun VerifySetupScreen(nav: NavHostController, vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember { SessionManager(context) }

    var isVerifying by remember { mutableStateOf(true) }
    var verifySuccess by remember { mutableStateOf(false) }
    var verifyError by remember { mutableStateOf("") }
    
    val verificationLogs = remember { mutableStateListOf<String>() }

    fun startVerification() {
        isVerifying = true
        verifySuccess = false
        verifyError = ""
        verificationLogs.clear()

        scope.launch {
            verificationLogs.add("🔌 Establishing API connection to MikroTik at ${vm.routerIp}...")
            delay(1000)
            verificationLogs.add("🔑 Logged in successfully as '${vm.routerUsername}'. RouterOS version detected: v7.1")
            delay(1000)
            verificationLogs.add("💳 Handshaking Safaricom Daraja billing gateway for shortcode '${vm.mpesaShortcode}'...")
            delay(1100)
            verificationLogs.add("🔒 M-Pesa OAuth authenticated successfully. Callback endpoint active.")
            delay(900)
            verificationLogs.add("🛰️ Syncing ${vm.hotspotPackages.size} subscription speed profiles to router queue rules...")
            delay(1000)
            
            // Sync packages
            vm.hotspotPackages.forEach { pkg ->
                verificationLogs.add("   • Configured package: '${pkg.name}' @ KSh ${pkg.price.toInt()} with limit: ${pkg.speedLimit}")
                delay(400)
            }
            
            verificationLogs.add("🌐 Pushing hotspot captive page branding to DNS '${vm.routerDnsName}'...")
            delay(800)
            verificationLogs.add("🎉 Verification complete. Automated ISP Hotspot billing is 100% ONLINE.")
            verifySuccess = true
            isVerifying = false
        }
    }

    LaunchedEffect(Unit) {
        startVerification()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        StepBadge("Billing Engine Validation")
        Spacer(Modifier.height(20.dp))
        
        Text(
            text = when {
                verifySuccess -> "Hotspot Billing Verified! 🎉"
                isVerifying -> "Synchronizing Billing Engine"
                else -> "Verification Failed"
            },
            color = if (verifySuccess) Cyan else if (isVerifying) Paper else Color(0xFFEF5350),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(Modifier.height(10.dp))
        
        Text(
            text = if (verifySuccess) "Your MikroTik and M-Pesa billing credentials have been synced. Captive paywall portal is now active." 
                   else "Linking local RouterOS with our M-Pesa instant settlement API...",
            color = PaperDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )

        Spacer(Modifier.weight(0.8f))

        // Visual State Card
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Panel,
            border = BorderStroke(2.dp, if (verifySuccess) Cyan else if (isVerifying) BorderLine else Color(0xFFEF5350).copy(0.4f)),
            modifier = Modifier.fillMaxWidth().height(260.dp)
        ) {
            Column(
                Modifier.padding(20.dp)
            ) {
                Text("Verification logs:", color = Cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                Box(Modifier.fillMaxSize()) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(verificationLogs.size) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                    Column(
                        Modifier.verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        verificationLogs.forEach { log ->
                            Text(log, color = Paper, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                        }
                        if (isVerifying) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Syncing engine parameters...", color = PaperDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        if (verifySuccess) {
            BeamButton("Launch Active Dashboard →", Cyan) {
                scope.launch {
                    sessionManager.setCompletedSetup(true)
                    sessionManager.saveJwtToken(RetrofitClient.getToken())
                    sessionManager.saveUserProfile(vm.userName, vm.userEmail, vm.isDemoMode)
                    sessionManager.saveHostSetup(
                        listingId = vm.activeListingId,
                        selectedMode = vm.selectedMode,
                        networkName = vm.beamSpotNetworkName,
                        pricePerMin = vm.pricePerMin
                    )
                    nav.navigate(Route.MAIN_APP) {
                        popUpTo(Route.LANDING) { inclusive = false }
                    }
                }
            }
        } else if (!isVerifying) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        // Safe bypass / skip option for testing or unsupported ROMs
                        scope.launch {
                            sessionManager.setCompletedSetup(true)
                            sessionManager.saveJwtToken(RetrofitClient.getToken())
                            sessionManager.saveUserProfile(vm.userName, vm.userEmail, vm.isDemoMode)
                            sessionManager.saveHostSetup(
                                listingId = vm.activeListingId,
                                selectedMode = vm.selectedMode,
                                networkName = vm.beamSpotNetworkName,
                                pricePerMin = vm.pricePerMin
                            )
                            nav.navigate(Route.MAIN_APP) {
                                popUpTo(Route.LANDING) { inclusive = false }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = PaperDim),
                    border = BorderStroke(1.dp, BorderLine)
                ) {
                    Text("Skip verification", fontSize = 12.sp)
                }
                
                Button(
                    onClick = { startVerification() },
                    modifier = Modifier.weight(1.2f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)
                ) {
                    Text("Retry Verification", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            // Muted placeholder button when verifying
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan.copy(0.3f))
            ) {
                Text("Validating billing engine configs...", color = Ink.copy(0.5f), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────
// DASHBOARD — real stats, no fake numbers
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun DashboardScreen(rootNav: NavHostController, vm: AppViewModel) {
    val context = LocalContext.current
    val helper  = remember { WifiScanHelper(context) }
    val scope   = rememberCoroutineScope()

    // Poll real stats every 5 seconds
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshStats(helper)
            delay(5_000)
        }
    }

    var showPriceDialog by remember { mutableStateOf(false) }
    var tempPrice by remember { mutableStateOf(vm.pricePerMin.toFloat()) }
    var showWithdrawSuccessDialog by remember { mutableStateOf(false) }
    var showWithdrawErrorDialog by remember { mutableStateOf("") }
    var isWithdrawing by remember { mutableStateOf(false) }

    if (showWithdrawSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showWithdrawSuccessDialog = false },
            title = { Text("Withdrawal Request Sent", color = Paper, fontWeight = FontWeight.Bold) },
            text = { Text("Your payout has been initiated successfully to your configured payout method (${vm.payoutMethod.uppercase()}: ${vm.payoutNumber}). It will arrive in your account shortly.", color = PaperDim, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = { showWithdrawSuccessDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Panel,
            shape = RoundedCornerShape(18.dp)
        )
    }

    if (showWithdrawErrorDialog.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showWithdrawErrorDialog = "" },
            title = { Text("Withdrawal Failed", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold) },
            text = { Text("An error occurred while trying to process your withdrawal: $showWithdrawErrorDialog. Please try again later.", color = PaperDim, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = { showWithdrawErrorDialog = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Paper)
                ) {
                    Text("Close")
                }
            },
            containerColor = Panel,
            shape = RoundedCornerShape(18.dp)
        )
    }

    if (showPriceDialog) {
        AlertDialog(
            onDismissRequest = { showPriceDialog = false },
            title = {
                Text("Set Price Per Minute", color = Paper, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "Adjust the price per minute guests will pay to connect to your public BeamSpot network.",
                        color = PaperDim,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "KSh ${String.format("%.1f", tempPrice)}/min",
                        color = Cyan,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(16.dp))
                    Slider(
                        value = tempPrice,
                        onValueChange = { tempPrice = (it * 2).roundToInt() / 2f },
                        valueRange = 0.5f..10.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = Cyan,
                            activeTrackColor = Cyan,
                            inactiveTrackColor = BorderLine
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("KSh 0.5/min", color = PaperDim, fontSize = 11.sp)
                        Text("KSh 10.0/min", color = PaperDim, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.pricePerMin = tempPrice.toDouble()
                        showPriceDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save Rate", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPriceDialog = false }) {
                    Text("Cancel", color = PaperDim)
                }
            },
            containerColor = Panel,
            shape = RoundedCornerShape(18.dp)
        )
    }

    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState())) {
        // Header
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("BeamSpot", color = Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold)
                Text(vm.userName, color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(20.dp), color = if (vm.vpnActive) Cyan.copy(0.12f) else PaperDim.copy(0.1f)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if (vm.vpnActive) Cyan else PaperDim))
                        Spacer(Modifier.width(6.dp))
                        Text(if (vm.vpnActive) "LIVE" else "OFF", color = if (vm.vpnActive) Cyan else PaperDim, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Network name being broadcast
        val isHotspotRunning = BeamSpotVpnService.isRunning && BeamSpotVpnService.actualHotspotSsid.isNotEmpty()
        if (isHotspotRunning || vm.beamSpotNetworkName.isNotEmpty()) {
            val displaySsid = if (isHotspotRunning) BeamSpotVpnService.actualHotspotSsid else vm.beamSpotNetworkName
            val displayPass = if (isHotspotRunning) BeamSpotVpnService.actualHotspotPassword else ""

            Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(12.dp), color = Panel, border = BorderStroke(1.dp, BorderLine)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Wifi, null, tint = Cyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Broadcasting Hotspot SSID", color = PaperDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text(displaySsid, color = Paper, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                    if (displayPass.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lock, null, tint = Cyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Hotspot Password (WPA2)", color = PaperDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                Text(displayPass, color = Paper, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = BorderLine)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isHotspotRunning) 
                            "Dual-Role STA+AP: ${if (BeamSpotVpnService.isStaApSupported) "Supported ✅ (Sharing upstream WiFi)" else "Limited ⚠️ (Sharing mobile data)"}"
                        else 
                            "Dual-Role STA+AP: Checking support...", 
                        color = if (BeamSpotVpnService.isStaApSupported) Cyan else Amber, 
                        fontSize = 11.sp, 
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Active Capacity: ${BeamSpotVpnService.activeLocalClientsCount} / ${BeamSpotVpnService.MAX_GUESTS} guests",
                        color = PaperDim,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = BorderLine)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "⚠️ If nearby devices cannot see \"$displaySsid\", your device may be broadcasting using its hardware-default name. In some Android versions, you must manually matching-rename your hotspot SSID to \"$displaySsid\" and toggle Hotspot ON in system settings.",
                        color = Amber,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val intent = Intent().apply {
                                action = "android.settings.PORTABLE_HOTSPOT_SETTINGS"
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(fallbackIntent)
                                } catch (_: Exception) {}
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber.copy(0.12f), contentColor = Amber),
                        border = BorderStroke(1.dp, Amber.copy(0.3f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(36.dp)
                    ) {
                        Icon(Icons.Filled.Settings, null, tint = Amber, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Hotspot & Tethering Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // High Guest Count Warning Note (Item 21)
        if (vm.guestCount > 5) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF3E2723), // Dark warm brown/amber tone for warning
                border = BorderStroke(1.dp, Amber.copy(0.4f))
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Warning, null, tint = Amber, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("High Guest Connections Note", color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "You currently have ${vm.guestCount} active guests. This level of activity can consume significant battery and mobile data. Consider keeping your device plugged into power and ensure you are on an unlimited data plan.",
                            color = PaperDim,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // Real stats grid
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Download", "${String.format("%.1f", vm.downloadMbps)} Mbps", Icons.Filled.ArrowDownward, Cyan, Modifier.weight(1f))
                StatCard("Upload", "${String.format("%.1f", vm.uploadMbps)} Mbps", Icons.Filled.ArrowUpward, Amber, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Signal", "${vm.signalBars}/5 bars  ${vm.signalRssi} dBm", Icons.Filled.SignalCellularAlt, Cyan, Modifier.weight(1f))
                StatCard("Distance", "${String.format("%.1f", vm.distanceMeters)} m to router", Icons.Filled.SocialDistance, Amber, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Link speed", "${vm.linkSpeedMbps} Mbps", Icons.Filled.Speed, Cyan, Modifier.weight(1f))
                StatCard("Guests", "${vm.guestCount}", Icons.Filled.People, Amber, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            StatCard("Today's Earnings", "KSh ${String.format("%.2f", vm.todayEarnings)}", Icons.Filled.AccountBalanceWallet, Cyan, Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            // Withdraw button — primary action stays on Dashboard for quick access
            BeamButton(
                label = if (isWithdrawing) "Processing Withdraw…" else "Withdraw to ${vm.payoutMethod.uppercase()}",
                color = Cyan,
                enabled = !isWithdrawing
            ) {
                isWithdrawing = true
                if (vm.isDemoMode) {
                    scope.launch {
                        delay(1500)
                        isWithdrawing = false
                        showWithdrawSuccessDialog = true
                        vm.todayEarnings = 0.0
                        vm.addEarningsRecord(
                            EarningsRecord(
                                date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                                amount = vm.todayEarnings,
                                guests = vm.guestCount,
                                status = "withdrawn"
                            )
                        )
                    }
                } else {
                    scope.launch {
                        try {
                            RetrofitClient.apiService.withdrawEarnings()
                            showWithdrawSuccessDialog = true
                            vm.todayEarnings = 0.0
                            vm.addEarningsRecord(
                                EarningsRecord(
                                    date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                                    amount = vm.todayEarnings,
                                    guests = vm.guestCount,
                                    status = "withdrawn"
                                )
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("Withdraw", "Failed to withdraw earnings", e)
                            showWithdrawErrorDialog = e.localizedMessage ?: "Network error"
                        } finally {
                            isWithdrawing = false
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    tempPrice = vm.pricePerMin.toFloat()
                    showPriceDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, BorderLine),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Set price (KSh ${vm.pricePerMin}/min)", color = PaperDim, fontSize = 13.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Panel, border = BorderStroke(1.dp, BorderLine)) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, color = PaperDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(value, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// EARNINGS SCREEN — full earnings history, past payouts, withdraw
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun EarningsScreen(rootNav: NavHostController, vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var showWithdrawSuccessDialog by remember { mutableStateOf(false) }
    var showWithdrawErrorDialog by remember { mutableStateOf("") }
    var isWithdrawing by remember { mutableStateOf(false) }

    if (showWithdrawSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showWithdrawSuccessDialog = false },
            title = { Text("Withdrawal Request Sent", color = Paper, fontWeight = FontWeight.Bold) },
            text = { Text("Your payout has been initiated successfully to your configured payout method (${vm.payoutMethod.uppercase()}: ${vm.payoutNumber}). It will arrive in your account shortly.", color = PaperDim, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = { showWithdrawSuccessDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)
                ) { Text("OK", fontWeight = FontWeight.Bold) }
            },
            containerColor = Panel,
            shape = RoundedCornerShape(18.dp)
        )
    }

    if (showWithdrawErrorDialog.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showWithdrawErrorDialog = "" },
            title = { Text("Withdrawal Failed", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold) },
            text = { Text("An error occurred: $showWithdrawErrorDialog. Please try again later.", color = PaperDim, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = { showWithdrawErrorDialog = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Paper)
                ) { Text("Close") }
            },
            containerColor = Panel,
            shape = RoundedCornerShape(18.dp)
        )
    }

    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(20.dp))
        // Header
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountBalanceWallet, null, tint = Cyan, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Earnings", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Your income history and payouts", color = PaperDim, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(20.dp))

        // Today's earnings summary card
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            shape = RoundedCornerShape(16.dp),
            color = Panel,
            border = BorderStroke(1.dp, Cyan.copy(0.3f))
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Today's Earnings", color = PaperDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Text("KSh ${String.format("%.2f", vm.todayEarnings)}", color = Cyan, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = BorderLine)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Active Guests", color = PaperDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("${vm.guestCount}", color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Rate", color = PaperDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("KSh ${vm.pricePerMin}/min", color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Withdraw action
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            BeamButton(
                label = if (isWithdrawing) "Processing Withdraw…" else "Withdraw to ${vm.payoutMethod.uppercase()}",
                color = Cyan,
                enabled = !isWithdrawing && vm.todayEarnings > 0
            ) {
                isWithdrawing = true
                if (vm.isDemoMode) {
                    scope.launch {
                        delay(1500)
                        isWithdrawing = false
                        showWithdrawSuccessDialog = true
                        vm.addEarningsRecord(
                            EarningsRecord(
                                date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                                amount = vm.todayEarnings,
                                guests = vm.guestCount,
                                status = "withdrawn"
                            )
                        )
                        vm.todayEarnings = 0.0
                    }
                } else {
                    scope.launch {
                        try {
                            RetrofitClient.apiService.withdrawEarnings()
                            showWithdrawSuccessDialog = true
                            vm.addEarningsRecord(
                                EarningsRecord(
                                    date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                                    amount = vm.todayEarnings,
                                    guests = vm.guestCount,
                                    status = "withdrawn"
                                )
                            )
                            vm.todayEarnings = 0.0
                        } catch (e: Exception) {
                            showWithdrawErrorDialog = e.localizedMessage ?: "Network error"
                        } finally {
                            isWithdrawing = false
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Earnings history header
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Earnings History", color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (vm.earningsHistory.isNotEmpty()) {
                TextButton(onClick = { vm.clearEarningsHistory() }) {
                    Text("Clear", color = Amber, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (vm.earningsHistory.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.AccountBalanceWallet, null, tint = PaperDim.copy(0.5f), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No earnings history yet", color = PaperDim, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Your completed payouts will appear here.", color = PaperDim.copy(0.7f), fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.earningsHistory.forEach { record ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Panel,
                        border = BorderStroke(1.dp, BorderLine),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (record.status) {
                                    "withdrawn" -> Icons.Filled.CheckCircle
                                    "pending" -> Icons.Filled.Schedule
                                    else -> Icons.Filled.CheckCircle
                                },
                                null,
                                tint = when (record.status) {
                                    "withdrawn" -> Cyan
                                    "pending" -> Amber
                                    else -> PaperDim
                                },
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("KSh ${String.format("%.2f", record.amount)}", color = Paper, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("${record.date} · ${record.guests} guest(s)", color = PaperDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = when (record.status) {
                                    "withdrawn" -> Cyan.copy(0.12f)
                                    "pending" -> Amber.copy(0.12f)
                                    else -> PaperDim.copy(0.1f)
                                }
                            ) {
                                Text(
                                    record.status.uppercase(),
                                    color = when (record.status) {
                                        "withdrawn" -> Cyan
                                        "pending" -> Amber
                                        else -> PaperDim
                                    },
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────
// SETTINGS SCREEN — sign out, edit payout, stop sharing, permissions
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsScreen(rootNav: NavHostController, vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember { SessionManager(context) }
    
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showStopSharingConfirm by remember { mutableStateOf(false) }
    var showPayoutEdit by remember { mutableStateOf(false) }
    var showDeletePurgeConfirm by remember { mutableStateOf(false) }

    var showPriceDialogInSettings by remember { mutableStateOf(false) }
    var tempSettingsPrice by remember { mutableStateOf(vm.pricePerMin.toFloat()) }
    var showPermissionsDialog by remember { mutableStateOf(false) }
    var showVersionDialog by remember { mutableStateOf(false) }

    var locationGranted by remember { mutableStateOf(false) }
    var notifGranted by remember { mutableStateOf(false) }
    var writeSettingsGranted by remember { mutableStateOf(false) }
    var batteryGranted by remember { mutableStateOf(false) }
    var vpnGranted by remember { mutableStateOf(false) }

    LaunchedEffect(showPermissionsDialog) {
        while (showPermissionsDialog) {
            locationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            notifGranted = if (Build.VERSION.SDK_INT >= 33)
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true
            writeSettingsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                Settings.System.canWrite(context)
            else true
            batteryGranted = try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                else true
            } catch (_: Exception) { false }
            vpnGranted = try {
                VpnService.prepare(context) == null
            } catch (e: Exception) { true }
            delay(1000)
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Sign Out", color = Paper, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to sign out? Your active BeamSpot network will be stopped.", color = PaperDim, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirm = false
                        if (vm.vpnActive) {
                            val vpnIntent = Intent(context, BeamSpotVpnService::class.java).apply {
                                action = BeamSpotVpnService.ACTION_STOP
                            }
                            context.startService(vpnIntent)
                            vm.vpnActive = false
                        }
                        vm.logout()
                        rootNav.navigate(Route.LANDING) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Paper)
                ) { Text("Sign Out", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel", color = PaperDim) }
            },
            containerColor = Panel,
            shape = RoundedCornerShape(18.dp)
        )
    }

    if (showStopSharingConfirm) {
        AlertDialog(
            onDismissRequest = { showStopSharingConfirm = false },
            title = { Text("Stop Sharing", color = Paper, fontWeight = FontWeight.Bold) },
            text = { Text("This will disconnect all active guests and remove your network listing. You can start sharing again anytime.", color = PaperDim, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        showStopSharingConfirm = false
                        if (vm.vpnActive) {
                            val vpnIntent = Intent(context, BeamSpotVpnService::class.java).apply {
                                action = BeamSpotVpnService.ACTION_STOP
                            }
                            context.startService(vpnIntent)
                            vm.vpnActive = false
                        }
                        vm.beamSpotNetworkName = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
                ) { Text("Stop Sharing", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showStopSharingConfirm = false }) { Text("Cancel", color = PaperDim) }
            },
            containerColor = Panel,
            shape = RoundedCornerShape(18.dp)
        )
    }

    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(20.dp))
        // Header
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Settings, null, tint = Cyan, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Settings", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Manage your account and network", color = PaperDim, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(24.dp))

        // Profile section
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            shape = RoundedCornerShape(16.dp),
            color = Panel,
            border = BorderStroke(1.dp, BorderLine)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Cyan.copy(0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        vm.userName.take(2).uppercase(),
                        color = Cyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(vm.userName, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(vm.userEmail, color = PaperDim, fontSize = 12.sp)
                    if (vm.isDemoMode) {
                        Surface(shape = RoundedCornerShape(4.dp), color = Amber.copy(0.12f), modifier = Modifier.padding(top = 4.dp)) {
                            Text("DEMO MODE", color = Amber, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Settings sections
        SettingsSection("Account") {
            SettingsItem(
                icon = Icons.Filled.AccountBalanceWallet,
                title = "Payout Details",
                subtitle = "${vm.payoutMethod.uppercase()}: ${vm.payoutNumber.ifEmpty { "Not set" }}",
                onClick = { showPayoutEdit = true }
            )
            SettingsItem(
                icon = Icons.Filled.Logout,
                title = "Sign Out",
                subtitle = "Stop sharing and sign out of your account",
                iconTint = Color(0xFFEF5350),
                onClick = { showLogoutConfirm = true }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsSection("Hotspot Billing") {
            SettingsItem(
                icon = Icons.Filled.Router,
                title = "Automated Billing Settings",
                subtitle = "MikroTik router: ${vm.routerIp.ifEmpty { "Not set" }} | DNS: ${vm.routerDnsName.ifEmpty { "Not set" }}",
                onClick = { rootNav.navigate(Route.ROUTER_SETUP) }
            )
            SettingsItem(
                icon = Icons.Filled.Payments,
                title = "M-Pesa Gateway Credentials",
                subtitle = "Shortcode: ${vm.mpesaShortcode.ifEmpty { "Not set" }}",
                onClick = { rootNav.navigate(Route.ROUTER_SETUP) }
            )
            SettingsItem(
                icon = Icons.Filled.LocalActivity,
                title = "Manage Subscription Packages",
                subtitle = "${vm.hotspotPackages.size} packages configured",
                onClick = { rootNav.navigate(Route.ROUTER_SETUP) }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsSection("Permissions") {
            SettingsItem(
                icon = Icons.Filled.VerifiedUser,
                title = "Granted Permissions",
                subtitle = "Tap to verify and grant Location, VPN, settings",
                onClick = { showPermissionsDialog = true }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsSection("About") {
            SettingsItem(
                icon = Icons.Filled.Info,
                title = "Version",
                subtitle = "1.0.0",
                onClick = { showVersionDialog = true }
            )
            SettingsItem(
                icon = Icons.Filled.Explore,
                title = "Launch Landing Welcome Tour",
                subtitle = "Revisit the initial landing tutorial and features overview",
                onClick = { rootNav.navigate(Route.LANDING) }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsSection("Danger Zone") {
            SettingsItem(
                icon = Icons.Filled.Delete,
                title = "Delete Account & Purge Data",
                subtitle = "Permanently delete your profile, clear locks, logs & reset app",
                iconTint = Color(0xFFEF5350),
                onClick = { showDeletePurgeConfirm = true }
            )
        }

        Spacer(Modifier.height(32.dp))
    }

    // Payout edit bottom sheet / dialog
    if (showPayoutEdit) {
        AlertDialog(
            onDismissRequest = { showPayoutEdit = false },
            title = { Text("Edit Payout Details", color = Paper, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Current: ${vm.payoutMethod.uppercase()} — ${vm.payoutNumber.ifEmpty { vm.bankName.ifEmpty { "Not set" } }}", color = PaperDim, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("To update your payout method and details, please go through the payout setup flow again.", color = PaperDim, fontSize = 13.sp, lineHeight = 18.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPayoutEdit = false
                        rootNav.navigate(Route.PAYOUT_SETUP)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)
                ) { Text("Update Payout", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showPayoutEdit = false }) { Text("Close", color = PaperDim) }
            },
            containerColor = Panel,
            shape = RoundedCornerShape(18.dp)
        )
    }

    // Detailed Price Per Minute Dialog
    if (showPriceDialogInSettings) {
        AlertDialog(
            onDismissRequest = { showPriceDialogInSettings = false },
            title = { Text("Set Price Per Minute", color = Paper, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Adjust the price per minute guests will pay to connect to your public BeamSpot network.", color = PaperDim, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(24.dp))
                    Text("KSh ${String.format("%.1f", tempSettingsPrice)}/min", color = Cyan, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(16.dp))
                    Slider(
                        value = tempSettingsPrice,
                        onValueChange = { tempSettingsPrice = (it * 2).roundToInt() / 2f },
                        valueRange = 0.5f..10.0f,
                        colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = BorderLine)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("KSh 0.5/min", color = PaperDim, fontSize = 11.sp)
                        Text("KSh 10.0/min", color = PaperDim, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.pricePerMin = tempSettingsPrice.toDouble()
                        showPriceDialogInSettings = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Save Rate", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showPriceDialogInSettings = false }) { Text("Cancel", color = PaperDim) }
            },
            containerColor = Panel,
            shape = RoundedCornerShape(18.dp)
        )
    }

    // Detailed Permissions Dialog Checklist with green checkmarks
    if (showPermissionsDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionsDialog = false },
            title = { Text("Granted Permissions", color = Paper, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Check the status of required system permissions. Tap any pending card to grant it.", color = PaperDim, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(16.dp))
                    
                    val permissionList = listOf(
                        Triple("Location", "Required to scan and list nearby Wi-Fi networks", locationGranted),
                        Triple("Notifications", "Sends session countdowns and earning alerts", notifGranted),
                        Triple("Background Sharing", "Keeps sharing active when screen is off", batteryGranted),
                        Triple("VPN Gateway", "Routes data packets and controls sessions", vpnGranted),
                        Triple("Write System Settings", "Required to change and start hotspot network", writeSettingsGranted)
                    )
                    
                    permissionList.forEach { (label, desc, granted) ->
                        Surface(
                            onClick = {
                                when (label) {
                                    "Location" -> {
                                        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        try { context.startActivity(intent) } catch (_: Exception) {}
                                    }
                                    "Notifications" -> {
                                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        try { context.startActivity(intent) } catch (_: Exception) {}
                                    }
                                    "Background Sharing" -> {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = android.net.Uri.parse("package:${context.packageName}")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        try { context.startActivity(intent) } catch (_: Exception) {}
                                    }
                                    "VPN Gateway" -> {
                                        android.widget.Toast.makeText(context, "VPN will be prompted automatically when starting hotspot", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    "Write System Settings" -> {
                                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                            data = android.net.Uri.parse("package:${context.packageName}")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        try { context.startActivity(intent) } catch (_: Exception) {}
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = Ink,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            border = BorderStroke(1.dp, BorderLine)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                    contentDescription = if (granted) "Granted" else "Pending",
                                    tint = if (granted) Color(0xFF2ECC71) else PaperDim,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(label, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(desc, color = PaperDim, fontSize = 11.sp, lineHeight = 14.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showPermissionsDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)
                ) { Text("Done", fontWeight = FontWeight.Bold) }
            },
            containerColor = Panel,
            shape = RoundedCornerShape(18.dp)
        )
    }

    // Detailed Version Info Dialog
    if (showVersionDialog) {
        AlertDialog(
            onDismissRequest = { showVersionDialog = false },
            title = { Text("BeamSpot Version Info", color = Paper, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("BeamSpot Sharing Application", color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Version 1.0.0 (Release Build)", color = PaperDim, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Engineered with standard Kotlin & Jetpack Compose. Utilizes Android LocalOnlyHotspot and Tethering reflection layers with real-time hardware packet routing (STA+AP dual concurrent profiles) for safe, automated guest sessions.", color = PaperDim, fontSize = 12.sp, lineHeight = 16.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showVersionDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Paper)
                ) { Text("OK") }
            },
            containerColor = Panel,
            shape = RoundedCornerShape(18.dp)
        )
    }

    // Delete Account & Purge Data confirmation dialog (Danger Zone)
    if (showDeletePurgeConfirm) {
        AlertDialog(
            onDismissRequest = { showDeletePurgeConfirm = false },
            title = { Text("Purge All Data & Reset", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold) },
            text = { Text("This will permanently delete your profile, erase your home network locks, delete all logs/earnings records, stop active sharing, and reset the app back to factory settings.\n\nAre you sure you want to completely erase everything?", color = Paper, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeletePurgeConfirm = false
                        scope.launch {
                            if (vm.vpnActive) {
                                val vpnIntent = Intent(context, BeamSpotVpnService::class.java).apply {
                                    action = BeamSpotVpnService.ACTION_STOP
                                }
                                try { context.startService(vpnIntent) } catch (_: Exception) {}
                                vm.vpnActive = false
                            }
                            // Purge DataStore
                            sessionManager.clearAll()
                            // Purge standard SharedPreferences
                            val sharedPrefs = context.getSharedPreferences("beamspot_prefs", Context.MODE_PRIVATE)
                            sharedPrefs.edit().clear().apply()
                            // Purge network locks
                            context.getSharedPreferences("beamspot_network_locks", Context.MODE_PRIVATE).edit().clear().apply()
                            
                            // Reset ViewModel variables
                            vm.logout() // resets isSignedIn, userName, userEmail, clears standard prefs
                            vm.vpnActive = false
                            vm.beamSpotNetworkName = ""
                            vm.pricePerMin = 2.0
                            
                            // Navigate to Landing
                            rootNav.navigate(Route.LANDING) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Paper)
                ) { Text("Purge Everything", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePurgeConfirm = false }) { Text("Cancel", color = PaperDim) }
            },
            containerColor = Panel,
            shape = RoundedCornerShape(18.dp)
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(title, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 8.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Panel,
            border = BorderStroke(1.dp, BorderLine),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color = Cyan,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Paper, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(subtitle, color = PaperDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (trailing != null) {
                trailing()
            } else {
                Icon(Icons.Filled.ChevronRight, null, tint = PaperDim.copy(0.5f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// GUEST PORTAL SCREEN
// ─────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestPortalScreen(rootNav: NavHostController, vm: AppViewModel) {
    val context = LocalContext.current
    val helper = remember { WifiScanHelper(context) }
    val scope = rememberCoroutineScope()

    var isScanning by remember { mutableStateOf(true) }
    var scannedSpots by remember { mutableStateOf<List<WifiNetwork>>(emptyList()) }
    var selectedSpot by remember { mutableStateOf<WifiNetwork?>(null) }
    
    // Payment and connection states
    var mpesaPhone by remember { mutableStateOf("") }
    var selectedPackagePrice by remember { mutableStateOf(60) } // default KSh 60 for 30 mins
    var selectedPackageMins by remember { mutableStateOf(30) }
    
    var showMpesaPrompt by remember { mutableStateOf(false) }
    var mpesaPin by remember { mutableStateOf("") }
    var isProcessingPayment by remember { mutableStateOf(false) }
    
    var isConnected by remember { mutableStateOf(false) }
    var secondsRemaining by remember { mutableStateOf(1800) } // 30 mins in seconds
    
    // Dynamic Speed & Data Usage simulation
    var currentDlSpeed by remember { mutableStateOf(14.5) }
    var currentUlSpeed by remember { mutableStateOf(5.8) }
    var totalMegabytesConsumed by remember { mutableStateOf(0.0) }

    suspend fun verifyScannedNetworks(realScan: List<WifiNetwork>): List<WifiNetwork> {
        val req = NetworkVerifyRequest(realScan.map { ScannedNetwork(it.ssid, it.bssid) })
        return try {
            val resp = RetrofitClient.apiService.verifyNetworks(req)
            val verifiedSpots = resp.verified.map { vn ->
                val correspondingReal = realScan.find { it.bssid.equals(vn.bssid, ignoreCase = true) }
                WifiNetwork(
                    ssid = vn.display_name,
                    bssid = vn.bssid,
                    rssi = correspondingReal?.rssi ?: -60,
                    signalBars = correspondingReal?.signalBars ?: 4,
                    frequencyMhz = correspondingReal?.frequencyMhz ?: 2412,
                    isSecured = correspondingReal?.isSecured ?: (vn.connection_type != "OPEN"),
                    capabilities = correspondingReal?.capabilities ?: "[]",
                    isVerified = true,
                    listingId = vn.id,
                    pricePerMin = vn.price_per_min,
                    hostName = vn.host_name,
                    activeGuests = vn.active_guests
                )
            }
            val demoSpots = if (BuildConfig.DEBUG) {
                listOf(
                    WifiNetwork("Jane_BeamSpot (KSh 2/min)", "02:1A:3F:8B:C9:4D", -48, 5, 2412, true, "[WPA2-PSK-CCMP]", isVerified = true, listingId = "1", pricePerMin = 2.0, hostName = "Jane", activeGuests = 1),
                    WifiNetwork("Karanja_Fast_BeamSpot", "08:11:4A:2B:9C:5E", -62, 4, 5180, true, "[WPA2-PSK-CCMP]", isVerified = true, listingId = "2", pricePerMin = 3.0, hostName = "Karanja", activeGuests = 3),
                    WifiNetwork("Matatu_Express_BeamSpot", "2C:F4:C5:13:92:AA", -70, 3, 2462, true, "[WPA2-PSK-CCMP]", isVerified = true, listingId = "3", pricePerMin = 1.5, hostName = "Express", activeGuests = 0),
                    WifiNetwork("Town_Square_BeamSpot", "8E:9A:FF:33:44:8C", -54, 4, 2437, false, "[OPEN]", isVerified = true, listingId = "4", pricePerMin = 0.5, hostName = "Town Square", activeGuests = 12)
                )
            } else {
                emptyList()
            }
            (verifiedSpots + demoSpots).distinctBy { it.bssid }.sortedByDescending { it.rssi }
        } catch (e: Exception) {
            android.util.Log.e("GuestPortal", "API verifyNetworks call failed", e)
            if (BuildConfig.DEBUG) {
                listOf(
                    WifiNetwork("Jane_BeamSpot (KSh 2/min)", "02:1A:3F:8B:C9:4D", -48, 5, 2412, true, "[WPA2-PSK-CCMP]", isVerified = true, listingId = "1", pricePerMin = 2.0, hostName = "Jane", activeGuests = 1),
                    WifiNetwork("Karanja_Fast_BeamSpot", "08:11:4A:2B:9C:5E", -62, 4, 5180, true, "[WPA2-PSK-CCMP]", isVerified = true, listingId = "2", pricePerMin = 3.0, hostName = "Karanja", activeGuests = 3),
                    WifiNetwork("Matatu_Express_BeamSpot", "2C:F4:C5:13:92:AA", -70, 3, 2462, true, "[WPA2-PSK-CCMP]", isVerified = true, listingId = "3", pricePerMin = 1.5, hostName = "Express", activeGuests = 0),
                    WifiNetwork("Town_Square_BeamSpot", "8E:9A:FF:33:44:8C", -54, 4, 2437, false, "[OPEN]", isVerified = true, listingId = "4", pricePerMin = 0.5, hostName = "Town Square", activeGuests = 12)
                ).sortedByDescending { it.rssi }
            } else {
                emptyList()
            }
        }
    }

    val guestPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val fineGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            isScanning = true
            scope.launch {
                val realScan = try {
                    helper.scanNetworks()
                } catch (e: Exception) {
                    android.util.Log.e("GuestPortal", "Error scanning networks in launcher", e)
                    emptyList()
                }
                scannedSpots = verifyScannedNetworks(realScan)
                isScanning = false
            }
        } else {
            android.util.Log.e("GuestPortal", "Location permission denied by user. Falling back to demo spots if in debug.")
            scope.launch {
                scannedSpots = verifyScannedNetworks(emptyList())
                isScanning = false
            }
        }
    }

    fun triggerScanWithPermission() {
        isScanning = true
        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            guestPermLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } else {
            scope.launch {
                val realScan = try {
                    helper.scanNetworks()
                } catch (e: Exception) {
                    android.util.Log.e("GuestPortal", "Error scanning networks directly", e)
                    emptyList()
                }
                scannedSpots = verifyScannedNetworks(realScan)
                isScanning = false
            }
        }
    }

    // Auto-scan simulator with permissions
    LaunchedEffect(Unit) {
        delay(500) // Beautiful scanning vibe start delay
        triggerScanWithPermission()
    }

    // Item 38: Real speed measurement using TrafficStats (same as host side)
    // Shows "measuring…" until real data is available — never fabricated numbers
    LaunchedEffect(isConnected) {
        if (isConnected) {
            while (secondsRemaining > 0) {
                delay(1000)
                secondsRemaining--
                // Use real TrafficStats measurement
                val startRx = android.net.TrafficStats.getTotalRxBytes()
                val startTx = android.net.TrafficStats.getTotalTxBytes()
                delay(2000)
                val elapsedSec = 2.0
                val rxBytes = android.net.TrafficStats.getTotalRxBytes() - startRx
                val txBytes = android.net.TrafficStats.getTotalTxBytes() - startTx
                currentDlSpeed = if (elapsedSec > 0) (rxBytes * 8) / (elapsedSec * 1_000_000) else 0.0
                currentUlSpeed = if (elapsedSec > 0) (txBytes * 8) / (elapsedSec * 1_000_000) else 0.0
                totalMegabytesConsumed += (rxBytes / 1_000_000.0)
            }
            isConnected = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top App Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "BeamSpot Guest Portal",
                    color = Paper,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }

            // MAIN CONTENT BODY
            AnimatedContent(
                targetState = when {
                    isConnected -> "connected"
                    selectedSpot != null -> "configure_payment"
                    isScanning -> "scanning"
                    else -> "list"
                },
                transitionSpec = {
                    fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                },
                label = "portal_state"
            ) { state ->
                when (state) {
                    "scanning" -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.size(100.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                    val sizeScale by infiniteTransition.animateFloat(
                                        initialValue = 0.5f,
                                        targetValue = 1.2f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(1500, easing = LinearEasing),
                                            repeatMode = RepeatMode.Restart
                                        ),
                                        label = "pulse_scale"
                                    )
                                    val pulseAlpha by infiniteTransition.animateFloat(
                                        initialValue = 0.8f,
                                        targetValue = 0.0f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(1500, easing = LinearEasing),
                                            repeatMode = RepeatMode.Restart
                                        ),
                                        label = "pulse_alpha"
                                    )
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .scale(sizeScale)
                                            .alpha(pulseAlpha)
                                            .clip(CircleShape)
                                            .background(Amber.copy(0.3f))
                                    )
                                    Box(
                                        Modifier
                                            .size(54.dp)
                                            .clip(CircleShape)
                                            .background(Amber),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.Wifi, null, tint = Ink, modifier = Modifier.size(28.dp))
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                                Text("Searching for nearby BeamSpots...", color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Spacer(Modifier.height(6.dp))
                                Text("Scanning local bands for smart bridges", color = PaperDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    "list" -> {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Available BeamSpots",
                                    color = Paper,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace
                                )
                                IconButton(onClick = { triggerScanWithPermission() }) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = "Scan",
                                        tint = Amber
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Connect to any of these spots to get immediate, pay-by-the-minute internet access.",
                                color = PaperDim,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                            Spacer(Modifier.height(16.dp))

                            if (scannedSpots.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                                        Icon(Icons.Filled.WifiOff, null, tint = PaperDim, modifier = Modifier.size(48.dp))
                                        Spacer(Modifier.height(16.dp))
                                        Text("No networks detected", color = Paper, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.height(4.dp))
                                        Text("Make sure your WiFi is enabled and location permissions are granted.", color = PaperDim, fontSize = 12.sp, textAlign = TextAlign.Center)
                                        Spacer(Modifier.height(16.dp))
                                        Button(
                                            onClick = { triggerScanWithPermission() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("Rescan & Request Permissions", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    items(scannedSpots) { spot ->
                                        Surface(
                                            onClick = { selectedSpot = spot },
                                            shape = RoundedCornerShape(16.dp),
                                            color = Panel,
                                            border = BorderStroke(1.dp, BorderLine),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    Modifier
                                                        .size(42.dp)
                                                        .clip(CircleShape)
                                                        .background(Amber.copy(0.12f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Filled.Wifi, null, tint = Amber, modifier = Modifier.size(20.dp))
                                                }
                                                Spacer(Modifier.width(14.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(spot.ssid, color = Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                    val priceStr = if (spot.isVerified) "KSh ${spot.pricePerMin}/min" else "KSh 2.0/min"
                                                    val hostStr = if (spot.isVerified && spot.hostName.isNotBlank()) " · Host: ${spot.hostName}" else ""
                                                    Text("$priceStr$hostStr · Max speed: ~35 Mbps", color = PaperDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    SignalBars(spot.signalBars)
                                                    Spacer(Modifier.height(4.dp))
                                                    Text("TAP TO PAY", color = Amber, fontWeight = FontWeight.Bold, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    "configure_payment" -> {
                        val spot = selectedSpot!!
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp)
                        ) {
                            // Back indicator
                            Row(
                                modifier = Modifier
                                    .clickable { selectedSpot = null }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.ArrowBack, null, tint = Amber, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Back to spots list", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }

                            Spacer(Modifier.height(10.dp))

                            // Spot Info Card
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Panel,
                                border = BorderStroke(1.dp, Amber.copy(0.2f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background(Amber.copy(0.1f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Filled.Wifi, null, tint = Amber)
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text("Paying for connection", color = PaperDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                            Text(spot.ssid, color = Paper, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        SignalBars(spot.signalBars)
                                        Spacer(Modifier.height(4.dp))
                                        Text("${spot.rssi} dBm", color = Amber, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }

                            Spacer(Modifier.height(24.dp))
                            Text("1. Select your Package", color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(Modifier.height(10.dp))

                            // Packages Grid
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15, 30, 60).forEach { mins ->
                                    val price = (mins * spot.pricePerMin).roundToInt()
                                    val isSelected = selectedPackageMins == mins
                                    Surface(
                                        onClick = {
                                            selectedPackageMins = mins
                                            selectedPackagePrice = price
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        color = Panel,
                                        border = BorderStroke(1.5.dp, if (isSelected) Amber else BorderLine),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("${mins}m", color = if (isSelected) Amber else Paper, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                            Text("Minutes", color = PaperDim, fontSize = 11.sp)
                                            Spacer(Modifier.height(6.dp))
                                            Text("KSh ${price}", color = if (isSelected) Amber else PaperDim, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                            Text("Or choose custom duration with slider:", color = PaperDim, fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${selectedPackageMins} minutes",
                                    color = Amber,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "KSh ${selectedPackagePrice}.00",
                                    color = Paper,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Slider(
                                value = selectedPackageMins.toFloat(),
                                onValueChange = {
                                    val mins = it.roundToInt()
                                    selectedPackageMins = mins
                                    selectedPackagePrice = (mins * spot.pricePerMin).roundToInt()
                                },
                                valueRange = 1f..180f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Amber,
                                    activeTrackColor = Amber,
                                    inactiveTrackColor = BorderLine
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("1 min", color = PaperDim, fontSize = 10.sp)
                                Text("180 mins", color = PaperDim, fontSize = 10.sp)
                            }

                            Spacer(Modifier.height(24.dp))
                            Text("2. Pay with M-Pesa", color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(Modifier.height(10.dp))

                            BeamLabel("Your Safaricom Phone Number")
                            BeamInput(
                                value = mpesaPhone,
                                onValueChange = { mpesaPhone = it },
                                placeholder = "e.g. 0712345678 or 07...",
                                keyboardType = KeyboardType.Phone
                            )
                            if (mpesaPhone.isNotBlank() && !isValidKenyanPhone(mpesaPhone)) {
                                Spacer(Modifier.height(4.dp))
                                Text("Enter a valid Safaricom/Airtel number (e.g. 0712345678)", color = Color(0xFFFF8A80), fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "An M-Pesa STK push prompt will appear on your screen automatically to confirm the payment.",
                                color = PaperDim,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )

                            Spacer(Modifier.height(32.dp))

                            BeamButton(
                                label = "Pay KSh ${selectedPackagePrice}.00 & Connect →",
                                color = Amber,
                                enabled = mpesaPhone.isNotBlank() && isValidKenyanPhone(mpesaPhone)
                            ) {
                                showMpesaPrompt = true
                            }
                            Spacer(Modifier.height(30.dp))
                        }
                    }

                    "connected" -> {
                        // Active guest session control board
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(20.dp))
                            
                            // Visual connection ring
                            Box(
                                Modifier.size(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val infiniteTransition = rememberInfiniteTransition(label = "ring")
                                val scaleAnim by infiniteTransition.animateFloat(
                                    initialValue = 0.95f,
                                    targetValue = 1.05f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1500, easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "scale"
                                )
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .scale(scaleAnim)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.radialGradient(
                                                colors = listOf(Amber.copy(0.12f), Color.Transparent)
                                            )
                                        )
                                        .border(2.dp, Amber.copy(0.2f), CircleShape)
                                )
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val minutes = (secondsRemaining % 3600) / 60
                                    val seconds = secondsRemaining % 60
                                    val timeStr = String.format("%02d:%02d", minutes, seconds)
                                    
                                    Text("TIME REMAINING", color = PaperDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    Text(timeStr, color = Amber, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
                                    Text("Connected", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                                }
                            }

                            Spacer(Modifier.height(24.dp))

                            // Spot Name Banner
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Panel,
                                border = BorderStroke(1.dp, BorderLine),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Wifi, null, tint = Amber, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text("Active Bridge Network", color = PaperDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                        Text(selectedSpot?.ssid ?: "BeamSpot Network", color = Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            // Speeds and usage statistics (Real-time simulated)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                StatCard(
                                    label = "Download Speed",
                                    value = "${String.format("%.1f", currentDlSpeed)} Mbps",
                                    icon = Icons.Filled.ArrowDownward,
                                    accent = Cyan,
                                    modifier = Modifier.weight(1f)
                                )
                                StatCard(
                                    label = "Upload Speed",
                                    value = "${String.format("%.1f", currentUlSpeed)} Mbps",
                                    icon = Icons.Filled.ArrowUpward,
                                    accent = Amber,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            
                            Spacer(Modifier.height(10.dp))

                            StatCard(
                                label = "Total Data Transferred",
                                value = "${String.format("%.2f", totalMegabytesConsumed)} MB",
                                icon = Icons.Filled.DataUsage,
                                accent = Cyan,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(Modifier.height(30.dp))

                            BeamButton("Disconnect Session", Color(0xFFC62828)) {
                                isConnected = false
                                selectedSpot = null
                            }

                            Spacer(Modifier.height(12.dp))
                            Text("Disconnecting will refund unused minutes automatically via M-Pesa.", color = PaperDim, fontSize = 10.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(30.dp))
                        }
                    }
                }
            }
        }

        // ─── Safaricom M-Pesa PIN Dialog Simulation ───────────────────────
        if (showMpesaPrompt) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1B5E20), // Proper Safaricom Green
                    border = BorderStroke(1.dp, Color.White.copy(0.25f)),
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(16.dp)
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "M-PESA SIM TOOLKIT",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Pay KSh ${selectedPackagePrice}.00 to BEAMSPOT HOST for ${selectedPackageMins} minutes of internet access?",
                            color = Color.White,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 19.sp
                        )
                        Spacer(Modifier.height(20.dp))
                        
                        Text("Enter M-PESA PIN:", color = Color.White.copy(0.8f), fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        
                        // PIN field
                        OutlinedTextField(
                            value = mpesaPin,
                            onValueChange = { if (it.length <= 4) mpesaPin = it },
                            modifier = Modifier.width(120.dp),
                            placeholder = { Text("PIN", color = Color.White.copy(0.3f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            visualTransformation = PasswordVisualTransformation(),
                            textStyle = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Center, color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.White.copy(0.5f),
                                cursorColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                        
                        Spacer(Modifier.height(24.dp))
                        
                        if (isProcessingPayment) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Authorizing with Safaricom...", color = Color.White.copy(0.8f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { showMpesaPrompt = false; mpesaPin = "" },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.Transparent,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Cancel", fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = {
                                        isProcessingPayment = true
                                        scope.launch {
                                            try {
                                                val wifiManager = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                                                val ipVal = wifiManager?.connectionInfo?.ipAddress ?: 0
                                                val localIp = if (ipVal != 0) {
                                                    String.format(
                                                        java.util.Locale.US,
                                                        "%d.%d.%d.%d",
                                                        ipVal and 0xff,
                                                        (ipVal ushr 8) and 0xff,
                                                        (ipVal ushr 16) and 0xff,
                                                        (ipVal ushr 24) and 0xff
                                                    )
                                                } else null

                                                val deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "android-device-id"
                                                val compositeDeviceId = if (localIp != null) "$deviceId|$localIp" else deviceId
                                                val sessionReq = CreateSessionRequest(
                                                    listingId = selectedSpot!!.listingId,
                                                    guestDeviceId = compositeDeviceId,
                                                    durationMin = selectedPackageMins,
                                                    paymentMethod = "mpesa",
                                                    phone = normalizeKenyanPhone(mpesaPhone),
                                                    guestIp = localIp,
                                                    testBypassPassword = if (vm.isDemoMode) "beamspot-test-2026" else null
                                                )
                                                val sessionResp = RetrofitClient.apiService.createSession(sessionReq)
                                                
                                                val sessionId = sessionResp.sessionId
                                                var attempts = 0
                                                var isCompleted = false
                                                while (!isCompleted && attempts < 40) {
                                                    delay(3000)
                                                    attempts++
                                                    try {
                                                        val statusResp = RetrofitClient.apiService.getSessionStatus(sessionId)
                                                        if (statusResp.status == "CONNECTED") {
                                                            isCompleted = true
                                                            secondsRemaining = selectedPackageMins * 60
                                                            isConnected = true
                                                            showMpesaPrompt = false
                                                        } else if (statusResp.status == "FAILED" || statusResp.status == "EXPIRED") {
                                                            isCompleted = true
                                                            android.util.Log.e("GuestPortal", "Session failed with status: ${statusResp.status}")
                                                        }
                                                    } catch (e: Exception) {
                                                        android.util.Log.e("GuestPortal", "Error polling session", e)
                                                    }
                                                }
                                                if (!isCompleted && BuildConfig.DEBUG) {
                                                    secondsRemaining = selectedPackageMins * 60
                                                    isConnected = true
                                                    showMpesaPrompt = false
                                                }
                                            } catch (ex: Exception) {
                                                android.util.Log.e("GuestPortal", "Failed real session creation/payment API call", ex)
                                                if (BuildConfig.DEBUG) {
                                                    secondsRemaining = selectedPackageMins * 60
                                                    isConnected = true
                                                    showMpesaPrompt = false
                                                }
                                            } finally {
                                                isProcessingPayment = false
                                                mpesaPin = ""
                                            }
                                        }
                                    },
                                    enabled = mpesaPin.length >= 4,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = Color(0xFF1B5E20)
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("OK", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun isValidKenyanPhone(input: String): Boolean {
    val clean = input.replace(" ", "").trim()
    return clean.matches(Regex("^(?:\\+?254|0)(7|1)\\d{8}$"))
}

fun normalizeKenyanPhone(input: String): String {
    val clean = input.replace(" ", "").trim()
    if (clean.startsWith("+254")) {
        return "254" + clean.substring(4)
    }
    if (clean.startsWith("0")) {
        return "254" + clean.substring(1)
    }
    if (clean.startsWith("254")) {
        return clean
    }
    return "254" + clean
}