package com.tneff.cyppieagents.routing

/**
 * CYP-143 — single-sourced input bounds for every agent-facing write/inject boundary.
 *
 * The REST send path ([commRoutes] `POST /channels/{id}/messages`) and the WS inject path
 * ([agentSocket] `/ws/agent`) are two genuinely different boundaries — a hub write vs. a session
 * inject — so each enforces the cap at its own edge. But the *value and the predicate* live here
 * **once**, so the two can never drift (CLAUDE.md: single-source derived values). The cap closes the
 * door the ticket flagged: today both sites hand an unbounded `String` straight to
 * `Hub.postAsAgent` / `ConnectorSession.sendTurn`.
 */
object MessageInput {
    /** Max characters of a hub message body / injected turn text (64 KiB). */
    const val MAX_BODY_CHARS: Int = 64 * 1024

    /**
     * Defense-in-depth: max incoming WebSocket frame size (bytes) — a protocol backstop set above
     * [MAX_BODY_CHARS] so an abusive frame is rejected by Ktor before it is even buffered into memory
     * (the app-level [requireValidBody] check runs only *after* `frame.readText()` has read the frame).
     */
    const val MAX_FRAME_BYTES: Long = 1L * 1024 * 1024 // 1 MiB

    /**
     * Validate a message body / turn text at a boundary, fail-closed. Throws [PayloadTooLargeException]
     * (413) when over [MAX_BODY_CHARS], [BadRequestException] (400) when blank — in either case nothing
     * is sent or injected. Used by BOTH the REST send route and the WS inject loop (the latter maps the
     * [ApiException] to a WebSocket close).
     */
    fun requireValidBody(text: String) {
        if (text.length > MAX_BODY_CHARS) {
            throw PayloadTooLargeException("message body exceeds the $MAX_BODY_CHARS-character limit")
        }
        if (text.isBlank()) {
            throw BadRequestException("message body must not be blank")
        }
    }
}
