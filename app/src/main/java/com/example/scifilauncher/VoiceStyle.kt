package com.example.scifilauncher

/**
 * Five enrollment styles instead of one repeated phrase - a single word ("unlock") sounds
 * meaningfully different from a full sentence, so one reference embedding covering all lengths
 * was never going to compare fairly. Shorter styles get a lower match threshold (less
 * discriminative audio to work with, so demanding too tight a match just causes false
 * rejections); longer styles get a higher threshold (more data available, so more confidence
 * can be demanded before accepting a match). The lock screen's voice option uses SHORT directly
 * (simple record-and-compare, no separate challenge step).
 */
enum class VoiceStyle(
    val label: String,
    val prompt: String,
    val recordSeconds: Int,
    val threshold: Float
) {
    LONG("Long phrase", "Emperor access protocol alpha seven", 5, 0.70f),
    MEDIUM("Medium phrase", "One hundred and one", 4, 0.65f),
    SHORT("Short word", "Unlock", 2, 0.55f),
    ALPHABET("Alphabet", "A B C D", 3, 0.60f),
    NUMBERS("Numbers", "1 2 3 4", 3, 0.60f)
}
