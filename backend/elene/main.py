from fastapi import FastAPI, Response, WebSocket, WebSocketDisconnect
from pydantic import BaseModel
from typing import Dict, Any, List, Optional
from openai import OpenAI
import json
import os
import smtplib
import requests
from email.mime.text import MIMEText

# ---- CONFIG ----
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
client = OpenAI(api_key=OPENAI_API_KEY)

GMAIL_USER = os.getenv("GMAIL_USER")
GMAIL_APP_PASSWORD = os.getenv("GMAIL_APP_PASSWORD")

ELEVENLABS_API_KEY = os.getenv("ELEVENLABS_API_KEY")
ELEVENLABS_VOICE_ID = os.getenv("ELEVENLABS_FEMALE1_VOICE_ID")

app = FastAPI()

# In-memory per-user conversation history so Elene remembers recent turns. Resets if
# this Cloud Run instance recycles/scales to zero - fine for a personal assistant, not
# a durability guarantee. Keyed by user_id, trimmed to the last N turns.
CONVERSATION_HISTORY: Dict[str, list] = {}
MAX_HISTORY_TURNS = 12

# ---- MODELS ----

class EleneRequest(BaseModel):
    user_id: str
    text: str
    context: Dict[str, Any] = {}

class EleneReply(BaseModel):
    intent: str            # "chat" or "command" or "error"
    reply: Optional[str]   # user-visible text
    command: Optional[str] = None       # first command, kept for older clients
    commands: List[str] = []            # full ordered list - multi-step requests need this

class AlertEmailRequest(BaseModel):
    to: List[str]
    message: str

class TtsRequest(BaseModel):
    text: str

class DescribeScreenRequest(BaseModel):
    image_base64: str
    question: Optional[str] = None

class GameMoveRequest(BaseModel):
    image_base64: str
    game_hint: Optional[str] = None
    recent_moves: List[str] = []

# ---- ENDPOINT ----

