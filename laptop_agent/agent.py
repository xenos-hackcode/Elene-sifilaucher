"""
SciFiLauncher Laptop Agent
--------------------------
Runs on this Windows machine and lets the SciFiLauncher Android app view and control
this screen remotely (over the internet, not just local WiFi), through the same
Cloud Run backend Xenos already uses.

First run generates a pairing token and prints it (plus a QR code you can scan with
the app's "Scan QR" quick-settings control, or the "Link to Laptop" screen's own
scan button). That token is the entire auth boundary for this connection - anyone who
has it can view/control this PC while the agent is running, so treat it like a password.
Delete config.json and rerun to generate a new one if it ever leaks.

Usage:
    pip install -r requirements.txt
    python agent.py              # run once, plain console, until you close the window
    python agent.py --install    # set up persistent background operation (see below)
    python agent.py --uninstall  # remove persistent background operation
    python agent.py --tray       # internal - used by the scheduled task, not for manual use

--install sets this up to survive closing the window and to auto-start at login, running
minimized with a system tray icon (never fully invisible - matching this whole project's
"no silent operation" rule) instead of a console window. Requires reading and accepting a
real disclosure first, same as this app's other shared-device features - this is a genuine
remote-control capability, not something to enable on a shrug.
"""

import asyncio
import base64
import io
import json
import secrets
import subprocess
import sys
import threading
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

SCHEDULED_TASK_NAME = "XenosLinkToPC"

DISCLOSURE_TEXT = """
============================================================
 SciFiLauncher / Xenos - Link to PC - background setup
============================================================
This installs BACKGROUND, PERSISTENT remote access to this PC:

  - Starts automatically every time you log in to Windows.
  - Runs minimized - NOT fully hidden: look for the "PC" icon
    in your system tray any time it's active.
  - While running, whoever holds the pairing code for this
    install can VIEW THIS SCREEN and CONTROL THE MOUSE AND
    KEYBOARD, for as long as it's running.
  - If the program is closed unexpectedly it restarts itself
    automatically (that's the point of installing this way).

This is real remote-control software. Only install this if you
mean to give someone ongoing access to this PC, and you trust
them with the pairing code. You can remove this at any time by
running:  python agent.py --uninstall
(or the equivalent from the installed .exe)
============================================================
"""


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
    print("Leave this window open. Ctrl+C to stop.")
    print("Want this to survive closing the window and auto-start at login?")
    print("Run:  python agent.py --install\n")


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


async def command_receiver(ws, screen_w: int, screen_h: int, phone_present: list, status: dict) -> None:
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
            status["text"] = "Phone connected"
            print("[+] Phone connected.")
        elif msg_type == "phone_offline":
            phone_present[0] = False
            status["text"] = "Waiting for phone"
            print("Waiting for the phone to open Link to Laptop...")
        elif msg_type == "phone_disconnected":
            phone_present[0] = False
            status["text"] = "Waiting for phone"
            print("[-] Phone disconnected.")
        elif msg_type in ("mouse_move", "mouse_click", "mouse_down", "mouse_up", "scroll", "key_text", "key_press"):
            handle_command(data, screen_w, screen_h)


async def run_session(token: str, status: dict) -> None:
    screen_w, screen_h = pyautogui.size()
    url = f"{BACKEND_WS_BASE}/laptop/ws/agent/{token}"
    print(f"Connecting to {url} ...")
    status["text"] = "Connecting..."
    async with websockets.connect(url, max_size=None, ping_interval=20, ping_timeout=20) as ws:
        print("Connected. Waiting for the phone to open Link to Laptop...")
        status["text"] = "Waiting for phone"
        await ws.send(json.dumps({"type": "info", "width": screen_w, "height": screen_h}))
        phone_present = [False]
        await asyncio.gather(
            frame_sender(ws, screen_w, screen_h, phone_present),
            command_receiver(ws, screen_w, screen_h, phone_present, status),
        )


async def main_loop(token: str, status: dict) -> None:
    backoff = 2
    while True:
        try:
            await run_session(token, status)
            backoff = 2
        except (websockets.exceptions.ConnectionClosed, OSError) as e:
            print(f"Connection lost ({e}). Reconnecting in {backoff}s...")
            status["text"] = f"Reconnecting in {backoff}s"
        except Exception as e:
            print(f"Unexpected error ({e}). Reconnecting in {backoff}s...")
            status["text"] = f"Reconnecting in {backoff}s"
        await asyncio.sleep(backoff)
        backoff = min(backoff * 2, 30)


# ---- tray mode (background/auto-start) ----

def _tray_icon_image():
    from PIL import Image, ImageDraw
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([4, 4, 60, 60], radius=10, fill=(20, 20, 24, 255), outline=(0, 220, 130, 255), width=3)
    d.text((16, 20), "PC", fill=(0, 220, 130, 255))
    return img


