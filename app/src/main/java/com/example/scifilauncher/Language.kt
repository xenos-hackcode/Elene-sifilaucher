package com.example.scifilauncher

import android.content.SharedPreferences
import java.util.Locale

// ========== Language option + prefs ==========

enum class LanguageOption {
    ENGLISH,
    YORUBA,
    MANDARIN,
    KOREAN,
    FRENCH,
    SPANISH,
    GERMAN
}

fun loadLanguage(prefs: SharedPreferences): LanguageOption {
    val name = prefs.getString("language_option", LanguageOption.ENGLISH.name)
    return try {
        LanguageOption.valueOf(name ?: LanguageOption.ENGLISH.name)
    } catch (_: Exception) {
        LanguageOption.ENGLISH
    }
}

fun saveLanguage(prefs: SharedPreferences, option: LanguageOption) {
    prefs.edit().putString("language_option", option.name).apply()
}

fun languageToLocale(option: LanguageOption): Locale {
    return when (option) {
        LanguageOption.ENGLISH -> Locale.UK
        LanguageOption.YORUBA -> Locale("yo")
        LanguageOption.MANDARIN -> Locale.SIMPLIFIED_CHINESE
        LanguageOption.KOREAN -> Locale.KOREAN
        LanguageOption.FRENCH -> Locale.FRENCH
        LanguageOption.SPANISH -> Locale("es")
        LanguageOption.GERMAN -> Locale.GERMAN
    }
}

// ========== Elene phrases ==========
// key list (fixed sentences only, no dynamic app/user text):
// - hey_elene_prompt
// - im_here
// - cleared_notifications
// - ask_which_app_general
// - ask_which_app_retry
// - no_replyable_for_app
// - ask_message_text
// - multiple_people_prompt
// - lost_notifications_for_app
// - person_not_found
// - all_missed_done
// (the dynamic "From X on Y: Z" and calls stay constructed in MainActivity)

