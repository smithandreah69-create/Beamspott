package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.compose.animation.core.*
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: BeamSpotViewModel = viewModel()
            val isDark = when (vm.currentThemeMode) {
                "Dark" -> true
                "Light" -> false
                else -> isSystemInDarkTheme()
            }
            MyApplicationTheme(darkTheme = isDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(vm, isDark)
                }
            }
        }
    }
}

// Model for Session
data class GuestSession(
    val id: String = UUID.randomUUID().toString(),
    val deviceName: String,
    var minutesRemaining: Int,
    val initialMinutes: Int,
    val mode: String
)

// Screen state representation
sealed class Screen {
    object Landing : Screen()
    object HostLogin : Screen()
    object HostSetupModeSelection : Screen()
    data class SmartBridgeSetup(val step: Int) : Screen()
    data class RouterSetup(val step: Int) : Screen()
    object PhoneHotspotSetup : Screen()
    object HostDashboard : Screen()
    object GuestMain : Screen()
}

class BeamSpotViewModel : ViewModel() {
    // Settings & Theme
    var currentThemeMode by mutableStateOf("System") // "System", "Light", "Dark"

    // Auth state
    var loggedInUserEmail by mutableStateOf<String?>(null)
    var loggedInUserName by mutableStateOf<String?>(null)
    var isNewUser by mutableStateOf(false)

    // Navigation
    var currentScreen by mutableStateOf<Screen>(Screen.Landing)
    private val navigationStack = mutableListOf<Screen>()

    fun navigateTo(screen: Screen) {
        navigationStack.add(currentScreen)
        currentScreen = screen
    }

    fun navigateBack() {
        if (navigationStack.isNotEmpty()) {
            currentScreen = navigationStack.removeAt(navigationStack.size - 1)
        } else {
            currentScreen = Screen.Landing
        }
    }

    // Host configurations
    var hostDisplayName by mutableStateOf("")
    var selectedConnectionMode by mutableStateOf("Smart Bridge") // "Smart Bridge", "Router", "Phone Hotspot"
    var pricePerMinute by mutableStateOf(2.00) // KSh 2.00/min

    // Smart Bridge setup variables
    var bridgeHomeSsid by mutableStateOf("")
    var bridgeHomePassword by mutableStateOf("")
    var bridgeHomePasswordVisible by mutableStateOf(false)
    var bridgeBeamSpotSsid by mutableStateOf("")
    var bridgeTestConnecting by mutableStateOf(false)

    // Router setup variables
    var routerOpenWrtVerified by mutableStateOf(false)
    var routerSsidConnected by mutableStateOf("")
    var routerBeamSpotSsid by mutableStateOf("")
    var routerScriptVerified by mutableStateOf(false)
    var routerScriptConnecting by mutableStateOf(false)

    // Phone hotspot setup variables
    var phoneHotspotWarningChecked by mutableStateOf(false)

    // Payout info
    var payoutMethod by mutableStateOf("M-Pesa") // "M-Pesa", "Airtel Money", "Bank Account"
    var payoutNumber by mutableStateOf("")
    var bankName by mutableStateOf("Equity Bank")
    var bankAccountNum by mutableStateOf("")
    var bankAccountName by mutableStateOf("")

    // Host dashboard statistics
    var isSharingActive by mutableStateOf(false)
    var todayEarnings by mutableStateOf(0.0)
    var pendingWithdrawalBalance by mutableStateOf(0.0)
    var minutesSoldToday by mutableStateOf(0)
    var totalMinutesSold by mutableStateOf(0)
    var totalSessionsSold by mutableStateOf(0)
    var activeGuestsCount by mutableStateOf(0)

    // Active sessions on host side
    val activeSessions = mutableStateListOf<GuestSession>()

    // Guest side variables
    var guestSelectedMinutes by mutableStateOf(60)
    var guestPaymentMethod by mutableStateOf("M-Pesa") // "M-Pesa", "Airtel Money", "Card"
    var guestMpesaNumber by mutableStateOf("")
    var guestAirtelNumber by mutableStateOf("")
    var guestScanning by mutableStateOf(false)
    var isGuestPaymentProcessing by mutableStateOf(false)
    var isGuestSTKPromptOpen by mutableStateOf(false)
    var guestSTKPin by mutableStateOf("")
    var isGuestConnected by mutableStateOf(false)
    var guestConnectedMinutesLeft by mutableStateOf(0) // seconds
    var guestConnectedTotalMinutes by mutableStateOf(0)
    var guestHostName by mutableStateOf("")
    var guestModeConnected by mutableStateOf("")

    // Fired warnings tracker
    var warning5mFired = false
    var warning1mFired = false

    // Bottom navigation index (Host dashboard)
    var activeDashboardTab by mutableStateOf(0) // 0: Dash, 1: QR/Router, 2: Reports, 3: Settings
    var reportsTimePeriod by mutableStateOf("Day") // "Day", "Week", "Month"

    // Initial ticking loop for countdowns
    init {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                // Guest side countdown ticking
                if (isGuestConnected && guestConnectedMinutesLeft > 0) {
                    guestConnectedMinutesLeft -= 1
                    
                    val minsLeft = (guestConnectedMinutesLeft + 59) / 60
                    val secsLeft = guestConnectedMinutesLeft

                    // Warning conditions
                    if (secsLeft == 300 && !warning5mFired) {
                        warning5mFired = true
                    } else if (secsLeft == 60 && !warning1mFired) {
                        warning1mFired = true
                    } else if (secsLeft == 0) {
                        isGuestConnected = false
                    }
                }
            }
        }
    }

    // Simulator controls
    fun simulateFastForward(seconds: Int) {
        if (isGuestConnected) {
            guestConnectedMinutesLeft = (guestConnectedMinutesLeft - seconds).coerceAtLeast(0)
            if (guestConnectedMinutesLeft == 0) {
                isGuestConnected = false
            }
        }
    }
}

// CENTRALIZED SLEEK INTERFACE STYLING HELPERS
object SleekStyle {
    fun hostColor(isDark: Boolean) = if (isDark) Color(0xFF818CF8) else Color(0xFF4F46E5) // Indigo-400 / Indigo-600
    fun guestColor(isDark: Boolean) = if (isDark) Color(0xFFF97316) else Color(0xFFEA580C) // Orange-500 / Orange-600
    fun primaryTextColor(isDark: Boolean) = if (isDark) Color(0xFFF8FAFC) else Color(0xFF1B1B1F) // Slate-50 / Slate-900
    fun mutedTextColor(isDark: Boolean) = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B) // Slate-400 / Slate-500
    fun cardBg(isDark: Boolean) = if (isDark) Color(0xFF1E293B) else Color(0xFFFFFFFF) // Slate-800 / White
    fun cardBorder(isDark: Boolean) = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9) // Slate-700 / Slate-100
    fun pillBg(isDark: Boolean) = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9) // Slate-700 / Slate-100
    fun backgroundColor(isDark: Boolean) = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC) // Slate-900 / Slate-50
}

@Composable
fun AppNavigation(vm: BeamSpotViewModel, isDark: Boolean) {
    AnimatedContent(
        targetState = vm.currentScreen,
        transitionSpec = {
            fadeIn(animationSpec = androidx.compose.animation.core.tween(200)) togetherWith
            fadeOut(animationSpec = androidx.compose.animation.core.tween(200))
        },
        label = "ScreenTransition"
    ) { screen ->
        when (screen) {
            is Screen.Landing -> LandingScreen(vm, isDark)
            is Screen.HostLogin -> HostLoginScreen(vm, isDark)
            is Screen.HostSetupModeSelection -> HostSetupModeSelectionScreen(vm, isDark)
            is Screen.SmartBridgeSetup -> SmartBridgeSetupFlow(vm, screen.step, isDark)
            is Screen.RouterSetup -> RouterSetupFlow(vm, screen.step, isDark)
            is Screen.PhoneHotspotSetup -> PhoneHotspotSetupFlow(vm, isDark)
            is Screen.HostDashboard -> HostDashboardScreen(vm, isDark)
            is Screen.GuestMain -> GuestMainScreen(vm, isDark)
        }
    }
}

