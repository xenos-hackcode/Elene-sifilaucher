package com.example.scifilauncher

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Shared, static terminal styling: no animation loops or extra battery work. */
internal class TerminalToolsUi(private val activity: Activity, code: String, title: String, subtitle: String) {
    val accent: Int
    val content: LinearLayout
    private val ink = Color.rgb(225, 234, 241)
    private val muted = Color.rgb(149, 167, 182)
    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    init {
        val index = activity.getSharedPreferences("theme_prefs", Activity.MODE_PRIVATE).getInt("theme_index", 0)
        val color = CedalThemes[Math.floorMod(index, CedalThemes.size)].primary
        accent = Color.rgb((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        activity.window.statusBarColor = Color.rgb(5, 9, 16)
        activity.window.navigationBarColor = Color.rgb(5, 9, 16)
        activity.window.decorView.systemUiVisibility = 0

        // Root holds a FIXED header (back button/title/subtitle - stays on screen) above a
        // scrolling body (just the card()s) - previously the back button was the first child
        // INSIDE the scrolling content itself, so it scrolled away with everything else on any
        // screen with enough cards to need scrolling at all.
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 9, 16), Color.rgb(12, 22, 32), Color.rgb(4, 8, 14)))
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), 0)
        }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(32))
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        activity.setContentView(root)
        button(header, "‹  SECURITY", false) { activity.finish() }
        text(header, "XENOS  /  $code", 11f, accent).apply { letterSpacing = 0.16f }
        text(header, title, 30f, ink).apply { setTypeface(Typeface.MONOSPACE, Typeface.BOLD) }
        text(header, subtitle, 14f, muted)
        header.addView(View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(accent, Color.TRANSPARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(16); bottomMargin = dp(12)
        })
    }

    private fun surface(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(12).toFloat(); setStroke(dp(1), stroke)
    }

    fun card(label: String): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = surface(Color.rgb(12, 19, 29), Color.argb(65, Color.red(accent), Color.green(accent), Color.blue(accent)))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        content.addView(card)
        text(card, label, 11f, accent).apply { letterSpacing = 0.12f }
        return card
    }

    fun text(parent: LinearLayout, value: String, size: Float = 14f, color: Int = muted): TextView = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = if (size <= 12f || size >= 24f) Typeface.MONOSPACE else Typeface.DEFAULT
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(6), 0, dp(6))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun input(parent: LinearLayout, hintText: String, initial: String, viewId: Int): EditText = EditText(activity).apply {
        id = viewId; hint = hintText; setText(initial); setTextColor(ink); setHintTextColor(muted)
        textSize = 17f; typeface = Typeface.MONOSPACE
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = surface(Color.rgb(5, 11, 19), Color.rgb(53, 69, 83))
        minHeight = dp(52)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
    }

    fun button(parent: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit): Button = Button(activity).apply {
        text = label; isAllCaps = false; textSize = 14f; typeface = Typeface.MONOSPACE
        val foreground = if (primary) Color.rgb(3, 10, 16) else ink
        // Keep primary labels readable even when the selected accent is dark.
        val fill = if (primary) Color.rgb((Color.red(accent) + 255) / 2,
            (Color.green(accent) + 255) / 2, (Color.blue(accent) + 255) / 2) else Color.rgb(17, 28, 40)
        setTextColor(foreground)
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(65, 255, 255, 255)), surface(fill,
            if (primary) fill else Color.rgb(48, 66, 81)), null)
        minHeight = dp(52); minimumHeight = dp(52)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        setOnClickListener {
            runCatching { action() }.onFailure {
                Toast.makeText(activity, it.message ?: "Action unavailable", Toast.LENGTH_LONG).show()
            }
        }
    }
}
