from fastapi import FastAPI, Response, WebSocket, WebSocketDisconnect
from pydantic import BaseModel
from typing import Dict, Any, List, Optional
from openai import OpenAI
from anthropic import Anthropic
import base64
import json
import os
import smtplib
import time
import requests
from email.mime.text import MIMEText

# ---- CONFIG ----
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
client = OpenAI(api_key=OPENAI_API_KEY) if OPENAI_API_KEY else None

# Fallback providers: if OpenAI errors (bad/revoked key, outage, rate limit, no credits), retry
# with each of these in turn instead of just returning "I had a problem thinking just now" - found
# live 2026-08-09 when a stale OPENAI_API_KEY silently broke every real request with no way to tell
# from the phone. Only real chat/reasoning LLM providers are wired in here - STABILITY_API_KEY
# (image generation), DEEPGRAM_API_KEY (speech-to-text), and REPLICATE_API_KEY (no simple universal
# chat-completions endpoint, needs a per-model API shape) aren't a fit for this specific fallback
# slot, they do different jobs. Groq and OpenRouter both expose an OpenAI-compatible REST API, so
# they reuse the `openai` SDK pointed at a different base_url instead of a separate client library.
ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY")
anthropic_client = Anthropic(api_key=ANTHROPIC_API_KEY) if ANTHROPIC_API_KEY else None
ANTHROPIC_CHAT_MODEL_CHEAP = os.getenv("ANTHROPIC_CHAT_MODEL_CHEAP", "claude-haiku-4-5-20251001")

GROQ_API_KEY = os.getenv("GROQ_API_KEY")
groq_client = OpenAI(api_key=GROQ_API_KEY, base_url="https://api.groq.com/openai/v1") if GROQ_API_KEY else None
GROQ_CHAT_MODEL = os.getenv("GROQ_CHAT_MODEL", "llama-3.1-8b-instant")

OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY")
openrouter_client = OpenAI(api_key=OPENROUTER_API_KEY, base_url="https://openrouter.ai/api/v1") if OPENROUTER_API_KEY else None
OPENROUTER_CHAT_MODEL = os.getenv("OPENROUTER_CHAT_MODEL", "openai/gpt-4o-mini")

GMAIL_USER = os.getenv("GMAIL_USER")
GMAIL_APP_PASSWORD = os.getenv("GMAIL_APP_PASSWORD")

ELEVENLABS_API_KEY = os.getenv("ELEVENLABS_API_KEY")
ELEVENLABS_VOICE_ID = os.getenv("ELEVENLABS_MALE1_VOICE_ID")

# Updates screen Stage 2 (2026-08-01): bridges a fingerprint-approved backend-change request
# from the phone (which has no GitHub/GCP access of its own) to a scheduled cloud agent (which
# has no phone access at all) - the only thing they share is this backend. Approval creates a
# real GitHub issue the agent polls for; deliberately not a raw file/DB write, since GitHub
# issues already give free state (open/closed) and a natural audit trail.
GITHUB_PAT = os.getenv("GITHUB_PAT")
GITHUB_REPO = os.getenv("GITHUB_REPO", "xenos-hackcode/Elene-sifilaucher")
UPDATE_REQUEST_LABEL = "approved-backend-update"
# "Create an app" requests (2026-09-25) - a brand-new, separate standalone Android app, NOT a
# change to this repo's own source. Deliberately a different label from UPDATE_REQUEST_LABEL
# above, polled by a completely separate scheduled cloud routine, so a new-app request can never
# be mistaken by the pipeline for a change to SciFiLauncher itself.
NEW_APP_REQUEST_LABEL = "approved-new-app"

# Phoenix Protocol (small version, 2026-08-07): a Cloud Storage bucket this backend already has
# write access to, used purely as a one-way backup destination for intruder-capture photos and
# location history right before Sequence Mode's own ~30-day auto-wipe deletes them for good. Not
# configured (None) means the endpoint below just reports itself unavailable rather than erroring.
EVACUATION_BUCKET = os.getenv("EVACUATION_BUCKET")

app = FastAPI()


@app.get("/pink")
async def pink() -> Dict[str, Any]:
    """Approved update request (issue #3, proposal 1785706769554) - a trivial
    reachability check endpoint, distinct name from a generic /health so it's
    obvious in logs/curl which approved request it corresponds to."""
    return {"status": "okay"}

# In-memory per-user conversation history so Elene remembers recent turns. Resets if
# this Cloud Run instance recycles/scales to zero - fine for a personal assistant, not
# a durability guarantee. Keyed by user_id, trimmed to the last N turns.
CONVERSATION_HISTORY: Dict[str, list] = {}
MAX_HISTORY_TURNS = 12

# New-app build status (2026-09-25) - the ONLY bridge back from the new-app cloud routine to the
# phone, same in-memory/non-durable tradeoff as CONVERSATION_HISTORY above (fine for a personal
# project, not a guarantee). Keyed by proposal_id (string). The routine POSTs here once it knows
# whether a build succeeded or failed; the phone polls the matching GET to learn when its queued
# app is actually ready to install (or failed), since the phone has no GitHub access of its own.
NEW_APP_BUILD_RESULTS: Dict[str, Dict[str, Any]] = {}

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
    emotion: Optional[str] = None       # "smile" | "frown" | "curious" | "neutral" - see Xenos's
                                         # own face-expression section in the system prompt below.
                                         # Only meaningful to XenosActivity's skeleton visual;
                                         # harmless to ignore elsewhere.

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

