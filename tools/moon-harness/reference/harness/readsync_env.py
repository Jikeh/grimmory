"""readsync's content caches and Node bridge, wired to the fake Grimmory client.

Comparisons that need book content or the bridge (tickets 04 onwards) take a ``ReadsyncEnv`` and call readsync's
own objects directly, the way readsync's connector would, with corpus files as the books.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from readsync.connectors.grimmory.cfi_bridge_client import CfiBridge
from readsync.connectors.grimmory.epub_content import EpubContentCache
from readsync.connectors.grimmory.mobi_content import MobiContentCache

from harness.fake_grimmory import FakeGrimmoryClient


@dataclass
class ReadsyncEnv:
    corpus: Path
    client: FakeGrimmoryClient = field(init=False)
    epub: EpubContentCache = field(init=False)
    mobi: MobiContentCache = field(init=False)
    bridge: CfiBridge = field(init=False)

    def __post_init__(self) -> None:
        self.client = FakeGrimmoryClient(self.corpus)
        self.epub = EpubContentCache(self.client)  # type: ignore[arg-type]
        self.mobi = MobiContentCache(self.client)  # type: ignore[arg-type]
        self.bridge = CfiBridge()

    async def aclose(self) -> None:
        await self.bridge.aclose()
        await self.client.aclose()
