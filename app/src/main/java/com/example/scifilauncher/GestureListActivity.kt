package com.example.scifilauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Lists every trainable GestureAction with its real trained/untrained status (and, for
 * OPEN_APP, which app is currently bound) - tapping a row opens GestureTrainingActivity to
 * record or re-record it. Entry point: Security > Gesture control > "Configure Gestures".
 * Reuses TerminalToolsUi (the App Workshop / Safety tools / VPN client screens' shared style),
 * per the request to use it "everywhere" instead of this screen's own separate flat-black look. */
class GestureListActivity : Activity() {
    // Matches TerminalToolsUi's own private ink/muted constants - not exposed there, so mirrored
    // here to keep row text consistent with everything built inside its card()s.
    private val ink = Color.rgb(225, 234, 241)
    private val muted = Color.rgb(149, 167, 182)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rebuildUi()
    }

    override fun onResume() {
        super.onResume()
        // Re-read trained status every time this comes back to front (e.g. returning from a
        // recording session) - real state, not a stale snapshot from onCreate.
        if (this::ui.isInitialized) rebuildUi()
    }

    private lateinit var ui: TerminalToolsUi

    private fun rebuildUi() {
        ui = TerminalToolsUi(this, "GESTURES", "Gesture control", "Tap a gesture to record it - real hand motion via the front camera, not a placeholder.")
        val list = ui.card("TRAINABLE GESTURES (${GestureAction.entries.size})")
        GestureAction.entries.forEach { action -> list.addView(buildRow(action)) }
    }

    private fun buildRow(action: GestureAction): LinearLayout {
        val data = GestureTemplateStore.load(this, action)
        val trained = (data?.samples?.size ?: 0) >= MIN_SAMPLES_TO_BE_TRAINED
        val repCount = data?.samples?.size ?: 0
        val cardinal = isCardinalSwipe(action)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(10), 0, ui.dp(10))
            if (!cardinal) {
                setOnClickListener {
                    startActivity(
                        Intent(this@GestureListActivity, GestureTrainingActivity::class.java)
                            .putExtra(GestureTrainingActivity.EXTRA_ACTION_ID, action.id)
                    )
                }
            }
        }

        val labelColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        labelColumn.addView(TextView(this).apply {
            text = action.label
            setTextColor(if (isDeprecated(action)) muted else ink)
            textSize = 15f
            typeface = Typeface.MONOSPACE
        })
        val statusText = when {
            // Cardinal swipes work via a fixed geometric fallback with no training at all -
            // "everyone swipes the same way" (user's call). No tap-to-record, no trained/
            // untrained status - just a plain statement that it already works.
            cardinal -> "Always available - no training needed"
            action == GestureAction.OPEN_APP && data?.openAppPackage == null -> "Not trained - and no app chosen yet"
            action == GestureAction.OPEN_APP -> "${appLabelFor(data?.openAppPackage)} - $repCount rep${if (repCount == 1) "" else "s"}"
            trained -> "✓ Trained - $repCount reps"
            repCount > 0 -> "$repCount / $MIN_SAMPLES_TO_BE_TRAINED reps - needs more"
            else -> "Not trained"
        }
        labelColumn.addView(TextView(this).apply {
            text = statusText
            setTextColor(if (cardinal || trained || (action == GestureAction.OPEN_APP && data?.openAppPackage != null)) ui.accent else muted)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, ui.dp(2), 0, 0)
        })
        row.addView(labelColumn)

        // Cardinal swipes aren't trainable anymore, but a CLEAR is still offered if this device
        // has leftover training data from before that decision, so it can be purged.
        if (repCount > 0) {
            row.addView(TextView(this).apply {
                text = "CLEAR"
                setTextColor(Color.parseColor("#E57373"))
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
                setOnClickListener {
                    GestureTemplateStore.clear(this@GestureListActivity, action)
                    rebuildUi()
                }
            })
        }

        return row
    }

    private fun isCardinalSwipe(action: GestureAction): Boolean = when (action) {
        GestureAction.SWIPE_LEFT, GestureAction.SWIPE_RIGHT, GestureAction.SWIPE_UP, GestureAction.SWIPE_DOWN -> true
        else -> false
    }

    private fun isDeprecated(action: GestureAction): Boolean = action == GestureAction.OPEN_REACTOR

    private fun appLabelFor(packageName: String?): String {
        if (packageName == null) return "No app chosen"
        return runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }
}
