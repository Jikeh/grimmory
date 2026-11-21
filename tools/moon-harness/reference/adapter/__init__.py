"""Reference side of the Moon+ differential harness (see ``tools/moon-harness/README.md``).

Each comparison is a module with ``NAME``, ``cases(roots)`` (the inputs it covers) and ``run(input, roots)``
(readsync's output for one input, as JSON). The Java side registers a comparison with the same name.
"""

from __future__ import annotations

from types import ModuleType

from harness import an_roundtrip, po_roundtrip

#: Every comparison, in report order. Adding one: write the module and list it here (README, "Adding a comparison").
COMPARISONS: dict[str, ModuleType] = {module.NAME: module for module in (po_roundtrip, an_roundtrip)}
