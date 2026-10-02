package com.example.scifilauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every proposed change to this app - whether Xenos asked for it directly or Elene suggested
 * it herself - and how it was resolved. APPROVED means fingerprint-confirmed, not "done" - there
 * is no build/deploy pipeline behind this yet, only the approval queue itself. */
@Composable
fun UpdatesScreen(
    themeColor: Color,
    isDark: Boolean,
    entries: List<UpdateProposalEntry>,
    onBack: () -> Unit,
    onReview: (UpdateProposalEntry) -> Unit
) {
    var filter by remember { mutableStateOf<UpdateProposalStatus?>(null) }
    val textColor = if (isDark) Color.White else Color.Black
    val filtered = remember(entries, filter) {
        if (filter == null) entries else entries.filter { it.status == filter }
    }

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
                text = "UPDATES",
                color = themeColor,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Every proposed change - tap a PROPOSED entry to review and approve with your fingerprint.",
                color = Color.Gray,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UpdateFilterChip("ALL", filter == null, themeColor) { filter = null }
                UpdateFilterChip("PROPOSED", filter == UpdateProposalStatus.PROPOSED, themeColor) { filter = UpdateProposalStatus.PROPOSED }
                UpdateFilterChip("APPROVED", filter == UpdateProposalStatus.APPROVED, themeColor) { filter = UpdateProposalStatus.APPROVED }
                UpdateFilterChip("DENIED", filter == UpdateProposalStatus.DENIED, themeColor) { filter = UpdateProposalStatus.DENIED }
            }

            Spacer(Modifier.height(16.dp))

            if (filtered.isEmpty()) {
                Text(
                    text = "No updates yet.",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered) { entry ->
                        UpdateRow(entry, themeColor, textColor, onReview)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateFilterChip(label: String, active: Boolean, themeColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) themeColor else Color.Gray.copy(alpha = 0.2f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (active) Color.Black else Color.White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun categoryLabel(category: UpdateCategory): String = when (category) {
    UpdateCategory.FEATURE_ADDED -> "FEATURE"
    UpdateCategory.BUG_FIX -> "FIX"
    UpdateCategory.FEATURE_REMOVED -> "REMOVED"
    UpdateCategory.OTHER -> "OTHER"
}

private fun categoryColor(category: UpdateCategory): Color = when (category) {
    UpdateCategory.FEATURE_ADDED -> Color(0xFF00E676)
    UpdateCategory.BUG_FIX -> Color(0xFF29B6F6)
    UpdateCategory.FEATURE_REMOVED -> Color(0xFFFF7043)
    UpdateCategory.OTHER -> Color.Gray
}

@Composable
private fun UpdateRow(entry: UpdateProposalEntry, themeColor: Color, textColor: Color, onReview: (UpdateProposalEntry) -> Unit) {
    val statusColor = when (entry.status) {
        UpdateProposalStatus.APPROVED -> Color(0xFF00E676)
        UpdateProposalStatus.DENIED -> Color.Red
        UpdateProposalStatus.PROPOSED -> themeColor
    }
    val sdf = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val reviewable = entry.status == UpdateProposalStatus.PROPOSED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (reviewable) Modifier.clickable { onReview(entry) } else Modifier)
            .padding(vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                val badgeColor = if (entry.kind == ProposalKind.NEW_APP) Color(0xFF00B8D4) else categoryColor(entry.category)
                val badgeLabel = if (entry.kind == ProposalKind.NEW_APP) "NEW APP" else categoryLabel(entry.category)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeLabel,
                        color = badgeColor,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.title,
                    color = textColor,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = entry.status.name,
                color = statusColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = if (entry.origin == ProposalOrigin.USER) "You asked" else "Xenos suggested this",
            color = Color.Gray,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = entry.description,
            color = Color.Gray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = sdf.format(Date(entry.timestamp)),
            color = Color.Gray,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
