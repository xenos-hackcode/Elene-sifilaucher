# SciFiLauncher (Xenos)

A full sci-fi/hacker-themed Android launcher with an AI voice assistant (Xenos, formerly Elene),
Device Owner-based kiosk/security features, and two custom keyboards (Xenos and Cedal).

This is open source, **not a hosted service** - there's no shared backend. Every install needs
its own backend deployment, with your own API keys. See [Setup](#setup) below.

## What's here

| Path | What it is |
|---|---|
| `app/` | The launcher itself - home screen, app drawer, Recents, Settings, Security, keyboards, voice assistant client. |
| `backend/elene/` | The Xenos/Elene backend (FastAPI) - LLM chat, TTS, screen description, email alerts, self-update pipeline. |
| `agent/` | "Link to Phone" companion app - lets a laptop/PC remote-control this phone. |
| `controller/` | Standalone controller app - QR pairing, phone/laptop remote control from another device. |
| `laptop_agent/` | The laptop-side agent for Link to PC (Python, packaged with PyInstaller). |
| `planner/` | Development log - what's done, in progress, and not started, plus real bugs found during live testing. |

## Setup

### 1. Deploy your own backend

```
cd backend/elene
cp .env.example .env
```

Fill in `.env` with your own API keys - see the comments in `.env.example` for what each
variable does and which ones are optional (at minimum you need **one** of
`OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `GROQ_API_KEY` / `OPENROUTER_API_KEY` for the assistant
to respond at all).

Run it locally to test:

```
pip install -r requirements.txt
uvicorn main:app --reload
```

Or deploy it wherever you like (Cloud Run, Render, a VPS, etc.) - `Dockerfile` is included for
container-based hosts. On a hosted platform, set the same variables as real environment
variables in that platform's dashboard/CLI instead of shipping the `.env` file.

### 2. Build and install the app

Open the project in Android Studio (or `./gradlew assembleDebug`) and install the `app` module
on a device. Device Owner-dependent features (Kiosk mode, some Security screen items) require
provisioning the app as Device Owner - see `planner/` for notes on how this was set up.

### 3. Point the app at your backend

In the app: **Settings → Elene Backend → Backend URL**, paste your deployed backend's URL, tap
Save. This is a runtime setting, no rebuild needed. Until it's set, the row shows "Not set -
Elene won't respond" and voice/chat requests just won't do anything - that's expected, not a bug.

## License

Apache 2.0 - see [LICENSE](LICENSE).

## Known limitations

- Phone/laptop control (`controller/`, `agent/`, `laptop_agent/`) is functional but not yet at
  the polish level intended for a dedicated Play Store release - that's separate, later work.
- The self-update proposal pipeline (in-app Updates screen) defaults to this project's own
  GitHub repo unless you set `GITHUB_REPO` in your backend's environment to point at your own
  fork.
