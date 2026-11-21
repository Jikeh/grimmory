"""Talk to the user's running Grimmory instance (http://grimmory.jikeh.lan/) from agent sessions.

Uses the user's own account. Safety model:
- Reads (GET) are unrestricted.
- Writes (POST/PUT/PATCH/DELETE) are refused unless they target a **test book**: a book in the library
  named "TEST" (Grimmory renames files on import, so file names can't be used). The request must name
  that book id, and the path or JSON body must reference it.

Credentials are never stored here: .env points at a file that holds GRIMMORY_USER / GRIMMORY_PASS
(readsync's .env), and this tool reads them from there.

Commands (results printed as JSON):
    check                               log in and report the instance's book count
    get <path> [key=value ...]          GET any API path, e.g. get /api/v1/books/123
    find <text>                         books whose title or any file name contains <text> (case-insensitive)
    book <id>                           one book, with its file names and whether it's a test book
    write-library <METHOD> <path> <libraryId> [json]
                                        a library-level write, refused unless the library is named TEST
    openapi                             fetch the instance's OpenAPI spec (/api/openapi.json) into .cache/
    endpoints [text]                    list endpoints whose path, summary or tag contains <text>
    write <METHOD> <path> <bookId> [json]
                                        a write request, refused unless <bookId> is a test book and the
                                        path or JSON body references it
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import requests

HERE = Path(__file__).resolve().parent
ENV_FILE = HERE / ".env"
TEST_LIBRARY = "TEST"
WRITE_METHODS = {"POST", "PUT", "PATCH", "DELETE"}


class Refused(Exception):
    """A write that isn't allowed by the test-book rule."""


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.lstrip().startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def settings() -> tuple[str, str, str]:
    env = {**read_env(ENV_FILE), **{k: v for k, v in os.environ.items() if k.startswith("GRIMMORY_")}}
    url = env.get("GRIMMORY_URL", "").rstrip("/")
    creds = read_env(Path(env["GRIMMORY_CREDENTIALS_FILE"])) if env.get("GRIMMORY_CREDENTIALS_FILE") else env
    user, password = creds.get("GRIMMORY_USER"), creds.get("GRIMMORY_PASS")
    if not (url and user and password):
        raise SystemExit("set GRIMMORY_URL and GRIMMORY_CREDENTIALS_FILE (or GRIMMORY_USER/GRIMMORY_PASS) in .env")
    return url, user, password


class Grimmory:
    def __init__(self) -> None:
        self.url, user, password = settings()
        self.http = requests.Session()
        resp = self.http.post(f"{self.url}/api/v1/auth/login", json={"username": user, "password": password}, timeout=30)
        resp.raise_for_status()
        data = resp.json()
        token = data.get("accessToken") or data.get("token")
        if not token:
            raise SystemExit(f"login response had no token (fields: {sorted(data)})")
        self.http.headers["Authorization"] = f"Bearer {token}"

    def request(self, method: str, path: str, **kwargs):
        if not path.startswith("/"):
            path = "/" + path
        resp = self.http.request(method, f"{self.url}{path}", timeout=60, **kwargs)
        try:
            body = resp.json()
        except ValueError:
            body = resp.text
        return resp.status_code, body

    def book(self, book_id: int) -> dict:
        status, body = self.request("GET", f"/api/v1/books/{book_id}")
        if status != 200:
            raise Refused(f"book {book_id} not readable (HTTP {status})")
        return body


def file_names(book: dict) -> list[str]:
    names = []
    primary = book.get("primaryFile") or {}
    if primary.get("fileName"):
        names.append(primary["fileName"])
    for alt in book.get("alternativeFormats") or []:
        if alt.get("fileName"):
            names.append(alt["fileName"])
    return names


def is_test_book(book: dict) -> bool:
    """A book in the library named TEST (case-insensitive)."""
    return (book.get("libraryName") or "").strip().upper() == TEST_LIBRARY


def require_test_book_write(g: Grimmory, book_id: int, path: str, payload) -> None:
    book = g.book(book_id)
    if not is_test_book(book):
        raise Refused(f"book {book_id} is not a test book (library: {book.get('libraryName')!r}, files: "
                      f"{file_names(book)}); writes are only allowed on books in the {TEST_LIBRARY!r} library")
    referenced = f"/{book_id}" in path or f"={book_id}" in path or (
        payload is not None and str(book_id) in json.dumps(payload))
    if not referenced:
        raise Refused(f"the request doesn't reference book {book_id} in its path or body")


def emit(result) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not (isinstance(result, dict) and result.get("ok") is False) else 1


def cmd_check() -> int:
    g = Grimmory()
    status, body = g.request("GET", "/api/v1/books")
    count = len(body) if isinstance(body, list) else None
    return emit({"ok": status == 200, "url": g.url, "status": status, "books": count})


