package com.example.scifilauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val ONBOARDING_PREFS = "onboarding_prefs"
private const val KEY_ONBOARDING_DONE = "onboarding_completed"
private const val PRIVACY_POLICY_URL = "https://xenos-hackcode.github.io/scifilauncher-privacy/"

fun isOnboardingComplete(context: Context): Boolean =
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ONBOARDING_DONE, false)

fun setOnboardingComplete(context: Context) {
    context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
}

private data class OnboardingSlide(val title: String, val body: String)

private val TUTORIAL_SLIDES = listOf(
    OnboardingSlide(
        title = "LEARNING YOU",
        body = "Elene learns from your behavior for the next 2 months to personalize how " +
                "she assists you. You can read the details of this anytime in Settings > Profile."
    ),
    OnboardingSlide(
        title = "READY",
        body = "That covers the essentials. Explore Security, Settings, and the Elene bubble " +
                "whenever you need them."
    )
)

/** First-run, non-repeating onboarding: a mandatory legal/intro gate the user must agree to
 * with no way to skip, followed by a swipeable tutorial pager (skippable) covering the rest. */
@Composable
fun OnboardingScreen(
    themeColor: Color,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    var agreed by remember { mutableStateOf(false) }

    if (!agreed) {
        OnboardingLegalGate(
            themeColor = themeColor,
            onAgree = { agreed = true }
        )
    } else {
        OnboardingTutorialPager(
            themeColor = themeColor,
            onFinished = {
                setOnboardingComplete(context)
                onFinished()
            }
        )
    }
}

@Composable
private fun OnboardingLegalGate(themeColor: Color, onAgree: () -> Unit) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "WELCOME TO SELDAR SCIFI LAUNCHER",
                color = themeColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "I am your automated AI, here to assist and control your device to " +
                        "its best capability. I am not restricted in any form or way, and I " +
                        "always put you first.",
                color = Color.White,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "If you are not part of the Cedal Star company, please close and " +
                        "delete this app immediately.",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = "THIS TUTORIAL WILL NOT BE REPEATED. READ IT CAREFULLY.",
                color = Color.Red,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Read our full policy",
                color = themeColor,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    openUrlInPreferredBrowser(context, PRIVACY_POLICY_URL)
                }
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "By tapping AGREE, you confirm you have read and agree to our full " +
                        "policy, and you are stating that you are part of the Cedal Star " +
                        "company. Legal action may be taken if this is false.",
                color = Color.Gray,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = onAgree,
                colors = ButtonDefaults.buttonColors(containerColor = themeColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "AGREE",
                    color = Color.Black,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnboardingTutorialPager(themeColor: Color, onFinished: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { TUTORIAL_SLIDES.size })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val slide = TUTORIAL_SLIDES[page]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = slide.title,
                    color = themeColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = slide.body,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Text(
            text = "SKIP",
            color = themeColor.copy(alpha = 0.8f),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp)
                .clickable { onFinished() }
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            TUTORIAL_SLIDES.indices.forEach { i ->
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(if (pagerState.currentPage == i) 10.dp else 7.dp)
                        .background(
                            color = if (pagerState.currentPage == i) themeColor else Color.Gray,
                            shape = CircleShape
                        )
                )
            }
        }

        if (pagerState.currentPage == TUTORIAL_SLIDES.lastIndex) {
            Button(
                onClick = onFinished,
                colors = ButtonDefaults.buttonColors(containerColor = themeColor),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 56.dp, start = 24.dp, end = 24.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    "GET STARTED",
                    color = Color.Black,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
