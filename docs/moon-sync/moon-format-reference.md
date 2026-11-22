# Moon+ Reader sync formats: `.po` and `.an`

What Moon+ Reader Pro writes to its Google Drive sync folder, and what its positions mean, for the formats
Grimmory syncs. Written for whoever implements the Moon+ position and highlight code (tickets 04–08, 16, 17,
19–21). Moon+ version: **10.8** on an Android phone (Samsung Galaxy S25, Android 17).

Every fact carries its source:

- **[device]**: seen on the phone with `tools/moon-device/`, with the fixture that shows it
  (`backend/src/test/resources/moonsync/device/<book>.json`, capture id in brackets).
- **[decompiled]**: read in the decompiled Moon+ 10.8 source (kept outside the repo); class and method named.
- **[readsync]**: from readsync's notes (`docs/connectors/moon*.md` in the readsync repo), not re-checked here.
- **[unknown]**: not settled; says what is missing.

Fixtures: `ulysses.json`, `pride-and-prejudice.json`, `pinocchio.json` (EPUB), `alice-mobi.json` (MOBI6),
`alice-azw.json` (MOBI6+KF8), `cs-for-dummies-pdf.json` (PDF).

## Files

One pair per book in the sync folder, named after the book's **file name** on the phone:
`<file name>.po` (position, plain text) and `<file name>.an` (notes, zlib-compressed text). [device]

Moon+ uploads both when the reader is paused: closing the book, but also switching to another app. On this
phone a long-press opens the user's dictionary app, and that alone made Moon+ sync (the user saw its sync
toasts), so **every text selection causes an upload**. [device] The `.an` is only uploaded when a note
changed; the `.po` only when the position changed, except on a forced "Sync to cloud". [device, ticket 00]

## `.po`: reading position

### Grammar

| Format | Line | Source |
|---|---|---|
| EPUB (and every reflowable format) | `{device}*{chapter}@{split}#{offset}:{percent}%` | [device] all fixtures |
| PDF | `{device}*{page}:{percent}%`, `page` 0-based | [device] cs-for-dummies-pdf |
| optional suffix | `…%|{webViewScrollOffset}` | [readsync]; never seen on device |

- `device` is a per-install random id; this phone always writes `1738611943423`. [device]
- `percent` has one decimal (`0.0%`, `32.6%`), except at the very end of a book, which is **`100%` with no
  decimal** [device: ulysses `po-end-of-book`, pride `po-end-of-book`]. Ticket 01's parser accepts it; positions
  Grimmory builds format as `100.0`, which differs from what Moon+ writes. Built percents round like readsync's
  `f"{p:.1f}"`: the double's exact binary value, half to even (`0.25` → `0.2`, `0.35` → `0.3`), not Java's
  `%.1f` (`0.3`, `0.4`). [differential harness, ticket 03]

### `chapter`: index into Moon+'s chapter list

- It is the **position in Moon+'s chapter list, which comes from the NCX flattened in document order**, not
  the spine index. Nested entries count (Ulysses' Part I/II/III navPoints are chapters 2, 6 and 19). [device:
  ulysses, pinocchio `po-capitolo-uno-start` = chapter 3 while its file is spine item 5; agrees with readsync]
