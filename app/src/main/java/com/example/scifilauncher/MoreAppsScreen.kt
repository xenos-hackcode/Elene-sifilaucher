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
    val textColor = if (isDark) Color.White else Color.Black
    val baseFontSize = fontSize.sp

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (isDark) Color(0xFF050710) else Color(0xFFF2F2F2)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 40.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "< BACK",
                color = themeColor,
                fontSize = baseFontSize,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "MORE APPS",
                color = themeColor,
                fontSize = (baseFontSize.value + 4).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Released",
                color = themeColor,
                fontSize = (baseFontSize.value + 1).sp,
                fontFamily = FontFamily.Monospace
            )

            AppItem("SciFiLauncher – Xenos", "Released", textColor, baseFontSize) {
                val uri = Uri.parse("https://play.google.com/store")
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "In development",
                color = themeColor,
                fontSize = (baseFontSize.value + 1).sp,
                fontFamily = FontFamily.Monospace
            )

            AppItem("Cedal Mobile", "In progress", textColor, baseFontSize)
            AppItem("Elene AI", "Prototype", textColor, baseFontSize)
            AppItem("SYSTEM", "Coming soon", textColor, baseFontSize)
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