_SINGLETON_MUTEX_HANDLE = None  # kept alive for the process lifetime - a GC'd handle releases the lock


def _acquire_singleton_lock() -> bool:
    """A named Win32 mutex, not a PID/lock file - survives even if a previous instance
    crashed uncleanly (no stale-file cleanup needed, the OS releases the mutex automatically
    when the owning process exits). Found live 2026-08-10: something (Windows Defender's
    behavioral monitoring re-executing this exact kind of screen-capture/remote-input program
    for analysis is the leading suspect, but never conclusively confirmed from the event log)
    was launching a second real --tray instance ~30s after the first, both independently
    connecting to the backend under the same token. Rather than depend on fully understanding
    the exact cause, refusing to run a second instance is the robust fix regardless of why one
    gets started - a genuine accidental double-launch would hit the same problem either way.

    First version of this used the "Global\\" namespace and only checked for
    ERROR_ALREADY_EXISTS - both wrong: "Global\\" needs a privilege this account doesn't have
    (same class of restriction already found blocking Scheduled Tasks), so CreateMutexW failed
    for an unrelated reason (access denied, not "already exists") and BOTH processes read that
    as "lock acquired" since neither saw the specific error code being checked for. A plain,
    session-local mutex name (no namespace prefix) needs no special privilege and is enough
    here anyway, since this only ever runs within one interactive session. Also now checks the
    handle itself, not just the error code - any failure to create the mutex, for any reason,
    means "don't assume we hold the lock"."""
    global _SINGLETON_MUTEX_HANDLE
    import ctypes
    ERROR_ALREADY_EXISTS = 183
    handle = ctypes.windll.kernel32.CreateMutexW(None, False, "XenosLinkToPCAgent")
    last_error = ctypes.windll.kernel32.GetLastError()
    if not handle or last_error == ERROR_ALREADY_EXISTS:
        return False
    _SINGLETON_MUTEX_HANDLE = handle  # keep a reference so it isn't garbage-collected/closed
    return True


def run_tray_mode(token: str) -> None:
    import pystray

    if not _acquire_singleton_lock():
        # No print here, deliberately - tray mode is launched without an attached console
        # (via the Startup .bat's "start \"\" ..."), and found live 2026-08-10 that a print()
        # in that situation can block forever with nothing to write to, leaving a zombie
        # process behind even though the mutex check itself worked correctly.
        return

    status = {"text": "Starting..."}

    def run_asyncio():
        asyncio.run(main_loop(token, status))

    threading.Thread(target=run_asyncio, daemon=True).start()

    def on_status(item):
        return f"Status: {status['text']}"

    def on_show_code(icon, item):
        import ctypes
        ctypes.windll.user32.MessageBoxW(
            0, f"Pairing code:\n\n{token}", "Xenos Link to PC", 0x40
        )

    def on_quit(icon, item):
        icon.stop()
        sys.exit(0)  # clean exit - Task Scheduler's restart-on-failure only fires on crash

    icon = pystray.Icon(
        "XenosLinkToPC",
        _tray_icon_image(),
        "Xenos Link to PC",
        menu=pystray.Menu(
            pystray.MenuItem(on_status, None, enabled=False),
            pystray.MenuItem("Show pairing code", on_show_code),
            pystray.MenuItem("Quit", on_quit),
        ),
    )
    icon.run()


# ---- install / uninstall (persistent background operation) ----

def _self_command_args() -> list:
    """The exact argv the scheduled task / startup shortcut should launch - the frozen exe
    itself if running as one (PyInstaller sets sys.frozen), otherwise this same interpreter +
    script. Returned as a real argument list, not a string to be re-parsed later - a path
    containing spaces (very common on Windows, e.g. "Program Files") would silently break any
    later naive string-splitting of a flattened command line."""
    if getattr(sys, "frozen", False):
        return [sys.executable, "--tray"]
    return [sys.executable, str(Path(__file__).resolve()), "--tray"]


def _self_command_quoted() -> str:
    """Same as _self_command_args() but flattened into a single quoted string, only for the
    one place that genuinely needs a string: PowerShell's -Argument parameter."""
    return " ".join(f'"{a}"' if " " in a else a for a in _self_command_args())


def _startup_folder() -> Path:
    import os
    return Path(os.getenv("APPDATA", str(Path.home()))) / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "Startup"


def _startup_bat_path() -> Path:
    return _startup_folder() / "XenosLinkToPC.bat"


