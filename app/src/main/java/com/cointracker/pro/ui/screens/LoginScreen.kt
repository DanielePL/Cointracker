package com.cointracker.pro.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cointracker.pro.data.supabase.SupabaseAuthRepository
import com.cointracker.pro.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit
) {
    val authRepository = remember { SupabaseAuthRepository() }
    val scope = rememberCoroutineScope()

    var isLoginMode by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        DeepBlue,
                        DarkBlue,
                        DeepBlue
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo & Title
            Icon(
                imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = ElectricBlue
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "CoinTracker Pro",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = "ML-Powered Crypto Trading",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Login/Register Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.1f),
                                Color.White.copy(alpha = 0.05f)
                            )
                        )
                    )
                    .padding(24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Mode Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.3f))
                            .padding(4.dp)
                    ) {
                        TabButton(
                            text = "Login",
                            selected = isLoginMode,
                            onClick = {
                                isLoginMode = true
                                errorMessage = null
                                successMessage = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TabButton(
                            text = "Register",
                            selected = !isLoginMode,
                            onClick = {
                                isLoginMode = false
                                errorMessage = null
                                successMessage = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Email Field
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        leadingIcon = {
                            Icon(Icons.Default.Email, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                            focusedLabelColor = ElectricBlue,
                            unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                            cursorColor = ElectricBlue,
                            focusedLeadingIconColor = ElectricBlue,
                            unfocusedLeadingIconColor = Color.White.copy(alpha = 0.6f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Password Field
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null)
                        },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                            focusedLabelColor = ElectricBlue,
                            unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                            cursorColor = ElectricBlue,
                            focusedLeadingIconColor = ElectricBlue,
                            unfocusedLeadingIconColor = Color.White.copy(alpha = 0.6f),
                            focusedTrailingIconColor = Color.White.copy(alpha = 0.6f),
                            unfocusedTrailingIconColor = Color.White.copy(alpha = 0.6f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true
                    )

                    // Confirm Password (Register only)
                    AnimatedVisibility(
                        visible = !isLoginMode,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it },
                                label = { Text("Confirm Password") },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = null)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ElectricBlue,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                                    focusedLabelColor = ElectricBlue,
                                    unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                                    cursorColor = ElectricBlue,
                                    focusedLeadingIconColor = ElectricBlue,
                                    unfocusedLeadingIconColor = Color.White.copy(alpha = 0.6f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                singleLine = true,
                                isError = confirmPassword.isNotEmpty() && password != confirmPassword
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Error Message
                    errorMessage?.let { error ->
                        Text(
                            text = error,
                            color = ErrorRed,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Success Message
                    successMessage?.let { success ->
                        Text(
                            text = success,
                            color = BullishGreen,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Submit Button
                    Button(
                        onClick = {
                            errorMessage = null
                            successMessage = null

                            // Validation
                            if (!email.contains("@")) {
                                errorMessage = "Please enter a valid email"
                                return@Button
                            }
                            if (password.length < 6) {
                                errorMessage = "Password must be at least 6 characters"
                                return@Button
                            }
                            if (!isLoginMode && password != confirmPassword) {
                                errorMessage = "Passwords do not match"
                                return@Button
                            }

                            isLoading = true

                            scope.launch {
                                if (isLoginMode) {
                                    val result = authRepository.signIn(email, password)
                                    result.fold(
                                        onSuccess = {
                                            onLoginSuccess()
                                        },
                                        onFailure = { e ->
                                            errorMessage = when {
                                                e.message?.contains("Invalid login") == true -> "Invalid email or password"
                                                e.message?.contains("Email not confirmed") == true -> "Please confirm your email first"
                                                else -> e.message ?: "Login failed"
                                            }
                                        }
                                    )
                                } else {
                                    val result = authRepository.signUp(email, password)
                                    result.fold(
                                        onSuccess = {
                                            successMessage = "Account created! Check your email to confirm."
                                            isLoginMode = true
                                            password = ""
                                            confirmPassword = ""
                                        },
                                        onFailure = { e ->
                                            errorMessage = when {
                                                e.message?.contains("already registered") == true -> "This email is already registered"
                                                else -> e.message ?: "Registration failed"
                                            }
                                        }
                                    )
                                }
                                isLoading = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricBlue,
                            disabledContainerColor = ElectricBlue.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        enabled = !isLoading && email.isNotBlank() && password.isNotBlank() &&
                                (isLoginMode || password == confirmPassword)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = if (isLoginMode) "Login" else "Create Account",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Forgot Password (Login only)
                    AnimatedVisibility(visible = isLoginMode) {
                        TextButton(
                            onClick = {
                                if (email.isNotBlank() && email.contains("@")) {
                                    scope.launch {
                                        isLoading = true
                                        authRepository.resetPassword(email).fold(
                                            onSuccess = {
                                                successMessage = "Password reset email sent!"
                                            },
                                            onFailure = { e ->
                                                errorMessage = e.message ?: "Failed to send reset email"
                                            }
                                        )
                                        isLoading = false
                                    }
                                } else {
                                    errorMessage = "Enter your email first"
                                }
                            }
                        ) {
                            Text(
                                text = "Forgot Password?",
                                color = ElectricBlue,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Powered by Supabase
            Text(
                text = "Secured by Supabase",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) ElectricBlue else Color.Transparent,
            contentColor = if (selected) Color.White else Color.White.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(8.dp),
        elevation = null
    ) {
        Text(text = text, fontWeight = FontWeight.Medium)
    }
}
