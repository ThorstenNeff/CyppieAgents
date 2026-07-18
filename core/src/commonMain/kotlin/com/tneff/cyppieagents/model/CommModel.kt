package com.tneff.cyppieagents.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Canonical comm-hub domain contract (Spec 02 §5). One definition, compiled into both
 * `:server` and `:app:shared` via `:core`, so client and server share the exact shape.
 *
 * Security note: secrets never live in shared DTOs. An agent's static bearer token is held
 * server-side only (token → agentId registry); it is NOT a field of [Agent]. The wire view
 * of an agent is [Agent] itself — there is nothing to strip.
 */

// CYP-217: `@Serializable` gives a COMPILE-TIME serializer on every target. Without it, direct
// `encodeToString(Role)`/`decodeFromString<Role>` fell back to runtime reflection — fine on jvm/android but
// throwing `SerializationException` on js/wasmJs (no reflection). The `@SerialName`s below were already
// present (they equal the constant names → wire format is unchanged, no DTO regress).
@Serializable
enum class Role {
    @SerialName("PO") PO,
    @SerialName("WORKER") WORKER,
    /**
     * Read-only reviewer ("Product Lead", CYP-98 / Doc 09). NOT part of the MVP build loop (PO/WORKER):
     * the ACL default posture grants `canRead` on the authorized hub spokes but **`canWrite = false`
     * everywhere** (enforced in [AclMatrix], not just the UI) and gives it **no spoke of its own** — so it
     * is structurally never a task target (the mediation router routes to `po-<agentId>`, which does not
     * exist for a PL). PL output is untrusted data, never an instruction; fail-closed.
     */
    @SerialName("PRODUCT_LEAD") PRODUCT_LEAD,
}

/** Runtime lifecycle state of an agent's process (CYP-73). Content-free; safe on the public wire. */
@Serializable
enum class AgentRunState {
    @SerialName("RUNNING") RUNNING,
    @SerialName("STOPPED") STOPPED,
    @SerialName("ERROR") ERROR,
}

/**
 * CYP-421 (b) — WHY an agent's run-state is [AgentRunState.ERROR], as a **finite code**, never free text.
 * Carried on [AgentRunStateEvent.errorCode] (non-null ONLY when runState == ERROR). A code — not a stderr
 * string or an exit-signal message — keeps the content-free `/ws/lifecycle` boundary intact (the always-visible
 * AgentWindow header must not leak operator-gated detail). Authoritativeness is normalized: an authoritative
 * observed OS exit code maps to [SIGNALLED]/[CRASHED]; a best-effort spawn failure to [SPAWN_FAILED]; anything
 * unmapped to [UNKNOWN] (never a fabricated specific cause).
 */
@Serializable
enum class AgentErrorCode {
    /** Died on a signal — exit code 128 < c ≤ 192 (e.g. 137 = SIGKILL/OOM, 143 = SIGTERM). */
    @SerialName("SIGNALLED") SIGNALLED,
    /** Exited non-zero for a non-signal reason (a crash / error exit). */
    @SerialName("CRASHED") CRASHED,
    /** Never started — a spawn attempt threw (best-effort; the exception message stays in the server log only). */
    @SerialName("SPAWN_FAILED") SPAWN_FAILED,
    /** ERROR with no classifiable cause — the normalizing fallback (an ERROR frame is never code-less). */
    @SerialName("UNKNOWN") UNKNOWN,
}

