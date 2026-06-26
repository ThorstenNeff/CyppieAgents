# CYP-5 — stream-json Connector-Spike — Report

> Status: **abgeschlossen** · Owner: Backend · Epic: CYP-1 (Agent-Connector & Mediation)
> Datum: 2026-06-26 · Scope: Protokoll-Verifikation im Scratch-Dir, **keine** echte Repo-Arbeit.

## 0. TL;DR (verbindliche Referenz)

- **CLI gepinnt: `claude` Version `2.1.193`** (Native-Install, `~/.local/bin/claude`). Diese Version ist ab jetzt die verbindliche Referenz; Flag-/Event-Verhalten unten gilt für sie.
- **Verifiziertes Flag-Set** für die langlebige Session:
  ```
  claude -p \
    --input-format stream-json \
    --output-format stream-json \
    --verbose
  ```
  `--verbose` ist bei `--output-format stream-json` **Pflicht**. `--print/-p` ist Pflicht (alle stream-json-Flags „only work with --print").
- **Kernfrage beantwortet: Multi-Turn über EINEN langlebigen Prozess funktioniert.** Ein einzelner Prozess nimmt nacheinander mehrere user-messages auf stdin an und liefert je Turn einen `result`. `session_id` bleibt über alle Turns stabil. → Die MVP-Präferenz „eine langlebige Session pro Agent" ist tragfähig; **kein** Prozess-pro-Aufgabe nötig.
- **stdin-Format bestätigt** (per `--replay-user-messages` echo + korrekter Antwort):
  ```json
  {"type":"user","message":{"role":"user","content":[{"type":"text","text":"<nachricht>"}]}}
  ```
  Eine NDJSON-Zeile pro Turn (`\n`-terminiert). `content` als Array von Blöcken funktioniert; ein einfacher String wurde **nicht** als robust angenommen → Array verwenden.
- **Keine Secrets** im Lauf/Report. `apiKeySource` war im Spike `none` (geerbte Abo-Auth); produktiv injizieren wir `ANTHROPIC_API_KEY` (D3) → dann `apiKeySource` entsprechend.

## 1. Was getestet wurde

Zwei Treiber (siehe `driver.mjs`, `capture.mjs` in diesem Ordner), Node v22, im Scratch-Dir:

1. **`driver.mjs`** — Multi-Turn-Beweis: ein Prozess, Turn 1 („FIRST") → nach `result` Turn 2 („SECOND") in **denselben** stdin. Permissions minimal (`--allowedTools ""`), Prompts brauchen keine Tools.
2. **`capture.mjs`** — Voll-Event-Struktur inkl. Tool-Call-Turn. Hier — **nur im isolierten Scratch-Dir, mit PO-Freigabe** — `--dangerously-skip-permissions`, um `tool_use`/`tool_result`-Events end-to-end zu sehen (`echo spike-tool-ok`).

## 2. Beobachtete Event-Sequenzen

**Reiner Text-Turn:**
```
system/init → rate_limit_event → user(replay) → assistant[text] → result/success
```

**Tool-Call-Turn:**
```
system/init → rate_limit_event → assistant[thinking] → assistant[tool_use]
  → user[tool_result] → assistant[text] → result/success
```

**Multi-Turn auf einem Prozess** (Turn-Grenze = `result`):
```
system/init → rate_limit_event → user → assistant → result/success
system/init →                    user → assistant → result/success   (gleiche session_id)
```

> Jeder Turn beginnt erneut mit `system/init`. Das `result`-Event ist der **eindeutige Turn-Ende-Marker** — genau hier mediiert das Backend (Ergebnis in den Hub-Kanal posten).

## 3. Event-Taxonomie (Grundlage der `:core`-DTOs)

Gemeinsamer Umschlag auf **jeder** Zeile: `type` (String), `session_id` (String), `uuid` (String). Optional `subtype`, `parent_tool_use_id` (für Sub-Agent-Attribution).

| `type` | `subtype` | Wichtige Felder | Bedeutung fürs Backend |
|---|---|---|---|
| `system` | `init` | `session_id`, `model`, `cwd`, `tools` (count), `permissionMode`, `apiKeySource`, `mcp_servers[]` | Turn-/Session-Start; `session_id` hier abgreifen (für `--resume`) |
| `rate_limit_event` | – | `rate_limit_info{status, resetsAt, rateLimitType, overageStatus, isUsingOverage}` | Observability/Spend (Exposé §4.1); nicht in den Hub |
| `assistant` | – | `message{role, content[], model, stop_reason, usage, ...}` | Konversationsausgabe; `content`-Blöcke: `thinking` \| `text` \| `tool_use` |
| `user` | – | `message.content[]` = entweder `text` (unser Input, ggf. replayed) **oder** `tool_result` | Replayed Input bzw. injizierte Tool-Ergebnisse |
| `result` | `success` \| (Fehler-Subtypes) | `is_error`, `duration_ms`, `num_turns`, `total_cost_usd`, `usage{...}`, `result` (finaler Text) | **Turn-Ende** → mediieren; `total_cost_usd`/`usage` → Spend-Tracking |

**Content-Block-Formen** (innerhalb `assistant.message.content` / `user.message.content`):
```json
// assistant text
{"type":"text","text":"..."}
// assistant thinking
{"type":"thinking","thinking":"..."}            // (Feldname im Lauf: thinking-Block)
// assistant tool_use
{"type":"tool_use","id":"toolu_…","name":"Bash","input":{...},"caller":{"type":"direct"}}
// user tool_result (Antwort an ein tool_use)
{"tool_use_id":"toolu_…","type":"tool_result","content":"…","is_error":false}
```

### 3.1 Vorgeschlagene `:core`-DTO-Skizze (zum Einfrieren mit Dev)

`sealed interface` je Richtung, `@Serializable`, Polymorphie über `type` (kotlinx.serialization `classDiscriminator = "type"`). Unbekannte Felder tolerieren (`ignoreUnknownKeys = true`), da das Protokoll versionsempfindlich ist.

```kotlin
// Server → (Mediator liest stdout)
sealed interface AgentEvent { val sessionId: String; val uuid: String }
//  SystemInit(model, cwd, permissionMode, apiKeySource, toolCount, mcpServers)
//  RateLimit(info)
//  Assistant(message: AssistantMessage /* content: List<ContentBlock> */)
//  UserEcho(content: List<ContentBlock>)            // replay + tool_result
//  Result(isError, durationMs, numTurns, totalCostUsd, usage, result: String?, subtype)

// (Mediator schreibt stdin) → Server
//  UserTurn(text) -> {"type":"user","message":{"role":"user","content":[{"type":"text","text":text}]}}

sealed interface ContentBlock                          // Text | Thinking | ToolUse | ToolResult
```

> **Wichtig fürs Backend-Mediation-Mapping:** Hub-Post = der `result.result`-Text (oder gesammelte `assistant[text]`). `tool_use`/`thinking` sind UI-/Audit-Material, **nicht** automatisch in den PO-Kanal. ACL-Durchsetzung sitzt an genau dieser Schreib-Grenze (canWrite).

## 4. Robustheit (`--resume`)

- Auf **einem** Prozess bleibt die Session über mehrere Turns erhalten (verifiziert). Solange der Prozess lebt, kein `--resume` nötig.
- **Crash-Recovery:** `session_id` aus dem ersten `system/init` persistieren. Stirbt der Prozess, neu spawnen mit `--resume <session_id>` (bzw. `--session-id <uuid>`, um die ID vorzugeben) → Konversationszustand wird fortgesetzt. Das ist der Robustheits-Fallback aus 05 §7.1; **Prozess-pro-Aufgabe** bleibt nur Notnagel.
- Empfehlung: Mediator hält pro Agent `{process, sessionId, lastResultTs}`; Supervisor startet bei Exit mit `--resume` neu.

## 5. Offene/zu klärende Punkte

1. **Produkt-Permission-Default:** für autonome Repo-Arbeit (S8) eng gefasste `--allowedTools` — mit Reviewer festzunageln, **bevor** ein Agent autonom am echten Repo läuft (nicht in diesem Spike).
2. **`--include-partial-messages`:** liefert Token-Deltas für Live-Streaming im Renderer (D6) — Frontend-relevant; Backend kann auch ohne (komplette `assistant`-Events) mediieren. Bei Bedarf separat verifizieren.
3. **Fehler-`result`-Subtypes** (Timeout/Abbruch) nicht im Happy-Path-Spike gesehen — beim Mediator defensiv behandeln (`is_error == true`).
4. **CLAUDE.md-Discovery / MCP-Vererbung:** der Spike erbte die Eltern-Umgebung (cwd + MCP-Server). Produktiv bestimmt das **Worktree-cwd** Persona (CLAUDE.md) und Tooling — beim Spawn cwd = Agent-Worktree setzen, keine fremde Config erben.

## 6. Reproduktion

```bash
# Scratch-Dir, Node v22
node driver.mjs    # Multi-Turn-Beweis (read-only, keine Tools)
node capture.mjs   # Voll-Events inkl. Tool-Call (NUR isoliert; --dangerously-skip-permissions)
```
