package com.example.scifilauncher

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun IconPackPanel(
    themeColor: Color,
    currentPackPkg: String?,
    onSelectPack: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var localPackPkg by remember { mutableStateOf(currentPackPkg) }
    val installedPacks = remember { IconPackManager.detectInstalledPacks(context) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
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
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "< BACK",
                        color = themeColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable { onDismiss() }
                    )
                    Text(
                        text = "ICON PACK",
                        color = themeColor,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "SAVE",
                        color = themeColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable {
                            onSelectPack(localPackPkg)
                            onDismiss()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Get packs free: install Icon Pack Studio, build a pack (or export one " +
                            "already made), and it shows up below automatically - no manual setup " +
                            "on this end.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "OPEN ICON PACK STUDIO ON PLAY STORE",
                    color = themeColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(top = 6.dp, bottom = 12.dp)
                        .clickable {
                            val query = Uri.encode("Icon Pack Studio")
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$query&c=apps")))
                            }
                        }
                )

                // "Normal" always comes first - null package name means the real, unthemed icon.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { localPackPkg = null }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Normal", color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        text = if (localPackPkg == null) "SELECTED" else "",
                        color = if (localPackPkg == null) themeColor else Color.Transparent,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                installedPacks.forEach { pack ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { localPackPkg = pack.packageName }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(pack.label, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            text = if (localPackPkg == pack.packageName) "SELECTED" else "",
                            color = if (localPackPkg == pack.packageName) themeColor else Color.Transparent,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Apps the pack doesn't cover keep their real icon - no pack maps every app.",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Changes apply only when you press SAVE.",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
