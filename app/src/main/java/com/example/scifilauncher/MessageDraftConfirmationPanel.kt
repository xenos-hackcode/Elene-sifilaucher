package com.example.scifilauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown before any message Elene drafts (or dictates on your behalf) actually sends - a
 * reply to the last message, or a new message to any contact. Deliberately NOT the same
 * component as DeviceActionConfirmationPanel: sending a message isn't a device-owner action,
 * so there's no fingerprint requirement here, and this needs an editable text field the other
 * panel doesn't have. Nothing sends until SEND is tapped; editing the draft or asking for a
 * re-read never triggers a send by itself. */
@Composable
fun MessageDraftConfirmationPanel(
    themeColor: Color,
    isDark: Boolean,
    recipientLabel: String,
    channelLabel: String,
    draftText: String,
    onDraftTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onRereadTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isDark) Color(0xFF0B0F18) else Color(0xFFEFEFEF))
                .padding(20.dp)
        ) {
            Text(
                text = "MESSAGE DRAFT",
                color = themeColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "To $recipientLabel via $channelLabel",
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draftText,
                onValueChange = onDraftTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            )

            Spacer(Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DraftConfirmButton("SEND", themeColor, Modifier.weight(1f), filled = true, onClick = onSend)
                DraftConfirmButton("CANCEL", Color.Gray, Modifier.weight(1f), filled = false, onClick = onCancel)
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = "🔊 RE-READ IT",
                color = themeColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRereadTap)
                    .padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun DraftConfirmButton(
    label: String,
    color: Color,
    modifier: Modifier,
    filled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled) color else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (filled) Color.Black else color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
