package com.example.scifilauncher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One built-in tool this launcher has - what it does and how you actually control it (tap, or
 * a specific voice phrase), plus how to open it ([onOpen] is null for a tool that's only ever
 * reached by voice/quick-toggle, with no dedicated screen of its own to jump to). */
data class ToolMarketEntry(
    val name: String,
    val whatItDoes: String,
    val howToControl: String,
    val onOpen: (() -> Unit)? = null
)

/** Settings > Tools Market (also reachable from the quick-settings panel's own tile) - a
 * directory of every real tool built into this launcher, not third-party apps. User: "create a
 * new app called tools market in there different tools used on phone the way u can control it
 * would be listed". */
@Composable
fun ToolsMarketScreen(
    themeColor: Color,
    isDark: Boolean,
    tools: List<ToolMarketEntry>,
    onBack: () -> Unit
) {
    TerminalScaffold(
        themeColor = themeColor,
        code = "MARKET",
        title = "Tools market",
        subtitle = "Every built-in tool this launcher has, and how to control it.",
        backLabel = "‹  BACK",
        onBack = onBack
    ) {
        TerminalCard(label = "TOOLS (${tools.size})", themeColor = themeColor) {
            tools.forEachIndexed { index, tool ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (tool.onOpen != null) Modifier.clickable { tool.onOpen.invoke() } else Modifier)
                        .padding(vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = tool.name,
                            color = themeColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        if (tool.onOpen != null) {
                            Text(
                                text = "OPEN ›",
                                color = themeColor.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Text(
                        text = tool.whatItDoes,
                        color = TerminalStyle.ink,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    Text(
                        text = "Control: ${tool.howToControl}",
                        color = TerminalStyle.muted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
