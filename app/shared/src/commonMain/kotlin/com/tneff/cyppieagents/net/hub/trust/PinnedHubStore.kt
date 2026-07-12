package com.tneff.cyppieagents.net.hub.trust

import kotlin.io.encoding.Base64

/**
 * CYP-478 — durable **TOFU pin** custody: the map of `hubId → pinned hub-static (DH public key)`, persisted
 * across restarts (SSH `known_hosts` semantics). A pinned key is a **public** value, not a secret — so this
 * follows the durable-KV [com.tneff.cyppieagents.ui.ThemePreferences] idiom (real on JVM `java.util.prefs` and
 * Web `localStorage`), **not** the session-scoped, in-memory-in-Phase-1 secret store
 * ([com.tneff.cyppieagents.auth.SecureSessionStore]) — using the latter would silently break TOFU because a
 * pin that doesn't survive the session can't detect a key change on the next connect.
 *
 * The security property this store carries is **integrity**, not confidentiality: an entry that an attacker
 * can rewrite lets them re-adopt a poisoned key. That is the same local-malware / same-OS-account trade-off
 * flagged for the operator device key (CYP-443 Slice 2); at-rest tamper-resistance (a hardware keystore) is a
 * later hardening, not this MVP. What this store DOES guarantee: a load never silently returns an
 * attacker-chosen key in place of the pinned one — a garbled/short entry **fails safe to `null`** (→ the
 * caller must run the OOB confirm again; never a silent adopt), and a wrong-sized decoded key is rejected.
 */
interface PinnedHubStore {
    /** The pinned hub static for [hubId], or `null` if none is pinned (or the stored entry is unreadable). */
    fun pinnedKey(hubId: String): ByteArray?

    /** Persist [hubStatic] as the pin for [hubId] (overwrites any prior pin — an OOB re-pin is just a `pin`). */
    fun pin(hubId: String, hubStatic: ByteArray)

    /** Remove the pin for [hubId] (e.g. an explicit un-pin). */
    fun unpin(hubId: String)
}

/** Noise_NK X25519 static keys are exactly 32 bytes; a decoded pin of any other size is rejected (fail-safe). */
internal const val HUB_STATIC_KEY_SIZE = 32

/** The per-hub storage key. Namespaced so it never collides with other durable-KV consumers. */
internal fun pinStorageKey(hubId: String): String = "hubpin.$hubId"

/** Process-local pin map — the default where no synchronous durable KV exists (Android/iOS, hermetic tests). */
class InMemoryPinnedHubStore : PinnedHubStore {
    private val pins = mutableMapOf<String, ByteArray>()
    override fun pinnedKey(hubId: String): ByteArray? = pins[hubId]?.copyOf()
    override fun pin(hubId: String, hubStatic: ByteArray) { pins[hubId] = hubStatic.copyOf() }
    override fun unpin(hubId: String) { pins.remove(hubId) }
}

/**
 * Durable [PinnedHubStore] over a platform **keyed** string KV. The value↔String mapping (Base64) + the
 * fail-safe decode live HERE (commonMain, unit-tested), so each platform `actual` only wires the raw keyed
 * get/set/remove. A garbled or wrong-sized stored value decodes to `null` (→ treated as "not pinned", which
 * forces a fresh OOB confirm — never a silent adopt of an unverified key).
 */
class PersistentPinnedHubStore(
    private val load: (key: String) -> String?,
    private val store: (key: String, value: String) -> Unit,
    private val remove: (key: String) -> Unit,
) : PinnedHubStore {

    override fun pinnedKey(hubId: String): ByteArray? = decodePin(load(pinStorageKey(hubId)))

    override fun pin(hubId: String, hubStatic: ByteArray) {
        require(hubStatic.size == HUB_STATIC_KEY_SIZE) { "hub static must be $HUB_STATIC_KEY_SIZE bytes" }
        store(pinStorageKey(hubId), Base64.Default.encode(hubStatic))
    }

    override fun unpin(hubId: String) { remove(pinStorageKey(hubId)) }
}

/** Fail-safe: absent / non-Base64 / wrong-sized → `null` (never a partial or attacker-shaped key). */
internal fun decodePin(raw: String?): ByteArray? {
    if (raw.isNullOrEmpty()) return null
    val bytes = runCatching { Base64.Default.decode(raw) }.getOrNull() ?: return null
    return if (bytes.size == HUB_STATIC_KEY_SIZE) bytes else null
}

/** The production pin store for the current platform: durable where a synchronous KV exists, in-memory otherwise. */
expect fun defaultPinnedHubStore(): PinnedHubStore