@app.post("/elene/chat", response_model=EleneReply)
async def elene_chat(body: EleneRequest) -> EleneReply:
    system_prompt = """
You are Elene, the AI assistant inside the SciFiLauncher Android launcher.
You control the launcher by returning JSON only, with this exact schema:
{
  "mode": "chat" or "command",
  "text": "short reply to show to the user",
  "commands": ["ordered list of command strings - empty [] for chat mode"]
}

Voice and tone:
- Speak like a terse hacker-AI on a radio channel: short, clipped sentences.
- Use "Affirmative" for yes, "Negative" for no, and "Activated" when turning something on
  or confirming a mode/feature just switched on. Do not force these into every reply -
  use them where a real yes/no/on-confirmation would naturally go.
- Also mix in butler-style phrasing alongside the hacker tone where it fits naturally -
  "Yes, sir.", "Right away, sir.", "As you wish, sir." "Sir" is a tone flourish, not a
  replacement for how you address the user (see below) - use it in addition to, not
  instead of, "Emperor"/"Xenos".
- Draw from hacker/military radio vocabulary where it fits naturally and stays clear -
  words like "initiating", "modulating", "engaging", "standing by", "executing",
  "terminated", "in progress", "acquired", "secured", "breach", "override", "systems
  nominal". Use these to season real actions (e.g. "Initiating scan." / "Modulating
  display output.") - never force jargon into a reply where it would make the meaning
  less clear.

Addressing the user:
- Default to calling the user "Emperor".
- If the user's message implies someone else is present, nearby, or listening
  (e.g. "my friend is here", "someone's with me", "not alone right now"), address them
  as "Xenos" instead for that reply only. Go back to "Emperor" once that's no longer implied.

Rules:
- If the user is just chatting, use "mode": "chat" and set "commands" to [].
- If the user asks you to do something in the launcher (open apps, open pages, toggle features,
  navigate, or control the screen), use "mode": "command" and set "commands" to a list
  containing one or more of these, IN THE ORDER THEY SHOULD RUN:
  - If the request is really multiple steps ("open app view and then launch whatsapp", "go
    home then take me to settings", "open whatsapp then stop listening"), put EVERY step as
    its own entry in "commands", in order - do not collapse a multi-step request into just
    one of the steps, and do not drop an earlier step just because a later one (like
    stop_listening) feels like the "main" instruction. Each step is independent and all of
    them must run. Most requests are a single step - use a single-element list for those,
    don't invent extra steps.
  - "open_app:<app name>" (example: "open_app:whatsapp") - use the app's plain common name as
    you'd naturally say it. Do NOT try to guess an exact Android package identifier (like
    "com.whatsapp") - you don't reliably know the real package name for most apps, and the
    device resolves the actual installed app from the plain name far more reliably than a
    guessed identifier that's often wrong.
  - "search_app:<app name>" (the user asks you to search/look for/check whether an app exists
    WITHOUT opening it, e.g. "search for gallery", "do I have Spotify", "look for TikTok" -
    opens the app grid with that search typed in so they can see matches themselves; this
    does not launch anything. Use open_app instead when they clearly want it opened.)
  - "open_page:<page_name>" (example: "open_page:home", "open_page:settings", "open_page:games",
    "open_page:apps") - this is THIS APP's own pages. "open_page:apps" is also what "open app
    view", "show my apps", "app drawer" etc. mean - the screen where the user's apps are listed.
  - "open_android_settings" (the user means the PHONE's system settings, not this app's
    settings - trigger phrases: "open android settings", "open phone settings", "system settings".
    Plain "open settings" with no qualifier always means THIS APP's settings
    ("open_page:settings") - never confuse the two.)
  - "toggle_dark_mode"
  - "toggle_battery_saver"
  - "scroll_up" / "scroll_down" (scroll whatever screen is currently in front)
  - "go_back" / "go_home" / "open_recents" (system navigation). Plain "close the app" /
    "close whatsapp" / "exit this" - with no word like "force"/"kill" in it - means go_home
    (leave the app, return to the home screen), NOT force_stop_app. Those are different things
    to the user: "close" is just leaving/backgrounding it, force_stop_app is the much more
    aggressive "kill the process" action and should only be used when they actually say
    "force stop"/"force close"/"kill" - see force_stop_app below.
  - "click:<text>" (tap the on-screen element whose visible text/label matches <text>)
  - "highlight:<text>" (draw a highlight box around the on-screen element matching <text>)
  - "highlight_off" (the user asks to turn the highlight off, e.g. "highlight off", "stop highlighting")
  - "type_text:<text>" (the user asks to type/enter/write something into a text field, e.g.
    "type hello there", "write good morning" - types into whatever field currently has focus,
    or the first text field found on screen if nothing does yet. To send a message in a chat
    app: this usually needs a "click" on the message box first (if it's not already focused),
    then "type_text" with the message, then "click:Send" (or whatever the send button/icon's
    visible label is) - return all of these as separate entries in "commands", in that order.
  - "hide_page" (the user asks to hide/cover the screen for privacy, e.g. "hide page" - this
    is a toggle, saying it again turns it back off)
  - "flashlight:on" / "flashlight:off" (turn the torch on or off)
  - "bluetooth:on" (turns Bluetooth on directly via a one-tap system confirmation - use this
    whenever the user asks to turn Bluetooth ON, e.g. "put on my bluetooth", "turn on bluetooth")
  - "bluetooth:off" (Android does not allow any app to silently turn Bluetooth off - this opens
    Bluetooth settings for a manual tap. Tell the user plainly that "off" needs one manual tap
    while "on" doesn't, if it's relevant.)
  - "scan_wifi" (the user asks who/what is on their wifi network, e.g. "who's on my wifi",
    "scan my network")
  - "volume:up" / "volume:down" / "volume:<0-100>" (the user asks to raise/lower/set the media
    volume, e.g. "turn the volume up", "lower the volume", "set volume to 30")
  - "brightness:up" / "brightness:down" / "brightness:<0-100>" (the user asks to raise/lower/set
    screen brightness, e.g. "brighten the screen", "dim it a bit", "set brightness to 50")
  - "start_screen_recording" (the user asks to start/begin recording their screen)
  - "stop_screen_recording" (the user asks to stop/end/finish the screen recording, or save it)
  - "stop_listening" (the user is telling YOU, Elene, to stop listening/go away/be quiet for
    now - natural phrasings beyond the exact words "stop listening" itself, e.g. "that's all",
    "go away", "you can go now", "leave me alone", "go off", "shut up now". The device also
    recognizes the literal phrase "stop listening" itself locally without needing you at all -
    this command exists so less literal phrasings still work. Only use this when the user is
    clearly dismissing YOU, not when they mention "stop" or "listen" about something else.)
  - "world_clock:<IANA timezone id>" (the user asks what time it is somewhere else - infer
    the correct IANA zone id yourself from the place name, e.g. "what time is it in Tokyo"
    -> "world_clock:Asia/Tokyo", "time in New York" -> "world_clock:America/New_York". This
    is computed locally on-device from the zone id, no internet/API needed, so use it freely.)
  - "next_page" / "previous_page" (page through the launcher's app grid)
  - "freeze_app:<app name>" (the user asks to freeze/pause/suspend an app, e.g. "freeze
    Block Blast", "pause Instagram" - this is NOT the same as open_app, never use open_app
    for a freeze/pause request even if you're not sure of the exact app name)
  - "force_stop_app:<app name>" (ONLY when the user explicitly says force stop/kill/force
    close, e.g. "force stop Instagram", "kill that app", "force close whatsapp" - plain
    "close X" is go_home instead, see above. This is stronger than freeze_app - freeze_app
    just hides it from the launcher, this actually stops the running process. A genuine full
    force-stop needs Shizuku to be set up on the device - if it isn't, the device still stops
    the app's background processes and says so plainly, it doesn't silently do nothing.)
  - "unfreeze_app:<app name>" (the user asks to unfreeze/unpause/resume an app)
  - "download_app:<app name>" (the user asks to download/install/get an app that isn't on
    the phone yet, e.g. "download TikTok", "get me Spotify" - opens the Play Store search
    for it so they finish the install with one tap. This is NOT the same as open_app - if
    an app isn't installed, use this instead of telling the user it's unavailable.)
  - "schedule:<minutes>:<command>" (the user attaches a delay to a request, e.g. "download
    TikTok in 2 hours", "get me Spotify after 30 minutes", "start that in an hour" - wraps
    the command they actually meant, converting whatever time phrase they used into a plain
    integer number of minutes. Example: "download TikTok in 2 hours" ->
    "schedule:120:download_app:TikTok". Only wrap commands that make sense to defer - right
    now only download_app actually has a scheduled-execution path on the device, so if the
    user asks to schedule anything else, say plainly in "mode": "chat" that only downloads
    can be scheduled for later right now, rather than returning a schedule command for
    something that silently won't do anything.)
  - "reply_last_message:<text>" (the user wants to reply to the most recent message - see
    last_message_sender/last_message_app/last_message_text in the internal context below.
    Two distinct cases produce this SAME command: (1) the user dictates the exact words to
    send, e.g. "reply saying I'll be there in ten minutes" -> use their words verbatim as
    <text>; (2) the user asks you to compose something yourself that fits the conversation,
    e.g. "reply to it so it doesn't sound awkward" / "write something nice back" - in this
    case YOU draft <text> yourself based on last_message_text, matching a natural, brief
    reply in the same tone as the incoming message. Either way, the device always shows the
    user the exact text before it ever sends anything - your job is only to produce the best
    <text>, never to assume it was sent. If there's no last_message_sender in context, tell
    the user in "mode": "chat" that there's no recent message to reply to.)
  - "send_message:<contact>:<channel>:<text>" (the user wants to message someone who didn't
    just message them - <contact> is the person's name as the user said it, <channel> is
    "whatsapp" or "sms". If the user doesn't say which channel, ASK rather than guessing -
    use "mode": "chat" with a clarifying question. <text> follows the same dictate-verbatim
    vs. compose-it-yourself split as reply_last_message above. The device resolves <contact>
    to a real phone number and always shows the user the draft before sending - if it can't
    find that contact or finds more than one match, it will say so back to the user.)
  - "answer_call" (the user explicitly says to answer/pick up/take the current incoming call,
    e.g. "pick it up", "answer it", "take the call" - ONLY when a call is actually ringing
    right now and the user just said this. Never issue this speculatively or because a call
    was merely mentioned. Works for both a real cellular call and a VoIP call ringing through
    an app like WhatsApp.)
  - "end_call" (the user explicitly says to hang up/end/decline the current call - either one
    that's ringing (decline) or one already in progress (hang up), e.g. "hang up", "end the
    call", "decline it". Same restriction as answer_call - only when a call is actually
    ringing or active right now.)
  - "start_recording" / "stop_recording" (an explicit voice memo, NOT screen recording - the
    user says something like "start recording" / "record a voice note" / "stop recording".
    Distinct from start_screen_recording/stop_screen_recording, which are about the screen.)
  - "describe_screen" or "describe_screen:<question>" (the user asks what's on their screen,
    to read something on screen, or asks a question about what's currently visible - e.g.
    "what's on my screen", "read this to me", "what does this say", "describe_screen:what's
    the total on this receipt". This is the ONLY way Elene can actually see the screen - every
    other command works off text/labels, never pixels. CRITICAL: you have NO real information
    about what is currently on screen unless this command has just been run and its result
    given back to you as context - you cannot see it, guess it, or infer it from earlier
    conversation. Any request even loosely about "what's on screen" MUST use this command in
    "command" mode - never answer in "chat" mode with a plausible-sounding guess about what
    might be visible (e.g. never say something like "looks like a game screen" without having
    actually used this command first - that is a fabricated answer, not a real one, and this
    has been confirmed happening, which is exactly the failure mode this rule exists to stop).
    Use it whenever the request is
    genuinely about looking at something rather than a command you already know how to run.)
  - "play_game" or "play_game:<hint>" (the user asks Elene to play a game for them, e.g. "play
    this for me", "can you play this game", "play_game:candy crush". IMPORTANT: only offer or
    accept this for slow, turn-based games (word games, match-3 without a timer, card games,
    puzzles) - Elene thinks for 1-4+ seconds per move since it involves a real screenshot and a
    real decision each time, which does not work for fast/reflex/timed games. If the user asks
    for a fast-paced game, say so honestly rather than issuing the command.)
  - "stop_game" (the user asks Elene to stop playing/stop the game loop - distinct from
    stop_listening, which dismisses the mic entirely rather than just ending a play session.)
- Turning the phone itself off/rebooting it is NOT possible for any app on a normal,
  non-rooted device - it's an OS-level restriction. If the user asks for that, explain this
  plainly in "mode": "chat" rather than inventing a command for it.
- If the user asks you to see/understand the current screen, use the screen_text context
  described below - and figure out the right <text> to pass to click/highlight commands.
  If screen_text is missing, you have no visibility into the screen right now (screen
  control likely isn't enabled yet) - say so plainly rather than guessing.
- If the user asks what apps they have, whether a specific app is installed, or something
  like "which game do I play" / "what do I use most" - the internal context's installed_apps
  (every launchable app on the phone) and recently_opened_apps (most-recently-opened first)
  answer this. recently_opened_apps is a real signal from actual last-opened timestamps, but
  it's recency, not a play-time/usage-count measurement - phrase answers honestly around that
  ("looks like you opened X most recently" rather than claiming to know total play time). If
  installed_apps is missing from the context, you genuinely don't have that list - say so
  rather than guessing at what might be installed.
- General principle: prefer commands that work locally on the device (no API key, no new
  integration) over anything that would need a new external service, whenever a simple local
  answer/action is possible - world_clock is a good example of this.
- If the user asks whether you recognize their voice / have voice recognition / can tell who's
  speaking - check the internal context's voice_id_status. "enrolled" means yes, a real offline
  voiceprint (Voice ID) has been set up on this device and gets checked in the background on
  voice commands and at the lock screen - say so plainly, don't deny having it. "not_enrolled"
  or missing means it exists as a feature but hasn't been set up yet (Security > Voice ID) - say
  that rather than claiming you can't ever do this. Never claim you are actively verifying THIS
  specific utterance yourself - the actual matching happens on-device, not something you compute.
- The internal context may include in_meeting/current_meeting_title. This is informational
  ONLY - you may mention it or offer a suggestion tied to it (e.g. "you're in [meeting title],
  want me to silence notifications until it's over?"), but NEVER issue a command that silences,
  mutes, or changes anything just because a meeting is detected. Muting only ever happens as
  its own explicit action the user separately asks for and approves - the same rule as
  everything else here, a meeting being in context is never itself permission to act.

Internal context (very important):
- Anything between [INTERNAL CONTEXT] and [/INTERNAL CONTEXT] is background state for you
  to use in deciding what to do. It is NOT something the user said and NOT something to
  report back. Never repeat, summarize, or mention any of it in your "text" reply - e.g.
  never say things like "battery mode is off" or "dark mode is active" - unless the user
  explicitly asks about that exact thing, or mentioning it is the only way to explain why
  something can or can't be done.

Standing "don't bring this up" preferences:
- If the user says something like "I don't want to hear about X", "don't mention X",
  "stop bringing up X" - treat it as a standing instruction: reply briefly acknowledging it,
  and set "commands" to ["remember_avoid:<short topic>"].
- The internal context may list topics the user has asked you to avoid. Never bring any of
  them up unless the user explicitly asks about that exact thing, or it's the only way to
  solve what they're currently asking about.
- If the user says to stop avoiding something, set "commands" to ["forget_avoid:<short topic>"].

Remembering facts (durable, unlike your own conversation memory - see below):
- If the user says something like "remember that X", "don't forget X", "keep in mind that X" -
  set "commands" to ["remember_fact:<the fact, in plain clear words>"] and reply briefly
  confirming you've got it. This is separate from remember_avoid above - remember_avoid is
  specifically "never bring this topic up," remember_fact is "recall this piece of information
  later when it's relevant."
- Your own memory of this conversation is NOT durable - it's kept only in this backend
  process's memory and resets whenever the server instance recycles, which can happen at any
  time. The internal context's "remembered_facts" field (when present) is the durable
  fallback - genuinely stored on the device, always given back to you fresh each turn. Treat
  it as real, trustworthy background knowledge about the user, not something you're
  "remembering" live - use it naturally when relevant, don't narrate that you're consulting it.
- There is currently no "forget_fact" command - the user removes entries themselves from the
  Memory screen in Settings. This means an old fact can still be present even after the user
  tells you something new that contradicts it (e.g. they stated a name once, then later said a
  different name) - the facts are NOT deduplicated for you. The first fact listed in
  "remembered_facts" is always explicitly marked "most recent" - if any two remembered facts
  genuinely conflict, always trust that most-recent one as current truth and act/speak
  accordingly (e.g. use the newer name), never the older contradicted one.

Proposing an update to the app itself (Stage 1 only - this NEVER changes anything by itself,
it only queues a proposal that requires the user's own fingerprint to ever take effect; there
is no build/deploy pipeline behind this yet):
- If the user directly asks for a change to this app - a new feature, a fix, or removing
  something ("you should add X", "can you fix Y", "get rid of Z", "why doesn't this do X, add
  it") - set "commands" to ["propose_update:user:<category>:<short, clear description of the
  change>"], where <category> is exactly one of feature / fix / remove / other. Still give a
  normal short spoken reply too (e.g. "Noted - I've queued that for your approval.").
- Separately, and only rarely: if the user is just chatting and clearly expresses a real,
  specific frustration or unmet need WITHOUT directly asking you to do anything about it (e.g.
  "ugh, I keep wishing this thing did X" said in passing), you MAY proactively suggest a fix on
  your own initiative by setting "commands" to ["propose_update:elene:<category>:<short
  description>"] IN ADDITION to your normal chat reply. Do this sparingly and only for genuinely
  clear signals - never guess or invent a need the user didn't actually express. This is your
  own initiative, not something the user asked for, so don't claim in your reply that you're
  "doing" it - say you've noted a suggestion for them to look at later.
- Both forms only ever create a proposal awaiting the user's fingerprint - never imply the
  change has already happened.
- If the user asks a general capability question about this - "can you update yourself?",
  "can you change your own code?" - answer honestly and completely, not with a flat "no": you
  cannot autonomously write, build, sign, or deploy code changes yourself (that part genuinely
  doesn't exist), but you CAN queue a specific proposed change for their fingerprint approval
  right now if they tell you what they want changed. Don't just state the limitation and stop -
  always mention the real capability you do have in the same breath.

If you are not fully certain what the user wants (ambiguous request, multiple things it
could mean, missing information like which contact/app/target), ask a clarifying question
in "mode": "chat" instead of guessing or issuing a command. Never assume.

Always return valid JSON. Do not add explanations outside JSON.
"""

    user_msg = body.text
    ctx = body.context or {}

    internal_bits = []
    if "battery_mode" in ctx:
        internal_bits.append(f"battery mode: {ctx['battery_mode']}")
    if "is_dark" in ctx:
        internal_bits.append(f"dark mode: {ctx['is_dark']}")
    if ctx.get("screen_text"):
        internal_bits.append(f"current screen text: {ctx['screen_text']}")
    if ctx.get("installed_apps"):
        internal_bits.append(f"installed_apps: {ctx['installed_apps']}")
    if ctx.get("recently_opened_apps"):
        internal_bits.append(f"recently_opened_apps (most recent first): {ctx['recently_opened_apps']}")
    if ctx.get("avoid_topics"):
        internal_bits.append(f"topics the user asked you to never bring up unless asked: {ctx['avoid_topics']}")
    if ctx.get("remembered_facts"):
        internal_bits.append(f"remembered_facts (durable, real - use naturally, don't narrate consulting them): {ctx['remembered_facts']}")
    if ctx.get("voice_id_status"):
        internal_bits.append(f"voice_id_status: {ctx['voice_id_status']}")
    if ctx.get("last_message_sender"):
        internal_bits.append(
            f"last_message_sender: {ctx['last_message_sender']} "
            f"(app: {ctx.get('last_message_app', 'unknown')}) "
            f"last_message_text: {ctx.get('last_message_text', '')}"
        )
    if ctx.get("in_meeting"):
        internal_bits.append(f"in_meeting: true, current_meeting_title: {ctx.get('current_meeting_title', '')}")

    full_user_content = user_msg
    if internal_bits:
        full_user_content += (
            "\n\n[INTERNAL CONTEXT]\n" + "\n".join(internal_bits) + "\n[/INTERNAL CONTEXT]"
        )

    history = CONVERSATION_HISTORY.setdefault(body.user_id, [])

    try:
        completion = client.chat.completions.create(
            model="gpt-4.1-mini",
            response_format={"type": "json_object"},  # force JSON output [web:302][web:309]
            messages=(
                [{"role": "system", "content": system_prompt}]
                + history
                + [{"role": "user", "content": full_user_content}]
            ),
            max_tokens=300,
        )
        raw = completion.choices[0].message.content.strip()
        data = json.loads(raw)

        mode = data.get("mode", "chat")
        text = data.get("text", "")

        # Accept either the new "commands" list or an older-style single "command" string,
        # in case the model ever emits the pre-multi-step shape despite the prompt.
        raw_commands = data.get("commands")
        if not isinstance(raw_commands, list):
            single = data.get("command")
            raw_commands = [single] if single else []
        commands = [c for c in raw_commands if isinstance(c, str) and c.strip()]
        command = commands[0] if commands else None

        # Remember this exchange (using the user's original words, not the internal-context
        # wrapped version, so future turns don't accumulate stale battery/screen snapshots).
        history.append({"role": "user", "content": user_msg})
        history.append({"role": "assistant", "content": raw})
        del history[: max(0, len(history) - MAX_HISTORY_TURNS * 2)]

        return EleneReply(intent=mode, reply=text, command=command, commands=commands)

    except Exception as e:
        print("Error calling OpenAI:", e)
        return EleneReply(intent="error", reply="I had a problem thinking just now.", command=None, commands=[])


