package com.example.scifilauncher

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

/** Browses the phone's shared/external storage (Downloads, Pictures, DCIM, Documents, Music,
 * Movies, and each app's public Android/data folder) - the same tier of storage a laptop sees
 * over USB file transfer. This deliberately does NOT reach other apps' private internal
 * storage (/data/data/<package>/) - that tier isn't accessible to any app, including this one,
 * without root; see the Security screen's Cedal Shared System / lockdown disclosures for why
 * that boundary is respected rather than worked around. */
@Composable
fun FileBrowserScreen(
    themeColor: Color,
    isDark: Boolean,
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val rootDir = remember { Environment.getExternalStorageDirectory() }
    var currentDir by remember { mutableStateOf(rootDir) }
    val textColor = TerminalStyle.ink

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalStyle.backgroundBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp, top = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "‹  SECURITY",
                    color = textColor,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        val parent = currentDir.parentFile
                        if (currentDir != rootDir && parent != null) {
                            currentDir = parent
                        } else {
                            onBack()
                        }
                    }
                )
            }
            Text(
                text = "XENOS  /  FILES",
                color = themeColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "File manager",
                color = textColor,
                fontSize = 28.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = currentDir.absolutePath.removePrefix(rootDir.absolutePath).ifBlank { "/" },
                color = TerminalStyle.muted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(top = 16.dp, bottom = 12.dp)
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(themeColor, Color.Transparent)))
            )

            if (!hasAccess) {
                Text(
                    text = "Browsing shared storage needs the \"All files access\" " +
                            "permission - the same tier of storage a laptop sees over USB " +
                            "(Downloads, Pictures, Documents, DCIM, and so on). This never " +
                            "reaches other apps' private data; that stays off-limits.",
                    color = textColor,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Text(
                    text = "GRANT ACCESS",
                    color = Color.Black,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(themeColor, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clickable(onClick = onRequestAccess)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            } else {
                val entries = remember(currentDir) {
                    currentDir.listFiles()
                        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        ?: emptyList()
                }

                if (entries.isEmpty()) {
                    Text(
                        text = "This folder is empty.",
                        color = TerminalStyle.muted,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(entries) { entry ->
                            FileRow(
                                file = entry,
                                themeColor = themeColor,
                                textColor = textColor,
                                onClick = {
                                    if (entry.isDirectory) {
                                        currentDir = entry
                                    } else {
                                        openFile(context, entry)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: File,
    themeColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (file.isDirectory) "📁" else "📄",
                fontSize = 16.sp,
                modifier = Modifier.padding(end = 10.dp)
            )
            Text(
                text = file.name,
                color = textColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        if (!file.isDirectory) {
            Text(
                text = formatFileSize(file.length()),
                color = themeColor.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/** Opens [file] the same way any normal file manager does: hand it to whichever app on the
 * phone already claims that file type, via a FileProvider URI rather than a raw file:// path. */
private fun openFile(context: android.content.Context, file: File) {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return

    val extension = file.extension.lowercase()
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
