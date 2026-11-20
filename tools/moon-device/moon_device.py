"""Drive Moon+ Reader on a real Android device for Grimmory's Moon+ sync device checks.

Connects through adb (wireless debugging) and uiautomator2. Moon+ is a third-party app: we only
observe and operate its UI and app lifecycle, we never modify it.

Safety: Moon+ on the phone uploads to the real Drive sync folder, so every action and every Drive
fetch only touches books listed in test_books.txt (see require_test_book).

Commands (via the Justfile in this folder, `just device <cmd>`); results are printed as JSON:
    smoke                      device + Moon+ version, open Moon+, capture
    dump [name]                capture the current screen (screenshot + UI dump)
    open <book>                open a test book in Moon+'s reader
    close                      leave the reader (Moon+ uploads position/notes on pause)
    force-stop                 force-stop Moon+ (clears its in-memory state, e.g. the deleted-notes list)
    fetch <book>               read <book>.po / <book>.an from the Moon+ Drive sync folder (read-only)
"""

from __future__ import annotations

import datetime as dt
import json
import os
import sys
import time
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
ADB = HERE / ".tools" / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
CAPTURES = HERE / "captures"
TEST_BOOKS_FILE = HERE / "test_books.txt"
ENV_FILE = HERE / ".env"
MOON_PACKAGE = "com.flyersoft.moonreaderp"
READER_ACTIVITY = "com.flyersoft.moonreaderp.ActivityTxt"
MAIN_ACTIVITY = "com.flyersoft.moonreaderp.ActivityMain"
PHONE_TEST_DIR = "/storage/emulated/0/Books/MoonReader/TEST"
MIME_TYPES = {
    ".epub": "application/epub+zip",
    ".mobi": "application/x-mobipocket-ebook",
    ".azw": "application/vnd.amazon.ebook",
    ".azw3": "application/vnd.amazon.ebook",
    ".pdf": "application/pdf",
}


def load_env() -> None:
    """Load KEY=VALUE lines from the ignored .env file (real environment variables win)."""
    if not ENV_FILE.exists():
        return
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip())


load_env()

# adbutils (used by uiautomator2) picks up this adb binary instead of looking on PATH.
if ADB.exists():
    os.environ.setdefault("ADBUTILS_ADB_PATH", str(ADB))

import uiautomator2 as u2  # noqa: E402  (must come after ADBUTILS_ADB_PATH is set)


# --- safety --------------------------------------------------------------------------------------


class NotATestBook(Exception):
    """Raised when an action targets a book that isn't on the test-book allowlist."""


def test_books() -> list[str]:
    lines = TEST_BOOKS_FILE.read_text(encoding="utf-8").splitlines()
    return [line for line in lines if line.strip() and not line.lstrip().startswith("#")]


def require_test_book(filename: str) -> str:
    """Every action and Drive fetch must call this first: Moon+ on the phone uploads to the real
    sync folder, so touching only allowlisted test books is what keeps real reading data safe."""
    if filename not in test_books():
        raise NotATestBook(f"{filename!r} is not in {TEST_BOOKS_FILE.name}; refusing to touch it")
    return filename


# --- device --------------------------------------------------------------------------------------


def connect(serial: str | None = None) -> u2.Device:
    """Connect to the device (the only connected one, or MOON_DEVICE_SERIAL / the given serial)."""
    serial = serial or os.environ.get("MOON_DEVICE_SERIAL") or None
    return u2.connect(serial)


def moon_version(d: u2.Device) -> str | None:
    # Read through adb rather than uiautomator2's app/device info RPCs, which fail on Android 17
    # ("ApplicationSharedMemory not initialized"); screenshots, UI dumps and input still work.
    out = d.shell(["dumpsys", "package", MOON_PACKAGE]).output
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("versionName="):
            return line.split("=", 1)[1]
    return None


def android_release(d: u2.Device) -> str:
    return d.shell(["getprop", "ro.build.version.release"]).output.strip()


def capture(d: u2.Device, name: str) -> Path:
    """Save a screenshot and the UI hierarchy under captures/<timestamp>-<name>.*"""
    CAPTURES.mkdir(exist_ok=True)
    stem = CAPTURES / f"{dt.datetime.now():%Y%m%d-%H%M%S}-{name}"
    d.screenshot(str(stem.with_suffix(".png")))
    stem.with_suffix(".xml").write_text(d.dump_hierarchy(), encoding="utf-8")
    return stem


def foreground(d: u2.Device) -> str:
    """The resumed activity as "package/.Activity", read from dumpsys: uiautomator2's app_current()
    reports the wrong app on Android 17."""
    out = d.shell(["dumpsys", "activity", "activities"]).output
    for line in out.splitlines():
        if "topResumedActivity=" in line:
            parts = line.split()
            for p in parts:
                if "/" in p:
                    return p
    return ""


def in_reader(d: u2.Device) -> bool:
    return foreground(d) == f"{MOON_PACKAGE}/.ActivityTxt"


def ensure_awake(d: u2.Device) -> None:
    """Wake the screen; refuse to continue on a locked phone (UI automation needs it unlocked)."""
    d.shell(["input", "keyevent", "KEYCODE_WAKEUP"])
    time.sleep(0.5)
    if "isKeyguardShowing=true" in d.shell(["dumpsys", "window", "policy"]).output.replace(" ", ""):
        raise PhoneLocked("the phone is locked; unlock it (and keep it unlocked) for device runs")


