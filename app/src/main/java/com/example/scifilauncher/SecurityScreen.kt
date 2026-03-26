package com.example.scifilauncher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SecurityScreen(
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    onBackToDashboard: () -> Unit,
    onSetAppPin: (String, String) -> Unit,
    onSetPhonePin: (String, String) -> Unit,
    onOpenLockedApps: () -> Unit,
    onOpenHiddenApps: () -> Unit,
    onOpenFavoriteApps: () -> Unit
) {
    var showAppPinDialog by remember { mutableStateOf(false) }
    var showPhonePinDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        MatrixBackground(
            themeColor = themeColor,
            isDark = isDark,
            batteryMode = batteryMode
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "< DASH",
                color = themeColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .clickable { onBackToDashboard() }
            )

            Text(
                text = "SECURITY CENTER",
                color = themeColor,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            SecuritySectionTitle("ACCESS LOCKS", themeColor)

            SecurityRow("App password") {
                showAppPinDialog = true
            }

            SecurityRow("Phone password") {
                showPhonePinDialog = true
            }

            SecurityRow("Locked apps") {
                onOpenLockedApps()
            }

            SecurityRow("Hidden apps") {
                onOpenHiddenApps()
            }

            SecurityRow("Favorite apps") {
                onOpenFavoriteApps()
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = themeColor.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(12.dp))

            SecuritySectionTitle("NETWORK CONTROL", themeColor)
            SecurityRow("IP block") { }

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = themeColor.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(12.dp))

            SecuritySectionTitle("DATA & MEDIA", themeColor)
            SecurityRow("Storage") { }
            SecurityRow("Video") { }

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (showAppPinDialog) {
            PinSetupDialog(
                title = "Set App PIN",
                themeColor = themeColor,
                onDismiss = { showAppPinDialog = false },
                onSave = { pin, favoriteAnimal ->
                    onSetAppPin(pin, favoriteAnimal)
                    showAppPinDialog = false
                    onOpenLockedApps()
                }
            )
        }

        if (showPhonePinDialog) {
            PinSetupDialog(
                title = "Set Phone PIN",
                themeColor = themeColor,
                onDismiss = { showPhonePinDialog = false },
                onSave = { pin, favoriteAnimal ->
                    onSetPhonePin(pin, favoriteAnimal)
                    showPhonePinDialog = false
                }
            )
        }
    }
}

@Composable
private fun SecuritySectionTitle(text: String, themeColor: Color) {
    Text(
        text = text,
        color = themeColor.copy(alpha = 0.8f),
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SecurityRow(
    label: String,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onClick() }
    )
}

@Composable
private fun PinSetupDialog(
    title: String,
    themeColor: Color,
    onDismiss: () -> Unit,
    onSave: (pin: String, favoriteAnimal: String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var favoriteAnimal by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(
                text = title,
                color = themeColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 6 && it.all { ch -> ch.isDigit() }) {
                            pin = it
                        }
                    },
                    label = { Text("Enter PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = {
                        if (it.length <= 6 && it.all { ch -> ch.isDigit() }) {
                            confirmPin = it
                        }
                    },
                    label = { Text("Confirm PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = favoriteAnimal,
                    onValueChange = { favoriteAnimal = it },
                    label = { Text("What is your favorite animal?") },
                    singleLine = true
                )

                if (errorText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorText ?: "",
                        color = Color.Red,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        pin.length < 4 -> {
                            errorText = "PIN must be at least 4 digits"
                        }
                        pin != confirmPin -> {
                            errorText = "PINs do not match"
                        }
                        favoriteAnimal.isBlank() -> {
                            errorText = "Please answer the recovery question"
                        }
                        else -> {
                            errorText = null
                            onSave(pin, favoriteAnimal.trim())
                        }
                    }
                }
            ) {
                Text(
                    text = "SAVE",
                    color = themeColor,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text(
                    text = "CANCEL",
                    color = themeColor.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    )
}
