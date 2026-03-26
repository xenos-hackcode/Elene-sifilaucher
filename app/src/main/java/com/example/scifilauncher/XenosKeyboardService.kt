package com.example.scifilauncher

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class XenosKeyboardService : InputMethodService() {

    private lateinit var themePrefs: SharedPreferences
    private lateinit var clipboardManager: ClipboardManager

    private var capsOn = false
    private var isCapsLock = false

    private data class ClipEntry(val text: String, val time: Long)
    private val clipHistory = mutableListOf<ClipEntry>()

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = clipboardManager.primaryClip
        val item = clip?.getItemAt(0)
        val text = item?.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            clipHistory.add(0, ClipEntry(text, System.currentTimeMillis()))
            if (clipHistory.size > 20) clipHistory.removeLast()
        }
    }

    override fun onCreate() {
        super.onCreate()
        themePrefs = getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager.removePrimaryClipChangedListener(clipListener)
    }

    override fun onCreateInputView(): View {
        return createXenosKeyboard()
    }

    private fun createXenosKeyboard(): View {
        val view = layoutInflater.inflate(R.layout.view_xenos_keyboard, null)

        // AI mode switch (bottom bar)
        val switchAi = view.findViewById<Switch>(R.id.switch_ai_mode)
        switchAi.isChecked = themePrefs.getBoolean("kb_ai_mode", false)
        switchAi.setOnCheckedChangeListener { _, isChecked ->
            themePrefs.edit().putBoolean("kb_ai_mode", isChecked).apply()
        }

        // Clipboard + settings UI (toolbar)
        val btnClipboard = view.findViewById<View>(R.id.btn_clipboard)
        val btnFonts = view.findViewById<View>(R.id.btn_fonts)
        val btnSettings = view.findViewById<View>(R.id.btn_settings)

        val keyArea = view.findViewById<View>(R.id.xenos_key_area)
        val clipboardPanel = view.findViewById<View>(R.id.xenos_clipboard_panel)
        val clipboardList = view.findViewById<LinearLayout>(R.id.xenos_clipboard_list)

        val settingsOverlay = view.findViewById<View>(R.id.xenos_settings_overlay)
        val switchTiles = view.findViewById<Switch>(R.id.switch_color_mode)
        val btnChangeMode = view.findViewById<Button>(R.id.btn_change_mode)

        // Tiles toggle (cyan tiles vs black)
        val tilesOn = themePrefs.getBoolean("kb_tiles_on", false)
        switchTiles.isChecked = tilesOn
        applyTilesMode(view, tilesOn)

        switchTiles.setOnCheckedChangeListener { _, isChecked ->
            themePrefs.edit().putBoolean("kb_tiles_on", isChecked).apply()
            applyTilesMode(view, isChecked)
        }

        // Clipboard button: show clipboard panel
        btnClipboard.setOnClickListener {
            settingsOverlay.visibility = View.GONE
            keyArea.visibility = View.GONE
            clipboardPanel.visibility = View.VISIBLE
            populateClipboardList(clipboardList, view)
        }

        // Fonts (placeholder)
        btnFonts.setOnClickListener {
            // TODO: fonts behavior
        }

        // Settings: toggle small overlay, ensure keys visible
        btnSettings.setOnClickListener {
            clipboardPanel.visibility = View.GONE
            keyArea.visibility = View.VISIBLE
            settingsOverlay.visibility =
                if (settingsOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Change mode: just flip Tiles switch
        btnChangeMode.setOnClickListener {
            switchTiles.isChecked = !switchTiles.isChecked
        }

        // NUMBER KEYS
        fun bindNumber(id: Int, char: String) {
            val btn = view.findViewById<Button>(id)
            btn.setOnClickListener {
                currentInputConnection?.commitText(char, 1)
            }
        }

        bindNumber(R.id.key_1, "1")
        bindNumber(R.id.key_2, "2")
        bindNumber(R.id.key_3, "3")
        bindNumber(R.id.key_4, "4")
        bindNumber(R.id.key_5, "5")
        bindNumber(R.id.key_6, "6")
        bindNumber(R.id.key_7, "7")
        bindNumber(R.id.key_8, "8")
        bindNumber(R.id.key_9, "9")
        bindNumber(R.id.key_0, "0")

        // LETTER KEYS + CAPS
        fun bindKey(id: Int, char: String) {
            val btn = view.findViewById<Button>(id)
            btn.setOnClickListener {
                val out = if (capsOn) char.uppercase() else char.lowercase()
                currentInputConnection?.commitText(out, 1)

                if (capsOn && !isCapsLock) {
                    capsOn = false
                    updateShiftVisual(view)
                    rebindAllLetterKeys(view)
                }
            }
        }

        // Row 1 letters
        bindKey(R.id.key_q, "q")
        bindKey(R.id.key_w, "w")
        bindKey(R.id.key_e, "e")
        bindKey(R.id.key_r, "r")
        bindKey(R.id.key_t, "t")
        bindKey(R.id.key_y, "y")
        bindKey(R.id.key_u, "u")
        bindKey(R.id.key_i, "i")
        bindKey(R.id.key_o, "o")
        bindKey(R.id.key_p, "p")

        // Row 2 letters
        bindKey(R.id.key_a, "a")
        bindKey(R.id.key_s, "s")
        bindKey(R.id.key_d, "d")
        bindKey(R.id.key_f, "f")
        bindKey(R.id.key_g, "g")
        bindKey(R.id.key_h, "h")
        bindKey(R.id.key_j, "j")
        bindKey(R.id.key_k, "k")
        bindKey(R.id.key_l, "l")

        // Row 3 letters
        bindKey(R.id.key_z, "z")
        bindKey(R.id.key_x, "x")
        bindKey(R.id.key_c, "c")
        bindKey(R.id.key_v, "v")
        bindKey(R.id.key_b, "b")
        bindKey(R.id.key_n, "n")
        bindKey(R.id.key_m, "m")

        // Space
        view.findViewById<Button>(R.id.key_space).setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }

        // Delete
        view.findViewById<Button>(R.id.key_delete).setOnClickListener {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        // Enter
        view.findViewById<Button>(R.id.key_enter).setOnClickListener {
            currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_DONE)
            currentInputConnection?.commitText("\n", 1)
        }

        // Shift
        val shiftBtn = view.findViewById<Button>(R.id.key_shift)
        shiftBtn.setOnClickListener {
            capsOn = !capsOn
            isCapsLock = capsOn
            updateShiftVisual(view)
            rebindAllLetterKeys(view)
        }
        shiftBtn.setOnLongClickListener {
            capsOn = true
            isCapsLock = true
            updateShiftVisual(view)
            rebindAllLetterKeys(view)
            true
        }

        updateShiftVisual(view)
        rebindAllLetterKeys(view)

        return view
    }

    private fun applyTilesMode(root: View, tilesOn: Boolean) {
        val ids = listOf(
            R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4, R.id.key_5,
            R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9, R.id.key_0,
            R.id.key_q, R.id.key_w, R.id.key_e, R.id.key_r, R.id.key_t,
            R.id.key_y, R.id.key_u, R.id.key_i, R.id.key_o, R.id.key_p,
            R.id.key_a, R.id.key_s, R.id.key_d, R.id.key_f, R.id.key_g,
            R.id.key_h, R.id.key_j, R.id.key_k, R.id.key_l,
            R.id.key_z, R.id.key_x, R.id.key_c, R.id.key_v,
            R.id.key_b, R.id.key_n, R.id.key_m,
            R.id.key_shift, R.id.key_theme, R.id.key_space,
            R.id.key_delete, R.id.key_enter,
            R.id.btn_clipboard, R.id.btn_fonts, R.id.btn_settings,
            R.id.btn_change_mode
        )
        val res = if (tilesOn) R.drawable.xenos_key_neon_cyan else R.drawable.xenos_key_black
        for (id in ids) {
            root.findViewById<View>(id)?.setBackgroundResource(res)
        }
    }

    private fun populateClipboardList(list: LinearLayout, root: View) {
        list.removeAllViews()
        val inflater: LayoutInflater = layoutInflater
        val timeFormat = "HH:mm dd/MM"

        for (entry in clipHistory) {
            val itemView = inflater.inflate(android.R.layout.simple_list_item_2, list, false)
            val txt1 = itemView.findViewById<TextView>(android.R.id.text1)
            val txt2 = itemView.findViewById<TextView>(android.R.id.text2)

            txt1.text = entry.text
            txt2.text = DateFormat.format(timeFormat, entry.time)

            itemView.setOnClickListener {
                currentInputConnection?.commitText(entry.text, 1)
                root.findViewById<View>(R.id.xenos_clipboard_panel).visibility = View.GONE
                root.findViewById<View>(R.id.xenos_key_area).visibility = View.VISIBLE
            }

            list.addView(itemView)
        }
    }

    private fun updateShiftVisual(root: View) {
        val shiftBtn = root.findViewById<Button>(R.id.key_shift)
        if (capsOn) {
            shiftBtn.text = "⬆"
            shiftBtn.alpha = 1.0f
        } else {
            shiftBtn.text = "↑"
            shiftBtn.alpha = 0.6f
        }
    }

    private fun rebindAllLetterKeys(root: View) {
        fun setLabel(id: Int, char: String) {
            val btn = root.findViewById<Button>(id)
            btn.text = if (capsOn) char.uppercase() else char.lowercase()
        }

        setLabel(R.id.key_q, "q"); setLabel(R.id.key_w, "w"); setLabel(R.id.key_e, "e")
        setLabel(R.id.key_r, "r"); setLabel(R.id.key_t, "t"); setLabel(R.id.key_y, "y")
        setLabel(R.id.key_u, "u"); setLabel(R.id.key_i, "i"); setLabel(R.id.key_o, "o")
        setLabel(R.id.key_p, "p")

        setLabel(R.id.key_a, "a"); setLabel(R.id.key_s, "s"); setLabel(R.id.key_d, "d")
        setLabel(R.id.key_f, "f"); setLabel(R.id.key_g, "g"); setLabel(R.id.key_h, "h")
        setLabel(R.id.key_j, "j"); setLabel(R.id.key_k, "k"); setLabel(R.id.key_l, "l")

        setLabel(R.id.key_z, "z"); setLabel(R.id.key_x, "x"); setLabel(R.id.key_c, "c")
        setLabel(R.id.key_v, "v"); setLabel(R.id.key_b, "b"); setLabel(R.id.key_n, "n")
        setLabel(R.id.key_m, "m")
    }
}
