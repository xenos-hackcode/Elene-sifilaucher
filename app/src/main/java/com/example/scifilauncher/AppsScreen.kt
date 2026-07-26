@file:OptIn(ExperimentalFoundationApi::class)

package com.example.scifilauncher

import android.content.SharedPreferences
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

@Composable
fun AppsScreen(
    modifier: Modifier = Modifier,
    themeColor: Color,
    apps: List<AppItem>,
    isPageMode: Boolean,
    lockedApps: Set<String>,
    hiddenApps: Set<String>,
    lockPrefs: SharedPreferences,
    lockTimeoutMinutes: Int?, // not used in lock check now, but kept if you need it elsewhere
    fontSizeOption: FontSizeOption,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    onToggleLayout: () -> Unit,
    onBackToDashboard: () -> Unit,
    onOpenRecents: () -> Unit,
    onAppClick: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onAppInfo: (String) -> Unit,
    onShare: (String) -> Unit,
    onRename: (String, String) -> Unit,
    getLastOpenedText: (String) -> String,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {}
) {
    val labelFontFamily = FontFamily.Monospace
    val labelColor = if (isDark) Color.White else Color.Black

    var showAppActionsPanel by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<AppItem?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }

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
                .padding(top = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${filteredApps.size} APPS",
                    color = themeColor.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontFamily = labelFontFamily
                )
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
                onValueChange = { onSearchQueryChange(it) },
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
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        Text(
                            text = "×",
                            color = themeColor,
                            fontSize = 18.sp,
                            fontFamily = labelFontFamily,
                            modifier = Modifier
                                .clickable { onSearchQueryChange("") }
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
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
                if (filteredApps.isEmpty()) {
                    Text(
                        text = if (searchQuery.isBlank()) {
                            "No apps here."
                        } else {
                            "No apps found for \"$searchQuery\"."
                        },
                        color = labelColor.copy(alpha = 0.6f),
                        fontSize = 13.sp * fontSizeOption.scale,
                        fontFamily = labelFontFamily,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else if (isPageMode) {
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
                                val isLockedNow = isAppLockedRightNow(
                                    prefs = lockPrefs,
                                    packageName = app.packageName,
                                    lockedApps = lockedApps
                                )

                                AppTile(
                                    app = app,
                                    labelColor = labelColor,
                                    labelFontFamily = labelFontFamily,
                                    fontSizeOption = fontSizeOption,
                                    isLocked = isLockedNow,
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
                            val isLockedNow = isAppLockedRightNow(
                                prefs = lockPrefs,
                                packageName = app.packageName,
                                lockedApps = lockedApps
                            )

                            AppTile(
                                app = app,
                                labelColor = labelColor,
                                labelFontFamily = labelFontFamily,
                                fontSizeOption = fontSizeOption,
                                isLocked = isLockedNow,
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
                onClick = { onBackToDashboard() }
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

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    showAppActionsPanel = false
                                    onShare(app.packageName)
                                    selectedApp = null
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SHARE APK",
                                color = themeColor,
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
                                    showRenameDialog = true
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "EDIT NAME",
                                color = themeColor,
                                fontSize = 13.sp,
                                fontFamily = labelFontFamily
                            )
                        }
                    }
                }
            }
        }

        if (showRenameDialog && selectedApp != null) {
            val app = selectedApp!!
            var newName by remember(app.packageName) { mutableStateOf(app.label) }

            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    showRenameDialog = false
                    selectedApp = null
                },
                title = {
                    Text("Rename app", color = themeColor, fontFamily = labelFontFamily, fontSize = 16.sp)
                },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("Name shown in this launcher") }
                    )
                },
                confirmButton = {
                    Text(
                        text = "SAVE",
                        color = themeColor,
                        fontFamily = labelFontFamily,
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable {
                                onRename(app.packageName, newName)
                                showRenameDialog = false
                                selectedApp = null
                            }
                    )
                },
                dismissButton = {
                    Text(
                        text = "CANCEL",
                        color = themeColor.copy(alpha = 0.7f),
                        fontFamily = labelFontFamily,
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable {
                                showRenameDialog = false
                                selectedApp = null
                            }
                    )
                }
            )
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
        if (app.iconBitmap != null) {
            Image(
                bitmap = app.iconBitmap.asImageBitmap(),
                contentDescription = app.label,
                modifier = Modifier
                    .size(42.dp)
                    .animatedGlitchIcon(
                        speed = 3f,
                        intensity = 0.35f,
                        slices = 6,
                        glitchColor = Color.Cyan.copy(alpha = 0.35f)
                    ),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Text(
            text = if (isLocked) "🔒 ${app.label}" else app.label,
            color = labelColor,
            fontSize = 10.sp * fontSizeOption.scale,
            fontFamily = labelFontFamily,
            maxLines = 1
        )
    }
}

/**
 * Animated glitch effect for icons using a sine-based offset over time.
 */
@Composable
fun Modifier.animatedGlitchIcon(
    speed: Float = 3f,
    intensity: Float = 0.35f,
    slices: Int = 6,
    glitchColor: Color = Color.Cyan.copy(alpha = 0.35f)
): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "glitch")
    val t by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glitch-time"
    )

    return this.then(
        Modifier.drawWithContent {
            val scope = this@drawWithContent

            if (intensity <= 0f || slices <= 0) {
                scope.drawContent()
                return@drawWithContent
            }

            val h = size.height
            val w = size.width
            val sliceHeight = h / slices

            // base icon
            scope.drawContent()

            for (i in 0 until slices) {
                val top = i * sliceHeight
                val bottom = (top + sliceHeight).coerceAtMost(h)

                val phase = t * speed + i * 0.8f
                val sinValue = sin(phase)
                val maxShift = w * 0.06f * intensity
                val randomShift = sinValue * maxShift

                val active = (sin(phase * 1.7f) + 1f) / 2f
                if (active < 0.2f) continue

                scope.clipRect(
                    left = 0f,
                    top = top,
                    right = w,
                    bottom = bottom
                ) {
                    translate(left = randomShift, top = 0f) {
                        scope.drawContent()
                    }

                    val overlayAlpha = glitchColor.alpha * active * 0.8f
                    drawRect(
                        color = glitchColor.copy(alpha = overlayAlpha),
                        blendMode = BlendMode.SrcAtop
                    )
                }
            }
        }
    )
}