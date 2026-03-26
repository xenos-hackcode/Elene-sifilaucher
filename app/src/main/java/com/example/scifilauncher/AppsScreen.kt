@file:OptIn(ExperimentalFoundationApi::class)

package com.example.scifilauncher

import android.content.Intent
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import kotlin.math.ceil

@Composable
fun AppsScreen(
    modifier: Modifier = Modifier,
    themeColor: Color,
    apps: List<AppItem>,
    isPageMode: Boolean,
    lockedApps: Set<String>,
    hiddenApps: Set<String>,
    fontSizeOption: FontSizeOption,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    onToggleLayout: () -> Unit,
    onBackToDashboard: () -> Unit,
    onOpenRecents: () -> Unit,
    onAppClick: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onAppInfo: (String) -> Unit,
    getLastOpenedText: (String) -> String
) {
    val context = LocalContext.current

    val labelFontFamily = FontFamily.Monospace
    val labelColor = if (isDark) Color.White else Color.Black

    var searchQuery by remember { mutableStateOf("") }
    var showAppActionsPanel by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<AppItem?>(null) }

    val filteredApps = remember(apps, searchQuery, hiddenApps) {
        val visibleApps = apps.filter { it.packageName !in hiddenApps }
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) visibleApps
        else visibleApps.filter { it.label.lowercase().contains(q) }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        MatrixBackground(
            themeColor = themeColor,
            isDark = isDark,
            batteryMode = batteryMode
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isPageMode) "PAGE" else "GRID",
                    color = themeColor,
                    fontSize = 14.sp,
                    fontFamily = labelFontFamily,
                    modifier = Modifier.clickable { onToggleLayout() }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                textStyle = TextStyle(
                    color = themeColor,
                    fontFamily = labelFontFamily,
                    fontSize = 13.sp * fontSizeOption.scale
                ),
                placeholder = {
                    Text(
                        text = "SEARCH",
                        color = themeColor.copy(alpha = 0.5f),
                        fontFamily = labelFontFamily,
                        fontSize = 12.sp * fontSizeOption.scale
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 72.dp)
            ) {
                if (isPageMode) {
                    val pageSize = 20
                    val pageCount = remember(filteredApps) {
                        ceil(filteredApps.size / pageSize.toFloat())
                            .toInt().coerceAtLeast(1)
                    }
                    val pagerState = rememberPagerState(pageCount = { pageCount })

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        val start = page * pageSize
                        val end = (start + pageSize).coerceAtMost(filteredApps.size)
                        val pageApps = filteredApps.subList(start, end)

                        LazyVerticalGrid(
                            modifier = Modifier.fillMaxSize(),
                            columns = GridCells.Adaptive(minSize = 72.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(pageApps, key = { it.packageName }) { app ->
                                AppTile(
                                    app = app,
                                    labelColor = labelColor,
                                    labelFontFamily = labelFontFamily,
                                    fontSizeOption = fontSizeOption,
                                    isLocked = lockedApps.contains(app.packageName),
                                    onTap = { onAppClick(app.packageName) },
                                    onLongPress = {
                                        selectedApp = app
                                        showAppActionsPanel = true
                                    }
                                )
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        modifier = Modifier.fillMaxSize(),
                        columns = GridCells.Adaptive(minSize = 72.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            AppTile(
                                app = app,
                                labelColor = labelColor,
                                labelFontFamily = labelFontFamily,
                                fontSizeOption = fontSizeOption,
                                isLocked = lockedApps.contains(app.packageName),
                                onTap = { onAppClick(app.packageName) },
                                onLongPress = {
                                    selectedApp = app
                                    showAppActionsPanel = true
                                }
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlitchNavLetter(
                letter = "N",
                color = themeColor,
                onClick = { onBackToDashboard() }
            )

            GlitchNavLetter(
                letter = "O",
                color = themeColor,
                onClick = {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            )

            GlitchNavLetter(
                letter = "S",
                color = themeColor,
                onClick = { onOpenRecents() }
            )
        }

        if (showAppActionsPanel && selectedApp != null) {
            val app = selectedApp!!
            val lastOpened = remember(app.packageName) { getLastOpenedText(app.packageName) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        showAppActionsPanel = false
                        selectedApp = null
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    color = Color(0xFF05070B),
                    tonalElevation = 8.dp,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = app.label,
                            color = themeColor,
                            fontSize = 14.sp,
                            fontFamily = labelFontFamily,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = lastOpened,
                            color = Color.Gray,
                            fontSize = 11.sp,
                            fontFamily = labelFontFamily,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    showAppActionsPanel = false
                                    selectedApp = null
                                    onUninstall(app.packageName)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "UNINSTALL",
                                color = Color.Red,
                                fontSize = 13.sp,
                                fontFamily = labelFontFamily
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    showAppActionsPanel = false
                                    selectedApp = null
                                    onAppInfo(app.packageName)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "APP INFO",
                                color = themeColor,
                                fontSize = 13.sp,
                                fontFamily = labelFontFamily
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppTile(
    app: AppItem,
    labelColor: Color,
    labelFontFamily: FontFamily,
    fontSizeOption: FontSizeOption,
    isLocked: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isLocked) "🔒 ${app.label}" else app.label,
            color = labelColor,
            fontSize = 10.sp * fontSizeOption.scale,
            fontFamily = labelFontFamily,
            maxLines = 1
        )
    }
}
