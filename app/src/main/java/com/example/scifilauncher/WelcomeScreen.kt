package com.example.scifilauncher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun WelcomeScreen(
    modifier: Modifier = Modifier,
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    onContinue: () -> Unit,
    onColorChange: (Boolean) -> Unit
) {
    // User's own words, this screen used to just show static "XENOS HACKER" glitch text: "it look
    // plain compared to even hide screen... cooler than our present hide screen, lock screen and
    // home screen combined". Replaced with WelcomeFaceSkeleton, a live camera-driven red eye
    // (EyeVisual) that's closed by default and opens/stares (iris tracking toward wherever your
    // face is) once the front camera actually finds one - no fixed timer, no skeleton dot cloud
    // (both removed per later feedback). EyeVisual paints its own opaque black backdrop, so
    // MatrixBackground underneath is currently always fully covered - kept as the base layer
    // anyway in case that ever changes. onColorChange/useRed (the old red<->green text-color
    // cycle) no longer applies to anything visible, kept as a harmless no-op call so the callback
    // contract doesn't change for MainActivity's caller.
    LaunchedEffect(Unit) { onColorChange(false) }

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
        WelcomeFaceSkeleton(modifier = Modifier.fillMaxSize())
    }
}