fun elenePhrase(
    key: String,
    lang: LanguageOption,
    userName: String?,          // NEW: current username, can be null
    vararg args: String
): String {
    // Default address term. The AI backend switches to "Xenos" mid-conversation
    // when the user indicates someone else is around; these local canned phrases
    // (launch-time greetings etc.) have no such context, so they always use "Emperor".
    val name = userName?.ifBlank { null } ?: "Emperor"

    return when (key) {

        "hey_elene_prompt" -> when (lang) {
            LanguageOption.ENGLISH ->
                "Hello, $name. Do you want me to check if you missed any notifications?"
            LanguageOption.YORUBA ->
                "Pẹ̀lẹ́, $name. Ṣe kí n ṣàyẹ̀wò bóyá o ti padanu ìkìlọ́ kankan?"
            LanguageOption.MANDARIN ->
                "你好，$name。要不要我检查你有没有错过通知？"
            LanguageOption.KOREAN ->
                "안녕, $name. 놓친 알림이 있는지 확인해 줄까?"
            LanguageOption.FRENCH ->
                "Bonjour, $name. Veux‑tu que je vérifie si tu as manqué des notifications ?"
            LanguageOption.SPANISH ->
                "Hola, $name. ¿Quieres que revise si te perdiste alguna notificación?"
            LanguageOption.GERMAN ->
                "Hallo, $name. Soll ich prüfen, ob du Benachrichtigungen verpasst hast?"
        }

        "im_here" -> when (lang) {
            LanguageOption.ENGLISH -> "I am here with you."
            LanguageOption.YORUBA -> "Mo wà lẹ́gbẹ̀ẹ́ rẹ."
            LanguageOption.MANDARIN -> "我在这里陪着你。"
            LanguageOption.KOREAN -> "나는 여기서 너와 함께 있어."
            LanguageOption.FRENCH -> "Je suis ici avec toi."
            LanguageOption.SPANISH -> "Estoy aquí contigo."
            LanguageOption.GERMAN -> "Ich bin hier bei dir."
        }

        "cleared_notifications" -> when (lang) {
            LanguageOption.ENGLISH ->
                "I cleared your missed notifications history."
            LanguageOption.YORUBA ->
                "Mo ti pa itan ìkìlọ́ tí o ti padanu rẹ."
            LanguageOption.MANDARIN ->
                "我已经清除了你错过通知的记录。"
            LanguageOption.KOREAN ->
                "놓친 알림 기록을 지웠어."
            LanguageOption.FRENCH ->
                "J’ai effacé l’historique de tes notifications manquées."
            LanguageOption.SPANISH ->
                "He borrado el historial de tus notificaciones perdidas."
            LanguageOption.GERMAN ->
                "Ich habe deinen Verlauf verpasster Benachrichtigungen gelöscht."
        }

        "ask_which_app_general" -> when (lang) {
            LanguageOption.ENGLISH ->
                "Which app should I reply on? You can say the app name, like %s."
            LanguageOption.YORUBA ->
                "Lórí ètò wo ni kí n dáhùn? O lè sọ orúkọ app náà, bíi %s."
            LanguageOption.MANDARIN ->
                "我要在哪个应用里回复？你可以说应用名称，比如 %s。"
            LanguageOption.KOREAN ->
                "어떤 앱에서 답장할까? 예를 들어 %s 라고 말해줘."
            LanguageOption.FRENCH ->
                "Sur quelle application dois‑je répondre ? Tu peux dire le nom, par exemple %s."
            LanguageOption.SPANISH ->
                "En qué aplicación debo responder Puedes decir el nombre, como %s."
            LanguageOption.GERMAN ->
                "In welcher App soll ich antworten? Du kannst den Namen sagen, zum Beispiel %s."
        }

        "ask_which_app_retry" -> when (lang) {
            LanguageOption.ENGLISH ->
                "Okay. Which app did you mean?"
            LanguageOption.YORUBA ->
                "Ó dáa. Ètò wo ni o túmọ̀ sí?"
            LanguageOption.MANDARIN ->
                "好。你指的是哪个应用？"
            LanguageOption.KOREAN ->
                "좋아. 어떤 앱을 말한 거야?"
            LanguageOption.FRENCH ->
                "D’accord. De quelle application parlais‑tu ?"
            LanguageOption.SPANISH ->
                "Vale. A qué aplicación te referías?"
            LanguageOption.GERMAN ->
                "Okay. Welche App meintest du?"
        }

        "no_replyable_for_app" -> when (lang) {
            LanguageOption.ENGLISH ->
                "I couldn't find any replyable notification for that app. Try saying the app name exactly as you see it, like WhatsApp or Messages."
            LanguageOption.YORUBA ->
                "Mi ò rí ìkìlọ́ tí mo lè dáhùn fún app yẹn. Gbìyànjú láti sọ orúkọ app náà gẹ́gẹ́ bí o ṣe rí i, bíi WhatsApp tàbí Messages."
            LanguageOption.MANDARIN ->
                "我找不到这个应用可以直接回复的通知。试着按你看到的名字说应用，比如 WhatsApp 或 Messages。"
            LanguageOption.KOREAN ->
                "그 앱에서 바로 답장할 수 있는 알림을 찾지 못했어. WhatsApp이나 메시지처럼 보이는 이름 그대로 말해줘."
            LanguageOption.FRENCH ->
                "Je n’ai trouvé aucune notification à laquelle je peux répondre pour cette application. Essaie de dire son nom exactement comme tu le vois, comme WhatsApp ou Messages."
            LanguageOption.SPANISH ->
                "No pude encontrar ninguna notificación con respuesta directa para esa app. Intenta decir el nombre exactamente como lo ves, como WhatsApp o Mensajes."
            LanguageOption.GERMAN ->
                "Ich konnte keine antwortbare Benachrichtigung für diese App finden. Versuche den App‑Namen genauso zu sagen, wie du ihn siehst, zum Beispiel WhatsApp oder Nachrichten."
        }

        "notif_from_app" -> when (lang) {
            LanguageOption.ENGLISH ->
                "$name, you got a notification from %s."
            LanguageOption.YORUBA ->
                "$name, o gba ìkìlọ́ láti ọ̀dọ̀ %s."
            LanguageOption.MANDARIN ->
                "$name，你收到了来自 %s 的通知。"
            LanguageOption.KOREAN ->
                "$name, %s 에서 알림이 왔어."
            LanguageOption.FRENCH ->
                "$name, tu as reçu une notification de %s."
            LanguageOption.SPANISH ->
                "$name, recibiste una notificación de %s."
            LanguageOption.GERMAN ->
                "$name, du hast eine Benachrichtigung von %s bekommen."
        }

        "ask_message_text" -> when (lang) {
            LanguageOption.ENGLISH ->
                "What do you want me to reply to %s on %s?"
            LanguageOption.YORUBA ->
                "Kí ni kí n dáhùn sí %s lórí %s?"
            LanguageOption.MANDARIN ->
                "你想让我在 %2\$s 回复给 %1\$s 什么内容？"
            LanguageOption.KOREAN ->
                "%2\$s에서 %1\$s 에게 뭐라고 답장할까?"
            LanguageOption.FRENCH ->
                "Que veux‑tu que je réponde à %s sur %s ?"
            LanguageOption.SPANISH ->
                "Qué quieres que responda a %s en %s?"
            LanguageOption.GERMAN ->
                "Was soll ich %s in %s antworten?"
        }

        "multiple_people_prompt" -> when (lang) {
            LanguageOption.ENGLISH ->
                "You have messages from %s. Who should I reply to?"
            LanguageOption.YORUBA ->
                "O ní àwọn ìránṣẹ́ láti ọ̀dọ̀ %s. Tálọ́ ni kí n dáhùn sí?"
            LanguageOption.MANDARIN ->
                "你有来自 %s 的消息。我要回复谁？"
            LanguageOption.KOREAN ->
                "%s 에게서 메시지가 있어. 누구에게 답장할까?"
            LanguageOption.FRENCH ->
                "Tu as des messages de %s. À qui veux‑tu que je réponde ?"
            LanguageOption.SPANISH ->
                "Tienes mensajes de %s. A quién debo responder?"
            LanguageOption.GERMAN ->
                "Du hast Nachrichten von %s. Wem soll ich antworten?"
        }

        "lost_notifications_for_app" -> when (lang) {
            LanguageOption.ENGLISH ->
                "I lost the notifications for that app, please try replying again."
            LanguageOption.YORUBA ->
                "Mo ti pàdánù àwọn ìkìlọ́ fún app yẹn, jọ̀wọ́ gbìyànjú láti dáhùn lẹ́ẹ̀kansi."
            LanguageOption.MANDARIN ->
                "我丢失了那个应用的通知，请再试一次回复。"
            LanguageOption.KOREAN ->
                "그 앱의 알림을 잃어버렸어. 다시 답장해 봐."
            LanguageOption.FRENCH ->
                "J’ai perdu les notifications pour cette application, essaie de répondre à nouveau."
            LanguageOption.SPANISH ->
                "Perdí las notificaciones de esa app, intenta responder otra vez."
            LanguageOption.GERMAN ->
                "Ich habe die Benachrichtigungen für diese App verloren, bitte versuche erneut zu antworten."
        }

        "person_not_found" -> when (lang) {
            LanguageOption.ENGLISH ->
                "I couldn't find that person. You can choose from: %s."
            LanguageOption.YORUBA ->
                "Mi ò rí ẹni náà. O lè yan láti inú: %s."
            LanguageOption.MANDARIN ->
                "我找不到那个人。你可以从这些中选择：%s。"
            LanguageOption.KOREAN ->
                "그 사람을 찾지 못했어. 다음 중에서 선택할 수 있어: %s."
            LanguageOption.FRENCH ->
                "Je n’ai pas trouvé cette personne. Tu peux choisir parmi : %s."
            LanguageOption.SPANISH ->
                "No pude encontrar a esa persona. Puedes elegir entre: %s."
            LanguageOption.GERMAN ->
                "Ich konnte diese Person nicht finden. Du kannst auswählen aus: %s."
        }

        "all_missed_done" -> when (lang) {
            LanguageOption.ENGLISH ->
                "That's all of your missed notifications."
            LanguageOption.YORUBA ->
                "Ìyẹn ni gbogbo ìkìlọ́ tí o ti padanu."
            LanguageOption.MANDARIN ->
                "这些就是你所有错过的通知。"
            LanguageOption.KOREAN ->
                "놓친 알림은 여기까지야."
            LanguageOption.FRENCH ->
                "C’est tout pour tes notifications manquées."
            LanguageOption.SPANISH ->
                "Esas son todas tus notificaciones perdidas."
            LanguageOption.GERMAN ->
                "Das waren alle deine verpassten Benachrichtigungen."
        }

        else -> key
    }.let { template ->
        if (args.isNotEmpty()) String.format(template, *args) else template
    }
}