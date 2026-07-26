package com.example.scifilauncher

import android.content.SharedPreferences

/** Topics the user has told Elene never to bring up unless explicitly asked. */

fun loadAvoidTopics(prefs: SharedPreferences): Set<String> =
    prefs.getStringSet("avoid_topics", emptySet()) ?: emptySet()

fun addAvoidTopic(prefs: SharedPreferences, topic: String) {
    val updated = loadAvoidTopics(prefs) + topic.trim().lowercase()
    prefs.edit().putStringSet("avoid_topics", updated).apply()
}

fun removeAvoidTopic(prefs: SharedPreferences, topic: String) {
    val updated = loadAvoidTopics(prefs) - topic.trim().lowercase()
    prefs.edit().putStringSet("avoid_topics", updated).apply()
}
