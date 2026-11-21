"""Comparison ``epub-parser-parity``: the tree jsdom (parse5) builds for every spine file, which readsync's offsets
and CFIs are computed on, against the tree jsoup builds on the Java side.

Per corpus EPUB: one signature per spine item (``tree_signature.js`` defines it: a digest of the element and text-node
sequence, element and text-node counts, and readsync's walk text length and digest), ``null`` for an item whose
content can't be read. A book whose structure can't be parsed is ``{"error": ...}``.
"""

from __future__ import annotations

import json
import os
import subprocess
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import readsync.connectors.grimmory.cfi_bridge_client as cfi_bridge_client
from readsync.connectors.grimmory.epub_content import EpubContentError

from harness.epub_books import book_cases, book_id, or_none, run_with_env
from harness.inputs import Case, Roots
from harness.readsync_env import ReadsyncEnv

NAME = "epub-parser-parity"

SCRIPT = Path(__file__).with_name("tree_signature.js")
BRIDGE = Path(cfi_bridge_client.__file__).with_name("cfi_bridge")


def cases(roots: Roots) -> Iterator[Case]:
    return book_cases(roots)


def signatures(htmls: list[str | None]) -> list[dict[str, Any] | None]:
    """Runs ``tree_signature.js`` (jsdom from readsync's bridge) over the given documents."""
    result = subprocess.run(
        ["node", str(SCRIPT)],
        input=json.dumps(htmls), capture_output=True, text=True, encoding="utf-8", check=True,
        env={**os.environ, "READSYNC_CFI_BRIDGE": str(BRIDGE)},
    )
    return json.loads(result.stdout)


def run(case_input: dict[str, Any], roots: Roots) -> dict[str, Any]:
    book = book_id(case_input)

    async def body(env: ReadsyncEnv) -> dict[str, Any]:
        length = await env.epub.get_spine_length(book)
        htmls = [await or_none(env.epub.get_spine_content(book, i), EpubContentError) for i in range(length)]
        return {"files": signatures(htmls)}

    return run_with_env(roots, body)
