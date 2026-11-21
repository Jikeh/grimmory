"""Shared plumbing for the comparisons that read corpus EPUBs through readsync's own content cache and bridge."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable, Iterator
from typing import Any, TypeVar

from harness.inputs import Case, Roots, error, files
from harness.readsync_env import ReadsyncEnv

T = TypeVar("T")


def book_cases(roots: Roots) -> Iterator[Case]:
    """One case per corpus EPUB: ``{"file": "corpus/<path>"}``."""
    for path in files(roots, "corpus", ".epub"):
        ref = roots.ref("corpus", path)
        yield Case(ref, {"file": ref})


def book_id(case_input: dict[str, Any]) -> str:
    """The fake Grimmory client's book id: the file's path under the corpus."""
    root, _, relative = str(case_input["file"]).partition("/")
    if root != "corpus" or not relative:
        raise ValueError(f"EPUB comparisons read corpus files, got {case_input['file']!r}")
    return relative


def run_with_env(roots: Roots, body: Callable[[ReadsyncEnv], Awaitable[dict[str, Any]]]) -> dict[str, Any]:
    """Runs ``body`` against a fresh ``ReadsyncEnv`` (its own bridge process), closing it afterwards."""

    async def main() -> dict[str, Any]:
        env = ReadsyncEnv(roots.corpus)
        try:
            return await body(env)
        finally:
            await env.aclose()

    try:
        return asyncio.run(main())
    except Exception as exc:  # noqa: BLE001 -- a failure is an output to compare
        return error(exc)


async def or_none(call: Awaitable[T], *errors: type[BaseException]) -> T | None:
    """``await call``, or ``None`` when it raises one of ``errors`` (a per-item failure, not a failed case)."""
    try:
        return await call
    except errors:
        return None
