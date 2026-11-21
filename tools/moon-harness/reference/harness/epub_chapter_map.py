"""Comparison ``epub-chapter-map``: readsync's ``EpubNcxCache`` (Moon+ chapter index <-> spine file and anchor).

Per corpus EPUB:

- ``chapters``: for every chapter index from 0 to ``max(navPoints, spine) + 1``, ``resolve_chapter_index`` with the
  single-chunk guard (``strict``) and without it (``relaxed``), each ``[spineIndex, anchorOffset]`` or ``null``;
- ``reverse``: ``resolve_spine_position`` at probe offsets in every spine file (0, and each relaxed anchor in that
  file minus one, itself and plus one), each ``{"spine", "offset", "result": [chapter, anchorOffset] | null}``.

The Java side derives the probes the same way from its own results, so differing anchors show up twice. A book
whose structure can't be parsed is ``{"error": ...}``.
"""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

from readsync.connectors.grimmory.epub_ncx import EpubNcxCache

from harness.epub_books import book_cases, book_id, run_with_env
from harness.inputs import Case, Roots
from harness.readsync_env import ReadsyncEnv

NAME = "epub-chapter-map"


def cases(roots: Roots) -> Iterator[Case]:
    return book_cases(roots)


def pair(value: tuple[int, int] | None) -> list[int] | None:
    return None if value is None else [value[0], value[1]]


def probes(spine_length: int, relaxed: list[list[int] | None]) -> list[tuple[int, int]]:
    """``(spine, offset)`` probes for the reverse direction, in a fixed order (both sides use this rule)."""
    offsets: dict[int, set[int]] = {spine: {0} for spine in range(spine_length)}
    for anchor in relaxed:
        if anchor is not None and anchor[0] in offsets:
            offsets[anchor[0]].update(o for o in (anchor[1] - 1, anchor[1], anchor[1] + 1) if o >= 0)
    return [(spine, offset) for spine in range(spine_length) for offset in sorted(offsets[spine])]


def run(case_input: dict[str, Any], roots: Roots) -> dict[str, Any]:
    book = book_id(case_input)

    async def body(env: ReadsyncEnv) -> dict[str, Any]:
        ncx = EpubNcxCache(env.epub, env.bridge)
        spine_length = await env.epub.get_spine_length(book)
        navpoints = await env.epub.get_ncx_navpoints(book) or ()
        count = max(len(navpoints), spine_length) + 2
        strict = [pair(await ncx.resolve_chapter_index(book, c)) for c in range(count)]
        relaxed = [pair(await ncx.resolve_chapter_index(book, c, allow_split_spine_file=True)) for c in range(count)]
        reverse = [
            {"spine": spine, "offset": offset, "result": pair(await ncx.resolve_spine_position(book, spine, offset))}
            for spine, offset in probes(spine_length, relaxed)
        ]
        return {
            "chapters": [{"strict": s, "relaxed": r} for s, r in zip(strict, relaxed, strict=True)],
            "reverse": reverse,
        }

    return run_with_env(roots, body)
