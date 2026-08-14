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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
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

        // Main areas
        val keyArea = view.findViewById<View>(R.id.xenos_key_area)
        val symbolArea = view.findViewById<View>(R.id.xenos_symbol_area)
        val clipboardPanel = view.findViewById<ScrollView>(R.id.xenos_clipboard_panel)
        val clipboardList = view.findViewById<LinearLayout>(R.id.xenos_clipboard_list)
        val settingsOverlay = view.findViewById<View>(R.id.xenos_settings_overlay)

        // Toolbar buttons
        val btnClipboard = view.findViewById<ImageButton>(R.id.btn_clipboard)
        val btnSettings = view.findViewById<ImageButton>(R.id.btn_settings)

        // Tiles switch (inside overlay)
        val switchTiles = view.findViewById<Switch>(R.id.switch_color_mode)
        val btnChangeMode = view.findViewById<Button>(R.id.btn_change_mode)

        // Tiles mode
        val tilesOn = themePrefs.getBoolean("kb_tiles_on", false)
        switchTiles.isChecked = tilesOn
        applyTilesMode(view, tilesOn)

        switchTiles.setOnCheckedChangeListener { _, isChecked ->
            themePrefs.edit().putBoolean("kb_tiles_on", isChecked).apply()
            applyTilesMode(view, isChecked)
        }

        btnChangeMode.setOnClickListener {
            switchTiles.isChecked = !switchTiles.isChecked
        }

        // Hacker text tools - each acts on whatever text is currently selected in the field
        // being typed into (see KeyboardTextTools.applyToSelection for the "nothing selected"
        // handling).
        view.findViewById<Button>(R.id.tool_base64_enc).setOnClickListener {
            KeyboardTextTools.applyToSelection(currentInputConnection, this, KeyboardTextTools::base64Encode)
        }
        view.findViewById<Button>(R.id.tool_base64_dec).setOnClickListener {
            KeyboardTextTools.applyToSelection(currentInputConnection, this, KeyboardTextTools::base64Decode)
        }
        view.findViewById<Button>(R.id.tool_rot13).setOnClickListener {
            KeyboardTextTools.applyToSelection(currentInputConnection, this, KeyboardTextTools::rot13)
        }
        view.findViewById<Button>(R.id.tool_hash).setOnClickListener {
            KeyboardTextTools.applyToSelection(currentInputConnection, this, KeyboardTextTools::sha256Hex)
        }
        view.findViewById<Button>(R.id.tool_leet).setOnClickListener {
            KeyboardTextTools.applyToSelection(currentInputConnection, this, KeyboardTextTools::leetspeak)
        }

        // Clipboard button
        // Real bug fixed 2026-08-11: this used to only ever open the clipboard panel - tapping
        // Clipboard again while it was already open did nothing, no way back except tapping an
        // actual clip. Now a second tap while open closes it back to the normal keyboard.
        btnClipboard.setOnClickListener {
            if (clipboardPanel.visibility == View.VISIBLE) {
                clipboardPanel.visibility = View.GONE
                keyArea.visibility = View.VISIBLE
            } else {
                settingsOverlay.visibility = View.GONE
                keyArea.visibility = View.GONE
                symbolArea.visibility = View.GONE
                clipboardPanel.visibility = View.VISIBLE
                populateClipboardList(clipboardList, view)
            }
        }

        // Settings overlay (spanner) – now just toggles overlay for tiles
        btnSettings.setOnClickListener {
            clipboardPanel.visibility = View.GONE
            symbolArea.visibility = View.GONE
            keyArea.visibility = View.VISIBLE
            settingsOverlay.visibility =
                if (settingsOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // THEME BUTTON = ?123 <-> ABC
        val themeButton = view.findViewById<Button>(R.id.key_theme)
        themeButton.text = "?123"
        themeButton.setOnClickListener {
            settingsOverlay.visibility = View.GONE
            if (keyArea.visibility == View.VISIBLE) {
                keyArea.visibility = View.GONE
                clipboardPanel.visibility = View.GONE
                symbolArea.visibility = View.VISIBLE
                themeButton.text = "ABC"
            } else {
                symbolArea.visibility = View.GONE
                clipboardPanel.visibility = View.GONE
                keyArea.visibility = View.VISIBLE
                themeButton.text = "?123"
            }
        }

        // Symbols back to letters
        val symToLetters = view.findViewById<Button>(R.id.sym_to_letters)
        symToLetters.setOnClickListener {
            settingsOverlay.visibility = View.GONE
            symbolArea.visibility = View.GONE
            keyArea.visibility = View.VISIBLE
            themeButton.text = "?123"
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

        // SYMBOL KEYS
        fun bindSym(id: Int, char: String) {
            val btn = view.findViewById<Button>(id)
            btn.setOnClickListener {
                currentInputConnection?.commitText(char, 1)
            }
        }

        // row 1
        bindSym(R.id.sym_exclam, "!")
        bindSym(R.id.sym_at, "@")
        bindSym(R.id.sym_hash, "#")
        bindSym(R.id.sym_dollar, "$")
        bindSym(R.id.sym_percent, "%")
        bindSym(R.id.sym_amp, "&")
        bindSym(R.id.sym_star, "*")
        bindSym(R.id.sym_lparen, "(")
        bindSym(R.id.sym_rparen, ")")
        bindSym(R.id.sym_underscore, "_")

        // row 2
        bindSym(R.id.sym_plus, "+")
        bindSym(R.id.sym_minus, "-")
        bindSym(R.id.sym_equal, "=")
        bindSym(R.id.sym_slash, "/")
        bindSym(R.id.sym_backslash, "\\")
        bindSym(R.id.sym_pipe, "|")
        bindSym(R.id.sym_tilde, "~")
        bindSym(R.id.sym_lt, "<")
        bindSym(R.id.sym_gt, ">")
        bindSym(R.id.sym_pm, "±")

        // row 3
        bindSym(R.id.sym_comma, ",")
        bindSym(R.id.sym_dot, ".")
        bindSym(R.id.sym_question, "?")
        bindSym(R.id.sym_colon, ":")
        bindSym(R.id.sym_semicolon, ";")
        bindSym(R.id.sym_quote, "'")
        bindSym(R.id.sym_dquote, "\"")
        bindSym(R.id.sym_ellipsis, "…")
        bindSym(R.id.sym_bullet, "•")
        bindSym(R.id.sym_hyphen, "-")

        // symbols actions
        val symSpace = view.findViewById<Button>(R.id.sym_space)
        val symDelete = view.findViewById<Button>(R.id.sym_delete)
        val symEnter = view.findViewById<Button>(R.id.sym_enter)

        symSpace.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }

        symDelete.setOnClickListener {
            val ic = currentInputConnection ?: return@setOnClickListener
            ic.deleteSurroundingText(1, 0)
        }
        symDelete.setOnLongClickListener {
            deletePreviousSentence()
            true
        }

        symEnter.setOnClickListener {
            currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_DONE)
            currentInputConnection?.commitText("\n", 1)
        }

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
        val spaceBtn = view.findViewById<Button>(R.id.key_space)
        spaceBtn.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }

        // Delete with long-press sentence delete
        val deleteBtn = view.findViewById<Button>(R.id.key_delete)
        deleteBtn.setOnClickListener {
            val ic = currentInputConnection ?: return@setOnClickListener
            ic.deleteSurroundingText(1, 0)
        }
        deleteBtn.setOnLongClickListener {
            deletePreviousSentence()
            true
        }

        // Enter
        val enterBtn = view.findViewById<Button>(R.id.key_enter)
        enterBtn.setOnClickListener {
            currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_DONE)
            currentInputConnection?.commitText("\n", 1)
        }

        // Shift: tap = one-shot capital (auto-reverts to lowercase after the next letter, via
        // bindKey()'s own "if (capsOn && !isCapsLock)" check above); tap again while locked =
        // fully off. Long-press = real caps lock (stays capital until tapped off).
        //
        // Real bug fixed 2026-08-11: this used to set isCapsLock = capsOn on every single tap,
        // which made bindKey()'s one-shot-revert condition unreachable - so a single tap behaved
        // exactly like caps lock (capitalized everything) instead of just the next letter.
        val shiftBtn = view.findViewById<Button>(R.id.key_shift)
        shiftBtn.setOnClickListener {
            if (isCapsLock) {
                capsOn = false
                isCapsLock = false
            } else {
                capsOn = !capsOn
            }
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

    // Delete previous sentence/line or selection
    private fun deletePreviousSentence() {
        val ic = currentInputConnection ?: return

        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }

        val before = ic.getTextBeforeCursor(300, 0) ?: ""
        if (before.isEmpty()) return

        val lastDot = before.lastIndexOf('.')
        val lastBang = before.lastIndexOf('!')
        val lastQ = before.lastIndexOf('?')
        val lastNewline = before.lastIndexOf('\n')

        val lastBoundary = listOf(lastDot, lastBang, lastQ, lastNewline).maxOrNull() ?: -1
        val toDelete = if (lastBoundary == -1) before.length else before.length - lastBoundary - 1
        ic.deleteSurroundingText(toDelete, 0)
    }

    private fun applyTilesMode(root: View, tilesOn: Boolean) {
        val ids = listOf(
            // numbers
            R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4, R.id.key_5,
            R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9, R.id.key_0,
            // letters
            R.id.key_q, R.id.key_w, R.id.key_e, R.id.key_r, R.id.key_t,
            R.id.key_y, R.id.key_u, R.id.key_i, R.id.key_o, R.id.key_p,
            R.id.key_a, R.id.key_s, R.id.key_d, R.id.key_f, R.id.key_g,
            R.id.key_h, R.id.key_j, R.id.key_k, R.id.key_l,
            R.id.key_z, R.id.key_x, R.id.key_c, R.id.key_v,
            R.id.key_b, R.id.key_n, R.id.key_m,
            // main special keys + toolbar
            R.id.key_shift, R.id.key_theme, R.id.key_space,
            R.id.key_delete, R.id.key_enter,
            R.id.btn_clipboard, R.id.btn_settings,
            R.id.btn_change_mode,
            // symbols
            R.id.sym_exclam, R.id.sym_at, R.id.sym_hash, R.id.sym_dollar, R.id.sym_percent,
            R.id.sym_amp, R.id.sym_star, R.id.sym_lparen, R.id.sym_rparen, R.id.sym_underscore,
            R.id.sym_plus, R.id.sym_minus, R.id.sym_equal, R.id.sym_slash, R.id.sym_backslash,
            R.id.sym_pipe, R.id.sym_tilde, R.id.sym_lt, R.id.sym_gt, R.id.sym_pm,
            R.id.sym_comma, R.id.sym_dot, R.id.sym_question, R.id.sym_colon, R.id.sym_semicolon,
            R.id.sym_quote, R.id.sym_dquote, R.id.sym_ellipsis, R.id.sym_bullet, R.id.sym_hyphen,
            R.id.sym_to_letters, R.id.sym_space, R.id.sym_delete, R.id.sym_enter
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

            // simple_list_item_2's default text color assumes a light background - on this
            // dark keyboard panel it rendered dark-on-dark, effectively invisible. Also cap
            // the preview to one short line instead of dumping the full copied text into a
            // cramped keyboard row - a long clip (a password, a paragraph) doesn't need to be
            // fully exposed just sitting in the list; tapping it still pastes the real full
            // text below, only the on-screen preview is shortened.
            txt1.setTextColor(0xFF09C20C.toInt())
            txt1.maxLines = 1
            txt1.ellipsize = android.text.TextUtils.TruncateAt.END
            txt1.text = entry.text.replace('\n', ' ').trim().take(60)
            txt2.setTextColor(0xFF6B9E6B.toInt())
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
