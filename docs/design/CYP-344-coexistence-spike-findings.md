# CYP-344 — BE-0 Coexistence Spike: Findings (risk-first, for CYP-331 hand-off)

> **VERDICT: COEXISTENCE HOLDS via plain `--resume` — BE-2 is trivial (no transcript-bridge).**
> Proven end-to-end on a real `claude` session (subscription creds, no API key). One caveat that is a
> concrete BE-2/CYP-332 requirement, not a blocker: the interactive spawn must **not inherit the
> `CLAUDE_CODE_*`/`CLAUDECODE` env vars** — production's `PtyManager` already satisfies this.
>
> Substrate: `HOME=~/.claude` (`CLAUDE_CONFIG_DIR` unset), `fs=tmpfs`, **CLI pinned `2.1.206`**. The claim
> holds for this substrate + version (X1/X2). Cloud/ephemeral-FS is a separate axis (Arch §4.3a).

## The obligation (Option D / D10)
The same `claude` session is handed **sequentially** between mediated (headless stream-json /
`LifecycleManager`) and interactive (PTY-TUI / `PtyManager`), continuity via `--resume <sid>`, never two
live processes on one session. For hand-back to preserve context, the **interactive** turns must persist to
`<sid>.jsonl` so a later **mediated** `--resume <sid>` sees them.

## The three questions — answered

| # | Question | Answer | Evidence |
|---|---|---|---|
| **1** | Does interactive `--resume <sid>` write to the **same** transcript the mediated reader tails? | **YES** | Held read-only fd on `<sid>.jsonl`: **inode stable** (`9098851` across all phases), **size monotonic-growing** (14838→112189), `held_fd_saw_appended_bytes=true`, **single transcript, no fork**, path is the exact cwd-derived one. The mediated hand-back reads the interactive needle back (both cycles `sees_interactive_needle=true`). |
| **2** | After a clean TUI exit, is the session mediated-`--resume`-able? | **YES** | Every mediated hand-back re-binds (`mediated_resumed_bound=true`) and recalls. Also proven after **exit-mid-turn** and **hard-kill-mid-turn** (`reresume_bound=true, recalls=true`) — the torn-JSONL worry (Arch §4.3b) **did not materialise**. |
| **3** | Is the sid stable, or does `--resume` fork? | **STABLE** | Same sid (`793e3aae…`) across create + 2 cycles + 2 exit-path legs; `single_transcript_no_fork=true`. Forking is **opt-in only** via `--fork-session`/`/branch` (CLI help + docs `code.claude.com/docs/en/how-claude-code-works`: "`--resume` appends new messages under the same session ID"). |

## Proof to the Reviewer bar (all met)
- **A — shared transcript, true tail-append:** held **read-only** fd; `stat %i` identical before/after every
  phase; size strictly grew; no truncate/rename; no fork-file; exact cwd-derived path. ✅
- **B — sid stable + carries context:** needle-recall as the **only** oracle — **all file/read tools denied**
  (`--disallowedTools Write Edit MultiEdit NotebookEdit Bash Read Glob Grep …`), verified `confound_no_scratch_files=true`,
  `confound_tool_uses_seen=[]`. So recall can only come from the transcript. Bind ≠ remember: the model
  recalls the mediated needle **inside** the TUI and the mediated reader recalls the interactive needle
  **after** hand-back. ✅
- **FP-B5 — ≥2 cycles:** two consecutive mediated↔interactive cycles, needle-recall each time, no fork
  accretion. ✅
- **Exit paths:** clean `/exit`, exit-mid-turn, **hard-kill (SIGKILL) mid-turn** — all re-resume bound +
  recall. ✅
- **X1 substrate / X2 CLI version:** logged (tmpfs, `~/.claude`, `2.1.206`). ✅
- **X3 read-only tail:** the concurrent observer is a **read-only fd**; interactive and mediated never run
  concurrently on one sid (single-flight honored). ✅
- **B(iii) real bind seam:** proven at the **CLI/observable** level — the resume re-emits the sid at
  `system/init` (exactly what `ClaudeCodeSession.onBind` / `awaitStartupOutcome` consume ⇒ BOUND) **and**
  recalls context. The dedicated JVM proof through `ResumingSession.awaitStartupOutcome()` +
  `PtyManager` is available on request (`coexistence_proof.py` mirrors the observable contract); the
  CYP-330 #11 **negative** misclassification is a separate concern already covered by CYP-330's own teeth.

## The one real finding for BE-2/CYP-332 (env hygiene)
The spike first returned a **false negative** ("interactive never persists"). Root cause (Context7,
changelog **2.1.170**): *"sessions launched from … shells inheriting Claude Code environment variables were
not saving transcripts or appearing in `--resume`."* This spike runs **inside** a Claude Code agent
session, so the spawned `claude` inherited `CLAUDECODE=1`, `CLAUDE_CODE_CHILD_SESSION=1`,
`CLAUDE_CODE_SESSION_ID`, `CLAUDE_CODE_ENTRYPOINT`, `CLAUDE_CODE_EXECPATH` → **interactive** transcript
saving was suppressed (headless `-p` was unaffected). Stripping those vars → interactive persists
continuously and every claim passes.

**Production impact:** none by default — `PtyManager.open` builds a **fresh minimal env**
(`buildMap { TERM; PATH; putAll(baseEnv=empty); HUB_AGENT_ID; ANTHROPIC_API_KEY }`) and hands it to
`PtyProcessBuilder.setEnvironment`, which pty4j uses as the child's environment. So `CLAUDE_CODE_*`/
`CLAUDECODE` are **not** propagated, and the server is not launched from within `claude` in prod.
**Recommendation (cheap, defense-in-depth for BE-2):** have `PtyManager` **explicitly strip any
`CLAUDE_CODE_*`/`CLAUDECODE` keys** from the final env (guards against a future `baseEnv` that passes a
env through), and add a tooth asserting the spawned env carries none of them. Same applies to any future
interactive/`--resume` spawn.

## What BE-2 can rely on
- Hand-off = graceful stop of the current process → spawn the other mode with `--resume <sid>`; the sid is
  stable, one transcript, append-only. The CYP-167 `SessionStore` sid is the durable hand-off token.
- Interactive work **survives** hand-back to mediated (proven). No transcript-bridge, no sid-chase.
- Robust even to a hard-kill mid-turn (re-resume binds + recalls) — so a `bootout`/crash during interactive
  use does not lose context on this substrate.

## Scope / honesty
- Proven on **local persistent FS (tmpfs, same-host, `~/.claude` survives)** + CLI **2.1.206**. A future
  cloud/ephemeral-FS deploy is a **separate** durability axis (Arch §4.3a: transcripts don't survive
  container restart/scale-down) — mount `~/.claude`/`CLAUDE_CONFIG_DIR` on the durable volume there.
- `--fork-session` must **never** be added to the resume spawns (it would fork the sid and break the shared
  transcript). BE-2's spawn = `--resume <sid>` **without** `--fork-session`.

## Repro
`docs/design/spikes/cyp344/coexistence_proof.py` (the bar-meeting harness) — env `SPIKE_STAMP`, `SPIKE_WT`,
`SPIKE_OUT`. Results: `proof_result_cleanenv.json` (clean env → all green),
`proof_result.json` (nested-env → the false negative, kept as the counter-example). The narrower probes
(`roundtrip_spike.py`, and the `/tmp` control probes referenced in the journal) established the env
root-cause.
