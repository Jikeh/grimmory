# grimmory-api

Lets agent sessions use the user's **running Grimmory instance** (`http://grimmory.jikeh.lan`) through its
REST API: look up books, progress, highlights and settings while developing, and (later) drive the
Grimmory side of Moon+ sync scenarios.

Run from the repo root with `just grimmory <recipe>`, or from this folder with
`uv run python grimmory_api.py <command> [args]`.

## Safety

This is the user's **own account and real library**.
- **Reads** (GET) are unrestricted.
- **Writes** (POST/PUT/PATCH/DELETE) go only through the `write` command, which refuses unless the target
  book is a **test book** — a book in the library named **TEST** — and the request's path or JSON body
  references that book id. A write to any other book is refused before anything is sent. (File names can't
  be used: Grimmory renames files on import with its naming pattern.)
- Don't change settings or users through `write`; it's for test-book progress and highlights.

## Setup

1. `just grimmory setup` (or `uv sync` here).
2. `tools/grimmory-api/.env` (ignored by git):

   ```
   GRIMMORY_URL=http://grimmory.jikeh.lan
   GRIMMORY_CREDENTIALS_FILE=<a file holding GRIMMORY_USER= and GRIMMORY_PASS=, e.g. readsync's .env>
   ```

   The tool reads the login from that file at run time; never copy the credentials into this repo.
3. `just grimmory check` — logs in and reports the book count.

## Commands

| Command | What it does |
|---|---|
| `check` | log in; report the instance URL and book count |
| `openapi` | fetch the instance's OpenAPI 3.1 spec from `/api/openapi.json` into `.cache/` (public docs: https://grimmory.org/api/) |
| `endpoints [text]` | list endpoints whose path, summary or tag contains `text` (uses the cached spec) |
| `get <path> [key=value …]` | GET any API path, e.g. `get /api/v1/books/84` |
| `find <text>` | books whose title or any file name contains `text`, with their ids, files and test-book flag |
| `book <id>` | one book, its file names, and whether it's a test book |
| `write <METHOD> <path> <bookId> [json]` | a write, refused unless `bookId` is a test book referenced by the path or body |

Every command prints one JSON object; non-zero exit on failure or refusal.

Useful endpoints for Moon+ sync work: `GET /api/v1/books/{id}` (book incl. `primaryFile.fileName`,
`alternativeFormats`), `POST /api/v1/books/progress` (the web reader's progress update, body names the
book), the annotations endpoints (`endpoints annotation`), reading sessions (`endpoints reading-session`).

## Test books

The **TEST** library (id 2, path `/booktest`) holds the same test books as the phone, imported from the
local samples folder. Its file naming pattern is `{title}` and each book's title is its test name, so the
files carry the same names as on the phone — and their contents are byte-identical (SHA-256 checked), so the
device fixtures apply. (Grimmory's file-name sanitizer collapses repeated spaces, so Pride and Prejudice was
renamed on the phone to match.)

| Grimmory book id | Grimmory file name | Phone / Moon+ file name |
|---|---|---|
| 314 | `TEST - ULYSSES.epub` | same |
| 315 | `TEST - CS FOR DUMMIES.pdf` | same |
| 316 | `TEST - Alices Adventures in Wonderland AZW.azw` | same |
| 317 | `TEST - Alices Adventures in Wonderland MOBI.mobi` | same |
| 318 | `TEST - Le avventure di Pinocchio.epub` | same |
| 319 | `TEST - Pride Prejudice.epub` | same (renamed on the phone from the double-space `TEST - Pride  Prejudice.epub`, which Grimmory can't produce) |

To rename a test book: set its title with
`run write PUT "/api/v1/books/<id>/metadata?replaceMode=REPLACE_WHEN_PROVIDED" <id> '{"metadata":{"title":"TEST - …"}}'`,
then `run write POST /api/v1/files/move <id> '{"moves":[{"bookId":<id>,"targetLibraryId":2,"targetLibraryPathId":2}]}'`.

`just grimmory run find "<title>"` shows them with `test_book: true`.
