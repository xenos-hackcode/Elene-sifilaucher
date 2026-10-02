package com.example.scifilauncher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings > Info - points each generic voice role ("music", "chat", "call") at one real
 * installed app, so "open music" / "play music" / "open chat" / "open call" always go to the
 * exact app you pick here, never a guessed search match. The same app can be assigned to more
 * than one role. User: "set music with list of apps that i want as music would be put so if i
 * say open music it opens that particular app ... only 1 app for each and it can be the same app
 * for all". */
@Composable
fun InfoScreen(
    themeColor: Color,
    isDark: Boolean,
    apps: List<AppItem>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var expandedRole by remember { mutableStateOf<AppRole?>(null) }
    var version by remember { mutableStateOf(0) }

    TerminalScaffold(
        themeColor = themeColor,
        code = "INFO",
        title = "App roles",
        subtitle = "Point a voice role at one real app - \"open music\", \"play music\", \"open chat\", \"open call\" always go to the app you pick here.",
        backLabel = "‹  SETTINGS",
        onBack = onBack
    ) {
        TerminalCard(label = "ROLES", themeColor = themeColor) {
            AppRole.entries.forEach { role ->
                version // read so this card recomposes after a pick below
                val assignedLabel = AppRoleStore.labelFor(context, role, apps)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedRole = if (expandedRole == role) null else role },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = role.label,
                            color = TerminalStyle.ink,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = assignedLabel ?: "Not set",
                            color = if (assignedLabel != null) themeColor else TerminalStyle.ink.copy(alpha = 0.45f),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (expandedRole == role) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp)) {
                            if (AppRoleStore.get(context, role) != null) {
                                Text(
                                    text = "Clear assignment",
                                    color = Color(0xFFE05C5C),
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .clickable {
                                            AppRoleStore.set(context, role, null)
                                            expandedRole = null
                                            version++
                                        }
                                )
                            }
                            apps.forEach { app ->
                                Text(
                                    text = app.label,
                                    color = TerminalStyle.ink.copy(alpha = 0.85f),
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .clickable {
                                            AppRoleStore.set(context, role, app.packageName)
                                            expandedRole = null
                                            version++
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
