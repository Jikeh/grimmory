"""Multi-step device scenarios built on moon_device's commands, checked against what Moon+ uploads.

    uv run python scenarios.py deletes     # samples steps 12-13: *DELETED* records and when they vanish

Prints one JSON object per step as it runs, then a summary; exits non-zero on the first failed
check. Every step's captures are listed in its line, so a failed run can be diagnosed afterwards.
"""

from __future__ import annotations

import json
import sys
import time

import moon_device as md

# Calibration (README "Calibration"): Pride and Prejudice, Chapter 2, page 1 of 3, Moon+'s current
# font/size on a 1080x2340 screen. Line n of the page has its text centre at y = 247 + 53 * (n - 1).
# A selection starts at the word under (x1, y1) and ends at the character boundary nearest (x2, y2):
# aim x2 at the right edge of the last character's ink (its centre drops it; the gap after it can
# pick up the next character).
BOOK = "TEST - Pride  Prejudice.epub"
CHAPTER = "Chapter 2"
LINE_A = (110, 618, 689, 618)    # line 8: 'I hope Mr. Bingley will like it, Lizzy.'  (Moon+ offset 396, length 39)
LINE_A_TEXT = "I hope Mr. Bingley will like it, Lizzy."
LINE_B = (110, 1624, 450, 1624)  # line 27: 'To-morrow fortnight.'
LINE_B_TEXT = "To-morrow fortnight."
UPLOAD_WAIT = 8  # seconds between leaving the reader and reading Drive (Moon+ uploads in the background)


class ScenarioFailed(Exception):
    pass


def records(an_text: str) -> list[list[str]]:
    """Split decompressed .an text into records (the lines after each '#' line)."""
    lines = (an_text[:-1] if an_text.endswith("\n") else an_text).split("\n")  # text ends with a newline
    out: list[list[str]] = []
    for line in lines:
        if line == "#":
            out.append([])
        elif out:
            out[-1].append(line)
    return out


def deleted(an_text: str) -> list[list[str]]:
    return [r for r in records(an_text) if "*DELETED*" in r]


def live_notes(an_text: str) -> list[list[str]]:
    return [r for r in records(an_text) if "*DELETED*" not in r]


class Run:
    def __init__(self) -> None:
        self.log: list[dict] = []

    def do(self, command: str, *args: str) -> dict:
        result = md.run(command, list(args))
        self.log.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
        if not result.get("ok"):
            raise ScenarioFailed(f"{command} failed: {result.get('error')}")
        return result

    def check(self, name: str, ok: bool, **details) -> None:
        entry = {"check": name, "ok": ok, **details}
        self.log.append(entry)
        print(json.dumps(entry, ensure_ascii=False), flush=True)
        if not ok:
            raise ScenarioFailed(f"check failed: {name}")

    def an_after_close(self) -> str:
        self.do("close")
        time.sleep(UPLOAD_WAIT)
        an = self.do("fetch", BOOK)["an"]
        return an["text"] if an else ""

    def open_on_page(self) -> None:
        pos = self.do("open", BOOK)
        if (pos.get("chapter"), pos.get("page")) != (CHAPTER, 1):
            pos = self.do("read-to", BOOK, CHAPTER)
        self.check("on calibrated page", (pos.get("chapter"), pos.get("page")) == (CHAPTER, 1),
                   status=pos.get("status"))


def scenario_deletes(r: Run) -> None:
    stamp = time.strftime("%H%M%S")
    trash_note, popup_note = f"trash {stamp}", f"popup {stamp}"

    # Step 12: highlight + single-line note, upload; reopen, delete via the trash-bin icon, close, fetch.
    r.open_on_page()
    r.do("add-highlight", BOOK, *map(str, LINE_A), trash_note)
    an = r.an_after_close()
    r.check("highlight uploaded with the calibrated text",
            any(rec[11] == trash_note and rec[12] == LINE_A_TEXT for rec in live_notes(an) if len(rec) > 12))
    r.open_on_page()
    r.do("delete-highlight", BOOK, str(LINE_A[0] + 300), str(LINE_A[1]), "trash")
    an = r.an_after_close()
    rec = next((d for d in deleted(an) if trash_note in d), None)
    r.check("trash-bin delete wrote a *DELETED* record", rec is not None and len(rec) == 16, record=rec)

    # Step 13: same with the note popup's Delete; reopening for it clears step 12's record.
    r.open_on_page()
    r.do("add-highlight", BOOK, *map(str, LINE_A), popup_note)
    an = r.an_after_close()
    r.check("second highlight uploaded", any(rec[11] == popup_note and rec[12] == LINE_A_TEXT
                                             for rec in live_notes(an) if len(rec) > 12))
    r.check("reopening cleared the trash-bin record", not any(trash_note in d for d in deleted(an)))
    r.open_on_page()
    r.do("delete-highlight", BOOK, str(LINE_A[0] + 300), str(LINE_A[1]), "note-popup")
    an = r.an_after_close()
    rec = next((d for d in deleted(an) if popup_note in d), None)
    r.check("note-popup delete wrote a *DELETED* record", rec is not None and len(rec) == 16, record=rec)

    # Reopen and make another change: the next upload no longer carries the record.
    r.open_on_page()
    r.do("add-highlight", BOOK, *map(str, LINE_B))
    an = r.an_after_close()
    r.check("reopen + change: earlier *DELETED* record gone", not any(popup_note in d for d in deleted(an)),
            deleted=deleted(an))
    r.check("the new change was uploaded with the calibrated text",
            any(len(rec) > 12 and rec[12] == LINE_B_TEXT for rec in live_notes(an)))

    # Tidy up: remove the last highlight (leaves only the driver's bookmark as a live note).
    r.open_on_page()
    r.do("delete-highlight", BOOK, str(LINE_B[0] + 100), str(LINE_B[1]), "trash")
    r.do("close")


SCENARIOS = {"deletes": scenario_deletes}

if __name__ == "__main__":
    name = sys.argv[1] if len(sys.argv) > 1 else ""
    if name not in SCENARIOS:
        sys.exit(f"usage: scenarios.py {'|'.join(SCENARIOS)}")
    run = Run()
    try:
        SCENARIOS[name](run)
        summary = {"scenario": name, "ok": True}
    except ScenarioFailed as e:
        summary = {"scenario": name, "ok": False, "error": str(e)}
    print(json.dumps(summary, ensure_ascii=False))
    sys.exit(0 if summary["ok"] else 1)
