# Generated contracts — `asyncapi.json` (WS) + `openapi.json` (REST)

Both files are **generated from `:core`** (the `@Serializable` DTOs, via `ContractGenerator`/`SchemaWalker`) — the
single source the TS consumer (`web-ts`, Dev5) derives its types from, the same source the Kotlin server itself
uses, so the wire types cannot drift between client and server.

- **`asyncapi.json`** (CYP-409) — the **AsyncAPI 2.6** description of the frontend **WebSocket** channels.
- **`openapi.json`** (CYP-426) — the **OpenAPI 3.1** description of the frontend **REST** surface (`/api/*`),
  including `GET /api/agents → Agent` (with `id`/`name`/`role`) — the agent **roster**, from which
  `poAgentId = role == PO` and the Phase-2 REST screens are derived.

**Do not hand-edit these files.** They are generated.

## Regenerating (producer)

```
./gradlew :server:exportContract
```

Writes **both** `web-ts/contract/asyncapi.json` and `web-ts/contract/openapi.json`. The task is **offline and
secret-free**: it calls `ContractGenerator.asyncApi()` / `ContractGenerator.openApi()` directly — it does **not**
hit the auth-gated `/docs` json routes and does **not** boot a server, so no live host, network, or token is
involved. The bytes are single-sourced with the `/docs` serialization (`docsJson(...)` + a trailing newline via
`asyncApiExportText()` / `openApiExportText()`), so the export and the drift guard below are byte-identical by
construction.

Re-run the task and commit the result whenever a `:core` DTO, a WS channel, or a `RestContract` op changes.

## Two fail-closed guards — no gap

The staleness axis and the missing axis are guarded on the two sides that can actually see each, both fail-closed:

| Axis | Failure it catches | Guard | Where |
|---|---|---|---|
| **Staleness** | a committed export ≠ what `:core` generates now | `ContractExportDriftTest` (one tooth per file — reddens on any drift) | producer, Gradle (`:server:test`) |
| **Missing** | a real export is absent (consumer still on its fixture) | `CONTRACT_REQUIRE_REAL` (see below) | consumer, `web-ts` Node (`contract:gen`) |

The producer cannot check "missing" meaningfully (it always regenerates a real file), and the Node consumer has no
JVM generator to check "staleness" — so each guard lives where the check is real. Coupling the Node build to a
Gradle check would be the wrong dependency direction.

## `CONTRACT_REQUIRE_REAL` — the consumer flag (Dev5 wires this)

Dev5's `contract:gen` decides **fixture vs. real export** (for **either** file) and honors this environment flag:

- **unset (default):** if a real export is absent, fall back to the fixture, print a **loud warning**, and
  **exit 0** — so `web-ts` still builds before the producer export has landed.
- **`CONTRACT_REQUIRE_REAL=1`:** a missing real export is **fatal → exit 1** (fail-closed). Set this once the real
  export is expected (CI / release), so nothing ships silently against the fixture.

Truthy = `1` / `true` / `yes` / `on` (case-insensitive); anything else (unset, empty, `0`, `false`) is the default.

This flag is **not** read by any Gradle task — it is purely the consumer-side gate. The producer's contribution is
the real exports (above) and their staleness guards; this document is the shared semantics Dev5 implements against.