def cmd_get(path: str, *params: str) -> int:
    query = dict(p.split("=", 1) for p in params)
    status, body = Grimmory().request("GET", path, params=query or None)
    return emit({"ok": status < 400, "status": status, "body": body})


def cmd_find(text: str) -> int:
    status, body = Grimmory().request("GET", "/api/v1/books")
    if status != 200:
        return emit({"ok": False, "status": status, "body": body})
    needle = text.lower()
    hits = []
    for b in body:
        title = ((b.get("metadata") or {}).get("title") or b.get("title") or "")
        names = file_names(b)
        if needle in title.lower() or any(needle in n.lower() for n in names):
            hits.append({"id": b.get("id"), "title": title, "files": names, "test_book": is_test_book(b)})
    return emit({"ok": True, "count": len(hits), "books": hits})


def cmd_book(book_id: str) -> int:
    book = Grimmory().book(int(book_id))
    return emit({"ok": True, "test_book": is_test_book(book), "files": file_names(book), "book": book})


def cmd_write(method: str, path: str, book_id: str, body: str | None = None) -> int:
    method = method.upper()
    if method not in WRITE_METHODS:
        raise Refused(f"write only takes {sorted(WRITE_METHODS)}; use get for reads")
    payload = json.loads(body) if body else None
    g = Grimmory()
    require_test_book_write(g, int(book_id), path, payload)
    status, resp = g.request(method, path, json=payload)
    return emit({"ok": status < 400, "status": status, "body": resp})


def cmd_write_library(method: str, path: str, library_id: str, body: str | None = None) -> int:
    """A library-level write (e.g. its file naming pattern), allowed only on the library named TEST."""
    method = method.upper()
    if method not in WRITE_METHODS:
        raise Refused(f"write-library only takes {sorted(WRITE_METHODS)}")
    g = Grimmory()
    status, lib = g.request("GET", f"/api/v1/libraries/{int(library_id)}")
    name = lib.get("name") if isinstance(lib, dict) else None
    if status != 200 or (name or "").strip().upper() != TEST_LIBRARY:
        raise Refused(f"library {library_id} is {name!r}; library-level writes are only allowed on {TEST_LIBRARY!r}")
    if f"/libraries/{int(library_id)}" not in path:
        raise Refused(f"the path doesn't target library {library_id}")
    status, resp = g.request(method, path, json=json.loads(body) if body else None)
    return emit({"ok": status < 400, "status": status, "body": resp})


SPEC_PATH = "/api/openapi.json"  # the instance's OpenAPI 3.1 spec (public docs: https://grimmory.org/api/)
SPEC_CACHE = HERE / ".cache" / "openapi.json"


def load_spec(refresh: bool = False) -> dict:
    if refresh or not SPEC_CACHE.exists():
        status, body = Grimmory().request("GET", SPEC_PATH)
        if status != 200 or not isinstance(body, dict):
            raise SystemExit(f"could not fetch {SPEC_PATH} (HTTP {status})")
        SPEC_CACHE.parent.mkdir(exist_ok=True)
        SPEC_CACHE.write_text(json.dumps(body, indent=1), encoding="utf-8")
        return body
    return json.loads(SPEC_CACHE.read_text(encoding="utf-8"))


def cmd_openapi() -> int:
    spec = load_spec(refresh=True)
    return emit({"ok": True, "saved": str(SPEC_CACHE), "openapi": spec.get("openapi"),
                 "version": spec.get("info", {}).get("version"), "paths": len(spec.get("paths", {}))})


def cmd_endpoints(text: str = "") -> int:
    spec, needle = load_spec(), text.lower()
    rows = []
    for path, ops in spec.get("paths", {}).items():
        for method, op in ops.items():
            if method.upper() not in {"GET", *WRITE_METHODS}:
                continue
            summary = op.get("summary") or ""
            if needle in path.lower() or needle in summary.lower() or needle in " ".join(op.get("tags", [])).lower():
                rows.append(f"{method.upper():6} {path}  — {summary}")
    return emit({"ok": True, "count": len(rows), "endpoints": rows})


COMMANDS = {"check": cmd_check, "get": cmd_get, "find": cmd_find, "book": cmd_book, "write": cmd_write,
            "openapi": cmd_openapi, "endpoints": cmd_endpoints, "write-library": cmd_write_library}

if __name__ == "__main__":
    command, *args = sys.argv[1:] or ["check"]
    if command not in COMMANDS:
        sys.exit(f"unknown command {command!r}; use one of {sorted(COMMANDS)}")
    try:
        sys.exit(COMMANDS[command](*args))
    except Refused as e:
        sys.exit(emit({"ok": False, "error": f"refused: {e}"}))
