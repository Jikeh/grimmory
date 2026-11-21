"""Comparison ``an-roundtrip``: readsync's EPUB ``.an`` parser and writer.

Per input (an ``.an`` file, or a device capture's decompressed text):

- ``kind``: ``"pdf"`` when readsync's dispatcher would take the PDF parser (``#A*#`` anywhere), else ``"epub"``;
- ``header``: ``parse_an_epub_header`` (or ``{"error": ...}``);
- ``records``: ``parse_an_epub``, one object per record (or ``{"error": ...}``);
- ``reparsed``: the records read back from ``serialize_an_epub(header, records)``, i.e. what survives readsync's
  own write (only when header and records parsed).

Compared on decompressed text, never on zlib bytes. readsync has no note field (it folds the bookmark and note
slots into ``text``); the Java side leaves ``text`` out of the comparison on records with a bookmark or note.
"""

from __future__ import annotations

import zlib
from collections.abc import Iterator
from typing import Any

from readsync.connectors.gdrive_moon.an_parser import (
    EpubAnnotation,
    parse_an_epub,
    parse_an_epub_header,
    serialize_an_epub,
)

from harness.inputs import Case, Roots, device_captures, files

NAME = "an-roundtrip"


def cases(roots: Roots) -> Iterator[Case]:
    for path in files(roots, "samples", ".an"):
        ref = roots.ref("samples", path)
        yield Case(ref, {"file": ref})
    for ref, capture in device_captures(roots):
        kind = capture.get("kind")
        header = capture.get("anHeader")
        if kind == "probe" and isinstance(header, list):
            # A probe is one highlight record as Moon+ uploaded it; its file's header is recorded beside it.
            yield Case(f"{ref}#{capture['id']}", {"text": "\n".join(header) + "\n" + capture["raw"]})
        elif kind == "deleted":
            # A deleted-notes record on its own: what Moon+ uploads once a book has no live notes (no header).
            yield Case(f"{ref}#{capture['id']}", {"text": capture["raw"]})


def compressed_input(case_input: dict[str, Any], roots: Roots) -> bytes:
    if "file" in case_input:
        return roots.resolve(case_input["file"]).read_bytes()
    return zlib.compress(str(case_input["text"]).encode("utf-8"))


def run(case_input: dict[str, Any], roots: Roots) -> dict[str, Any]:
    raw = compressed_input(case_input, roots)
    try:
        text = zlib.decompress(raw).decode("utf-8")
    except Exception as exc:  # noqa: BLE001
        return {"error": f"{type(exc).__name__}: {exc}"}
    if "#A*#" in text:
        # readsync's parse_an dispatches this to its PDF parser; Grimmory rejects PDF .an files (spec deviation).
        return {"kind": "pdf"}

    header: Any
    records: Any
    try:
        parsed_header = parse_an_epub_header(raw)
        header = {"totalLength": parsed_header.total_length, "indent": parsed_header.indent, "trim": parsed_header.trim}
    except Exception as exc:  # noqa: BLE001
        parsed_header, header = None, {"error": f"{type(exc).__name__}: {exc}"}
    try:
        annotations = parse_an_epub(raw)
        records = [_record(a) for a in annotations]
    except Exception as exc:  # noqa: BLE001
        annotations, records = None, {"error": f"{type(exc).__name__}: {exc}"}

    output: dict[str, Any] = {"kind": "epub", "header": header, "records": records}
    if parsed_header is not None and annotations is not None:
        try:
            output["reparsed"] = [_record(a) for a in parse_an_epub(serialize_an_epub(parsed_header, annotations))]
        except Exception as exc:  # noqa: BLE001
            output["reparsed"] = {"error": f"{type(exc).__name__}: {exc}"}
    return output


def _record(a: EpubAnnotation) -> dict[str, Any]:
    underline, strikethrough, bak = a.trailer
    return {
        "id": a.annotation_id,
        "filename": a.filename,
        "path": a.path,
        "lowerPath": a.path_lower,
        "chapter": a.chapter_index,
        "split": a.sub_index,
        "offset": a.start_offset,
        "length": a.span_length,
        "color": a.color_argb,
        "time": a.created_at_ms,
        "text": a.text,
        "underline": underline,
        "strikethrough": strikethrough,
        "bak": bak,
        "deleted": a.is_deleted,
    }
