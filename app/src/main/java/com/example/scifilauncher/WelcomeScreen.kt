package com.example.scifilauncher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

@Composable
fun WelcomeScreen(
    modifier: Modifier = Modifier,
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    onContinue: () -> Unit,
    onColorChange: (Boolean) -> Unit
) {
    var useRed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onColorChange(useRed)
        while (true) {
            delay(300_000)
            useRed = !useRed
            onColorChange(useRed)
        }
    }

    val currentColor = if (useRed) Color.Red else MatrixGreen

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onContinue),
        contentAlignment = Alignment.Center
    ) {
        MatrixBackground(
            themeColor = themeColor,
            isDark = isDark,
            batteryMode = batteryMode,
        )
        GlitchTitle(
            text = "XENOS HACKER",
            color = currentColor,
            fontSize = 32f
        )
    }
}