class PhoneLocked(Exception):
    """Raised when the screen is locked, since UI automation can't unlock it."""


def wait_for(predicate, timeout: float = 15.0, interval: float = 0.5) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return False


# --- Drive (read-only) ---------------------------------------------------------------------------


def drive_session():
    from google.auth.transport.requests import AuthorizedSession
    from google.oauth2 import service_account

    key = os.environ.get("MOON_DRIVE_SA_JSON")
    if not key or not os.environ.get("MOON_DRIVE_FOLDER_ID"):
        raise RuntimeError("MOON_DRIVE_SA_JSON and MOON_DRIVE_FOLDER_ID must be set (see .env)")
    creds = service_account.Credentials.from_service_account_file(
        key, scopes=["https://www.googleapis.com/auth/drive.readonly"]
    )
    return AuthorizedSession(creds)


def drive_get(session, name: str) -> dict | None:
    folder = os.environ["MOON_DRIVE_FOLDER_ID"]
    escaped = name.replace("\\", "\\\\").replace("'", "\\'")
    resp = session.get(
        "https://www.googleapis.com/drive/v3/files",
        params={
            "q": f"name = '{escaped}' and '{folder}' in parents and trashed = false",
            "fields": "files(id,name,modifiedTime,md5Checksum)",
            "supportsAllDrives": "true",
            "includeItemsFromAllDrives": "true",
        },
        timeout=30,
    )
    resp.raise_for_status()
    files = resp.json().get("files", [])
    if not files:
        return None
    meta = files[0]
    data = session.get(
        f"https://www.googleapis.com/drive/v3/files/{meta['id']}",
        params={"alt": "media", "supportsAllDrives": "true"},
        timeout=60,
    )
    data.raise_for_status()
    return {"meta": meta, "bytes": data.content}


# --- commands ------------------------------------------------------------------------------------


def emit(result: dict) -> int:
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result.get("ok") else 1


def smoke() -> int:
    d = connect()
    width, height = d.window_size()
    version = moon_version(d)
    if not version:
        return emit({"ok": False, "error": f"Moon+ ({MOON_PACKAGE}) is not installed"})
    d.app_start(MOON_PACKAGE, wait=True)
    stem = capture(d, "smoke-moon-open")
    return emit({"ok": True, "device": d.serial, "android": android_release(d), "screen": [width, height],
                 "moon_version": version, "capture": str(stem)})


def dump(name: str = "dump") -> int:
    return emit({"ok": True, "capture": str(capture(connect(), name))})


def open_book(book: str) -> int:
    require_test_book(book)
    d = connect()
    ensure_awake(d)
    path = f"{PHONE_TEST_DIR}/{book}"
    if d.shell(["ls", path]).exit_code != 0:
        return emit({"ok": False, "error": f"not on the phone: {path}"})
    mime = MIME_TYPES.get(Path(book).suffix.lower(), "*/*")
    # The reader activity isn't exported; Moon+'s exported "open file" entry point is ActivityMain
    # (VIEW, file:// and content:// schemes — see the 10.8 manifest), which then opens the reader.
    d.shell(["am", "start", "-a", "android.intent.action.VIEW", "-t", mime, "-d", f"file://{path}",
             "-n", f"{MOON_PACKAGE}/{MAIN_ACTIVITY}"])
    opened = wait_for(lambda: in_reader(d))
    time.sleep(2)  # let the page render and Moon+'s on-open sync start
    stem = capture(d, "open")
    return emit({"ok": opened, "book": book, "activity": foreground(d), "capture": str(stem),
                 **({} if opened else {"error": "Moon+ reader did not open"})})


def close_book() -> int:
    d = connect()
    ensure_awake(d)
    for _ in range(4):
        if not in_reader(d):
            break
        d.press("back")
        time.sleep(1.5)
    stem = capture(d, "close")
    closed = not in_reader(d)
    return emit({"ok": closed, "activity": foreground(d), "capture": str(stem),
                 **({} if closed else {"error": "still in the reader"})})


def force_stop() -> int:
    d = connect()
    d.shell(["am", "force-stop", MOON_PACKAGE])
    return emit({"ok": True, "stopped": MOON_PACKAGE})


def fetch(book: str) -> int:
    require_test_book(book)
    session = drive_session()
    CAPTURES.mkdir(exist_ok=True)
    stamp = f"{dt.datetime.now():%Y%m%d-%H%M%S}"
    result: dict = {"ok": True, "book": book}
    for ext in ("po", "an"):
        got = drive_get(session, f"{book}.{ext}")
        if got is None:
            result[ext] = None
            continue
        raw = got["bytes"]
        (CAPTURES / f"{stamp}-{book}.{ext}").write_bytes(raw)
        text = zlib.decompress(raw).decode("utf-8") if ext == "an" else raw.decode("utf-8")
        result[ext] = {"modifiedTime": got["meta"]["modifiedTime"], "md5": got["meta"].get("md5Checksum"),
                       "text": text}
    return emit(result)


COMMANDS = {"smoke": smoke, "dump": dump, "open": open_book, "close": close_book,
            "force-stop": force_stop, "fetch": fetch}

if __name__ == "__main__":
    command, *args = sys.argv[1:] or ["smoke"]
    if command not in COMMANDS:
        sys.exit(f"unknown command {command!r}; use one of {sorted(COMMANDS)}")
    try:
        sys.exit(COMMANDS[command](*args))
    except (NotATestBook, PhoneLocked) as e:
        sys.exit(emit({"ok": False, "error": str(e)}))
