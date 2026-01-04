package com.cointracker.pro.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cointracker.pro.data.binance.BinanceConfig
import com.cointracker.pro.data.repository.ApiKeyRepository
import com.cointracker.pro.data.supabase.SupabaseAuthRepository
import com.cointracker.pro.ui.components.GlassCard
import com.cointracker.pro.ui.components.GradientBackground
import com.cointracker.pro.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val binanceConfig = remember { BinanceConfig.getInstance(context) }
    val apiKeyRepository = remember { ApiKeyRepository() }
    val authRepository = remember { SupabaseAuthRepository() }
    val scope = rememberCoroutineScope()

    var autoTrading by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(true) }
    var testnetMode by remember { mutableStateOf(binanceConfig.isTestnetMode()) }

    // Trading Type Settings
    var tradingType by remember { mutableStateOf("spot") }  // spot, margin, future
    var leverage by remember { mutableStateOf(1) }
    var showTradingTypeDialog by remember { mutableStateOf(false) }
    var showLeverageDialog by remember { mutableStateOf(false) }

    // Binance API Keys (from Supabase)
    var apiKey by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var showSecretKey by remember { mutableStateOf(false) }
    var showApiDialog by remember { mutableStateOf(false) }
    var apiKeysSaved by remember { mutableStateOf(false) }
    var isLoadingKeys by remember { mutableStateOf(true) }

    // Load API keys from Supabase on start
    LaunchedEffect(Unit) {
        val result = apiKeyRepository.getApiKeys()
        result.getOrNull()?.let { keys ->
            apiKey = keys.apiKey
            secretKey = keys.secretKey
            testnetMode = keys.isTestnet
            apiKeysSaved = true
            // Also save locally for BinanceConfig to use
            binanceConfig.saveCredentials(keys.apiKey, keys.secretKey)
            binanceConfig.setTestnetMode(keys.isTestnet)
        }
        isLoadingKeys = false
    }

    // Auth State
    var isLoggedIn by remember { mutableStateOf(authRepository.isLoggedIn()) }
    var currentUser by remember { mutableStateOf(authRepository.getCurrentUser()) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var isSignUpMode by remember { mutableStateOf(false) }
    var authLoading by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    GradientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Configure your trading bot",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            // Binance API Configuration
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = AccentOrange,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Binance API",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }

                        // Status Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (apiKeysSaved) BullishGreen.copy(alpha = 0.2f)
                                    else BearishRed.copy(alpha = 0.2f)
                                )
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (apiKeysSaved) "Connected" else "Not Set",
                                color = if (apiKeysSaved) BullishGreen else BearishRed,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (apiKeysSaved) {
                        Text(
                            text = "API Key: ${apiKey.take(8)}...${apiKey.takeLast(4)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showApiDialog = true },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = ElectricBlue
                                )
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Edit")
                            }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        apiKeyRepository.deleteApiKeys()
                                        binanceConfig.clearCredentials()
                                        apiKey = ""
                                        secretKey = ""
                                        apiKeysSaved = false
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = BearishRed
                                )
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Remove")
                            }
                        }
                    } else {
                        Text(
                            text = "Connect your Binance account to enable trading",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { showApiDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentOrange
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add API Keys")
                        }
                    }
                }
            }

            // Trading Settings
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = "Trading",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Trading Type Selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = AccentOrange,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Trading Type",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                text = when (tradingType) {
                                    "spot" -> "Spot Trading (No Leverage)"
                                    "margin" -> "Margin Trading (${leverage}x Leverage)"
                                    "future" -> "Futures Trading (${leverage}x Leverage)"
                                    else -> "Spot Trading"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Button(
                            onClick = { showTradingTypeDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentOrange.copy(alpha = 0.2f),
                                contentColor = AccentOrange
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = tradingType.uppercase(),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Leverage Selector (only for margin/futures)
                    if (tradingType != "spot") {
                        HorizontalDivider(
                            color = GlassBorder,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.TrendingUp,
                                contentDescription = null,
                                tint = if (leverage > 10) BearishRed else ElectricBlue,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Leverage",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (leverage > 10) "High Risk!" else "Multiplies gains and losses",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (leverage > 10) BearishRed else TextSecondary
                                )
                            }
                            Button(
                                onClick = { showLeverageDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (leverage > 10)
                                        BearishRed.copy(alpha = 0.2f)
                                    else
                                        ElectricBlue.copy(alpha = 0.2f),
                                    contentColor = if (leverage > 10) BearishRed else ElectricBlue
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "${leverage}x",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = GlassBorder,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    SettingsToggle(
                        icon = Icons.Default.Speed,
                        title = "Auto Trading",
                        description = "Execute trades automatically based on ML signals",
                        checked = autoTrading,
                        onCheckedChange = { autoTrading = it },
                        enabled = apiKeysSaved
                    )

                    HorizontalDivider(
                        color = GlassBorder,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    SettingsToggle(
                        icon = Icons.Default.Science,
                        title = "Testnet Mode",
                        description = "Use Binance testnet for safe testing",
                        checked = testnetMode,
                        onCheckedChange = {
                            testnetMode = it
                            binanceConfig.setTestnetMode(it)
                        }
                    )
                }
            }

            // Notification Settings
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = "Notifications",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsToggle(
                        icon = Icons.Default.Notifications,
                        title = "Push Notifications",
                        description = "Receive alerts for strong signals & whale movements",
                        checked = notifications,
                        onCheckedChange = { notifications = it }
                    )
                }
            }

            // Account Section
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = "Account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (isLoggedIn && currentUser != null) {
                        // Logged in state
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = ElectricBlue,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = currentUser?.email ?: "User",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Eingeloggt via Supabase",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BullishGreen
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    authRepository.signOut()
                                    isLoggedIn = false
                                    currentUser = null
                                    onLogout()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BearishRed
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Logout")
                        }
                    } else {
                        // Not logged in state
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PersonOff,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Nicht eingeloggt",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Login für Paper Trading & Sync",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { showLoginDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricBlue
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Login, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Login / Sign Up")
                        }
                    }
                }
            }

            // Version Info
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "CoinTracker Pro v1.0",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                text = "Powered by Supabase & Binance",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    // API Key Dialog
    if (showApiDialog) {
        val clipboardManager = LocalClipboardManager.current

        AlertDialog(
            onDismissRequest = { showApiDialog = false },
            containerColor = DarkBlue,
            title = {
                Text(
                    "Binance API Keys",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Kopiere die Keys und nutze die Paste-Buttons.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )

                    // API Key with Paste Button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("API Key") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricBlue,
                                unfocusedBorderColor = GlassBorder,
                                focusedLabelColor = ElectricBlue,
                                unfocusedLabelColor = TextSecondary,
                                cursorColor = ElectricBlue,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.getText()?.text?.let { apiKey = it }
                            }
                        ) {
                            Icon(
                                Icons.Default.ContentPaste,
                                contentDescription = "Paste",
                                tint = ElectricBlue
                            )
                        }
                    }

                    // Secret Key with Paste Button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = secretKey,
                            onValueChange = { secretKey = it },
                            label = { Text("Secret Key") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            visualTransformation = if (showSecretKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showSecretKey = !showSecretKey }) {
                                    Icon(
                                        if (showSecretKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = TextSecondary
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricBlue,
                                unfocusedBorderColor = GlassBorder,
                                focusedLabelColor = ElectricBlue,
                                unfocusedLabelColor = TextSecondary,
                                cursorColor = ElectricBlue,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.getText()?.text?.let { secretKey = it }
                            }
                        ) {
                            Icon(
                                Icons.Default.ContentPaste,
                                contentDescription = "Paste",
                                tint = ElectricBlue
                            )
                        }
                    }

                    Text(
                        "Niemals Withdrawal-Rechte aktivieren!",
                        color = AccentOrange,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (apiKey.isNotBlank() && secretKey.isNotBlank()) {
                            scope.launch {
                                // Save to Supabase
                                val result = apiKeyRepository.saveApiKeys(
                                    apiKey = apiKey,
                                    secretKey = secretKey,
                                    isTestnet = testnetMode
                                )
                                if (result.isSuccess) {
                                    // Also save locally
                                    binanceConfig.saveCredentials(apiKey, secretKey)
                                    binanceConfig.setTestnetMode(testnetMode)
                                    apiKeysSaved = true
                                    showApiDialog = false
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BullishGreen),
                    enabled = apiKey.isNotBlank() && secretKey.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    // Login/SignUp Dialog
    if (showLoginDialog) {
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var showPassword by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                showLoginDialog = false
                authError = null
            },
            containerColor = DarkBlue,
            title = {
                Text(
                    if (isSignUpMode) "Account erstellen" else "Login",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (isSignUpMode) "Erstelle einen Account für Paper Trading"
                        else "Melde dich an um Paper Trading zu nutzen",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("E-Mail") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = GlassBorder,
                            focusedLabelColor = ElectricBlue,
                            unfocusedLabelColor = TextSecondary,
                            cursorColor = ElectricBlue,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Passwort") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = TextSecondary
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = GlassBorder,
                            focusedLabelColor = ElectricBlue,
                            unfocusedLabelColor = TextSecondary,
                            cursorColor = ElectricBlue,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    // Error message
                    authError?.let { error ->
                        Text(
                            text = error,
                            color = BearishRed,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Toggle Login/SignUp
                    TextButton(
                        onClick = {
                            isSignUpMode = !isSignUpMode
                            authError = null
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            if (isSignUpMode) "Schon einen Account? Login"
                            else "Noch kein Account? Registrieren",
                            color = ElectricBlue
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (email.isNotBlank() && password.isNotBlank()) {
                            authLoading = true
                            authError = null
                            scope.launch {
                                val result = if (isSignUpMode) {
                                    authRepository.signUp(email, password)
                                } else {
                                    authRepository.signIn(email, password)
                                }
                                authLoading = false
                                result.onSuccess { user ->
                                    isLoggedIn = true
                                    currentUser = user
                                    showLoginDialog = false
                                    authError = null
                                }.onFailure { error ->
                                    authError = error.message ?: "Fehler beim Login"
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BullishGreen),
                    enabled = email.isNotBlank() && password.length >= 6 && !authLoading
                ) {
                    if (authLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(if (isSignUpMode) "Registrieren" else "Login")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLoginDialog = false
                    authError = null
                }) {
                    Text("Abbrechen", color = TextSecondary)
                }
            }
        )
    }

    // Trading Type Selection Dialog
    if (showTradingTypeDialog) {
        AlertDialog(
            onDismissRequest = { showTradingTypeDialog = false },
            containerColor = DarkBlue,
            title = {
                Text(
                    "Select Trading Type",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Spot Option
                    TradingTypeOption(
                        title = "Spot",
                        description = "Buy and sell crypto directly. No leverage, lower risk.",
                        icon = Icons.Default.AccountBalance,
                        isSelected = tradingType == "spot",
                        onClick = {
                            tradingType = "spot"
                            leverage = 1
                            showTradingTypeDialog = false
                            // TODO: Call API to update backend
                        }
                    )

                    // Margin Option
                    TradingTypeOption(
                        title = "Margin",
                        description = "Trade with borrowed funds. Up to 10x leverage.",
                        icon = Icons.Default.TrendingUp,
                        isSelected = tradingType == "margin",
                        onClick = {
                            tradingType = "margin"
                            if (leverage < 2) leverage = 2
                            showTradingTypeDialog = false
                        },
                        warningText = "Higher risk"
                    )

                    // Futures Option
                    TradingTypeOption(
                        title = "Futures",
                        description = "Trade perpetual contracts. Up to 125x leverage.",
                        icon = Icons.Default.Rocket,
                        isSelected = tradingType == "future",
                        onClick = {
                            tradingType = "future"
                            if (leverage < 2) leverage = 2
                            showTradingTypeDialog = false
                        },
                        warningText = "Highest risk"
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTradingTypeDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    // Leverage Selection Dialog
    if (showLeverageDialog) {
        val leverageOptions = if (tradingType == "margin") {
            listOf(2, 3, 5, 10)
        } else {
            listOf(2, 3, 5, 10, 20, 50, 75, 100, 125)
        }

        AlertDialog(
            onDismissRequest = { showLeverageDialog = false },
            containerColor = DarkBlue,
            title = {
                Text(
                    "Select Leverage",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Higher leverage = Higher risk",
                        color = BearishRed,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Leverage options in rows
                    leverageOptions.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { lev ->
                                val isHighRisk = lev > 10
                                val isSelected = leverage == lev
                                Button(
                                    onClick = {
                                        leverage = lev
                                        showLeverageDialog = false
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = when {
                                            isSelected -> if (isHighRisk) BearishRed else ElectricBlue
                                            isHighRisk -> BearishRed.copy(alpha = 0.1f)
                                            else -> ElectricBlue.copy(alpha = 0.1f)
                                        },
                                        contentColor = when {
                                            isSelected -> Color.White
                                            isHighRisk -> BearishRed
                                            else -> ElectricBlue
                                        }
                                    )
                                ) {
                                    Text(
                                        "${lev}x",
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                            // Fill remaining space if row is not complete
                            repeat(3 - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLeverageDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun TradingTypeOption(
    title: String,
    description: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    warningText: String? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) ElectricBlue.copy(alpha = 0.2f) else GlassWhite,
        border = if (isSelected) BorderStroke(2.dp, ElectricBlue) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) ElectricBlue else TextSecondary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    warningText?.let {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = BearishRed,
                            modifier = Modifier
                                .background(
                                    BearishRed.copy(alpha = 0.1f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = ElectricBlue,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (!enabled) it.then(Modifier) else it },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) ElectricBlue else TextMuted,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) TextPrimary else TextMuted
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ElectricBlue,
                checkedTrackColor = ElectricBlue.copy(alpha = 0.3f),
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = GlassWhite,
                disabledCheckedThumbColor = TextMuted,
                disabledUncheckedThumbColor = TextMuted
            )
        )
    }
}