// 1. LANDING SCREEN
@Composable
fun LandingScreen(vm: BeamSpotViewModel, isDark: Boolean) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            Toast.makeText(context, "Permissions authorized. Scanning ready!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Location authorized denied. Nearby scanning will be limited.", Toast.LENGTH_LONG).show()
        }
    }

    fun checkAndRequestPermissions(onGranted: () -> Unit) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            onGranted()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE
                )
            )
        }
    }

    LaunchedEffect(Unit) {
        Toast.makeText(context, "BeamSpot scans nearby networks utilizing Location permission.", Toast.LENGTH_LONG).show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "BeamSpot",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1).sp,
                    color = SleekStyle.primaryTextColor(isDark)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(SleekStyle.hostColor(isDark))
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Pay-by-the-minute local internet",
                fontSize = 15.sp,
                color = SleekStyle.mutedTextColor(isDark),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(48.dp))

            // Two entry cards
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = SleekStyle.cardBg(isDark)
                ),
                border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        checkAndRequestPermissions {
                            vm.guestScanning = true
                            vm.navigateTo(Screen.GuestMain)
                        }
                    }
                    .testTag("i_need_internet_card"),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SleekStyle.guestColor(isDark).copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.SignalWifi4Bar,
                            contentDescription = "Guest",
                            tint = SleekStyle.guestColor(isDark),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "I need internet",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekStyle.primaryTextColor(isDark)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Connect nearby with zero passwords.",
                            fontSize = 13.sp,
                            color = SleekStyle.mutedTextColor(isDark)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = SleekStyle.cardBg(isDark)
                ),
                border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        checkAndRequestPermissions {
                            if (vm.loggedInUserEmail != null) {
                                vm.navigateTo(Screen.HostDashboard)
                            } else {
                                vm.navigateTo(Screen.HostLogin)
                            }
                        }
                    }
                    .testTag("i_have_internet_card"),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SleekStyle.hostColor(isDark).copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Router,
                            contentDescription = "Host",
                            tint = SleekStyle.hostColor(isDark),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "I have internet",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekStyle.primaryTextColor(isDark)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Share data/wifi & earn automatic income.",
                            fontSize = 13.sp,
                            color = SleekStyle.mutedTextColor(isDark)
                        )
                    }
                }
            }
        }
    }
}

// 2. MOCK GOOGLE LOGIN SCREEN (CHANGE 1)
@Composable
fun HostLoginScreen(vm: BeamSpotViewModel, isDark: Boolean) {
    var showConsentDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        IconButton(
            onClick = { vm.navigateBack() },
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = SleekStyle.primaryTextColor(isDark)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Host Account Sign-In",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                color = SleekStyle.primaryTextColor(isDark)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Continue with Google to access your BeamSpot dashboard",
                fontSize = 14.sp,
                color = SleekStyle.mutedTextColor(isDark),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(40.dp))

            Button(
                onClick = { showConsentDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDark) Color(0xFF1E293B) else Color.White,
                    contentColor = SleekStyle.primaryTextColor(isDark)
                ),
                shape = RoundedCornerShape(100.dp),
                border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("continue_google_button")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawArc(Color(0xFFEA4335), 180f, 90f, true)
                            drawArc(Color(0xFFFBBC05), 270f, 90f, true)
                            drawArc(Color(0xFF4285F4), 0f, 90f, true)
                            drawArc(Color(0xFF34A853), 90f, 90f, true)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Continue with Google",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showConsentDialog) {
        val context = LocalContext.current
        var typedEmail by remember { mutableStateOf("") }
        var typedName by remember { mutableStateOf("") }
        var emailError by remember { mutableStateOf(false) }

        Dialog(onDismissRequest = { showConsentDialog = false }) {
            var animateTrigger by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                animateTrigger = true
            }
            val scale by animateFloatAsState(
                targetValue = if (animateTrigger) 1f else 0.85f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
            val alpha by animateFloatAsState(
                targetValue = if (animateTrigger) 1f else 0f,
                animationSpec = tween(durationMillis = 200)
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = SleekStyle.cardBg(isDark)
                ),
                shape = RoundedCornerShape(32.dp),
                border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                modifier = Modifier
                    .padding(16.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Sign in with Google",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = SleekStyle.primaryTextColor(isDark),
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Enter your Google credentials to continue to BeamSpot",
                        fontSize = 12.sp,
                        color = SleekStyle.mutedTextColor(isDark),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    OutlinedTextField(
                        value = typedEmail,
                        onValueChange = {
                            typedEmail = it
                            emailError = false
                        },
                        label = { Text("Gmail Address") },
                        placeholder = { Text("your.name@gmail.com") },
                        isError = emailError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (emailError) {
                        Text(
                            text = "A valid Gmail address is required.",
                            color = Color(0xFFEF4444),
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.Start).padding(top = 4.dp, start = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = typedName,
                        onValueChange = { typedName = it },
                        label = { Text("Your Display Name") },
                        placeholder = { Text("Mama Jane") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            val isGmail = typedEmail.trim().lowercase().endsWith("@gmail.com")
                            if (typedEmail.isBlank() || !isGmail) {
                                emailError = true
                            } else {
                                vm.loggedInUserEmail = typedEmail.trim()
                                vm.loggedInUserName = if (typedName.isNotBlank()) typedName.trim() else "Host"
                                vm.hostDisplayName = if (typedName.isNotBlank()) typedName.trim() else "Host"
                                vm.isNewUser = true
                                showConsentDialog = false
                                vm.navigateTo(Screen.HostSetupModeSelection)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SleekStyle.hostColor(isDark),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("Sign In", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://accounts.google.com/signup"))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open browser. Please visit accounts.google.com/signup", Toast.LENGTH_LONG).show()
                            }
                        }
                    ) {
                        Text(
                            text = "Don't have a Gmail account? Create one",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekStyle.hostColor(isDark)
                        )
                    }
                }
            }
        }
    }
}

// 3. HOST SETUP MODE SELECTION (CHANGE 8)
@Composable
fun HostSetupModeSelectionScreen(vm: BeamSpotViewModel, isDark: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Choose Connection Mode",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF1A2925) else Color(0xFFFFFFFF)
                ),
                border = BorderStroke(2.dp, if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        vm.selectedConnectionMode = "Smart Bridge"
                        vm.navigateTo(Screen.SmartBridgeSetup(1))
                    }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SettingsInputHdmi, "Bridge", tint = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Smart Bridge Mode", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isDark) Color(0xFF3FE0C5).copy(alpha = 0.2f) else Color(0xFF1FB89E).copy(alpha = 0.2f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "EASIEST SETUP",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Give us your WiFi password. We create a separate network. Guests pay to join. Fully automatic — no router changes needed.",
                        fontSize = 12.sp,
                        color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Est. Setup: 2 minutes", fontSize = 10.sp, color = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E))
                    Text("• Requirement: Your phone stays on while sharing", fontSize = 10.sp, color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF1A2925) else Color(0xFFFFFFFF)
                ),
                border = BorderStroke(1.dp, if (isDark) Color(0xFF223A33) else Color(0xFFE0E4E3)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        vm.selectedConnectionMode = "Router"
                        vm.navigateTo(Screen.RouterSetup(1))
                    }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Router, "Router", tint = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Router Mode", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .background(Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "RECOMMENDED FOR POWER USERS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Install a small script on your router. Full automatic control. Best speed. Phone doesn't need to stay open.",
                        fontSize = 12.sp,
                        color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Est. Setup: 10 minutes", fontSize = 10.sp, color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614))
                    Text("• Requirement: Compatible router (~KSh 3,500)", fontSize = 10.sp, color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF1A2925) else Color(0xFFFFFFFF)
                ),
                border = BorderStroke(1.dp, if (isDark) Color(0xFF223A33) else Color(0xFFE0E4E3)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        vm.selectedConnectionMode = "Phone Hotspot"
                        vm.navigateTo(Screen.PhoneHotspotSetup)
                    }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PhoneAndroid, "Phone", tint = if (isDark) Color(0xFFFF7A45) else Color(0xFFE55A1F))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Phone Hotspot (Basic)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .background(Color.Red.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "LIMITED ENFORCEMENT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Red
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Share your phone's mobile data. Guests are shown a password after paying and connect manually. No automatic disconnect.",
                        fontSize = 12.sp,
                        color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Est. Setup: 1 minute", fontSize = 10.sp, color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614))
                    Text("• Requirement: Mobile data plan required", fontSize = 10.sp, color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614))
                }
            }
        }
    }
}

