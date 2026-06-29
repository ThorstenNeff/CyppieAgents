package com.tneff.cyppieagents.crossproject

/**
 * Cross-project authorization data port (S17 / CYP-93). The directed owner-authorization of a channel
 * across the project boundary, plus its honest state. All mutations are owner/operator-gated (server
 * authoritative, fail-closed — anti-injection §2.6: only the human owner authorizes, never an agent or a
 * message). Built against [StubCrossProjectRepository]; the live `HttpCrossProjectRepository` is a later
 * stub→real swap once the backend permit seam lands (no UI/VM change).
 */
interface CrossProjectRepository {
    /** The channel's current cross-project state (shared? since when? which concrete members reached). */
    suspend fun status(channelId: String): CrossShareStatus

    /**
     * Authorize this channel across projects — a deliberate owner-consent act (operator/owner-gated), not
     * "add member". The consent is recorded as a **collection** (1→N-able; S18 fills bilateral consent
     * without rebuild — today N=1). Returns the updated state. Throws [CrossProjectException].
     */
    suspend fun authorize(channelId: String): CrossShareStatus

    /** Revoke — the channel falls back to project-local **immediately**, fail-closed (the gate, not the ACL entries). */
    suspend fun revoke(channelId: String): CrossShareStatus
}

/**
 * A cross-project call was rejected. [code] is the server reason — `operator_required` (403) / `unauthorized`
 * (401) for the gate, or a generic failure surfaced as `crossproject_error`.
 */
class CrossProjectException(val code: String) : Exception(code)
