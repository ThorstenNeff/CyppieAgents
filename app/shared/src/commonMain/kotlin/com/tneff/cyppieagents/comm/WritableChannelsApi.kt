package com.tneff.cyppieagents.comm

/**
 * CYP-273 — the **narrow client seam** for the caller's OWN writable channel set.
 *
 * Backing endpoint (Backend, CYP-273 part 1 — auto-versioned `/api` + `/api/v1`):
 * ```
 * GET /api/channels/writable → List<String>   // channel ids the resolved caller may write to RIGHT NOW
 * ```
 * Server-computed at the participant read tier (same auth as `GET /api/channels`); the invariant is
 * `writable ⊆ readable`. The server 403 stays the real enforcement point — this set only lets the UI
 * disable the composer honestly (comfort/honesty, not the enforce point).
 *
 * The shape (`List<String>`) is FIXED, so the client binding is built and tested against it now; wiring the
 * real HTTP implementation once the endpoint is merged is trivial (one shell-side swap). **Fail-closed** is
 * enforced at the call site ([CommViewModel.refreshWritable]): an error / not-yet-known result leaves the
 * writable set `null` → the composer is disabled, never optimistically open (CYP-288 fail-closed class).
 */
fun interface WritableChannelsApi {
    /** The channel ids the resolved caller may write to right now. Invariant: `writable ⊆ readable`. */
    suspend fun writableChannels(): List<String>
}
