# web-e2e — Web-TS E2E harness (§8)

Playwright teeth that run in a **real browser** against a **real Ktor server** — no mocks. This is the gap
the type-stripped `vitest` units miss: a contract break shows up here because every assertion goes through
the real source (the server + its wire types), not a hand-rolled double.

## What it is

- `fixture/index.html` — a **reference client** (vanilla JS, a test fixture, **not** the product SPA). It is
  the executable contract the TS SPA must satisfy: dedup (agent-events by gapless `seq > cursor`, comm by
  `Message.id`) and **inert** rendering (`textContent`, never `innerHTML`). When the real SPA lands, the
  DOM-level teeth re-point at it — same assertions.
- The server under test is the hermetic CYP-106 harness (`e2ePlatform`, `FakeSpawner`/`FakeGit`): a real
  `installPlatform` with **no real `claude`, no API key, no repo**. Booted by the `:e2e:webE2eServer` Gradle
  task, which `playwright.config.ts` starts as its `webServer` and serves the fixture same-origin (so the
  browser's WebSocket handshake is same-origin — no CORS variable).

## Teeth

| File | Tooth | Feed |
|---|---|---|
| `harness-smoke.spec.ts` | rig is real (server up, fixture loads) | — |
| `seq-since-replay.spec.ts` | `?since=N` replays exactly `seq>N`, gapless | agent-events (`/ws/agent`) |
| `reconnect-idempotency.spec.ts` | reconnect with the last cursor → no dup (server `seq>cursor` + DOM) | agent-events |
| `xss-inert.spec.ts` | an XSS payload posted through the real comm feed renders inert | comm (`POST /api/channels/{id}/messages`) |

## Run it (manual — no CI)

Prereqs: JDK (the repo's toolchain) + Node. From this directory:

```bash
npm install                 # installs @playwright/test
npm run test                # pretest installs the chromium browser, then runs the teeth
```

Playwright boots the Gradle harness (first run compiles the `:e2e` test classpath — allow a cold run),
waits on `/api/health`, runs the teeth against the real server, then tears the server down.

- Headed / debug: `npm run test:headed` or `npm run test:ui`.
- Report of the last run: `npm run report`.
- Point at an already-running harness: set `WEB_E2E_PORT` and leave `reuseExistingServer` on (default).

## Seed contract

The boot main (`e2e/.../WebE2eServerMain.kt`, `WebE2eSeed`) seeds project `alpha` (PO `po` + worker
`backend` → channel `po-backend`) and pre-seeds agent-events seq `1..5` for `backend`. The TS constants in
`tests/harness.ts` mirror it — change both together.
