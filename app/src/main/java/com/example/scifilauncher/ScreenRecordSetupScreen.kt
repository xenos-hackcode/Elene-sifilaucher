package com.example.scifilauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ScreenRecordSetupScreen(
    themeColor: Color,
    isDark: Boolean,
    selectedArea: android.graphics.Rect?,
    onBack: () -> Unit,
    onChoosePartialArea: () -> Unit,
    onSelectFullScreen: () -> Unit,
    onStart: (RecordAudioMode) -> Unit
) {
    var audioMode by remember { mutableStateOf(RecordAudioMode.NONE) }

    Box(modifier = Modifier.fillMaxSize()) {
        PanelBackdrop(isDark = isDark)
        CompositionLocalProvider(LocalPanelIsDark provides isDark) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 40.dp)
            ) {
                Text(
                    text = "< DASH",
                    color = themeColor,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 16.dp).clickable { onBack() }
                )
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text(
                    text = "SCREEN RECORD SETUP",
                    color = if (isDark) Color.White else Color.Black,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                PanelSection(title = "AREA", themeColor = themeColor) {
                    RadioRow(
                        label = "Full screen",
                        selected = selectedArea == null,
                        themeColor = themeColor,
                        onClick = onSelectFullScreen
                    )
                    RadioRow(
                        label = "Partial screen",
                        value = selectedArea?.let { "${it.width()}×${it.height()}px selected" } ?: "tap to choose region",
                        selected = selectedArea != null,
                        themeColor = themeColor,
                        showDivider = false,
                        onClick = onChoosePartialArea
                    )
                }

                PanelSection(title = "AUDIO", themeColor = themeColor) {
                    RadioRow(
                        label = "None",
                        selected = audioMode == RecordAudioMode.NONE,
                        themeColor = themeColor,
                        onClick = { audioMode = RecordAudioMode.NONE }
                    )
                    RadioRow(
                        label = "Microphone",
                        selected = audioMode == RecordAudioMode.MIC,
                        themeColor = themeColor,
                        showDivider = false,
                        onClick = { audioMode = RecordAudioMode.MIC }
                    )
                }

                Text(
                    text = "\"Media\" and \"Mic + media\" audio (capturing other apps' sound) aren't available yet - only Microphone and None for now.",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 4.dp, bottom = 16.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(themeColor)
                        .clickable { onStart(audioMode) }
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "● START RECORDING",
                        color = Color.Black,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun RadioRow(
    label: String,
    themeColor: Color,
    selected: Boolean,
    value: String? = null,
    showDivider: Boolean = true,
    onClick: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (selected) themeColor else Color.Transparent)
                        .then(
                            if (!selected) Modifier.background(Color.Gray.copy(alpha = 0.15f), CircleShape) else Modifier
                        )
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = label,
                        color = if (LocalPanelIsDark.current) Color.White else Color.Black,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (value != null) {
                        Text(
                            text = value,
                            color = Color.Gray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
        if (showDivider) PanelDivider(themeColor)
    }
}
