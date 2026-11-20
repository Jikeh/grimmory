# moon-device

Drives **Moon+ Reader on a real Android phone** for the Moon+ sync feature's device checks — the things
the in-JVM Moon+ emulator can't prove on its own (does Moon+ accept a position Grimmory wrote? does a
delete write a `*DELETED*` record?). It uses `adb` over wireless debugging and
[uiautomator2](https://github.com/openatx/uiautomator2), and reads results straight from Moon+'s Drive
sync folder with a read-only service account. Moon+ itself is never modified; the driver only operates
its UI and app lifecycle.

Recipes run from the repo root with `just device <recipe>`. Without `just`, run the same commands from
this folder: `uv run python moon_device.py <command> [args]`, `uv run python scenarios.py <name>`.

**Testing something with Moon+? Read [PLAYBOOK.md](PLAYBOOK.md) first.** It covers the working loop,
the traps from earlier sessions and the known Moon+ controls.

Verified with: Samsung Galaxy S25 (Android 17, 1080×2340), Moon+ Reader Pro 10.8.

## One-time setup

1. `just device setup` — downloads Android platform-tools (`adb`) into `tools/moon-device/.tools/` (ignored)
   and installs the Python dependencies with uv.
2. Create `tools/moon-device/.env` (ignored):

   ```
   MOON_DEVICE_SERIAL=<phone IP:port from step 4>
   MOON_DRIVE_SA_JSON=<path to the service-account key file; never copy the key into the repo>
   MOON_DRIVE_FOLDER_ID=<Drive id of the folder Moon+ syncs to, shared read-only with the service account>
   ```
3. On the phone: **Settings → Developer options → Wireless debugging → on**, then
   **Pair device with pairing code**. It shows an `IP:port` and a 6-digit code.
   `just device pair <IP:pairing-port> <code>`
4. `just device connect <IP:port>` — use the address on the main Wireless debugging screen (a different
   port from pairing). Put the same address in `MOON_DEVICE_SERIAL`.
5. `just device smoke` — reports the device and Moon+ version, opens Moon+, and saves captures. The first
   run installs uiautomator2's helper apps on the phone (`com.github.uiautomator*`); remove them any time
   with `just device uninstall-helper`.
6. In Moon+: sync to Google Drive must be **automatic** (with "Sync via cloud buttons on reader bar
   manually only" on, nothing is uploaded when a book is closed).

### Reconnecting

The wireless-debugging port changes when the phone reboots or wireless debugging is toggled. If adb
reports the device `offline` or missing, read the current address on the phone's Wireless debugging
screen, run `just device connect <IP:port>` and update `MOON_DEVICE_SERIAL`. Pair again only if the
computer was removed from the phone's paired devices.

### Before a run

The phone must be on and **unlocked** (the driver wakes it and refuses a locked phone), Moon+ installed
and signed in to Drive sync, and nobody using it: the driver taps on the screen.

## Safety: test books only

On the phone, Moon+ uploads to its real Drive sync folder, so device runs only ever touch **test books**:
the allowlist `test_books.txt` (all names start with `TEST - `; note the double space in
`TEST - Pride  Prejudice.epub`). The driver refuses any book not on the list before connecting to the
phone or Drive (`just device test` checks this offline). In-reader commands also refuse to act unless the
reader in front is the one `open` started for that same book (the driver records the reader's activity
instance in `captures/reader-state.json`), and `open` refuses to leave a reader it didn't open. The driver
never writes to Drive (read-only scope), never changes Moon+ settings, and never deletes files on the phone.

Local copies of the test books live in `C:\Users\Eddy\Downloads\moon-an-samples\` (not in the repo; one is
a commercial book). `just device push-test-books '<folder>'` copies them to
`/storage/emulated/0/Books/MoonReader/TEST/` on the phone.

## Commands

Every command prints one JSON object and exits 0 on success, 1 on failure:

```json
{"ok": true, "command": "open", "book": "...", "status": "Chapter 2 (1/3)", "chapter": "Chapter 2",
 "page": 1, "pages": 3, "percent": "0.7%", "steps": ["captures/...-open"]}
{"ok": false, "command": "edit-note", "error": "DriverError: no highlight at (400, 618) ...",
 "steps": [...], "failure_capture": "captures/...-FAIL-edit-note"}
```

`steps` lists the captures saved along the way (`<stem>.png` screenshot + `<stem>.xml` UI dump);
a failure adds `failure_capture`, the screen at the moment it failed. Reader commands that can read it
also report the position from Moon+'s status line (`status`, `chapter`, `page`/`pages` within the
chapter, `percent`).

Names follow the Moon+ emulator's actions (ticket 09) so ticket 15's Java adapter can map them 1:1.
Run them with `just device run <command> [args]` (or `uv run python moon_device.py ...`).

| Command | Emulator action | What it does |
|---|---|---|
| `smoke` | — | device, Android and Moon+ version; opens Moon+ |
| `dump [name]` | — | capture whatever is on screen |
| `ui [name]` | — | capture, plus the foreground activity and a compact widget list (id, text, bounds) |
| `position` | report position | the reader's status line |
| `open <book>` | open book / sync down (normal) | opens the book via Moon+'s file intent; Moon+ downloads the cloud position/notes and clears its deleted-notes list |
| `close` | sync up | leaves the reader; Moon+ uploads the `.po` (and the `.an` when notes changed) |
| `force-stop` | — | `am force-stop` Moon+ |
| `restart <book>` | restart app | force-stop, then `open` |
| `read-to <book> <chapter> [pages]` | read to a position | chapter list → chapter start, then turn `pages` pages |
| `turn <book> [pages] [next\|prev]` | turn pages | tap the right (next) or left (prev) edge `pages` times; reports the new position |
| `select <book> x1 y1 x2 y2` | — | select text (see Calibration); leaves Moon+'s selection bar open |
| `add-highlight <book> x1 y1 x2 y2 [note]` | add highlight | select → Highlight → OK (Moon+'s current style); optional note |
| `edit-note <book> x y <note>` | edit highlight | tap the highlight at (x, y) → Note → replace the text → OK; reports `old_note` |
| `delete-highlight <book> x y trash\|note-popup` | delete highlight | trash-bin icon in the highlight bar, or Note → Delete |
| `delete-listed <book> <text>` | delete highlight | Bookmarks panel → every entry whose text contains `text` → ⋮ → Delete (no confirmation in Moon+); reports what it deleted |
| `add-bookmark <book> [name]` | add bookmark | Bookmarks → ADD NEW BOOKMARK at the current page; default name is Moon+'s |
| `sync-up <book>` | sync up | reader bar "Sync to cloud" (forced position upload) |
| `sync-down <book> forced\|normal` | sync down | forced: reader bar "Sync from cloud"; normal: close + open. Reports `prompt` if the jump prompt shows |
| `answer-prompt <book> ok\|cancel [timeout]` | answer the prompt | answers "There is a new reading position in your Google Drive, sync with it?" |
| `fetch <book>` | — | Drive `.po` and `.an` (decompressed text), with modified time and md5; raw files saved in `captures/` |

### Scenarios

`just device scenario deletes` (`scenarios.py`) reproduces the manual samples steps 12–13 and checks
every result in Drive (about 5 minutes):

1. highlight + note, close → the `.an` has it with the calibrated text;
2. reopen, delete via the trash-bin icon, close, fetch → a 16-line `*DELETED*` record;
3. highlight + note again, close → reopening cleared step 2's record;
4. reopen, delete via the note popup's Delete, close, fetch → a `*DELETED*` record;
5. reopen, highlight, close → the record is gone and the new highlight is there;
6. tidy up: delete that highlight.

## Calibration

Book text is drawn by Moon+'s own view (`MRTextView`), so words are not in the UI tree: selections use
screen coordinates on a fixed page. Menus, dialogs and buttons are normal widgets and are found by
resource id or text.

- **Book / page:** `TEST - Pride  Prejudice.epub`, **Chapter 2, page 1 of 3** (`read-to <book> "Chapter 2"`).
- **Layout:** Moon+'s current font and size on the S25 (1080×2340), status line on. Line *n* of the page has
  its text centre at **y = 247 + 53·(n − 1)**.
- **Calibrated selections** (in `scenarios.py`):
  - line 8, `(110, 618) → (689, 618)` = `I hope Mr. Bingley will like it, Lizzy.` (Moon+ chapter 2, offset 396, length 39)
  - line 27, `(110, 1624) → (450, 1624)` = `To-morrow fortnight.` (offset 1346)
- **How selection works:** a long-press at (x1, y1) selects the word under it; the driver then drags the end
  handle so the selection ends at the **character boundary nearest (x2, y2)**. Aim x2 at the right edge of
  the last character's ink: its centre drops the character, the gap after it can pick up the next one.
- **Recording new coordinates:** go to the page, `just device dump <name>`, open the PNG and read the pixel
  coordinates (or measure glyph edges from the screenshot), then check with `select` and `dump`, and
  confirm with `add-highlight` + `close` + `fetch` (the `.an` holds the selected text). Any change of font,
  size, margins, theme or screen invalidates the coordinates.

## Limits and Moon+ quirks

- **Coordinates and timing.** Selections depend on the calibration above. Steps wait for widgets to appear
  and stop moving before tapping, and `fetch` after `close` should wait ~8 s for Moon+'s background upload.
- **Dictionary on long-press.** On this phone a long-press also opens a dictionary app (a user setting);
  the driver presses Back to close it and keeps the selection.
- **Reader bar buttons** have no ids or labels and are user-customisable. Sync up/down long-press the last /
  third-last button and continue only if Moon+'s "Sync to cloud" / "Sync from cloud" menu appears;
  otherwise they fail. Set `MOON_SYNC_UP_BUTTON` / `MOON_SYNC_DOWN_BUTTON` (negative index from the end)
  after rearranging the bar.
- **Back after a jump** (chapter list, prompt) first shows a "back to <previous place> / Exit" menu;
  `close` taps Exit.
- **Android 17:** uiautomator2's device-info and current-app calls fail or are wrong, and its click RPC
  sometimes drops the connection, so the driver reads state through `dumpsys` and sends input with
  `adb shell input`. Screenshots, UI dumps and `set_text` go through uiautomator2 (with retries).
- **Book identity.** dumpsys does not show which file the reader has open; the driver relies on the reader
  instance recorded by `open` (switching books inside Moon+ by hand keeps the same instance — don't).
- **Observed Moon+ 10.8 behaviour:** a forced "Sync from cloud" shows the jump prompt for a cloud `.po`
  carrying this phone's own device id; deletes write `*DELETED*` records only until the book is next
  opened; when no notes remain, the uploaded `.an` is just the `*DELETED*` records with no header;
  bookmark records carry the file name as the book title, highlights the EPUB title.

## Test books on the phone and cleaning up

Test books live in `/storage/emulated/0/Books/MoonReader/TEST/`. At the end (ticket 23):

1. `just device remove-test-books` — deletes the phone's `TEST` folder and Moon+'s cached covers for those books.
2. In Moon+: remove the test books from the Recent list / shelf (long-press → remove) so they don't linger in history.
3. In the Moon+ Drive sync folder: delete `TEST - *.po` and `TEST - *.an` (only files whose names start with `TEST - `).
4. `just device uninstall-helper` — removes uiautomator2's helper apps.
5. Optionally turn off Wireless debugging and remove the paired computer on the phone.
