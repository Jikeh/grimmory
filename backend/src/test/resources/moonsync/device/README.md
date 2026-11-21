# Moon+ device fixtures

Positions and highlights captured from **Moon+ Reader Pro 10.8 on a real phone** (ticket 00b, driver in
`tools/moon-device/`). They are ground truth for the position tickets: a converter is right when it maps
these Moon+ positions to the same text Moon+ showed.

The format facts they prove are written up in
[`docs/moon-sync/moon-format-reference.md`](../../../../../../docs/moon-sync/moon-format-reference.md).

## Files

- `device/<book>.json`: one file per test book.
- `books/<book>.<ext>`: the test books that are clearly free to redistribute, **unmodified** (renamed from
  Moon+'s `TEST - …` file name; the SHA-256 in the fixture is the file's): Ulysses (Standard Ebooks, CC0) and
  Alice AZW (Project Gutenberg #19033). The others are not committed — the commercial PDF, and editions whose
  packaging isn't clearly free (Pride and Prejudice: Amazon; Pinocchio: De Agostini; Alice MOBI: Global Grey).
  Their fixtures have `"resource": null`, a `notCommittedBecause` reason and the file's SHA-256; tests that
  need the file look for it locally and skip when it is absent.

## Shape

```jsonc
{
  "book": {
    "moonFileName": "TEST - ULYSSES.epub",       // file name on the phone and in Moon+'s Drive files (<name>.po/.an)
    "resource": "moonsync/books/ulysses.epub",   // test resource path, or null when the book isn't committed
    "format": "EPUB", "edition": "…", "structure": "…",
    "sha256": "…", "size": 1438506               // of the exact file Moon+ read
  },
  "moonVersion": "10.8",
  "device": "…",
  "display": "…",                                // Moon+ display settings in force for these captures
  "notes": ["…"],                                // what the captures show, in words
  "captures": [
    {
      "id": "po-xii-chunk1-start",
      "kind": "po",                              // the .po Moon+ uploaded at that moment
      "raw": "1738611943423*15@1#0:37.2%",       // byte-exact .po content
      "parsed": {"format": "epub", "deviceId": "…", "chapter": 15, "split": 1, "offset": 0, "percent": "37.2", "scroll": null},
      "context": {"page": "…", "how": "…", "statusLine": ["XII (42/58)", "37.2%"], "pageStartsWith": "…"}
    },
    {
      "id": "U3",
      "kind": "probe",                           // a highlight made on the phone to pin a position to text
      "text": "Perfectly true, says Bloom.",     // the highlighted text exactly as Moon+ stored it
      "raw": "#\n157\nUlysses\n…",               // the .an record as uploaded (16 lines after '#')
      "parsed": {"chapter": 15, "split": 1, "position": 1, "length": 27},
      "anHeader": ["1438506", "indent:true", "trim:true"],
      "screen": {"from": [200, 177], "to": [553, 177], "line": 1},  // selection drag on a 1080x2340 screen
      "context": {"page": "po-xii-chunk1-start", "note": "…"}        // the .po capture of the page it was on
    },
    {
      "id": "del-U1",
      "kind": "deleted",                         // the *DELETED* record Moon+ uploaded after the probe was deleted
      "raw": "#\n0\n0\n0\n0\n15\n0\n83298\n…\n*DELETED*\n",
      "parsed": {"chapter": 15, "split": 0, "position": 83298}
    }
  ]
}
```

To re-capture a probe: open the book on the phone, reach the page of its `context.page` capture (the `.po`
`raw` value is where Moon+ was), and select from `screen.from` to `screen.to` with
`moon_device.py add-highlight`. Coordinates hold only for the display settings in `display`.
