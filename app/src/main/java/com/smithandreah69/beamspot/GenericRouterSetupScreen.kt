package com.smithandreah69.beamspot

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GenericRouterSetupScreen(nav: NavHostController, vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember { SessionManager(context) }
    
    // Internal States
    var ip by remember { mutableStateOf(vm.routerIp.ifEmpty { "192.168.1.1" }) }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    
    // Status tracking
    var statusMessage by remember { mutableStateOf("Ready. Enter credentials and click 'Connect & Auto-Login'.") }
    var statusType by remember { mutableStateOf("READY") } // READY, CONNECTING, SCANNING, SUBMITTING, MANUAL_FALLBACK, SUCCESS, LOGIN_FAILED
    
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isPanelExpanded by remember { mutableStateOf(true) }
    var hasSubmitted by remember { mutableStateOf(false) }
    var lastLoadedUrl by remember { mutableStateOf("") }
    
    // Heuristic script and Autofill script
    val scannerJs = """
        (function() {
            const inputs = Array.from(document.querySelectorAll('input'));
            const findField = (typeMatch, keywordPatterns) => {
                let bestMatch = null;
                let bestScore = 0;
                for (const input of inputs) {
                    if (typeMatch && input.type !== typeMatch) continue;
                    const nearbyText = (
                        (input.name || '') + ' ' +
                        (input.id || '') + ' ' +
                        (input.placeholder || '') + ' ' +
                        (input.closest('tr')?.textContent || '') + ' ' +
                        (input.previousElementSibling?.textContent || '')
                    ).toLowerCase();
                    let score = 0;
                    for (const pattern of keywordPatterns) {
                        if (nearbyText.includes(pattern)) score++;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        bestMatch = input;
                    }
                }
                return { element: bestMatch, confidence: bestScore };
            };

            const usernameField = findField('text', ['user', 'admin', 'login', 'account']);
            const passwordField = findField('password', ['pass', 'pwd', 'key']);

            return JSON.stringify({
                usernameFound: usernameField.element !== null,
                usernameConfidence: usernameField.confidence,
                passwordFound: passwordField.element !== null,
                passwordConfidence: passwordField.confidence
            });
        })();
    """.trimIndent()
    
    fun getAutofillJs(user: String, pass: String): String {
        val escapedUser = user.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
        val escapedPass = pass.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
        return """
            (function(username, password) {
                const inputs = Array.from(document.querySelectorAll('input'));
                const findField = (typeMatch, keywordPatterns) => {
                    let bestMatch = null;
                    let bestScore = 0;
                    for (const input of inputs) {
                        if (typeMatch && input.type !== typeMatch) continue;
                        const nearbyText = (
                            (input.name || '') + ' ' +
                            (input.id || '') + ' ' +
                            (input.placeholder || '') + ' ' +
                            (input.closest('tr')?.textContent || '') + ' ' +
                            (input.previousElementSibling?.textContent || '')
                        ).toLowerCase();
                        let score = 0;
                        for (const pattern of keywordPatterns) {
                            if (nearbyText.includes(pattern)) score++;
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            bestMatch = input;
                        }
                    }
                    return { element: bestMatch, confidence: bestScore };
                };

                const usernameField = findField('text', ['user', 'admin', 'login', 'account']);
                const passwordField = findField('password', ['pass', 'pwd', 'key']);

                if (usernameField.element) {
                    usernameField.element.value = username;
                    usernameField.element.dispatchEvent(new Event('input', { bubbles: true }));
                    usernameField.element.dispatchEvent(new Event('change', { bubbles: true }));
                }
                if (passwordField.element) {
                    passwordField.element.value = password;
                    passwordField.element.dispatchEvent(new Event('input', { bubbles: true }));
                    passwordField.element.dispatchEvent(new Event('change', { bubbles: true }));
                }

                const form = usernameField.element ? usernameField.element.closest('form') : null;
                const submitButton = (form ? form.querySelector('button[type="submit"], input[type="submit"], button') : null)
                    || document.querySelector('button[type="submit"], input[type="submit"], button, input[type="button"]');
                
                if (submitButton) {
                    submitButton.click();
                    return 'clicked_submit';
                } else if (form) {
                    form.submit(); // fallback only if truly no clickable submit element exists on the page
                    return 'submitted_fallback';
                }
                
                // Fallback click login button for other non-standard clickable elements
                const buttons = Array.from(document.querySelectorAll('a, div, span'));
                let bestBtn = null;
                let bestBtnScore = 0;
                const btnPatterns = ['login', 'log in', 'submit', 'sign in', 'signin', 'enter', 'ok'];
                for (const btn of buttons) {
                    const text = (btn.textContent || btn.id || btn.className || '').toLowerCase();
                    let score = 0;
                    for (const pat of btnPatterns) {
                        if (text.includes(pat)) score++;
                    }
                    if (score > bestBtnScore) {
                        bestBtnScore = score;
                        bestBtn = btn;
                    }
                }
                if (bestBtn) {
                    bestBtn.click();
                    return 'clicked_extra_button';
                }

                return 'no_form_found';
            })('$escapedUser', '$escapedPass');
        """.trimIndent()
    }
    
    // Parse Heuristic Scan JSON Result
    fun parseScanResult(json: String): Triple<Boolean, Boolean, Boolean> {
        val clean = if (json.startsWith("\"") && json.endsWith("\"")) {
            json.substring(1, json.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        } else {
            json
        }
        val uFound = clean.contains("\"usernameFound\":true")
        val pFound = clean.contains("\"passwordFound\":true")
        
        val uConf = "\"usernameConfidence\":\\s*(\\d+)".toRegex().find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val pConf = "\"passwordConfidence\":\\s*(\\d+)".toRegex().find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        
        // Confidence score >= 1 each means found with reasonable confidence
        val uReasonable = uFound && uConf >= 1
        val pReasonable = pFound && pConf >= 1
        
        return Triple(uReasonable, pReasonable, uReasonable && pReasonable)
    }

    // Colors
    val Ink = Color(0xFF0E1614)
    val Panel = Color(0xFF1A2925)
    val Cyan = Color(0xFF3FE0C5)
    val Amber = Color(0xFFFF7A45)
    val Paper = Color(0xFFF0EEE6)
    val PaperDim = Color(0x9EF0EEE6.toInt())
    val BorderLine = Color(0x1AF0EEE6.toInt())
    val Emerald = Color(0xFF2ECC71)

    // Layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header TopBar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Filled.ArrowBack, "Back", tint = Cyan)
                }
                Text(
                    "Generic Router Login",
                    color = Paper,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }

            // Expanded/Collapsed Control Form
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
                border = BorderStroke(1.dp, BorderLine),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.SettingsInputHdmi, null, tint = Cyan, modifier = Modifier.size(18.dp))
                            Text("Credentials Setup", color = Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Text(
                            text = if (isPanelExpanded) "Collapse" else "Expand",
                            color = Cyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable { isPanelExpanded = !isPanelExpanded }
                        )
                    }

                    if (isPanelExpanded) {
                        Spacer(Modifier.height(4.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                BeamLabel("Router IP")
                                BeamInput(value = ip, onValueChange = { ip = it }, placeholder = "e.g. 192.168.1.1")
                            }
                            Column(modifier = Modifier.weight(1.2f)) {
                                BeamLabel("Username")
                                BeamInput(value = username, onValueChange = { username = it }, placeholder = "admin")
                            }
                        }
                        
                        BeamLabel("Password")
                        BeamPasswordInput(value = password, onValueChange = { password = it }, placeholder = "router password")

                        Spacer(Modifier.height(6.dp))
                        
                        Button(
                            onClick = {
                                isPanelExpanded = false
                                statusType = "CONNECTING"
                                statusMessage = "Connecting to http://$ip..."
                                hasSubmitted = false
                                lastLoadedUrl = ""
                                webViewRef?.loadUrl("http://$ip")
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("connect_login_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan)
                        ) {
                            Text("Connect & Auto-Login", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    } else {
                        // Display abbreviated state when collapsed
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "IP: $ip | User: $username",
                                color = PaperDim,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Change Credentials",
                                color = Cyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { isPanelExpanded = true }
                            )
                        }
                    }
                }
            }

            // Thin Status Bar Indicator Above WebView
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(10.dp),
                color = when (statusType) {
                    "SUCCESS" -> Emerald.copy(alpha = 0.15f)
                    "FAILED", "LOGIN_FAILED" -> Color.Red.copy(alpha = 0.15f)
                    "MANUAL_FALLBACK" -> Amber.copy(alpha = 0.15f)
                    "CONNECTING", "SCANNING", "SUBMITTING" -> Cyan.copy(alpha = 0.12f)
                    else -> Panel
                },
                border = BorderStroke(
                    1.dp,
                    when (statusType) {
                        "SUCCESS" -> Emerald
                        "FAILED", "LOGIN_FAILED" -> Color.Red
                        "MANUAL_FALLBACK" -> Amber
                        "CONNECTING", "SCANNING", "SUBMITTING" -> Cyan
                        else -> BorderLine
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (statusType == "CONNECTING" || statusType == "SCANNING" || statusType == "SUBMITTING") {
                        CircularProgressIndicator(
                            color = Cyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            imageVector = when (statusType) {
                                "SUCCESS" -> Icons.Filled.CheckCircle
                                "FAILED", "LOGIN_FAILED" -> Icons.Filled.Error
                                "MANUAL_FALLBACK" -> Icons.Filled.Info
                                else -> Icons.Filled.Cloud
                            },
                            contentDescription = null,
                            tint = when (statusType) {
                                "SUCCESS" -> Emerald
                                "FAILED", "LOGIN_FAILED" -> Color.Red
                                "MANUAL_FALLBACK" -> Amber
                                else -> Cyan
                            },
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = statusMessage,
                        color = Paper,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Webview visible on screen
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, BorderLine, RoundedCornerShape(16.dp))
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    val currentUrl = url ?: ""
                                    android.util.Log.d("GenericRouterSetup", "Page started loading: $currentUrl")
                                    if (statusType == "SUBMITTING") {
                                        statusMessage = "Page redirected, checking results..."
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val currentUrl = url ?: ""
                                    android.util.Log.d("GenericRouterSetup", "Page finished loading: $currentUrl")
                                    
                                    // If we are already in SUCCESS state, do not run scanner or any auto-submit logic again.
                                    if (statusType == "SUCCESS") {
                                        return
                                    }
                                    
                                    // 1. If we loaded the login page first time
                                    if (statusType == "CONNECTING") {
                                        statusType = "SCANNING"
                                        statusMessage = "Scanning page for login fields..."
                                        
                                        // Delay slightly to let dynamic JS elements load if any
                                        postDelayed({
                                            evaluateJavascript(scannerJs) { result ->
                                                android.util.Log.d("GenericRouterSetup", "Scan Result: $result")
                                                val (uReasonable, pReasonable, foundBoth) = parseScanResult(result ?: "")
                                                
                                                if (foundBoth) {
                                                    statusType = "SUBMITTING"
                                                    statusMessage = "Heuristic scanner matched fields! Autofilling & submitting..."
                                                    
                                                    // Trigger autofill & submit
                                                    hasSubmitted = true
                                                    lastLoadedUrl = currentUrl
                                                    
                                                    val autofillJs = getAutofillJs(username, password)
                                                    evaluateJavascript(autofillJs) { autofillResult ->
                                                        android.util.Log.d("GenericRouterSetup", "Autofill Result: $autofillResult")
                                                    }
                                                } else {
                                                    val isLoginUrl = currentUrl.contains("login", ignoreCase = true) || 
                                                                     currentUrl.contains("auth", ignoreCase = true) ||
                                                                     currentUrl.contains("signin", ignoreCase = true)
                                                    
                                                    if (!isLoginUrl && !result.contains("\"usernameFound\":true") && !result.contains("\"passwordFound\":true")) {
                                                        statusType = "SUCCESS"
                                                        statusMessage = "Already logged in! Reached router's dashboard."
                                                        
                                                        // Update ViewModel
                                                        vm.routerUsername = username
                                                        vm.routerPassword = password
                                                        vm.routerIp = ip
                                                        vm.selectedMode = "router"
                                                    } else {
                                                        statusType = "MANUAL_FALLBACK"
                                                        statusMessage = "We couldn't automatically find the login form. Please log in manually below."
                                                        isPanelExpanded = false
                                                    }
                                                }
                                            }
                                        }, 1000)
                                    } 
                                    // 2. If we just submitted and a new page loaded
                                    else if (hasSubmitted) {
                                        statusType = "SCANNING"
                                        statusMessage = "Confirming login session status..."
                                        
                                        postDelayed({
                                            evaluateJavascript(scannerJs) { result ->
                                                android.util.Log.d("GenericRouterSetup", "Post-submit Scan Result: $result")
                                                val (uReasonable, pReasonable, foundBoth) = parseScanResult(result ?: "")
                                                
                                                // If login fields are still found and url is same or page is unchanged, credentials failed
                                                val isUrlSame = currentUrl == lastLoadedUrl || currentUrl.contains("login") || currentUrl.contains("auth")
                                                if (foundBoth && isUrlSame) {
                                                    statusType = "LOGIN_FAILED"
                                                    statusMessage = "Login didn't work. Please check your username and password and try again."
                                                    hasSubmitted = false
                                                    isPanelExpanded = true
                                                } else {
                                                    // Form is gone or URL changed meaningfully -> Success!
                                                    statusType = "SUCCESS"
                                                    statusMessage = "Logged in successfully to router!"
                                                    hasSubmitted = false
                                                    
                                                    // Update ViewModel
                                                    vm.routerUsername = username
                                                    vm.routerPassword = password
                                                    vm.routerIp = ip
                                                    vm.selectedMode = "router"
                                                }
                                            }
                                        }, 1500)
                                    }
                                }
                            }
                        }
                    },
                    update = {
                        // Keep reference updated
                        webViewRef = it
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                // Show initial Overlay when READY state to prevent white flash or show user hint
                if (statusType == "READY") {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Panel),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(Icons.Filled.BrowserUpdated, null, tint = Cyan, modifier = Modifier.size(48.dp))
                            Text(
                                "Administrative Web Portal",
                                color = Paper,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Enter your credentials above and click 'Connect & Auto-Login'. We will automatically locate the forms, log in, and establish a secure billing session.",
                                color = PaperDim,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            // Bottom Success Action Bar (Only shows when successful!)
            AnimatedVisibility(
                visible = statusType == "SUCCESS",
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Panel,
                    border = BorderStroke(1.dp, Emerald)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "🎉 Router Login Complete!",
                            color = Emerald,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "BeamSpot has successfully logged into your generic router. We'll now configure the captive portal settings on the dashboard.",
                            color = PaperDim,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    sessionManager.saveRouterConnection(ip, 80, username, password)
                                    sessionManager.setCompletedSetup(true)
                                    sessionManager.saveUserProfile(vm.userName, vm.userEmail, vm.isDemoMode)
                                    nav.navigate(Route.MAIN_APP) {
                                        popUpTo(Route.SPLASH) { inclusive = true }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("finish_setup_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald)
                        ) {
                            Text("Finish Setup & Launch Dashboard", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BeamPasswordInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    var passwordVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().testTag("password_input"),
        placeholder = { Text(placeholder, color = Color(0x9EF0EEE6.toInt())) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
            val description = if (passwordVisible) "Hide password" else "Show password"
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(imageVector = image, contentDescription = description, tint = Color(0xFF3FE0C5))
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color(0xFFF0EEE6),
            unfocusedTextColor = Color(0xFFF0EEE6),
            focusedBorderColor = Color(0xFF3FE0C5),
            unfocusedBorderColor = Color(0x1AF0EEE6.toInt()),
            cursorColor = Color(0xFF3FE0C5)
        ),
        shape = RoundedCornerShape(12.dp),
        singleLine = true
    )
}
