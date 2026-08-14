package com.example.scifilauncher

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class CedalKeyboardService : InputMethodService() {

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
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager.removePrimaryClipChangedListener(clipListener)
    }

    override fun onCreateInputView(): View {
        return createCedalKeyboard()
    }

    private fun createCedalKeyboard(): View {
        val view = layoutInflater.inflate(R.layout.view_cedal_keyboard, null)

        val keyArea = view.findViewById<View>(R.id.xenos_key_area)
        val symbolArea = view.findViewById<View>(R.id.xenos_symbol_area)
        val clipboardPanel = view.findViewById<ScrollView>(R.id.xenos_clipboard_panel)
        val clipboardList = view.findViewById<LinearLayout>(R.id.xenos_clipboard_list)
        val settingsOverlay = view.findViewById<View>(R.id.cedal_settings_overlay)
        val wordCountText = view.findViewById<TextView>(R.id.cedal_word_count)

        val btnClipboard = view.findViewById<ImageButton>(R.id.btn_clipboard)
        val btnSettings = view.findViewById<ImageButton>(R.id.btn_settings)

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

        // Cedal's own tools (deliberately not Xenos's neon Tiles toggle - real productivity
        // tools instead, fitting "professional"): case conversion, whitespace cleanup, and a
        // live word/character count of the field currently being typed into.
        btnSettings.setOnClickListener {
            clipboardPanel.visibility = View.GONE
            symbolArea.visibility = View.GONE
            keyArea.visibility = View.VISIBLE
            val opening = settingsOverlay.visibility != View.VISIBLE
            settingsOverlay.visibility = if (opening) View.VISIBLE else View.GONE
            if (opening) {
                val fullText = currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text?.toString().orEmpty()
                val (words, chars) = KeyboardTextTools.wordAndCharCount(fullText)
                wordCountText.text = "$words words, $chars characters"
            }
        }

        view.findViewById<Button>(R.id.tool_uppercase).setOnClickListener {
            KeyboardTextTools.applyToSelection(currentInputConnection, this) { it.uppercase() }
        }
        view.findViewById<Button>(R.id.tool_lowercase).setOnClickListener {
            KeyboardTextTools.applyToSelection(currentInputConnection, this) { it.lowercase() }
        }
        view.findViewById<Button>(R.id.tool_titlecase).setOnClickListener {
            KeyboardTextTools.applyToSelection(currentInputConnection, this, KeyboardTextTools::titleCase)
        }
        view.findViewById<Button>(R.id.tool_trim).setOnClickListener {
            KeyboardTextTools.applyToSelection(currentInputConnection, this, KeyboardTextTools::trimWhitespace)
        }

        val themeButton = view.findViewById<Button>(R.id.key_theme)
        themeButton.text = "\u003F123"
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
                themeButton.text = "\u003F123"
            }
        }

        val symToLetters = view.findViewById<Button>(R.id.sym_to_letters)
        symToLetters.setOnClickListener {
            settingsOverlay.visibility = View.GONE
            symbolArea.visibility = View.GONE
            keyArea.visibility = View.VISIBLE
            themeButton.text = "\u003F123"
        }

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

        fun bindSym(id: Int, char: String) {
            val btn = view.findViewById<Button>(id)
            btn.setOnClickListener {
                currentInputConnection?.commitText(char, 1)
            }
        }

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

        bindKey(R.id.key_a, "a")
        bindKey(R.id.key_s, "s")
        bindKey(R.id.key_d, "d")
        bindKey(R.id.key_f, "f")
        bindKey(R.id.key_g, "g")
        bindKey(R.id.key_h, "h")
        bindKey(R.id.key_j, "j")
        bindKey(R.id.key_k, "k")
        bindKey(R.id.key_l, "l")

        bindKey(R.id.key_z, "z")
        bindKey(R.id.key_x, "x")
        bindKey(R.id.key_c, "c")
        bindKey(R.id.key_v, "v")
        bindKey(R.id.key_b, "b")
        bindKey(R.id.key_n, "n")
        bindKey(R.id.key_m, "m")

        val spaceBtn = view.findViewById<Button>(R.id.key_space)
        spaceBtn.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }

        val deleteBtn = view.findViewById<Button>(R.id.key_delete)
        deleteBtn.setOnClickListener {
            val ic = currentInputConnection ?: return@setOnClickListener
            ic.deleteSurroundingText(1, 0)
        }
        deleteBtn.setOnLongClickListener {
            deletePreviousSentence()
            true
        }

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
            txt1.setTextColor(0xFFE4E7EC.toInt())
            txt1.maxLines = 1
            txt1.ellipsize = android.text.TextUtils.TruncateAt.END
            txt1.text = entry.text.replace('\n', ' ').trim().take(60)
            txt2.setTextColor(0xFF8A8F98.toInt())
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