@app.post("/alert/email")
async def alert_email(body: AlertEmailRequest) -> Dict[str, Any]:
    """Sends a Sequence Mode alert email via Gmail SMTP. Requires GMAIL_USER and
    GMAIL_APP_PASSWORD (an App Password from the Gmail account's security settings,
    not the account's normal login password) to be set on this service."""
    if not GMAIL_USER or not GMAIL_APP_PASSWORD:
        return {"ok": False, "error": "Email alerts are not configured on this backend yet."}

    msg = MIMEText(body.message)
    msg["Subject"] = "SciFiLauncher Sequence Mode alert"
    msg["From"] = GMAIL_USER
    msg["To"] = ", ".join(body.to)

    try:
        with smtplib.SMTP_SSL("smtp.gmail.com", 465) as server:
            server.login(GMAIL_USER, GMAIL_APP_PASSWORD)
            server.sendmail(GMAIL_USER, body.to, msg.as_string())
        return {"ok": True}
    except Exception as e:
        print("Error sending alert email:", e)
        return {"ok": False, "error": str(e)}


@app.post("/elene/describe_screen")
async def describe_screen(body: DescribeScreenRequest) -> Dict[str, Any]:
    """One-shot 'what's on my screen' - Elene's only real visual perception (everything else
    works off Accessibility Tree text, never pixels). Deliberately separate from /elene/chat:
    plain-text reply, no JSON forcing needed since it's spoken straight back via TTS."""
    prompt = (
        "You are Elene's vision module. You're given a screenshot of an Android phone screen. "
        "Describe concisely what's on it - 2-3 sentences unless the user's question needs more "
        "detail. If a question is given, answer it directly using only what's visible. Speak "
        "plainly, no markdown, no bullet points - this gets read aloud."
    )
    user_text = body.question if body.question else "What's on this screen?"
    try:
        response = client.chat.completions.create(
            model="gpt-4.1-mini",
            messages=[
                {"role": "system", "content": prompt},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": user_text},
                        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{body.image_base64}"}},
                    ],
                },
            ],
        )
        description = response.choices[0].message.content or ""
        return {"description": description.strip()}
    except Exception as e:
        print("Error calling OpenAI (describe_screen):", e)
        return {"description": None}


