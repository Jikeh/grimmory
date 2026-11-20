# Testing with Moon+ on the phone: playbook

How to check something against the real Moon+ app, written from the first working session (ticket 00).
`README.md` is the reference: setup, the command table, calibration and limits. This file covers how to
work and what went wrong last time. Moon+ facts found on the device are in the spec's "Device findings"
section (`.scratch/moon-sync/spec.md`).

## 1. Before touching the phone

1. **Tell the user** you are about to operate the phone and roughly for how long (a full scenario takes
   about 5 minutes). They may be using it. If `ui` shows a book you didn't open, they are reading: stop
   and ask.
2. **Connection:** `.tools/platform-tools/adb.exe devices -l`. If the device is `offline` or missing,
   `adb connect <IP:port>`. If the port changed or pairing was lost, ask the user for the address on
   Settings → Developer options → Wireless debugging (and a pairing code if needed). Never guess a port.
3. **Look before acting:** `uv run python moon_device.py ui`. It saves captures and lists the widgets
   plus the foreground activity. Read the PNG too, since book text isn't in the widget list. The last
   session may have left a popup or selection open.
4. Run everything from `tools/moon-device/`: `uv run python moon_device.py <command> …` and
   `uv run python scenarios.py <name>`. `just` is not installed on this PC (`just device …` only works
   after the user installs it). The shell's working directory can reset between calls, so `cd` explicitly.

## 2. Hard rules

- **Test books only** (`test_books.txt`). Moon+ uploads to the user's real Drive folder. Every new command
  calls `require_test_book` / `require_reader` first and is listed in `BOOK_COMMANDS` and in
  `test_safety.py`'s `ARGS`. The driver never writes to Drive.
