package com.tneff.cyppieagents.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * CYP-215 (CYP-212 avatar backend) — a per-agent avatar override. **THE shared contract** the server
 * endpoints and the client avatar resolver (CYP-216) both compile against ("Vertrags-DTOs gehören in
 * :core"). Two discriminated, orthogonal sources; a `null` [Agent.avatar] = no override.
 *
 * Wire form (kotlinx closed polymorphism; [com.tneff.cyppieagents.CommJson] `classDiscriminator="type"`):
 * ```
 *   preset  → {"type":"preset","style":"bottts","seed":"backend"}
 *   upload  → {"type":"upload","ref":"a1b2c3d4e5f6"}
 *   none    → the `avatar` field is ABSENT  (explicitNulls=false)
 * ```
 * Backward-compatible: an older payload without the field decodes to `avatar = null`, and the client
 * falls back to its deterministic default (the CYP-210 colour slot).
 *
 * **Write vs read asymmetry (server-authoritative, fail-closed by construction):** the read DTOs
 * ([Agent], [AgentDetail]) carry the full [AgentAvatar]. The JSON *write* paths ([AgentEdit],
 * [NewAgentSpec]) accept only [Preset] — a client **cannot even express** an [Upload] there, so it can
 * never forge an upload `ref` pointing at bytes it does not control. An [Upload] is minted ONLY by the
 * authoritative multipart endpoint after full server-side validation (magic-bytes → dimension cap →
 * re-encode strip).
 */
@Serializable
sealed interface AgentAvatar {

    /**
     * A self-hosted **DiceBear** avatar chosen by ([style], [seed]) — resolved **server-side** to a
     * bundled / pre-generated PNG, **egress-free** (never a call to api.dicebear.com). The safe default
     * path: no untrusted bytes, so it is settable directly on the JSON write paths ([AgentEdit] /
     * [NewAgentSpec]).
     *
     * [style] MUST be one of the server's **license-approved, self-hosted** styles — the allow-list is
     * owned by UIUX (CYP-214); the server REJECTS an unknown/unapproved style **fail-closed**, the client
     * never dictates the set. [seed] selects the deterministic variant within the style (the server
     * substitutes the agent id when the client sends a blank seed).
     */
    @Serializable
    @SerialName("preset")
    data class Preset(val style: String, val seed: String) : AgentAvatar

    /**
     * A custom image the operator uploaded. [ref] is an **opaque, server-minted** identifier for the
     * validated + re-encoded PNG (a short content hash) — **never** a client filename or path
     * (path-traversal-safe) and minted ONLY by the authoritative multipart upload endpoint. The bytes are
     * served by `GET /api/agents/{id}/avatar`; [ref] doubles as the cache-busting version token (it
     * changes on every re-upload, so a stale client cache is invalidated).
     */
    @Serializable
    @SerialName("upload")
    data class Upload(val ref: String) : AgentAvatar
}