/** A participant in the hub. Hub role drives the default ACL (PO = hub of the spokes). */
@Serializable
data class Agent(
    val id: String,
    val name: String,
    val role: Role,
    /** worktree sub-folder name; informational on the wire, used server-side for spawns. */
    val worktree: String,
    /**
     * Live process run-state (CYP-73). Named `runState` to avoid colliding with the agent *activity*
     * `AgentStatus` (CYP-12) — different concept (process lifecycle ≠ activity). Additive + defaulted so
     * older payloads still decode and the many config-time `Agent(...)` constructions are unaffected;
     * `GET /api/agents` fills the real value per agent. Not a secret — same public exposure as this DTO.
     */
    val runState: AgentRunState = AgentRunState.RUNNING,
    /**
     * Connector fidelity for the steady-state per-agent read model (Doc 10 §6.4 / CYP-120). Known at
     * boot from the agent's connector; the UI surfaces which Mediator functions are reduced. Additive +
     * defaulted (null = not yet resolved / config-time `Agent(...)`), exactly like [runState] — so older
     * payloads still decode and every config-time construction is unaffected. The endpoint population
     * (per-agent connector → capabilities) and the `connectorKind` choice on the agent-spec land
     * additively in CYP-122; this field is the foundation Dev scaffolds the UI against.
     */
    val capabilities: Capabilities? = null,
    /**
     * Which connector drives this agent (Doc 10 §1 / CYP-122). `STREAM_JSON` (default) = Connector A, the
     * first-class full-fidelity path; `MCP` = Connector B (opt-in, lower fidelity). Additive + defaulted so
     * older payloads/config-time constructions are unaffected. The choice is operator-set on the agent
     * spec (server-enforced opt-in, never flipped by a channel message); the spawn path selects the
     * connector implementation from it (CYP-122).
     */
    val connectorKind: ConnectorKind = ConnectorKind.STREAM_JSON,
    /**
     * The provider (tool) behind this agent (E2.1 / CYP-137, provider-display-spec §1) — a **fourth,
     * separate axis**: provider ≠ [connectorKind] ≠ fidelity ([capabilities]) ≠ identity, never merged.
     * Declared by the agent's connector, surfaced subordinate to identity ("PO (Claude)"). Additive +
     * nullable (null = not yet resolved / config-time `Agent(...)` / an older payload), exactly like
     * [capabilities] — so older payloads still decode and the participant identity stays
     * **provider-agnostic**. Not a secret / not operator-gated — same public exposure as the rest of this DTO.
     */
    val provider: ProviderInfo? = null,
    /**
     * CYP-208 / CYP-210 — per-agent display colour for UI theming (window chrome / title bar), carried on
     * the list [Agent] so the client themes without an extra call. **Seam shape = nullable hex string** (e.g.
     * `"#3B82F6"`); the final derivation (hex vs palette-slot / [`ColorSlot`]) is settled by the UIUX spec
     * (CYP-209) — this stays a nullable String so a slot id or a hex both fit and older payloads decode.
     * `null` = no override → the client derives a default (slot from the agent id). Not a secret, not
     * operator-gated — same public exposure as the rest of this DTO.
     */
    val color: String? = null,
    /**
     * CYP-215 (CYP-212 avatar backend) — the per-agent avatar override, carried on the list [Agent] so the
     * client resolver (CYP-216) themes the window without an extra call. A discriminated [AgentAvatar]:
     * a self-hosted DiceBear [AgentAvatar.Preset] or an operator [AgentAvatar.Upload] (opaque server-minted
     * `ref`). Additive + nullable exactly like [color] — `null` = no override → the client falls back to
     * its deterministic default (the colour slot), and older payloads without the field still decode. Not
     * a secret, not operator-gated to READ — same public exposure as the rest of this DTO.
     */
    val avatar: AgentAvatar? = null,
)

/**
 * A provider (the tool/vendor behind an agent, e.g. Claude/Codex/Gemini), the fourth connector-vertrag
 * axis (CYP-137). [id] is a stable machine id (`"claude"`); [displayName] the human label (`"Claude"`).
 * MVP declares only Claude. Kept deliberately minimal; an optional model detail lands later additively.
 */
@Serializable
data class ProviderInfo(val id: String, val displayName: String) {
    companion object {
        /** The single-sourced MVP provider — both Connector A (CLI/stream-json) and B (MCP) are Claude. */
        val CLAUDE: ProviderInfo = ProviderInfo("claude", "Claude")
    }
}

enum class ChannelKind {
    @SerialName("DIRECT") DIRECT,
    @SerialName("GROUP") GROUP,
    @SerialName("HUB") HUB,
}

@Serializable
data class Channel(
    val id: String,
    val name: String,
    val kind: ChannelKind,
    val members: List<String>,
    /**
     * Tenant scope (S12 / CYP-81). Additive + defaulted → older payloads decode onto the single MVP
     * project ([DEFAULT_PROJECT_ID]); never a wildcard. Project isolation is enforced through
     * [AclMatrix]/[ProjectScope], fail-closed (a blank/mismatched id is out of scope, not global).
     */
    val projectId: String = DEFAULT_PROJECT_ID,
)

/**
 * The configurable heart of the system (Spec 02 §5.3): exactly one entry per (channel, agent).
 * Hub-and-spoke is just a particular ACL assignment, not special-cased logic.
 */
