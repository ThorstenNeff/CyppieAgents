# CYP-332 — PTY-over-WebSocket Protocol (contract)

> The server foundation for the Desktop interactive terminal (CYP-331 Option D). Dev binds the JediTerm
> `TtyConnector` to this. Types live in `:core` (`model/TerminalModel.kt`) — compiler-guaranteed on both
> sides; also generated into the AsyncAPI doc (`ContractGenerator.WS_CHANNELS`). Route:
> `server/.../routing/TerminalSocket.kt`; PTY engine: `server/.../pty/PtyManager.kt` (pty4j `0.13.4`).

## Endpoint

`GET /ws/terminal?agentId=<id>` — one **interactive `claude` TUI** in a real pty4j PTY per agent (NOT
`--print`/stream-json). Text WebSocket frames, JSON-encoded via `CommJson` (`classDiscriminator = "type"`).

- **Auth:** any valid reader — agent/operator **token** (`Authorization: Bearer` **or** `?token=`, so a
  browser WS works) or a verified session (same bar as `/ws/agent`). No/invalid credential → close
  **1008** `unauthorized`. (Operator-gated take-over / IDLE-gate / Seize = the CYP-333 hand-off follow-up.)
- **Unknown agent** (not in the active project) → close **1003** `unknown agent`.
- **Single-flight (§4.1):** a PTY already live for that agent → close **1008** `pty_busy` (never two rival
  interactive processes on one session).
- **Lifecycle (this story):** PTY is **spawned on connect, torn down on disconnect**. A PTY that survives a
  reconnect is a follow-up story.

## Frames

Bytes are raw; JSON can't carry raw bytes, so payloads are **standard Base64**.

### Client → Server (`TerminalClientFrame`)

| `type` | Fields | Meaning |
|---|---|---|
| `input` | `dataBase64: String` | Base64 of the typed/pasted bytes → PTY **stdin**. |
| `resize` | `cols: Int, rows: Int` | Window resized → `setWinSize` (raises `SIGWINCH`; the TUI reflows). Send on attach + every resize. |

### Server → Client (`TerminalServerFrame`)

| `type` | Fields | Meaning |
|---|---|---|
| `output` | `dataBase64: String` | Base64 of bytes read from PTY **stdout** (VT/ANSI) → the client's terminal renders it. |
| `exit` | `code: Int` | The PTY process exited (`code`, or `-1` if unknown). **Last** server frame; the socket closes right after. |

**Ordering:** `output` frames are delivered **in order** (server drains the PTY on one thread → an unbounded
channel → a single sender). The client applies `input`/`output` byte-for-byte; `claude`'s TUI owns all
rendering (cursor, colours, redraws).

## Example

```
C→S  {"type":"resize","cols":120,"rows":40}
C→S  {"type":"input","dataBase64":"bHMK"}            // "ls\n"
S→C  {"type":"output","dataBase64":"…"}              // the TUI's rendered bytes
…
S→C  {"type":"exit","code":0}                        // then the socket closes
```

## TtyConnector notes (Dev)

- Base64-encode keystrokes into `input`; Base64-decode `output` and feed the terminal widget raw.
- Send a `resize` on first attach (with the widget's initial cols/rows) and on every resize event — the
  server spawns at 80×24 until the first `resize` corrects it.
- On `exit` (or a socket close), surface the terminal as ended; re-attaching spawns a **fresh** PTY (this
  story's WS-scoped lifecycle).
