package com.example.scifilauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun XenosKeyboard(
    text: String,
    onTextChange: (String) -> Unit,
    themeColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp),
        color = Color(0xFF020308),
        tonalElevation = 8.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            MatrixBackground(
                themeColor = themeColor,
                isDark = true,
                batteryMode = BatterySaverMode.OFF
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                KeyRow(
                    keys = listOf("Q","W","E","R","T","Y","U","I","O","P"),
                    text = text,
                    onTextChange = onTextChange,
                    themeColor = themeColor
                )
                KeyRow(
                    keys = listOf("A","S","D","F","G","H","J","K","L"),
                    text = text,
                    onTextChange = onTextChange,
                    themeColor = themeColor
                )
                KeyRow(
                    keys = listOf("Z","X","C","V","B","N","M"),
                    text = text,
                    onTextChange = onTextChange,
                    themeColor = themeColor
                )
                BottomKeyRow(
                    text = text,
                    onTextChange = onTextChange,
                    themeColor = themeColor
                )
            }
        }
    }
}

@Composable
private fun KeyRow(
    keys: List<String>,
    text: String,
    onTextChange: (String) -> Unit,
    themeColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        keys.forEach { label ->
            KeyButton(
                label = label,
                onClick = { onTextChange(text + label) },
                themeColor = themeColor
            )
        }
    }
}

@Composable
private fun BottomKeyRow(
    text: String,
    onTextChange: (String) -> Unit,
    themeColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        KeyButton(
            label = "SPACE",
            weight = 3f,
            onClick = { onTextChange(text + " ") },
            themeColor = themeColor
        )
        KeyButton(
            label = "DEL",
            weight = 1f,
            onClick = {
                if (text.isNotEmpty()) {
                    onTextChange(text.dropLast(1))
                }
            },
            themeColor = themeColor
        )
    }
}

@Composable
private fun KeyButton(
    label: String,
    onClick: () -> Unit,
    themeColor: Color,
    weight: Float = 1f
) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .height(42.dp)
            .background(
                color = Color(0xFF050A14).copy(alpha = 0.9f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = themeColor,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
