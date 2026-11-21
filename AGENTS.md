# AGENTS.md

Guidance for AI coding agents working in this repository.

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/<feature>/` (kept out of git). See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix), recorded as a `Status:` line in each issue file. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Real-device Moon+ testing

To check Moon+ Reader behaviour on the user's phone, use the driver in `tools/moon-device/`: read its
`PLAYBOOK.md` first (safety rules, working loop, known traps), then `README.md` for the commands.

### Differential harness against readsync

To check the Java Moon+ position library against readsync (the reference), use `tools/moon-harness/` (read its
`README.md`): `just harness run` builds readsync from its source tree in Docker, runs both sides on the same
inputs and fails on any difference.

### The user's running Grimmory instance

To look at real data or exercise the API of the user's own Grimmory (`http://grimmory.jikeh.lan`), use
`tools/grimmory-api/` (read its `README.md`). It uses the user's own account: reads are unrestricted,
writes are refused unless they target a book in the **TEST** library. The API spec is at `/api/openapi.json` on the
instance (`grimmory_api.py endpoints <text>` searches a cached copy); public docs: https://grimmory.org/api/.
