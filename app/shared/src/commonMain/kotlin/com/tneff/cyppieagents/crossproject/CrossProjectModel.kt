package com.tneff.cyppieagents.crossproject

/**
 * Cross-project channel authorization model (S17 / CYP-93). UI-side types for the authorization/disclosure
 * shell; the wire DTOs land in `:core` when the backend CYP-93 seam reconciles the permit mechanism (the
 * one open architecture question — how an authorized channel spans the exact-match `ProjectScope.permits`
 * boundary). Until then these plain types back the stub so the shell is built + tested independently.
 */

/** A new cross-project member is READ by default; WRITE only via explicit ACL (no auto-read — §2.3/§2.7). */
enum class CrossAccess { READ, WRITE }

/**
 * One concrete member-agent a shared channel reaches **across** the project boundary: who ([agentId]),
 * their **home project** ([homeProjectId]), and their **access** ([access]). The disclosure names these —
 * never "project B" wholesale (no over-widen; this is both disclosure-truth and the security boundary).
 */
data class CrossMember(val agentId: String, val homeProjectId: String, val access: CrossAccess)

/**
 * Per-channel cross-project state (PROJECT §2.4). [shared] is the authorization gate (owner consent);
 * [sharedAt] is when (epoch ms) — `sharedBy` is deferred to S18 (single-owner = trivially the one operator).
 * [reachableMembers] are the concrete cross-project members the channel reaches once authorized. Fail-closed:
 * not shared → projects stay isolated regardless of any lingering ACL entries (the gate, not the entries).
 */
data class CrossShareStatus(
    val channelId: String,
    val shared: Boolean,
    val sharedAt: Long? = null,
    val reachableMembers: List<CrossMember> = emptyList(),
)