// 4. SMART BRIDGE SETUP FLOW (CHANGE 9)
@Composable
fun SmartBridgeSetupFlow(vm: BeamSpotViewModel, step: Int, isDark: Boolean) {
    val coroutineScope = rememberCoroutineScope()
    var showVpnPermissionDialog by remember { mutableStateOf(false) }
    var showExplanationBeforeVpn by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.navigateBack() }) {
                    Icon(Icons.Filled.ArrowBack, "Back")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Smart Bridge Setup (${step}/4)", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))

            when (step) {
                1 -> {
                    Text("Your Home WiFi Details", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "We need these to connect to your internet. They are stored securely on your phone only — never sent to our servers.",
                        fontSize = 12.sp,
                        color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = vm.bridgeHomeSsid,
                        onValueChange = { vm.bridgeHomeSsid = it },
                        label = { Text("WiFi Network Name (SSID)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bridge_ssid_input"),
                        trailingIcon = {
                            Button(
                                onClick = { vm.bridgeHomeSsid = "Safaricom_Home_Fiber_5G" },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDark) Color(0xFF223A33) else Color(0xFFE0E4E3),
                                    contentColor = MaterialTheme.colorScheme.onBackground
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("Scan", fontSize = 10.sp)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = vm.bridgeHomePassword,
                        onValueChange = { vm.bridgeHomePassword = it },
                        label = { Text("WiFi Password") },
                        visualTransformation = if (vm.bridgeHomePasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { vm.bridgeHomePasswordVisible = !vm.bridgeHomePasswordVisible }) {
                                Icon(
                                    imageVector = if (vm.bridgeHomePasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = "Toggle password"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bridge_password_input")
                    )

                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = { vm.navigateTo(Screen.SmartBridgeSetup(2)) },
                        enabled = vm.bridgeHomeSsid.isNotBlank() && vm.bridgeHomePassword.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("bridge_step1_continue"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E),
                            contentColor = Color(0xFF0E1614)
                        )
                    ) {
                        Text("Continue", fontWeight = FontWeight.Bold)
                    }
                }

                2 -> {
                    LaunchedEffect(Unit) {
                        vm.bridgeTestConnecting = true
                        coroutineScope.launch {
                            delay(1800)
                            vm.bridgeTestConnecting = false
                            delay(400)
                            vm.navigateTo(Screen.SmartBridgeSetup(3))
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (vm.bridgeTestConnecting) {
                            CircularProgressIndicator(color = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Connecting to your home WiFi...", fontWeight = FontWeight.Bold)
                            Text("Verifying credentials & checking internet access...", fontSize = 12.sp, color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614))
                        } else {
                            Icon(Icons.Filled.CheckCircle, "Success", tint = Color.Green, modifier = Modifier.size(64.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Connection Test Successful!", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("Connected to ${vm.bridgeHomeSsid}", fontSize = 14.sp)
                        }
                    }
                }

                3 -> {
                    Text("Name Your BeamSpot Network", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "This is the SSID/Name that guests will see in their WiFi networks list. Keep it simple and inviting.",
                        fontSize = 12.sp,
                        color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = vm.bridgeBeamSpotSsid,
                        onValueChange = { vm.bridgeBeamSpotSsid = it },
                        label = { Text("Public Guest network Name") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bridge_network_name_input")
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Guests connect to this network. They cannot access your actual home WiFi or see your credentials.",
                        fontSize = 11.sp,
                        color = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E)
                    )

                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = { vm.navigateTo(Screen.SmartBridgeSetup(4)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("bridge_step3_continue"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E),
                            contentColor = Color(0xFF0E1614)
                        )
                    ) {
                        Text("Continue", fontWeight = FontWeight.Bold)
                    }
                }

                4 -> {
                    Text("Active Smart Bridge Gateways", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "We are ready to start broadcasting your BeamSpot guest network.",
                        fontSize = 12.sp,
                        color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDark) Color(0xFF1A2925) else Color(0xFFFFFFFF)
                        ),
                        border = BorderStroke(1.dp, if (isDark) Color(0xFF223A33) else Color(0xFFE0E4E3)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Permissions Needed", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "To control which guests get internet, BeamSpot needs to manage your phone's network traffic. We only use this to start and stop internet for paying guests. We do not monitor browsing content.",
                                fontSize = 12.sp,
                                color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = { showExplanationBeforeVpn = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("start_bridge_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E),
                            contentColor = Color(0xFF0E1614)
                        )
                    ) {
                        Text("Start Sharing", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showExplanationBeforeVpn) {
        Dialog(onDismissRequest = { showExplanationBeforeVpn = false }) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF1A2925) else Color(0xFFFFFFFF)
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (isDark) Color(0xFF223A33) else Color(0xFFE0E4E3)),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Virtual Gateway Permission",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "To filter unpaid guest devices and route payments securely, we spin up a local VPN routing service. Tap CONTINUE to allow the Android connection prompt.",
                        fontSize = 12.sp,
                        color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { showExplanationBeforeVpn = false }) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                showExplanationBeforeVpn = false
                                showVpnPermissionDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E),
                                contentColor = Color(0xFF0E1614)
                            )
                        ) {
                            Text("Continue")
                        }
                    }
                }
            }
        }
    }

    if (showVpnPermissionDialog) {
        Dialog(onDismissRequest = { showVpnPermissionDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF1A2925) else Color(0xFFFFFFFF)
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (isDark) Color(0xFF223A33) else Color(0xFFE0E4E3)),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "Connection Request",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "BeamSpot wants to set up a VPN connection that allows it to monitor network traffic. Only allow if you trust the source.",
                        fontSize = 12.sp,
                        color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = { showVpnPermissionDialog = false }) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = {
                                showVpnPermissionDialog = false
                                vm.isSharingActive = true
                                vm.navigateTo(Screen.HostDashboard)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E),
                                contentColor = Color(0xFF0E1614)
                            )
                        ) {
                            Text("Allow")
                        }
                    }
                }
            }
        }
    }
}

