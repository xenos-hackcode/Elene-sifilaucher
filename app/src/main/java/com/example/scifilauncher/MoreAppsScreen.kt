package com.example.scifilauncher

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MoreAppsScreen(
    themeColor: Color,
    isDark: Boolean,
    fontSize: Float,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val baseFontSize = fontSize.sp

    TerminalScaffold(
        themeColor = themeColor,
        code = "APPS",
        title = "More apps",
        subtitle = "",
        backLabel = "‹  SETTINGS",
        onBack = onBack
    ) {
        TerminalCard(label = "RELEASED", themeColor = themeColor) {
            AppItem("SciFiLauncher – Xenos", "Released", TerminalStyle.ink, baseFontSize) {
                val uri = Uri.parse("https://play.google.com/store")
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }

        TerminalCard(label = "IN DEVELOPMENT", themeColor = themeColor) {
            AppItem("Cedal Mobile", "In progress", TerminalStyle.ink, baseFontSize)
            AppItem("Elene AI", "Prototype", TerminalStyle.ink, baseFontSize)
            AppItem("SYSTEM", "Coming soon", TerminalStyle.ink, baseFontSize)
        }
    }
}

@Composable
fun AppItem(
    name: String,
    status: String,
    textColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .then(
                if (onClick != null) Modifier.clickable { onClick() } else Modifier
            )
    ) {
        Text(
            text = name,
            color = textColor,
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = status,
            color = textColor.copy(alpha = 0.7f),
            fontSize = (fontSize.value - 2).sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