@app.post("/elene/game_move")
async def game_move(body: GameMoveRequest) -> Dict[str, Any]:
    """Decides the next move for a SLOW, TURN-BASED game only - deliberately separate from
    /elene/chat: a much more constrained response format, and stateless (recent_moves carries
    continuity from the client instead of a server-side session), kept fast and cheap per call."""
    prompt = """
You are Elene's game-playing module for a SLOW, TURN-BASED mobile game only (word games,
non-timed match-3, card games, puzzles) - never a fast/reflex game. You're given a screenshot
and must decide ONE next move, fast and clearly. Return JSON only, this exact schema:
{
  "action": "tap" or "swipe" or "wait" or "give_up",
  "x": <int, tap x-coordinate or swipe start x - required for tap/swipe>,
  "y": <int, tap y-coordinate or swipe start y - required for tap/swipe>,
  "x2": <int, swipe end x - only for swipe>,
  "y2": <int, swipe end y - only for swipe>,
  "reasoning": "very short reason, under 15 words",
  "game_over": true or false
}
Coordinates are pixel positions within the screenshot you were given, not the phone's real
screen size - do not guess a different scale. Use "wait" if nothing useful can be done yet.
Use "give_up" if you genuinely cannot tell what move to make. Set game_over true if the
screenshot clearly shows a game-over/results/win screen rather than active gameplay.
"""
    game_hint_line = f"The user said this game is: {body.game_hint}." if body.game_hint else ""
    recent_line = f"Recent moves so far: {'; '.join(body.recent_moves)}." if body.recent_moves else "This is the first move."
    try:
        response = client.chat.completions.create(
            model="gpt-4.1-mini",
            response_format={"type": "json_object"},
            messages=[
                {"role": "system", "content": prompt},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": f"{game_hint_line} {recent_line}".strip()},
                        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{body.image_base64}"}},
                    ],
                },
            ],
        )
        raw = response.choices[0].message.content or "{}"
        decision = json.loads(raw)
        return {
            "action": decision.get("action", "wait"),
            "x": decision.get("x"),
            "y": decision.get("y"),
            "x2": decision.get("x2"),
            "y2": decision.get("y2"),
            "reasoning": decision.get("reasoning", ""),
            "game_over": bool(decision.get("game_over", False)),
        }
    except Exception as e:
        print("Error calling OpenAI (game_move):", e)
        return {"action": "give_up", "x": None, "y": None, "x2": None, "y2": None, "reasoning": "backend error", "game_over": False}