// 5. ROUTER SETUP SCREEN (CHANGE 8)
@Composable
fun RouterSetupFlow(vm: BeamSpotViewModel, step: Int, isDark: Boolean) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.navigateBack() }) {
                    Icon(Icons.Filled.ArrowBack, "Back")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Router Setup (${step}/4)", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))

            when (step) {
                1 -> {
                    Text("Check Router Compatibility", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Do you have a home router that supports OpenWRT firmware (e.g. GL.iNet Mango, Slate, etc.)?",
                        fontSize = 14.sp,
                        color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            vm.routerOpenWrtVerified = true
                            vm.navigateTo(Screen.RouterSetup(2))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E),
                            contentColor = Color(0xFF0E1614)
                        )
                    ) {
                        Text("Yes, I have one")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            Toast.makeText(context, "Recommended: GL.iNet Mango (~KSh 3,500)", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("I need to buy one")
                    }
                }

                2 -> {
                    Text("Connect to your Router", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Make sure your phone is connected to your router's WiFi right now to proceed.",
                        fontSize = 14.sp,
                        color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614)
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Text("SSID Detected:", fontSize = 12.sp, color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614))
                    Text(vm.routerSsidConnected, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E))
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            vm.navigateTo(Screen.RouterSetup(3))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E),
                            contentColor = Color(0xFF0E1614)
                        )
                    ) {
                        Text("Verify and Continue")
                    }
                }

                3 -> {
                    Text("Name Guest network", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vm.routerBeamSpotSsid,
                        onValueChange = { vm.routerBeamSpotSsid = it },
                        label = { Text("What should guests see?") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            vm.navigateTo(Screen.RouterSetup(4))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E),
                            contentColor = Color(0xFF0E1614)
                        )
                    ) {
                        Text("Generate Setup Script")
                    }
                }

                4 -> {
                    Text("Run Setup Script", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Copy this script, SSH into your router, and paste it to activate the captive portal agent.",
                        fontSize = 12.sp,
                        color = if (isDark) Color(0x9EF0EEE6) else Color(0x800E1614)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Black),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .padding(vertical = 4.dp)
                    ) {
                        Box(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "sh -c \"$(curl -fsSL https://beamspot.com/setup/r_agent_X89A)\"",
                                color = Color.Green,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Row {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Beamspot Script", "sh -c \"$(curl -fsSL https://beamspot.com/setup/r_agent_X89A)\""))
                                Toast.makeText(context, "Copied script to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDark) Color(0xFF223A33) else Color(0xFFE0E4E3),
                                contentColor = MaterialTheme.colorScheme.onBackground
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Copy Script")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (vm.routerScriptConnecting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Pinging router agent endpoint...", fontSize = 12.sp)
                        }
                    } else if (vm.routerScriptVerified) {
                        Text("✓ Router Agent is Live!", color = Color.Green, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = {
                            if (!vm.routerScriptVerified) {
                                vm.routerScriptConnecting = true
                                coroutineScope.launch {
                                    delay(2000)
                                    vm.routerScriptConnecting = false
                                    vm.routerScriptVerified = true
                                }
                            } else {
                                vm.isSharingActive = true
                                vm.navigateTo(Screen.HostDashboard)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) Color(0xFF3FE0C5) else Color(0xFF1FB89E),
                            contentColor = Color(0xFF0E1614)
                        )
                    ) {
                        Text(if (vm.routerScriptVerified) "Go to Dashboard" else "Verify Connection")
                    }
                }
            }
        }
    }
}

