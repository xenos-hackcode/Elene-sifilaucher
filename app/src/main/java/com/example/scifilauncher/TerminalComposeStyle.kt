package com.example.scifilauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Compose port of TerminalToolsUi's look (App Workshop/Safety tools/VPN client's shared style -
 * gradient backdrop, "XENOS / CODE" header tag, card sections) for screens that live inside
 * MainActivity's own Compose navigation instead of a separate plain Activity. Kept visually
 * identical to TerminalToolsUi on purpose (same colors/spacing), just as Compose functions instead
 * of raw Views, so a screen can adopt this look without being rewritten as its own Activity and
 * re-wiring whatever live state (hidden apps, frozen apps, etc.) it already threads through
 * MainActivity correctly today. */
object TerminalStyle {
    val ink = Color(0xFFE1EAF1)
    val muted = Color(0xFF95A7B6)
    val backgroundBrush = Brush.linearGradient(
        colors = listOf(Color(0xFF05090F), Color(0xFF0C1620), Color(0xFF04080D))
    )
    fun cardBackground() = Color(0xFF0C131D)
    // Matches TerminalToolsUi.button()'s non-primary style exactly (fill/border/radius/min-height)
    // so the back control looks identical whether a screen is a real Activity using that raw-View
    // class or one of these Compose screens.
    val buttonFill = Color(0xFF111C28)
    val buttonBorder = Color(0xFF304251)
}

/** [code] is the short "XENOS / <CODE>" tag TerminalToolsUi shows under the back button.
 * [backLabel] preserves each screen's own existing back-navigation text/target exactly - only the
 * visual chrome changes here, not what tapping back actually does. */
@Composable
fun TerminalScaffold(
    themeColor: Color,
    code: String,
    title: String,
    subtitle: String,
    backLabel: String = "‹  BACK",
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalStyle.backgroundBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = backLabel,
                color = TerminalStyle.ink,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TerminalStyle.buttonFill)
                    .border(1.dp, TerminalStyle.buttonBorder, RoundedCornerShape(12.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 14.dp, vertical = 16.dp)
            )
            Text(
                text = "XENOS  /  $code",
                color = themeColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = title,
                color = TerminalStyle.ink,
                fontSize = 28.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = TerminalStyle.muted,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(top = 16.dp, bottom = 12.dp)
                    .background(Brush.horizontalGradient(listOf(themeColor, Color.Transparent)))
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
                content = content
            )
        }
    }
}

/** A TerminalToolsUi card(): a labeled, bordered rounded container - [label] rendered the same
 * accent-colored small-caps style TerminalToolsUi uses for its own card headers. */
@Composable
fun TerminalCard(
    label: String,
    themeColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(TerminalStyle.cardBackground())
            .border(1.dp, themeColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            text = label,
            color = themeColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}