- **Don't change Moon+ or phone settings.** Highlight-style, bookmark and note dialogs contain settings
  checkboxes and style radios. Only press OK on a dialog whose effect you know; to back out of an
  unknown or mis-tapped dialog, press **Cancel**, never OK. (Last session a mis-timed tap selected
  "Squiggly" in the highlight-style dialog; Cancel kept the user's style.)
- **Don't delete or uninstall anything on the phone.** Cleanup is ticket 23. Keep a list of what you leave
  behind (current leftovers: the bookmarks "Driver bookmark" in chapter 1 and "Driver bookmark 2" in
  chapter 2 of Pride and Prejudice).

## 3. The loop that works

1. `open <book>`, then `read-to <book> "Chapter 2"` puts you on the calibrated page. `open` returns
   to wherever the book was left, so check `chapter`/`page` in its result.
2. Act with the driver commands. Each result lists `steps` (captures). On failure, read
   `failure_capture` (PNG + XML) before retrying.
3. `close`, wait **~8 s**, then `fetch <book>`, and assert on the decompressed `.an` / `.po` text.
   Split `.an` records on `#` lines; `scenarios.py` has `records()` / `deleted()` / `live_notes()`.
   Fields after `#`: 0 id, 1 title, 2 path, 3 lowercase path, 4 chapter, 5 split, 6 position,
   7 length, 8 colour, 9 time, 10 bookmark, 11 note, 12 original text, 13–15 style flags. Deleted
   records have no original text: 0–3 are `0`, 12–14 are the flags and 15 is `*DELETED*`.
4. For a repeatable check, add a scenario to `scenarios.py` (pattern: `Run.do` + `Run.check`, unique note
   text per run such as a timestamp, tidy up at the end) rather than running commands by hand.

## 4. Timing and ordering traps

- **Reopening the book clears Moon+'s deleted-notes list.** To see a `*DELETED*` record: delete, `close`,
  `fetch`, and only then reopen.
- **The `.an` uploads only after a note change; the `.po` only when the position changed** (or on a
  forced `sync-up`). If a fetch shows an old `modifiedTime`, nothing was uploaded; that isn't a failure
  of your action.
- **To get the jump prompt** without writing to Drive: move the local position away from the cloud one
  (`read-to <book> "Chapter 2" 2` turns 2 pages), then `sync-down <book> forced`, then
  `answer-prompt <book> ok|cancel`. `open` (normal sync) never prompted in our runs, but the cloud
  `.po` always matched the local position then. Whether a normal sync prompts for a differing position
  (own or another device ID) needs a Drive write that the driver doesn't do; that's for the
  Grimmory-side tickets.
- Dialogs slide in. Tap only once a widget's bounds are stable (`Phone.tap_node` does this). Raw taps
  on remembered coordinates during an animation hit whatever is underneath.

## 5. Selecting text

- Text isn't in the UI tree (Moon+ draws it in `MRTextView`). Select by coordinates on the calibrated page
  (README "Calibration": line *n* at y = 247 + 53·(n−1)).
- A long-press selects the word; the driver then drags the end handle (`dot2`) to (x2, y2). The end snaps
  to the **character boundary nearest x2**. Aim at the right edge of the last character's ink: its centre
  drops it, and the gap after it can pick up the next character (this happened with a closing quote).
- A long-press also opens the user's dictionary app (a Moon+ setting; leave it). The driver closes it
  with Back and the selection survives.
- To find coordinates for new text: take a `ui` capture, then measure glyph edges on a line from the PNG:

  ```python
  from PIL import Image  # Pillow comes with uiautomator2
  im = Image.open("captures/<stem>.png").convert("L"); y0 = 618
  ink = [x for x in range(1080) if any(im.getpixel((x, y)) > 150 for y in range(y0 - 22, y0 + 22))]
  ```

  Light text on a dark theme: runs of `ink` x values are glyphs. Confirm with `select` + `ui`, then with
  `add-highlight` + `close` + `fetch` (field 12 is the selected text).

## 6. Finding Moon+ controls

Prefer resource ids, then text; use coordinates only for book text. Known controls (Moon+ 10.8):

| Where | Controls |
|---|---|
| Selection / highlight bar | `hb0` Copy, `hb1` Highlight, `hb2` Note, `hb3` Dict., `penIv` trash bin (only on an existing highlight), handles `dot1` / `dot2` |
| Highlight style dialog | `radioButton1..4` styles, `okB` / `cancelB` |
| Note editor (`PrefEditNote`) | `noteEt`, `okB`, `cancelB`, `thirdB` Delete (existing notes only) |
| Reader bars (tap page centre) | `menuB` (⋮) → "More Operations..." → "Chapters in book" / "Bookmarks"; `buttonsLay` = bottom icons (no ids) |
| Chapter list | `chListView01`, entries `chapter_list_tv` (current one ends in " √") |
| Bookmarks | `bmAddButton` → dialog `noteEt` (name), `android:id/button1` OK |
| Jump prompt | `android:id/message` "There is a new reading position…", `button1` OK, `button2` Cancel |
| Status line | `statusMiddle` "Chapter 2 (1/3)", `statusRight` "0.7%" |

- The bottom-bar icons are unlabelled and the user can rearrange them. Sync from cloud is third from
  the end and sync to cloud is last. Long-pressing either shows a menu titled with its name; the
  driver checks that title before tapping.
- After any jump, Back first shows "back to <previous place> / Exit"; `close` taps Exit.
- The decompiled source helps map behaviour to ids: layouts in
  `C:\Users\Eddy\Downloads\moonreadersource\10.8\resources\res\layout\` (e.g. `pref_edit_note.xml`),
  handlers in `ActivityTxt.java`. Explore on the phone to confirm, since the user's settings change
  what shows.

## 7. Tooling quirks (Android 17, Windows)

- uiautomator2's `d.info`, `app_info` and `app_current()` fail or lie: use `dumpsys` (`Phone.foreground`,
  `reader_token`, `moon_version`). Its click RPC sometimes drops the connection: send input with
  `adb shell input` (`Phone.tap/long_press/drag`). Screenshots, dumps and `set_text` work (with retries).
- Type text with `set_text` on the field (accessibility); don't use `send_keys`, which can switch the
  keyboard (a phone setting).
- Git Bash rewrites `/storage/...` arguments: prefix raw adb calls with `MSYS_NO_PATHCONV=1`.
- Printing UI XML on the Windows console needs `PYTHONIOENCODING=utf-8` (Moon+ uses "√" and such).
- In-reader commands refuse a reader the driver didn't open (it records the reader instance in
  `captures/reader-state.json`). If that happens, the user (or you, if it is your test book) closes it,
  then `open` again.

## 8. Adding a command

1. Write `cmd_<name>(p, book, …)` in `moon_device.py`. Start with `require_reader(p, book)` (or
   `require_test_book`), call `dismiss_popups(p)`, find widgets with `tap_node`/`wait_node`, call
   `p.step("<name>")` after each meaningful step, and raise `DriverError` with a clear message on
   anything unexpected.
2. Register it in `COMMANDS` and, if it takes a book, in `BOOK_COMMANDS` and `test_safety.py` `ARGS`.
   Run `uv run python -m unittest test_safety`.
3. Name it after the Moon+ emulator action it stands for (ticket 09); ticket 15's Java adapter maps
   these names one to one. Add it to the README table.