def _try_task_scheduler(command: str) -> bool:
    """Preferred path - also gets real restart-on-crash, not just auto-start-at-login.
    Returns False (without raising) on any failure, e.g. Access Denied under a locked-down
    account - found live 2026-08-10 that a plain, non-admin domain account can be blocked from
    creating scheduled tasks at all (not just this specific one - Register-ScheduledTask AND
    the classic schtasks.exe both fail identically), so this must never be assumed to work."""
    ps_script = f"""
$action = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument '/c start "" {command}'
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit 0 -DontStopOnIdleEnd -AllowStartIfOnBatteries
Register-ScheduledTask -TaskName '{SCHEDULED_TASK_NAME}' -Action $action -Trigger $trigger -Settings $settings -Description 'Xenos Link to PC - background remote access agent. Remove with: python agent.py --uninstall' -Force | Out-Null
Start-ScheduledTask -TaskName '{SCHEDULED_TASK_NAME}'
"""
    result = subprocess.run(
        ["powershell", "-NoProfile", "-Command", ps_script],
        capture_output=True, text=True,
    )
    return result.returncode == 0


def _install_startup_folder_fallback(command_quoted: str, command_args: list) -> bool:
    """Needs no special permissions - any standard account can write its own Startup folder.
    Gives auto-start-at-login only, NOT restart-if-closed-mid-session (that needs real OS
    supervision like Task Scheduler, which this path exists because we couldn't use)."""
    try:
        folder = _startup_folder()
        folder.mkdir(parents=True, exist_ok=True)
        _startup_bat_path().write_text(f'@echo off\nstart "" {command_quoted}\n')
        # A frozen (PyInstaller onefile) exe launching a fresh copy of itself must NOT
        # inherit the parent's _MEI*/_PYI* bootloader env vars - those tell the child to
        # reuse the parent's temp extraction folder, which gets deleted the moment this
        # --install process exits, so the child crashes with a FileNotFoundError as soon as
        # it tries to read from an already-cleaned-up temp dir. Found live 2026-08-10.
        import os
        clean_env = {k: v for k, v in os.environ.items() if not k.startswith(("_MEI", "_PYI"))}
        subprocess.Popen(command_args, close_fds=True, env=clean_env)
        return True
    except Exception:
        return False


def install_autostart() -> None:
    print(DISCLOSURE_TEXT)
    answer = input('Type "yes" to install, anything else to cancel: ').strip().lower()
    if answer != "yes":
        print("Cancelled. Nothing was installed.")
        return

    load_or_create_token()  # make sure a token exists before the first background launch
    command_quoted = _self_command_quoted()
    command_args = _self_command_args()

    if _try_task_scheduler(command_quoted):
        print("Installed via Task Scheduler. It will start automatically at your next login,")
        print("AND restart itself automatically if it's ever closed unexpectedly.")
        print("Started now - look for the \"PC\" icon in your system tray.")
    elif _install_startup_folder_fallback(command_quoted, command_args):
        print("Task Scheduler isn't available on this account (this can happen on managed/")
        print("domain accounts) - installed via the Startup folder instead. It will start")
        print("automatically at your next login, but it will NOT restart itself if you close")
        print("it mid-session - only Task Scheduler can do that, and it's blocked here.")
        print("Started now - look for the \"PC\" icon in your system tray.")
    else:
        print("Install failed on both the Task Scheduler and Startup-folder paths.")
        return

    print("To remove it later: python agent.py --uninstall")


def uninstall_autostart() -> None:
    subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         f"Stop-ScheduledTask -TaskName '{SCHEDULED_TASK_NAME}' -ErrorAction SilentlyContinue; "
         f"Unregister-ScheduledTask -TaskName '{SCHEDULED_TASK_NAME}' -Confirm:$false -ErrorAction SilentlyContinue"],
        capture_output=True, text=True,
    )
    try:
        _startup_bat_path().unlink(missing_ok=True)
    except Exception:
        pass
    # Also kill any currently-running tray instance so removal takes effect immediately,
    # not just on the next login.
    subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*--tray*' -and "
         "($_.CommandLine -like '*agent.py*' -or $_.CommandLine -like '*XenosLinkToPC*') } | "
         "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"],
        capture_output=True, text=True,
    )
    print("Removed - no longer starts at login, and any running background instance was stopped.")


async def main() -> None:
    token = load_or_create_token()
    print_pairing_info(token)
    status = {"text": "Starting..."}
    await main_loop(token, status)


if __name__ == "__main__":
    # Required for a frozen (PyInstaller onefile) exe - without this, Windows' lack of
    # fork() means any multiprocessing-style bootstrap pulled in transitively re-imports
    # __main__ in a fresh process, which for a frozen exe means silently launching a second
    # full copy of this program. Found live 2026-08-10: --install was producing two
    # independent --tray processes, both really connecting to the backend under the same
    # token - not a race condition, a missing standard PyInstaller guard.
    import multiprocessing
    multiprocessing.freeze_support()

    if "--install" in sys.argv:
        install_autostart()
    elif "--uninstall" in sys.argv:
        uninstall_autostart()
    elif "--tray" in sys.argv:
        token = load_or_create_token()
        run_tray_mode(token)
    else:
        try:
            asyncio.run(main())
        except KeyboardInterrupt:
            print("\nStopped.")
            sys.exit(0)