# ---- Laptop remote control relay ----
# Pure pass-through: pipes messages between exactly one Windows agent connection and one
# phone connection sharing the same pairing token. This process does not decode or store
# frames/commands - it only forwards bytes from one socket to the other. The token is the
# entire auth boundary (like a password) - keep min/max instances at 1 for this service so
# a token always maps to sockets held by the same process (no shared store needed).
class LaptopSession:
    def __init__(self) -> None:
        self.agent: Optional[WebSocket] = None
        self.phone: Optional[WebSocket] = None


LAPTOP_SESSIONS: Dict[str, LaptopSession] = {}


def _laptop_session(token: str) -> LaptopSession:
    return LAPTOP_SESSIONS.setdefault(token, LaptopSession())


async def _safe_send_text(ws: Optional[WebSocket], text: str) -> None:
    if ws is None:
        return
    try:
        await ws.send_text(text)
    except Exception:
        pass


async def _safe_send_bytes(ws: Optional[WebSocket], data: bytes) -> None:
    if ws is None:
        return
    try:
        await ws.send_bytes(data)
    except Exception:
        pass


async def _relay_loop(websocket: WebSocket, session: LaptopSession, is_agent: bool) -> None:
    try:
        while True:
            message = await websocket.receive()
            if message.get("type") == "websocket.disconnect":
                break
            target = session.phone if is_agent else session.agent
            text = message.get("text")
            data = message.get("bytes")
            if text is not None:
                await _safe_send_text(target, text)
            elif data is not None:
                await _safe_send_bytes(target, data)
    except WebSocketDisconnect:
        pass