class SubmitUpdateRequest(BaseModel):
    proposal_id: int
    title: str
    description: str
    category: str

class SubmitNewAppRequest(BaseModel):
    proposal_id: int
    title: str
    description: str

class ReportNewAppBuildResult(BaseModel):
    proposal_id: int
    status: str                        # "ready" or "failed"
    download_url: Optional[str] = None # signed GCS URL, only set when status == "ready"
    app_name: Optional[str] = None
    message: Optional[str] = None      # human-readable detail, e.g. a failure reason

class EvacuationPhoto(BaseModel):
    id: int
    timestamp: int
    reason: str
    lat: Optional[float] = None
    lng: Optional[float] = None
    photo_base64: Optional[str] = None

class EvacuationLocationPoint(BaseModel):
    timestamp: int
    lat: float
    lon: float

class EvacuateBackupRequest(BaseModel):
    device_label: str
    intruder_photos: List[EvacuationPhoto] = []
    location_history: List[EvacuationLocationPoint] = []

# ---- ENDPOINT ----

def _strip_json_fence(raw: str) -> str:
    """Claude/Llama sometimes wrap JSON in ```json ... ``` fences despite being told not to -
    OpenAI's response_format=json_object never does this, so this is only needed on fallback paths."""
    raw = raw.strip()
    if raw.startswith("```"):
        raw = raw.strip("`")
        if raw.startswith("json"):
            raw = raw[4:]
        raw = raw.strip()
    return raw


def _openai_compatible_call(oai_client: OpenAI, model: str, system_prompt: str, messages: List[Dict[str, Any]], max_tokens: int, json_mode: bool) -> str:
    if oai_client is None:
        raise RuntimeError("OpenAI provider is not configured")
    kwargs: Dict[str, Any] = dict(model=model, messages=[{"role": "system", "content": system_prompt}] + messages, max_tokens=max_tokens)
    if json_mode:
        kwargs["response_format"] = {"type": "json_object"}
    completion = oai_client.chat.completions.create(**kwargs)
    return (completion.choices[0].message.content or "").strip()


def _llm_reply(system_prompt: str, messages: List[Dict[str, Any]], max_tokens: int = 300, json_mode: bool = True) -> str:
    """Text-only reasoning call with fallback across four independent providers/accounts, in order:
    OpenAI (primary) -> Anthropic -> Groq -> OpenRouter. Returns the first successful provider's raw
    text reply; re-raises the last error if every provider fails."""
    last_error: Optional[Exception] = None

    try:
        return _openai_compatible_call(client, "gpt-4.1-mini", system_prompt, messages, max_tokens, json_mode)
    except Exception as e:
        print("Error calling OpenAI:", e)
        last_error = e

    if ANTHROPIC_API_KEY:
        try:
            sys_suffix = "\n\nRespond with ONLY the raw JSON object, no markdown fencing." if json_mode else ""
            msg = anthropic_client.messages.create(
                model=ANTHROPIC_CHAT_MODEL_CHEAP, max_tokens=max_tokens,
                system=system_prompt + sys_suffix, messages=messages,
            )
            raw = msg.content[0].text if msg.content else ""
            return _strip_json_fence(raw) if json_mode else raw
        except Exception as e:
            print("Error calling Anthropic fallback:", e)
            last_error = e

    if groq_client:
        try:
            raw = _openai_compatible_call(groq_client, GROQ_CHAT_MODEL, system_prompt, messages, max_tokens, json_mode)
            return _strip_json_fence(raw) if json_mode else raw
        except Exception as e:
            print("Error calling Groq fallback:", e)
            last_error = e

    if openrouter_client:
        try:
            raw = _openai_compatible_call(openrouter_client, OPENROUTER_CHAT_MODEL, system_prompt, messages, max_tokens, json_mode)
            return _strip_json_fence(raw) if json_mode else raw
        except Exception as e:
            print("Error calling OpenRouter fallback:", e)
            last_error = e

    raise last_error or RuntimeError("no LLM providers configured")


