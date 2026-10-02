package com.example.scifilauncher

/** User's own observation: most of what Elene "does" (end a call, toggle the flashlight, go
 * back, scroll) doesn't actually need the AI backend at all - the backend's real job is
 * understanding a command phrased in an unexpected way, not executing a known one. This is a
 * fast, fully offline first pass over what was heard: if it clearly matches one of a set of
 * known device commands, [match] returns the SAME command string handleOverlayCommand() already
 * knows how to run (e.g. "end_call", "flashlight:on"), with no network round-trip at all. Only
 * text this can't confidently place still goes to the backend - this never invents a new command
 * verb, it only recognizes phrasings of ones that already exist. Deliberately conservative:
 * returning null on anything ambiguous is always safe (it just falls through to the AI), while a
 * wrong local match would silently run the wrong action - so every pattern here requires a real,
 * near-exact phrase match, not a loose keyword guess. */
object LocalCommandMatcher {

    private fun normalize(text: String): String =
        text.trim().lowercase().trim('.', '!', '?', ' ')

    // Order matters - checked top to bottom, first match wins. Each list is phrasings that
    // unambiguously mean that one command; nothing overlaps another entry's meaning.
    private val exactCommands: List<Pair<Set<String>, String>> = listOf(
        setOf(
            "decline the call", "decline call", "cut the call", "cut call", "hang up",
            "hang up the call", "end the call", "end call", "reject the call", "reject call",
            "cancel the call"
        ) to "end_call",
        setOf(
            "answer the call", "answer call", "pick up", "pick up the call", "accept the call",
            "accept call"
        ) to "answer_call",
        setOf("go back", "back", "go back a page") to "go_back",
        setOf("go home", "home screen", "go to home screen", "take me home") to "go_home",
        setOf("open recents", "show recents", "recent apps", "show recent apps") to "open_recents",
        setOf("scroll up", "scroll up a bit") to "scroll_up",
        setOf("scroll down", "scroll down a bit") to "scroll_down",
        setOf(
            "turn on the flashlight", "turn on flashlight", "flashlight on", "turn the flashlight on",
            "torch on", "turn on the torch"
        ) to "flashlight:on",
        setOf(
            "turn off the flashlight", "turn off flashlight", "flashlight off", "turn the flashlight off",
            "torch off", "turn off the torch"
        ) to "flashlight:off",
        setOf("volume up", "turn the volume up", "increase the volume", "louder") to "volume:up",
        setOf("volume down", "turn the volume down", "decrease the volume", "quieter", "lower the volume") to "volume:down",
        setOf(
            "toggle dark mode", "switch to dark mode", "switch to light mode", "toggle light mode",
            "turn on dark mode", "turn off dark mode"
        ) to "toggle_dark_mode",
        setOf(
            "toggle battery saver", "turn on battery saver", "turn off battery saver",
            "enable battery saver", "disable battery saver"
        ) to "toggle_battery_saver",
        setOf("open settings", "open android settings", "open system settings") to "open_android_settings",
        setOf("open bluetooth settings", "open bluetooth", "bluetooth settings") to "bluetooth",
        setOf("stop recording", "stop the recording", "stop voice memo") to "stop_recording",
        setOf("start recording", "start a voice memo", "record a voice memo") to "start_recording",
        setOf("stop the game", "stop playing", "stop game") to "stop_game",
        setOf("clear the highlight", "remove the highlight", "unhighlight") to "highlight_off"
    )

    private val openAppPrefixes = listOf("open ", "launch ", "start ")

    /** Returns a ready-to-run command string (same vocabulary handleOverlayCommand() already
     * accepts), or null if this isn't a confident local match - callers should send null results
     * on to the AI backend as usual. */
    fun match(heard: String): String? {
        val text = normalize(heard)
        if (text.isEmpty()) return null

        exactCommands.firstOrNull { (phrases, _) -> text in phrases }?.let { return it.second }

        // "open <app name>" - only when nothing follows that looks like a real request rather
        // than a bare app name (a long/complex sentence is more likely to need the AI's
        // judgment about which app or page is actually meant).
        for (prefix in openAppPrefixes) {
            if (text.startsWith(prefix)) {
                val rest = text.removePrefix(prefix).trim()
                if (rest.isNotEmpty() && rest.split(" ").size <= 4 && !rest.contains(" and ")) {
                    return "open_app:$rest"
                }
            }
        }
        return null
    }
}
