package com.example.scifilauncher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
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
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val gridColor = if (isDark) Color(0xFF7395B8).copy(alpha = 0.045f) else Color(0xFF182A40).copy(alpha = 0.035f)
            val step = 44.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
                y += step
            }
        }
    }
}

@Composable
fun PanelSection(
    title: String,
    themeColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardColor = if (LocalPanelIsDark.current) Color(0xFF0C131D) else Color.White
    Column(
        modifier = modifier.padding(bottom = 14.dp).fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardColor)
            .border(BorderStroke(1.dp, themeColor.copy(alpha = 0.26f)), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp)
    ) {
        Text(title, color = themeColor, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp))
        content()
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
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
                .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = if (LocalPanelIsDark.current) Color(0xFFE1EAF1) else Color(0xFF172432),
                    fontSize = 15.sp, fontFamily = FontFamily.Default)
                if (!value.isNullOrBlank()) {
                    Text(value, color = if (LocalPanelIsDark.current) Color(0xFF95A7B6) else Color(0xFF526477),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 5.dp))
                }
            }
            if (onInfoClick != null) {
                TextButton(onClick = onInfoClick,
                    modifier = Modifier.semantics { contentDescription = "More information: " + label }) {
                    Text("?", color = themeColor)
                }
            }
            Text("?", color = themeColor, fontSize = 24.sp,
                modifier = Modifier.padding(start = 10.dp))
        }
        if (showDivider) PanelDivider(themeColor)
    }
}

/** Plain status display (no navigation/click target) - a colored dot + ON/OFF, used by the
 * Security screen's "PROTECTION STATUS" summary so the user can see what's actually active at a
 * glance instead of opening every row to check. */
@Composable
fun ProtectionStatusRow(
    label: String,
    active: Boolean,
    themeColor: Color,
    showDivider: Boolean = true
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = if (LocalPanelIsDark.current) Color.White else Color.Black,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(color = if (active) themeColor else Color.Gray)
                }
                Text(
                    text = if (active) "  ON" else "  OFF",
                    color = if (active) themeColor else Color.Gray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 4.dp)
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
    enabled: Boolean = true,
    disabledHint: String? = null,
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
                Column {
                    Text(
                        text = label,
                        color = if (!enabled) Color.Gray else if (LocalPanelIsDark.current) Color.White else Color.Black,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (!enabled && disabledHint != null) {
                        Text(
                            text = disabledHint,
                            color = Color.Gray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
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
                enabled = enabled,
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, color = if (LocalPanelIsDark.current) Color(0xFFE1EAF1) else Color(0xFF172432),
                fontSize = 15.sp)
            Text(value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 5.dp))
        }
        if (showDivider) PanelDivider(themeColor)
    }
}


@Composable
fun PanelScreenHeader(code: String, title: String, subtitle: String, themeColor: Color, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()
            .border(1.dp, themeColor.copy(alpha = 0.22f), RoundedCornerShape(12.dp))) {
            Text(tr("back_dash"), color = themeColor, fontFamily = FontFamily.Monospace)
        }
        Text("XENOS / " + code, color = themeColor, fontSize = 11.sp, letterSpacing = 1.5.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 14.dp))
        Text(title, color = if (LocalPanelIsDark.current) Color(0xFFE1EAF1) else Color(0xFF172432),
            fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp))
        Text(subtitle, color = if (LocalPanelIsDark.current) Color(0xFF95A7B6) else Color(0xFF526477),
            fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun PanelChoiceRow(label: String, selected: Boolean, themeColor: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(if (selected) themeColor.copy(alpha = 0.13f) else Color.Transparent)
        .border(1.dp, themeColor.copy(alpha = if (selected) 0.6f else 0.15f), RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).heightIn(min = 52.dp).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 15.sp,
            color = if (LocalPanelIsDark.current) Color(0xFFE1EAF1) else Color(0xFF172432))
        if (selected) Text("?", color = themeColor, modifier = Modifier.padding(start = 12.dp))
    }
}
