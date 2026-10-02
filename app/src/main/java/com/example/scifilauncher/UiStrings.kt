package com.example.scifilauncher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/** The in-app language choice (independent of the device's system locale - Language.kt's
 * LanguageOption) provided down the whole Compose tree from MainActivity, so any screen can call
 * tr("key") without threading a language parameter through every composable's signature. Default
 * is English so a composable previewed/used outside the real provider still renders sane text. */
val LocalLanguage = compositionLocalOf { LanguageOption.ENGLISH }

@Composable
fun tr(key: String, vararg args: String): String = uiString(key, LocalLanguage.current, *args)

fun uiString(key: String, lang: LanguageOption, vararg args: String): String {
    val row = UI_STRINGS[key]
    val template = row?.get(lang) ?: row?.get(LanguageOption.ENGLISH) ?: key
    return if (args.isNotEmpty()) String.format(template, *args) else template
}

private fun s(
    en: String,
    yo: String,
    zh: String,
    ko: String,
    fr: String,
    es: String,
    de: String
): Map<LanguageOption, String> = mapOf(
    LanguageOption.ENGLISH to en,
    LanguageOption.YORUBA to yo,
    LanguageOption.MANDARIN to zh,
    LanguageOption.KOREAN to ko,
    LanguageOption.FRENCH to fr,
    LanguageOption.SPANISH to es,
    LanguageOption.GERMAN to de
)

/**
 * Full-app UI string table (Settings screen fully covered as of 2026-08-10; other screens are
 * being migrated incrementally - see planner). Keyed by a stable id, not the English text itself,
 * so English wording can change later without breaking the other 6 languages' entries.
 */
