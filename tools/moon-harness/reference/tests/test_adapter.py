"""Tests for the reference adapter; run inside the reference image (``just harness test-reference``)."""

from __future__ import annotations

import json
import zlib
from pathlib import Path

import httpx
import pytest

from harness import COMPARISONS, an_roundtrip, po_roundtrip
from harness.__main__ import write_reference
from harness.fake_grimmory import FakeGrimmoryClient
from harness.inputs import Roots

DEVICE_FIXTURE = {
    "captures": [
        {"id": "po-1", "kind": "po", "raw": "1738611943423*3@0#0:1.4%"},
        {
            "id": "P1",
            "kind": "probe",
            "raw": "#\n1\nBook\n/p\n/p\n3\n0\n0\n5\n-1\n2\n\n\nHello\n0\n0\n0\n",
            "anHeader": ["1000", "indent:true", "trim:true"],
        },
        {"id": "D1", "kind": "deleted", "raw": "#\n0\n\n\n\n3\n0\n0\n0\n0\n2\n\nnote\n0\n0\n0\n*DELETED*\n"},
    ]
}


@pytest.fixture
def roots(tmp_path: Path) -> Roots:
    samples, corpus = tmp_path / "samples", tmp_path / "corpus"
    (samples / "device").mkdir(parents=True)
    (samples / "format").mkdir()
    corpus.mkdir()
    (samples / "format" / "a.po").write_text("1*2@3#4:5.5%|-7\n", encoding="utf-8")
    (samples / "format" / "bad.po").write_text("not a position\n", encoding="utf-8")
    (samples / "device" / "book.json").write_text(json.dumps(DEVICE_FIXTURE), encoding="utf-8")
    (samples / "format" / "pdf.an").write_bytes(zlib.compress(b"100#A*#1#A1#2#A2#3#A3#4#A4#5#A5##A6##A7#t#A8##A@#"))
    (corpus / "Book.epub").write_bytes(b"epub bytes")
    return Roots(samples=samples, corpus=corpus)


def test_po_cases_cover_files_and_device_captures(roots: Roots) -> None:
    ids = [case.id for case in po_roundtrip.cases(roots)]
    assert ids == ["samples/format/a.po", "samples/format/bad.po", "samples/device/book.json#po-1"]


def test_po_output_has_fields_serialization_and_percent_rewrites(roots: Roots) -> None:
    output = po_roundtrip.run({"text": "1*2@3#4:5.5%", "percents": [0.25, 100.0]}, roots)
    assert output == {
        "format": "epub", "deviceId": "1", "percent": 5.5, "chapter": 2, "split": 3, "offset": 4, "page": None,
        "scroll": None, "serialized": "1*2@3#4:5.5%", "withPercent": ["1*2@3#4:0.2%", "1*2@3#4:100.0%"],
    }


def test_po_parse_failure_is_an_error_output(roots: Roots) -> None:
    assert "error" in po_roundtrip.run({"file": "samples/format/bad.po", "percents": []}, roots)


def test_an_cases_cover_files_probes_and_deleted_captures(roots: Roots) -> None:
    cases = list(an_roundtrip.cases(roots))
    assert [c.id for c in cases] == ["samples/format/pdf.an", "samples/device/book.json#P1", "samples/device/book.json#D1"]
    assert cases[1].input["text"].startswith("1000\nindent:true\ntrim:true\n#\n")
    assert cases[2].input["text"].startswith("#\n")


def test_an_probe_parses_through_readsync(roots: Roots) -> None:
    case = next(c for c in an_roundtrip.cases(roots) if c.id.endswith("#P1"))
    output = an_roundtrip.run(case.input, roots)
    assert output["kind"] == "epub"
    assert output["header"] == {"totalLength": "1000", "indent": True, "trim": True}
    assert output["records"][0]["text"] == "Hello"
    assert output["reparsed"] == output["records"]


def test_an_pdf_and_header_less_inputs(roots: Roots) -> None:
    assert an_roundtrip.run({"file": "samples/format/pdf.an"}, roots) == {"kind": "pdf"}
    header_less = next(c for c in an_roundtrip.cases(roots) if c.id.endswith("#D1"))
    output = an_roundtrip.run(header_less.input, roots)
    assert "error" in output["header"]
    assert "reparsed" not in output


def test_roots_refuse_paths_outside_the_mounts(roots: Roots) -> None:
    with pytest.raises(ValueError):
        roots.resolve("samples/../corpus/Book.epub")
    with pytest.raises(ValueError):
        roots.resolve("elsewhere/x")


@pytest.mark.asyncio
async def test_fake_client_serves_corpus_files_and_404s_the_rest(roots: Roots) -> None:
    client = FakeGrimmoryClient(roots.corpus)
    response = await client.request("GET", "download_book", path_params={"bookId": "Book.epub"})
    assert response.content == b"epub bytes"
    with pytest.raises(httpx.HTTPStatusError):
        await client.request("GET", "download_book", path_params={"bookId": "Missing.epub"})
    with pytest.raises(httpx.HTTPStatusError):
        await client.request("GET", "download_book", path_params={"bookId": "../samples/format/a.po"})
    with pytest.raises(NotImplementedError):
        await client.request("GET", "book_progress", path_params={"bookId": "Book.epub"})


def test_write_reference_writes_one_document_per_comparison(roots: Roots, tmp_path: Path) -> None:
    out = tmp_path / "out"
    assert write_reference(roots, out, []) == 0
    written = sorted(p.name for p in (out / "reference").glob("*.json"))
    assert written == sorted(f"{name}.json" for name in COMPARISONS)
    document = json.loads((out / "reference" / "po-roundtrip.json").read_text(encoding="utf-8"))
    assert document["formatVersion"] == 1 and document["comparison"] == "po-roundtrip"
    assert [c["id"] for c in document["cases"]][0] == "samples/format/a.po"


def test_write_reference_rejects_unknown_names(roots: Roots, tmp_path: Path) -> None:
    assert write_reference(roots, tmp_path / "out", ["nope"]) == 2


@pytest.mark.asyncio
@pytest.mark.skipif(not Path("/app/src/readsync").is_dir(), reason="needs the reference image (Node bridge)")
async def test_bridge_answers_through_readsync_env(tmp_path: Path) -> None:
    from harness.readsync_env import ReadsyncEnv

    env = ReadsyncEnv(tmp_path)
    try:
        split_map = await env.bridge.describe_moon_split_map("<html><body><p>Hello</p></body></html>")
    finally:
        await env.aclose()
    assert len(split_map["chunks"]) == 1
