package com.example.scifilauncher

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*

@Composable
fun PinCheckDialog(
    themeColor: Color,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(
                text = "ENTER APP PIN",
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
                        if (it.length <= 6 && it.all { ch -> ch.isDigit() }) pin = it
                    },
                    label = { Text("PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error!!,
                        color = Color.Red,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pin.isBlank()) {
                    error = "Enter your PIN"
                } else {
                    error = null
                    onSuccess(pin)
                }
            }) {
                Text("UNLOCK", color = themeColor, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("CANCEL", color = themeColor.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace)
            }
        }
    )
}
