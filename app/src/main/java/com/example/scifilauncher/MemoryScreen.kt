package com.example.scifilauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the user has told Elene to remember - the durable fallback for the backend's own
 * conversation memory, which is only in-memory and resets whenever the Cloud Run instance
 * recycles. Tap the delete mark to forget an entry - always asks first, no instant/accidental
 * delete on a plain row tap. */
@Composable
fun MemoryScreen(
    themeColor: Color,
    isDark: Boolean,
    entries: List<RememberedFact>,
    onBack: () -> Unit,
    onForget: (RememberedFact) -> Unit
) {
    val textColor = if (isDark) Color.White else Color.Black
    val sdf = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    var pendingForget by remember { mutableStateOf<RememberedFact?>(null) }

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
                text = "MEMORY",
                color = themeColor,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Everything you've told Elene to remember - tap ✕ to forget an entry.",
                color = Color.Gray,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (entries.isEmpty()) {
                Text(
                    text = "Nothing remembered yet - say \"remember that...\" to Elene.",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.text,
                                    color = textColor,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = sdf.format(Date(entry.timestamp)),
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Text(
                                text = "✕",
                                color = Color(0xFFFF5252),
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .clickable { pendingForget = entry }
                                    .padding(start = 12.dp, top = 2.dp, bottom = 8.dp, end = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        val target = pendingForget
        if (target != null) {
            AlertDialog(
                onDismissRequest = { pendingForget = null },
                title = { Text("Forget this?", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                text = { Text(target.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                confirmButton = {
                    TextButton(onClick = {
                        onForget(target)
                        pendingForget = null
                    }) { Text("FORGET", color = Color(0xFFFF5252), fontFamily = FontFamily.Monospace) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingForget = null }) {
                        Text("CANCEL", color = themeColor, fontFamily = FontFamily.Monospace)
                    }
                }
            )
        }
    }
}
