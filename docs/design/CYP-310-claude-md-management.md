# CYP-310 — CLAUDE.md management without auto-overwrite (LOCAL)

> Status: as-built · Auftraggeber-confirmed model 2026-07-07 · Remote (BYOA) = CYP-197 (separate) · Field-removal cleanup = CYP-311

## Doc-delta (supersedes the persona auto-discovery)

**Before (CYP-97/CYP-133, Doc 05 §5):** the `ClaudeCodeConnector` wrote the agent's stored `persona` to
`CLAUDE.md` in its worktree cwd at every spawn (Claude-Code auto-discovery). The stored persona config field
drove the file.

**After (CYP-310):** the connector **NEVER** auto-writes `CLAUDE.md`. A new agent starts with an **empty**
(absent) `CLAUDE.md`. The file is managed **exclusively** via two operator/participant endpoints + the live
worktree file (external edits and the agent's own edits are first-class, read live). The stored `persona`
config field is **deprecated** (no longer drives anything; removal tracked as CYP-311) — kept only for wire
compatibility while the client migrates.

## The model

- **config-seeded / new agent → empty CLAUDE.md.** No write-if-absent, no persona materialisation.
- **`GET /api/agents/{id}/claude-md`** (participant-gated, same read posture as `GET /api/agents/{id}`): reads
  the **live** worktree file fresh each call → reflects external/agent edits. Absent/unreadable →
  `exists=false, content=""` (fail-closed empty, **never** the stored persona). Response
  `ClaudeMdView(agentId, content, exists, version)`; `version` = sha-256 of the file bytes (null when absent).
- **`POST /api/agents/{id}/claude-md`** (operator-gated, structural): **hard overwrite** (no merge). Body
  `ClaudeMdUpdate(content, expectedVersion)`.
  - **Optimistic concurrency:** the server re-hashes the current file under a lock; if it differs from
    `expectedVersion` → **409 `claude_md_stale`**, **no write** (an unseen external/agent edit is preserved).
    `expectedVersion=null` = expect no file yet (first create). Closes the TOCTOU window atomically.
  - **EFFECT_DEFERRED:** the file changes immediately, but a running session already read its `CLAUDE.md`, so
    it takes effect on the **next spawn/restart** (the client shows the restart hint).
  - **Bounded:** 256 KB cap → **413 `too_large`**, fail-closed before the write.
- **Errors:** `404 agent_not_found` (not in the active project) · `409 agent_not_local` (a remote/BYOA agent
  has no local worktree — remote management is CYP-197) · `400 invalid_body` · `409 claude_md_stale` ·
  `413 too_large`.

## Where it lives

- `ClaudeCodeConnector.open()` — the auto-write block + the `personaOf` param/wiring are removed.
- `AgentManagement.readClaudeMd` / `writeClaudeMd` — active-project-scoped, resolve the agent's worktree dir,
  read/write `CLAUDE.md` under the management lock (atomic read-compare-write for the stale guard). Remote
  agents are tracked (`config.agents.filter { remote }` + a remote `add`) → `agent_not_local`.
- `AgentMgmtRoutes` — the two routes (participant GET, operator POST) + the 256 KB cap.
- DTOs in `:core` — `ClaudeMdView`, `ClaudeMdUpdate`; `NewAgentSpec.persona`/`AgentEdit.persona`/
  `AgentDetail.persona` deprecated + ignored (removal = CYP-311).
