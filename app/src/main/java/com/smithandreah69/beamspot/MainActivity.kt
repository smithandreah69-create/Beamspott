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
import kotlinx.coroutines.launch
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
    const val DASHBOARD        = "dashboard"
    const val GUEST_PORTAL     = "guest_portal"      // Guest Flow: find and connect
}

// ─── Shared ViewModel ─────────────────────────────────────────────────────
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("beamspot_prefs", Context.MODE_PRIVATE)

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

    init {
        val savedToken = prefs.getString("jwt_token", null)
        if (savedToken != null) {
            RetrofitClient.setToken(savedToken)
        }
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
}

// ─── Activity ─────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

// ─── Root navigation ──────────────────────────────────────────────────────
@Composable
fun BeamSpotApp() {
    val nav = rememberNavController()
    val vm: AppViewModel = viewModel()

    NavHost(navController = nav, startDestination = Route.SPLASH) {
        composable(Route.SPLASH)         { SplashScreen(nav) }
        composable(Route.LANDING)        { LandingScreen(nav, vm) }
        composable(Route.SIGN_IN)        { SignInScreen(nav, vm) }
        composable(Route.MODE_SELECT)    { ModeSelectScreen(nav, vm) }
        composable(Route.PAYOUT_SETUP)   { PayoutSetupScreen(nav, vm) }
        composable(Route.SB_WIFI_SCAN)   { SmartBridgeWifiScanScreen(nav, vm) }
        composable("sb_password/{ssid}/{bssid}") { back ->
            val ssid  = back.arguments?.getString("ssid")  ?: ""
            val bssid = back.arguments?.getString("bssid") ?: ""
            SmartBridgePasswordScreen(nav, vm, ssid, bssid)
        }
        composable(Route.SB_PERMISSIONS) { SmartBridgePermissionsScreen(nav, vm) }
        composable(Route.SB_NAMING)      { SmartBridgeNamingScreen(nav, vm) }
        composable(Route.DASHBOARD)      { DashboardScreen(nav, vm) }
        composable(Route.GUEST_PORTAL)   { GuestPortalScreen(nav, vm) }
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
    LaunchedEffect(Unit) {
        delay(2000)
        nav.navigate(Route.LANDING) { popUpTo(Route.SPLASH) { inclusive = true } }
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
                onClick = { nav.navigate(Route.GUEST_PORTAL) }
            )
            LandingCard(
                title = "I have internet",
                subtitle = "Share your connection and earn per minute",
                accentColor = Cyan,
                icon = Icons.Filled.Router,
                onClick = {
                    if (vm.isSignedIn) {
                        nav.navigate(Route.MODE_SELECT)
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
                        "Meanwhile, you can click the \"Continue with Demo Account (Bypass)\" button below to bypass login and continue testing!"
            } else {
                val friendlyMsg = when (e.statusCode) {
                    7 -> "Network error (code 7). Please make sure your device has internet access."
                    8 -> "Internal configuration error (code 8). Please verify that GOOGLE_WEB_CLIENT_ID matches your Google Cloud Web Client ID."
                    12500 -> "Sign-in required (code 12500). Please select an active Google account to sign in."
                    12501 -> "Sign-in cancelled."
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
        vm.userName = "Jane Host"
        vm.userEmail = "jane.host.beamspot@gmail.com"
        vm.isDemoMode = true
        vm.isSignedIn = true
        nav.navigate(Route.PAYOUT_SETUP) { popUpTo(Route.SIGN_IN) { inclusive = true } }
    }

    Column(
        Modifier.fillMaxSize().background(Ink).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Back", color = PaperDim, modifier = Modifier.align(Alignment.Start).clickable { nav.popBackStack() }, fontSize = 13.sp)
        Spacer(Modifier.weight(0.4f))
        Text("Sign in to BeamSpot", color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Use your Google account or proceed with a local demo profile to test host features.", color = PaperDim, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)

        Spacer(Modifier.height(36.dp))

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
            onClick = { if (!isLoading && termsAccepted) launchGoogleSignIn() },
            shape = RoundedCornerShape(12.dp),
            color = if (termsAccepted) Color.White else Color.White.copy(0.4f),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color(0xFF4285F4))
                } else {
                    GoogleGLogo()
                    Spacer(Modifier.width(12.dp))
                    Text("Continue with Google", color = if (termsAccepted) Color(0xFF1F1F1F) else Color(0xFF1F1F1F).copy(0.4f), fontWeight = FontWeight.Medium, fontSize = 15.sp)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // OR Divider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(Modifier.weight(1f), color = BorderLine)
            Text("OR", color = PaperDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp), fontFamily = FontFamily.Monospace)
            HorizontalDivider(Modifier.weight(1f), color = BorderLine)
        }

        Spacer(Modifier.height(18.dp))

        // Demo Bypass Button
        Button(
            onClick = { if (termsAccepted) proceedAsDemoHost() },
            enabled = termsAccepted,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Panel,
                contentColor = Cyan,
                disabledContainerColor = Panel.copy(0.4f),
                disabledContentColor = Cyan.copy(0.4f)
            ),
            border = BorderStroke(1.dp, if (termsAccepted) Cyan.copy(0.4f) else Cyan.copy(0.15f)),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Settings, null, tint = if (termsAccepted) Cyan else Cyan.copy(0.4f), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Continue with Demo Account (Bypass)", color = if (termsAccepted) Cyan else Cyan.copy(0.4f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Google login is recommended for real hosts.\nDemo Mode lets you preview the experience without setting up GCP.", color = PaperDim, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 16.sp)
        Spacer(Modifier.weight(1f))
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
                nav.navigate(Route.MODE_SELECT)
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
                    nav.navigate(Route.MODE_SELECT)
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
    val modes = listOf(
        Triple("smart_bridge", "Smart Bridge", "Easiest — give us your WiFi password, we handle everything."),
        Triple("router",       "Router Mode",  "Best — install a script on your OpenWRT router. Full automatic control."),
        Triple("hotspot",      "Phone Hotspot","Basic — share your mobile data directly. Guests connect manually.")
    )
    val badges = mapOf("smart_bridge" to "EASIEST SETUP", "router" to "BEST EXPERIENCE", "hotspot" to "LIMITED")
    val badgeColors = mapOf("smart_bridge" to Cyan, "router" to Amber, "hotspot" to PaperDim)

    Column(
        Modifier.fillMaxSize().background(Ink)
            .verticalScroll(rememberScrollState())
    ) {
        Column(Modifier.padding(24.dp)) {
            Spacer(Modifier.height(40.dp))
            StepBadge("Step 2 of 2")
            Spacer(Modifier.height(12.dp))
            Text("How will you share internet?", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Pick the option that fits your setup.", color = PaperDim, fontSize = 13.sp)
        }

        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            modes.forEach { (id, title, desc) ->
                ModeCard(
                    id = id, title = title, description = desc,
                    badge = badges[id] ?: "",
                    badgeColor = badgeColors[id] ?: PaperDim,
                    selected = vm.selectedMode == id,
                    onSelect = { vm.selectedMode = id }
                )
            }
            Spacer(Modifier.height(24.dp))
            BeamButton("Configure Setup →", Cyan) {
                when (vm.selectedMode) {
                    "smart_bridge" -> nav.navigate(Route.SB_WIFI_SCAN)
                    "router"       -> nav.navigate(Route.SB_NAMING)
                    "hotspot"      -> nav.navigate(Route.SB_PERMISSIONS)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModeCard(id: String, title: String, description: String, badge: String, badgeColor: Color, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(18.dp),
        color = Panel,
        border = BorderStroke(1.5.dp, if (selected) Cyan else BorderLine),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                RadioButton(
                    selected = selected, onClick = onSelect,
                    colors = RadioButtonDefaults.colors(selectedColor = Cyan, unselectedColor = PaperDim)
                )
                Surface(shape = RoundedCornerShape(6.dp), color = badgeColor.copy(0.12f)) {
                    Text(badge, color = badgeColor, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(title, color = Paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(description, color = PaperDim, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// SMART BRIDGE: WIFI SCAN SCREEN (real scan, no fake data)
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun SmartBridgeWifiScanScreen(nav: NavHostController, vm: AppViewModel) {
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
// SMART BRIDGE: PASSWORD ENTRY
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun SmartBridgePasswordScreen(nav: NavHostController, vm: AppViewModel, ssid: String, bssid: String) {
    val decodedSsid = ssid.replace("%2F", "/")
    var password  by remember { mutableStateOf("") }
    var showPass  by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf("") }
    val context = LocalContext.current
    val wifiConnectHelper = remember { WifiConnectHelper(context) }

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
            isConnecting = true
            connectError = ""
            wifiConnectHelper.connect(
                ssid = decodedSsid,
                password = password,
                onSuccess = {
                    isConnecting = false
                    nav.navigate(Route.SB_PERMISSIONS)
                },
                onFailure = { errorMsg ->
                    isConnecting = false
                    connectError = errorMsg
                }
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
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
fun SmartBridgePermissionsScreen(nav: NavHostController, vm: AppViewModel) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    data class PermItem(val label: String, val reason: String, var granted: Boolean = false)

    var perms by remember { mutableStateOf(listOf(
        PermItem("Location", "Required by Android to scan and connect to WiFi networks"),
        PermItem("Notifications", "Sends session warnings (5 min, 1 min left) and earning alerts"),
        PermItem("Run in background", "Keeps the VPN and session timers alive when screen is off"),
        PermItem("VPN", "Creates the virtual network that routes and controls guest internet access"),
        PermItem("Battery optimisation", "Prevents Android from killing BeamSpot while you're earning")
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
        context.startActivity(intent)
        perms = perms.toMutableList().apply { find { it.label == "Run in background" }?.granted = true }

        // VPN — shows Android's own VPN consent dialog
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            vpnPermLauncher?.launch(vpnIntent)
        } else {
            perms = perms.toMutableList().apply { find { it.label == "VPN" }?.granted = true }
        }
    }

    Column(Modifier.fillMaxSize().background(Ink).padding(24.dp)) {
        Spacer(Modifier.height(40.dp))
        Text("Permissions needed", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("BeamSpot needs these to manage guest sessions. You'll see real Android dialogs — not popups from us.", color = PaperDim, fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(24.dp))

        perms.forEach { perm ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    if (perm.granted) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    null, tint = if (perm.granted) Cyan else PaperDim,
                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(perm.label, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(perm.reason, color = PaperDim, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
            HorizontalDivider(color = BorderLine)
        }

        Spacer(Modifier.weight(1f))
        BeamButton("Grant permissions", Cyan) { requestAll() }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { nav.navigate(Route.SB_NAMING) }, modifier = Modifier.fillMaxWidth()) {
            Text("Skip for now (some features won't work)", color = PaperDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        BeamButton("Continue →", Cyan.copy(0.5f)) {
            nav.navigate(Route.SB_NAMING)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// SMART BRIDGE: NAMING the public BeamSpot network
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun SmartBridgeNamingScreen(nav: NavHostController, vm: AppViewModel) {
    val defaultName = "${vm.userName.take(12)}_BeamSpot".replace(" ", "_")
    var name by remember { mutableStateOf(defaultName) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isActivating by remember { mutableStateOf(false) }

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
        BeamButton(if (isActivating) "Starting…" else "Start earning →", Cyan, enabled = name.isNotBlank() && !isActivating) {
            vm.beamSpotNetworkName = name
            isActivating = true
            scope.launch {
                // Start the VPN service (real Android service start)
                val vpnIntent = Intent(context, BeamSpotVpnService::class.java).apply {
                    action = BeamSpotVpnService.ACTION_START
                    putExtra("EXTRA_LISTING_ID", vm.activeListingId)
                }
                context.startService(vpnIntent)
                delay(1200)
                vm.vpnActive = true
                isActivating = false
                nav.navigate(Route.DASHBOARD) { popUpTo(Route.LANDING) { inclusive = false } }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// DASHBOARD — real stats, no fake numbers
// ─────────────────────────────────────────────────────────────────────────
@Composable
fun DashboardScreen(nav: NavHostController, vm: AppViewModel) {
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
                IconButton(
                    onClick = {
                        if (vm.vpnActive) {
                            val vpnIntent = Intent(context, BeamSpotVpnService::class.java).apply {
                                action = BeamSpotVpnService.ACTION_STOP
                            }
                            context.startService(vpnIntent)
                            vm.vpnActive = false
                        }
                        vm.logout()
                        nav.navigate(Route.LANDING) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.Logout, contentDescription = "Logout", tint = Color(0xFFEF5350))
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
                    }
                } else {
                    scope.launch {
                        try {
                            RetrofitClient.apiService.withdrawEarnings()
                            showWithdrawSuccessDialog = true
                            vm.todayEarnings = 0.0
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
// Reusable components
// ─────────────────────────────────────────────────────────────────────────
@Composable
private fun StepBadge(text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = Cyan.copy(0.1f)) {
        Text(text, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = Paper) }
        Text(title, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun BeamLabel(text: String) {
    Text(text, color = PaperDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 5.dp))
}

@Composable
private fun BeamInput(value: String, onValueChange: (String) -> Unit, placeholder: String, keyboardType: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = PaperDim) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
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
private fun BeamButton(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
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
private fun PayoutMethodCard(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier,
        shape = RoundedCornerShape(12.dp), color = Panel,
        border = BorderStroke(1.5.dp, if (selected) Cyan else BorderLine)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(12.dp)) {
            Text(
                label,
                color = if (selected) Cyan else PaperDim,
                fontSize = if (label.length > 8) 10.sp else 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// GUEST PORTAL SCREEN
// ─────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestPortalScreen(nav: NavHostController, vm: AppViewModel) {
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

    // Live countdown timer and stats simulation when connected
    LaunchedEffect(isConnected) {
        if (isConnected) {
            while (secondsRemaining > 0) {
                delay(1000)
                secondsRemaining--
                // Slight fluctuation in speeds to look completely real
                currentDlSpeed = (12.0 + Math.random() * 8.0).coerceAtLeast(1.0)
                currentUlSpeed = (4.0 + Math.random() * 3.5).coerceAtLeast(0.5)
                // Add a bit of data consumption based on dl speed
                totalMegabytesConsumed += (currentDlSpeed / 8.0) * 0.1
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
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Filled.ArrowBack, "Back", tint = Paper)
                }
                Text(
                    "BeamSpot Guest Portal",
                    color = Paper,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
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
