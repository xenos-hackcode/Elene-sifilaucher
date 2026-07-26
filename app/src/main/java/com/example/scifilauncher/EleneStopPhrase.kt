package com.example.scifilauncher

/** "stop listening" is the one absolute, always-recognized-locally phrase to end a continuous
 * listening session - it has to work even if the backend is unreachable or misreads a more
 * natural phrasing, since there'd otherwise be no guaranteed way to make the bubble stop. A
 * couple of close variants are included since real speech rarely comes out as an exact match.
 *
 * This is an EXACT match against the whole utterance, not a substring check - it used to be
 * `.contains()`, which meant a compound request like "open whatsapp then stop listening"
 * matched too (the phrase is a substring of it) and got intercepted locally before ever
 * reaching the backend - so "open whatsapp" was silently never even asked for. A compound
 * utterance like that now correctly falls through to the backend, which returns both
 * open_app and stop_listening as separate entries in "commands" and runs them in order.
 *
 * Anything else relies on the backend LLM recognizing "stop_listening" as a command from more
 * varied natural phrasing ("go away", "that's enough", etc.) - best-effort, not guaranteed,
 * which is exactly why this local check exists as the absolute fallback for the plain phrase. */
fun isStopListeningPhrase(text: String): Boolean {
    val normalized = text.trim().lowercase().trimEnd('.', '!', '?')
    val phrases = setOf(
        "stop listening",
        "you can stop listening",
        "stop listening now",
        "go off",
        "you can go off"
    )
    return normalized in phrases
}
