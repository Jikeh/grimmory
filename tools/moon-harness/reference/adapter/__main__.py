"""Command line of the reference image.

``reference [--only NAME ...]``  write ``<out>/reference/<comparison>.json`` for every (or the named) comparison
``selftest``                     check the fake Grimmory client and the Node bridge against the corpus
``list``                         print the comparison names

Folders default to the container mounts ``/samples``, ``/corpus`` (read-only) and ``/out``.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path
from typing import Any

from harness import COMPARISONS
from harness.inputs import Roots, files

FORMAT_VERSION = 1


def write_reference(roots: Roots, out: Path, only: list[str]) -> int:
    unknown = [name for name in only if name not in COMPARISONS]
    if unknown:
        print(f"unknown comparison(s): {', '.join(unknown)}; known: {', '.join(COMPARISONS)}", file=sys.stderr)
        return 2
    target = out / "reference"
    target.mkdir(parents=True, exist_ok=True)
    for stale in target.glob("*.json"):
        stale.unlink()
    for name, module in COMPARISONS.items():
        if only and name not in only:
            continue
        cases: list[dict[str, Any]] = []
        seen: set[str] = set()
        for case in module.cases(roots):
            if case.id in seen:
                raise ValueError(f"{name}: duplicate case id {case.id!r}")
            seen.add(case.id)
            cases.append({"id": case.id, "input": case.input, "output": module.run(case.input, roots)})
        document = {
            "formatVersion": FORMAT_VERSION,
            "comparison": name,
            "readsyncRevision": os.environ.get("READSYNC_REVISION", "unknown"),
            "cases": cases,
        }
        (target / f"{name}.json").write_text(json.dumps(document, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"{name}: {len(cases)} cases")
    return 0


async def selftest(roots: Roots) -> int:
    from harness.readsync_env import ReadsyncEnv

    env = ReadsyncEnv(roots.corpus)
    checked = 0
    try:
        for path in files(roots, "corpus", ".epub"):
            book_id = path.relative_to(roots.corpus).as_posix()
            spine = await env.epub.get_spine_length(book_id)
            html = await env.epub.get_spine_content(book_id, 0)
            split_map = await env.bridge.describe_moon_split_map(html)
            print(f"epub {book_id}: {spine} spine items; item 0 has {len(split_map['chunks'])} Moon+ chunk(s)")
            checked += 1
        for suffix in (".mobi", ".azw", ".azw3"):
            for path in files(roots, "corpus", suffix):
                book_id = path.relative_to(roots.corpus).as_posix()
                print(f"mobi {book_id}: {len(await env.mobi.get_mobi_bytes(book_id))} bytes")
                checked += 1
    finally:
        await env.aclose()
    if checked == 0:
        print(f"no EPUB or MOBI-family books under {roots.corpus}", file=sys.stderr)
        return 1
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="harness")
    parser.add_argument("--samples", type=Path, default=Path("/samples"))
    parser.add_argument("--corpus", type=Path, default=Path("/corpus"))
    parser.add_argument("--out", type=Path, default=Path("/out"))
    commands = parser.add_subparsers(dest="command", required=True)
    reference = commands.add_parser("reference")
    reference.add_argument("--only", action="append", default=[])
    commands.add_parser("selftest")
    commands.add_parser("list")
    args = parser.parse_args(argv)
    roots = Roots(samples=args.samples, corpus=args.corpus)
    if args.command == "reference":
        return write_reference(roots, args.out, args.only)
    if args.command == "selftest":
        return asyncio.run(selftest(roots))
    print("\n".join(COMPARISONS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
