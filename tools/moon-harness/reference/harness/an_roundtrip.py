"""Comparison ``an-roundtrip``: readsync's EPUB ``.an`` parser and writer.

Per input (an ``.an`` file, or a device capture's decompressed text):

- ``kind``: ``"pdf"`` when readsync's dispatcher would take the PDF parser (``#A*#`` anywhere), else ``"epub"``;
- ``header``: ``parse_an_epub_header`` (or ``{"error": ...}``);
- ``records``: ``parse_an_epub``, one object per record (or ``{"error": ...}``);
- ``reparsed`` / ``reparsedHeader``: the records and header read back from ``serialize_an_epub(header, records)``,
  i.e. what survives readsync's own write (only when header and records parsed).

Compared on decompressed text, never on zlib bytes. readsync has no note field (it folds the bookmark and note
slots into ``text``); the Java side compares its ``text`` folded the same way.
"""

from __future__ import annotations

import zlib
from collections.abc import Iterator
from typing import Any

from readsync.connectors.gdrive_moon.an_parser import (
    AnEpubHeader,
    EpubAnnotation,
    parse_an_epub,
    parse_an_epub_header,
    serialize_an_epub,
)

from harness.inputs import Case, Roots, device_captures, error, files, read_bytes

NAME = "an-roundtrip"
KIND_EPUB = "epub"
KIND_PDF = "pdf"
#: readsync's ``parse_an`` takes a file holding this tag anywhere as the PDF sub-format.
PDF_TAG = "#A*#"


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
    """``.an`` files are zlib already; inline captures are decompressed text, compressed here for readsync."""
    raw = read_bytes(case_input, roots)
    return raw if "file" in case_input else zlib.compress(raw)


def _header(header: AnEpubHeader) -> dict[str, Any]:
    return {"totalLength": header.total_length, "indent": header.indent, "trim": header.trim}


def run(case_input: dict[str, Any], roots: Roots) -> dict[str, Any]:
    raw = compressed_input(case_input, roots)
    try:
        text = zlib.decompress(raw).decode("utf-8")
    except Exception as exc:  # noqa: BLE001
        return error(exc)
    if PDF_TAG in text:
        # Mirrors readsync's parse_an, which dispatches this to its PDF parser; Grimmory rejects PDF .an files (spec deviation).
        return {"kind": KIND_PDF}

    header: Any
    records: Any
    try:
        parsed_header = parse_an_epub_header(raw)
        header = _header(parsed_header)
    except Exception as exc:  # noqa: BLE001
        parsed_header, header = None, error(exc)
    try:
        annotations = parse_an_epub(raw)
        records = [_record(a) for a in annotations]
    except Exception as exc:  # noqa: BLE001
        annotations, records = None, error(exc)

    output: dict[str, Any] = {"kind": KIND_EPUB, "header": header, "records": records}
    if parsed_header is not None and annotations is not None:
        try:
            written = serialize_an_epub(parsed_header, annotations)
            output["reparsed"] = [_record(a) for a in parse_an_epub(written)]
            output["reparsedHeader"] = _header(parse_an_epub_header(written))
        except Exception as exc:  # noqa: BLE001
            output["reparsed"] = error(exc)
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
