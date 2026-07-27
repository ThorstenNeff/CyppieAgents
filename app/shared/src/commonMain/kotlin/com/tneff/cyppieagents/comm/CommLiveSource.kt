package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import kotlinx.coroutines.flow.Flow

/**
 * UI-shaped live event from the comm hub. This is the renderer's view, NOT the wire format — the
 * real `/ws/comm` source (CYP-18) maps the `:core` WS frame DTOs (`MessageEvent`/`AclEvent`/
 * `ChannelsEvent`, Spec 02 §8 — not in `:core` yet) into these. Until then the panel runs against
 * [StubCommLiveSource]. Keeping this seam means the `/ws/comm` swap is the only follow-up commit.
 */
sealed interface CommLiveEvent {
    /** The socket is open — the timeline may honestly show "live". */
    data object Connected : CommLiveEvent

    /** The socket dropped — the timeline must stop claiming "live" (CYP-17 §5 honesty). */
    data object Disconnected : CommLiveEvent

    /** CYP-291: the server closed `/ws/comm` with 1008 (VIOLATED_POLICY) — a revoked/invalid token. TERMINAL:
     *  the timeline stops claiming "live" AND the VM must NOT reconnect (re-opening would re-send the revoked
     *  token every backoff period). Distinct from a transient [Disconnected], which DOES reconnect. */
    data object AccessRevoked : CommLiveEvent

    /**
     * CYP-786: a `/ws/comm` frame could not be decoded against the client's `:core` schema (a
     * [kotlinx.serialization.SerializationException] — an unknown discriminator or a missing field, i.e. the
     * client and server app-schemas have SKEWED, e.g. a version deployed on one side but not the other). **TERMINAL
     * + DISTINCT** (mirrors [AccessRevoked], the CYP-819 D2 "safe-but-silent → own named state" discipline): a skew
     * NEVER resolves without a deploy, so the VM must NOT `.reconnecting()`-churn (the server would replay the same
     * undecodable frame every backoff) — and it is NOT a transient offline drop, so it is surfaced as its own named
     * operator-facing state, never folded into [Disconnected]. [detail] = a best-effort hint from the exception
     * message (e.g. the missing field), for the banner — presentation only, never trusted/parsed.
     */
    data class ProtocolSkew(val detail: String? = null) : CommLiveEvent

    /** A new message pushed by the hub; deduped by [Message.id] downstream. */
    data class MessageReceived(val message: Message) : CommLiveEvent

    /** The set of channels the viewer may read changed (ACL/config). */
    data class ChannelsChanged(val channels: List<Channel>) : CommLiveEvent

    /**
     * CYP-273/S7: an ACL row changed on `/ws/comm` (the server pushes `AclEvent`s to readers, incl. the
     * caller's own `(channel, self)` grant). Content-free by design — the VM does not trust the pushed row;
     * it re-fetches the server-authoritative writable set (`GET /api/channels/writable`) in response, so a
     * revoked/granted write disables/enables the composer live. Distinct from [ChannelsChanged] (readable set)
     * and never a timeline event.
     */
    data object AclChanged : CommLiveEvent
}

/** Source of the live comm stream. The Ktor `/ws/comm` adapter (CYP-18) replaces the stub here. */
interface CommLiveSource {
    fun events(): Flow<CommLiveEvent>
}