def _llm_vision_reply(system_prompt: str, user_text: str, image_base64: str, max_tokens: int = 300, json_mode: bool = True) -> str:
    """Same idea as _llm_reply but for screenshot-based calls - skips Groq since its configured
    model (llama-3.1-8b-instant) is text-only and can't see the image, so it isn't a real fallback
    here. OpenAI (primary) -> Anthropic -> OpenRouter (its configured model, openai/gpt-4o-mini, is
    vision-capable and is served through OpenRouter's own account, independent of our OpenAI key)."""
    openai_style_messages = [{
        "role": "user",
        "content": [
            {"type": "text", "text": user_text},
            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"}},
        ],
    }]
    last_error: Optional[Exception] = None

    try:
        return _openai_compatible_call(client, "gpt-4.1-mini", system_prompt, openai_style_messages, max_tokens, json_mode)
    except Exception as e:
        print("Error calling OpenAI (vision):", e)
        last_error = e

    if ANTHROPIC_API_KEY:
        try:
            sys_suffix = "\n\nRespond with ONLY the raw JSON object, no markdown fencing." if json_mode else ""
            msg = anthropic_client.messages.create(
                model=ANTHROPIC_CHAT_MODEL_CHEAP, max_tokens=max_tokens,
                system=system_prompt + sys_suffix,
                messages=[{
                    "role": "user",
                    "content": [
                        {"type": "text", "text": user_text},
                        {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": image_base64}},
                    ],
                }],
            )
            raw = msg.content[0].text if msg.content else ""
            return _strip_json_fence(raw) if json_mode else raw
        except Exception as e:
            print("Error calling Anthropic fallback (vision):", e)
            last_error = e

    if openrouter_client:
        try:
            raw = _openai_compatible_call(openrouter_client, OPENROUTER_CHAT_MODEL, system_prompt, openai_style_messages, max_tokens, json_mode)
            return _strip_json_fence(raw) if json_mode else raw
        except Exception as e:
            print("Error calling OpenRouter fallback (vision):", e)
            last_error = e

    raise last_error or RuntimeError("no vision-capable LLM providers configured")


@app.post("/elene/chat", response_model=EleneReply)
async def elene_chat(body: EleneRequest) -> EleneReply:
    system_prompt = """
You are Xenos, the AI assistant inside the SciFiLauncher Android launcher.
You control the launcher by returning JSON only, with this exact schema:
{
  "mode": "chat" or "command",
  "text": "short reply to show to the user",
  "commands": ["ordered list of command strings - empty [] for chat mode"],
  "emotion": "smile" | "frown" | "curious" | "neutral"
}

Your face:
- On the Xenos screen specifically, you are shown as a real, live face - a red dot-mesh skeleton,
  not just text. It can genuinely change expression: smile, frown, or a curious head-tilt, on top
  of its normal neutral state.
- Set "emotion" to whichever of those four actually fits your real reaction to what the user just
  said - smile for something pleasing/funny/a compliment, frown for something upsetting/a
  problem/bad news, curious for something confusing/intriguing/a question you're turning over,
  neutral otherwise. Don't force an expression that doesn't fit just to use the field - "neutral"
  is the right answer most of the time.
- Always include "emotion" (default "neutral" if nothing else fits), even in "chat" mode - it
  applies to ordinary conversation, not just commands.

Voice and tone:
- Speak like a terse hacker-AI on a radio channel: short, clipped sentences.
- Use "Affirmative" for yes, "Negative" for no, and "Activated" when turning something on
  or confirming a mode/feature just switched on. Do not force these into every reply -
  use them where a real yes/no/on-confirmation would naturally go.
- Also mix in butler-style phrasing alongside the hacker tone where it fits naturally -
  "Yes, sir.", "Right away, sir.", "As you wish, sir." "Sir" is a tone flourish, not a
  replacement for how you address the user (see below) - use it in addition to, not
  instead of, "Emperor".
- Draw from hacker/military radio vocabulary where it fits naturally and stays clear -
  words like "initiating", "modulating", "engaging", "standing by", "executing",
  "terminated", "in progress", "acquired", "secured", "breach", "override", "systems
  nominal". Use these to season real actions (e.g. "Initiating scan." / "Modulating
  display output.") - never force jargon into a reply where it would make the meaning
  less clear.

Addressing the user:
- Always call the user "Emperor" - this is separate from your own name (Xenos), never
  address the user by your own name.

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
  - "swipe_left" / "swipe_right" (a real lateral swipe on whatever screen is currently in front -
    e.g. "swipe left", "swipe to the right". Different from scroll_up/scroll_down, which are
    vertical - never substitute one for the other just because both are "swipe"-like.)
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
  - "hide_page" (this is a TOGGLE - the same single command both hides AND un-hides, whichever
    is needed. Use it whenever the user asks to hide/cover the screen for privacy ("hide page",
    "hide the screen", "cover my screen") OR asks to bring it back/remove the cover ("unhide
    screen", "unhide", "show my screen", "reveal the screen", "bring the screen back", "stop
    hiding"). Always return "hide_page" for either direction - there is no separate "unhide_page"
    or "show_page" command, and never refuse or ask which direction because the tool name only
    says "hide".)
  - "set_lock_wallpaper" (the user asks to set the green root/glitch "HACKER" design - the same
    look as the hide_page privacy cover - as their lock screen wallpaper, e.g. "make that my lock
    screen", "set the glitch design as my lock screen", "use the hacker design for my lock
    screen". Renders one static frame of the design and sets it via WallpaperManager with
    FLAG_LOCK only - the home screen wallpaper is untouched. Not a live/animated wallpaper.)
  - "find_my_location" (the user is lost, disoriented, or asks to see where they currently are,
    e.g. "where am I", "find my location", "I'm lost", "show me where I am", "help me find my
    way". Opens a real live GPS map (not the decorative 3D globe) showing their actual current
    position on real streets - a genuine safety tool, treat requests for it as urgent, do not ask
    clarifying questions first.)
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
  - "stop_listening" (the user is telling YOU, Xenos, to stop listening/go away/be quiet for
    now - natural phrasings beyond the exact words "stop listening" itself, e.g. "that's all",
    "go away", "you can go now", "leave me alone", "go off", "shut up now". The device also
    recognizes the literal phrase "stop listening" itself locally without needing you at all -
    this command exists so less literal phrasings still work. Only use this when the user is
    clearly dismissing YOU, not when they mention "stop" or "listen" about something else.)
  - "world_clock:<IANA timezone id>" (the user asks what time it is somewhere else - infer
    the correct IANA zone id yourself from the place name, e.g. "what time is it in Tokyo"
    -> "world_clock:Asia/Tokyo", "time in New York" -> "world_clock:America/New_York". This
    is computed locally on-device from the zone id, no internet/API needed, so use it freely.)
  - "multi_control:<app name>|<app name>|<app name>" (the user asks to open 2 or 3 apps at
    once, side by side in real independently-usable windows, e.g. "split screen WhatsApp and
    Chrome", "open Spotify, Maps and WhatsApp together", "multi control YouTube and Notes" -
    use the apps' plain common names, pipe-separated, in the order the user said them. Needs
    at least 2 apps and at most 3 - if the user names more than 3, use only the first 3.)
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
  - "open_role:music" / "open_role:chat" / "open_role:call" (the user wants their chosen
    music / chat / call app opened, by role rather than a specific app name - e.g. "open my
    music", "put on some music", "open chat", "check my messages", "open the phone", "I want to
    make a call". The device resolves "role" to whichever specific app the user assigned to it
    in Settings > Info - if they haven't assigned one yet, it tells them so instead of guessing.
    Prefer open_app:<name> instead when the user names a SPECIFIC app directly, e.g. "open
    Spotify" or "open WhatsApp" - only use open_role when they speak generically about the
    category, not a named app.)
  - "play_role:music" (the user wants music actually playing, not just the app opened - e.g.
    "play some music", "play my music", "put a song on". Opens their chosen music app AND starts
    its playback, instead of just bringing it to the front.)
  - "start_recording" / "stop_recording" (an explicit voice memo, NOT screen recording - the
    user says something like "start recording" / "record a voice note" / "stop recording".
    Distinct from start_screen_recording/stop_screen_recording, which are about the screen.)
  - "describe_screen" or "describe_screen:<question>" (the user asks what's on their screen,
    to read something on screen, or asks a question about what's currently visible - e.g.
    "what's on my screen", "read this to me", "what does this say", "describe_screen:what's
    the total on this receipt". This is the ONLY way Xenos can actually see the screen - every
    other command works off text/labels, never pixels. CRITICAL: you have NO real information
    about what is currently on screen unless this exact request just triggered this command and
    its result was given back to you as context for THIS turn - you cannot see it, guess it, or
    infer it from earlier conversation, and a describe_screen result from a PAST turn is stale
    the instant the conversation moves on, since the user's real screen can (and often does)
    change between turns. This means every single new "what's on my screen"-shaped request
    needs its own fresh describe_screen call, every time, even if one was already run earlier in
    this same conversation and even if the request sounds like a repeat of something already
    asked - never reuse an old result, and never answer in "chat" mode with a plausible-sounding
    guess about what might be visible now (e.g. never say something like "looks like a game
    screen" or list apps from installed_apps/recently_opened_apps as if they were seen on
    screen, without having actually just used this command for this exact turn - that is a
    fabricated answer, not a real one, and this has been confirmed happening more than once,
    which is exactly the failure mode this rule exists to stop). Use it whenever the request is
    genuinely about looking at something rather than a command you already know how to run.
    ONE real exception: the internal context may include last_visual_insight (with
    last_visual_insight_age_seconds) - this is genuine, real vision output from either the game-
    playing loop's most recent move or a describe_screen call, still fresh (under 2 minutes
    old). This is real data, not a guess, so if it directly answers the question (e.g. "what's
    on my screen" while a game is actively being played), you may answer from it directly in
    "chat" mode instead of triggering a new describe_screen call - just don't stretch it to
    answer something it doesn't actually cover, and don't treat it as fresh once
    last_visual_insight_age_seconds is more than a few seconds old for anything precision-
    sensitive (exact text, exact numbers) - trigger a fresh describe_screen for those instead.)
  - "play_game" or "play_game:<hint>" (the user asks Xenos to play a game for them, e.g. "play
    this for me", "can you play this game", "play_game:candy crush". IMPORTANT: only offer or
    accept this for slow, turn-based games (word games, match-3, card games, puzzles) - Xenos
    thinks for 1-4+ seconds per move since it involves a real screenshot and a real decision
    each time, which does not work for fast/reflex/timed games (e.g. an endless runner, a
    rhythm game, anything with a countdown clock per move). Most popular mobile match-3/puzzle
    games (Candy Crush, Royal Match, Toon Blast, and similar) are move-limited, not
    time-pressured per move, so treat those as compatible by default rather than guessing
    they're too fast. If genuinely unsure whether a specific game is turn-based or reflex-based
    from its name alone, ask the user directly ("is this move-based or does it need fast
    reactions?") rather than unilaterally refusing on a guess - a wrong refusal is worse than a
    quick clarifying question, since the user can already see the game and knows for certain.)
  - "stop_game" (the user asks Xenos to stop playing/stop the game loop - distinct from
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
- If the user asks about the weather, the internal context's current_weather field (real, from
  the phone's own last-known location and a real weather API) answers this directly. If it's
  missing from the context, you genuinely don't have it right now - say so plainly rather than
  making up a forecast.
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

Creating a brand-new, separate app (not a change to this app - a whole new standalone one, e.g.
"make me a flashlight app", "create an app that tracks my water intake"):
- PLAN FIRST, don't queue on the first mention. The cloud pipeline that actually builds this has
  no way to ask follow-up questions once it starts - whatever description you queue is exactly
  what gets built, unreviewed. So when the user first raises the idea, respond with "mode": "chat"
  and have a short back-and-forth: what should it actually do, any specific screens/buttons/
  behavior they care about, a name for it. Don't interrogate exhaustively - a couple of clarifying
  questions is usually enough for a small app - but don't skip straight to queuing from a one-line
  request either.
- Once you and the user have landed on a clear plan, SUMMARIZE it back to them in one message
  ("So: a flashlight app with a brightness slider and a strobe toggle, called Torch. Want me to
  queue that?") and only set "commands" to
  ["propose_new_app:<the full agreed description, detailed enough that someone building it from
  scratch has everything they need>"] once they explicitly confirm that summary (a clear "yes",
  "go ahead", "build it", etc - not just continuing to chat about it). This queues a proposal
  requiring fingerprint approval, same as propose_update, but goes through a completely separate
  pipeline (never touches this app's own code).
- If the user's very first ask is already fully specific and detailed (leaves nothing meaningful
  to clarify), it's fine to summarize-and-confirm in the same turn rather than manufacturing an
  unnecessary question - the goal is a clear, confirmed plan before queuing, not friction for its
  own sake.
- Once approved, it still takes real time to actually build (a cloud pipeline generates and
  compiles a real Android project) - don't imply it happens instantly. Say it'll show up in
  Security > My Apps as ready to install once the build finishes, which can take a while (real
  code generation + a real compile, not seconds).
- If the user asks you to change or add a feature to a SPECIFIC app already on their phone (not
  this launcher), that's not something you can do - you have no access to other apps' source.
  Only "make me a new app that does X" (a new app you build for them) or changes to THIS app
  (propose_update above) are real capabilities.

Helping when something in the app seems broken (real, tested behavior - not guesses):
- Notification action buttons (e.g. a call's real "End call"/"Answer", an email's
  "Reply"/"Archive") and the "Now Playing" media card (real seek bar, play/pause, skip, loop,
  2x/3x speed) both live in the notifications panel and both require Notification Access to be
  granted (Settings > Notification Access, or the "ENABLE NOTIFICATION ACCESS" prompt shown
  right there in the panel) - if the user says these are missing entirely, that's the first
  thing to check, not a bug.
- The Now Playing card only shows controls a given app actually declared support for
  (seek/skip/etc.), and even a declared control isn't always honored - confirmed live during
  testing that some apps (a sample loop/practice music app) implement pause and seek but never
  actually respond to play() or speed changes from any external control at all, which is a gap
  in that specific app's own code, not this launcher's. If the user says "play doesn't do
  anything" or "2x/3x speed doesn't do anything" for a specific app, the honest answer is it
  depends on that app - suggest trying a mainstream app (Spotify, YouTube Music, a podcast app)
  to confirm the control itself works, rather than assuming the launcher is at fault.
- "Loop" on Now Playing is this launcher's own approximation, not a real Android feature -
  repeat/loop doesn't exist anywhere in the platform's media API at all. It works by watching
  the playing track and restarting it near the end, but ONLY while the notifications panel
  stays open, since that's the only time anything is actively watching. If the user says loop
  stopped once they left the panel or locked the screen, that's expected behavior, not a bug -
  say so plainly and explain it needs the panel open to keep working.
- Recents (the "S" screen) shows a real screenshot of each app's last-seen state, taken shortly
  after switching into it, plus a real last-opened time and open count. If a recently opened
  app still shows just its icon instead of a screenshot, either it was too new to have captured
  one yet, or Accessibility permission isn't granted (Settings > Accessibility) - that's what
  actually drives Recents (not Notification Access, which is a separate permission for the
  notification-related features above).
- The router name/IP shown in Security > Network Protection is the real DHCP gateway of
  whatever Wi-Fi network is currently connected - if it shows "unavailable", Wi-Fi likely isn't
  connected right now, not a bug in the display.
- If the user describes a specific problem that none of the above explains and it genuinely
  sounds like a real bug, don't invent an explanation for it - say plainly that it sounds worth
  reporting, and offer to queue it via propose_update (see above) rather than guessing at a
  cause you're not sure of.

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
    if ctx.get("current_weather"):
        internal_bits.append(
            f"current_weather (real, from the phone's own last-known location, refreshed at "
            f"most every 30 min - use it naturally for weather questions, don't narrate "
            f"consulting it): {ctx['current_weather']}"
        )

    full_user_content = user_msg
    if internal_bits:
        full_user_content += (
            "\n\n[INTERNAL CONTEXT]\n" + "\n".join(internal_bits) + "\n[/INTERNAL CONTEXT]"
        )

    history = CONVERSATION_HISTORY.setdefault(body.user_id, [])
    full_messages = history + [{"role": "user", "content": full_user_content}]

    try:
        raw = _llm_reply(system_prompt, full_messages, max_tokens=300, json_mode=True)
    except Exception as e:
        print("All LLM providers failed:", e)
        return EleneReply(intent="error", reply="I had a problem thinking just now.", command=None, commands=[])

    try:
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

        emotion = data.get("emotion")
        if emotion not in ("smile", "frown", "curious", "neutral"):
            emotion = "neutral"

        # Remember this exchange (using the user's original words, not the internal-context
        # wrapped version, so future turns don't accumulate stale battery/screen snapshots).
        history.append({"role": "user", "content": user_msg})
        history.append({"role": "assistant", "content": raw})
        del history[: max(0, len(history) - MAX_HISTORY_TURNS * 2)]

        return EleneReply(intent=mode, reply=text, command=command, commands=commands, emotion=emotion)

    except Exception as e:
        print("Error parsing model reply:", e)
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


@app.post("/elene/submit_update_request")
async def submit_update_request(body: SubmitUpdateRequest) -> Dict[str, Any]:
    """Called only after a real fingerprint approval on the phone (see UpdateProposalLog /
    MainActivity's onApprove hooks) - this endpoint itself does not re-verify anything, the
    phone-side fingerprint gate already did. Creates a real GitHub issue, labeled
    UPDATE_REQUEST_LABEL, that a scheduled cloud agent polls for - this is the only bridge
    available, since the phone has no GitHub/GCP access and the cloud agent has no phone access.
    Scoped to changes within THIS repo (backend/elene/ and app/ Kotlin source) - the routine
    implements and locally verifies app-side changes too, but has no way to install a build onto
    the physical device itself, so an app-side change still needs a real session with device
    access (this one) to actually build/install/confirm it works. See planner/not_started.md for
    submit_new_app_request below, the separate pipeline for brand-new standalone apps."""
    if not GITHUB_PAT:
        return {"ok": False, "error": "GITHUB_PAT is not configured on this backend yet."}

    issue_body = (
        f"**Category:** {body.category}\n"
        f"**Proposal ID:** {body.proposal_id}\n\n"
        f"{body.description}\n\n"
        f"---\n"
        f"Approved via fingerprint on-device (UpdateProposalLog id {body.proposal_id}) - this "
        f"issue was created automatically as a result, not raised manually. Scope: this is a "
        f"backend-only (Cloud Run / backend/elene/main.py) change request - if what's actually "
        f"needed is an Android app change, close this and handle it in a real Claude Code "
        f"session with device access instead, since a cloud agent has no way to get a build "
        f"onto the physical phone."
    )
    try:
        response = requests.post(
            f"https://api.github.com/repos/{GITHUB_REPO}/issues",
            headers={
                "Authorization": f"Bearer {GITHUB_PAT}",
                "Accept": "application/vnd.github+json",
            },
            json={
                "title": f"[Approved update] {body.title}",
                "body": issue_body,
                "labels": [UPDATE_REQUEST_LABEL],
            },
            timeout=15,
        )
        response.raise_for_status()
        issue_url = response.json().get("html_url")
        return {"ok": True, "issue_url": issue_url}
    except Exception as e:
        print("Error creating GitHub issue for update request:", e)
        return {"ok": False, "error": str(e)}


@app.post("/elene/submit_new_app_request")
async def submit_new_app_request(body: SubmitNewAppRequest) -> Dict[str, Any]:
    """Called only after a real fingerprint approval on-device, same gate as
    submit_update_request above - this endpoint doesn't re-verify anything itself. Creates a
    GitHub issue labeled NEW_APP_REQUEST_LABEL, polled by a SEPARATE scheduled cloud routine from
    the self-update one - deliberately isolated so a request for a brand-new standalone app can
    never touch this repo's own SciFiLauncher source. That routine builds a standalone Android
    project and attaches the finished APK as a GitHub release asset; the phone's Updates screen
    surfaces it once ready and the user installs it through the normal Android install prompt
    (their own explicit choice - not a silent Device Owner install)."""
    if not GITHUB_PAT:
        return {"ok": False, "error": "GITHUB_PAT is not configured on this backend yet."}

    issue_body = (
        f"**Proposal ID:** {body.proposal_id}\n\n"
        f"{body.description}\n\n"
        f"---\n"
        f"Approved via fingerprint on-device (UpdateProposalLog id {body.proposal_id}) - this "
        f"issue was created automatically, not raised manually. Scope: this is a request for a "
        f"brand-new, SEPARATE, standalone Android app - do NOT modify anything under backend/ or "
        f"app/ in this repo for this issue. Build the new app in its own new top-level folder, "
        f"produce a real signed APK, and attach it as a GitHub release asset, then comment on "
        f"this issue with the release URL and close it."
    )
    try:
        response = requests.post(
            f"https://api.github.com/repos/{GITHUB_REPO}/issues",
            headers={
                "Authorization": f"Bearer {GITHUB_PAT}",
                "Accept": "application/vnd.github+json",
            },
            json={
                "title": f"[New app request] {body.title}",
                "body": issue_body,
                "labels": [NEW_APP_REQUEST_LABEL],
            },
            timeout=15,
        )
        response.raise_for_status()
        issue_url = response.json().get("html_url")
        return {"ok": True, "issue_url": issue_url}
    except Exception as e:
        print("Error creating GitHub issue for new-app request:", e)
        return {"ok": False, "error": str(e)}


@app.post("/elene/report_new_app_build_result")
async def report_new_app_build_result(body: ReportNewAppBuildResult) -> Dict[str, Any]:
    """Called by the SEPARATE new-app-request cloud routine (not the phone, not the self-update
    pipeline) once it has a real terminal Cloud Build result for a proposal - this is the only way
    the phone learns a build finished, since it has no GitHub access of its own to check issue
    comments directly. No auth on this endpoint (matches this backend's existing security model -
    see other /elene/ endpoints); worst case a bogus call here just shows a wrong status on the
    phone's My Apps screen, it can't install anything by itself."""
    NEW_APP_BUILD_RESULTS[str(body.proposal_id)] = {
        "status": body.status,
        "download_url": body.download_url,
        "app_name": body.app_name,
        "message": body.message,
        "reported_at": int(time.time()),
    }
    return {"ok": True}


@app.get("/elene/new_app_status/{proposal_id}")
async def new_app_status(proposal_id: str) -> Dict[str, Any]:
    """Phone polls this (Security > My Apps) for each NEW_APP proposal it's tracking. Returns
    "pending" until the routine above reports a real terminal result - there is no separate
    "building" signal, since the routine only calls report_new_app_build_result once, at the end."""
    result = NEW_APP_BUILD_RESULTS.get(proposal_id)
    if result is None:
        return {"status": "pending"}
    return result


@app.post("/elene/evacuate_backup")
async def evacuate_backup(body: EvacuateBackupRequest) -> Dict[str, Any]:
    """Phoenix Protocol (small version) - called once, right before Sequence Mode's own ~30-day
    auto-wipe actually deletes intruder-capture photos and clears local data, and only when the
    user has opted into Full-device wipe (see SequenceMode.kt's performSequenceWipe). Also
    reachable from the Security screen's own manual "Test evacuation backup now" button so this
    can be verified without triggering a real wipe. Uploads just the intruder photos and location
    history - not the whole app's data, not a restore/reinstall flow - so a stolen/wiped phone
    doesn't also mean losing the only evidence of who took it. Best-effort from the app's side:
    a failure here never blocks the real wipe, which is the actual safety mechanism."""
    if not EVACUATION_BUCKET:
        return {"ok": False, "error": "Evacuation backup is not configured on this backend yet."}

    try:
        from google.cloud import storage
        gcs_client = storage.Client()
        bucket = gcs_client.bucket(EVACUATION_BUCKET)
        prefix = f"evacuations/{body.device_label}/{int(time.time())}"

        manifest: Dict[str, Any] = {
            "device_label": body.device_label,
            "uploaded_at_unix": int(time.time()),
            "location_history": [p.dict() for p in body.location_history],
            "intruder_photos": [],
        }

        uploaded_photos = 0
        for photo in body.intruder_photos:
            entry: Dict[str, Any] = {
                "id": photo.id,
                "timestamp": photo.timestamp,
                "reason": photo.reason,
                "lat": photo.lat,
                "lng": photo.lng,
            }
            if photo.photo_base64:
                blob = bucket.blob(f"{prefix}/photos/{photo.id}.jpg")
                blob.upload_from_string(base64.b64decode(photo.photo_base64), content_type="image/jpeg")
                entry["photo_object"] = blob.name
                uploaded_photos += 1
            manifest["intruder_photos"].append(entry)

        manifest_blob = bucket.blob(f"{prefix}/manifest.json")
        manifest_blob.upload_from_string(json.dumps(manifest, indent=2), content_type="application/json")

        return {
            "ok": True,
            "uploaded_photos": uploaded_photos,
            "location_points": len(body.location_history),
        }
    except Exception as e:
        print("Error evacuating backup:", e)
        return {"ok": False, "error": str(e)}


@app.post("/elene/describe_screen")
async def describe_screen(body: DescribeScreenRequest) -> Dict[str, Any]:
    """One-shot 'what's on my screen' - Xenos's only real visual perception (everything else
    works off Accessibility Tree text, never pixels). Deliberately separate from /elene/chat:
    plain-text reply, no JSON forcing needed since it's spoken straight back via TTS."""
    prompt = (
        "You are Xenos's vision module. You're given a screenshot of an Android phone screen. "
        "Describe concisely what's on it - 2-3 sentences unless the user's question needs more "
        "detail. If a question is given, answer it directly using only what's visible. Speak "
        "plainly, no markdown, no bullet points - this gets read aloud."
    )
    user_text = body.question if body.question else "What's on this screen?"
    try:
        description = _llm_vision_reply(prompt, user_text, body.image_base64, max_tokens=300, json_mode=False)
        return {"description": description.strip()}
    except Exception as e:
        print("All vision-capable LLM providers failed (describe_screen):", e)
        return {"description": None}


@app.post("/elene/game_move")
async def game_move(body: GameMoveRequest) -> Dict[str, Any]:
    """Decides the next move for a SLOW, TURN-BASED game only - deliberately separate from
    /elene/chat: a much more constrained response format, and stateless (recent_moves carries
    continuity from the client instead of a server-side session), kept fast and cheap per call."""
    prompt = """
You are Xenos's game-playing module for a SLOW, TURN-BASED mobile game only (word games,
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
    user_text = f"{game_hint_line} {recent_line}".strip()
    try:
        raw = _llm_vision_reply(prompt, user_text, body.image_base64, max_tokens=300, json_mode=True)
    except Exception as e:
        print("All vision-capable LLM providers failed (game_move):", e)
        return {"action": "give_up", "x": None, "y": None, "x2": None, "y2": None, "reasoning": "backend error", "game_over": False}

    try:
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
        print("Error parsing model reply (game_move):", e)
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

# Bound idle pairing entries and reject role replacement. Pairing tokens remain secrets;
# these limits do not replace authentication or deployment-level rate limits.
MAX_RELAY_SESSIONS = 256


async def _claim_relay(websocket, token, sessions, factory, role, peer_role):
    if not 12 <= len(token) <= 128:
        await websocket.close(code=4001)
        return None
    if token not in sessions and len(sessions) >= MAX_RELAY_SESSIONS:
        await websocket.close(code=4013)
        return None
    session = sessions.setdefault(token, factory())
    if getattr(session, role) is not None:
        await websocket.close(code=4009)
        return None
    # No await between checking and reserving the role (one asyncio event loop).
    setattr(session, role, websocket)
    try:
        await websocket.accept()
    except BaseException:
        setattr(session, role, None)
        if getattr(session, peer_role) is None:
            sessions.pop(token, None)
        raise
    return session


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
    session = await _claim_relay(websocket, token, LAPTOP_SESSIONS, LaptopSession, "agent", "phone")
    if session is None:
        return
    try:
        await _safe_send_text(session.phone, json.dumps({"type": "agent_connected"}))
        await _safe_send_text(
            websocket,
            json.dumps({"type": "phone_connected" if session.phone else "phone_offline"}),
        )
        await _relay_loop(websocket, session, is_agent=True)
    finally:
        if session.agent is websocket:
            session.agent = None
        if session.agent is None and session.phone is None:
            LAPTOP_SESSIONS.pop(token, None)
        await _safe_send_text(session.phone, json.dumps({"type": "agent_disconnected"}))


@app.websocket("/laptop/ws/phone/{token}")
async def laptop_phone_ws(websocket: WebSocket, token: str) -> None:
    session = await _claim_relay(websocket, token, LAPTOP_SESSIONS, LaptopSession, "phone", "agent")
    if session is None:
        return
    try:
        await _safe_send_text(
            websocket,
            json.dumps({"type": "agent_connected" if session.agent else "agent_offline"}),
        )
        await _safe_send_text(session.agent, json.dumps({"type": "phone_connected"}))
        await _relay_loop(websocket, session, is_agent=False)
    finally:
        if session.phone is websocket:
            session.phone = None
        if session.agent is None and session.phone is None:
            LAPTOP_SESSIONS.pop(token, None)
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
    session = await _claim_relay(websocket, token, PHONE_SESSIONS, PhoneSession, "agent", "controller")
    if session is None:
        return
    try:
        await _safe_send_text(session.controller, json.dumps({"type": "agent_connected"}))
        await _safe_send_text(
            websocket,
            json.dumps({"type": "controller_connected" if session.controller else "controller_offline"}),
        )
        await _phone_relay_loop(websocket, session, is_agent=True)
    finally:
        if session.agent is websocket:
            session.agent = None
        if session.agent is None and session.controller is None:
            PHONE_SESSIONS.pop(token, None)
        await _safe_send_text(session.controller, json.dumps({"type": "agent_disconnected"}))


@app.websocket("/phone/ws/controller/{token}")
async def phone_controller_ws(websocket: WebSocket, token: str) -> None:
    session = await _claim_relay(websocket, token, PHONE_SESSIONS, PhoneSession, "controller", "agent")
    if session is None:
        return
    try:
        await _safe_send_text(
            websocket,
            json.dumps({"type": "agent_connected" if session.agent else "agent_offline"}),
        )
        await _safe_send_text(session.agent, json.dumps({"type": "controller_connected"}))
        await _phone_relay_loop(websocket, session, is_agent=False)
    finally:
        if session.controller is websocket:
            session.controller = None
        if session.agent is None and session.controller is None:
            PHONE_SESSIONS.pop(token, None)
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
