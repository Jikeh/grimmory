# moon-device

Drives **Moon+ Reader on a real Android phone** for the Moon+ sync feature's device checks — the things
the in-JVM Moon+ emulator can't prove on its own (does Moon+ accept a position Grimmory wrote? does a
delete write a `*DELETED*` record?). It uses `adb` over wireless debugging and
[uiautomator2](https://github.com/openatx/uiautomator2). Moon+ itself is never modified; the scripts only
operate its UI and app lifecycle.

Everything runs from the repo root with `just device <recipe>`.

## One-time setup

1. `just device setup` — downloads Android platform-tools (`adb`) into `tools/moon-device/.tools/` (ignored)
   and installs the Python dependencies with uv.
2. On the phone: **Settings → Developer options → Wireless debugging → on**, then
   **Pair device with pairing code**. It shows an `IP:port` and a 6-digit code.
3. `just device pair <IP:pairing-port> <code>`
4. `just device connect <IP:port>` — use the address on the main Wireless debugging screen (a different
   port from pairing). Repeat this after the phone reboots or wireless debugging is toggled.
5. `just device smoke` — reports the device and Moon+ version, opens Moon+, and saves a screenshot and UI
   dump in `captures/`. The first run installs uiautomator2's helper apps on the phone
   (`com.github.uiautomator*`); remove them any time with `just device uninstall-helper`.

With several devices connected, set `MOON_DEVICE_SERIAL` to the one to use.

## Test books

The allowlist is `test_books.txt`. Local copies live in `C:\Users\Eddy\Downloads\moon-an-samples\` (not in the
repo; one is a commercial book). `just device push-test-books '<folder>'` copies them to
`/storage/emulated/0/Books/MoonReader/TEST/` on the phone.

### Cleaning up at the end

1. `just device remove-test-books` — deletes the phone's `TEST` folder and Moon+'s cached covers for those books.
2. In Moon+: remove the test books from the Recent list / shelf (long-press → remove) so they don't linger in history.
3. In the Moon+ Drive sync folder: delete `TEST - *.po` and `TEST - *.an` (only files whose names start with `TEST - `).
4. `just device uninstall-helper` — removes uiautomator2's helper apps.
5. Optionally turn off Wireless debugging and remove the paired computer on the phone.

## Notes

- Moon+ draws book text in its own view, so the words on a page are probably not visible to UI automation.
  Selecting text means long-pressing/dragging at screen coordinates with a fixed font, size and page;
  menus, popups and buttons are normal widgets and can be found by text or resource id.
- On the phone, Moon+ uploads to its real Drive sync folder, so device runs are kept safe by touching **only
  dedicated test books** (an allowlist in this folder). The driver refuses any other book.
- Scripts here are referenced by the Moon+ sync tickets in `.scratch/moon-sync/issues/`.
