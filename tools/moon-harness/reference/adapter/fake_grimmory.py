"""A stand-in for readsync's ``GrimmoryClient`` that serves corpus files instead of calling Grimmory.

readsync's EPUB and MOBI content caches (``EpubContentCache``, ``MobiContentCache``) download whole books with
``client.request("GET", "download_book", path_params={"bookId": ...})`` and read ``response.content``. This client
answers exactly that call from the mounted corpus, with the book id being the file's path relative to the corpus
root (e.g. ``"Novels/Ulysses.epub"``). Anything else fails loudly, so a comparison that reaches a Grimmory
endpoint the harness doesn't fake is noticed instead of silently returning nothing.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import httpx

from harness.inputs import Roots

_BASE_URL = "http://fake-grimmory.invalid"


class FakeGrimmoryClient:
    def __init__(self, corpus: Path) -> None:
        self._corpus = corpus
        self.requests: list[tuple[str, str, dict[str, Any]]] = []

    @property
    def base_url(self) -> str:
        return _BASE_URL

    async def request(
        self,
        method: str,
        endpoint_key: str,
        *,
        path_params: dict[str, Any] | None = None,
        json: dict[str, Any] | None = None,
    ) -> httpx.Response:
        params = dict(path_params or {})
        self.requests.append((method, endpoint_key, params))
        url = f"{_BASE_URL}/{endpoint_key}"
        request = httpx.Request(method, url)
        if method != "GET" or endpoint_key != "download_book" or json is not None:
            raise NotImplementedError(f"the fake Grimmory client only serves GET download_book, not {method} {endpoint_key}")
        book_id = str(params.get("bookId", ""))
        try:
            path = Roots(samples=self._corpus, corpus=self._corpus).resolve(f"corpus/{book_id}")
        except ValueError:
            path = None
        if path is None or not path.is_file():
            response = httpx.Response(404, request=request, text=f"no corpus file {book_id!r}")
            response.raise_for_status()
        return httpx.Response(200, request=request, content=path.read_bytes())

    async def aclose(self) -> None:
        return None
