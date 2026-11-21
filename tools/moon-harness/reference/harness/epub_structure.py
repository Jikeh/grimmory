"""Comparison ``epub-structure``: readsync's ``EpubContentCache`` (OPF spine, NCX navPoints, trust gate).

Per corpus EPUB: the spine as manifest hrefs and as paths from the zip root, whether each spine item's content can be
read, the NCX navPoints in document order (``null`` without a usable NCX), whether the NCX is trusted, and the spine
index each navPoint's href resolves to. A book whose structure can't be parsed is ``{"error": ...}``.
"""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

from readsync.connectors.grimmory.epub_content import EpubContentError

from harness.epub_books import book_cases, book_id, or_none, run_with_env
from harness.inputs import Case, Roots
from harness.readsync_env import ReadsyncEnv

NAME = "epub-structure"


def cases(roots: Roots) -> Iterator[Case]:
    return book_cases(roots)


def run(case_input: dict[str, Any], roots: Roots) -> dict[str, Any]:
    book = book_id(case_input)

    async def body(env: ReadsyncEnv) -> dict[str, Any]:
        epub = env.epub
        length = await epub.get_spine_length(book)
        navpoints = await epub.get_ncx_navpoints(book)
        readable = []
        for index in range(length):
            readable.append(await or_none(epub.get_spine_content(book, index), EpubContentError) is not None)
        return {
            "spineHrefs": [await epub.get_spine_href(book, i) for i in range(length)],
            "spinePaths": [await epub.get_spine_path(book, i) for i in range(length)],
            "spineReadable": readable,
            "navPoints": None if navpoints is None else [
                {"playOrder": n.play_order, "href": n.href, "fragment": n.fragment_id} for n in navpoints
            ],
            "ncxTrusted": await epub.is_ncx_trusted(book),
            "navPointSpineIndex": None if navpoints is None else [
                await epub.get_ncx_spine_index(book, n.href) for n in navpoints
            ],
        }

    return run_with_env(roots, body)
