"""
SciFiLauncher Laptop Agent
--------------------------
Runs on this Windows machine and lets the SciFiLauncher Android app view and control
this screen remotely (over the internet, not just local WiFi), through the same
Cloud Run backend Elene already uses.

First run generates a pairing token and prints it (plus a QR code you can scan with
the app's "Scan QR" quick-settings control, or the "Link to Laptop" screen's own
scan button). That token is the entire auth boundary for this connection - anyone who
has it can view/control this PC while the agent is running, so treat it like a password.
Delete config.json and rerun to generate a new one if it ever leaks.

Usage:
    pip install -r requirements.txt
    python agent.py
"""

import asyncio
import base64
import io
import json
import secrets
import sys
from pathlib import Path

import mss
import pyautogui
import websockets
from PIL import Image

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0

BACKEND_WS_BASE = "wss://elene-backend-717899371194.us-central1.run.app"

FRAME_INTERVAL_SECONDS = 0.15   # ~6-7 fps, tuned for a phone data connection
JPEG_QUALITY = 45
MAX_FRAME_WIDTH = 1000           # downscaled before encoding to keep frames small

CONFIG_DIR = Path(
    __import__("os").getenv("APPDATA", str(Path.home()))
) / "SciFiLauncherAgent"
CONFIG_FILE = CONFIG_DIR / "config.json"


def load_or_create_token() -> str:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    if CONFIG_FILE.exists():
        try:
            data = json.loads(CONFIG_FILE.read_text())
            token = data.get("token")
            if token and len(token) >= 16:
                return token
        except Exception:
            pass
    # Lowercase hex only - no case-sensitivity and no 0/O, 1/l ambiguity to trip up typing
    # this in on a phone keyboard (mixed-case base64 tokens turned out to be genuinely
    # error-prone to transcribe by hand).
    token = secrets.token_hex(16)
    CONFIG_FILE.write_text(json.dumps({"token": token}))
    return token


def print_pairing_info(token: str) -> None:
    print("=" * 60)
    print("SciFiLauncher Laptop Agent")
    print("=" * 60)
    print("Pairing code (enter this in the app's Link to Laptop screen):\n")
    print(f"    {token}\n")
    print("Type it carefully, or better - use the QR code so there's no chance of a typo:")
    try:
        import qrcode

        qr = qrcode.QRCode(border=1)
        qr.add_data(token)
        qr.make()
        qr.print_ascii(invert=True)
        print("(scan the code above with the app's QR scanner instead of typing it)")
    except UnicodeEncodeError:
        # Common on the default Windows console (cp1252), which can't render the
        # block characters print_ascii() uses - fall back to a QR image file instead.
        _open_qr_image(token)
    except Exception:
        pass
    print("=" * 60)
    print("Leave this window open. Ctrl+C to stop.\n")


def _open_qr_image(token: str) -> None:
    try:
        import qrcode

        img = qrcode.make(token)
        path = CONFIG_DIR / "pairing_qr.png"
        img.save(path)
        print(f"(this terminal can't draw the QR as text - opened it as an image instead: {path})")
        if sys.platform == "win32":
            import os
            os.startfile(str(path))  # noqa: S606 - local file the agent itself just wrote
    except Exception:
        pass


def capture_frame_jpeg(sct: "mss.base.MSSBase", monitor) -> bytes:
    shot = sct.grab(monitor)
    img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
    if img.width > MAX_FRAME_WIDTH:
        ratio = MAX_FRAME_WIDTH / img.width
        img = img.resize((MAX_FRAME_WIDTH, int(img.height * ratio)))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=JPEG_QUALITY)
    return buf.getvalue()


_KEY_MAP = {
    "enter": "enter",
    "backspace": "backspace",
    "tab": "tab",
    "esc": "esc",
    "escape": "esc",
    "up": "up",
    "down": "down",
    "left": "left",
    "right": "right",
    "space": "space",
    "delete": "delete",
    "home": "home",
    "end": "end",
}


def handle_command(cmd: dict, screen_w: int, screen_h: int) -> None:
    kind = cmd.get("type")
    if kind == "mouse_move":
        x = int(cmd.get("x", 0) * screen_w)
        y = int(cmd.get("y", 0) * screen_h)
        pyautogui.moveTo(x, y)
    elif kind == "mouse_click":
        x = int(cmd.get("x", 0) * screen_w)
        y = int(cmd.get("y", 0) * screen_h)
        button = cmd.get("button", "left")
        pyautogui.click(x=x, y=y, button=button)
    elif kind == "mouse_down":
        pyautogui.mouseDown(button=cmd.get("button", "left"))
    elif kind == "mouse_up":
        pyautogui.mouseUp(button=cmd.get("button", "left"))
    elif kind == "scroll":
        pyautogui.scroll(int(cmd.get("dy", 0)))
    elif kind == "key_text":
        text = cmd.get("text", "")
        if text:
            pyautogui.write(text, interval=0.01)
    elif kind == "key_press":
        key = _KEY_MAP.get(cmd.get("key", ""))
        if key:
            pyautogui.press(key)


async def frame_sender(ws, screen_w: int, screen_h: int, phone_present: list) -> None:
    with mss.mss() as sct:
        monitor = sct.monitors[1]  # primary display
        while True:
            if phone_present[0]:
                try:
                    frame = capture_frame_jpeg(sct, monitor)
                    await ws.send(frame)
                except Exception:
                    return
            await asyncio.sleep(FRAME_INTERVAL_SECONDS)


async def command_receiver(ws, screen_w: int, screen_h: int, phone_present: list) -> None:
    async for message in ws:
        if isinstance(message, (bytes, bytearray)):
            continue
        try:
            data = json.loads(message)
        except Exception:
            continue
        msg_type = data.get("type")
        if msg_type == "phone_connected":
            phone_present[0] = True
            print("[+] Phone connected.")
        elif msg_type == "phone_offline":
            phone_present[0] = False
            print("Waiting for the phone to open Link to Laptop...")
        elif msg_type == "phone_disconnected":
            phone_present[0] = False
            print("[-] Phone disconnected.")
        elif msg_type in ("mouse_move", "mouse_click", "mouse_down", "mouse_up", "scroll", "key_text", "key_press"):
            handle_command(data, screen_w, screen_h)


async def run_session(token: str) -> None:
    screen_w, screen_h = pyautogui.size()
    url = f"{BACKEND_WS_BASE}/laptop/ws/agent/{token}"
    print(f"Connecting to {url} ...")
    async with websockets.connect(url, max_size=None, ping_interval=20, ping_timeout=20) as ws:
        print("Connected. Waiting for the phone to open Link to Laptop...")
        await ws.send(json.dumps({"type": "info", "width": screen_w, "height": screen_h}))
        phone_present = [False]
        await asyncio.gather(
            frame_sender(ws, screen_w, screen_h, phone_present),
            command_receiver(ws, screen_w, screen_h, phone_present),
        )


async def main() -> None:
    token = load_or_create_token()
    print_pairing_info(token)
    backoff = 2
    while True:
        try:
            await run_session(token)
            backoff = 2
        except (websockets.exceptions.ConnectionClosed, OSError) as e:
            print(f"Connection lost ({e}). Reconnecting in {backoff}s...")
        except Exception as e:
            print(f"Unexpected error ({e}). Reconnecting in {backoff}s...")
        await asyncio.sleep(backoff)
        backoff = min(backoff * 2, 30)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nStopped.")
        sys.exit(0)
