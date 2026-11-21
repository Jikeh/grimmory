# syntax=docker/dockerfile:1
#
# Reference side of the Moon+ differential harness: readsync built from its own source tree, with its dev
# dependencies and the Node CFI bridge, plus the harness adapter. Not readsync's runtime image (that one has no
# dev dependencies, and its EPUB code fetches books through Grimmory's HTTP API).
#
# Build contexts (see ../Justfile):
#   default   this folder (the adapter)
#   readsync  readsync's source tree; only pyproject.toml, uv.lock, README.md and src/ are copied, and readsync's
#             own .dockerignore keeps its secrets/state out of the build.

FROM node:22.12.0-bookworm-slim AS bridge
WORKDIR /bridge
COPY --from=readsync src/readsync/connectors/grimmory/cfi_bridge/package.json src/readsync/connectors/grimmory/cfi_bridge/package-lock.json ./
RUN npm ci --omit=dev

FROM ghcr.io/astral-sh/uv:python3.13-bookworm-slim
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PROJECT_ENVIRONMENT=/app/.venv PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
COPY --from=bridge /usr/local/bin/node /usr/local/bin/node

WORKDIR /app
COPY --from=readsync pyproject.toml uv.lock README.md ./
RUN uv sync --frozen --no-install-project
COPY --from=readsync src/ ./src/
RUN uv sync --frozen
# The host tree may hold a node_modules installed for another platform; use the one built above.
RUN rm -rf src/readsync/connectors/grimmory/cfi_bridge/node_modules
COPY --from=bridge /bridge/node_modules src/readsync/connectors/grimmory/cfi_bridge/node_modules

ARG READSYNC_REVISION=unknown
ENV READSYNC_REVISION=${READSYNC_REVISION} PATH="/app/.venv/bin:${PATH}" PYTHONPATH=/harness

COPY adapter/ /harness/harness/
COPY tests/ /harness/tests/
RUN node --version && python -c "import readsync, harness"

WORKDIR /harness
ENTRYPOINT ["python", "-m", "harness"]
CMD ["reference"]
