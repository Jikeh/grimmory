# Moon+ differential harness

Runs **readsync** (the reference) and Grimmory's **Java Moon+ position library** on the same inputs and fails on
any difference. Test-only: nothing here ships in Grimmory's image.

```
just harness run            # build the reference image, write readsync's outputs, compare the Java side
just harness run --save     # same, and save every difference as a Java regression case
```

Exit code is non-zero on any difference. The report is `tools/moon-harness/out/report.md` (and `report.json`):
a summary per comparison, the known differences that were skipped and why, and for every differing input
the differing fields plus the input and both full outputs.

## How it works

1. **Reference image** (`reference/Dockerfile`, `just harness build`): built from readsync's *source tree*,
   not its runtime image, with its dev dependencies (`uv sync --frozen`) and the Node CFI bridge
   (`npm ci`). The readsync revision (short commit, `-dirty` if the tree has changes) is recorded in
   every output. The harness adapter (`reference/harness/`, Python package `harness`) is copied in beside it.
2. **Reference outputs** (`just harness reference`): the adapter finds each comparison's inputs, calls
   readsync's functions directly and writes `out/reference/<comparison>.json`: per case an `id`, the `input`
   and readsync's `output` as JSON. Book content goes through `FakeGrimmoryClient`, which answers readsync's
   `download_book` call from the corpus (book id = path under the corpus), so readsync's own
   `EpubContentCache`/`MobiContentCache` and `CfiBridge` run unchanged (`ReadsyncEnv`).
3. **Java side** (`just harness java [--save]`): in the JDK 25 image from `dev.docker-compose.yml`,
   `MoonDifferentialHarnessTest` (skipped unless `MOON_HARNESS_DIR` is set) runs every case through the Java
   comparison of the same name, compares the two outputs and writes the report.

Outputs are compared structurally (`JsonDiff`): same keys, same array order, numbers by value; two failures are
equal whatever their messages say. `.an` content is compared as decompressed text, never as zlib bytes.

## Inputs

| Folder | Mounted at | Default | Override |
|---|---|---|---|
| readsync source tree | build context | `../readsync` next to this repo | `MOON_HARNESS_READSYNC` |
| samples (`.po`, `.an`, device fixtures) | `/samples` (read-only) | `backend/src/test/resources/moonsync` | `MOON_HARNESS_SAMPLES` |
| corpus (EPUB/MOBI/PDF books) | `/corpus` (read-only) | `backend/src/test/resources/moonsync/books` | `MOON_HARNESS_CORPUS` |
| output | `/out` | `tools/moon-harness/out` (git-ignored) | `MOON_HARNESS_OUT` |

Point the corpus at your library (or a subset folder) for a full run, e.g.
`MOON_HARNESS_CORPUS=/path/to/books just harness run`. Samples are every `*.po` and `*.an` file under the
samples folder plus the captures in device fixtures (`device/*.json`, ticket 00b): `po` captures, `probe`
captures (one highlight record, with the file's header recorded beside it) and `deleted` captures.

Other commands: `just harness selftest` (the fake client and the Node bridge against the corpus),
`just harness test-reference` (the adapter's own pytest suite, in the reference image),
`just harness reference --only <name>` (one comparison; the others show as "not run" in the report). In a full
reference run, a comparison with a Java side but no readsync output (registered on one side only) fails the run,
and so does a comparison with no cases (e.g. a samples override holding no matching files).

## Comparisons

| Name | readsync | Java | Compared |
|---|---|---|---|
| `po-roundtrip` | `po_parser.parse_po`, `serialize_po`, `with_percent` | `MoonPosition` | parsed fields; serialization of the parsed fields (percent re-formatted to one decimal); percent-only rewrites at fixed probe percents (rounding) |
| `an-roundtrip` | `an_parser.parse_an_epub_header`, `parse_an_epub`, `serialize_an_epub` | `MoonAnnotationFile` | header; every record's fields; the records and header read back after each side's writer lays the whole file out afresh (Java: `MoonAnnotationFile.of`, Moon+'s 16-line layout) |

Tickets 04, 05, 06, 07 (foliate output), 08 (MOBI6 forward only), 13, 16, 19 and 20 each add one.

## Known differences (exempt from parity)

Deliberate deviations (spec, "Where this deliberately differs from readsync") and readsync errors found on the
device are never "fixed" to match readsync. The comparison declares them and the report counts them:

- **Note text** (ticket 02): readsync has no bookmark or note field and folds both slots into `text`. Java's
  `bookmark`/`note` are reported but not compared on their own; Java's `text` is compared folded the same way
  (`AnRoundTripComparison.readsyncText`), so the highlighted text is still checked on records with a note.
- **PDF `.an` files**: Grimmory rejects them (readsync parses them); readsync also takes `#A*#` anywhere in the
  file as PDF, Grimmory only on line 0 (exempt only when line 0 has no tag). Whole case exempt.
- **Header-less `.an` files** (only deleted-notes records) and **lone `#` columns**: Grimmory parses them,
  readsync can't. Whole case exempt.
- **Negative `.po` scroll suffix**: Grimmory accepts it, readsync takes digits only. Whole case exempt.
- **MOBI chapter → `filepos`** (for ticket 08's comparison): readsync's `resolve_mobi_chapter_filepos` is off by
  one where front matter precedes the first NCX entry, and `mobi6_chapter_fileposes` uses INDX record order
  instead of the NCX tree in document order (`docs/moon-sync/moon-format-reference.md`, "Differences from
  readsync's notes"). Cases affected must be exempted, and covered by the device fixtures
  (`device/alice-mobi.json`, `device/alice-azw.json`) instead.

## Adding a comparison

1. **Reference side**: a module in `reference/harness/` with `NAME`, `cases(roots)` (yield `Case(id, input)`;
   inputs are JSON: `{"file": "samples/…"}` / `{"file": "corpus/…"}` or `{"text": …}`, plus any keys you need)
   and `run(input, roots)` (readsync's output as JSON; failures as `{"error": "…"}`). Use `ReadsyncEnv` for book
   content and the bridge. List it in `COMPARISONS` in `reference/harness/__init__.py` and add a test in
   `reference/tests/`.
2. **Java side**: a `HarnessComparison` in `backend/src/test/java/org/booklore/service/moonsync/harness/` with
   the same name, whose `run` gives the same JSON shape from the Java library. Declare known differences with
   `exemption` (whole case) or `ignoredFields` (JSON pointers), each with its reason. List it in
   `MoonDifferentialHarness.COMPARISONS` and add a unit test.
3. `just harness run`, then document the comparison in the table above.

## Regression cases

`just harness run --save` writes each differing case to
`backend/src/test/resources/moonsync/harness/regressions/<comparison>/<slug>.json` (input, readsync's output as
`expected`, readsync revision), copying an input file beside it. `MoonHarnessRegressionTest` runs those cases in
the normal test suite, without readsync or Docker, so each one fails until the Java side is fixed. If a case turns
out to be a deliberate deviation, declare it in the comparison and delete the case. Corpus inputs can be books
that aren't free to redistribute: check before committing one.
