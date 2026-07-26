package com.example.scifilauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown after Sequence Mode's delayed wipe runs - permanent, no way back in from here. */
@Composable
fun WipedScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "This launcher's protected data has been wiped after an unrecovered " +
                "Sequence Mode lockdown. Reinstall the app to start over.",
            color = Color.Red,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
