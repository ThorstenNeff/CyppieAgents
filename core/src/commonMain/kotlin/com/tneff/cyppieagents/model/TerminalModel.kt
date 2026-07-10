package com.tneff.cyppieagents.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * CYP-332 — the **PTY-over-WebSocket protocol** for `GET /ws/terminal?agentId=<id>` (02 §8.1, design-doc
 * §4/§5). One agent = one live **pty4j PTY** running an **interactive `claude` TUI** (NOT stream-json /
 * `--print`). The frames are `@Serializable` sealed interfaces — one per direction — so the client
 * (Desktop JediTerm `TtyConnector`, CYP-331 Option D) and the server (de)serialize the **same** types, and
 * the contract is compiler-guaranteed. Decoded via [com.tneff.cyppieagents.CommJson]
 * (`classDiscriminator = "type"`), like [StreamJsonEvent] and the other WS channels.
 *
 * **Byte encoding:** terminal I/O is raw bytes, and JSON cannot carry raw bytes, so the payload frames
 * ([TerminalInput], [TerminalOutput]) carry the bytes as **standard Base64** in [TerminalInput.dataBase64] /
 * [TerminalOutput.dataBase64]. The `TtyConnector` Base64-encodes keystrokes into [TerminalInput] and
 * Base64-decodes [TerminalOutput] to feed its renderer.
 *
 * **Single-flight (design-doc §4.1):** the PTY seam keeps **at most one live process per session** — a
 * second `/ws/terminal` for an agent whose PTY is already live does not spawn a rival process (the mode
 * hand-off mediated↔interactive is a follow-up story; this protocol only guarantees the invariant holds).
 */

/** Client → Server (the terminal/`TtyConnector` drives these). */
@Serializable
sealed interface TerminalClientFrame

/**
 * Bytes typed at the terminal → written to the PTY **stdin**. [dataBase64] = standard Base64 of the raw
 * byte run (may be a single keystroke or a paste).
 */
@Serializable
@SerialName("input")
data class TerminalInput(val dataBase64: String) : TerminalClientFrame

/**
 * The terminal was resized → the server calls `PtyProcess.setWinSize`, which raises `SIGWINCH` so a TUI
 * (`claude`, vim, ncurses) reflows to [cols]×[rows]. Sent on attach and on every window resize.
 */
@Serializable
@SerialName("resize")
data class TerminalResize(val cols: Int, val rows: Int) : TerminalClientFrame

/** Server → Client (the PTY manager emits these). */
@Serializable
sealed interface TerminalServerFrame

/**
 * Bytes read from the PTY **stdout** → rendered by the terminal. [dataBase64] = standard Base64 of the raw
 * byte run (VT/ANSI as produced by the interactive process; the client's terminal parses it).
 */
@Serializable
@SerialName("output")
data class TerminalOutput(val dataBase64: String) : TerminalServerFrame

/**
 * The PTY process exited; [code] is its exit code (or -1 if unknown). This is the **last** server frame —
 * the socket closes normally right after it.
 */
@Serializable
@SerialName("exit")
data class TerminalExit(val code: Int) : TerminalServerFrame
