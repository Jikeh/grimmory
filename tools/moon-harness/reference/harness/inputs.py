"""Harness inputs: the mounted folders, and how a case names what it reads.

A case's ``input`` is JSON shared with the Java side, which resolves it the same way:

- ``{"file": "<root>/<relative path>"}`` reads a file under a mounted root (``samples`` or ``corpus``);
- ``{"text": "..."}`` carries the content inline (e.g. a ``.po`` or ``.an`` capture from a device fixture);

plus any comparison-specific keys (e.g. ``percents``). Paths always use ``/``.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any


#: Where ``just harness run --save`` writes regression cases, relative to the default samples folder
#: (``backend/src/test/resources/moonsync``). Never enumerated as samples.
REGRESSIONS_DIR = "harness/regressions"


@dataclass(frozen=True)
class Roots:
    samples: Path
    corpus: Path

    def root(self, name: str) -> Path:
        if name == "samples":
            return self.samples
        if name == "corpus":
            return self.corpus
        raise ValueError(f"unknown input root {name!r}; expected samples or corpus")

    def resolve(self, ref: str) -> Path:
        root_name, _, relative = ref.partition("/")
        if root_name not in ("samples", "corpus") or not relative:
            raise ValueError(f"input file must be samples/<path> or corpus/<path>, got {ref!r}")
        root = self.root(root_name)
        path = (root / relative).resolve()
        if not path.is_relative_to(root.resolve()):
            raise ValueError(f"input file escapes its root: {ref!r}")
        return path

    def ref(self, root_name: str, path: Path) -> str:
        return f"{root_name}/{path.relative_to(self.root(root_name)).as_posix()}"


def error(exc: BaseException) -> dict[str, str]:
    """A failed side, as both sides write it: ``{"error": "<message>"}`` (only its presence is compared)."""
    return {"error": f"{type(exc).__name__}: {exc}"}


@dataclass(frozen=True)
class Case:
    #: Stable, human-readable id: the input's file ref, plus ``#<capture id>`` for device-fixture captures.
    id: str
    input: dict[str, Any]


def read_bytes(case_input: dict[str, Any], roots: Roots) -> bytes:
    if "file" in case_input:
        return roots.resolve(case_input["file"]).read_bytes()
    return str(case_input["text"]).encode("utf-8")


def read_text(case_input: dict[str, Any], roots: Roots) -> str:
    return read_bytes(case_input, roots).decode("utf-8")


def files(roots: Roots, root_name: str, suffix: str) -> Iterator[Path]:
    root = roots.root(root_name)
    if not root.is_dir():
        return iter(())
    # Saved regression cases live under the default samples folder; they are inputs already, not new samples.
    return iter(sorted(
        p for p in root.rglob(f"*{suffix}")
        if p.is_file() and REGRESSIONS_DIR not in p.relative_to(root).parent.as_posix()
    ))


def device_captures(roots: Roots) -> Iterator[tuple[str, dict[str, Any]]]:
    """``(file ref, capture)`` for every capture in the device fixtures (``device/*.json`` shape, ticket 00b)."""
    for path in files(roots, "samples", ".json"):
        try:
            fixture = json.loads(path.read_text(encoding="utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
        if not isinstance(fixture, dict) or not isinstance(fixture.get("captures"), list):
            continue
        ref = roots.ref("samples", path)
        for capture in fixture["captures"]:
            if isinstance(capture, dict) and isinstance(capture.get("raw"), str) and capture.get("id"):
                yield ref, capture