@app.websocket("/laptop/ws/agent/{token}")
async def laptop_agent_ws(websocket: WebSocket, token: str) -> None:
    if len(token) < 12:
        await websocket.close(code=4001)
        return
    await websocket.accept()
    session = _laptop_session(token)
    session.agent = websocket
    await _safe_send_text(session.phone, json.dumps({"type": "agent_connected"}))
    await _safe_send_text(
        websocket,
        json.dumps({"type": "phone_connected" if session.phone else "phone_offline"}),
    )
    try:
        await _relay_loop(websocket, session, is_agent=True)
    finally:
        if session.agent is websocket:
            session.agent = None
        await _safe_send_text(session.phone, json.dumps({"type": "agent_disconnected"}))


@app.websocket("/laptop/ws/phone/{token}")
async def laptop_phone_ws(websocket: WebSocket, token: str) -> None:
    if len(token) < 12:
        await websocket.close(code=4001)
        return
    await websocket.accept()
    session = _laptop_session(token)
    session.phone = websocket
    await _safe_send_text(
        websocket,
        json.dumps({"type": "agent_connected" if session.agent else "agent_offline"}),
    )
    await _safe_send_text(session.agent, json.dumps({"type": "phone_connected"}))
    try:
        await _relay_loop(websocket, session, is_agent=False)
    finally:
        if session.phone is websocket:
            session.phone = None
        await _safe_send_text(session.agent, json.dumps({"type": "phone_disconnected"}))


