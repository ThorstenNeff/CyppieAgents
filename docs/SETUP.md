# Local-Dev Setup & Quickstart

How to take a fresh clone to a running server + connected desktop UI. Every command and
variable below is grounded in the actual code (not invented). If something here drifts from
the code, the code wins — please file it.

## 1. Prerequisites

- **JDK 17+** (JetBrains Runtime recommended).
- **Git** with working remote credentials *if* you point the platform at a remote repo (SSH key
  or `gh auth login`). For a credential-free local boot, see §3.
- **openssl** — used to generate dev tokens.
- *Optional (real agent run):* the `claude` CLI authenticated with your **OAuth subscription
  credentials** (`~/.claude`). Only needed when agents `launch: claude`.
- *Optional (extra targets):* Android SDK (Android app), macOS/Xcode (iOS — stubbed).

## 2. One-time setup

```bash
git clone <this-repo> && cd KMPCyppieAgents

# Fast path: generate a local .env (random dev tokens) + a local platform.config.json
./scripts/dev-setup.sh
```

Prefer to do it by hand? 

```bash
cp .env.example .env                       # then fill in tokens (see the file's comments)
cp platform.config.example.json platform.config.json
```

Generate each token with `openssl rand -hex 32`. **Never commit `.env` or `platform.config.json`**
(both are gitignored) and never paste token values into chat.

### Required environment (server, fail-closed)

The server resolves secrets via `Secrets.fromEnv` and **refuses to boot** if a required token is
missing (it will not run an unauthenticated hub):

| Variable | Required | Notes |
|---|---|---|
| `HUB_TOKEN_PO` / `HUB_TOKEN_FRONTEND` / `HUB_TOKEN_BACKEND` | ✅ | One per agent; suffix = agent id **uppercased**, must match `platform.config.json`. |
| `OPERATOR_TOKEN` | ✅ | Privileged token (ACL + operator-only REST/WS). **Same variable name on the client** → identical value when both run from the same `.env`. |
| `PLATFORM_CONFIG` | — | Config path. Default `platform.config.json`. |
| `PLATFORM_GIT_ROOT` | — | Clone + per-agent worktrees + event-log db/spool. Default `.cyppie`. |
| `ANTHROPIC_API_KEY` | — | **Real agent run only.** Default auth is OAuth subscription creds (`~/.claude`); set this only for explicit key auth. |
| `HUB_HOST` / `HUB_PORT` | — | **Client-side** (desktop UI). Defaults `localhost` / `8787`. The server itself binds `127.0.0.1:8787` (hardcoded). |

`.env` is **not** auto-loaded by Gradle — export it into the shell first:

```bash
set -a; . ./.env; set +a
```

## 3. Run the server

```bash
./gradlew :server:run        # binds http://127.0.0.1:8787
```

**Important — the boot clones `repo.url` as a hard prerequisite.** If the clone fails, the boot
aborts and the server does **not** start. The example config's `repo.url`
(`git@github.com:org/projekt.git`) is a placeholder and will abort. Choose one:

- **Credential-free local boot (recommended for UI/dev):** point `repo.url` at a local repo and
  use a branch that exists there, e.g. in `platform.config.json`:
  ```jsonc
  "repo": { "url": "file:///absolute/path/to/KMPCyppieAgents", "branch": "develop" }
  ```
- **Real remote:** set `repo.url` to a repo you can clone (SSH key / `gh auth`).

Per-agent `launch: claude` spawns are **isolated and fail-closed** — if `claude` isn't installed
or authenticated, that agent simply has no live session, but **the server still boots and the UI
still connects** (you'll see the channels; agent sessions are empty until Claude is available).

Health check:

```bash
curl http://127.0.0.1:8787/api/health      # -> ok
```

## 4. Run the desktop client

```bash
# In a shell that has the same .env loaded (so OPERATOR_TOKEN / HUB_TOKEN_* match the server):
set -a; . ./.env; set +a
./gradlew :app:desktopApp:run
```

The desktop client reads `HUB_HOST`/`HUB_PORT`/`HUB_TOKEN_<ID>`/`OPERATOR_TOKEN` from the env and
connects to the server. With no env set it falls back to `localhost:8787` and dev-default agent
tokens (`dev-token-<id>`), which match the server's dev `TokenRegistry` fallback.

## 5. Zero-setup web UI preview (no server / tokens / Claude)

Two distinct web entry points — they are **not** equivalent:

```bash
# Prod web shell — operatorToken=null → the operator-only Event-Log windows are OMITTED.
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun        # Wasm (or :jsBrowserDevelopmentRun)

# Demo entry — operator context + fully STUB sources → the Event-Log windows ARE visible,
# streaming fake events, with zero backend. This is the pure-UI path for the Event-Log.
./gradlew :app:webAppDemo:wasmJsBrowserDevelopmentRun     # Wasm (or :jsBrowserDevelopmentRun)
```

Both serve the UI on a local dev port and need no `.env`, tokens, server, or Claude. Use
`:app:webApp` to preview the production shell; use **`:app:webAppDemo`** when you need to see the
operator-only Event-Log Browse / Live-Tail windows (it bakes a demo operator token + stub sources in
a separate non-prod artifact — the prod build keeps operator-gating as a real security boundary, so
the Event-Log windows stay omitted there).

## 6. Real agent run (Claude)

1. Ensure `claude` is installed and authenticated with your subscription (`~/.claude`) — this is the
   default auth path. Only set `ANTHROPIC_API_KEY` if you explicitly want key-based auth.
2. Keep `launch: claude` for the agents in `platform.config.json` and point `repo.url` at the repo
   the agents should work in.
3. Start the server (§3). Agents spawn one session each in their own git worktree under
   `PLATFORM_GIT_ROOT`.

## 7. Troubleshooting

- **`missing required env HUB_TOKEN_… / OPERATOR_TOKEN`** → you didn't load `.env` into the shell
  (`set -a; . ./.env; set +a`) or a token is blank. This is the fail-closed guard working.
- **Boot exits with `git clone failed`** → `repo.url` isn't clonable. Use a `file://` local repo
  (§3) or fix your git credentials.
- **UI connects but agents are silent** → expected without `claude` available; the server boots
  regardless. Install/authenticate `claude` for live sessions.
- **CORS / web UI can't reach the server** → the server only allows origins listed under
  `web.allowedOrigins` in `platform.config.json` (default `http://localhost:8080`); add your web
  dev origin there.

## Run-task reference

| What | Command |
|---|---|
| Server | `./gradlew :server:run` |
| Desktop app | `./gradlew :app:desktopApp:run` (hot reload: `:app:desktopApp:hotRun --auto`) |
| Web UI — prod shell (Wasm/JS) | `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun` (Event-Log windows omitted) |
| Web UI — Event-Log demo (Wasm/JS) | `./gradlew :app:webAppDemo:wasmJsBrowserDevelopmentRun` (operator + stub sources) |
| Android | `./gradlew :app:androidApp:assembleDebug` |
| Server tests | `./gradlew :server:test` |
