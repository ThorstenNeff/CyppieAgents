# WS contract — `asyncapi.json` (CYP-409, W1 producer)

`asyncapi.json` is the **AsyncAPI 2.6** description of the frontend WebSocket channels, **generated from `:core`**
(the `@Serializable` DTOs, via `ContractGenerator`/`SchemaWalker`). It is the single source the TS consumer
(`web-ts`, Dev5) derives its WS types from — the same source the Kotlin server itself uses, so the wire types
cannot drift between client and server.

**Do not hand-edit this file.** It is generated.

## Regenerating (producer)

```
./gradlew :server:exportContract
```

Writes `web-ts/contract/asyncapi.json`. The task is **offline and secret-free**: it calls
`ContractGenerator.asyncApi()` directly — it does **not** hit the auth-gated `/docs/asyncapi.json` route and does
**not** boot a server, so no live host, network, or token is involved. The bytes are single-sourced with the
`/docs` serialization (`docsJson(...)` + a trailing newline via `asyncApiExportText()`), so the export, the
`/docs` render, and the drift guard below are byte-identical by construction.

Re-run the task and commit the result whenever a `:core` DTO or a WS channel changes.

## Two fail-closed guards — no gap

The staleness axis and the missing axis are guarded on the two sides that can actually see each, both fail-closed:

| Axis | Failure it catches | Guard | Where |
|---|---|---|---|
| **Staleness** | committed export ≠ what `:core` generates now | `ContractExportDriftTest` (reddens on any drift) | producer, Gradle (`:server:test`) |
| **Missing** | the real export is absent (consumer still on its fixture) | `CONTRACT_REQUIRE_REAL` (see below) | consumer, `web-ts` Node (`contract:gen`) |

The producer cannot check "missing" meaningfully (it always regenerates a real file), and the Node consumer has no
JVM generator to check "staleness" — so each guard lives where the check is real. Coupling the Node build to a
Gradle check would be the wrong dependency direction.

## `CONTRACT_REQUIRE_REAL` — the consumer flag (Dev5 wires this)

Dev5's `contract:gen` decides **fixture vs. real export** and honors this environment flag:

- **unset (default):** if the real `asyncapi.json` is absent, fall back to the fixture, print a **loud warning**,
  and **exit 0** — so `web-ts` still builds before the producer export has landed.
- **`CONTRACT_REQUIRE_REAL=1`:** a missing real export is **fatal → exit 1** (fail-closed). Set this once the real
  export is expected (CI / release), so nothing ships silently against the fixture.

Truthy = `1` / `true` / `yes` / `on` (case-insensitive); anything else (unset, empty, `0`, `false`) is the default.

This flag is **not** read by any Gradle task — it is purely the consumer-side gate. The producer's contribution is
the real export (above) and its staleness guard; this document is the shared semantics Dev5 implements against.