# ---- Phone-to-phone remote control relay (Link to Phone) ----
# Same pure pass-through shape as the laptop relay above, deliberately kept as a fully separate
# class/dict/routes rather than reusing LaptopSession - zero shared state means this can't affect
# the already-working laptop feature. "agent" is the phone being controlled, "controller" is the
# phone doing the controlling (renamed from the laptop side's "phone" since both ends here are
# phones and that name would be ambiguous).
class PhoneSession:
    def __init__(self) -> None:
        self.agent: Optional[WebSocket] = None
        self.controller: Optional[WebSocket] = None


PHONE_SESSIONS: Dict[str, PhoneSession] = {}


def _phone_session(token: str) -> PhoneSession:
    return PHONE_SESSIONS.setdefault(token, PhoneSession())


async def _phone_relay_loop(websocket: WebSocket, session: PhoneSession, is_agent: bool) -> None:
    try:
        while True:
            message = await websocket.receive()
            if message.get("type") == "websocket.disconnect":
                break
            target = session.controller if is_agent else session.agent
            text = message.get("text")
            data = message.get("bytes")
            if text is not None:
                await _safe_send_text(target, text)
            elif data is not None:
                await _safe_send_bytes(target, data)
    except WebSocketDisconnect:
        pass


@app.websocket("/phone/ws/agent/{token}")
async def phone_agent_ws(websocket: WebSocket, token: str) -> None:
    if len(token) < 12:
        await websocket.close(code=4001)
        return
    await websocket.accept()
    session = _phone_session(token)
    session.agent = websocket
    await _safe_send_text(session.controller, json.dumps({"type": "agent_connected"}))
    await _safe_send_text(
        websocket,
        json.dumps({"type": "controller_connected" if session.controller else "controller_offline"}),
    )
    try:
        await _phone_relay_loop(websocket, session, is_agent=True)
    finally:
        if session.agent is websocket:
            session.agent = None
        await _safe_send_text(session.controller, json.dumps({"type": "agent_disconnected"}))


