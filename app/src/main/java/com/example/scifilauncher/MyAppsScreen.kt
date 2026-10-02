package com.example.scifilauncher

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Apps requested from Xenos ("create an app that does X") and approved via fingerprint - a
 * separate list from the main Updates screen (which is about changes to THIS app) since these are
 * brand-new, standalone apps built by a completely separate cloud pipeline. Each APPROVED entry
 * here is polled against the backend for a real build result - nothing here is simulated or
 * assumed done just because it was approved. */
@Composable
fun MyAppsScreen(
    themeColor: Color,
    isDark: Boolean,
    entries: List<UpdateProposalEntry>,
    onBack: () -> Unit
) {
    val newAppEntries = remember(entries) { entries.filter { it.kind == ProposalKind.NEW_APP } }
    val textColor = if (isDark) Color.White else Color.Black

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xFF050505) else Color(0xFFF5F5F5))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 40.dp)
        ) {
            Text(
                text = "< BACK",
                color = themeColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(bottom = 12.dp)
            )
            Text(
                text = "MY APPS",
                color = themeColor,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Apps you asked Xenos to create. Approved ones are built by a separate cloud pipeline - this checks in on real progress, not a guess.",
                color = Color.Gray,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (newAppEntries.any { it.status == UpdateProposalStatus.APPROVED }) {
                NextCheckCountdown(themeColor)
                Spacer(Modifier.height(12.dp))
            }

            if (newAppEntries.isEmpty()) {
                Text(
                    text = "No app requests yet - ask Xenos to create one.",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(newAppEntries) { entry ->
                        MyAppRow(entry, themeColor, textColor)
                    }
                }
            }
        }
    }
}

// The new-app cloud routine runs on a fixed cron schedule (every 4 hours, at minute 4, UTC -
// "4 (every 4th hour) * * *") - not something the phone can query directly, but a fixed cron IS
// something it can compute the next firing of itself, so this is real information, not a guess
// or a fake progress bar. Only shown while at least one request is still APPROVED (i.e. genuinely
// waiting on the routine to pick it up), since it's meaningless once nothing's pending.
@Composable
private fun NextCheckCountdown(themeColor: Color) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val nextRunMillis = remember(now / 60_000L) { nextRoutineRunMillis(now) }
    val remainingMs = (nextRunMillis - now).coerceAtLeast(0L)
    val hours = remainingMs / 3_600_000L
    val minutes = (remainingMs / 60_000L) % 60
    val seconds = (remainingMs / 1_000L) % 60
    val localTimeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(themeColor.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Next automatic check",
            color = Color.Gray,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "%02d:%02d:%02d  (%s)".format(hours, minutes, seconds, localTimeFmt.format(Date(nextRunMillis))),
            color = themeColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** Next UTC firing of the routine's cron schedule strictly after [afterMillis] - minute 4 of
 * every hour divisible by 4 (00:04, 04:04, 08:04, 12:04, 16:04, 20:04 UTC). Matches the actual
 * routine exactly, since that's the cron expression it was created with (see planner/done.md). */
private fun nextRoutineRunMillis(afterMillis: Long): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = afterMillis
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.set(Calendar.MINUTE, 4)
    cal.set(Calendar.HOUR_OF_DAY, (cal.get(Calendar.HOUR_OF_DAY) / 4) * 4)
    if (cal.timeInMillis <= afterMillis) cal.add(Calendar.HOUR_OF_DAY, 4)
    return cal.timeInMillis
}

@Composable
private fun MyAppRow(entry: UpdateProposalEntry, themeColor: Color, textColor: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sdf = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    var buildStatus by remember(entry.id) { mutableStateOf<NewAppStatus?>(null) }
    var installing by remember(entry.id) { mutableStateOf(false) }

    // Only approved proposals ever got a GitHub issue filed - PROPOSED/DENIED ones have nothing
    // to poll for. Re-polls every 20s until a terminal (ready/failed) result comes back, since a
    // real Cloud Build run genuinely takes minutes, not seconds.
    LaunchedEffect(entry.id, entry.status) {
        if (entry.status != UpdateProposalStatus.APPROVED) return@LaunchedEffect
        while (buildStatus?.status != "ready" && buildStatus?.status != "failed") {
            buildStatus = EleneApiClient.fetchNewAppStatus(entry.id)
            if (buildStatus?.status == "ready" || buildStatus?.status == "failed") break
            delay(20_000L)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            text = entry.title,
            color = textColor,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = entry.description,
            color = Color.Gray,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (label, color) = when {
                entry.status == UpdateProposalStatus.DENIED -> "Denied" to Color.Red
                entry.status == UpdateProposalStatus.PROPOSED -> "Awaiting your approval" to Color.Gray
                buildStatus?.status == "ready" -> "Ready to install" to Color(0xFF00E676)
                buildStatus?.status == "failed" -> "Build failed" to Color.Red
                else -> "Building..." to themeColor
            }
            if (label == "Building...") {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), color = themeColor, strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = 0.2f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(text = label, color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = sdf.format(Date(entry.timestamp)),
                color = Color.Gray,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        buildStatus?.message?.takeIf { buildStatus?.status == "failed" }?.let { msg ->
            Text(text = msg, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
        }
        if (buildStatus?.status == "ready") {
            val downloadUrl = buildStatus?.downloadUrl
            Text(
                text = if (installing) "Downloading..." else "INSTALL",
                color = if (installing) Color.Gray else themeColor,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(themeColor.copy(alpha = if (installing) 0.05f else 0.15f))
                    .clickable(enabled = !installing && downloadUrl != null) {
                        installing = true
                        scope.launch {
                            val installed = downloadAndOpenApk(context, downloadUrl!!, entry.title)
                            installing = false
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/** Downloads the signed APK from [url] into cacheDir/shared_apks (already FileProvider-exposed,
 * see filepaths.xml - originally added for sharing an installed app's own APK) and hands it to
 * Android's normal package installer via ACTION_VIEW, same as opening any other file - this is
 * the one-tap "install unknown app" confirmation the user explicitly chose over a silent install. */
private suspend fun downloadAndOpenApk(context: android.content.Context, url: String, appTitle: String): Boolean =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val client = OkHttpClient.Builder().build()
            val request = Request.Builder().url(url).get().build()
            val outDir = File(context.cacheDir, "shared_apks").apply { mkdirs() }
            val safeName = appTitle.filter { it.isLetterOrDigit() }.take(40).ifBlank { "app" }
            val outFile = File(outDir, "$safeName.apk")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                outFile.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension("apk") ?: "application/vnd.android.package-archive"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
