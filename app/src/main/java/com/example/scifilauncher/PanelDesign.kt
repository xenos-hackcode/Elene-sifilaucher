package com.example.scifilauncher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared visual language for list-style screens (Settings, Security, About): a flat dark
 * gradient instead of the busy animated matrix rain, plus bordered cards grouping related
 * rows - replaces the old plain-text-list-over-noisy-background layout. */

/** PanelRow/PanelToggleRow/PanelStaticInfoRow are called from ~35 places across Security and
 * Settings - reading isDark via CompositionLocal here instead of threading it through every
 * one of those call sites individually. The screen that hosts these rows wraps its content in
 * CompositionLocalProvider(LocalPanelIsDark provides isDark) once, at the top. Defaults to
 * dark (true) since that's the pre-existing hardcoded behavior wherever a screen doesn't
 * (yet) provide it explicitly. */
val LocalPanelIsDark = compositionLocalOf { true }

@Composable
fun PanelBackdrop(modifier: Modifier = Modifier, isDark: Boolean = true) {
    val colors = if (isDark) {
        listOf(Color(0xFF03040A), Color(0xFF090D16), Color(0xFF03040A))
    } else {
        listOf(Color(0xFFF5F5F5), Color(0xFFE8E8EC), Color(0xFFF5F5F5))
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = colors))
    )
}

@Composable
fun PanelSection(
    title: String,
    themeColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.padding(bottom = 18.dp)) {
        Text(
            text = title,
            color = themeColor.copy(alpha = 0.75f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        val cardColor = if (LocalPanelIsDark.current) Color(0xFF0B0F18) else Color(0xFFFFFFFF)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(cardColor)
                .border(BorderStroke(1.dp, themeColor.copy(alpha = 0.22f)), RoundedCornerShape(14.dp))
        ) {
            content()
        }
    }
}

@Composable
fun PanelDivider(themeColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(themeColor.copy(alpha = 0.08f))
    )
}

@Composable
fun PanelRow(
    label: String,
    themeColor: Color,
    value: String? = null,
    showDivider: Boolean = true,
    onInfoClick: (() -> Unit)? = null,
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
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    color = if (LocalPanelIsDark.current) Color.White else Color.Black,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (onInfoClick != null) {
                    Text(
                        text = "  (i)",
                        color = themeColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { onInfoClick() }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    Text(
                        text = value,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text(
                    text = "›",
                    color = themeColor.copy(alpha = 0.7f),
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        if (showDivider) PanelDivider(themeColor)
    }
}

@Composable
fun PanelToggleRow(
    label: String,
    themeColor: Color,
    checked: Boolean,
    showDivider: Boolean = true,
    onToggle: (Boolean) -> Unit,
    onInfoClick: (() -> Unit)? = null
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = if (LocalPanelIsDark.current) Color.White else Color.Black,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (onInfoClick != null) {
                    Text(
                        text = "  (i)",
                        color = themeColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { onInfoClick() }
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = themeColor
                )
            )
        }
        if (showDivider) PanelDivider(themeColor)
    }
}

@Composable
fun PanelStaticInfoRow(
    label: String,
    value: String,
    valueColor: Color,
    showDivider: Boolean = true,
    themeColor: Color
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = if (LocalPanelIsDark.current) Color.White else Color.Black,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                color = valueColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        if (showDivider) PanelDivider(themeColor)
    }
}
