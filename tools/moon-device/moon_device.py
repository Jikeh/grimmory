"""Drive Moon+ Reader on a real Android device for Grimmory's Moon+ sync device checks.

Connects through adb (wireless debugging) and uiautomator2. Moon+ is a third-party app: we only
observe and operate its UI and app lifecycle, we never modify it.

Safety: Moon+ on the phone uploads to the real Drive sync folder, so every action and every Drive
fetch only touches books listed in test_books.txt (see require_test_book), and in-reader actions
also check that the reader in front is the one `open` started for that book (see require_reader).

Every command prints one JSON object ({"ok": ..., "steps": [...captures...], ...}) and exits 0 on
success, 1 on failure (with "error" and a "failure_capture" path). See README.md for the full
interface; command names follow the Moon+ emulator actions (ticket 09) so ticket 15 can wrap them.

    smoke | dump [name] | ui [name] | position
    open <book> | close | force-stop | restart <book>
    read-to <book> <chapter title> [pages]
    turn <book> [pages] [next|prev]
    select <book> <x1> <y1> <x2> <y2>
    add-highlight <book> <x1> <y1> <x2> <y2> [note]
    edit-note <book> <x> <y> <note>
    delete-highlight <book> <x> <y> trash|note-popup
    delete-listed <book> <text>
    add-bookmark <book> [name]
    sync-up <book> | sync-down <book> normal|forced
    answer-prompt <book> ok|cancel [timeout]
    fetch <book>
"""

from __future__ import annotations

import datetime as dt
import json
import os
import re
import sys
import time
import zlib
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
ADB = HERE / ".tools" / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
CAPTURES = HERE / "captures"
STATE_FILE = CAPTURES / "reader-state.json"
TEST_BOOKS_FILE = HERE / "test_books.txt"
ENV_FILE = HERE / ".env"
MOON_PACKAGE = "com.flyersoft.moonreaderp"
READER = f"{MOON_PACKAGE}/.ActivityTxt"
MAIN_ACTIVITY = "com.flyersoft.moonreaderp.ActivityMain"
PHONE_TEST_DIR = "/storage/emulated/0/Books/MoonReader/TEST"
MIME_TYPES = {
    ".epub": "application/epub+zip",
    ".mobi": "application/x-mobipocket-ebook",
    ".azw": "application/vnd.amazon.ebook",
    ".azw3": "application/vnd.amazon.ebook",
    ".pdf": "application/pdf",
}
ID = MOON_PACKAGE + ":id/"


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

# Reader-bar buttons, counted from the end of the bar (the bar is user-customisable and its icons
# have no ids or labels). The driver long-presses them and only proceeds if Moon+'s own menu titled
# "Sync from cloud" / "Sync to cloud" appears, so a moved button fails instead of pressing something else.
SYNC_DOWN_BUTTON = int(os.environ.get("MOON_SYNC_DOWN_BUTTON", "-3"))
SYNC_UP_BUTTON = int(os.environ.get("MOON_SYNC_UP_BUTTON", "-1"))
PAGE_TURN_TAP = (980, 1200)  # right side of the page: next page (left of Samsung's edge-panel handle)
MENU_TAP = (540, 1170)  # page centre: toggles Moon+'s top/bottom bars


# --- safety --------------------------------------------------------------------------------------


class DriverError(Exception):
    """A step failed; the command reports it as {"ok": false, "error": ...} with a capture."""


class NotATestBook(DriverError):
    """Raised when an action targets a book that isn't on the test-book allowlist."""


class PhoneLocked(DriverError):
    """Raised when the screen is locked, since UI automation can't unlock it."""


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


