package com.example.scifilauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Full-screen lock shown over the launcher's own home screen when it wakes and a passcode is
 * set - fingerprint (primary), enrolled-voice (when 2-Step Verify is on), and passcode fallback.
 * Reconstructed after an accidental deletion (original had no git history) - rebuilt to match
 * MainActivity's exact call site and the app's existing Matrix-background visual language; not
 * guaranteed pixel-identical to the original. */
@Composable
fun LauncherLockGate(
    themeColor: Color,
    isDark: Boolean,
    batteryMode: BatterySaverMode,
    twoStepVerifyEnabled: Boolean,
    onFingerprintTap: () -> Unit,
    onVoiceTap: () -> Unit,
    onPasscodeTap: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        MatrixBackground(
            themeColor = themeColor,
            isDark = isDark,
            batteryMode = batteryMode
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "LOCKED",
                color = themeColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .clickable { onFingerprintTap() },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_fingerprint),
                    contentDescription = "Unlock with fingerprint",
                    colorFilter = ColorFilter.tint(themeColor),
                    modifier = Modifier.size(64.dp)
                )
            }

            Text(
                text = "TAP TO UNLOCK",
                color = Color.LightGray,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 12.dp, bottom = 40.dp)
            )

            if (twoStepVerifyEnabled) {
                Text(
                    text = "USE VOICE",
                    color = themeColor,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(bottom = 20.dp)
                        .clickable { onVoiceTap() }
                )
            }

            Text(
                text = "USE PASSCODE",
                color = themeColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onPasscodeTap() }
            )
        }
    }
}