// 6. PHONE HOTSPOT SETUP FLOW (CHANGE 3, 8)
@Composable
fun PhoneHotspotSetupFlow(vm: BeamSpotViewModel, isDark: Boolean) {
    val context = LocalContext.current
    var pulsingScale by remember { mutableStateOf(1f) }
    
    // Simple pulsing animation for the broadcasting signal
    LaunchedEffect(Unit) {
        while(true) {
            pulsingScale = 1.15f
            delay(1200)
            pulsingScale = 1.0f
            delay(1200)
        }
    }
    
    val animatedScale by animateFloatAsState(
        targetValue = pulsingScale,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SleekStyle.backgroundColor(isDark))
            .padding(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.navigateBack() }) {
                    Icon(
                        Icons.Filled.ArrowBack, 
                        "Back", 
                        tint = SleekStyle.primaryTextColor(isDark)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Phone Hotspot Setup", 
                    fontWeight = FontWeight.ExtraBold, 
                    fontSize = 22.sp,
                    color = SleekStyle.primaryTextColor(isDark),
                    letterSpacing = (-0.5).sp
                )
            }
            
            Spacer(modifier = Modifier.height(20.dp))

            // Pulse/Bloom Hero Widget
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer blooming radial gradient aura
                Box(
                    modifier = Modifier
                        .size(100.dp * animatedScale)
                        .clip(RoundedCornerShape(100.dp))
                        .background(
                            Color(0xFFFF7A45).copy(alpha = 0.08f)
                        )
                )
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .clip(RoundedCornerShape(100.dp))
                        .background(
                            Color(0xFFFF7A45).copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.WifiTethering,
                        contentDescription = "Hotspot Pulse",
                        tint = Color(0xFFFF7A45),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Configure Public Hotspot Mode", 
                fontWeight = FontWeight.Bold, 
                fontSize = 16.sp,
                color = SleekStyle.primaryTextColor(isDark)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Earn passive income by sharing your phone's cellular data connection directly with nearby guests.",
                fontSize = 12.sp,
                color = SleekStyle.mutedTextColor(isDark)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Sleek list of info points
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SleekStyle.cardBg(isDark).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Key, 
                            "Key", 
                            tint = Color(0xFFFF7A45), 
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Automatic Password Handoff", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = SleekStyle.primaryTextColor(isDark))
                            Text("Paying guests are instantly shown the random password to type and connect.", fontSize = 11.sp, color = SleekStyle.mutedTextColor(isDark))
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = SleekStyle.cardBg(isDark).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Info, 
                            "Info", 
                            tint = SleekStyle.mutedTextColor(isDark), 
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Manual Connection Management", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = SleekStyle.primaryTextColor(isDark))
                            Text("Because Android restricts automated mobile hotspot cutoff, guests connect manually.", fontSize = 11.sp, color = SleekStyle.mutedTextColor(isDark))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Premium Toggle Row
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (vm.phoneHotspotWarningChecked) Color(0xFFFF7A45).copy(alpha = 0.08f) else SleekStyle.cardBg(isDark)
                ),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, if (vm.phoneHotspotWarningChecked) Color(0xFFFF7A45).copy(alpha = 0.3f) else SleekStyle.cardBorder(isDark)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.phoneHotspotWarningChecked = !vm.phoneHotspotWarningChecked }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = vm.phoneHotspotWarningChecked,
                        onCheckedChange = { vm.phoneHotspotWarningChecked = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFFFF7A45),
                            uncheckedColor = SleekStyle.mutedTextColor(isDark)
                        )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "I acknowledge that public hotspot mode requires manual monitoring.", 
                        fontSize = 11.sp, 
                        fontWeight = FontWeight.Medium,
                        color = SleekStyle.primaryTextColor(isDark)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    vm.bridgeBeamSpotSsid = if (vm.hostDisplayName.isNotBlank()) "${vm.hostDisplayName.replace(" ", "")}_Spot" else "BeamSpot_Guest_Network"
                    vm.isSharingActive = true
                    vm.navigateTo(Screen.HostDashboard)
                },
                enabled = vm.phoneHotspotWarningChecked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF7A45),
                    contentColor = Color.White,
                    disabledContainerColor = SleekStyle.cardBorder(isDark),
                    disabledContentColor = SleekStyle.mutedTextColor(isDark)
                )
            ) {
                Text("Start Hotspot Earning", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

// 7. HOST DASHBOARD WITH FOUR TABS (CHANGE 6, 8, 9)
@Composable
fun HostDashboardScreen(vm: BeamSpotViewModel, isDark: Boolean) {
    val context = LocalContext.current
    var showWithdrawSheet by remember { mutableStateOf(false) }
    var editPriceSheet by remember { mutableStateOf(false) }
    var inputPrice by remember { mutableStateOf(vm.pricePerMinute.toString()) }

    val infiniteTransition = rememberInfiniteTransition(label = "sharing_dot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Scaffold(
        bottomBar = {
            Surface(
                border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                color = SleekStyle.cardBg(isDark),
                tonalElevation = 0.dp
            ) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = vm.activeDashboardTab == 0,
                        onClick = { vm.activeDashboardTab = 0 },
                        icon = { Icon(Icons.Filled.Dashboard, "Dash", tint = if (vm.activeDashboardTab == 0) SleekStyle.hostColor(isDark) else SleekStyle.mutedTextColor(isDark)) },
                        label = { Text("Dash", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (vm.activeDashboardTab == 0) SleekStyle.hostColor(isDark) else SleekStyle.mutedTextColor(isDark)) }
                    )
                    NavigationBarItem(
                        selected = vm.activeDashboardTab == 1,
                        onClick = { vm.activeDashboardTab = 1 },
                        icon = { Icon(Icons.Filled.QrCode, "QR", tint = if (vm.activeDashboardTab == 1) SleekStyle.hostColor(isDark) else SleekStyle.mutedTextColor(isDark)) },
                        label = { Text("Share", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (vm.activeDashboardTab == 1) SleekStyle.hostColor(isDark) else SleekStyle.mutedTextColor(isDark)) }
                    )
                    NavigationBarItem(
                        selected = vm.activeDashboardTab == 2,
                        onClick = { vm.activeDashboardTab = 2 },
                        icon = { Icon(Icons.Filled.BarChart, "Reports", tint = if (vm.activeDashboardTab == 2) SleekStyle.hostColor(isDark) else SleekStyle.mutedTextColor(isDark)) },
                        label = { Text("Reports", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (vm.activeDashboardTab == 2) SleekStyle.hostColor(isDark) else SleekStyle.mutedTextColor(isDark)) }
                    )
                    NavigationBarItem(
                        selected = vm.activeDashboardTab == 3,
                        onClick = { vm.activeDashboardTab = 3 },
                        icon = { Icon(Icons.Filled.Settings, "Settings", tint = if (vm.activeDashboardTab == 3) SleekStyle.hostColor(isDark) else SleekStyle.mutedTextColor(isDark)) },
                        label = { Text("Settings", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (vm.activeDashboardTab == 3) SleekStyle.hostColor(isDark) else SleekStyle.mutedTextColor(isDark)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (vm.activeDashboardTab) {
                0 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // SLEEK HEADER BLOCK
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // First initial Avatar
                            val firstChar = if (vm.hostDisplayName.isNotEmpty()) vm.hostDisplayName.first().toString() else "B"
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(
                                        androidx.compose.ui.graphics.Brush.linearGradient(
                                            colors = listOf(Color(0xFF60A5FA), Color(0xFF6366F1))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(firstChar, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = vm.hostDisplayName,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = SleekStyle.primaryTextColor(isDark),
                                    letterSpacing = (-0.5).sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                // Active status badge
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(
                                            if (vm.isSharingActive) {
                                                if (isDark) Color(0xFF022C22) else Color(0xFFECFDF5)
                                            } else {
                                                if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)
                                            },
                                            RoundedCornerShape(100.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (vm.isSharingActive) {
                                                if (isDark) Color(0xFF064E3B) else Color(0xFFD1FAE5)
                                            } else {
                                                if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)
                                            },
                                            RoundedCornerShape(100.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .graphicsLayer(alpha = if (vm.isSharingActive) dotAlpha else 1.0f)
                                            .background(if (vm.isSharingActive) Color(0xFF10B981) else Color(0xFF94A3B8))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (vm.isSharingActive) "ONLINE · SHARING" else "OFFLINE · PAUSED",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.5.sp,
                                        color = if (vm.isSharingActive) {
                                            if (isDark) Color(0xFF34D399) else Color(0xFF047857)
                                        } else {
                                            if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Switch(
                                checked = vm.isSharingActive,
                                onCheckedChange = { vm.isSharingActive = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = SleekStyle.hostColor(isDark),
                                    uncheckedThumbColor = SleekStyle.mutedTextColor(isDark),
                                    uncheckedTrackColor = SleekStyle.cardBorder(isDark)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // SLEEK MODE STATUS CARD (Rounded 32dp, sleek details)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SleekStyle.cardBg(isDark)),
                            border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                            shape = RoundedCornerShape(32.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(24.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(SleekStyle.hostColor(isDark).copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            if (vm.selectedConnectionMode == "Router") Icons.Filled.Router else Icons.Filled.SettingsInputHdmi,
                                            contentDescription = "Mode Icon",
                                            tint = SleekStyle.hostColor(isDark),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "${vm.selectedConnectionMode.uppercase()} MODE",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = 1.sp,
                                            color = SleekStyle.hostColor(isDark)
                                        )
                                        Text(
                                            text = if (vm.selectedConnectionMode == "Router") "Relaying via ${vm.routerBeamSpotSsid}" else "Relaying via ${vm.bridgeBeamSpotSsid}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SleekStyle.primaryTextColor(isDark)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                // Grid of two mini contrast boxes
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(if (isDark) Color(0xFF1E293B) else Color(0xFFF8FAFC))
                                            .border(1.dp, SleekStyle.cardBorder(isDark), RoundedCornerShape(16.dp))
                                            .padding(14.dp)
                                    ) {
                                        Column {
                                            Text("Active Guests", fontSize = 11.sp, color = SleekStyle.mutedTextColor(isDark))
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${vm.activeSessions.size}",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp,
                                                color = SleekStyle.primaryTextColor(isDark)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(if (isDark) Color(0xFF1E293B) else Color(0xFFF8FAFC))
                                            .border(1.dp, SleekStyle.cardBorder(isDark), RoundedCornerShape(16.dp))
                                            .padding(14.dp)
                                    ) {
                                        Column {
                                            Text("Speed Limit", fontSize = 11.sp, color = SleekStyle.mutedTextColor(isDark))
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "5.0 Mbps",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp,
                                                color = SleekStyle.primaryTextColor(isDark)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // SLEEK EARNINGS GRID (Tab 0 stats)
                        Row(modifier = Modifier.fillMaxWidth()) {
                            // Rich Indigo Earnings Card
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 6.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF4F46E5)),
                                shape = RoundedCornerShape(28.dp)
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text("Today's Revenue", fontSize = 11.sp, color = Color(0xFFC7D2FE))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "KSh ${vm.todayEarnings.toInt()}",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 22.sp,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    // Trend badge
                                    Box(
                                        modifier = Modifier
                                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(100.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("↑ 12% vs yesterday", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }

                            // Total Sessions Card
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 6.dp),
                                colors = CardDefaults.cardColors(containerColor = SleekStyle.cardBg(isDark)),
                                border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                                shape = RoundedCornerShape(28.dp)
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text("Total Sessions", fontSize = 11.sp, color = SleekStyle.mutedTextColor(isDark))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${vm.totalSessionsSold}",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 22.sp,
                                        color = SleekStyle.primaryTextColor(isDark)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "${vm.totalMinutesSold} mins sold",
                                        fontSize = 11.sp,
                                        color = SleekStyle.mutedTextColor(isDark)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Sharing rate
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SleekStyle.cardBg(isDark)),
                            border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Your Sharing Rate", fontSize = 11.sp, color = SleekStyle.mutedTextColor(isDark))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("KSh ${vm.pricePerMinute} per minute", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = SleekStyle.primaryTextColor(isDark))
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                TextButton(onClick = { editPriceSheet = true }) {
                                    Text("Edit Rate", color = SleekStyle.hostColor(isDark), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Connected Guests List",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = SleekStyle.primaryTextColor(isDark)
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        if (vm.activeSessions.isEmpty()) {
                            Text("No guests connected right now", color = SleekStyle.mutedTextColor(isDark), fontSize = 14.sp)
                        } else {
                            vm.activeSessions.forEach { session ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = SleekStyle.cardBg(isDark)),
                                    border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(SleekStyle.hostColor(isDark).copy(alpha = 0.1f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Filled.Smartphone,
                                                    contentDescription = "Device",
                                                    tint = SleekStyle.hostColor(isDark),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(session.deviceName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SleekStyle.primaryTextColor(isDark))
                                                Text("MAC: B0:19:C2:AA:7B:D4 · Mode: ${session.mode}", fontSize = 10.sp, color = SleekStyle.mutedTextColor(isDark), fontFamily = FontFamily.Monospace)
                                            }
                                            Spacer(modifier = Modifier.weight(1f))
                                            Text("${session.minutesRemaining}m left", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = SleekStyle.hostColor(isDark))
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        // Sleek compact linear progress indicator
                                        val progress = (session.minutesRemaining.toFloat() / session.initialMinutes.toFloat()).coerceIn(0f, 1f)
                                        LinearProgressIndicator(
                                            progress = progress,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = SleekStyle.hostColor(isDark),
                                            trackColor = SleekStyle.cardBorder(isDark)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Card(
                            colors = CardDefaults.cardColors(containerColor = SleekStyle.cardBg(isDark)),
                            border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text("Pending Withdrawal Balance", fontSize = 11.sp, color = SleekStyle.mutedTextColor(isDark))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("KSh ${vm.pendingWithdrawalBalance.toInt()}", fontWeight = FontWeight.Black, fontSize = 26.sp, color = SleekStyle.primaryTextColor(isDark))
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { showWithdrawSheet = true },
                                    enabled = vm.pendingWithdrawalBalance > 0,
                                    modifier = Modifier.fillMaxWidth().height(50.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SleekStyle.hostColor(isDark),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Withdraw earnings to ${vm.payoutMethod}", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                1 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Connect QR / Hotspot Link",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 22.sp,
                            color = SleekStyle.primaryTextColor(isDark),
                            letterSpacing = (-0.5).sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Guests can scan this QR code to connect instantly",
                            fontSize = 13.sp,
                            color = SleekStyle.mutedTextColor(isDark)
                        )
                        Spacer(modifier = Modifier.height(28.dp))

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(32.dp),
                            border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                            modifier = Modifier
                                .size(230.dp)
                                .padding(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val sizeBlock = size.width / 8f
                                    for (i in 0..7) {
                                        for (j in 0..7) {
                                            if ((i + j) % 2 == 0) {
                                                drawRect(
                                                    color = Color(0xFF0F172A), // Sleek deep slate
                                                    topLeft = androidx.compose.ui.geometry.Offset(i * sizeBlock, j * sizeBlock),
                                                    size = androidx.compose.ui.geometry.Size(sizeBlock, sizeBlock)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(28.dp))

                        Text(
                            text = "Hotspot SSID: ${vm.bridgeBeamSpotSsid}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = SleekStyle.primaryTextColor(isDark)
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Beamspot Connect Link", "https://beamspot.com/connect/${vm.hostDisplayName.replace(" ", "_")}"))
                                Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SleekStyle.hostColor(isDark),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Copy Connect URL", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                2 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    ) {
                        Text(
                            text = "Earning Reports",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 24.sp,
                            color = SleekStyle.primaryTextColor(isDark),
                            letterSpacing = (-0.5).sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(100.dp))
                                .background(SleekStyle.cardBg(isDark))
                                .border(1.dp, SleekStyle.cardBorder(isDark), RoundedCornerShape(100.dp))
                                .padding(4.dp)
                        ) {
                            listOf("Day", "Week", "Month").forEach { period ->
                                val active = vm.reportsTimePeriod == period
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(100.dp))
                                        .clickable { vm.reportsTimePeriod = period }
                                        .background(
                                            if (active) SleekStyle.hostColor(isDark) else Color.Transparent
                                        )
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = period,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (active) Color.White else SleekStyle.mutedTextColor(isDark)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))

                        Card(
                            colors = CardDefaults.cardColors(containerColor = SleekStyle.cardBg(isDark)),
                            border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text("Total Earnings", fontSize = 12.sp, color = SleekStyle.mutedTextColor(isDark))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "KSh ${vm.todayEarnings.toInt()}",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 26.sp,
                                    color = SleekStyle.hostColor(isDark)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider(color = SleekStyle.cardBorder(isDark))
                                Spacer(modifier = Modifier.height(16.dp))
                                Row {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Sessions Sold", fontSize = 11.sp, color = SleekStyle.mutedTextColor(isDark))
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text("${vm.totalSessionsSold}", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = SleekStyle.primaryTextColor(isDark))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Minutes Sold", fontSize = 11.sp, color = SleekStyle.mutedTextColor(isDark))
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text("${vm.totalMinutesSold}", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = SleekStyle.primaryTextColor(isDark))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Daily Breakdown",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            color = SleekStyle.primaryTextColor(isDark)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(SleekStyle.cardBg(isDark))
                                .border(1.dp, SleekStyle.cardBorder(isDark), RoundedCornerShape(24.dp))
                                .padding(16.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            listOf("Mon" to 0.4f, "Tue" to 0.6f, "Wed" to 0.3f, "Thu" to 0.7f, "Fri" to 0.9f, "Sat" to 0.8f, "Sun" to 0.5f).forEach { (day, h) ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .height((100 * h).dp)
                                            .width(18.dp)
                                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                            .background(
                                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                                    colors = listOf(Color(0xFF818CF8), Color(0xFF4F46E5))
                                                )
                                            )
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(day, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SleekStyle.mutedTextColor(isDark))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                val summary = "BeamSpot Report - Period: ${vm.reportsTimePeriod}, Earnings: KSh ${vm.todayEarnings}, Sessions: ${vm.totalSessionsSold}"
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Beamspot Report", summary))
                                Toast.makeText(context, "Report copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0),
                                contentColor = SleekStyle.primaryTextColor(isDark)
                            ),
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Share, contentDescription = "Export", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Export Summary", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                3 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "Settings",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 24.sp,
                            color = SleekStyle.primaryTextColor(isDark),
                            letterSpacing = (-0.5).sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Profile Details",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            color = SleekStyle.primaryTextColor(isDark)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = vm.hostDisplayName,
                            onValueChange = { vm.hostDisplayName = it },
                            label = { Text("Display Name") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Payout Details",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            color = SleekStyle.primaryTextColor(isDark)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = vm.payoutNumber,
                            onValueChange = { vm.payoutNumber = it },
                            label = { Text("M-Pesa Number") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Theme & Appearance",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            color = SleekStyle.primaryTextColor(isDark)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(100.dp))
                                .background(SleekStyle.cardBg(isDark))
                                .border(1.dp, SleekStyle.cardBorder(isDark), RoundedCornerShape(100.dp))
                                .padding(4.dp)
                        ) {
                            listOf("System", "Light", "Dark").forEach { themeOption ->
                                val active = vm.currentThemeMode == themeOption
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(100.dp))
                                        .clickable { vm.currentThemeMode = themeOption }
                                        .background(
                                            if (active) SleekStyle.hostColor(isDark) else Color.Transparent
                                        )
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = themeOption,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (active) Color.White else SleekStyle.mutedTextColor(isDark)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(48.dp))

                        Button(
                            onClick = {
                                vm.loggedInUserEmail = null
                                vm.navigateTo(Screen.Landing)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF4444)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text("Log Out", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showWithdrawSheet) {
        Dialog(onDismissRequest = { showWithdrawSheet = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SleekStyle.cardBg(isDark)),
                shape = RoundedCornerShape(32.dp),
                border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Confirm Withdrawal",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = SleekStyle.primaryTextColor(isDark)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Are you sure you want to withdraw KSh ${vm.pendingWithdrawalBalance.toInt()} directly to your M-Pesa account (${vm.payoutNumber})?",
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = SleekStyle.mutedTextColor(isDark)
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    Row {
                        TextButton(
                            onClick = { showWithdrawSheet = false },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", color = SleekStyle.mutedTextColor(isDark), fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = {
                                showWithdrawSheet = false
                                vm.pendingWithdrawalBalance = 0.0
                                NotificationHelper.showNotification(context, "Withdrawal Successful", "KSh 1480 sent to your M-Pesa.")
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SleekStyle.hostColor(isDark),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Confirm", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (editPriceSheet) {
        Dialog(onDismissRequest = { editPriceSheet = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SleekStyle.cardBg(isDark)),
                shape = RoundedCornerShape(32.dp),
                border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "Edit Price Rate",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = SleekStyle.primaryTextColor(isDark)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = inputPrice,
                        onValueChange = { newValue ->
                            if (newValue.all { it.isDigit() || it == '.' }) {
                                inputPrice = newValue
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("Price per minute (KSh)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row {
                        TextButton(
                            onClick = { editPriceSheet = false },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", color = SleekStyle.mutedTextColor(isDark), fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = {
                                val parsed = inputPrice.toDoubleOrNull()
                                if (parsed != null && parsed > 0.0) {
                                    vm.pricePerMinute = parsed
                                    editPriceSheet = false
                                } else {
                                    Toast.makeText(context, "Please enter a positive rate (greater than 0)!", Toast.LENGTH_LONG).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SleekStyle.hostColor(isDark),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// 8. GUEST SCANNING, PAYMENT & CONNECTION CONTROL SCREEN (CHANGE 4, 5, 10, 11)
@Composable
fun GuestMainScreen(vm: BeamSpotViewModel, isDark: Boolean) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SleekStyle.backgroundColor(isDark))
            .padding(24.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.navigateBack() }) {
                    Icon(
                        Icons.Filled.ArrowBack, 
                        "Back", 
                        tint = SleekStyle.primaryTextColor(isDark)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Legitimate Spot Scan",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp,
                    color = SleekStyle.primaryTextColor(isDark),
                    letterSpacing = (-0.5).sp
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (vm.isGuestConnected) {
                val timeLeftSeconds = vm.guestConnectedMinutesLeft
                val formattedTime = String.format("%02d:%02d", timeLeftSeconds / 60, timeLeftSeconds % 60)
                val countdownCardColor = when {
                    timeLeftSeconds <= 60 -> Color(0xFFEF4444) // Intense Red
                    timeLeftSeconds <= 300 -> Color(0xFFF59E0B) // Amber
                    else -> SleekStyle.hostColor(isDark) // Indigo/Emerald accent
                }

                LaunchedEffect(timeLeftSeconds) {
                    if (timeLeftSeconds == 300) {
                        NotificationHelper.showNotification(context, "BeamSpot warning", "Your session ends in 5 minutes.")
                    } else if (timeLeftSeconds == 60) {
                        NotificationHelper.showNotification(context, "⚠ 1 minute remaining", "Tap to buy more time.")
                    } else if (timeLeftSeconds == 0) {
                        NotificationHelper.showNotification(context, "Session expired", "Tap to reconnect.")
                    }
                }

                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "You are connected!", 
                        fontSize = 24.sp, 
                        fontWeight = FontWeight.ExtraBold,
                        color = SleekStyle.primaryTextColor(isDark)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Gateway: ${vm.guestHostName}", 
                        fontSize = 14.sp, 
                        color = SleekStyle.mutedTextColor(isDark)
                    )
                    Text(
                        text = "Connection Mode: ${vm.guestModeConnected}", 
                        fontSize = 12.sp, 
                        color = SleekStyle.mutedTextColor(isDark)
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(90.dp))
                            .background(countdownCardColor.copy(alpha = 0.12f))
                            .border(2.dp, countdownCardColor.copy(alpha = 0.4f), RoundedCornerShape(90.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = formattedTime, 
                            fontSize = 38.sp, 
                            fontWeight = FontWeight.Black, 
                            color = countdownCardColor,
                            letterSpacing = (-1).sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Internet access will stop automatically when time runs out.", 
                        fontSize = 12.sp, 
                        color = SleekStyle.mutedTextColor(isDark),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )

                    if (vm.guestModeConnected == "Phone Hotspot") {
                        Spacer(modifier = Modifier.height(24.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SleekStyle.cardBg(isDark)),
                            border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp), 
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Join Hotspot SSID: ${vm.guestHostName}_Spot", 
                                    fontWeight = FontWeight.Bold, 
                                    fontSize = 14.sp,
                                    color = SleekStyle.primaryTextColor(isDark)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Password: safe_random_pass_X29", 
                                    fontSize = 13.sp, 
                                    fontWeight = FontWeight.SemiBold,
                                    color = SleekStyle.hostColor(isDark)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(40.dp))

                    Text(
                        text = "Simulation Fast-Forwards (Testing warnings):", 
                        fontSize = 11.sp, 
                        fontWeight = FontWeight.Bold,
                        color = SleekStyle.mutedTextColor(isDark)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row {
                        Button(
                            onClick = { vm.simulateFastForward(300) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0), 
                                contentColor = SleekStyle.primaryTextColor(isDark)
                            )
                        ) {
                            Text("FF 5m", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { vm.guestConnectedMinutesLeft = 62 },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0), 
                                contentColor = SleekStyle.primaryTextColor(isDark)
                            )
                        ) {
                            Text("Jump to 1m", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { vm.guestConnectedMinutesLeft = 2 },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF4444), 
                                contentColor = Color.White
                            )
                        ) {
                            Text("Expire", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LaunchedEffect(Unit) {
                    vm.guestScanning = true
                    coroutineScope.launch {
                        delay(1200)
                        vm.guestScanning = false
                    }
                }

                if (vm.guestScanning) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = SleekStyle.hostColor(isDark))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Scanning nearby BeamSpot signals...", 
                            fontWeight = FontWeight.Bold,
                            color = SleekStyle.primaryTextColor(isDark)
                        )
                    }
                } else {
                    if (vm.isSharingActive) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "Legitimate networks detected near you:", 
                                fontSize = 13.sp, 
                                color = SleekStyle.mutedTextColor(isDark)
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            val currentSsid = if (vm.selectedConnectionMode == "Router") {
                                if (vm.routerBeamSpotSsid.isNotBlank()) vm.routerBeamSpotSsid else "BeamSpot Router"
                            } else if (vm.selectedConnectionMode == "Phone Hotspot") {
                                if (vm.bridgeBeamSpotSsid.isNotBlank()) vm.bridgeBeamSpotSsid else "BeamSpot Guest"
                            } else {
                                if (vm.bridgeBeamSpotSsid.isNotBlank()) vm.bridgeBeamSpotSsid else "BeamSpot Smart Bridge"
                            }
                            val currentMode = vm.selectedConnectionMode
                            val currentHost = if (vm.hostDisplayName.isNotBlank()) vm.hostDisplayName else "Host"

                            Card(
                                colors = CardDefaults.cardColors(containerColor = SleekStyle.cardBg(isDark)),
                                border = BorderStroke(1.5.dp, SleekStyle.hostColor(isDark)),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.guestHostName = currentHost
                                        vm.guestModeConnected = currentMode
                                        vm.isGuestSTKPromptOpen = true
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(20.dp), 
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF10B981).copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Filled.SignalWifi4Bar, 
                                            "Signal", 
                                            tint = Color(0xFF10B981),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = currentSsid, 
                                                fontWeight = FontWeight.Bold, 
                                                fontSize = 16.sp,
                                                color = SleekStyle.primaryTextColor(isDark)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0xFF10B981).copy(alpha = 0.15f), RoundedCornerShape(100.dp))
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "✓ VERIFIED", 
                                                    fontSize = 9.sp, 
                                                    fontWeight = FontWeight.Black, 
                                                    color = Color(0xFF10B981)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "BSSID: 00:1A:2B:3C:4D:5E · KSh ${vm.pricePerMinute}/min", 
                                            fontSize = 11.sp, 
                                            fontFamily = FontFamily.Monospace,
                                            color = SleekStyle.mutedTextColor(isDark)
                                        )
                                        Text(
                                            text = "Active Guests: ${vm.activeSessions.size} · Signal: Very Strong", 
                                            fontSize = 11.sp, 
                                            color = SleekStyle.mutedTextColor(isDark)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        NoActiveBeamSpotEmptyState(isDark)
                    }
                }
            }
        }
    }

    if (vm.isGuestSTKPromptOpen) {
        val amount = vm.guestSelectedMinutes * vm.pricePerMinute
        
        // Helper to format minutes nicely
        val formattedTime = if (vm.guestSelectedMinutes < 60) {
            "${vm.guestSelectedMinutes} minutes"
        } else {
            val hrs = vm.guestSelectedMinutes / 60
            val mins = vm.guestSelectedMinutes % 60
            if (mins == 0) {
                if (hrs == 1) "1 hour" else "$hrs hours"
            } else {
                "${hrs}h ${mins}m"
            }
        }

        Dialog(onDismissRequest = { vm.isGuestSTKPromptOpen = false }) {
            var animateTrigger by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                animateTrigger = true
            }
            val scale by animateFloatAsState(
                targetValue = if (animateTrigger) 1f else 0.85f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
            val alpha by animateFloatAsState(
                targetValue = if (animateTrigger) 1f else 0f,
                animationSpec = tween(durationMillis = 200)
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = SleekStyle.cardBg(isDark)),
                shape = RoundedCornerShape(32.dp),
                border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                modifier = Modifier
                    .padding(16.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "Select Time Block", 
                        fontWeight = FontWeight.ExtraBold, 
                        fontSize = 20.sp,
                        color = SleekStyle.primaryTextColor(isDark),
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(100.dp))
                            .background(if (isDark) Color(0xFF0F172A) else Color(0xFFF1F5F9))
                            .border(1.dp, SleekStyle.cardBorder(isDark), RoundedCornerShape(100.dp))
                            .padding(4.dp)
                    ) {
                        listOf(30, 60, 120, 180).forEach { mins ->
                            val active = vm.guestSelectedMinutes == mins
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(100.dp))
                                    .clickable { vm.guestSelectedMinutes = mins }
                                    .background(
                                        if (active) SleekStyle.hostColor(isDark) else Color.Transparent
                                    )
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                              ) {
                                Text(
                                    text = "${mins}m", 
                                    fontSize = 12.sp, 
                                    fontWeight = FontWeight.Bold,
                                    color = if (active) Color.White else SleekStyle.mutedTextColor(isDark)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Or use Custom slider:", 
                            fontSize = 11.sp, 
                            fontWeight = FontWeight.Bold,
                            color = SleekStyle.mutedTextColor(isDark)
                        )
                        Box(
                            modifier = Modifier
                                .background(SleekStyle.hostColor(isDark).copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = formattedTime,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = SleekStyle.hostColor(isDark)
                            )
                        }
                    }
                    
                    Slider(
                        value = vm.guestSelectedMinutes.toFloat(),
                        onValueChange = { vm.guestSelectedMinutes = it.toInt() },
                        valueRange = 15f..1440f,
                        colors = SliderDefaults.colors(
                            thumbColor = SleekStyle.hostColor(isDark),
                            activeTrackColor = SleekStyle.hostColor(isDark),
                            inactiveTrackColor = SleekStyle.cardBorder(isDark)
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Price to Pay: ",
                            fontSize = 14.sp,
                            color = SleekStyle.primaryTextColor(isDark)
                        )
                        Text(
                            text = "KSh ${amount.toInt()}", 
                            fontWeight = FontWeight.Black, 
                            fontSize = 18.sp, 
                            color = SleekStyle.hostColor(isDark)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = vm.guestMpesaNumber,
                        onValueChange = { vm.guestMpesaNumber = it },
                        label = { Text("M-Pesa Number") },
                        placeholder = { Text("e.g., 0712345678") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row {
                        TextButton(
                            onClick = { vm.isGuestSTKPromptOpen = false },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "Cancel", 
                                color = SleekStyle.mutedTextColor(isDark), 
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = {
                                vm.isGuestSTKPromptOpen = false
                                vm.isGuestPaymentProcessing = true
                                coroutineScope.launch {
                                    delay(2000)
                                    vm.isGuestPaymentProcessing = false
                                    vm.isGuestConnected = true
                                    vm.guestConnectedMinutesLeft = vm.guestSelectedMinutes * 60
                                    vm.guestConnectedTotalMinutes = vm.guestSelectedMinutes
                                    vm.warning5mFired = false
                                    vm.warning1mFired = false

                                    vm.activeSessions.add(
                                        GuestSession(deviceName = "Guest_device", minutesRemaining = vm.guestSelectedMinutes, initialMinutes = vm.guestSelectedMinutes, mode = vm.guestModeConnected)
                                    )
                                    vm.todayEarnings += amount
                                    vm.pendingWithdrawalBalance += amount
                                    vm.totalSessionsSold += 1
                                    vm.activeGuestsCount = vm.activeSessions.size
                                    vm.totalMinutesSold += vm.guestSelectedMinutes
                                    vm.minutesSoldToday += vm.guestSelectedMinutes
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SleekStyle.hostColor(isDark),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1.5f)
                        ) {
                            Text("Pay KSh ${amount.toInt()}", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (vm.isGuestPaymentProcessing) {
        Dialog(onDismissRequest = {}) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SleekStyle.cardBg(isDark)),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, SleekStyle.cardBorder(isDark)),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = SleekStyle.hostColor(isDark))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Triggering STK push to phone...", 
                        fontWeight = FontWeight.Bold, 
                        fontSize = 15.sp,
                        color = SleekStyle.primaryTextColor(isDark)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Waiting for payment webhook confirmation from Flutterwave...", 
                        fontSize = 12.sp, 
                        color = SleekStyle.mutedTextColor(isDark), 
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun NoActiveBeamSpotEmptyState(isDark: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale1 by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale1"
    )
    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha1"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(160.dp),
            contentAlignment = Alignment.Center
        ) {
            // Ripple wave 1
            Box(
                modifier = Modifier
                    .size(120.dp * scale1)
                    .clip(RoundedCornerShape(100.dp))
                    .background(SleekStyle.hostColor(isDark).copy(alpha = alpha1))
            )
            // Center solid circle with wifi off icon
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(RoundedCornerShape(35.dp))
                    .background(SleekStyle.hostColor(isDark).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.WifiOff,
                    contentDescription = "No networks nearby",
                    tint = SleekStyle.hostColor(isDark),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "No BeamSpot Nearby",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
            color = SleekStyle.primaryTextColor(isDark),
            letterSpacing = (-0.5).sp
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Only configured, active BeamSpot networks are listed here to prevent fraudulent or unverified access.\n\nTo list your own network and get paid, enable 'Start Sharing' under the Host Dashboard.",
            fontSize = 13.sp,
            color = SleekStyle.mutedTextColor(isDark),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
            lineHeight = 18.sp
        )
    }
}