- **Content before the first navPoint is chapter 0.** In Pride and Prejudice, chapter 0 ("Pride & Prejudice"
  in Moon+'s list) is the title page's cover image **plus** the table of contents at the head of the next file,
  up to the first navPoint's anchor. [device: pride `po-chapter0-start`, `P0a`, `P0b`]
- **A navPoint pointing at an anchor inside a file starts the chapter at that anchor**, not at the file start:
  P&P "Chapter 1" → `split_000.html#filepos2884`, and chapter 1 offsets count from the anchor. [device: pride;
  ticket 00's "any of them, Mr. Bingle" at 1919 ≈ 1921 from the anchor, ~2603 from the file start]
- **Reading order follows the NCX, not the spine.** In Pinocchio the NCX lists Copertina, Colophon,
  Frontespizio, Capitolo uno…trentasei, Dizionarietto, Indice, while the spine has `indice.xhtml` and
  `pre.xhtml` between Frontespizio and Capitolo uno. Moon+ shows Frontespizio (1 page), then Capitolo uno.
  [device: pinocchio]
- **The last navPoint's chapter runs from its file to the end of the spine.** Pinocchio's last navPoint,
  "Indice", points at spine item 3, so chapter 40 is `indice.xhtml` + `pre.xhtml` + every chapter again +
  `diz.xhtml` + `titoli_collana.xhtml`: ~130 pages, status line 99.2% on its page 4. Files with no navPoint
  (`pre.xhtml`) are only reachable inside it. [device: pinocchio `po-indice-p4`, `Pi7`, `po-end-of-book`]
- **What a chapter contains** [decompiled `Epub.getChaptersFromOpf`, `checkChapterAdditionalText`,
  `getChapterTextWithIDTag`, `getMoreContentFromNextID_Tag`, `recheckChapters`; device: pride, pinocchio]. Ported
  in `MoonChapterAssembly` (ticket 04); each piece of a chapter's HTML is traced back to its spine file:
  - Each file contributes its `<body>` content (`T.getHtmlBody`).
  - A chapter with a `#fragment` starts at the tag holding that id (found by string search for `id="…"`,
    `name="…"`, then bare quotes) and steps back over opening tags directly before it; it ends at the next chapter's
    id tag when that chapter is in the same file. A fragment within the first 500 characters of the body (or 100 and
    a quarter of it) of a file the previous chapter isn't in is ignored: the chapter starts at the body start.
  - Spine files between a chapter's file and the next chapter's (or an earlier-in-the-spine later chapter's: that is
    why Pinocchio's "Frontespizio" stops before `indice.xhtml`) are appended to it, each after `<a name=FILE>` and the
    page break `<hr2>`. With at most two files between, only files no chapter claims are appended; with more, every
    one of them is, already-used chapters included. That is the repeat in Pinocchio's chapter 40: `indice.xhtml` then
    every file to the spine end.
  - Unclaimed files before the first chapter's file are chapter 0; so is the body before the first chapter's
    anchor when longer than 100 characters (P&P: title page, `<hr2>`, the table of contents). The head of a file
    before a later chapter's anchor goes to the chapter before it when long enough.
  - An ID-addressed chapter has its HTML comments removed (`ifContentEmpty`), and becomes `<center><h2>title</h2>`
    when nothing is left.
  - `<hr2>` adds no character itself: it is a paragraph break plus a zero-width page-break span
    (`HtmlToSpannedConverter`).
  - [unknown] Pinocchio's chapter 40, chunk 2: the device's end-of-book offset (65,889, at the last page's start)
    is ~1,300 characters past what readsync's renderer counts for that chunk of the assembled chapter (64,575; a
    plain concatenation without Moon+'s markers gave 64,059). The markers shift the chunk cuts and explain ~500 of the
    original ~1,900-character gap; the rest isn't in the assembly (the chunk boundaries and the file list match the
    decompiled rules and the device's 3 chunks). Left for ticket 05's renderer (suspects: the glossary's table and the
    many `&nbsp;` paragraphs).
  - Not ported (reported as unsupported, never guessed): books whose NCX Moon+ picks by file name differently from
    the OPF, or reads where readsync can't (not named in the OPF, no namespace), or reads differently (a navPoint
    outside `navMap`/`navPoint`, several `content`s, a `content` with other attributes but no `src`, a `%23` in a
    `src`); books Moon+ reads through an EPUB 3 nav document (no usable NCX; a nav at least as long as the NCX
    whose entries differ, or that ends at an `<a>` without `href`; a nav that doesn't parse here, which Moon+'s parser
    may still read); a one-navPoint NCX over at most two spine files; and books whose Moon+ chapter numbers differ from
    readsync's: front matter one side counts and the other doesn't, a navPoint whose file isn't in the zip or whose
    label has no text, a bare `#`, fewer than two resolving navPoints, or chapters `deleteBrokenChapters` drops.
    [decompiled `Epub.getTocFilename`, `addNCXChapters`, `getCName`, `getChapter`, `getChaptersFromOpf`,
    `recheckChapters`]

  - When Moon+ takes the EPUB 3 nav over the NCX (a nav at least as long, with the same entries), the chapters
    carry the nav's labels and nesting. [decompiled `Epub.getChapters`, `addEpub3NCXChapters`]

### `split`: chunk of an oversized chapter

Moon+ cuts any chapter whose `<body>` inner HTML is longer than 100,000 characters into chunks and shows one
chunk at a time. [device: ulysses; [decompiled] `A.adjustChapterHtml`, `A.createSplitHtmls`; agrees with
readsync]

- The input is the `<body>` inner HTML (`T.getHtmlBody`), measured in Java `char`s. [decompiled]
- From position 100,000, the cut goes at the next `<p` (then `<P`, `<div`, `<DIV`, tried in that order). If the
  first one found is more than 20,000 characters further on, Moon+ cuts right after the next `>` instead. The
  next chunk's search starts 100,000 after the cut. [decompiled; device: ulysses XV chunk @3]
  - Because the `<p` search comes first, a `<div` closer than a distant `<p` is not used. [decompiled]
  - The `>` fallback can cut **mid-paragraph, right after an opening tag**. Ulysses XV chunk @3 starts
    "He touches the keys again." after an opening `<i …>`; Moon+ draws it in roman, outside its table. Nothing
    re-opens the cut `<i>` or table. [device: ulysses `po-xv-chunk3-start`, `U5`]
  - Only an unmatched opening `<div …>` tag is carried into the next chunk. [decompiled; readsync]
- 100,000 is the default of the preference `maxHtmlChapterSize5`. [decompiled `A.java`] Every chunk boundary
  seen on this phone matched 100,000. [device]
- Chunks are numbered from 0. Offsets **restart at 0 in each chunk**: the `.po` at a chunk's first page is
  `{chapter}@{split}#0`, and its first character is position 0. [device: ulysses `po-xii-chunk1-start`,
  `U3`, `po-xv-chunk3-start`, `U5`]
- Each non-final chunk ends with a visible `[n/N]` marker (blue, after a line break) when the chapter is under
  2,000,000 characters. [device; decompiled]
- A chapter merged from several files (Pinocchio's chapter 40) is split the same way: the end of that book is
  `40@2#65889`. [device: pinocchio `po-end-of-book`]

### `offset`: character position in the chunk's rendered text

`offset` counts characters in the text Moon+ renders for the chunk (its `Spanned` buffer), not raw HTML and not
bytes. [decompiled `MRTextView`; readsync; device]

- **Positions don't depend on display settings.** The same text got the same position at the user's settings,
  at font 24 / line spacing 12 / margins 60, and in landscape, and highlights made at one setting land on
  the same words at the others. [device: pride `P1`/`I1` 396, `P2`/`I2` 0, `P3`/`I4` 934, `P5`/`L3` 2508,
  `L1` 1349 = "To-morrow" 1346 + 3]
- In the `.po`, `offset` is where the page on screen starts:
  - At the user's settings and in landscape it is the page's first character [device: pride
    `po-baseline-ch2-p2` 1993 = `P4`; `po-inv2-ch2-p2` 1368 = the quote before `L2` "Aye" at 1369].
  - At line spacing 12 it was the start of the page's **second** line (534, page starting with "mother" at 500)
    [device: pride `po-inv1-ch2-p2`, `I3`]. So a `.po` offset can point a line past the visible top; matching
    must tolerate that.
  - A chapter that opens with an image has `offset` 1 at its first page (`0@0#1`) [device: pride
    `po-chapter0-start`]; otherwise a chapter start is `#0`.
- What counts as a character [device, all matching a whitespace-collapsed tag-strip of the source]:
  - one per precomposed accented letter (NFC), guillemet, curly apostrophe and `…` [pinocchio `Pi2`–`Pi6`], and
    per dash (`―` U+2015) [ulysses `U3`, `U4`];
  - invisible characters count: Ulysses' `U+FEFF U+200A U+FEFF` before "…" are 3 characters [ulysses `U4`];
  - a paragraph break is **one** character [ulysses `U4`, pride `P3`];
  - headings are part of the text: "Chapter 2" / "Capitolo uno" are at position 0 [pride `P2`, pinocchio `Pi1`];
  - a line holding only `&nbsp;` is not in the text (`.an` header `trim:true`) [pride `P0b`].
- [unknown] The exact renderer. A naive tag-strip is off by 1–2 characters in places (pride chapter 1: 1919 vs
  1921; pinocchio chapter 40: 5578 vs 5577). Ticket 05 ports readsync's Moon renderer, and these fixtures are
  its test cases.

### Status line vs `.po`

Moon+'s status line ("XII (42/58)", "37.2%") is not reliable across chunks: the same Ulysses page showed 1/24,
41/58 and 42/58-style counts, and Pinocchio's chapter 40 showed 99.2% on its 4th of 130 pages. Use the `.po`.
[device: ulysses notes]

### Forced "Sync to cloud" can upload a stale position

Right after a chapter-list jump, or a page turn back into the previous chapter, "Sync to cloud" uploaded the
**old** chapter's start (`3@0#0`, `14@0#0`) while the screen showed another page; closing the reader uploaded
the real position. [device: ulysses `po-forced-sync-stale-after-jump`, `po-forced-sync-stale-after-prev`]

## `.an`: notes, highlights and bookmarks

zlib-compressed UTF-8 text. [device; readsync]

### Header

Three lines, then records: the book file's size in bytes, `indent:<bool>`, `trim:<bool>` (Moon+'s
"indent paragraph" and "trim blank space" settings). This phone writes `indent:true`, `trim:true`. There is no
header when the book has no live notes; then the file is only `*DELETED*` records.
[device: all fixtures, ulysses `del-U1`; [decompiled] `BookDb.exportNotesToText`]

### Live record

`#`, then 16 lines in the `notes` table's column order [device; [decompiled] `exportNotesToText`; readsync]:

| # | Field | Seen on device |
|---|---|---|
| 0 | id | increases across books and sessions on this phone (130, 155…188) |
| 1 | title | EPUB title for highlights ("Ulysses", "Pride & Prejudice"); **file name** for bookmarks |
| 2 | path | `/sdcard/Books/MoonReader/TEST/<file>` |
| 3 | lowercase path | |
| 4 | chapter | as in `.po` |
| 5 | split | as in `.po` |
| 6 | position | start offset, as in `.po` |
| 7 | length | characters selected (a paragraph break counts 1); a bookmark's is `0` |
| 8 | colour | `1996532479` for the user's current style |
| 9 | time | creation, ms since epoch |
| 10 | bookmark | bookmark name; empty for highlights |
| 11 | note | note text; newlines stored as `<BR>` |
| 12 | original | the selected text; a paragraph break inside it is stored as `<BR>` [pride `P3`] |
| 13–15 | underline, strikethrough, bak (squiggly) | `0`/`0`/`0` for the default style |

### Note slot

Confirmed on Moon+ 10.8 uploads [device: `backend/src/test/resources/moonsync/format/notes/`, steps 01–13,
checked against the app's own `notes` table; [decompiled] `BookDb.exportNotesToText`, `alGet`]:

- The note is **always field 11**, between bookmark (10) and original (12), empty or not. Records are read by
  position; readsync's "strip blank lines as padding" would move a real note into the text.
- Export replaces `\n` with `<BR>` in **every** column; import turns every `<BR>` back into `\n`
  (case-sensitive). There is no escape for a literal `<BR>`: typed into a note, it comes back as a line break.
- Note text is stored exactly as the text box returns it, never trimmed. Every sample note and bookmark name
  ends with a space; it comes from the phone keyboard, not Moon+ (`PrefEditNote`). Accents, quotes, `#$*:`
  and emoji are stored verbatim.
- A bookmark keeps its name in field 10 with empty note and original; a squiggly highlight has `bak` `1`.
- An edited note rewrites the same record; the old text isn't kept anywhere.
- Deleted records write the note unescaped (real newlines), but import still decodes `<BR>`. Grimmory writes
  every note `<BR>`-encoded, deleted records included, so each record keeps Moon+'s fixed 16 lines;
  Moon+'s own multi-line deleted records are left verbatim while unchanged.
- Grimmory treats `\r\n` and `\r` as line breaks when encoding (Moon+'s `readLine` would split on them).

In code: `MoonAnnotationRecord.noteText()` / `highlightedText()` decode, `withNoteText()` encodes; the raw
`note()` / `text()` stay as stored.

### Deleted record

`#`, `0`, `0`, `0`, `0`, chapter, split, position, `0`, `0`, time, bookmark, note (newlines **not**
escaped), `0`, `0`, `0`, `*DELETED*`. Written for deletes through the trash-bin icon, the note popup's Delete
and the Bookmarks panel's ⋮ → Delete. It keeps chapter **and split** [device: ulysses `del-U1`, `del-U5`;
ticket 00]. Moon+ keeps deletions in memory only until the book is reopened, so only the next upload carries
them. [device, ticket 00; [decompiled] `deletedNotesToText`]

### Notes that share a (chapter, split, position) get lost

A highlight created at the same chapter, split and position as another note **silently disappeared**: no
`*DELETED*` record, gone from Moon+'s own list [device: pride notes]:

1. A highlight on the "Chapter 2" heading (2/0/0), where the bookmark "Driver bookmark 2" already was: both
   vanished.
2. Highlights re-created at 2/0/0, 2/0/396 and 2/0/934 while the cloud `.an` still held `*DELETED*` records
   for those positions: each was gone after the reader next resumed (one had already been uploaded once).
3. Once an upload without those `*DELETED*` records had replaced the cloud file, the same highlights stayed.

Likely mechanism, [unknown] whether exactly: when the reader resumes (including after the dictionary app),
Moon+ downloads the cloud `.an` and imports it. The import deletes local notes by (chapter, split, position)
(`BookDb.importNotesFromText`; `BookDb.removeNote` falls back to deleting by position alone when the text
doesn't match). [decompiled] Consequences for Grimmory:
- never write two notes at the same (chapter, split, position);
- after a `*DELETED*` record for a position, don't create a new note there until a later `.an` has dropped it.

The export also keeps only notes that match Moon+'s current annotation list by (chapter, split, position)
(`BookDb.noteInAnnots`). [decompiled]

## Text selection quirks (for the device driver and probe design)

- Long-press selects a word. Word boundaries break at a curly apostrophe ("I’m" → "m", "C’era" → "era"), at an
  em dash ("—That’s" → "That’s") and at a hyphen ("To-morrow" → "morrow"). [device]
- The end handle snaps to the character boundary nearest the drop point; dropping on the last glyph's ink edge
  often loses a final full stop or comma. [device]

## Differences from readsync's notes

- **Reading order and chapter contents when the NCX disagrees with the spine** (Pinocchio) aren't in readsync's
  notes: chapter index and reading order follow the NCX, and the last navPoint's chapter runs to the spine end.
- **A chapter's text spans files**: readsync measures an offset inside the chapter's first file and clamps it to
  that file's end; Moon+ assembles the chapter from several files (see "What a chapter contains"), so Pinocchio's
  `Pi7` (40@0#5578, three files into chapter 40) can't be reached the readsync way.
- **Chapter 0 can span two files** and stops at a mid-file anchor (Pride and Prejudice). readsync has "content
  before the first navPoint is chapter 0"; this adds that it crosses files and that the anchor splits a file.
- **The `.po` offset isn't always the page's first character** (line spacing 12). readsync treats it as the
  first visible character.
- **MOBI chapter → `filepos` is off by one** where front matter precedes the first NCX entry: readsync's
  `resolve_mobi_chapter_filepos` takes `fileposes[chapter]`, but Moon+ chapter *n* is NCX entry *n−1*
  (Alice MOBI).
- **MOBI chapter order is the NCX tree flattened in document order**, not the INDX record order readsync's
  `mobi6_chapter_fileposes` returns (Alice AZW: 2 chapters apart).
- readsync adds the `.po` `offset` (rendered characters) to a byte `filepos`; it is characters, so that sum
  is only approximate.
- **`100%` without a decimal** at the end of a book.
- **Forced "Sync to cloud" can upload a stale position.**
- **Lost notes at a shared (chapter, split, position)**: new.
- **Note text** is read from its fixed slot and synced; readsync reads every note as empty (its writer always
  leaves the slot blank).
- Upgraded from readsync/decompiled to **[device]**: the split rule including the `>` fallback, the
  per-chunk offset reset and the `[n/N]` marker; NCX-ordinal chapter index; invisible characters and
  paragraph breaks counting one each.

## MOBI family

Test files: `TEST - Alices Adventures in Wonderland MOBI.mobi` is **MOBI6** (file version 6, no KF8 section);
`TEST - Alices Adventures in Wonderland AZW.azw` is a **joint MOBI6+KF8** file (`BOUNDARY` record, KF8 from
record 66, EXTH 121), from Project Gutenberg #19033. [file headers; fixtures `alice-mobi.json`,
`alice-azw.json`]

- **Same `.po` grammar, chapter list and offsets as EPUB.** Moon+ loads MOBI-family files through its EPUB code
  path (`Epub` over `MyZip_Mobi` / the native `MLoader`) [readsync, decompiled]. On the device: chapter starts
  are `{n}@0#0`, headings are at 0, `.po` is the page's first character, and the end of the book is `100%`.
  [device: alice-mobi `po-chapter1-start`, `A1`, `A4`, `po-end-of-book`]
- **`offset` is rendered characters, not bytes**: probes are 2–4 characters from a naive character count of
  the decoded text starting at the chapter's NCX `filepos`, while a byte count would be off by 2 for every
  curly quote or dash. [device: alice-mobi `A2`–`A5`]
- **Chapter 0 is the front matter before the first NCX entry** (listed under the book title), so Moon+
  chapter *n* is NCX entry *n−1* in MOBI6 Alice (chapter 1 = filepos 6177). [device: alice-mobi]
- **The chapter list is the NCX tree flattened in document order**, not the order of the compiled NCX
  (`INDX`) records. In the AZW, the copyright entry is the last INDX record but a child of "SAM'L GABRIEL
  SONS…", and Moon+ lists it 4th, so "I—DOWN THE RABBIT-HOLE" is chapter 5. [device: alice-azw
  `po-chapter-i-start`]
- **Images count** in MOBI text as in EPUB: chapter 0 opening with a cover image is `0@0#1`, and the first
  line after an illustration is 3 characters further than a naive count. [device: alice-azw]
- [unknown] **Which half of a joint MOBI6+KF8 file Moon+ reads.** The loader is native and both halves carry
  the same text; the styled rendering (centred headings and images, paragraph spacing) suggests KF8.
  Settle it in ticket 07 by comparing a probe where the two halves' markup differs.

## PDF

Test file: `TEST - CS FOR DUMMIES.pdf` (338 pages, commercial, not committed). [fixture `cs-for-dummies-pdf.json`]

- **`page` is 0-based**: displayed page 1 → `0`, 4 → `3`, 23 → `22`, the last page 338 → `337`. [device:
  `po-page1`, `po-page4`, `po-page23`, `po-last-page`]
- **`percent` = (page + 1) / pages, truncated to one decimal**, not rounded: 1/338 = 0.296 → `0.2%`,
  4/338 = 1.18 → `1.1%`; the last page is `100%`. [device] Whether EPUB percent truncates too is [unknown].
- **Text reflow doesn't change the format**: in reflow mode, page 24 was `…*23:7.1%`, with no in-page offset
  and no `|scroll` suffix. Reflow only has a button once PDF Options → "Text Reflow button on top bar" is on.
  [device: `po-reflow-page24`]
- The reader's status line shows the outline title and `page/pages` (1-based). [device]
- PDF highlights were not probed: Grimmory doesn't sync them (ticket 01 rejects PDF `.an`).
