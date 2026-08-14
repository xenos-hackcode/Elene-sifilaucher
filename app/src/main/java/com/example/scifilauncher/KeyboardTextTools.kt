package com.example.scifilauncher

import android.content.Context
import android.util.Base64
import android.view.inputmethod.InputConnection
import android.widget.Toast
import java.security.MessageDigest

/** Real text-transform tools for the Xenos/Cedal keyboards' settings overlays - every button
 * calls one of these against whatever text is currently selected in the field being typed into,
 * replacing the selection with the transformed result. No selection = nothing to transform, so
 * this tells the user that rather than silently doing nothing. */
object KeyboardTextTools {
    fun applyToSelection(ic: InputConnection?, context: Context, transform: (String) -> String) {
        val selected = ic?.getSelectedText(0)?.toString()
        if (selected.isNullOrEmpty()) {
            Toast.makeText(context, "Select some text first", Toast.LENGTH_SHORT).show()
            return
        }
        val result = runCatching { transform(selected) }.getOrNull()
        if (result == null) {
            Toast.makeText(context, "Couldn't process that selection", Toast.LENGTH_SHORT).show()
            return
        }
        ic.commitText(result, 1)
    }

    // ---- Xenos: hacker/cyber tools ----

    fun base64Encode(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    fun base64Decode(text: String): String =
        String(Base64.decode(text, Base64.NO_WRAP), Charsets.UTF_8)

    fun rot13(text: String): String = text.map { c ->
        when (c) {
            in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
            in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
            else -> c
        }
    }.joinToString("")

    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private val LEET_MAP = mapOf(
        'a' to '4', 'A' to '4',
        'e' to '3', 'E' to '3',
        'i' to '1', 'I' to '1',
        'o' to '0', 'O' to '0',
        's' to '5', 'S' to '5',
        't' to '7', 'T' to '7',
        'b' to '8', 'B' to '8',
        'g' to '9', 'G' to '9'
    )

    fun leetspeak(text: String): String = text.map { LEET_MAP[it] ?: it }.joinToString("")

    // ---- Cedal: professional/productivity tools ----

    fun titleCase(text: String): String =
        text.split(" ").joinToString(" ") { word ->
            if (word.isEmpty()) word else word[0].uppercase() + word.substring(1).lowercase()
        }

    fun trimWhitespace(text: String): String =
        text.trim().replace(Regex("[ \\t]+"), " ")

    fun wordAndCharCount(text: String): Pair<Int, Int> {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        return words to text.length
    }
}
