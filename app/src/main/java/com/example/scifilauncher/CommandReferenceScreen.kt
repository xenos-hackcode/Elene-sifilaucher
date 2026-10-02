package com.example.scifilauncher

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class EleneCommandDoc(val command: String, val sayThis: String, val whatItDoes: String)

/** Kept in sync by hand with the command schema documented in the backend's system prompt
 * (backend/elene/main.py) - this is what Elene can actually be told to do, not a generated list. */
val ELENE_COMMANDS: List<EleneCommandDoc> = listOf(
    EleneCommandDoc("open_app", "\"open WhatsApp\"", "Launches an app by its plain name."),
    EleneCommandDoc("search_app", "\"do I have Spotify\", \"look for TikTok\"", "Opens the app grid with that search typed in - doesn't launch anything."),
    EleneCommandDoc("open_page", "\"open settings\", \"show my apps\", \"open games\"", "Navigates to one of THIS app's own screens (home/settings/games/apps)."),
    EleneCommandDoc("open_android_settings", "\"open android settings\", \"open phone settings\"", "Opens the phone's system Settings app, not this app's own settings."),
    EleneCommandDoc("toggle_dark_mode", "\"turn on dark mode\", \"switch to light mode\"", "Flips the launcher's dark/light theme."),
    EleneCommandDoc("toggle_battery_saver", "\"turn on battery saver\"", "Toggles this app's battery saver mode."),
    EleneCommandDoc("scroll_up / scroll_down", "\"scroll down\", \"scroll up\"", "Scrolls whatever screen is currently in front."),
    EleneCommandDoc("go_back / go_home / open_recents", "\"go back\", \"close WhatsApp\", \"show recents\"", "System navigation. Plain \"close X\" just leaves the app - it does not force-stop it."),
    EleneCommandDoc("click", "\"click Send\", \"tap Install\"", "Taps the on-screen element whose visible text matches."),
    EleneCommandDoc("highlight / highlight_off", "\"highlight Send\", \"highlight off\"", "Draws (or removes) a highlight box around a matching on-screen element."),
    EleneCommandDoc("type_text", "\"type hello there\"", "Types into whatever text field currently has focus."),
    EleneCommandDoc("hide_page", "\"hide the page\"", "Privacy cover over the screen - a toggle, saying it again turns it off."),
    EleneCommandDoc("flashlight", "\"turn on the flashlight\"", "Turns the torch on or off."),
    EleneCommandDoc("bluetooth", "\"turn on bluetooth\"", "Turns Bluetooth on directly. Turning it off needs one manual tap - Android doesn't allow apps to silently disable it."),
    EleneCommandDoc("scan_wifi", "\"who's on my wifi\"", "Scans and lists devices on the current wifi network."),
    EleneCommandDoc("volume", "\"turn the volume up\", \"set volume to 30\"", "Raises/lowers/sets media volume."),
    EleneCommandDoc("brightness", "\"dim the screen\", \"set brightness to 50\"", "Raises/lowers/sets screen brightness."),
    EleneCommandDoc("start_screen_recording / stop_screen_recording", "\"start recording my screen\", \"stop recording\"", "Starts or stops (and saves) a screen recording."),
    EleneCommandDoc("stop_listening", "\"stop listening\", \"that's all\", \"go away\"", "Tells Xenos to stop listening until you wake it again."),
    EleneCommandDoc("world_clock", "\"what time is it in Tokyo\"", "Looks up the time in another timezone - computed on-device, no internet needed."),
    EleneCommandDoc("next_page / previous_page", "\"next page\", \"previous page\"", "Pages through the launcher's app grid."),
    EleneCommandDoc("freeze_app / unfreeze_app", "\"freeze Instagram\", \"unfreeze Instagram\"", "Suspends/resumes an app - hides it from the launcher without killing its process."),
    EleneCommandDoc("force_stop_app", "\"force stop Instagram\", \"kill that app\"", "Actually stops the running process (stronger than freeze). A genuine force-stop needs Shizuku set up - otherwise it stops background processes instead and says so."),
    EleneCommandDoc("download_app", "\"download TikTok\", \"get me Spotify\"", "Opens the Play Store search for an app that isn't installed yet."),
    EleneCommandDoc("schedule", "\"download TikTok in 2 hours\"", "Delays a command (currently only download_app supports this) until the time you gave."),
    EleneCommandDoc("remember_avoid / forget_avoid", "\"don't bring up X again\", \"stop avoiding X\"", "Tells Xenos to stop (or resume) mentioning a topic in conversation."),
    EleneCommandDoc("describe_screen", "\"what's on my screen\", \"read this to me\"", "The only command where Xenos actually looks at the screen (a real screenshot sent to a vision model) - everything else works off on-screen text/labels, never pixels. Asks for the system screen-sharing permission each time."),
    EleneCommandDoc("play_game / stop_game", "\"play this for me\", \"stop playing\"", "Xenos repeatedly screenshots the game and taps/swipes a move, looping until told to stop. Slow, turn-based games only (word games, non-timed match-3, cards, puzzles) - each move takes a few real seconds of thinking, so this does not work for fast/reflex games. Stops immediately if you switch apps.")
)

@Composable
fun CommandReferenceScreen(
    themeColor: Color,
    isDark: Boolean,
    onBack: () -> Unit
) {
    TerminalScaffold(
        themeColor = themeColor,
        code = "COMMANDS",
        title = "Commands",
        subtitle = "Everything Xenos can be told to do, and roughly how to say it.",
        backLabel = "‹  BACK",
        onBack = onBack
    ) {
        TerminalCard(label = "REFERENCE (${ELENE_COMMANDS.size})", themeColor = themeColor) {
            ELENE_COMMANDS.forEachIndexed { index, doc ->
                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    Text(
                        text = doc.command,
                        color = themeColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = doc.sayThis,
                        color = TerminalStyle.muted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        text = doc.whatItDoes,
                        color = TerminalStyle.ink,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