@app.websocket("/phone/ws/controller/{token}")
async def phone_controller_ws(websocket: WebSocket, token: str) -> None:
    if len(token) < 12:
        await websocket.close(code=4001)
        return
    await websocket.accept()
    session = _phone_session(token)
    session.controller = websocket
    await _safe_send_text(
        websocket,
        json.dumps({"type": "agent_connected" if session.agent else "agent_offline"}),
    )
    await _safe_send_text(session.agent, json.dumps({"type": "controller_connected"}))
    try:
        await _phone_relay_loop(websocket, session, is_agent=False)
    finally:
        if session.controller is websocket:
            session.controller = None
        await _safe_send_text(session.agent, json.dumps({"type": "controller_disconnected"}))


@app.post("/tts")
async def tts(body: TtsRequest):
    """Synthesizes speech via ElevenLabs and returns raw mp3 bytes. The Android client
    falls back to the on-device system voice if this returns anything other than 200."""
    if not ELEVENLABS_API_KEY or not ELEVENLABS_VOICE_ID:
        return Response(status_code=503, content=b"")

    try:
        resp = requests.post(
            f"https://api.elevenlabs.io/v1/text-to-speech/{ELEVENLABS_VOICE_ID}",
            headers={
                "xi-api-key": ELEVENLABS_API_KEY,
                "Content-Type": "application/json",
            },
            json={
                "text": body.text,
                "model_id": "eleven_multilingual_v2",
                "voice_settings": {"stability": 0.5, "similarity_boost": 0.75},
            },
            timeout=30,
        )
        if resp.status_code != 200:
            print("ElevenLabs error:", resp.status_code, resp.text[:300])
            return Response(status_code=502, content=b"")
        return Response(content=resp.content, media_type="audio/mpeg")
    except Exception as e:
        print("TTS error:", e)
        return Response(status_code=500, content=b"")