@dataclass
class Node:
    cls: str
    rid: str
    text: str
    desc: str
    bounds: tuple[int, int, int, int]
    clickable: bool

    @property
    def center(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2


NODE_RE = re.compile(r"<node [^>]*>")
ATTR_RE = re.compile(r'([\w-]+)="([^"]*)"')


def parse_nodes(xml: str) -> list[Node]:
    nodes = []
    for m in NODE_RE.finditer(xml):
        a = dict(ATTR_RE.findall(m.group(0)))
        b = tuple(map(int, re.findall(r"\d+", a.get("bounds", "[0,0][0,0]"))))
        text = a.get("text", "").replace("&amp;", "&").replace("&quot;", '"').replace("&#10;", "\n")
        nodes.append(Node(a.get("class", ""), a.get("resource-id", ""), text, a.get("content-desc", ""),
                          b, a.get("clickable") == "true"))
    return nodes


class Phone:
    """The connected phone. Input goes through `adb shell input` (uiautomator2's click RPC dropped
    connections on Android 17); screenshots, UI dumps and set_text go through uiautomator2."""

    def __init__(self, serial: str | None = None):
        serial = serial or os.environ.get("MOON_DEVICE_SERIAL") or None
        self.d = u2.connect(serial)
        self.steps: list[str] = []

    # adb / input
    def sh(self, *args: str) -> str:
        return self.d.shell([str(a) for a in args]).output

    def tap(self, x: int, y: int) -> None:
        self.sh("input", "tap", x, y)

    def long_press(self, x: int, y: int, ms: int = 1000) -> None:
        self.sh("input", "swipe", x, y, x, y, ms)

    def drag(self, x1: int, y1: int, x2: int, y2: int, ms: int = 800) -> None:
        self.sh("input", "swipe", x1, y1, x2, y2, ms)

    def back(self) -> None:
        self.sh("input", "keyevent", "KEYCODE_BACK")

    # observation
    def _retry(self, fn):
        for attempt in range(3):
            try:
                return fn()
            except Exception:  # uiautomator2's RPC occasionally drops; it restarts its server
                if attempt == 2:
                    raise
                time.sleep(1.5)

    def nodes(self) -> list[Node]:
        return parse_nodes(self._retry(self.d.dump_hierarchy))

    def find(self, rid: str | None = None, text: str | None = None, contains: str | None = None,
             nodes: list[Node] | None = None) -> Node | None:
        for n in nodes if nodes is not None else self.nodes():
            if rid and n.rid != (rid if ":" in rid else ID + rid):
                continue
            if text is not None and n.text != text:
                continue
            if contains is not None and contains not in n.text:
                continue
            return n
        return None

    def wait_node(self, timeout: float = 8.0, **kw) -> Node | None:
        deadline = time.monotonic() + timeout
        while True:
            node = self.find(**kw)
            if node is not None or time.monotonic() >= deadline:
                return node
            time.sleep(0.5)

    def tap_node(self, what: str, timeout: float = 8.0, **kw) -> Node:
        """Tap a widget once it has stopped moving (dialogs slide in; tapping mid-animation hits
        whatever ends up under the old coordinates)."""
        n = self.wait_node(timeout, **kw)
        for _ in range(10):
            if n is None:
                break
            time.sleep(0.4)
            again = self.find(**kw)
            if again is not None and again.bounds == n.bounds:
                break
            n = again
        if n is None:
            raise DriverError(f"{what} not found on screen ({kw})")
        self.tap(*n.center)
        return n

    def capture(self, name: str) -> Path:
        """Save a screenshot and the UI hierarchy under captures/<timestamp>-<name>.*"""
        CAPTURES.mkdir(exist_ok=True)
        stem = CAPTURES / f"{dt.datetime.now():%Y%m%d-%H%M%S-%f}"[:-3]
        stem = stem.with_name(f"{stem.name}-{name}")
        self._retry(lambda: self.d.screenshot(str(stem.with_suffix(".png"))))
        stem.with_suffix(".xml").write_text(self._retry(self.d.dump_hierarchy), encoding="utf-8")
        return stem

    def step(self, name: str, settle: float = 1.0) -> None:
        """Let the UI settle, then record a capture for this step."""
        time.sleep(settle)
        self.steps.append(str(self.capture(name)))

    # app state
    def foreground(self) -> str:
        """The resumed activity as "package/.Activity", read from dumpsys: uiautomator2's app_current()
        reports the wrong app on Android 17."""
        for line in self.sh("dumpsys", "activity", "activities").splitlines():
            if "topResumedActivity=" in line:
                for p in line.split():
                    if "/" in p:
                        return p.rstrip("}")
        return ""

    def reader_token(self) -> str | None:
        """Identity of the resumed reader activity instance (its ActivityRecord hash), or None."""
        for line in self.sh("dumpsys", "activity", "activities").splitlines():
            if "topResumedActivity=" in line and READER in line:
                m = re.search(r"ActivityRecord\{(\w+)", line)
                return m.group(1) if m else None
        return None

    def in_reader(self) -> bool:
        return self.foreground() == READER

    def ensure_awake(self) -> None:
        """Wake the screen; refuse to continue on a locked phone (UI automation needs it unlocked)."""
        self.sh("input", "keyevent", "KEYCODE_WAKEUP")
        time.sleep(0.5)
        if "isKeyguardShowing=true" in self.sh("dumpsys", "window", "policy").replace(" ", ""):
            raise PhoneLocked("the phone is locked; unlock it (and keep it unlocked) for device runs")

    def moon_version(self) -> str | None:
        # Read through adb rather than uiautomator2's app/device info RPCs, which fail on Android 17.
        for line in self.sh("dumpsys", "package", MOON_PACKAGE).splitlines():
            if line.strip().startswith("versionName="):
                return line.strip().split("=", 1)[1]
        return None


def wait_for(predicate, timeout: float = 15.0, interval: float = 0.5) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return False


# --- reader state (which test book the reader in front was opened for) ---------------------------


def save_reader_state(book: str, token: str | None) -> None:
    CAPTURES.mkdir(exist_ok=True)
    STATE_FILE.write_text(json.dumps({"book": book, "token": token}), encoding="utf-8")


def require_reader(p: Phone, book: str) -> None:
    """In-reader actions only run in the reader instance that `open` started for this test book."""
    require_test_book(book)
    p.ensure_awake()
    state = json.loads(STATE_FILE.read_text(encoding="utf-8")) if STATE_FILE.exists() else {}
    token = p.reader_token()
    if token is None:
        raise DriverError("Moon+'s reader is not in front; run `open <book>` first")
    if state.get("book") != book or state.get("token") != token:
        raise DriverError(f"the reader in front was not opened by `open {book!r}`; run `open` first")


# --- reader UI helpers ---------------------------------------------------------------------------


def status(p: Phone, nodes: list[Node] | None = None) -> dict:
    """Moon+'s reader status line: "Chapter 2 (1/3)" and "0.7%"."""
    nodes = nodes if nodes is not None else p.nodes()
    middle = p.find(rid="statusMiddle", nodes=nodes)
    right = p.find(rid="statusRight", nodes=nodes)
    result: dict = {"status": middle.text if middle else None, "percent": right.text if right else None}
    m = re.match(r"(.*) \((\d+)/(\d+)\)$", middle.text) if middle else None
    if m:
        result.update(chapter=m.group(1), page=int(m.group(2)), pages=int(m.group(3)))
    return result


def dismiss_popups(p: Phone) -> None:
    """Back out of any selection bar, dialog or dictionary until the plain page shows."""
    for _ in range(4):
        nodes = p.nodes()
        if p.in_reader() and not any(p.find(rid=r, nodes=nodes) for r in ("HighlightBar", "buttonsLay", "noteEt", "bmListView01", "chListView01")) \
                and not p.find(rid="android:id/button1", nodes=nodes) and not p.find(text="Exit", nodes=nodes):
            return
        p.back()
        time.sleep(1)


def show_bars(p: Phone) -> list[Node]:
    nodes = p.nodes()
    if not p.find(rid="buttonsLay", nodes=nodes):
        p.tap(*MENU_TAP)
        if not p.wait_node(5, rid="buttonsLay"):
            raise DriverError("Moon+'s reader bars did not appear")
        nodes = p.nodes()
    return nodes


def open_more_operation(p: Phone, item: str) -> None:
    show_bars(p)
    p.tap_node("reader menu button", rid="menuB")
    p.tap_node("'More Operations...' menu item", text="More Operations...")
    p.tap_node(f"'{item}' menu item", text=item)


def bar_button(p: Phone, index: int, menu_title: str) -> Node:
    """Long-press a reader-bar button and return the matching item of the menu Moon+ shows, or fail."""
    nodes = show_bars(p)
    lay = p.find(rid="buttonsLay", nodes=nodes)
    x1, y1, x2, y2 = lay.bounds
    buttons = [n for n in nodes if n.cls.endswith("ImageView") and not n.rid and n.clickable
               and x1 <= n.bounds[0] and n.bounds[2] <= x2 and y1 <= n.bounds[1] and n.bounds[3] <= y2]
    if not buttons:
        raise DriverError("no reader-bar buttons found")
    button = buttons[index]
    p.long_press(*button.center, 800)
    item = None
    if p.wait_node(5, rid="textView1", text=menu_title):
        item = next((n for n in p.nodes() if n.text == menu_title and n.clickable), None)
    if item is None:
        p.step("bar-button-wrong")
        dismiss_popups(p)
        raise DriverError(f"reader-bar button {index} did not open the {menu_title!r} menu; set "
                          f"MOON_SYNC_DOWN_BUTTON / MOON_SYNC_UP_BUTTON to its position from the end")
    return item


def select(p: Phone, x1: int, y1: int, x2: int, y2: int) -> None:
    """Long-press the word at (x1, y1), then drag the end handle so the selection ends at (x2, y2)."""
    p.long_press(x1, y1)
    time.sleep(1.5)
    if not p.wait_node(3, rid="hb1"):
        # A dictionary app set to open on selection covers Moon+'s bar; Back closes it, the selection stays.
        if not p.in_reader():
            p.step("select-dictionary")
            p.back()
        if not p.wait_node(5, rid="hb1"):
            raise DriverError(f"no text selection at ({x1}, {y1})")
    dot2 = p.find(rid="dot2")
    if (x2, y2) != (x1, y1) and dot2:
        hx, hy = dot2.center
        # The handle hangs below the selected line; keep that offset when moving it to the end line.
        p.drag(hx, hy, x2, y2 + (hy - y1))
        if not p.wait_node(5, rid="hb1"):
            raise DriverError("selection bar disappeared after moving the end handle")


def tap_highlight(p: Phone, x: int, y: int) -> None:
    p.tap(x, y)
    if not p.wait_node(5, rid="penIv"):
        raise DriverError(f"no highlight at ({x}, {y}) (tapping it did not show the trash-bin bar)")


def set_note_text(p: Phone, note: str) -> None:
    if not p.wait_node(5, rid="noteEt"):
        raise DriverError("note editor did not open")
    field = p.d(resourceId=ID + "noteEt")
    p._retry(lambda: field.set_text(note))
    if p._retry(field.get_text) != note:
        raise DriverError("note text did not stick in the editor")


# --- Drive (read-only) ---------------------------------------------------------------------------


def drive_session():
    from google.auth.transport.requests import AuthorizedSession
    from google.oauth2 import service_account

    key = os.environ.get("MOON_DRIVE_SA_JSON")
    if not key or not os.environ.get("MOON_DRIVE_FOLDER_ID"):
        raise DriverError("MOON_DRIVE_SA_JSON and MOON_DRIVE_FOLDER_ID must be set (see .env)")
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
# Each command takes the Phone (or None for Drive-only commands) and returns a result dict.


def cmd_smoke(p: Phone) -> dict:
    version = p.moon_version()
    if not version:
        raise DriverError(f"Moon+ ({MOON_PACKAGE}) is not installed")
    p.ensure_awake()
    p.d.app_start(MOON_PACKAGE, wait=True)
    p.step("smoke-moon-open", 2)
    width, height = p.d.window_size()
    return {"device": p.d.serial, "android": p.sh("getprop", "ro.build.version.release").strip(),
            "screen": [width, height], "moon_version": version}


def cmd_dump(p: Phone, name: str = "dump") -> dict:
    p.step(name, 0)
    return {}


def cmd_ui(p: Phone, name: str = "ui") -> dict:
    """Capture the screen and list its widgets compactly (Moon+ ids without the package prefix):
    the quickest way to find what to tap when exploring a new Moon+ screen."""
    p.step(name, 0)
    xml = Path(p.steps[-1]).with_suffix(".xml").read_text(encoding="utf-8")
    skip = ("com.android.systemui", "com.samsung.", "com.sec.android.")
    widgets = []
    for m in NODE_RE.finditer(xml):
        a = dict(ATTR_RE.findall(m.group(0)))
        if a.get("package", "").startswith(skip):
            continue
        if not (a.get("resource-id") or a.get("text") or a.get("content-desc") or a.get("clickable") == "true"):
            continue
        widgets.append(" | ".join([a.get("class", "").rsplit(".", 1)[-1], a.get("resource-id", "").replace(ID, ""),
                                   a.get("text", "")[:60], a.get("content-desc", "")[:40], a.get("bounds", ""),
                                   "clk" if a.get("clickable") == "true" else ""]))
    return {"foreground": p.foreground(), "widgets": widgets}


def cmd_position(p: Phone) -> dict:
    """Report the reader's current position from its status line (chapter, page within it, percent)."""
    if not p.in_reader():
        raise DriverError("Moon+'s reader is not in front")
    p.step("position", 0)
    return status(p)


def cmd_open(p: Phone, book: str) -> dict:
    """Open a test book. Moon+ downloads the cloud position/notes on open (normal sync down) and,
    in 10.8, clears its in-memory deleted-notes list."""
    require_test_book(book)
    p.ensure_awake()
    path = f"{PHONE_TEST_DIR}/{book}"
    if p.d.shell(["ls", path]).exit_code != 0:
        raise DriverError(f"not on the phone: {path}")
    if p.in_reader():
        # Only leave a reader this driver opened: closing makes Moon+ upload that book's files.
        state = json.loads(STATE_FILE.read_text(encoding="utf-8")) if STATE_FILE.exists() else {}
        if state.get("token") != p.reader_token() or state.get("book") not in test_books():
            raise DriverError("another book is open in Moon+'s reader; close it on the phone first")
        cmd_close(p)
    mime = MIME_TYPES.get(Path(book).suffix.lower(), "*/*")
    # The reader activity isn't exported; Moon+'s exported "open file" entry point is ActivityMain
    # (VIEW, file:// and content:// schemes — see the 10.8 manifest), which then opens the reader.
    p.sh("am", "start", "-a", "android.intent.action.VIEW", "-t", mime, "-d", f"file://{path}",
         "-n", f"{MOON_PACKAGE}/{MAIN_ACTIVITY}")
    if not wait_for(p.in_reader):
        raise DriverError("Moon+ reader did not open")
    save_reader_state(book, p.reader_token())
    p.step("open", 2.5)  # let the page render and Moon+'s on-open sync start
    return {"book": book, **status(p)}


def cmd_close(p: Phone) -> dict:
    """Leave the reader. Moon+ uploads the position (and notes, if they changed) on pause."""
    p.ensure_awake()
    for _ in range(5):
        if not p.in_reader():
            break
        # After a jump (chapter list, prompt), Back first offers "return to <previous place>" / "Exit".
        exit_item = p.find(text="Exit")
        if exit_item and exit_item.clickable:
            p.tap(*exit_item.center)
        else:
            p.back()
        time.sleep(1.5)
    p.step("close", 0)
    if p.in_reader():
        raise DriverError("still in the reader")
    return {"activity": p.foreground()}


def cmd_force_stop(p: Phone) -> dict:
    """Force-stop Moon+ (clears its in-memory state, e.g. the deleted-notes list)."""
    p.sh("am", "force-stop", MOON_PACKAGE)
    return {"stopped": MOON_PACKAGE}


def cmd_restart(p: Phone, book: str) -> dict:
    """Restart the app: force-stop Moon+, then open the test book again."""
    require_test_book(book)
    cmd_force_stop(p)
    time.sleep(1)
    return cmd_open(p, book)


def cmd_read_to(p: Phone, book: str, chapter: str, pages: str = "0") -> dict:
    """Go to the start of a chapter (by its title in Moon+'s chapter list), then turn `pages` pages."""
    require_reader(p, book)
    dismiss_popups(p)
    open_more_operation(p, "Chapters in book")
    if not p.wait_node(8, rid="chListView01"):
        raise DriverError("chapter list did not open")
    p.step("chapters", 0.5)
    entry = None
    for _ in range(30):
        nodes = p.nodes()
        entry = next((n for n in nodes if n.rid == ID + "chapter_list_tv"
                      and n.text.rstrip(" √") == chapter), None)
        if entry:
            break
        listing = p.find(rid="chListView01", nodes=nodes)
        x1, y1, x2, y2 = listing.bounds
        p.drag((x1 + x2) // 2, y2 - 150, (x1 + x2) // 2, y1 + 150, 400)
        time.sleep(0.8)
    if entry is None:
        raise DriverError(f"chapter {chapter!r} not in the chapter list")
    p.tap(*entry.center)
    time.sleep(2)
    for _ in range(int(pages)):
        p.tap(*PAGE_TURN_TAP)
        time.sleep(0.8)
    p.step("read-to", 1)
    pos = status(p)
    if pos.get("chapter") != chapter:
        raise DriverError(f"expected chapter {chapter!r}, the status line says {pos.get('status')!r}")
    return {"book": book, **pos}


def cmd_turn(p: Phone, book: str, pages: str = "1", direction: str = "next") -> dict:
    """Turn `pages` pages forward (next) or back (prev) from the current page; reports the new position."""
    if direction not in ("next", "prev"):
        raise DriverError("direction must be 'next' or 'prev'")
    require_reader(p, book)
    dismiss_popups(p)
    x = PAGE_TURN_TAP[0] if direction == "next" else 1080 - PAGE_TURN_TAP[0]
    for _ in range(int(pages)):
        p.tap(x, PAGE_TURN_TAP[1])
        time.sleep(0.8)
    p.step("turn", 1)
    return {"book": book, **status(p)}


def cmd_select(p: Phone, book: str, x1: str, y1: str, x2: str, y2: str) -> dict:
    """Select text from the word at (x1, y1) to the character before (x2, y2); leaves Moon+'s bar open."""
    require_reader(p, book)
    dismiss_popups(p)
    select(p, int(x1), int(y1), int(x2), int(y2))
    p.step("select", 0.5)
    return {"book": book}


def cmd_add_highlight(p: Phone, book: str, x1: str, y1: str, x2: str, y2: str, note: str = "") -> dict:
    """Select, highlight with Moon+'s current highlight style, and optionally add a note."""
    require_reader(p, book)
    dismiss_popups(p)
    select(p, int(x1), int(y1), int(x2), int(y2))
    p.step("select", 0.5)
    p.tap_node("Highlight button", rid="hb1")
    # Moon+'s highlight-style dialog; OK applies the style already chosen there (never changed here).
    if not p.wait_node(5, rid="radioButton1"):
        raise DriverError("highlight style dialog did not open")
    p.tap_node("Highlight dialog OK", rid="okB")
    if not wait_for(lambda: p.find(rid="radioButton1") is None and p.find(rid="hb1") is None, 5):
        raise DriverError("highlight style dialog or selection bar did not close")
    p.step("highlighted")
    if note:
        p.tap(int(x1), int(y1))
        if not p.wait_node(5, rid="hb2"):
            raise DriverError("tapping the new highlight did not show Moon+'s bar")
        p.tap_node("Note button", rid="hb2")
        set_note_text(p, note)
        p.step("note-typed", 0.3)
        p.tap_node("note OK", rid="okB")
        p.step("note-saved")
    dismiss_popups(p)
    return {"book": book, "note": note or None}


def cmd_edit_note(p: Phone, book: str, x: str, y: str, note: str) -> dict:
    """Add or replace the note of the highlight at (x, y)."""
    require_reader(p, book)
    dismiss_popups(p)
    tap_highlight(p, int(x), int(y))
    p.tap_node("Note button", rid="hb2")
    old = p._retry(p.d(resourceId=ID + "noteEt").get_text) if p.wait_node(5, rid="noteEt") else None
    set_note_text(p, note)
    p.step("note-typed", 0.3)
    p.tap_node("note OK", rid="okB")
    p.step("note-saved")
    dismiss_popups(p)
    return {"book": book, "old_note": old, "note": note}


def cmd_delete_highlight(p: Phone, book: str, x: str, y: str, via: str = "trash") -> dict:
    """Delete the highlight at (x, y) via the trash-bin icon or the note popup's Delete button."""
    if via not in ("trash", "note-popup"):
        raise DriverError("via must be 'trash' or 'note-popup'")
    require_reader(p, book)
    dismiss_popups(p)
    tap_highlight(p, int(x), int(y))
    p.step("highlight-tapped", 0.3)
    if via == "trash":
        p.tap_node("trash-bin icon", rid="penIv")
    else:
        p.tap_node("Note button", rid="hb2")
        p.wait_node(5, rid="noteEt")
        p.step("note-popup", 0.3)
        p.tap_node("note popup Delete button", rid="thirdB")
    p.step("deleted")
    dismiss_popups(p)
    return {"book": book, "via": via}


def cmd_add_bookmark(p: Phone, book: str, name: str = "") -> dict:
    """Add a bookmark at the current page (Bookmarks → ADD NEW BOOKMARK), optionally renamed."""
    require_reader(p, book)
    dismiss_popups(p)
    open_more_operation(p, "Bookmarks")
    p.tap_node("ADD NEW BOOKMARK", rid="bmAddButton")
    if not p.wait_node(5, rid="noteEt"):
        raise DriverError("Add bookmark dialog did not open")
    default = p._retry(p.d(resourceId=ID + "noteEt").get_text)
    if name:
        set_note_text(p, name)
    p.step("bookmark-dialog", 0.3)
    p.tap_node("Add bookmark OK", rid="android:id/button1")
    p.step("bookmarked")
    dismiss_popups(p)
    return {"book": book, "name": name or default}


def cmd_delete_listed(p: Phone, book: str, text: str) -> dict:
    """Delete every entry of the reader's Bookmarks panel (highlights, notes and bookmarks of this book)
    whose listed text contains `text`, via its ⋮ menu → Delete (Moon+ deletes without asking)."""
    require_reader(p, book)
    dismiss_popups(p)
    if not text.strip():
        raise DriverError("text must not be empty")
    open_more_operation(p, "Bookmarks")
    if not p.wait_node(8, rid="bmListView01"):
        raise DriverError("Bookmarks panel did not open")
    p.step("bookmarks-panel", 0.5)
    deleted = []
    for _ in range(50):
        nodes = p.nodes()
        rows = [n for n in nodes if n.rid == ID + "tv1" and text in n.text]
        if not rows:
            break
        row = rows[0]
        # The row's ⋮ button shares the row's vertical band (it sits beside the time line above the text).
        menu = next((n for n in nodes if n.rid == ID + "overflow1" and n.bounds[1] <= row.bounds[1] <= n.bounds[3] + 40
                     and n.bounds[1] < row.bounds[3]), None)
        if menu is None:
            raise DriverError(f"no ⋮ button for {row.text!r}")
        p.tap(*menu.center)
        p.tap_node("Delete menu item", text="Delete")
        time.sleep(1.2)
        deleted.append(row.text)
    p.step("deleted-listed", 0.5)
    dismiss_popups(p)
    return {"book": book, "deleted": deleted}


def wait_prompt(p: Phone, timeout: float) -> Node | None:
    return p.wait_node(timeout, rid="android:id/message", contains="new reading position")


def cmd_sync_up(p: Phone, book: str) -> dict:
    """Reader-bar "Sync to cloud": upload the position now (Moon+ also uploads on close)."""
    require_reader(p, book)
    dismiss_popups(p)
    item = bar_button(p, SYNC_UP_BUTTON, "Sync to cloud")
    p.tap(*item.center)
    p.step("sync-up", 3)
    dismiss_popups(p)
    return {"book": book}


def cmd_sync_down(p: Phone, book: str, mode: str = "forced") -> dict:
    """Download the cloud position. forced: reader-bar "Sync from cloud" (prompts even for a .po
    with this phone's own device id); normal: close and reopen the book (Moon+'s on-open sync).
    Reports whether Moon+'s jump prompt is showing; answer it with answer-prompt."""
    if mode not in ("forced", "normal"):
        raise DriverError("mode must be 'forced' or 'normal'")
    require_reader(p, book)
    dismiss_popups(p)
    if mode == "normal":
        cmd_close(p)
        cmd_open(p, book)
    else:
        item = bar_button(p, SYNC_DOWN_BUTTON, "Sync from cloud")
        p.tap(*item.center)
    prompt = wait_prompt(p, 8)
    p.step("sync-down", 0.3)
    return {"book": book, "mode": mode, "prompt": prompt.text if prompt else None, **status(p)}


def cmd_answer_prompt(p: Phone, book: str, answer: str, timeout: str = "10") -> dict:
    """Answer Moon+'s jump prompt ("There is a new reading position in your Google Drive, sync with it?")."""
    if answer not in ("ok", "cancel"):
        raise DriverError("answer must be 'ok' or 'cancel'")
    require_reader(p, book)
    prompt = wait_prompt(p, float(timeout))
    if prompt is None:
        raise DriverError("Moon+'s jump prompt is not showing")
    p.step("prompt", 0)
    p.tap_node(f"prompt {answer}", rid="android:id/button1" if answer == "ok" else "android:id/button2")
    p.step("prompt-answered", 2)
    dismiss_popups(p)
    return {"book": book, "answer": answer, "prompt": prompt.text, **status(p)}


def cmd_fetch(_: Phone | None, book: str) -> dict:
    """Read <book>.po / <book>.an from the Moon+ Drive sync folder (read-only; .an decompressed)."""
    require_test_book(book)
    session = drive_session()
    CAPTURES.mkdir(exist_ok=True)
    stamp = f"{dt.datetime.now():%Y%m%d-%H%M%S}"
    result: dict = {"book": book}
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
    return result


COMMANDS = {
    "smoke": cmd_smoke, "dump": cmd_dump, "ui": cmd_ui, "position": cmd_position,
    "open": cmd_open, "close": cmd_close, "force-stop": cmd_force_stop, "restart": cmd_restart,
    "read-to": cmd_read_to, "turn": cmd_turn, "select": cmd_select, "add-highlight": cmd_add_highlight,
    "edit-note": cmd_edit_note, "delete-highlight": cmd_delete_highlight,
    "delete-listed": cmd_delete_listed, "add-bookmark": cmd_add_bookmark,
    "sync-up": cmd_sync_up, "sync-down": cmd_sync_down, "answer-prompt": cmd_answer_prompt,
    "fetch": cmd_fetch,
}
NO_PHONE = {"fetch"}
BOOK_COMMANDS = {"open", "restart", "read-to", "turn", "select", "add-highlight", "edit-note", "delete-highlight",
                 "delete-listed", "add-bookmark", "sync-up", "sync-down", "answer-prompt", "fetch"}


def run(command: str, args: list[str]) -> dict:
    """Run one command; never raises. Failures carry the error and a capture of the screen."""
    if command not in COMMANDS:
        return {"ok": False, "error": f"unknown command {command!r}; use one of {sorted(COMMANDS)}"}
    p = None
    try:
        if command in BOOK_COMMANDS:
            # Refuse before touching the phone or Drive (each command checks again itself).
            require_test_book(args[0] if args else "")
        p = None if command in NO_PHONE else Phone()
        result = COMMANDS[command](p, *args)
        return {"ok": True, "command": command, **result, "steps": p.steps if p else []}
    except TypeError as e:
        if "positional argument" not in str(e):
            raise
        return {"ok": False, "command": command, "error": f"bad arguments: {e}"}
    except Exception as e:  # noqa: BLE001 — every failure becomes a JSON result with a capture
        result = {"ok": False, "command": command, "error": f"{type(e).__name__}: {e}",
                  "steps": p.steps if p else []}
        if p is not None and not isinstance(e, NotATestBook):
            try:
                result["failure_capture"] = str(p.capture(f"FAIL-{command}"))
            except Exception as capture_error:  # noqa: BLE001
                result["failure_capture_error"] = str(capture_error)
        return result


def main(argv: list[str]) -> int:
    command, *args = argv or ["smoke"]
    result = run(command, args)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
