"""Comparison ``po-roundtrip``: readsync's ``.po`` parser and writer.

Per input: the parsed fields, readsync's serialization of them (``serialize_po``, which re-formats the percent
with one decimal), and its percent-only rewrite (``with_percent`` + ``serialize_po``) at a fixed set of percents
chosen to probe rounding. A parse failure is ``{"error": ...}``; only its presence is compared.
"""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

from readsync.connectors.gdrive_moon.po_parser import parse_po, serialize_po, with_percent

from harness.inputs import Case, Roots, device_captures, files, read_text

NAME = "po-roundtrip"

#: Percents for the percent-only rewrite. Halfway cases (0.25, 0.35, 99.95) catch rounding-rule differences.
PERCENT_PROBES = [0.0, 0.05, 0.25, 0.35, 2.5, 12.345, 33.35, 99.95, 100.0]


def cases(roots: Roots) -> Iterator[Case]:
    for path in files(roots, "samples", ".po"):
        ref = roots.ref("samples", path)
        yield Case(ref, {"file": ref, "percents": PERCENT_PROBES})
    for ref, capture in device_captures(roots):
        if capture.get("kind") == "po":
            yield Case(f"{ref}#{capture['id']}", {"text": capture["raw"], "percents": PERCENT_PROBES})


def run(case_input: dict[str, Any], roots: Roots) -> dict[str, Any]:
    try:
        record = parse_po(read_text(case_input, roots))
    except Exception as exc:  # noqa: BLE001 -- any failure is a parse error to compare
        return {"error": f"{type(exc).__name__}: {exc}"}
    return {
        "format": record.format,
        "deviceId": record.device_id,
        "percent": record.percent,
        "chapter": record.chapter_index,
        "split": record.sub_index,
        "offset": record.offset,
        "page": record.page,
        "scroll": record.trailing_field,
        "serialized": serialize_po(record),
        # In the order of the input's "percents".
        "withPercent": [serialize_po(with_percent(record, float(p))) for p in case_input.get("percents", [])],
    }