private val UI_STRINGS: Map<String, Map<LanguageOption, String>> = mapOf(
    // ---- Settings: header ----
    "back_dash" to s("< DASH", "< ÀÁRÍN", "< 主页", "< 대시보드", "< TABLEAU", "< PANEL", "< DASH"),
    "settings_title" to s("SETTINGS", "ÌṢÉTÒ", "设置", "설정", "PARAMÈTRES", "AJUSTES", "EINSTELLUNGEN"),

    // ---- APPEARANCE ----
    "section_appearance" to s("APPEARANCE", "ÌRÍRA", "外观", "화면", "APPARENCE", "APARIENCIA", "DARSTELLUNG"),
    "row_theme" to s("Theme", "Àwòrán", "主题", "테마", "Thème", "Tema", "Design"),
    "row_font_size" to s("Font size", "Ìwọ̀n lẹ́tà", "字体大小", "글자 크기", "Taille de police", "Tamaño de fuente", "Schriftgröße"),
    "font_small" to s("Small", "Kékeré", "小", "작게", "Petit", "Pequeño", "Klein"),
    "font_normal" to s("Normal", "Déédéé", "正常", "보통", "Normal", "Normal", "Normal"),
    "font_large" to s("Large", "Ńlá", "大", "크게", "Grand", "Grande", "Groß"),
    "font_huge" to s("Huge", "Ńlá gan-an", "特大", "매우 크게", "Très grand", "Muy grande", "Riesig"),
    "row_icon_pack" to s("Icon pack", "Àwòrán ìsàmì", "图标包", "아이콘 팩", "Pack d'icônes", "Paquete de iconos", "Icon-Paket"),

    // ---- NETWORK ----
    "section_network" to s("NETWORK", "ÌNÀGA", "网络", "네트워크", "RÉSEAU", "RED", "NETZWERK"),
    "row_wifi" to s("Wi-Fi", "Wi-Fi", "Wi-Fi", "와이파이", "Wi-Fi", "Wi-Fi", "WLAN"),
    "wifi_not_connected" to s("Not connected", "Kò so", "未连接", "연결 안 됨", "Non connecté", "No conectado", "Nicht verbunden"),
    "row_signal_speed" to s("Signal / speed", "Ìfàmọ́ / ìyára", "信号/速度", "신호 / 속도", "Signal / vitesse", "Señal / velocidad", "Signal / Geschwindigkeit"),
    "row_ip_address" to s("IP address", "Àdírẹ́sì IP", "IP 地址", "IP 주소", "Adresse IP", "Dirección IP", "IP-Adresse"),
    "row_reveal_password" to s("Reveal password", "Fi ọ̀rọ̀ìpamọ́ hàn", "显示密码", "비밀번호 표시", "Révéler le mot de passe", "Mostrar contraseña", "Passwort anzeigen"),
    "value_unavailable" to s("Unavailable", "Kò sí", "不可用", "사용 불가", "Indisponible", "No disponible", "Nicht verfügbar"),
    "value_tap_to_reveal" to s("Tap to reveal", "Tẹ láti fi hàn", "点击显示", "탭하여 표시", "Touchez pour révéler", "Toca para revelar", "Tippen zum Anzeigen"),
    "wifi_dialog_title" to s("Wi-Fi", "Wi-Fi", "Wi-Fi", "와이파이", "Wi-Fi", "Wi-Fi", "WLAN"),
    "wifi_dialog_body" to s(
        "Shows the currently connected network. \"Reveal password\" reads the saved password for that network from Android's own Wi-Fi config store - only possible because this app is enrolled as this device's Device Owner, which keeps access ordinary apps lost in Android 10+. Needs your fingerprint first, and only works for networks actually saved on this device.",
        "Ó fi ètò tí a ti so mọ́ǹ hàn nísinsìnyí. \"Fi ọ̀rọ̀ìpamọ́ hàn\" ń ka ọ̀rọ̀ìpamọ́ tí a fi pamọ́ fún ètò yẹn láti inú ibi ìpamọ́ Wi-Fi ti Android fúnra rẹ̀ - èyí ṣeéṣe nítorí pé app yìí jẹ́ Onílé Ẹrọ ẹ̀rọ yìí, èyí tí ó pa àǹfààní tí àwọn app lásán ti pàdánù láti Android 10 wá mọ́. Ó nílò ìwádìí ìka rẹ láti kọ́kọ́, ó sì ń ṣiṣẹ́ nìkan fún àwọn ètò tí a ti fi pamọ́ ní gbogbo gbò lórí ẹ̀rọ yìí.",
        "显示当前连接的网络。“显示密码”会从 Android 自己的 Wi-Fi 配置存储中读取该网络保存的密码 - 之所以能做到，是因为本应用是这台设备的设备所有者（Device Owner），拥有 Android 10 及以上版本中普通应用已失去的访问权限。需要先验证指纹，并且只对本设备上实际保存过的网络有效。",
        "현재 연결된 네트워크를 보여줘. \"비밀번호 표시\"는 Android 자체 Wi-Fi 설정 저장소에서 해당 네트워크의 저장된 비밀번호를 읽어옵니다 - 이 앱이 이 기기의 기기 소유자(Device Owner)로 등록되어 있어서만 가능한데, 이는 Android 10 이상에서 일반 앱은 잃어버린 접근 권한입니다. 먼저 지문 인증이 필요하며, 실제로 이 기기에 저장된 네트워크에서만 작동합니다.",
        "Affiche le réseau actuellement connecté. « Révéler le mot de passe » lit le mot de passe enregistré pour ce réseau depuis le propre magasin de configuration Wi-Fi d'Android - possible uniquement parce que cette application est enregistrée comme propriétaire de cet appareil (Device Owner), ce qui conserve un accès que les applications ordinaires ont perdu depuis Android 10+. Nécessite d'abord votre empreinte digitale, et ne fonctionne que pour les réseaux réellement enregistrés sur cet appareil.",
        "Muestra la red actualmente conectada. \"Mostrar contraseña\" lee la contraseña guardada de esa red desde el propio almacén de configuración Wi-Fi de Android - solo es posible porque esta app está registrada como el Propietario del Dispositivo (Device Owner), lo que conserva un acceso que las apps normales perdieron desde Android 10+. Requiere tu huella digital primero, y solo funciona con redes realmente guardadas en este dispositivo.",
        "Zeigt das aktuell verbundene Netzwerk. „Passwort anzeigen“ liest das gespeicherte Passwort für dieses Netzwerk aus Androids eigenem Wi-Fi-Konfigurationsspeicher - das ist nur möglich, weil diese App als Geräteinhaber (Device Owner) dieses Geräts registriert ist, wodurch ein Zugriff erhalten bleibt, den normale Apps seit Android 10+ verloren haben. Erfordert zuerst deinen Fingerabdruck und funktioniert nur bei Netzwerken, die tatsächlich auf diesem Gerät gespeichert sind."
    ),
    "action_close" to s("CLOSE", "PÁDÉ", "关闭", "닫기", "FERMER", "CERRAR", "SCHLIESSEN"),

    // ---- SYSTEM & BEHAVIOR ----
    "section_system_behavior" to s("SYSTEM & BEHAVIOR", "ÈTÒ ÀTI ÌṢESÍ", "系统与行为", "시스템 및 동작", "SYSTÈME ET COMPORTEMENT", "SISTEMA Y COMPORTAMIENTO", "SYSTEM & VERHALTEN"),
    "row_time_format" to s("Time format", "Ìlànà àkókò", "时间格式", "시간 형식", "Format de l'heure", "Formato de hora", "Zeitformat"),
    "time_24h" to s("24-hour", "Wákàtí 24", "24小时制", "24시간제", "24 heures", "24 horas", "24-Stunden"),
    "time_12h" to s("12-hour", "Wákàtí 12", "12小时制", "12시간제", "12 heures", "12 horas", "12-Stunden"),
    "row_battery_saver" to s("Battery saver", "Àwùjọ agbára", "省电模式", "배터리 절약", "Économie de batterie", "Ahorro de batería", "Akkusparen"),
    "row_language" to s("Language", "Èdè", "语言", "언어", "Langue", "Idioma", "Sprache"),
    "lang_english" to s("English", "Gẹ̀ẹ́sì", "英语", "영어", "Anglais", "Inglés", "Englisch"),
    "lang_yoruba" to s("Yoruba", "Yorùbá", "约鲁巴语", "요루바어", "Yoruba", "Yoruba", "Yoruba"),
    "lang_mandarin" to s("Mandarin", "Máńdálíìnì", "普通话", "표준 중국어", "Mandarin", "Mandarín", "Mandarin"),
    "lang_korean" to s("Korean", "Kòríà", "韩语", "한국어", "Coréen", "Coreano", "Koreanisch"),
    "lang_french" to s("French", "Faransé", "法语", "프랑스어", "Français", "Francés", "Französisch"),
    "lang_spanish" to s("Spanish", "Sípáníṣì", "西班牙语", "스페인어", "Espagnol", "Español", "Spanisch"),
    "lang_german" to s("German", "Jámánì", "德语", "독일어", "Allemand", "Alemán", "Deutsch"),
    "row_keyboard" to s("Keyboard", "Pátákò kíkọ̀", "键盘", "키보드", "Clavier", "Teclado", "Tastatur"),
    "keyboard_normal" to s("Normal", "Déédéé", "正常", "보통", "Normal", "Normal", "Normal"),
    "keyboard_xenos" to s("Xenos", "Xenos", "Xenos", "Xenos", "Xenos", "Xenos", "Xenos"),
    "keyboard_cedal" to s("Cedal", "Cedal", "Cedal", "Cedal", "Cedal", "Cedal", "Cedal"),
    "row_xenos_voice" to s("Xenos voice", "Ohùn Xenos", "Xenos 语音", "Xenos 음성", "Voix de Xenos", "Voz de Xenos", "Xenos-Stimme"),
    "row_xenos_keeps_listening" to s("Xenos keeps listening", "Xenos ń tẹ̀síwájú láti gbọ́", "Xenos 持续聆听", "Xenos가 계속 듣기", "Xenos continue d'écouter", "Xenos sigue escuchando", "Xenos hört weiter zu"),
    "dialog_keeps_listening_title" to s("Xenos keeps listening", "Xenos ń tẹ̀síwájú láti gbọ́", "Xenos 持续聆听", "Xenos가 계속 듣기", "Xenos continue d'écouter", "Xenos sigue escuchando", "Xenos hört weiter zu"),
    "dialog_keeps_listening_body" to s(
        "On: after replying, Xenos keeps the mic open and waits for your next thing - he only stops when you say \"stop listening\" (or a clear equivalent like \"go away\").\n\nOff: he stops listening automatically after every single reply, the same as before - you'll need to tap the bubble again each time.",
        "Ti ó bá ń ṣiṣẹ́: lẹ́yìn ìdáhùn, Xenos yóò jẹ́ kí máìkì wà ní ṣíṣí, yóò sì dúró de ohun tí ó kàn - yóò dúró nìkan tí o bá sọ pé \"stop listening\" (tàbí ohun tí ó jọ bíi \"lọ kúrò\").\n\nTí kò bá ń ṣiṣẹ́: yóò dúró láti gbọ́ lẹ́yìn ìdáhùn kọ̀ọ̀kan, gẹ́gẹ́ bí ti tẹ́lẹ̀ - o ó máa tẹ bọ́ọ̀lù náà lẹ́ẹ̀kansi ní gbogbo ìgbà.",
        "开启：回复后，Xenos 会保持麦克风开启并等待你说下一句话 - 只有当你说“停止聆听”（或类似的话，比如“走开”）时它才会停止。\n\n关闭：每次回复后它都会自动停止聆听，和以前一样 - 你需要每次重新点击气泡。",
        "켜짐: 응답 후 Xenos는 마이크를 계속 열어두고 다음 말을 기다립니다 - \"그만 들어\" (또는 \"저리 가\" 같은 표현)라고 말할 때만 멈춥니다.\n\n꺼짐: 매번 응답 후 자동으로 듣기를 멈춥니다, 이전과 동일하게 - 매번 다시 버블을 탭해야 합니다.",
        "Activé : après avoir répondu, Xenos garde le micro ouvert et attend ta prochaine demande - il ne s'arrête que si tu dis « stop listening » (ou un équivalent clair comme « va-t'en »).\n\nDésactivé : il arrête d'écouter automatiquement après chaque réponse, comme avant - tu devras retoucher la bulle à chaque fois.",
        "Activado: después de responder, Xenos mantiene el micrófono abierto y espera tu siguiente petición - solo se detiene si dices \"stop listening\" (o un equivalente claro como \"vete\").\n\nDesactivado: deja de escuchar automáticamente después de cada respuesta, como antes - tendrás que tocar la burbuja de nuevo cada vez.",
        "An: Nach einer Antwort hält Xenos das Mikrofon offen und wartet auf deine nächste Eingabe - er stoppt nur, wenn du „stop listening“ sagst (oder ein klares Äquivalent wie „geh weg“).\n\nAus: Er stoppt automatisch nach jeder einzelnen Antwort, wie zuvor - du musst die Blase jedes Mal erneut antippen."
    ),
    "row_always_listening" to s(
        "Always listening (\"Hey Xenos\")", "Ó ń gbọ́ nígbà gbogbo (\"Hey Xenos\")", "始终聆听（“Hey Xenos”）",
        "항상 듣기 (\"Hey Xenos\")", "Toujours à l'écoute (« Hey Xenos »)", "Siempre escuchando (\"Hey Xenos\")",
        "Immer zuhören („Hey Xenos“)"
    ),
    "dialog_always_listening_title" to s(
        "Always listening (\"Hey Xenos\")", "Ó ń gbọ́ nígbà gbogbo (\"Hey Xenos\")", "始终聆听（“Hey Xenos”）",
        "항상 듣기 (\"Hey Xenos\")", "Toujours à l'écoute (« Hey Xenos »)", "Siempre escuchando (\"Hey Xenos\")",
        "Immer zuhören („Hey Xenos“)"
    ),
    "dialog_always_listening_body" to s(
        "On: Xenos periodically listens for \"Hey Xenos\" even when you haven't tapped the bubble, and starts a real listening turn the moment he hears it - you can still tap the bubble any time too, this doesn't replace that.\n\nHonest limitation: Android has no dedicated low-power wake-word engine exposed to apps, so this works by running real short listening sessions every few seconds - it costs real battery, more than \"Xenos keeps listening\" above. Automatically turns off whenever battery saver is on, and resumes on its own once it's off again.",
        "Ti ó bá ń ṣiṣẹ́: Xenos yóò máa gbọ́ fún \"Hey Xenos\" lẹ́ẹ̀kọ̀ọ̀kan bí o tilẹ̀ kò tẹ bọ́ọ̀lù náà, yóò sì bẹ̀rẹ̀ ìgbọ́ràn gidi ní ìṣẹ́jú tí ó bá gbọ́ ọ - o ṣì lè tẹ bọ́ọ̀lù náà nígbàkigbà, èyí kò rọ́pò ìyẹn.\n\nÒtítọ́ ààlà: Android kò ní ẹ̀rọ ìjí-ọ̀rọ̀ agbára-kékeré pàtàkì fún àwọn app, nítorí náà èyí ń ṣiṣẹ́ nípa ṣíṣe ìgbọ́ràn kúkúrú gidi ní gbogbo ìṣẹ́jú àáyá díẹ̀ - ó ń jẹ agbára gidi, ju \"Xenos ń tẹ̀síwájú láti gbọ́\" lókè lọ. Yóò pa ara rẹ̀ dá nígbàkúgbà tí àwùjọ agbára bá ń ṣiṣẹ́, yóò sì tún bẹ̀rẹ̀ fúnra rẹ̀ nígbà tí ó bá ti pa.",
        "开启：即使你没有点击气泡，Xenos 也会定期聆听“Hey Xenos”，一听到就立即开始真正的聆听 - 你仍然可以随时点击气泡，这不会取代它。\n\n如实说明：Android 没有向应用开放专用的低功耗唤醒词引擎，所以这是通过每隔几秒运行一次真正的短时聆听会话来实现的 - 会消耗真实电量，比上面的“Xenos 持续聆听”更耗电。省电模式开启时会自动关闭，关闭后会自动恢复。",
        "켜짐: 버블을 탭하지 않아도 Xenos가 주기적으로 \"Hey Xenos\"를 듣고, 들리는 즉시 실제 대화를 시작합니다 - 언제든 버블을 탭할 수도 있으며 이를 대체하지는 않습니다.\n\n솔직한 한계: Android는 앱에 노출된 전용 저전력 웨이크워드 엔진이 없어서, 몇 초마다 실제 짧은 청취 세션을 실행하는 방식으로 작동합니다 - 위의 \"Xenos가 계속 듣기\"보다 배터리를 더 많이 소모합니다. 배터리 절약 모드가 켜지면 자동으로 꺼지고, 꺼지면 다시 자동으로 재개됩니다.",
        "Activé : Xenos écoute périodiquement « Hey Xenos » même si tu n'as pas touché la bulle, et démarre une vraie session d'écoute dès qu'il l'entend - tu peux toujours toucher la bulle à tout moment, cela ne remplace pas cette option.\n\nLimite honnête : Android n'expose aux applications aucun moteur de mot de réveil à faible consommation dédié, donc ceci fonctionne en exécutant de vraies courtes sessions d'écoute toutes les quelques secondes - cela coûte de la vraie batterie, plus que « Xenos continue d'écouter » ci-dessus. Se désactive automatiquement dès que l'économie de batterie est activée, et reprend de lui-même une fois désactivée.",
        "Activado: Xenos escucha periódicamente \"Hey Xenos\" incluso cuando no has tocado la burbuja, e inicia una escucha real en cuanto lo oye - aún puedes tocar la burbuja en cualquier momento, esto no lo reemplaza.\n\nLimitación honesta: Android no expone a las apps un motor de palabra de activación de bajo consumo dedicado, así que esto funciona ejecutando sesiones de escucha cortas reales cada pocos segundos - consume batería real, más que \"Xenos sigue escuchando\" arriba. Se apaga automáticamente cuando el ahorro de batería está activado, y se reanuda solo cuando se desactiva.",
        "An: Xenos hört regelmäßig auf „Hey Xenos“, auch wenn du die Blase nicht angetippt hast, und startet eine echte Zuhör-Runde, sobald er es hört - du kannst die Blase jederzeit trotzdem antippen, das ersetzt diese Funktion nicht.\n\nEhrliche Einschränkung: Android bietet Apps keine dedizierte stromsparende Weckwort-Engine, daher funktioniert dies durch echte kurze Zuhör-Sitzungen alle paar Sekunden - das kostet echten Akku, mehr als „Xenos hört weiter zu“ oben. Schaltet sich automatisch aus, sobald der Akkusparmodus aktiv ist, und läuft von selbst weiter, sobald er wieder aus ist."
    ),
    "row_record_hey_xenos" to s(
        "Record my voice for \"Hey Xenos\"", "Gbà ohùn mi fún \"Hey Xenos\"", "为“Hey Xenos”录制我的声音",
        "\"Hey Xenos\"를 위한 내 목소리 녹음", "Enregistrer ma voix pour « Hey Xenos »", "Grabar mi voz para \"Hey Xenos\"",
        "Meine Stimme für „Hey Xenos“ aufnehmen"
    ),
    "row_rerecord_hey_xenos" to s(
        "Re-record my voice for \"Hey Xenos\"", "Tún gbà ohùn mi fún \"Hey Xenos\"", "重新录制我为“Hey Xenos”的声音",
        "\"Hey Xenos\"를 위한 내 목소리 다시 녹음", "Réenregistrer ma voix pour « Hey Xenos »", "Volver a grabar mi voz para \"Hey Xenos\"",
        "Meine Stimme für „Hey Xenos“ neu aufnehmen"
    ),
    "hey_xenos_enrolled" to s(
        "Enrolled (%s takes)", "A ti gbà (ìgbà %s)", "已录制（%s 次）", "등록됨 (%s회)", "Enregistré (%s prises)", "Registrado (%s tomas)", "Registriert (%s Aufnahmen)"
    ),
    "hey_xenos_not_recorded" to s(
        "Not recorded - using generic detection", "A kò tíì gbà - a ń lo ìdámọ̀ gbogbogbò", "尚未录制 - 使用通用检测",
        "녹음 안 됨 - 일반 감지 사용 중", "Non enregistré - détection générique utilisée", "No grabado - usando detección genérica",
        "Nicht aufgenommen - generische Erkennung wird verwendet"
    ),
    "dialog_record_hey_xenos_title" to s(
        "Record my voice for \"Hey Xenos\"", "Gbà ohùn mi fún \"Hey Xenos\"", "为“Hey Xenos”录制我的声音",
        "\"Hey Xenos\"를 위한 내 목소리 녹음", "Enregistrer ma voix pour « Hey Xenos »", "Grabar mi voz para \"Hey Xenos\"",
        "Meine Stimme für „Hey Xenos“ aufnehmen"
    ),
    "dialog_record_hey_xenos_body" to s(
        "By default, \"Hey Xenos\" is detected by transcribing what you say and checking the text for the phrase - which depends on Android's speech recognizer getting the words right, and that can miss for some accents.\n\nRecording here switches to matching your own voice directly instead: say \"Hey Xenos\" 5 times, and future listening compares the raw sound of what you say against those takes - no transcription involved at all, so it no longer depends on Android getting the words right.\n\nHonest limitation: this is a simpler technique than a real trained wake-word model (matching against your own recordings, not a model trained on thousands of voices) - it can still occasionally miss or mis-trigger. A false trigger just opens a normal listening turn, same as it would today. If it's not working well, re-recording (tap again) replaces the old takes with fresh ones.",
        "Ní ìpìlẹ̀, a ń mọ̀ \"Hey Xenos\" nípa kíkọ ohun tí o sọ sí àkọsílẹ̀ àti wíwo àkọsílẹ̀ náà fún gbólóhùn náà - èyí gbára lé bí ẹ̀rọ ìdámọ̀ ọ̀rọ̀ Android ṣe rí àwọn ọ̀rọ̀ náà dáadáa, èyí tí ó lè kùnà fún àwọn ohùn kan.\n\nGbígbà níbí ń yí padà sí ìbáramu ohùn rẹ tààrà: sọ \"Hey Xenos\" ní ìgbà márùn-ún, ìgbọ́ràn ọjọ́ iwájú yóò sì fi ohun gidi tí o sọ wéra ìgbà wọ̀nyẹn - kò sí àkọsílẹ̀ kankan mọ́, nítorí náà kò gbára lé bí Android ṣe rí àwọn ọ̀rọ̀ náà dáadáa mọ́.\n\nÒtítọ́ ààlà: èyí jẹ́ ọ̀nà tí ó rọrùn ju àwòṣe ìjí-ọ̀rọ̀ tí a tí kọ́ gidi lọ (ìbáramu pẹ̀lú àwọn gbígbà tirẹ, kìí ṣe àwòṣe tí a kọ́ pẹ̀lú ẹgbẹẹgbẹ̀rún ohùn) - ó ṣì lè kùnà tàbí kúnjú lẹ́ẹ̀kọ̀ọ̀kan. Ìkúnjú irọ́ máa ń ṣí ìgbọ́ràn lásán, gẹ́gẹ́ bí ó ti ṣe lónìí. Tí kò bá ń ṣiṣẹ́ dáadáa, tún gbígbà (tẹ lẹ́ẹ̀kansi) yóò rọ́pò àwọn ìgbà àtijọ́ pẹ̀lú àwọn tuntun.",
        "默认情况下，“Hey Xenos”是通过转录你说的话并检查文本中是否含有该短语来检测的 - 这取决于 Android 语音识别器是否正确识别文字，某些口音可能会被漏掉。\n\n在此录制会改为直接匹配你自己的声音：说 5 次“Hey Xenos”，之后的聆听会将你说话的原始声音与这些录音进行比对 - 完全不涉及转录，因此不再依赖 Android 是否识别对文字。\n\n如实说明：这比真正训练过的唤醒词模型更简单（只是与你自己的录音匹配，而非基于数千种声音训练的模型） - 仍可能偶尔漏掉或误触发。误触发只会打开一次普通的聆听，和现在一样。如果效果不好，重新录制（再次点击）会用新的录音替换旧的。",
        "기본적으로 \"Hey Xenos\"는 네가 말한 것을 텍스트로 변환하고 그 문구를 확인하는 방식으로 감지됩니다 - 이는 Android 음성 인식기가 단어를 정확히 인식하는지에 달려 있으며, 일부 억양에서는 놓칠 수 있습니다.\n\n여기서 녹음하면 대신 네 목소리와 직접 매칭하는 방식으로 전환됩니다: \"Hey Xenos\"를 5번 말하면, 이후의 청취는 네가 말하는 원본 소리를 이 녹음들과 비교합니다 - 텍스트 변환이 전혀 없으므로 더 이상 Android가 단어를 정확히 인식하는지에 의존하지 않습니다.\n\n솔직한 한계: 이는 실제로 학습된 웨이크워드 모델보다 단순한 기술입니다 (수천 개의 목소리로 학습된 모델이 아니라 네 자신의 녹음과 매칭) - 여전히 가끔 놓치거나 오작동할 수 있습니다. 오작동은 오늘처럼 그냥 일반 청취를 엽니다. 잘 작동하지 않으면 다시 녹음(다시 탭)하면 이전 녹음이 새 녹음으로 교체됩니다.",
        "Par défaut, « Hey Xenos » est détecté en transcrivant ce que tu dis et en vérifiant si le texte contient la phrase - ce qui dépend de la capacité du reconnaisseur vocal d'Android à bien saisir les mots, ce qui peut échouer pour certains accents.\n\nEnregistrer ici passe plutôt à une correspondance directe avec ta propre voix : dis « Hey Xenos » 5 fois, et l'écoute future comparera le son brut de ce que tu dis à ces prises - aucune transcription n'est impliquée, donc cela ne dépend plus de la justesse d'Android.\n\nLimite honnête : c'est une technique plus simple qu'un vrai modèle de mot de réveil entraîné (correspondance avec tes propres enregistrements, pas un modèle entraîné sur des milliers de voix) - il peut encore occasionnellement rater ou se déclencher à tort. Un faux déclenchement ouvre simplement une écoute normale, comme aujourd'hui. Si ça ne fonctionne pas bien, réenregistrer (retoucher) remplace les anciennes prises par de nouvelles.",
        "Por defecto, \"Hey Xenos\" se detecta transcribiendo lo que dices y comprobando si el texto contiene la frase - lo cual depende de que el reconocedor de voz de Android acierte las palabras, y eso puede fallar con algunos acentos.\n\nGrabar aquí cambia a comparar directamente con tu propia voz: di \"Hey Xenos\" 5 veces, y la escucha futura comparará el sonido bruto de lo que dices con esas tomas - no hay transcripción involucrada, así que ya no depende de que Android acierte las palabras.\n\nLimitación honesta: esta es una técnica más simple que un modelo de palabra de activación realmente entrenado (comparación con tus propias grabaciones, no un modelo entrenado con miles de voces) - todavía puede fallar u activarse por error ocasionalmente. Una activación falsa simplemente abre una escucha normal, igual que hoy. Si no funciona bien, volver a grabar (toca de nuevo) reemplaza las tomas antiguas por unas nuevas.",
        "Standardmäßig wird „Hey Xenos“ erkannt, indem das Gesagte transkribiert und der Text auf die Phrase geprüft wird - das hängt davon ab, ob Androids Spracherkennung die Wörter richtig erfasst, was bei manchen Akzenten fehlschlagen kann.\n\nDie Aufnahme hier wechselt stattdessen zum direkten Abgleich deiner eigenen Stimme: Sage „Hey Xenos“ 5 Mal, und zukünftiges Zuhören vergleicht den rohen Klang dessen, was du sagst, mit diesen Aufnahmen - keine Transkription mehr beteiligt, sodass es nicht mehr davon abhängt, ob Android die Wörter richtig erfasst.\n\nEhrliche Einschränkung: Dies ist eine einfachere Technik als ein echtes trainiertes Weckwort-Modell (Abgleich mit deinen eigenen Aufnahmen, kein an Tausenden von Stimmen trainiertes Modell) - es kann gelegentlich immer noch versagen oder fehlauslösen. Ein Fehlauslöser öffnet einfach eine normale Zuhör-Runde, genau wie heute. Falls es nicht gut funktioniert, ersetzt eine Neuaufnahme (erneut antippen) die alten Aufnahmen durch frische."
    ),
    "hey_xenos_couldnt_record" to s(
        "Couldn't get enough clean takes - check your microphone and try again.",
        "A kò rí ìgbà mímọ́ tó pọ̀ - ṣàyẹ̀wò máìkì rẹ kí o sì tún gbìyànjú.",
        "无法获得足够清晰的录音 - 请检查麦克风后重试。",
        "충분히 깨끗한 녹음을 얻지 못했어 - 마이크를 확인하고 다시 시도해줘.",
        "Impossible d'obtenir assez de prises propres - vérifie ton micro et réessaie.",
        "No se pudieron obtener suficientes tomas limpias - revisa tu micrófono e inténtalo de nuevo.",
        "Nicht genug saubere Aufnahmen erhalten - überprüfe dein Mikrofon und versuche es erneut."
    ),
    "hey_xenos_recorded_ok" to s(
        "Recorded %s takes - \"Hey Xenos\" now matches your voice directly.",
        "A ti gbà ìgbà %s - \"Hey Xenos\" ti ń bá ohùn rẹ tààrà mu nísinsìnyí.",
        "已录制 %s 次 - “Hey Xenos”现在直接匹配你的声音。",
        "%s회 녹음됨 - 이제 \"Hey Xenos\"가 네 목소리와 직접 일치합니다.",
        "%s prises enregistrées - « Hey Xenos » correspond maintenant directement à ta voix.",
        "%s tomas grabadas - \"Hey Xenos\" ahora coincide directamente con tu voz.",
        "%s Aufnahmen gemacht - „Hey Xenos“ passt jetzt direkt zu deiner Stimme."
    ),
    "hey_xenos_recording_failed" to s(
        "Recording failed - try again.", "Gbígbà kùnà - tún gbìyànjú.", "录制失败 - 请重试。",
        "녹음 실패 - 다시 시도해줘.", "Échec de l'enregistrement - réessaie.", "Grabación fallida - inténtalo de nuevo.",
        "Aufnahme fehlgeschlagen - versuche es erneut."
    ),
    "hey_xenos_dialog_title" to s("\"Hey Xenos\"", "\"Hey Xenos\"", "“Hey Xenos”", "\"Hey Xenos\"", "« Hey Xenos »", "\"Hey Xenos\"", "„Hey Xenos“"),
    "hey_xenos_processing" to s("Processing...", "Ń ṣiṣẹ́ lórí rẹ̀...", "处理中...", "처리 중...", "Traitement...", "Procesando...", "Verarbeitung..."),
    "hey_xenos_say_it_now" to s("Say it now", "Sọ ọ́ nísinsìnyí", "现在说", "지금 말해줘", "Dis-le maintenant", "Dilo ahora", "Sag es jetzt"),
    "hey_xenos_take_of" to s(
        "Take %s of %s", "Ìgbà %s nínú %s", "第 %s 次，共 %s 次", "%s / %s회", "Prise %s sur %s", "Toma %s de %s", "Aufnahme %s von %s"
    ),
    "action_cancel" to s("CANCEL", "FAGILE", "取消", "취소", "ANNULER", "CANCELAR", "ABBRECHEN"),

    // ---- CAPABILITIES / MEMORY ----
    "section_capabilities" to s("CAPABILITIES", "AGBÁRA", "功能", "기능", "CAPACITÉS", "CAPACIDADES", "FÄHIGKEITEN"),
    "row_what_app_can_do" to s(
        "What this app can do", "Ohun tí app yìí lè ṣe", "此应用能做什么", "이 앱이 할 수 있는 일",
        "Ce que cette application peut faire", "Qué puede hacer esta app", "Was diese App kann"
    ),
    "section_memory" to s("MEMORY", "ÌRÁNTÍ", "记忆", "메모리", "MÉMOIRE", "MEMORIA", "SPEICHER"),
    "row_what_xenos_remembers" to s(
        "What Xenos remembers", "Ohun tí Xenos rántí", "Xenos 记得什么", "Xenos가 기억하는 것",
        "Ce dont Xenos se souvient", "Lo que Xenos recuerda", "Was Xenos sich merkt"
    ),

    // ---- ELENE BACKEND (open-source: everyone points at their own deployment, not a shared one) ----
    "section_elene_backend" to s("ELENE BACKEND", "ELENE BACKEND", "ELENE BACKEND", "ELENE BACKEND", "ELENE BACKEND", "ELENE BACKEND", "ELENE BACKEND"),
    "row_backend_url" to s("Backend URL", "Backend URL", "Backend URL", "Backend URL", "Backend URL", "Backend URL", "Backend URL"),
    "backend_url_configured" to s("Configured", "Configured", "Configured", "Configured", "Configured", "Configured", "Configured"),
    "backend_url_not_configured" to s("Not set - Elene won't respond", "Not set - Elene won't respond", "Not set - Elene won't respond", "Not set - Elene won't respond", "Not set - Elene won't respond", "Not set - Elene won't respond", "Not set - Elene won't respond"),
    "backend_url_dialog_title" to s("Elene Backend URL", "Elene Backend URL", "Elene Backend URL", "Elene Backend URL", "Elene Backend URL", "Elene Backend URL", "Elene Backend URL"),
    "backend_url_help" to s(
        "This app is open source - it doesn't come with a shared backend, so every install needs its own. Deploy backend/elene from the repo (see its .env.example for what it needs) and paste your own URL here.",
        "This app is open source - it doesn't come with a shared backend, so every install needs its own. Deploy backend/elene from the repo (see its .env.example for what it needs) and paste your own URL here.",
        "This app is open source - it doesn't come with a shared backend, so every install needs its own. Deploy backend/elene from the repo (see its .env.example for what it needs) and paste your own URL here.",
        "This app is open source - it doesn't come with a shared backend, so every install needs its own. Deploy backend/elene from the repo (see its .env.example for what it needs) and paste your own URL here.",
        "This app is open source - it doesn't come with a shared backend, so every install needs its own. Deploy backend/elene from the repo (see its .env.example for what it needs) and paste your own URL here.",
        "This app is open source - it doesn't come with a shared backend, so every install needs its own. Deploy backend/elene from the repo (see its .env.example for what it needs) and paste your own URL here.",
        "This app is open source - it doesn't come with a shared backend, so every install needs its own. Deploy backend/elene from the repo (see its .env.example for what it needs) and paste your own URL here."
    ),
    "backend_url_hint" to s(
        "https://your-backend.example.com", "https://your-backend.example.com", "https://your-backend.example.com",
        "https://your-backend.example.com", "https://your-backend.example.com", "https://your-backend.example.com",
        "https://your-backend.example.com"
    ),

    // ---- ABOUT & SUPPORT ----
    "section_about_support" to s("ABOUT & SUPPORT", "NÍPA ÀTI ÀTILẸ́YÌN", "关于与支持", "정보 및 지원", "À PROPOS ET ASSISTANCE", "ACERCA DE Y SOPORTE", "ÜBER & SUPPORT"),
    "row_feedback" to s("Feedback", "Àṣesí", "反馈", "피드백", "Retour", "Comentarios", "Feedback"),
    "feedback_message_hint" to s(
        "What's on your mind?", "Kí ni ó wà lọ́kàn rẹ?", "你想说什么？", "무슨 이야기를 하고 싶어?",
        "Qu'as-tu en tête ?", "¿Qué tienes en mente?", "Was liegt dir am Herzen?"
    ),
    "feedback_email_hint" to s(
        "Your Gmail (optional, for a reply)", "Gmail rẹ (kì í ṣe dandan, fún ìdáhùn)", "你的 Gmail（可选，用于回复）",
        "네 Gmail (선택, 답장용)", "Ton Gmail (facultatif, pour une réponse)", "Tu Gmail (opcional, para responder)",
        "Deine Gmail-Adresse (optional, für eine Antwort)"
    ),
    "action_send" to s("SEND", "FIRÁNṢẸ́", "发送", "보내기", "ENVOYER", "ENVIAR", "SENDEN"),
    "row_privacy_policy" to s("Privacy policy", "Ìlànà àṣírí", "隐私政策", "개인정보 처리방침", "Politique de confidentialité", "Política de privacidad", "Datenschutzrichtlinie"),
    "row_make_default_launcher" to s("Make default launcher", "Ṣe é ní launcher àkọ́kọ́", "设为默认启动器", "기본 런처로 설정", "Définir comme lanceur par défaut", "Establecer como launcher predeterminado", "Als Standard-Launcher festlegen"),
    "row_about" to s("About", "Nípa", "关于", "정보", "À propos", "Acerca de", "Über"),
    "row_more_apps" to s("More apps", "Àwọn app mìíràn", "更多应用", "더 많은 앱", "Plus d'applications", "Más aplicaciones", "Weitere Apps"),

    // ---- Panels: Theme selection uses ThemePanel.kt separately (not yet migrated) ----
    "panel_language_title" to s("LANGUAGE", "ÈDÈ", "语言", "언어", "LANGUE", "IDIOMA", "SPRACHE"),
    "panel_time_format_title" to s("TIME FORMAT", "ÌLÀNÀ ÀKÓKÒ", "时间格式", "시간 형식", "FORMAT DE L'HEURE", "FORMATO DE HORA", "ZEITFORMAT"),
    "time_12h_ampm" to s("12-hour (AM/PM)", "Wákàtí 12 (AM/PM)", "12小时制（上午/下午）", "12시간제 (오전/오후)", "12 heures (AM/PM)", "12 horas (AM/PM)", "12-Stunden (AM/PM)"),
    "panel_font_size_title" to s("FONT SIZE", "ÌWỌ̀N LẸ́TÀ", "字体大小", "글자 크기", "TAILLE DE POLICE", "TAMAÑO DE FUENTE", "SCHRIFTGRÖSSE"),
    "panel_keyboard_title" to s("KEYBOARD STYLE", "ÌRÌN PÁTÁKÒ", "键盘样式", "키보드 스타일", "STYLE DE CLAVIER", "ESTILO DE TECLADO", "TASTATURSTIL"),
    "keyboard_xenos_matrix" to s("Xenos (matrix)", "Xenos (matrix)", "Xenos（矩阵）", "Xenos (매트릭스)", "Xenos (matrice)", "Xenos (matriz)", "Xenos (Matrix)"),
    "keyboard_picker_hint" to s(
        "After choosing, select the keyboard in the system picker.",
        "Lẹ́yìn yíyàn, yan pátákò náà nínú àyèwò ètò.",
        "选择后，请在系统选择器中选择该键盘。",
        "선택 후 시스템 선택 창에서 키보드를 선택해줘.",
        "Après avoir choisi, sélectionne le clavier dans le sélecteur système.",
        "Después de elegir, selecciona el teclado en el selector del sistema.",
        "Wähle nach der Auswahl die Tastatur im System-Auswahldialog aus."
    ),
    "tap_outside_to_cancel" to s(
        "Tap outside to cancel.", "Tẹ ní òde láti fagile.", "点击外部取消。", "취소하려면 바깥을 탭하세요.",
        "Touchez à l'extérieur pour annuler.", "Toca fuera para cancelar.", "Zum Abbrechen daneben tippen."
    ),
    "value_selected" to s("SELECTED", "A YÀN", "已选择", "선택됨", "SÉLECTIONNÉ", "SELECCIONADO", "AUSGEWÄHLT"),

    // ==== Security screen (2026-08-11) ====
    "security_center_title" to s("SECURITY CENTER", "ÀARÍN AABÒ", "安全中心", "보안 센터", "CENTRE DE SÉCURITÉ", "CENTRO DE SEGURIDAD", "SICHERHEITSZENTRUM"),
    "action_ok" to s("OK", "Ó DÁA", "确定", "확인", "OK", "OK", "OK"),
    "action_save" to s("SAVE", "FI PAMỌ́", "保存", "저장", "ENREGISTRER", "GUARDAR", "SPEICHERN"),
    "action_never_show_again" to s("NEVER SHOW AGAIN", "MÁA FI HÀN MỌ́", "不再显示", "다시 표시 안 함", "NE PLUS AFFICHER", "NO MOSTRAR DE NUEVO", "NICHT MEHR ANZEIGEN"),

    "section_data_media" to s("DATA & MEDIA", "DÁTA ÀTI MÍDÍÀ", "数据与媒体", "데이터 및 미디어", "DONNÉES ET MÉDIAS", "DATOS Y MEDIOS", "DATEN & MEDIEN"),
    "row_freezer" to s("Freezer", "Firisa", "冻结库", "동결 보관함", "Congélateur", "Congelador", "Gefrierfach"),
    "row_hidden_apps" to s("Hidden apps", "Àwọn app tí a fi pamọ́", "隐藏的应用", "숨긴 앱", "Applications masquées", "Apps ocultas", "Versteckte Apps"),
    "row_intruder_attempts" to s("Intruder attempts", "Ìgbìyànjú adigunjale", "入侵尝试", "침입 시도", "Tentatives d'intrusion", "Intentos de intrusión", "Eindringversuche"),
    "row_file_manager" to s("File manager", "Alábojútó fáìlì", "文件管理器", "파일 관리자", "Gestionnaire de fichiers", "Administrador de archivos", "Dateimanager"),
    "row_commands" to s("Commands", "Àṣẹ", "命令", "명령어", "Commandes", "Comandos", "Befehle"),

    "section_activity" to s("ACTIVITY", "ÌṢẸ̀LẸ̀", "活动", "활동", "ACTIVITÉ", "ACTIVIDAD", "AKTIVITÄT"),
    "row_requests" to s("Requests", "Àwọn ìbéèrè", "请求", "요청", "Demandes", "Solicitudes", "Anfragen"),
    "row_updates" to s("Updates", "Àwọn ìmúdójúìwọ̀n", "更新", "업데이트", "Mises à jour", "Actualizaciones", "Updates"),
    "row_my_apps" to s("My apps", "Àwọn app mi", "我的应用", "내 앱", "Mes applis", "Mis apps", "Meine Apps"),
    "row_log" to s("Log", "Àkọsílẹ̀", "日志", "로그", "Journal", "Registro", "Protokoll"),
    "row_new_app_installs" to s("New app installs", "Àwọn app tuntun tí a fi sí", "新安装的应用", "새 앱 설치", "Nouvelles installations d'applications", "Nuevas instalaciones de apps", "Neue App-Installationen"),
    "row_watch_new_installs" to s("Watch new installs", "Ṣọ́ àwọn tuntun tí a fi sí", "监控新安装", "새 설치 감시", "Surveiller les nouvelles installations", "Vigilar nuevas instalaciones", "Neue Installationen überwachen"),

    "section_sequence_mode" to s("SEQUENCE MODE", "ÀṢOwọ́ SEQUENCE", "序列模式", "시퀀스 모드", "MODE SÉQUENCE", "MODO SECUENCIA", "SEQUENCE-MODUS"),
    "sequence_mode_desc" to s(
        "Anti-theft system. \"Standing by\" = watching normally. \"ACTIVE\" means a failed identity check triggered lockdown just now. Auto-arms on two real signals: 3 failed fingerprint scans within 10 minutes (immediate), or a sudden motion spike (asks \"are you running?\" first, then arms if not confirmed by fingerprint within 10 minutes) - plus the manual trigger below.",
        "Ètò ìdènà olè. \"Ó ń dúró de\" = ó ń ṣọ́ ní ìwọ̀nba. \"ACTIVE\" túmọ̀ sí pé àyẹ̀wò ìdánimọ̀ tí kùnà ti mú kí ìdènà bẹ̀rẹ̀ nísinsìnyí. Ó ń dá ara rẹ̀ dúró fún àwọn àmì gidi méjì: ìwádìí ìka tí kùnà ní ìgbà mẹ́ta láàrin ìṣẹ́jú 10 (lẹ́sẹ̀kẹsẹ̀), tàbí ìgbóná ìṣíṣẹ́ lójijì (yóò kọ́kọ́ béèrè \"ṣé o ń sáré?\", yóò sì dènà bí a kò bá fi ìka jẹ́rìí sí i láàrin ìṣẹ́jú 10) - àti ìdásẹ́ ọwọ́ ní ìsàlẹ̀.",
        "防盗系统。“待命中”=正常监视中。“ACTIVE（已激活）”表示刚刚有一次身份验证失败触发了锁定。会在两种真实信号下自动启动：10分钟内3次指纹识别失败（立即启动），或突然的剧烈运动（先询问“你在跑吗？”，如果10分钟内未通过指纹确认则启动）——此外下方还有手动触发按钮。",
        "도난 방지 시스템입니다. \"대기 중\" = 정상적으로 감시 중. \"ACTIVE\"는 방금 신원 확인 실패로 잠금이 실행되었음을 의미합니다. 두 가지 실제 신호에서 자동으로 작동합니다: 10분 이내 지문 스캔 3회 실패(즉시), 또는 갑작스러운 움직임 감지(먼저 \"뛰고 있어?\"라고 묻고, 10분 이내에 지문으로 확인하지 않으면 작동) - 그리고 아래의 수동 트리거도 있습니다.",
        "Système antivol. « En veille » = surveillance normale. « ACTIVE » signifie qu'un contrôle d'identité échoué vient de déclencher le verrouillage. S'arme automatiquement sur deux signaux réels : 3 scans d'empreinte échoués en 10 minutes (immédiat), ou un pic de mouvement soudain (demande d'abord « es-tu en train de courir ? », puis s'arme si non confirmé par empreinte digitale en 10 minutes) - plus le déclencheur manuel ci-dessous.",
        "Sistema antirrobo. \"En espera\" = vigilando normalmente. \"ACTIVE\" significa que una verificación de identidad fallida acaba de activar el bloqueo. Se arma automáticamente con dos señales reales: 3 escaneos de huella fallidos en 10 minutos (inmediato), o un pico de movimiento repentino (primero pregunta \"¿estás corriendo?\", y se arma si no se confirma con huella digital en 10 minutos) - además del disparador manual de abajo.",
        "Diebstahlschutzsystem. „Bereit“ = normale Überwachung. „ACTIVE“ bedeutet, dass eine fehlgeschlagene Identitätsprüfung gerade die Sperre ausgelöst hat. Aktiviert sich automatisch bei zwei echten Signalen: 3 fehlgeschlagene Fingerabdruck-Scans innerhalb von 10 Minuten (sofort), oder ein plötzlicher Bewegungsausschlag (fragt zuerst „Läufst du gerade?“, aktiviert sich dann, wenn nicht innerhalb von 10 Minuten per Fingerabdruck bestätigt) - plus der manuelle Auslöser unten."
    ),
    "row_anti_theft_status" to s("Anti-theft status", "Ipò ìdènà olè", "防盗状态", "도난 방지 상태", "État antivol", "Estado antirrobo", "Diebstahlschutzstatus"),
    "status_active_locked_down" to s("ACTIVE - locked down", "Ó ń ṣiṣẹ́ - a ti dènà", "已激活 - 已锁定", "ACTIVE - 잠김", "ACTIF - verrouillé", "ACTIVO - bloqueado", "AKTIV - gesperrt"),
    "status_awaiting_confirmation" to s("Awaiting confirmation (motion detected)", "Ń dúró de ìjẹ́rìí (ìṣíṣẹ́ ni a rí)", "等待确认（检测到移动）", "확인 대기 중 (움직임 감지됨)", "En attente de confirmation (mouvement détecté)", "Esperando confirmación (movimiento detectado)", "Warte auf Bestätigung (Bewegung erkannt)"),
    "status_standing_by" to s("Standing by", "Ó ń dúró de", "待命中", "대기 중", "En veille", "En espera", "Bereit"),
    "row_exit_sequence_mode" to s("Exit Sequence Mode", "Jáde kúrò ní Sequence Mode", "退出序列模式", "시퀀스 모드 종료", "Quitter le mode séquence", "Salir del modo secuencia", "Sequence-Modus verlassen"),
    "row_trigger_lockdown_now" to s("Trigger lockdown now", "Dá ìdènà sílẹ̀ nísinsìnyí", "立即触发锁定", "지금 잠금 실행", "Déclencher le verrouillage maintenant", "Activar bloqueo ahora", "Sperre jetzt auslösen"),
    "row_anti_theft_mode" to s("Anti-theft mode", "Ipò ìdènà olè", "防盗模式", "도난 방지 모드", "Mode antivol", "Modo antirrobo", "Diebstahlschutzmodus"),
    "row_last_known_location" to s("Last known location", "Ibi tí a mọ̀ kẹ́yìn", "最后已知位置", "마지막으로 알려진 위치", "Dernière position connue", "Última ubicación conocida", "Zuletzt bekannter Standort"),
    "value_just_now" to s("Just now", "Ní bí ìṣẹ́jú yìí", "刚刚", "방금", "À l'instant", "Justo ahora", "Gerade eben"),
    "value_min_ago" to s("%s min ago", "ìṣẹ́jú %s sẹ́yìn", "%s 分钟前", "%s분 전", "il y a %s min", "hace %s min", "vor %s Min"),
    "row_location_history" to s("Location history", "Ìtàn ibùdó", "位置历史", "위치 기록", "Historique de localisation", "Historial de ubicación", "Standortverlauf"),
    "value_entries" to s("%s entries", "àkọsílẹ̀ %s", "%s 条记录", "%s개 항목", "%s entrées", "%s entradas", "%s Einträge"),
    "row_track_location_history" to s("Track location history", "Tọpasẹ̀ ìtàn ibùdó", "追踪位置历史", "위치 기록 추적", "Suivre l'historique de localisation", "Rastrear historial de ubicación", "Standortverlauf verfolgen"),
    "row_os_level_lockdown" to s("OS-level lockdown", "Ìdènà ipele-OS", "系统级锁定", "OS 수준 잠금", "Verrouillage niveau OS", "Bloqueo a nivel de SO", "Sperre auf Betriebssystemebene"),
    "value_enabled" to s("ENABLED", "Ó Ń ṢISẸ́", "已启用", "활성화됨", "ACTIVÉ", "ACTIVADO", "AKTIVIERT"),
    "row_enable_os_level_lockdown" to s("Enable OS-level lockdown", "Mú ìdènà ipele-OS ṣiṣẹ́", "启用系统级锁定", "OS 수준 잠금 활성화", "Activer le verrouillage niveau OS", "Activar bloqueo a nivel de SO", "Sperre auf Betriebssystemebene aktivieren"),
    "row_full_device_wipe" to s("Full-device wipe if never recovered", "Pa gbogbo ẹrọ rẹ́ bí a kò bá rí i pa dà", "若始终未找回则完全清除设备", "끝내 복구되지 않으면 기기 전체 초기화", "Effacement complet si jamais récupéré", "Borrado total si nunca se recupera", "Vollständiges Löschen bei Nichtwiederauffindung"),
    "row_test_evacuation_backup" to s("Test evacuation backup now", "Dán ìdábùú ìjáde kúrò wò nísinsìnyí", "立即测试撤离备份", "지금 대피 백업 테스트", "Tester la sauvegarde d'évacuation maintenant", "Probar copia de seguridad de evacuación ahora", "Evakuierungs-Backup jetzt testen"),
    "value_uploading" to s("Uploading...", "Ń gbé sókè...", "上传中...", "업로드 중...", "Téléversement...", "Subiendo...", "Wird hochgeladen..."),
    "value_not_run_yet" to s("Not run yet", "Kò tíì ṣiṣẹ́ rí", "尚未运行", "아직 실행 안 됨", "Pas encore exécuté", "Aún no ejecutado", "Noch nicht ausgeführt"),
    "evac_failed_generic" to s(
        "Failed - check network/backend and try again.", "Ó kùnà - ṣàyẹ̀wò ìnàgà/backend kí o sì tún gbìyànjú.",
        "失败 - 请检查网络/后端后重试。", "실패 - 네트워크/백엔드를 확인하고 다시 시도해줘.",
        "Échec - vérifie le réseau/backend et réessaie.", "Fallido - revisa la red/backend e inténtalo de nuevo.",
        "Fehlgeschlagen - Netzwerk/Backend prüfen und erneut versuchen."
    ),

    "section_network_protection" to s("NETWORK PROTECTION", "AABÒ ÌNÀGA", "网络保护", "네트워크 보호", "PROTECTION RÉSEAU", "PROTECCIÓN DE RED", "NETZWERKSCHUTZ"),
    "row_tracker_ad_blocking" to s("Tracker & ad blocking", "Ìdènà olùtọpasẹ̀ & ìpolówó", "跟踪器与广告拦截", "트래커 및 광고 차단", "Blocage des traqueurs et publicités", "Bloqueo de rastreadores y anuncios", "Tracker- & Werbeblocker"),
    "row_router" to s("Router", "Router", "路由器", "라우터", "Routeur", "Router", "Router"),
    "row_traffic_proxy" to s("Traffic proxy", "Àárín ìrìnajò", "流量代理", "트래픽 프록시", "Proxy de trafic", "Proxy de tráfico", "Traffic-Proxy"),
    "row_vpn_client" to s("VPN client", "Oníbàárà VPN", "VPN 客户端", "VPN 클라이언트", "Client VPN", "Cliente VPN", "VPN-Client"),
    "row_blocked_today" to s("Blocked today", "Dídídí lónìí", "今日已拦截", "오늘 차단됨", "Bloqués aujourd'hui", "Bloqueados hoy", "Heute blockiert"),
    "row_update_blocklist" to s("Update blocklist", "Sọdọtun àkọsílẹ̀", "更新拦截列表", "차단 목록 업데이트", "Mettre à jour la liste", "Actualizar lista", "Blockliste aktualisieren"),
    "row_app_exceptions" to s("App exceptions", "Àwọn ìyàsímímọ́ ẹ̀rọ", "应用例外", "앱 예외", "Exceptions d'appli", "Excepciones de apps", "App-Ausnahmen"),
    "value_update_now" to s("Update now", "Sọdọtun báyìí", "立即更新", "지금 업데이트", "Mettre à jour", "Actualizar ahora", "Jetzt aktualisieren"),
    "value_updating" to s("Updating...", "Ń sọdọtun...", "更新中...", "업데이트 중...", "Mise à jour...", "Actualizando...", "Wird aktualisiert..."),
    "value_update_failed" to s("Update failed", "Sọdọtun kùnà", "更新失败", "업데이트 실패", "Échec de la mise à jour", "Actualización fallida", "Aktualisierung fehlgeschlagen"),
    "value_manage" to s("Manage", "Ṣàkóso", "管理", "관리", "Gérer", "Gestionar", "Verwalten"),
    "value_not_set" to s("Not set", "Kò tíì tò", "未设置", "설정 안 됨", "Non défini", "No configurado", "Nicht festgelegt"),
    "dialog_traffic_proxy_body" to s(
        "Point this phone's traffic (tracker/ad blocking still applies first) at a real intercepting proxy - mitmproxy or Burp Suite - running on your own laptop on the same network. This app doesn't read, block, or edit traffic content itself beyond the domain blocklist above - the proxy does that, since it's already built and trusted for exactly this. Leave blank to turn this off. Takes effect next time tracker & ad blocking is turned on.",
        "Darí ìrìnajò fóònù yìí (ìdènà olùtọpasẹ̀/ìpolówó ṣì ń ṣiṣẹ́ kọ́kọ́) sí àárín ìdènà gidi - mitmproxy tàbí Burp Suite - tí ń ṣiṣẹ́ lórí kọ̀mpútà rẹ fúnra rẹ lórí ìnàga kan náà. App yìí kò ka, dènà, tàbí ṣàtúnṣe ọ̀rọ̀ ìrìnajò fúnra rẹ̀ ju àkọsílẹ̀ ìdènà lókè lọ - àárín náà ni ó ṣe bẹ́ẹ̀, nítorí a ti kọ́ ọ tán tí a sì gbẹ́kẹ̀lé fún gan-an èyí. Fi silẹ̀ ní òfo láti pa èyí. Yóò bẹ̀rẹ̀ ní ìgbà tí ó kàn tí a bá tan ìdènà olùtọpasẹ̀/ìpolówó.",
        "将此手机的流量（跟踪器/广告拦截仍会先生效）指向运行在你自己笔记本电脑上、同一网络内的真实拦截代理——mitmproxy 或 Burp Suite。除了上面的域名黑名单外，本应用本身不会读取、拦截或编辑流量内容——那是代理的工作，因为它本来就是为此而生并受信任的工具。留空即可关闭此功能。将在下次开启跟踪器与广告拦截时生效。",
        "이 휴대폰의 트래픽을(트래커/광고 차단이 먼저 적용됨) 같은 네트워크의 네 노트북에서 실행 중인 실제 가로채기 프록시 - mitmproxy 또는 Burp Suite - 로 향하게 합니다. 이 앱은 위의 도메인 차단 목록 외에는 트래픽 내용을 직접 읽거나 차단하거나 편집하지 않습니다 - 그건 프록시가 하는 일이며, 이미 정확히 이 용도로 만들어지고 신뢰받고 있기 때문입니다. 비워두면 꺼집니다. 트래커 및 광고 차단이 다음에 켜질 때 적용됩니다.",
        "Redirige le trafic de ce téléphone (le blocage des traqueurs/publicités s'applique toujours en premier) vers un vrai proxy intercepteur - mitmproxy ou Burp Suite - fonctionnant sur ton propre ordinateur portable sur le même réseau. Cette application ne lit, ne bloque ni ne modifie le contenu du trafic elle-même au-delà de la liste de blocage de domaines ci-dessus - le proxy s'en charge, puisqu'il est déjà conçu et fiable exactement pour cela. Laisse vide pour désactiver. Prend effet la prochaine fois que le blocage des traqueurs et publicités est activé.",
        "Dirige el tráfico de este teléfono (el bloqueo de rastreadores/anuncios se sigue aplicando primero) hacia un proxy interceptor real - mitmproxy o Burp Suite - ejecutándose en tu propia laptop en la misma red. Esta app no lee, bloquea ni edita el contenido del tráfico por sí misma más allá de la lista de bloqueo de dominios de arriba - eso lo hace el proxy, ya que está construido y es confiable exactamente para esto. Déjalo en blanco para desactivarlo. Entra en vigor la próxima vez que se active el bloqueo de rastreadores y anuncios.",
        "Leitet den Datenverkehr dieses Telefons (Tracker-/Werbeblocker gilt weiterhin zuerst) an einen echten abfangenden Proxy - mitmproxy oder Burp Suite - der auf deinem eigenen Laptop im selben Netzwerk läuft. Diese App selbst liest, blockiert oder bearbeitet den Datenverkehrsinhalt nicht über die obige Domain-Sperrliste hinaus - das übernimmt der Proxy, da er genau dafür bereits gebaut und vertrauenswürdig ist. Leer lassen, um dies auszuschalten. Wird wirksam, sobald Tracker- & Werbeblocker das nächste Mal eingeschaltet wird."
    ),
    "label_ip_port" to s("ip:port", "ip:port", "ip:端口", "ip:포트", "ip:port", "ip:puerto", "ip:port"),

    "section_cedal_shared_system" to s("CEDAL SHARED SYSTEM", "ÈTÒ ÀJỌPỌ̀ CEDAL", "Cedal 共享系统", "Cedal 공유 시스템", "SYSTÈME PARTAGÉ CEDAL", "SISTEMA COMPARTIDO CEDAL", "CEDAL SHARED SYSTEM"),
    "row_cedal_shared_system" to s("Cedal Shared System", "Ètò Àjọpọ̀ Cedal", "Cedal 共享系统", "Cedal 공유 시스템", "Système partagé Cedal", "Sistema compartido Cedal", "Cedal Shared System"),
    "dialog_cedal_body" to s(
        "Cedal Shared System lets this app exchange basic version and configuration information with other apps you've installed that are also made by Cedal - verified by matching digital signature, so no other app can use this channel. It helps keep security settings consistent across Cedal apps. No personal data is shared. You can turn this off here at any time.",
        "Ètò Àjọpọ̀ Cedal ń jẹ́ kí app yìí pín àwọn ìsọfúnni ìwọ̀n àti ètò pẹ̀lú àwọn app mìíràn tí o ti fi sí tí Cedal náà ṣe pẹ̀lú - a ti fi àmì oníṣùu dájú rẹ̀, nítorí náà kò sí app mìíràn tí ó lè lo ọ̀nà yìí. Ó ń ràn án lọ́wọ́ láti pa àwọn ètò aabò mọ́ ní déédéé láàrin àwọn app Cedal. Kò sí dátà ti ara ẹni tí a pín. O lè pa èyí níbí ní ìgbàkigbà.",
        "Cedal 共享系统让本应用能与你安装的其他同为 Cedal 出品的应用交换基本版本和配置信息——通过匹配数字签名进行验证，因此没有其他应用能使用此通道。它有助于让各个 Cedal 应用之间的安全设置保持一致。不会共享任何个人数据。你可以随时在此关闭此功能。",
        "Cedal 공유 시스템은 이 앱이 설치한 다른 Cedal 제작 앱들과 기본 버전 및 설정 정보를 교환할 수 있게 합니다 - 디지털 서명 일치로 검증되므로 다른 앱은 이 채널을 사용할 수 없습니다. 이는 Cedal 앱 전반에서 보안 설정을 일관되게 유지하는 데 도움이 됩니다. 개인 데이터는 공유되지 않습니다. 언제든지 여기서 끌 수 있습니다.",
        "Le système partagé Cedal permet à cette application d'échanger des informations de base sur la version et la configuration avec d'autres applications que tu as installées et qui sont également fabriquées par Cedal - vérifié par correspondance de signature numérique, donc aucune autre application ne peut utiliser ce canal. Cela aide à maintenir des paramètres de sécurité cohérents entre les applications Cedal. Aucune donnée personnelle n'est partagée. Tu peux désactiver ceci ici à tout moment.",
        "El Sistema Compartido Cedal permite que esta app intercambie información básica de versión y configuración con otras apps que hayas instalado y que también sean de Cedal - verificado mediante coincidencia de firma digital, así que ninguna otra app puede usar este canal. Ayuda a mantener la configuración de seguridad consistente entre las apps de Cedal. No se comparte ningún dato personal. Puedes desactivar esto aquí en cualquier momento.",
        "Cedal Shared System ermöglicht es dieser App, grundlegende Versions- und Konfigurationsinformationen mit anderen installierten Apps auszutauschen, die ebenfalls von Cedal stammen - verifiziert durch Abgleich der digitalen Signatur, sodass keine andere App diesen Kanal nutzen kann. Es hilft, Sicherheitseinstellungen über Cedal-Apps hinweg konsistent zu halten. Es werden keine persönlichen Daten geteilt. Du kannst dies hier jederzeit ausschalten."
    ),

    "section_shizuku" to s("SHIZUKU", "SHIZUKU", "Shizuku", "Shizuku", "SHIZUKU", "SHIZUKU", "SHIZUKU"),
    "row_shizuku_access" to s("Shizuku access", "Ààyè Shizuku", "Shizuku 权限", "Shizuku 접근", "Accès Shizuku", "Acceso a Shizuku", "Shizuku-Zugriff"),
    "value_not_running" to s("Not running", "Kò ń ṣiṣẹ́", "未运行", "실행 중 아님", "Non actif", "No en ejecución", "Läuft nicht"),
    "value_granted" to s("Granted", "A fúnni", "已授予", "허용됨", "Accordé", "Concedido", "Erteilt"),
    "value_tap_to_grant" to s("Tap to grant", "Tẹ láti fúnni", "点击授权", "탭하여 허용", "Touchez pour accorder", "Toca para conceder", "Zum Gewähren tippen"),
    "dialog_shizuku_body" to s(
        "Shizuku lets this app run commands with real ADB/shell-level privilege - the same access `adb shell` has - without rooting the phone. It unlocks a genuine force-stop (matching Settings > App Info > Force Stop exactly), instead of the lighter \"stop background processes\" this app falls back to without it.\n\nInstall: search \"Shizuku\" on the Play Store first. If your Android version isn't listed there yet (seen on Android 16), get the official APK instead from github.com/RikkaApps/Shizuku (Releases tab) and install that file directly.\n\nSetup happens outside this app: open Shizuku, go to its Wireless debugging section and pair it with Developer Options > Wireless debugging (use \"Pair device with pairing code\" directly from Developer Options if Shizuku's own auto-search hangs), then tap Start - pairing alone does not start the service, Start is a separate step. On most phones Start needs redoing after a reboot unless the device is rooted (pairing itself is remembered). Nothing here works silently - you'll see Shizuku's own permission prompt the first time this app asks.",
        "Shizuku ń jẹ́ kí app yìí ṣiṣẹ́ àṣẹ pẹ̀lú agbára ADB/shell gidi - agbára kan náà tí `adb shell` ní - láìsí rí fóònù náà. Ó ń ṣí gbangba force-stop gidi (tí ó bára mu pẹ̀lú Settings > App Info > Force Stop gan-an), dípò \"dá àwọn ìlànà ẹ̀yìn dúró\" tí ó fúyẹ́ jù tí app yìí ń padà sí láìsí i.\n\nFífisí: wá \"Shizuku\" lórí Play Store kọ́kọ́. Tí ẹ̀yà Android rẹ kò bá tíì wà níbẹ̀ (a ti rí i lórí Android 16), gba APK aládàáṣe dípò láti github.com/RikkaApps/Shizuku (tábà Releases) kí o sì fi fáìlì yẹn sí tààrà.\n\nÌtòlẹ́sẹẹsẹ ń ṣẹlẹ̀ ní òde app yìí: ṣí Shizuku, lọ sí apá Wireless debugging rẹ̀ kí o sì so mọ́ Developer Options > Wireless debugging (lo \"Pair device with pairing code\" tààrà láti Developer Options tí ìwádìí aládàáṣe ti Shizuku fúnra rẹ̀ bá dúró jẹ́ẹ́), lẹ́yìn náà tẹ Start - so mọ́ nìkan kò bẹ̀rẹ̀ iṣẹ́ náà, Start jẹ́ ìgbésẹ̀ ọ̀tọ̀. Lórí ọ̀pọ̀lọpọ̀ fóònù, Start nílò àtúnṣe lẹ́yìn àtúnbẹ̀rẹ̀ àyàfi tí a bá ti rí ẹrọ náà (a máa rántí so mọ́ fúnra rẹ̀). Kò sí ohun tí ó ṣiṣẹ́ ní ìdákẹ́jẹ́ níbí - o óò rí ìbéèrè àṣẹ tirẹ̀ ti Shizuku ní ìgbà àkọ́kọ́ tí app yìí bá béèrè.",
        "Shizuku 让本应用能以真正的 ADB/shell 级别权限运行命令——与 `adb shell` 拥有的权限相同——而无需 root 手机。它解锁了真正的强制停止（与设置 > 应用信息 > 强制停止完全一致），而不是本应用在没有它时退回使用的更轻量的“停止后台进程”。\n\n安装：先在 Play 商店搜索“Shizuku”。如果你的 Android 版本尚未在那里列出（在 Android 16 上出现过），请改为从 github.com/RikkaApps/Shizuku（Releases 标签页）获取官方 APK 并直接安装该文件。\n\n设置在本应用之外进行：打开 Shizuku，进入其无线调试部分，并与开发者选项 > 无线调试配对（如果 Shizuku 自身的自动搜索卡住，可直接从开发者选项使用“使用配对码配对设备”），然后点击 Start——仅配对不会启动服务，Start 是单独的一步。在大多数手机上，重启后需要重新执行 Start，除非设备已 root（配对本身会被记住）。这里的一切都不会静默进行——本应用首次请求时，你会看到 Shizuku 自己的权限提示。",
        "Shizuku를 사용하면 이 앱이 실제 ADB/셸 수준 권한으로 명령을 실행할 수 있습니다 - `adb shell`이 가진 것과 동일한 접근 권한 - 휴대폰을 루팅하지 않고도요. 이는 진짜 강제 종료(설정 > 앱 정보 > 강제 종료와 정확히 일치)를 가능하게 하며, 이것 없이는 이 앱이 사용하는 더 가벼운 \"백그라운드 프로세스 중지\"로 대체됩니다.\n\n설치: 먼저 Play 스토어에서 \"Shizuku\"를 검색하세요. Android 버전이 아직 거기에 없다면(Android 16에서 발견됨) 대신 github.com/RikkaApps/Shizuku(Releases 탭)에서 공식 APK를 받아 해당 파일을 직접 설치하세요.\n\n설정은 이 앱 밖에서 이루어집니다: Shizuku를 열고 무선 디버깅 섹션으로 이동하여 개발자 옵션 > 무선 디버깅과 페어링하세요(Shizuku 자체 자동 검색이 멈추면 개발자 옵션에서 직접 \"페어링 코드로 기기 페어링\" 사용), 그런 다음 Start를 탭하세요 - 페어링만으로는 서비스가 시작되지 않으며, Start는 별도의 단계입니다. 대부분의 휴대폰에서는 기기가 루팅되지 않은 한 재부팅 후 Start를 다시 해야 합니다(페어링 자체는 기억됩니다). 여기서는 아무것도 조용히 작동하지 않습니다 - 이 앱이 처음 요청할 때 Shizuku 자체의 권한 프롬프트가 표시됩니다.",
        "Shizuku permet à cette application d'exécuter des commandes avec un vrai privilège de niveau ADB/shell - le même accès que possède `adb shell` - sans rooter le téléphone. Cela débloque un véritable arrêt forcé (correspondant exactement à Paramètres > Infos application > Forcer l'arrêt), au lieu du plus léger « arrêter les processus en arrière-plan » utilisé par défaut par cette application sans cela.\n\nInstallation : cherche d'abord « Shizuku » sur le Play Store. Si ta version d'Android n'y est pas encore listée (observé sur Android 16), récupère plutôt l'APK officiel depuis github.com/RikkaApps/Shizuku (onglet Releases) et installe ce fichier directement.\n\nLa configuration se fait en dehors de cette application : ouvre Shizuku, va dans sa section Débogage sans fil et associe-le avec Options pour les développeurs > Débogage sans fil (utilise « Associer l'appareil avec un code d'appairage » directement depuis les Options pour les développeurs si la recherche automatique de Shizuku se bloque), puis appuie sur Start - l'appairage seul ne démarre pas le service, Start est une étape séparée. Sur la plupart des téléphones, Start doit être refait après un redémarrage sauf si l'appareil est rooté (l'appairage lui-même est mémorisé). Rien ici ne fonctionne silencieusement - tu verras la propre invite d'autorisation de Shizuku la première fois que cette application la demande.",
        "Shizuku permite que esta app ejecute comandos con privilegios reales de nivel ADB/shell - el mismo acceso que tiene `adb shell` - sin rootear el teléfono. Desbloquea una detención forzada genuina (que coincide exactamente con Ajustes > Info de la app > Forzar detención), en lugar del más ligero \"detener procesos en segundo plano\" al que recurre esta app sin ello.\n\nInstalación: busca primero \"Shizuku\" en la Play Store. Si tu versión de Android aún no aparece ahí (visto en Android 16), obtén en su lugar el APK oficial desde github.com/RikkaApps/Shizuku (pestaña Releases) e instala ese archivo directamente.\n\nLa configuración ocurre fuera de esta app: abre Shizuku, ve a su sección de Depuración inalámbrica y empárejalo con Opciones de desarrollador > Depuración inalámbrica (usa \"Emparejar dispositivo con código de emparejamiento\" directamente desde Opciones de desarrollador si la búsqueda automática de Shizuku se cuelga), luego toca Start - el emparejamiento solo no inicia el servicio, Start es un paso aparte. En la mayoría de los teléfonos, Start debe rehacerse tras un reinicio a menos que el dispositivo esté rooteado (el emparejamiento en sí se recuerda). Nada aquí funciona silenciosamente - verás el propio aviso de permiso de Shizuku la primera vez que esta app lo solicite.",
        "Mit Shizuku kann diese App Befehle mit echten ADB/Shell-Rechten ausführen - demselben Zugriff, den `adb shell` hat - ohne das Telefon zu rooten. Es schaltet ein echtes erzwungenes Beenden frei (das genau Einstellungen > App-Info > Erzwungenes Beenden entspricht), anstelle des leichteren „Hintergrundprozesse stoppen“, auf das diese App ohne es zurückgreift.\n\nInstallation: Suche zuerst „Shizuku“ im Play Store. Falls deine Android-Version dort noch nicht gelistet ist (bei Android 16 beobachtet), lade stattdessen die offizielle APK von github.com/RikkaApps/Shizuku (Releases-Tab) herunter und installiere diese Datei direkt.\n\nDie Einrichtung erfolgt außerhalb dieser App: Öffne Shizuku, gehe zum Bereich Kabelloses Debugging und koppele es mit Entwickleroptionen > Kabelloses Debugging (nutze „Gerät mit Kopplungscode koppeln“ direkt aus den Entwickleroptionen, falls Shizukus eigene automatische Suche hängen bleibt), tippe dann auf Start - die Kopplung allein startet den Dienst nicht, Start ist ein separater Schritt. Auf den meisten Telefonen muss Start nach einem Neustart wiederholt werden, es sei denn, das Gerät ist gerootet (die Kopplung selbst wird gespeichert). Hier läuft nichts im Stillen ab - du wirst Shizukus eigene Berechtigungsabfrage sehen, wenn diese App das erste Mal fragt."
    ),

    "section_phone_calls" to s("PHONE CALLS", "ÌPÈ FÓÒNÙ", "电话", "전화", "APPELS TÉLÉPHONIQUES", "LLAMADAS TELEFÓNICAS", "TELEFONANRUFE"),
    "row_decline_calls_by_voice" to s("Decline calls by voice", "Kọ ìpè sílẹ̀ pẹ̀lú ohùn", "用语音拒接电话", "음성으로 전화 거절", "Refuser les appels par la voix", "Rechazar llamadas por voz", "Anrufe per Sprache ablehnen"),
    "dialog_decline_calls_body" to s(
        "Saying \"pick it up\" to answer a real cellular call already works without this. Declining/hanging up a call by voice on a real cellular call specifically needs this app to hold Android's \"Caller ID & spam\" role - there's no other way for a non-default-phone-app to reject a ringing call. This opens the real system prompt for that role; on some phones it may compete with a built-in Caller ID app for the same role. Even once granted, declining several seconds after a call starts ringing (rather than instantly) isn't guaranteed to work - that's a real Android platform limitation, not a bug in this app. VoIP call declines (WhatsApp etc.) don't need this at all - those already work independently.",
        "Wíwí \"gbé e sókè\" láti dáhùn ìpè cellular gidi ti ń ṣiṣẹ́ tán láìsí èyí. Kíkọ/dídá ìpè sílẹ̀ pẹ̀lú ohùn lórí ìpè cellular gidi nílò pàtàkì kí app yìí di ipò \"Caller ID & spam\" ti Android mú - kò sí ọ̀nà mìíràn fún app tí kìí ṣe fóònù àkọ́kọ́ láti kọ ìpè tí ń dún sílẹ̀. Èyí ń ṣí ìbéèrè ètò gidi fún ipò yẹn; lórí àwọn fóònù kan ó lè máa díje pẹ̀lú app Caller ID tí a ti kọ́ sínú fún ipò kan náà. Bí a tilẹ̀ ti fúnni tán, kíkọ ìpè sílẹ̀ ní ìṣẹ́jú àáyá díẹ̀ lẹ́yìn tí ìpè bá bẹ̀rẹ̀ ń dún (dípò lẹ́sẹ̀kẹsẹ̀) kò dájú láti ṣiṣẹ́ - ààlà gidi ti pèpéle Android ni, kìí ṣe àbùkù nínú app yìí. Kíkọ ìpè VoIP sílẹ̀ (WhatsApp àti bẹ́ẹ̀ bẹ́ẹ̀ lọ) kò nílò èyí rárá - àwọn wọ̀nyẹn ti ń ṣiṣẹ́ lóde ẹ̀yìn tán.",
        "说“接听”来接听真实的蜂窝电话已经无需此项即可工作。用语音拒接/挂断真实的蜂窝电话，具体需要本应用持有 Android 的“来电显示与垃圾电话”角色——非默认电话应用没有其他方式可以拒接正在响铃的来电。这会打开该角色的真实系统提示；在某些手机上，它可能会与内置的来电显示应用争夺同一角色。即使已获授权，在电话开始响铃几秒钟后（而非立即）拒接也不能保证成功——这是 Android 平台的真实限制，不是本应用的缺陷。VoIP 通话拒接（WhatsApp 等）完全不需要此项——那些已经能独立工作。",
        "\"받아\"라고 말해서 실제 셀룰러 통화를 받는 것은 이것 없이도 이미 작동합니다. 실제 셀룰러 통화를 음성으로 거절/끊으려면 이 앱이 Android의 \"발신자 ID 및 스팸\" 역할을 가지고 있어야 합니다 - 기본 전화 앱이 아닌 앱이 울리는 전화를 거절할 다른 방법은 없습니다. 이는 해당 역할에 대한 실제 시스템 프롬프트를 엽니다. 일부 휴대폰에서는 동일한 역할을 두고 내장 발신자 ID 앱과 경쟁할 수 있습니다. 권한이 부여되어도, 전화가 울리기 시작한 지 몇 초 후에(즉시가 아니라) 거절하는 것은 작동이 보장되지 않습니다 - 이는 이 앱의 버그가 아니라 실제 Android 플랫폼의 제한입니다. VoIP 통화 거절(WhatsApp 등)은 이것이 전혀 필요 없습니다 - 그것들은 이미 독립적으로 작동합니다.",
        "Dire « décroche » pour répondre à un vrai appel cellulaire fonctionne déjà sans cela. Refuser/raccrocher un appel par la voix sur un vrai appel cellulaire nécessite spécifiquement que cette application détienne le rôle Android « ID de l'appelant et spam » - il n'y a pas d'autre moyen pour une application non-téléphone-par-défaut de rejeter un appel qui sonne. Cela ouvre la vraie invite système pour ce rôle ; sur certains téléphones, elle peut entrer en concurrence avec une application ID de l'appelant intégrée pour le même rôle. Même une fois accordé, refuser plusieurs secondes après le début de la sonnerie (plutôt qu'instantanément) n'est pas garanti de fonctionner - c'est une vraie limitation de la plateforme Android, pas un bug de cette application. Les refus d'appels VoIP (WhatsApp, etc.) n'en ont pas du tout besoin - ceux-ci fonctionnent déjà indépendamment.",
        "Decir \"contesta\" para responder una llamada celular real ya funciona sin esto. Rechazar/colgar una llamada por voz en una llamada celular real necesita específicamente que esta app tenga el rol de Android \"ID de llamadas y spam\" - no hay otra forma de que una app que no es la de teléfono predeterminada rechace una llamada que está sonando. Esto abre el aviso real del sistema para ese rol; en algunos teléfonos puede competir con una app de ID de llamadas integrada por el mismo rol. Incluso una vez concedido, rechazar varios segundos después de que la llamada empieza a sonar (en lugar de instantáneamente) no está garantizado que funcione - esa es una limitación real de la plataforma Android, no un error de esta app. Los rechazos de llamadas VoIP (WhatsApp, etc.) no necesitan esto en absoluto - esos ya funcionan de forma independiente.",
        "„Nimm ab“ zu sagen, um einen echten Mobilfunkanruf anzunehmen, funktioniert bereits ohne dies. Einen echten Mobilfunkanruf per Sprache abzulehnen/aufzulegen erfordert speziell, dass diese App Androids Rolle „Anrufer-ID & Spam“ innehat - es gibt keine andere Möglichkeit für eine Nicht-Standard-Telefon-App, einen klingelnden Anruf abzulehnen. Dies öffnet die echte Systemabfrage für diese Rolle; auf manchen Telefonen konkurriert sie möglicherweise mit einer eingebauten Anrufer-ID-App um dieselbe Rolle. Selbst nach Erteilung ist ein Ablehnen mehrere Sekunden nach Klingelbeginn (statt sofort) nicht garantiert erfolgreich - das ist eine echte Android-Plattformeinschränkung, kein Fehler dieser App. VoIP-Anrufablehnungen (WhatsApp usw.) benötigen dies überhaupt nicht - die funktionieren bereits unabhängig."
    ),

    "section_voice_id" to s("VOICE ID", "ÌDÁNIMỌ̀ OHÙN", "语音身份", "음성 ID", "IDENTIFICATION VOCALE", "ID DE VOZ", "SPRACH-ID"),
    "voice_id_drifting_warning" to s(
        "Voice ID matches have been weaker lately - consider adding a fresh sample below.",
        "Àwọn ìbáramu Ìdánimọ̀ Ohùn ti fúyẹ́ jẹ́ẹ́ láìpẹ́ - gbé yẹ̀wò láti fi àpẹẹrẹ tuntun kún un ní ìsàlẹ̀.",
        "最近语音身份匹配的强度有所减弱 - 请考虑在下方添加一个新样本。",
        "최근 음성 ID 일치도가 약해졌습니다 - 아래에서 새 샘플을 추가하는 것을 고려해줘.",
        "Les correspondances d'identification vocale ont été plus faibles ces derniers temps - envisage d'ajouter un nouvel échantillon ci-dessous.",
        "Las coincidencias de ID de voz han sido más débiles últimamente - considera añadir una muestra nueva abajo.",
        "Die Sprach-ID-Übereinstimmungen waren zuletzt schwächer - erwäge, unten eine neue Probe hinzuzufügen."
    ),
    "row_add_voice_samples" to s("Add voice samples", "Fi àpẹẹrẹ ohùn kún un", "添加语音样本", "음성 샘플 추가", "Ajouter des échantillons vocaux", "Añadir muestras de voz", "Sprachproben hinzufügen"),
    "row_enroll_voice" to s("Enroll voice", "Forúkọsílẹ̀ ohùn", "注册语音", "음성 등록", "Enregistrer la voix", "Registrar voz", "Stimme registrieren"),
    "value_working" to s("Working...", "Ń ṣiṣẹ́...", "处理中...", "작동 중...", "En cours...", "Trabajando...", "Wird bearbeitet..."),
    "value_enrolled" to s("Enrolled", "A ti forúkọsílẹ̀", "已注册", "등록됨", "Enregistré", "Registrado", "Registriert"),
    "value_not_enrolled" to s("Not enrolled", "A kò tíì forúkọsílẹ̀", "未注册", "등록 안 됨", "Non enregistré", "No registrado", "Nicht registriert"),
    "row_test_voice_match" to s("Test voice match", "Dán ìbáramu ohùn wò", "测试语音匹配", "음성 일치 테스트", "Tester la correspondance vocale", "Probar coincidencia de voz", "Sprachabgleich testen"),
    "row_reset_enrollment" to s("Reset enrollment", "Padà bẹ̀rẹ̀ ìforúkọsílẹ̀", "重置注册", "등록 재설정", "Réinitialiser l'enregistrement", "Restablecer registro", "Registrierung zurücksetzen"),
    "voice_couldnt_record" to s(
        "Couldn't record - check microphone permission.", "A kò lè gbà - ṣàyẹ̀wò ìyọ̀ǹda máìkì.",
        "无法录制 - 请检查麦克风权限。", "녹음할 수 없습니다 - 마이크 권한을 확인해줘.",
        "Impossible d'enregistrer - vérifie l'autorisation du micro.", "No se pudo grabar - revisa el permiso del micrófono.",
        "Aufnahme nicht möglich - Mikrofonberechtigung prüfen."
    ),
    "voice_enrollment_cleared" to s(
        "Enrollment cleared.", "A ti pa ìforúkọsílẹ̀ rẹ́.", "注册已清除。", "등록이 지워졌습니다.",
        "Enregistrement effacé.", "Registro borrado.", "Registrierung gelöscht."
    ),
    "voice_didnt_catch_that" to s(
        "Didn't catch that clearly - try again.\n", "Mi ò gbọ́ ọ dáadáa - tún gbìyànjú.\n", "没听清楚 - 请重试。\n",
        "잘 듣지 못했어 - 다시 시도해줘.\n", "Je n'ai pas bien compris - réessaie.\n", "No lo entendí bien - inténtalo de nuevo.\n",
        "Habe das nicht klar verstanden - versuche es erneut.\n"
    ),
    "voice_take_prompt" to s(
        "%s (take %s of 3, %ss) - say:\n\"%s\"", "%s (ìgbà %s nínú 3, ìṣẹ́jú%s) - sọ:\n\"%s\"",
        "%s（第 %s/3 次，%s 秒）- 请说：\n“%s”", "%s (3회 중 %s번째, %s초) - 말해줘:\n\"%s\"",
        "%s (prise %s sur 3, %ss) - dis :\n« %s »", "%s (toma %s de 3, %ss) - di:\n\"%s\"",
        "%s (Aufnahme %s von 3, %ss) - sag:\n„%s“"
    ),
    "voice_enrolled_all_styles" to s(
        "Enrolled - all 5 styles.", "A ti forúkọsílẹ̀ - onírúurú márùn-ún gbogbo rẹ̀.", "已注册 - 全部 5 种风格。",
        "등록됨 - 5가지 스타일 모두.", "Enregistré - les 5 styles.", "Registrado - los 5 estilos.",
        "Registriert - alle 5 Stile."
    ),
    "voice_say_seconds" to s(
        "Say (%ss): \"%s\"", "Sọ (ìṣẹ́jú%s): \"%s\"", "请说（%s 秒）：“%s”", "말해줘 (%s초): \"%s\"",
        "Dis (%ss) : « %s »", "Di (%ss): \"%s\"", "Sag (%ss): „%s“"
    ),
    "voice_no_enrollment_for" to s(
        "No enrollment on file for %s.", "Kò sí ìforúkọsílẹ̀ fún %s.", "没有 %s 的注册记录。",
        "%s에 대한 등록 기록이 없습니다.", "Aucun enregistrement en dossier pour %s.", "No hay registro guardado para %s.",
        "Keine Registrierung für %s vorhanden."
    ),
    "voice_similarity_result" to s(
        "%s: similarity %s (threshold %s) - %s", "%s: ìbáramu %s (ààlà %s) - %s", "%s：相似度 %s（阈值 %s）- %s",
        "%s: 유사도 %s (임계값 %s) - %s", "%s : similarité %s (seuil %s) - %s", "%s: similitud %s (umbral %s) - %s",
        "%s: Ähnlichkeit %s (Schwelle %s) - %s"
    ),
    "voice_match" to s("MATCH", "Ó BÁRA MU", "匹配", "일치", "CORRESPONDANCE", "COINCIDENCIA", "ÜBEREINSTIMMUNG"),
    "voice_no_match" to s("NO MATCH", "KÒ BÁRA MU", "不匹配", "불일치", "AUCUNE CORRESPONDANCE", "SIN COINCIDENCIA", "KEINE ÜBEREINSTIMMUNG"),
    "dialog_voice_id_title" to s("Voice ID", "Ìdánimọ̀ Ohùn", "语音身份", "음성 ID", "Identification vocale", "ID de voz", "Sprach-ID"),
    "dialog_voice_id_body" to s(
        "Offline speaker verification (ECAPA-TDNN) - runs entirely on this device, nothing is sent anywhere. Enrollment records five different styles (a long phrase, a medium phrase, a short word, reciting letters, reciting digits) since a single word and a full sentence sound different enough that one reference doesn't compare fairly to both. Each style has its own match threshold - shorter ones are more lenient since there's less audio to work with.\n\nThe lock screen's voice option just records and compares against the \"short word\" style directly.\n\n\"Add voice samples\" doesn't replace what's already enrolled - it adds to it. Your voice isn't one fixed thing (tired, sick, or just talking differently all sound a bit different), so if you keep getting rejected, come back here and add a fresh sample in whatever state your voice is in right now - verification checks against every sample you've added and accepts the closest match, not an average of all of them.",
        "Ìjẹ́rìí olùsọ̀rọ̀ lóde ẹ̀rọ (ECAPA-TDNN) - ó ń ṣiṣẹ́ pátápátá lórí ẹrọ yìí, kò sí ohun tí a fi ránṣẹ́ síbìkíbi. Ìforúkọsílẹ̀ ń gbà onírúurú márùn-ún (gbólóhùn gígùn, gbólóhùn àárín, ọ̀rọ̀ kúkúrú, kíka lẹ́tà, kíka nọ́mbà) nítorí ọ̀rọ̀ kan àti gbólóhùn kíkún dún yàtọ̀ tó bẹ́ẹ̀ tí ìtọ́kasí kan kò fi lè fi ìdí méjèèjì múlẹ̀ tọ́nà. Onírúurú kọ̀ọ̀kan ní ààlà ìbáramu tirẹ̀ - àwọn tí ó kúrú jẹ́ onínúure jù nítorí kéré ohùn tí ó wà láti fi ṣiṣẹ́.\n\nÀṣàyàn ohùn ti ìbojú ìdènà kàn ń gbà tí ó sì fi wéra pẹ̀lú onírúurú \"ọ̀rọ̀ kúkúrú\" tààrà.\n\n\"Fi àpẹẹrẹ ohùn kún un\" kò rọ́pò ohun tí a ti forúkọsílẹ̀ tán - ó ń fi kún un ni. Ohùn rẹ kìí ṣe ohun kan tí kò yí padà (tí o bá rẹ̀, tí o ṣàìsàn, tàbí tí o kàn ń sọ̀rọ̀ yàtọ̀ gbogbo rẹ̀ dún yàtọ̀ díẹ̀), nítorí náà tí wọ́n bá ń kọ̀ ọ́ sílẹ̀ nígbà gbogbo, padà wá síbí kí o sì fi àpẹẹrẹ tuntun kún un ní ipò tí ohùn rẹ wà nísinsìnyí - ìjẹ́rìí ń ṣàyẹ̀wò lòdì sí gbogbo àpẹẹrẹ tí o ti fi kún un, ó sì ń gba èyí tí ó súnmọ́ jùlọ, kìí ṣe ìwọ̀nba gbogbo wọn.",
        "离线说话人验证（ECAPA-TDNN）——完全在本设备上运行，不会向任何地方发送任何内容。注册会录制五种不同的风格（一段长句、一段中等长度短语、一个短词、朗读字母、朗读数字），因为单个词语和完整句子的声音差异较大，仅用一种参考无法公平地对两者进行比较。每种风格都有自己的匹配阈值——较短的风格更宽松，因为可用于分析的音频较少。\n\n锁屏的语音选项只会直接录制并与“短词”风格进行比对。\n\n“添加语音样本”不会替换已注册的内容——而是在其基础上增加。你的声音并非一成不变（疲惫、生病或只是说话方式不同，听起来都会略有差异），所以如果你一直被拒绝，可以回到这里，用你此刻声音的实际状态添加一个新样本——验证会对照你添加过的每一个样本，接受最接近的匹配，而不是所有样本的平均值。",
        "오프라인 화자 검증(ECAPA-TDNN) - 이 기기에서 완전히 실행되며 아무것도 어디로도 전송되지 않습니다. 등록은 다섯 가지 다른 스타일(긴 구절, 중간 구절, 짧은 단어, 알파벳 낭독, 숫자 낭독)을 기록합니다. 단어 하나와 완전한 문장은 소리가 충분히 다르기 때문에 하나의 기준으로는 둘 다 공정하게 비교할 수 없기 때문입니다. 각 스타일에는 자체 일치 임계값이 있습니다 - 더 짧은 것은 사용할 오디오가 적기 때문에 더 관대합니다.\n\n잠금 화면의 음성 옵션은 \"짧은 단어\" 스타일과 직접 녹음하고 비교할 뿐입니다.\n\n\"음성 샘플 추가\"는 이미 등록된 것을 대체하지 않습니다 - 추가할 뿐입니다. 네 목소리는 고정된 것이 아니므로(피곤하거나, 아프거나, 그냥 다르게 말할 때 모두 조금씩 다르게 들립니다) 계속 거부당한다면 여기로 돌아와서 지금 네 목소리 상태 그대로 새 샘플을 추가하세요 - 검증은 네가 추가한 모든 샘플과 대조하며 가장 가까운 일치를 받아들입니다, 전체의 평균이 아니라요.",
        "Vérification du locuteur hors ligne (ECAPA-TDNN) - s'exécute entièrement sur cet appareil, rien n'est envoyé où que ce soit. L'enregistrement capture cinq styles différents (une longue phrase, une phrase moyenne, un mot court, la récitation de lettres, la récitation de chiffres) car un seul mot et une phrase complète sonnent suffisamment différemment pour qu'une seule référence ne compare pas équitablement les deux. Chaque style a son propre seuil de correspondance - les plus courts sont plus indulgents car il y a moins d'audio à exploiter.\n\nL'option vocale de l'écran de verrouillage se contente d'enregistrer et de comparer directement au style « mot court ».\n\n« Ajouter des échantillons vocaux » ne remplace pas ce qui est déjà enregistré - cela s'y ajoute. Ta voix n'est pas une chose fixe (fatigué, malade, ou parlant simplement différemment sonnent tous un peu différemment), donc si tu continues à être rejeté, reviens ici et ajoute un nouvel échantillon dans l'état actuel de ta voix - la vérification compare avec chaque échantillon que tu as ajouté et accepte la correspondance la plus proche, pas une moyenne de toutes.",
        "Verificación de locutor sin conexión (ECAPA-TDNN) - se ejecuta completamente en este dispositivo, no se envía nada a ningún lado. El registro graba cinco estilos diferentes (una frase larga, una frase media, una palabra corta, recitar letras, recitar dígitos) ya que una sola palabra y una oración completa suenan lo suficientemente distintas como para que una referencia no compare de forma justa ambas. Cada estilo tiene su propio umbral de coincidencia - los más cortos son más indulgentes ya que hay menos audio con el que trabajar.\n\nLa opción de voz de la pantalla de bloqueo simplemente graba y compara directamente con el estilo de \"palabra corta\".\n\n\"Añadir muestras de voz\" no reemplaza lo ya registrado - se añade a ello. Tu voz no es algo fijo (cansado, enfermo, o simplemente hablando distinto, todo suena un poco diferente), así que si sigues siendo rechazado, vuelve aquí y añade una muestra nueva en el estado en que esté tu voz ahora mismo - la verificación compara contra cada muestra que has añadido y acepta la coincidencia más cercana, no un promedio de todas.",
        "Offline-Sprechererkennung (ECAPA-TDNN) - läuft vollständig auf diesem Gerät, nichts wird irgendwohin gesendet. Die Registrierung zeichnet fünf verschiedene Stile auf (einen langen Satz, einen mittleren Satz, ein kurzes Wort, das Aufsagen von Buchstaben, das Aufsagen von Ziffern), da ein einzelnes Wort und ein ganzer Satz sich unterschiedlich genug anhören, sodass eine Referenz nicht beide fair vergleicht. Jeder Stil hat seinen eigenen Übereinstimmungsschwellenwert - kürzere sind nachsichtiger, da weniger Audio zur Verfügung steht.\n\nDie Sprachoption des Sperrbildschirms nimmt einfach auf und vergleicht direkt mit dem Stil „kurzes Wort“.\n\n„Sprachproben hinzufügen“ ersetzt nicht, was bereits registriert ist - es ergänzt es. Deine Stimme ist keine feste Sache (müde, krank oder einfach anders sprechend klingt alles etwas anders), also wenn du weiterhin abgelehnt wirst, komm hierher zurück und füge eine frische Probe hinzu, in welchem Zustand sich deine Stimme gerade befindet - die Verifizierung prüft gegen jede hinzugefügte Probe und akzeptiert die nächstliegende Übereinstimmung, keinen Durchschnitt aller."
    ),

    "section_kiosk_lock_screen" to s("KIOSK & LOCK SCREEN", "KIOSK ÀTI ÌBOJÚ ÌDÈNÀ", "亭模式与锁屏", "키오스크 및 잠금 화면", "KIOSQUE ET ÉCRAN DE VERROUILLAGE", "QUIOSCO Y PANTALLA DE BLOQUEO", "KIOSK & SPERRBILDSCHIRM"),
    "row_kiosk_mode" to s("Kiosk mode", "Ipò Kiosk", "亭模式", "키오스크 모드", "Mode kiosque", "Modo quiosco", "Kiosk-Modus"),
    "row_gesture_control" to s("Gesture control", "Gesture control", "Gesture control", "Gesture control", "Gesture control", "Gesture control", "Gesture control"),
    "row_configure_gestures" to s("Configure gestures", "Configure gestures", "Configure gestures", "Configure gestures", "Configure gestures", "Configure gestures", "Configure gestures"),
    "row_gesture_control_untrained_hint" to s("Train at least one gesture first", "Train at least one gesture first", "Train at least one gesture first", "Train at least one gesture first", "Train at least one gesture first", "Train at least one gesture first", "Train at least one gesture first"),
    "row_pinch_gesture" to s("Pinch to zoom", "Pinch to zoom", "Pinch to zoom", "Pinch to zoom", "Pinch to zoom", "Pinch to zoom", "Pinch to zoom"),
    "dialog_gesture_control_body" to s(
        "Watches the front camera for hand swipes (up/down/left/right) and a pinch, dispatching them as real swipe/zoom gestures - no touching the screen. Uses on-device hand tracking only, nothing is sent anywhere. This phone has no depth/radar sensor, so it's camera-based gesture recognition, not millimeter-precision 3D tracking. Runs the camera continuously while on, which costs real battery - turn it off when you're not using it.",
        "Watches the front camera for hand swipes (up/down/left/right) and a pinch, dispatching them as real swipe/zoom gestures - no touching the screen. Uses on-device hand tracking only, nothing is sent anywhere. This phone has no depth/radar sensor, so it's camera-based gesture recognition, not millimeter-precision 3D tracking. Runs the camera continuously while on, which costs real battery - turn it off when you're not using it.",
        "Watches the front camera for hand swipes (up/down/left/right) and a pinch, dispatching them as real swipe/zoom gestures - no touching the screen. Uses on-device hand tracking only, nothing is sent anywhere. This phone has no depth/radar sensor, so it's camera-based gesture recognition, not millimeter-precision 3D tracking. Runs the camera continuously while on, which costs real battery - turn it off when you're not using it.",
        "Watches the front camera for hand swipes (up/down/left/right) and a pinch, dispatching them as real swipe/zoom gestures - no touching the screen. Uses on-device hand tracking only, nothing is sent anywhere. This phone has no depth/radar sensor, so it's camera-based gesture recognition, not millimeter-precision 3D tracking. Runs the camera continuously while on, which costs real battery - turn it off when you're not using it.",
        "Watches the front camera for hand swipes (up/down/left/right) and a pinch, dispatching them as real swipe/zoom gestures - no touching the screen. Uses on-device hand tracking only, nothing is sent anywhere. This phone has no depth/radar sensor, so it's camera-based gesture recognition, not millimeter-precision 3D tracking. Runs the camera continuously while on, which costs real battery - turn it off when you're not using it.",
        "Watches the front camera for hand swipes (up/down/left/right) and a pinch, dispatching them as real swipe/zoom gestures - no touching the screen. Uses on-device hand tracking only, nothing is sent anywhere. This phone has no depth/radar sensor, so it's camera-based gesture recognition, not millimeter-precision 3D tracking. Runs the camera continuously while on, which costs real battery - turn it off when you're not using it.",
        "Watches the front camera for hand swipes (up/down/left/right) and a pinch, dispatching them as real swipe/zoom gestures - no touching the screen. Uses on-device hand tracking only, nothing is sent anywhere. This phone has no depth/radar sensor, so it's camera-based gesture recognition, not millimeter-precision 3D tracking. Runs the camera continuously while on, which costs real battery - turn it off when you're not using it."
    ),
    "row_face_gestures" to s("Face gestures", "Face gestures", "Face gestures", "Face gestures", "Face gestures", "Face gestures", "Face gestures"),
    "dialog_face_gestures_body" to s(
        "Watches the front camera for a nod, head shake, smile, or open mouth - fixed, non-trainable gestures. Nod answers a pending confirmation \"yes\", shake answers \"no\" (only while Xenos is already open with something pending - never forces the app open), smile hides the screen and opening your mouth un-hides it (two separate one-way gestures, not one toggle). Approving anything still requires your fingerprint, same as tapping Yes - a nod never bypasses that. Uses on-device face tracking only, nothing is sent anywhere. Shares the same camera and battery cost as hand gesture control.",
        "Watches the front camera for a nod, head shake, smile, or open mouth - fixed, non-trainable gestures. Nod answers a pending confirmation \"yes\", shake answers \"no\" (only while Xenos is already open with something pending - never forces the app open), smile hides the screen and opening your mouth un-hides it (two separate one-way gestures, not one toggle). Approving anything still requires your fingerprint, same as tapping Yes - a nod never bypasses that. Uses on-device face tracking only, nothing is sent anywhere. Shares the same camera and battery cost as hand gesture control.",
        "Watches the front camera for a nod, head shake, smile, or open mouth - fixed, non-trainable gestures. Nod answers a pending confirmation \"yes\", shake answers \"no\" (only while Xenos is already open with something pending - never forces the app open), smile hides the screen and opening your mouth un-hides it (two separate one-way gestures, not one toggle). Approving anything still requires your fingerprint, same as tapping Yes - a nod never bypasses that. Uses on-device face tracking only, nothing is sent anywhere. Shares the same camera and battery cost as hand gesture control.",
        "Watches the front camera for a nod, head shake, smile, or open mouth - fixed, non-trainable gestures. Nod answers a pending confirmation \"yes\", shake answers \"no\" (only while Xenos is already open with something pending - never forces the app open), smile hides the screen and opening your mouth un-hides it (two separate one-way gestures, not one toggle). Approving anything still requires your fingerprint, same as tapping Yes - a nod never bypasses that. Uses on-device face tracking only, nothing is sent anywhere. Shares the same camera and battery cost as hand gesture control.",
        "Watches the front camera for a nod, head shake, smile, or open mouth - fixed, non-trainable gestures. Nod answers a pending confirmation \"yes\", shake answers \"no\" (only while Xenos is already open with something pending - never forces the app open), smile hides the screen and opening your mouth un-hides it (two separate one-way gestures, not one toggle). Approving anything still requires your fingerprint, same as tapping Yes - a nod never bypasses that. Uses on-device face tracking only, nothing is sent anywhere. Shares the same camera and battery cost as hand gesture control.",
        "Watches the front camera for a nod, head shake, smile, or open mouth - fixed, non-trainable gestures. Nod answers a pending confirmation \"yes\", shake answers \"no\" (only while Xenos is already open with something pending - never forces the app open), smile hides the screen and opening your mouth un-hides it (two separate one-way gestures, not one toggle). Approving anything still requires your fingerprint, same as tapping Yes - a nod never bypasses that. Uses on-device face tracking only, nothing is sent anywhere. Shares the same camera and battery cost as hand gesture control.",
        "Watches the front camera for a nod, head shake, smile, or open mouth - fixed, non-trainable gestures. Nod answers a pending confirmation \"yes\", shake answers \"no\" (only while Xenos is already open with something pending - never forces the app open), smile hides the screen and opening your mouth un-hides it (two separate one-way gestures, not one toggle). Approving anything still requires your fingerprint, same as tapping Yes - a nod never bypasses that. Uses on-device face tracking only, nothing is sent anywhere. Shares the same camera and battery cost as hand gesture control."
    ),
    "row_change_lock_screen" to s("Change Android lock screen", "Yí ìbojú ìdènà Android padà", "更改 Android 锁屏", "Android 잠금 화면 변경", "Changer l'écran de verrouillage Android", "Cambiar la pantalla de bloqueo de Android", "Android-Sperrbildschirm ändern"),
    "dialog_kiosk_body" to s(
        "Pins this app in full-screen using Android's own Screen Pinning feature - no other app, the notification shade, or Recents can be reached until it's unpinned with your app PIN. The first time you turn this on, Android will show its own one-time confirmation for pinning.",
        "Ó ń fi app yìí mọ́ ní ojú kíkún pẹ̀lú ẹ̀yà Screen Pinning ti Android fúnra rẹ̀ - kò sí app mìíràn, aṣọ ìkìlọ̀, tàbí Recents tí a lè dé bá títí a óò fi tú u sílẹ̀ pẹ̀lú PIN app rẹ. Ní ìgbà àkọ́kọ́ tí o bá tan èyí, Android yóò fi ìjẹ́rìí ìgbà-kan tirẹ̀ hàn fún fífi mọ́.",
        "使用 Android 自身的屏幕固定功能将本应用固定为全屏——在用你的应用 PIN 取消固定之前，无法访问任何其他应用、通知栏或最近任务。首次开启此功能时，Android 会显示它自己的一次性固定确认提示。",
        "Android 자체의 화면 고정 기능을 사용하여 이 앱을 전체 화면으로 고정합니다 - 앱 PIN으로 고정을 해제하기 전까지는 다른 앱, 알림창, 최근 앱에 접근할 수 없습니다. 이 기능을 처음 켤 때 Android가 자체적인 일회성 고정 확인 창을 표시합니다.",
        "Épingle cette application en plein écran à l'aide de la fonction Épinglage d'écran propre à Android - aucune autre application, le volet de notifications ou les Récents ne sont accessibles tant qu'elle n'est pas désépinglée avec le code PIN de l'application. La première fois que tu actives cela, Android affichera sa propre confirmation ponctuelle pour l'épinglage.",
        "Fija esta app en pantalla completa usando la propia función de Fijado de pantalla de Android - no se puede acceder a ninguna otra app, la barra de notificaciones ni Recientes hasta que se desfije con el PIN de la app. La primera vez que actives esto, Android mostrará su propia confirmación única para fijar.",
        "Fixiert diese App im Vollbildmodus mit Androids eigener Bildschirmfixierung - keine andere App, die Benachrichtigungsleiste oder Zuletzt verwendet sind erreichbar, bis sie mit deiner App-PIN gelöst wird. Beim ersten Aktivieren zeigt Android seine eigene einmalige Bestätigung für die Fixierung."
    ),
    "dialog_lock_screen_body" to s(
        "Opens Android's own Security settings, where you can change your lock method to \"Swipe\"/\"None\" if you want this app's PIN to be the only thing gating the phone. Doing that removes Android's own secure PIN prompt, but it also weakens the phone's underlying disk encryption, since that PIN is part of what protects your data at rest - not just a screen you see. This app can't make that change for you; only you can, inside Android's own settings.",
        "Ó ń ṣí àwọn ètò Aabò ti Android fúnra rẹ̀, níbi tí o ti lè yí ọ̀nà ìdènà rẹ padà sí \"Swipe\"/\"None\" bí o bá fẹ́ kí PIN app yìí jẹ́ ohun kan ṣoṣo tí ó ń ṣọ́ fóònù náà. Ṣíṣe bẹ́ẹ̀ ń mú ìbéèrè PIN aláàbò ti Android fúnra rẹ̀ kúrò, ṣùgbọ́n ó tún ń dín agbára ìdábùú disk ìsàlẹ̀ fóònù náà kù, nítorí PIN yẹn jẹ́ apá kan ohun tí ó ń dáàbò bo dátà rẹ nígbà tí kò ṣiṣẹ́ - kìí ṣe ìbojú kan ṣoṣo tí o rí. App yìí kò lè ṣe ìyípadà yẹn fún ọ; ìwọ nìkan ni ó lè, ní inú àwọn ètò Android fúnra rẹ̀.",
        "打开 Android 自身的安全设置，你可以在其中将锁定方式更改为“滑动”/“无”，如果你希望本应用的 PIN 成为守卫手机的唯一屏障。这样做会移除 Android 自身安全的 PIN 提示，但也会削弱手机底层的磁盘加密，因为该 PIN 是保护静态数据的一部分——不仅仅是你看到的一个屏幕。本应用无法为你做出该更改；只有你自己才能在 Android 自身的设置中完成。",
        "Android 자체의 보안 설정을 여는데, 여기서 이 앱의 PIN이 휴대폰을 지키는 유일한 수단이 되길 원한다면 잠금 방식을 \"스와이프\"/\"없음\"으로 변경할 수 있습니다. 그렇게 하면 Android 자체의 보안 PIN 프롬프트가 제거되지만, 해당 PIN이 화면뿐 아니라 저장된 데이터를 보호하는 요소의 일부이기 때문에 휴대폰의 기본 디스크 암호화도 약해집니다. 이 앱은 그 변경을 대신할 수 없습니다 - 오직 너만이 Android 자체 설정 안에서 할 수 있습니다.",
        "Ouvre les propres paramètres de sécurité d'Android, où tu peux changer ta méthode de verrouillage en « Glisser »/« Aucun » si tu veux que le code PIN de cette application soit la seule chose protégeant le téléphone. Faire cela supprime la propre invite PIN sécurisée d'Android, mais cela affaiblit aussi le chiffrement de disque sous-jacent du téléphone, puisque ce PIN fait partie de ce qui protège tes données au repos - pas seulement un écran que tu vois. Cette application ne peut pas faire ce changement à ta place ; toi seul le peux, dans les propres paramètres d'Android.",
        "Abre los propios ajustes de Seguridad de Android, donde puedes cambiar tu método de bloqueo a \"Deslizar\"/\"Ninguno\" si quieres que el PIN de esta app sea lo único que proteja el teléfono. Hacer eso elimina el propio aviso de PIN seguro de Android, pero también debilita el cifrado de disco subyacente del teléfono, ya que ese PIN es parte de lo que protege tus datos en reposo - no solo una pantalla que ves. Esta app no puede hacer ese cambio por ti; solo tú puedes, dentro de los propios ajustes de Android.",
        "Öffnet Androids eigene Sicherheitseinstellungen, wo du deine Sperrmethode auf „Wischen“/„Keine“ ändern kannst, wenn du möchtest, dass die PIN dieser App das einzige ist, was das Telefon schützt. Dies entfernt Androids eigene sichere PIN-Abfrage, schwächt aber auch die zugrunde liegende Festplattenverschlüsselung des Telefons, da diese PIN Teil dessen ist, was deine ruhenden Daten schützt - nicht nur ein Bildschirm, den du siehst. Diese App kann diese Änderung nicht für dich vornehmen; nur du kannst das, innerhalb von Androids eigenen Einstellungen."
    ),

    "section_integrity_tamper" to s("INTEGRITY & TAMPER DETECTION", "ÌDÚRÓ ṢÙGBỌ́N ÀTI ÌDÁNIMỌ̀ ÌFỌ̀WỌ́KAN", "完整性与篡改检测", "무결성 및 변조 감지", "INTÉGRITÉ ET DÉTECTION D'ALTÉRATION", "INTEGRIDAD Y DETECCIÓN DE MANIPULACIÓN", "INTEGRITÄT & MANIPULATIONSERKENNUNG"),
    "row_app_integrity" to s("App integrity", "Ìdúró ṣùgbọ́n app", "应用完整性", "앱 무결성", "Intégrité de l'application", "Integridad de la app", "App-Integrität"),
    "value_verified" to s("Verified", "A ti jẹ́rìí", "已验证", "확인됨", "Vérifié", "Verificado", "Verifiziert"),
    "value_modified" to s("MODIFIED - not the genuine build", "A ti ṣàtúnṣe - kìí ṣe kíkọ́ gidi", "已被修改 - 非正版构建", "수정됨 - 정품 빌드 아님", "MODIFIÉ - pas la vraie version", "MODIFICADO - no es la versión genuina", "GEÄNDERT - kein echter Build"),
    "row_root_magisk" to s("Root/Magisk", "Root/Magisk", "Root/Magisk", "루트/Magisk", "Root/Magisk", "Root/Magisk", "Root/Magisk"),
    "value_detected" to s("Detected", "A rí i", "已检测到", "감지됨", "Détecté", "Detectado", "Erkannt"),
    "value_not_detected" to s("Not detected", "A kò rí i", "未检测到", "감지 안 됨", "Non détecté", "No detectado", "Nicht erkannt"),
    "row_selinux" to s("SELinux", "SELinux", "SELinux", "SELinux", "SELinux", "SELinux", "SELinux"),
    "value_enforcing" to s("Enforcing", "Ó ń fipá mú", "强制模式", "Enforcing(강제)", "Applicatoire", "Forzoso", "Enforcing"),
    "value_permissive" to s("Permissive", "Ó ń yọ̀ǹda", "宽容模式", "Permissive(허용)", "Permissif", "Permisivo", "Permissive"),
    "value_unknown" to s("Unknown", "Aimọ̀", "未知", "알 수 없음", "Inconnu", "Desconocido", "Unbekannt"),
    "row_instrumentation_frida" to s("Instrumentation (Frida)", "Ohun-èlò ìṣàyẹ̀wò (Frida)", "检测工具（Frida）", "계측 도구 (Frida)", "Instrumentation (Frida)", "Instrumentación (Frida)", "Instrumentierung (Frida)"),
    "value_frida_unavailable" to s("Unavailable", "Kò sí", "不可用", "사용 불가", "Indisponible", "No disponible", "Nicht verfügbar"),
    "value_frida_possible" to s("Possible (1 weak signal)", "Ó ṣeéṣe (àmì aláìlera 1)", "可能（1个弱信号）", "가능성 있음 (약한 신호 1개)", "Possible (1 signal faible)", "Posible (1 señal débil)", "Möglich (1 schwaches Signal)"),
    "value_frida_detected" to s("Detected (%s signals)", "A rí i (àmì %s)", "已检测到（%s 个信号）", "감지됨 (신호 %s개)", "Détecté (%s signaux)", "Detectado (%s señales)", "Erkannt (%s Signale)"),
    "row_about_these_checks" to s("About these checks", "Nípa àwọn àyẹ̀wò wọ̀nyí", "关于这些检测", "이 검사에 대해", "À propos de ces vérifications", "Acerca de estas verificaciones", "Über diese Prüfungen"),
    "dialog_integrity_body" to s(
        "App integrity compares this app's real signing certificate against the one baked in when it was built, to catch a repackaged or resigned copy running under this app's identity - it can't detect a stolen signing key used to sign a genuine-looking build, since that would pass legitimately.\n\nRoot/Magisk checks common su binary paths and known Magisk package IDs. SELinux checks whether it's actually in enforcing mode. Worth being honest about the limit: root detection is a real cat-and-mouse game - Magisk's own hiding features (Zygisk, DenyList) exist specifically to spoof exactly these checks - so a clean result here is a signal to weigh, not a guarantee.\n\nInstrumentation (Frida) layers five independent native checks - a loaded-library scan, a probe of Frida's default port, a thread-name scan, a running-process scan, and a timing heuristic - and reports how many actually flagged something, since any single layer alone is a weak signal that a determined attacker's own anti-detection scripts could defeat once they know what to look for. One weak signal (often just the timing check) can happen on a genuinely clean device; several at once is a real signal.",
        "Ìdúró ṣùgbọ́n app ń fi ìwé-ẹ̀rí ìforúkọsílẹ̀ gidi ti app yìí wéra pẹ̀lú èyí tí a fi sínú nígbà tí a kọ́ ọ, láti mú àdàkọ tí a tún ṣe pọ̀ tàbí tí a tún fi ìwé-ẹ̀rí sí tí ń ṣiṣẹ́ lábẹ́ ìdánimọ̀ app yìí - kò lè mọ̀ kọ́kọ́rọ́ ìforúkọsílẹ̀ tí a jí tí a lò láti fi ìwé-ẹ̀rí sí kíkọ́ tí ó dà bí gidi, nítorí èyí yóò kọjá lọ́nà tí ó tọ́.\n\nRoot/Magisk ń ṣàyẹ̀wò àwọn ipa-ọ̀nà su binary tí ó wọ́pọ̀ àti àwọn ID páálí Magisk tí a mọ̀. SELinux ń ṣàyẹ̀wò bóyá ó wà ní ipò fífipá mú gidi. Ó yẹ kí a sọ òtítọ́ nípa ààlà: ìdánimọ̀ root jẹ́ eré ológbò-àti-eku gidi - àwọn ẹ̀yà ìfarasin ti Magisk fúnra rẹ̀ (Zygisk, DenyList) wà ní pàtàkì láti tan àwọn àyẹ̀wò wọ̀nyí jẹ - nítorí náà àbájáde mímọ́ níbí jẹ́ àmì láti wọ̀n, kìí ṣe ìdánilójú.\n\nOhun-èlò ìṣàyẹ̀wò (Frida) ń fi àwọn àyẹ̀wò abínibí márùn-ún ọ̀tọ̀ọ̀tọ̀ ṣe pọ̀ - ìṣàyẹ̀wò ìwé-ìkàwé tí a ti kó sí, ìwádìí ibùdókọ̀ àkọ́kọ́ ti Frida, ìṣàyẹ̀wò orúkọ-thread, ìṣàyẹ̀wò ìlànà tí ń ṣiṣẹ́, àti ìṣirò àkókò - yóò sì ròyìn iye tí ó ti fi àmì hàn gan-an, nítorí ẹ̀yà kan ṣoṣo nìkan jẹ́ àmì aláìlera tí àwọn ìwé-àṣẹ ìfarasin tí ọ̀tá pinnu lè borí lẹ́yìn tí wọ́n bá mọ ohun tí wọ́n ń wá. Àmì aláìlera kan (ọ̀pọ̀lọpọ̀ ìgbà kìí ṣe àyẹ̀wò àkókò nìkan) lè ṣẹlẹ̀ lórí ẹrọ tí ó mọ́ gan-an; ọ̀pọ̀lọpọ̀ ní ẹ̀ẹ̀kan jẹ́ àmì gidi.",
        "应用完整性会将本应用真实的签名证书与构建时内置的证书进行比较，以发现在本应用身份下运行的重新打包或重新签名的副本——但它无法检测使用被盗签名密钥签署的、看起来正版的构建，因为那会合法通过。\n\nRoot/Magisk 检测会检查常见的 su 二进制路径和已知的 Magisk 包 ID。SELinux 会检查它是否真的处于强制模式。有必要如实说明其局限性：root 检测是一场真实的猫鼠游戏——Magisk 自身的隐藏功能（Zygisk、DenyList）正是专门为了欺骗这些检测而存在的——因此这里显示的“干净”结果只是一个需要权衡的信号，而非保证。\n\n检测工具（Frida）叠加了五项独立的原生检查——已加载库扫描、Frida 默认端口探测、线程名扫描、运行进程扫描以及计时启发式检测——并报告实际触发了多少项，因为任何单一层面本身都只是一个弱信号，一旦攻击者知道要找什么，其自身的反检测脚本就可能破解它。一个弱信号（通常只是计时检测）可能出现在真正干净的设备上；同时出现多个才是真正的信号。",
        "앱 무결성은 이 앱이 빌드될 때 내장된 서명 인증서와 실제 서명 인증서를 비교하여 이 앱의 신원으로 실행되는 재패키징되거나 재서명된 사본을 찾아냅니다 - 정상처럼 보이는 빌드에 서명하는 데 사용된 도난당한 서명 키는 감지할 수 없습니다, 그것은 정당하게 통과할 것이기 때문입니다.\n\nRoot/Magisk는 일반적인 su 바이너리 경로와 알려진 Magisk 패키지 ID를 확인합니다. SELinux는 실제로 강제 모드인지 확인합니다. 한계를 솔직히 말할 가치가 있습니다: 루트 감지는 진짜 고양이와 쥐 게임입니다 - Magisk 자체의 숨김 기능(Zygisk, DenyList)은 바로 이러한 검사를 속이기 위해 존재합니다 - 그래서 여기서 깨끗한 결과는 보장이 아니라 저울질할 신호입니다.\n\n계측(Frida)은 다섯 가지 독립적인 네이티브 검사를 겹칩니다 - 로드된 라이브러리 스캔, Frida 기본 포트 탐색, 스레드 이름 스캔, 실행 중인 프로세스 스캔, 타이밍 휴리스틱 - 그리고 실제로 몇 개가 표시되었는지 보고합니다, 어떤 단일 계층이든 그 자체로는 공격자가 무엇을 찾아야 하는지 알게 되면 자체 탐지 방지 스크립트로 물리칠 수 있는 약한 신호이기 때문입니다. 약한 신호 하나(대개 타이밍 검사만)는 정말 깨끗한 기기에서도 발생할 수 있습니다; 여러 개가 동시에 나타나면 진짜 신호입니다.",
        "L'intégrité de l'application compare le vrai certificat de signature de cette application à celui intégré lors de sa construction, pour détecter une copie repackagée ou resignée fonctionnant sous l'identité de cette application - elle ne peut pas détecter une clé de signature volée utilisée pour signer une version d'apparence authentique, car cela passerait légitimement.\n\nRoot/Magisk vérifie les chemins binaires su courants et les ID de paquets Magisk connus. SELinux vérifie s'il est réellement en mode applicatoire. Il convient d'être honnête sur la limite : la détection de root est un vrai jeu du chat et de la souris - les propres fonctionnalités de dissimulation de Magisk (Zygisk, DenyList) existent spécifiquement pour tromper exactement ces vérifications - donc un résultat propre ici est un signal à peser, pas une garantie.\n\nL'instrumentation (Frida) superpose cinq vérifications natives indépendantes - un scan des bibliothèques chargées, une sonde du port par défaut de Frida, un scan des noms de threads, un scan des processus en cours, et une heuristique de timing - et rapporte combien ont réellement signalé quelque chose, car une seule couche à elle seule est un signal faible qu'un attaquant déterminé pourrait déjouer avec ses propres scripts anti-détection une fois qu'il sait quoi chercher. Un signal faible (souvent juste la vérification de timing) peut se produire sur un appareil réellement propre ; plusieurs à la fois constituent un vrai signal.",
        "La integridad de la app compara el certificado de firma real de esta app con el que se incluyó al compilarla, para detectar una copia reempaquetada o refirmada que se ejecuta bajo la identidad de esta app - no puede detectar una clave de firma robada usada para firmar una compilación de apariencia genuina, ya que eso pasaría legítimamente.\n\nRoot/Magisk verifica rutas binarias su comunes e ID de paquetes Magisk conocidos. SELinux verifica si realmente está en modo forzoso. Vale la pena ser honesto sobre el límite: la detección de root es un verdadero juego del gato y el ratón - las propias funciones de ocultamiento de Magisk (Zygisk, DenyList) existen específicamente para burlar exactamente estas verificaciones - así que un resultado limpio aquí es una señal a considerar, no una garantía.\n\nLa instrumentación (Frida) superpone cinco verificaciones nativas independientes - un escaneo de bibliotecas cargadas, una sonda del puerto predeterminado de Frida, un escaneo de nombres de hilos, un escaneo de procesos en ejecución y una heurística de temporización - e informa cuántas realmente detectaron algo, ya que cualquier capa individual por sí sola es una señal débil que los propios scripts anti-detección de un atacante decidido podrían burlar una vez que sepan qué buscar. Una señal débil (a menudo solo la verificación de temporización) puede ocurrir en un dispositivo genuinamente limpio; varias a la vez es una señal real.",
        "Die App-Integrität vergleicht das echte Signaturzertifikat dieser App mit dem beim Erstellen eingebetteten, um eine neu verpackte oder neu signierte Kopie zu erkennen, die unter der Identität dieser App läuft - sie kann keinen gestohlenen Signaturschlüssel erkennen, der zum Signieren eines echt aussehenden Builds verwendet wurde, da dieser legitim durchgehen würde.\n\nRoot/Magisk prüft gängige su-Binärpfade und bekannte Magisk-Paket-IDs. SELinux prüft, ob es sich tatsächlich im Enforcing-Modus befindet. Es lohnt sich, ehrlich über die Grenze zu sein: Root-Erkennung ist ein echtes Katz-und-Maus-Spiel - Magisks eigene Verstecke-Funktionen (Zygisk, DenyList) existieren speziell, um genau diese Prüfungen zu täuschen - ein sauberes Ergebnis hier ist also ein abzuwägendes Signal, keine Garantie.\n\nInstrumentierung (Frida) schichtet fünf unabhängige native Prüfungen - einen Scan geladener Bibliotheken, eine Sondierung von Fridas Standardport, einen Thread-Namen-Scan, einen Scan laufender Prozesse und eine Timing-Heuristik - und meldet, wie viele tatsächlich etwas markiert haben, da eine einzelne Ebene allein ein schwaches Signal ist, das die eigenen Anti-Erkennungs-Skripte eines entschlossenen Angreifers überwinden könnten, sobald er weiß, wonach er suchen muss. Ein schwaches Signal (oft nur die Timing-Prüfung) kann auf einem wirklich sauberen Gerät auftreten; mehrere gleichzeitig sind ein echtes Signal."
    ),

    "dialog_listening_mode_title" to s("Listening mode", "Ipò Ìgbọ́ràn", "聆听模式", "청취 모드", "Mode d'écoute", "Modo de escucha", "Zuhörmodus"),
    "dialog_listening_mode_body" to s(
        "When Listening mode is ON, Xenos will use your voice input as commands or chat whenever you tap the mic.\n\nPros: hands-free, faster commands.\nDrawbacks: anything you say after tapping the mic may trigger actions even if you didn't mean it.",
        "Nígbà tí Ipò Ìgbọ́ràn bá ń ṣiṣẹ́, Xenos yóò lo ohun tí o wí gẹ́gẹ́ bí àṣẹ tàbí ìjíròrò nígbàkigbà tí o bá tẹ máìkì.\n\nÀǹfààní: ọwọ́-òfo, àṣẹ tí ó yára jù.\nÀìléwu: ohunkóhun tí o bá sọ lẹ́yìn tí o bá tẹ máìkì lè mú ìgbésẹ̀ jáde bí o tilẹ̀ kò gbèrò rẹ̀.",
        "聆听模式开启时，只要你点击麦克风，Xenos 就会将你的语音输入用作命令或聊天内容。\n\n优点：解放双手，命令更快。\n缺点：点击麦克风后你说的任何话都可能触发操作，即使你并非本意。",
        "청취 모드가 켜져 있으면 마이크를 탭할 때마다 Xenos는 네 음성 입력을 명령이나 대화로 사용합니다.\n\n장점: 손을 쓰지 않아도 되고 명령이 더 빠릅니다.\n단점: 마이크를 탭한 후 말하는 모든 것이 의도치 않아도 동작을 유발할 수 있습니다.",
        "Lorsque le mode d'écoute est ACTIVÉ, Xenos utilisera ton entrée vocale comme commandes ou discussion chaque fois que tu touches le micro.\n\nAvantages : mains libres, commandes plus rapides.\nInconvénients : tout ce que tu dis après avoir touché le micro peut déclencher des actions même si ce n'était pas voulu.",
        "Cuando el Modo de escucha está ACTIVADO, Xenos usará tu entrada de voz como comandos o chat cada vez que toques el micrófono.\n\nVentajas: manos libres, comandos más rápidos.\nDesventajas: cualquier cosa que digas después de tocar el micrófono puede activar acciones aunque no fuera tu intención.",
        "Wenn der Zuhörmodus AN ist, verwendet Xenos deine Spracheingabe als Befehle oder Chat, sobald du das Mikrofon antippst.\n\nVorteile: freihändig, schnellere Befehle.\nNachteile: Alles, was du nach dem Antippen des Mikrofons sagst, kann Aktionen auslösen, auch wenn du es nicht so gemeint hast."
    ),
    "dialog_trigger_lockdown_body" to s(
        "Immediately arms Sequence Mode: locks the phone right now (if OS-level lockdown is enabled) and starts the recovery countdown toward full-device wipe if that's turned on. Use this if the phone is lost or stolen. Exiting afterward requires a fingerprint.",
        "Ó ń dá Sequence Mode sílẹ̀ lẹ́sẹ̀kẹsẹ̀: ó ń dènà fóònù náà nísinsìnyí (bí ìdènà ipele-OS bá ń ṣiṣẹ́) yóò sì bẹ̀rẹ̀ ìka àkókò ìpadàbọ̀ sí ìparẹ́ ẹrọ pátápátá bí a bá ti tan èyí. Lo èyí bí fóònù náà bá sọnù tàbí tí a jí i. Jíjáde lẹ́yìn náà nílò ìka.",
        "立即启动序列模式：马上锁定手机（如果已启用系统级锁定），并在已开启的情况下开始朝完全清除设备方向的恢复倒计时。手机丢失或被盗时使用此项。之后退出需要指纹验证。",
        "즉시 시퀀스 모드를 작동시킵니다: 지금 바로 휴대폰을 잠그고(OS 수준 잠금이 활성화된 경우) 활성화되어 있다면 기기 전체 초기화를 향한 복구 카운트다운을 시작합니다. 휴대폰을 잃어버리거나 도난당했을 때 사용하세요. 이후 종료하려면 지문이 필요합니다.",
        "Arme immédiatement le mode séquence : verrouille le téléphone tout de suite (si le verrouillage niveau OS est activé) et démarre le compte à rebours de récupération vers l'effacement complet si celui-ci est activé. Utilise ceci si le téléphone est perdu ou volé. En sortir ensuite nécessite une empreinte digitale.",
        "Arma inmediatamente el Modo secuencia: bloquea el teléfono ahora mismo (si el bloqueo a nivel de SO está activado) e inicia la cuenta regresiva de recuperación hacia el borrado total si eso está activado. Úsalo si el teléfono se pierde o es robado. Salir después requiere una huella digital.",
        "Aktiviert sofort den Sequence-Modus: sperrt das Telefon jetzt (falls die Sperre auf Betriebssystemebene aktiviert ist) und startet den Wiederherstellungs-Countdown zum vollständigen Löschen, falls dies eingeschaltet ist. Verwende dies, wenn das Telefon verloren oder gestohlen wurde. Das anschließende Verlassen erfordert einen Fingerabdruck."
    ),
    "dialog_anti_theft_body" to s(
        "When ON, Xenos watches for sudden movement and, if he spots it, asks \"are you running, or is everything OK?\" (a high-priority \"Are you OK?\" notification) and starts a 10-minute countdown that locks the phone down if you don't confirm with a fingerprint. Turning this OFF silences those proactive motion alerts and the status-change notification entirely - Xenos won't ask if you're OK. Manual lockdown (Trigger lockdown now), the failed-fingerprint auto-arm, location tracking, and full-device wipe all still work normally.",
        "Nígbà tí ó bá ń ṣiṣẹ́, Xenos yóò ṣọ́ ìṣíṣẹ́ lójijì, tí ó bá sì rí i, yóò béèrè \"ṣé o ń sáré, tàbí ó ha yẹ?\" (ìkìlọ̀ gíga-pàtàkì \"Ṣé o yẹ?\") yóò sì bẹ̀rẹ̀ ìka àkókò ìṣẹ́jú 10 tí yóò dènà fóònù náà bí o kò bá fi ìka jẹ́rìí. Pípa èyí kúrò ń dá àwọn ìkìlọ̀ ìṣíṣẹ́ aládàáṣe wọ̀nyí àti ìkìlọ̀ ìyípadà-ipò dúró pátápátá - Xenos kì yóò béèrè bóyá o yẹ. Ìdènà ọwọ́ (Dá ìdènà sílẹ̀ nísinsìnyí), ìdásílẹ̀ aládàáṣe fún ìka tí kùnà, ìtọpasẹ̀ ibùdó, àti ìparẹ́ ẹrọ pátápátá ń ṣiṣẹ́ ní déédéé ṣì.",
        "开启时，Xenos 会监视突发运动，一旦发现，就会询问“你在跑步吗，还是一切都好？”（一条高优先级的“你还好吗？”通知），并启动一个10分钟倒计时，如果你未通过指纹确认，就会锁定手机。关闭此项会完全静音这些主动运动提醒和状态变更通知——Xenos 不会再问你是否安好。手动锁定（立即触发锁定）、指纹失败自动启动、位置追踪和完全清除设备仍会正常工作。",
        "켜져 있으면 Xenos는 갑작스러운 움직임을 감시하고, 발견하면 \"뛰고 있어, 아니면 괜찮은 거야?\"라고 묻고(높은 우선순위의 \"괜찮아?\" 알림), 지문으로 확인하지 않으면 휴대폰을 잠그는 10분 카운트다운을 시작합니다. 이것을 끄면 이러한 능동적 움직임 알림과 상태 변경 알림이 완전히 조용해집니다 - Xenos는 괜찮은지 묻지 않습니다. 수동 잠금(지금 잠금 실행), 지문 실패 자동 작동, 위치 추적, 기기 전체 초기화는 모두 정상적으로 계속 작동합니다.",
        "Lorsque c'est ACTIVÉ, Xenos surveille les mouvements soudains et, s'il en détecte un, demande « es-tu en train de courir, ou est-ce que tout va bien ? » (une notification prioritaire « Est-ce que tout va bien ? ») et démarre un compte à rebours de 10 minutes qui verrouille le téléphone si tu ne confirmes pas avec une empreinte digitale. Désactiver ceci fait taire entièrement ces alertes de mouvement proactives et la notification de changement de statut - Xenos ne demandera pas si tout va bien. Le verrouillage manuel (Déclencher le verrouillage maintenant), l'armement automatique par empreinte échouée, le suivi de localisation et l'effacement complet fonctionnent tous toujours normalement.",
        "Cuando está ACTIVADO, Xenos vigila el movimiento repentino y, si lo detecta, pregunta \"¿estás corriendo, o todo está bien?\" (una notificación de alta prioridad \"¿Estás bien?\") e inicia una cuenta regresiva de 10 minutos que bloquea el teléfono si no confirmas con una huella digital. Desactivar esto silencia por completo esas alertas de movimiento proactivas y la notificación de cambio de estado - Xenos no preguntará si estás bien. El bloqueo manual (Activar bloqueo ahora), el armado automático por huella fallida, el seguimiento de ubicación y el borrado total siguen funcionando normalmente.",
        "Wenn AN, beobachtet Xenos plötzliche Bewegungen und fragt, falls er eine erkennt, „Läufst du, oder ist alles in Ordnung?“ (eine hochprioritäre „Ist alles okay?“-Benachrichtigung) und startet einen 10-minütigen Countdown, der das Telefon sperrt, wenn du nicht per Fingerabdruck bestätigst. Das Ausschalten dieser Option unterdrückt diese proaktiven Bewegungsalarme und die Statusänderungsbenachrichtigung vollständig - Xenos fragt nicht, ob es dir gut geht. Manuelle Sperre (Sperre jetzt auslösen), die automatische Aktivierung bei fehlgeschlagenem Fingerabdruck, die Standortverfolgung und das vollständige Löschen funktionieren weiterhin normal."
    ),
    "dialog_os_lockdown_body" to s(
        "Enables the real Android lockscreen for Sequence Mode, not just this app's PIN, and is required if you also want full-device wipe. Once enabled, it can't be turned off from inside this app - only through Android's own Device Admin settings. That's intentional: if your phone is taken, whoever has it can't just tap a toggle in here to undo it.",
        "Ó ń mú ìbojú ìdènà gidi ti Android ṣiṣẹ́ fún Sequence Mode, kìí ṣe PIN app yìí nìkan, ó sì gbọ́dọ̀ jẹ́ bí o bá tún fẹ́ ìparẹ́ ẹrọ pátápátá. Ní ìgbà tí a bá ti mú un ṣiṣẹ́, a kò lè pa á kúrò láti inú app yìí - àyàfi nípasẹ̀ àwọn ètò Device Admin ti Android fúnra rẹ̀. Èyí jẹ́ ìpinnu: bí a bá gba fóònù rẹ, ẹnikẹ́ni tí ó ní i kò lè kàn tẹ àyípadà kan níbí láti yí i padà.",
        "为序列模式启用真正的 Android 锁屏，而不仅仅是本应用的 PIN，如果你还想要完全清除设备，这也是必需的。一旦启用，就无法在本应用内关闭——只能通过 Android 自身的设备管理员设置关闭。这是刻意设计的：如果你的手机被拿走，拿到手机的人无法仅通过点击这里的一个开关来撤销它。",
        "시퀀스 모드를 위해 이 앱의 PIN뿐만 아니라 실제 Android 잠금 화면을 활성화하며, 기기 전체 초기화도 원한다면 필수입니다. 한 번 활성화되면 이 앱 내부에서는 끌 수 없습니다 - Android 자체의 기기 관리자 설정을 통해서만 가능합니다. 이는 의도적입니다: 휴대폰을 빼앗기면, 가진 사람이 여기서 토글 하나만 눌러서 되돌릴 수 없습니다.",
        "Active le véritable écran de verrouillage Android pour le mode séquence, pas seulement le code PIN de cette application, et est requis si tu veux aussi l'effacement complet. Une fois activé, il ne peut pas être désactivé depuis cette application - seulement via les propres paramètres Administrateur de l'appareil d'Android. C'est intentionnel : si ton téléphone est pris, celui qui l'a ne peut pas simplement appuyer sur un interrupteur ici pour l'annuler.",
        "Habilita la verdadera pantalla de bloqueo de Android para el Modo secuencia, no solo el PIN de esta app, y es necesario si también quieres el borrado total. Una vez habilitado, no se puede desactivar desde dentro de esta app - solo a través de los propios ajustes de Administrador del dispositivo de Android. Eso es intencional: si te quitan el teléfono, quien lo tenga no puede simplemente tocar un interruptor aquí para deshacerlo.",
        "Aktiviert den echten Android-Sperrbildschirm für den Sequence-Modus, nicht nur die PIN dieser App, und ist erforderlich, wenn du auch das vollständige Löschen möchtest. Einmal aktiviert, kann es nicht von innerhalb dieser App ausgeschaltet werden - nur über Androids eigene Geräteadministrator-Einstellungen. Das ist beabsichtigt: Wenn dein Telefon genommen wird, kann derjenige, der es hat, nicht einfach einen Schalter hier antippen, um es rückgängig zu machen."
    ),
    "dialog_location_history_body" to s(
        "When on, this records the device's location roughly every 15 minutes (using whatever fix the phone already has, not an active GPS request each time) so you can see where it's been, not just where it is right now. Stored locally only - view it under Location history above. Turn off any time; existing entries stay until you clear them.",
        "Nígbà tí ó bá ń ṣiṣẹ́, èyí ń gba ibùdó ẹrọ náà bí ìṣẹ́jú 15 kọ̀ọ̀kan (ní lílo fix tí fóònù náà ti ní tán, kìí ṣe ìbéèrè GPS aládàáṣe ní ìgbà kọ̀ọ̀kan) kí o bàa lè rí ibi tí ó ti wà, kìí ṣe ibi tí ó wà nísinsìnyí nìkan. A fi pamọ́ ládìí ní agbègbè nìkan - wo o lábẹ́ Ìtàn ibùdó lókè. Pa kúrò nígbàkigbà; àwọn àkọsílẹ̀ tí ó wà yóò dúró títí o fi pa wọ́n rẹ́.",
        "开启时，这会大约每15分钟记录一次设备位置（使用手机已有的定位信息，而非每次都主动发起 GPS 请求），这样你就能看到它去过哪里，而不仅仅是当前所在位置。仅存储在本地——可在上方的位置历史中查看。可随时关闭；已有记录会保留，直到你清除它们。",
        "켜져 있으면 대략 15분마다 기기 위치를 기록합니다(매번 능동적으로 GPS를 요청하는 것이 아니라 휴대폰이 이미 가지고 있는 위치 정보 사용) - 지금 어디 있는지뿐 아니라 어디에 있었는지도 볼 수 있습니다. 로컬에만 저장됩니다 - 위의 위치 기록에서 확인하세요. 언제든지 끌 수 있습니다; 기존 항목은 지우기 전까지 남아 있습니다.",
        "Lorsque c'est activé, cela enregistre la position de l'appareil environ toutes les 15 minutes (en utilisant la position déjà connue du téléphone, pas une demande GPS active à chaque fois) pour que tu puisses voir où il est allé, pas seulement où il est maintenant. Stocké localement uniquement - consulte-le sous Historique de localisation ci-dessus. Désactive à tout moment ; les entrées existantes restent jusqu'à ce que tu les effaces.",
        "Cuando está activado, esto registra la ubicación del dispositivo aproximadamente cada 15 minutos (usando la ubicación que el teléfono ya tenga, no una solicitud GPS activa cada vez) para que puedas ver dónde ha estado, no solo dónde está ahora. Se almacena solo localmente - consúltalo en Historial de ubicación arriba. Desactívalo cuando quieras; las entradas existentes permanecen hasta que las borres.",
        "Wenn eingeschaltet, wird der Standort des Geräts etwa alle 15 Minuten aufgezeichnet (unter Verwendung des bereits vorhandenen Standorts des Telefons, nicht einer aktiven GPS-Anfrage jedes Mal), damit du sehen kannst, wo es war, nicht nur, wo es gerade ist. Nur lokal gespeichert - unter Standortverlauf oben einsehbar. Jederzeit ausschaltbar; bestehende Einträge bleiben, bis du sie löschst."
    ),
    "dialog_full_wipe_body" to s(
        "If Sequence Mode is triggered and never recovered (about 30 days), this app normally only clears its own local data - locks, hidden/frozen app lists, intruder photos. Turning this ON instead factory-resets the entire phone at that point, not just this app. This requires OS-level lockdown to be enabled too, and cannot be undone once it happens - use it only if you'd rather lose everything than risk your data.",
        "Bí a bá dá Sequence Mode sílẹ̀ tí a kò sì rí i pa dà (bí ọjọ́ 30), app yìí máa ń pa dátà ládìí tirẹ̀ nìkan rẹ́ ní ìwọ̀nba - ìdènà, àkọsílẹ̀ àwọn app tí a fi pamọ́/tí a dì, àwòrán adigunjale. Títan èyí dípò ń fi gbogbo fóònù náà padà sí ipò-ilé-iṣẹ́ ní àkókò yẹn, kìí ṣe app yìí nìkan. Èyí nílò kí ìdènà ipele-OS náà ṣiṣẹ́ pẹ̀lú, a kò sì lè yí padà ní ìgbà tí ó bá ṣẹlẹ̀ - lo o nìkan bí o bá fẹ́ kí ohun gbogbo sọnù ju kí o fi dátà rẹ sínú ewu.",
        "如果序列模式被触发且始终未被恢复（约30天），本应用通常只会清除自身的本地数据——锁定、隐藏/冻结应用列表、入侵者照片。开启此选项后，届时会将整部手机恢复出厂设置，而不仅仅是本应用。这也要求同时启用系统级锁定，且一旦发生便无法撤销——只有在你宁愿失去一切也不愿让数据处于风险中时才使用它。",
        "시퀀스 모드가 작동되고 끝내 복구되지 않으면(약 30일), 이 앱은 일반적으로 자체 로컬 데이터만 지웁니다 - 잠금, 숨김/동결 앱 목록, 침입자 사진. 이것을 켜면 대신 그 시점에 이 앱뿐만 아니라 휴대폰 전체를 공장 초기화합니다. 이는 OS 수준 잠금도 활성화되어 있어야 하며, 일단 발생하면 되돌릴 수 없습니다 - 데이터를 위험에 빠뜨리느니 모든 것을 잃는 게 낫다고 생각할 때만 사용하세요.",
        "Si le mode séquence est déclenché et jamais récupéré (environ 30 jours), cette application n'efface normalement que ses propres données locales - verrous, listes d'applications masquées/gelées, photos d'intrus. Activer ceci réinitialise plutôt l'intégralité du téléphone aux paramètres d'usine à ce moment-là, pas seulement cette application. Cela nécessite aussi que le verrouillage niveau OS soit activé, et ne peut pas être annulé une fois que cela se produit - utilise-le seulement si tu préfères tout perdre plutôt que risquer tes données.",
        "Si el Modo secuencia se activa y nunca se recupera (unos 30 días), esta app normalmente solo borra sus propios datos locales - bloqueos, listas de apps ocultas/congeladas, fotos de intrusos. Activar esto en su lugar restaura de fábrica todo el teléfono en ese momento, no solo esta app. Esto requiere que el bloqueo a nivel de SO también esté habilitado, y no se puede deshacer una vez que ocurre - úsalo solo si prefieres perderlo todo antes que arriesgar tus datos.",
        "Wenn der Sequence-Modus ausgelöst und nie wiederhergestellt wird (etwa 30 Tage), löscht diese App normalerweise nur ihre eigenen lokalen Daten - Sperren, Listen versteckter/eingefrorener Apps, Eindringlingsfotos. Das Einschalten setzt stattdessen das gesamte Telefon zu diesem Zeitpunkt auf Werkseinstellungen zurück, nicht nur diese App. Dies erfordert auch, dass die Sperre auf Betriebssystemebene aktiviert ist, und kann nicht rückgängig gemacht werden, sobald es geschieht - verwende es nur, wenn du lieber alles verlieren würdest, als deine Daten zu riskieren."
    ),
    "dialog_evacuation_body" to s(
        "If Full-device wipe is on, right before the real ~30-day auto-wipe deletes this app's local data, it first uploads just the intruder photos and location history (nothing else) to the backend, so a wiped/stolen phone doesn't also mean losing the only evidence of who took it. This button does the exact same upload on demand, right now, without wiping anything - so you can confirm it actually works before you ever need it for real.",
        "Bí Ìparẹ́ ẹrọ pátápátá bá ń ṣiṣẹ́, ṣáájú kí ìparẹ́ aládàáṣe gidi ti ọjọ́ 30 tó pa dátà ládìí app yìí rẹ́, ó máa kọ́kọ́ gbé àwòrán adigunjale àti ìtàn ibùdó nìkan (kò sí ohun mìíràn) sókè sí backend, kí fóònù tí a ti parẹ́/jí má bàa túmọ̀ sí pípàdánù ẹ̀rí kan ṣoṣo ti ẹni tí ó gbà á pẹ̀lú. Bọ́ọ̀lù yìí ń ṣe gbígbé sókè kan náà gan-an ní ìbéèrè, nísinsìnyí, láìsí pípa ohunkóhun rẹ́ - kí o bàa lè jẹ́rìí pé ó ń ṣiṣẹ́ gan-an kí o tó nílò rẹ̀ ní gidi.",
        "如果“完全清除设备”已开启，在真正的约30天自动清除删除本应用的本地数据之前，它会先仅将入侵者照片和位置历史（仅此而已）上传到后端，这样被清除/被盗的手机也不会同时意味着失去唯一能证明是谁拿走它的证据。此按钮会立即按需执行完全相同的上传，不会清除任何内容——这样你可以在真正需要它之前确认它确实有效。",
        "기기 전체 초기화가 켜져 있으면, 실제 약 30일 자동 초기화가 이 앱의 로컬 데이터를 삭제하기 직전에, 침입자 사진과 위치 기록만(다른 것은 없이) 먼저 백엔드에 업로드하여, 초기화되거나 도난당한 휴대폰이라 해도 누가 가져갔는지에 대한 유일한 증거를 잃지 않도록 합니다. 이 버튼은 아무것도 지우지 않고 지금 바로 요청 시 정확히 동일한 업로드를 수행합니다 - 실제로 필요하기 전에 제대로 작동하는지 확인할 수 있습니다.",
        "Si l'effacement complet est activé, juste avant que l'effacement automatique réel d'environ 30 jours ne supprime les données locales de cette application, elle téléverse d'abord uniquement les photos d'intrus et l'historique de localisation (rien d'autre) vers le backend, afin qu'un téléphone effacé/volé ne signifie pas aussi perdre la seule preuve de qui l'a pris. Ce bouton effectue exactement le même téléversement à la demande, maintenant, sans rien effacer - pour que tu puisses confirmer que cela fonctionne vraiment avant d'en avoir besoin pour de vrai.",
        "Si el borrado total está activado, justo antes de que el auto-borrado real de ~30 días elimine los datos locales de esta app, primero sube solo las fotos de intrusos y el historial de ubicación (nada más) al backend, para que un teléfono borrado/robado no signifique también perder la única evidencia de quién lo tomó. Este botón hace exactamente la misma subida bajo demanda, ahora mismo, sin borrar nada - para que puedas confirmar que realmente funciona antes de necesitarlo de verdad.",
        "Wenn das vollständige Löschen aktiviert ist, lädt es kurz bevor das echte ~30-tägige automatische Löschen die lokalen Daten dieser App entfernt, zunächst nur die Eindringlingsfotos und den Standortverlauf (sonst nichts) zum Backend hoch, damit ein gelöschtes/gestohlenes Telefon nicht auch bedeutet, den einzigen Beweis dafür zu verlieren, wer es genommen hat. Diese Schaltfläche führt genau denselben Upload auf Abruf durch, jetzt sofort, ohne irgendetwas zu löschen - damit du bestätigen kannst, dass es wirklich funktioniert, bevor du es jemals wirklich brauchst."
    )
)