@Serializable
data class AclEntry(
    val channelId: String,
    val agentId: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    /** Tenant scope (S12 / CYP-81). Additive + defaulted; see [Channel.projectId]. An entry whose
        `projectId` ≠ the active project is dropped from the [AclMatrix] (fail-closed), so it can
        grant nothing across the project boundary. */
    val projectId: String = DEFAULT_PROJECT_ID,
)

enum class MessageKind {
    @SerialName("TASK") TASK,
    @SerialName("STATUS") STATUS,
    @SerialName("NOTE") NOTE,
}

@Serializable
data class MessageMeta(
    val inReplyTo: String? = null,
    val kind: MessageKind? = null,
)

@Serializable
data class Message(
    val id: String,
    val channelId: String,
    val from: String,
    val body: String,
    /** epoch millis */
    val ts: Long,
    val meta: MessageMeta? = null,
    /** Tenant scope (S12 / CYP-81). Stamped by the [Hub] with the active project on post; additive +
        defaulted so older persisted messages decode onto [DEFAULT_PROJECT_ID]. Out-of-project
        messages are filtered out by [AclMatrix.visibleMessages] (fail-closed). */
    val projectId: String = DEFAULT_PROJECT_ID,
    /**
     * CYP-705 — the store-assigned, monotonic append-order ordinal (the authoritative ordering key for the
     * read-state cursor; **not `ts`**, whose client-observed epoch-ms can collide/skew). CANONICAL and
     * **viewer-independent** — every viewer agrees on a message's `seq` (unlike the per-viewer read cursor,
     * which is never a `Message` field). Sourced from the store's monotonic sequence (SQLite `AUTOINCREMENT`;
     * in-memory insertion order) and stamped on the returned/delivered copy. Global-monotonic → also a valid
     * total order **within any channel** (a channel's messages are a strictly-increasing subsequence), so the
     * unread cursor works off it directly. Additive + defaulted (`0` = not-yet-persisted / an older payload),
     * so every existing `Message(...)` construction and every older stored/wire payload still decode.
     */
    val seq: Long = 0L,
)

// ----- REST request/response wire types -----

/**
 * Body for POST /api/channels/{id}/messages. Deliberately carries NO `from` and NO channel
 * override: the sender is resolved from the bearer identity and the channel from the path,
 * never from client/agent-supplied content (Reviewer Gate #1).
 */
@Serializable
data class SendMessageRequest(
    val body: String,
    val meta: MessageMeta? = null,
)

/**
 * CYP-705 — one channel's read-state **for the calling principal** (`GET /api/read-state` returns a
 * `List<ChannelReadState>`; `POST /api/channels/{id}/read` returns the updated one). Server-computed.
 *
 * **The three-state honesty edge is carried by PRESENCE, not a nullable field** (UIUX2 §8 ① UX-condition):
 *  - a channel **present** with [unreadCount] `> 0` → **unread** (count badge);
 *  - **present** with [unreadCount] `== 0` → **confirmed-read** (render nothing — an authoritative all-clear);
 *  - **ABSENT** from the read-state list → **UNKNOWN** (no cursor yet → the visible neutral "•", never silence,
 *    never a fabricated 0). A `ChannelReadState` exists **iff** the principal has a cursor for that channel, so
 *    both fields are non-null and [lastReadSeq] is always a real value.
 *
 * Per-viewer read-state is **self-only** — no read-receipts (a principal never observes another's cursor).
 */
@Serializable
data class ChannelReadState(
    val channelId: String,
    /** The principal's cursor: `seq` of the last message they marked read in this channel (advance-only). */
    val lastReadSeq: Long,
    /** Server-computed count of messages in this channel with `seq > lastReadSeq`, ACL-`canRead` + in-project,
        excluding the principal's own sends. `0` = confirmed-read; `> 0` = unread. */
    val unreadCount: Int,
)

/** CYP-705 — body for `POST /api/channels/{id}/read`. The client sends the `seq` of the last message it has
 *  read; the server advances the cursor to `max(existing, upToSeq)` (monotonic, non-optimistic). */
@Serializable
data class MarkReadRequest(val upToSeq: Long)

@Serializable
data class ApiError(val code: String, val message: String)

/** Uniform error envelope (Spec 02 §7): `{ "error": { code, message } }`. */
@Serializable
data class ApiErrorBody(val error: ApiError)
