package com.tneff.cyppieagents.connect

/**
 * CYP-525 GE2 (the load-bearing lockout guard) — a **durable, reload-safe** record of whether the operator has
 * acknowledged saving THIS device's backup codes at first-enroll. The connect flow does **not** reach `CONNECTED`
 * until this is set (the ack is an `enabled`-gate, not a tap-through), AND it must **survive a reload** — a reload
 * before the ack must never strand the operator "enrolled without acknowledged codes" (they re-see the reveal,
 * which is safe; they must never silently skip it). Keyed per `hubId` so each hub's first-enroll ack is independent.
 *
 * **Fail-safe:** absent ⇒ NOT acknowledged (the gate holds; the safe default is "codes not yet saved"). Mirrors the
 * [com.tneff.cyppieagents.ui.PersistentThemePreferences] idiom: the key mapping + default live here (commonMain,
 * unit-tested); each platform `actual` only wires the raw keyed get/set.
 */
interface DeviceCodesAckStore {
    fun isAcknowledged(hubId: String): Boolean
    fun acknowledge(hubId: String)
}

/** Process-local default (hermetic tests / platforms without durable prefs). NOT reload-safe by itself. */
class InMemoryDeviceCodesAckStore : DeviceCodesAckStore {
    private val acked = mutableSetOf<String>()
    override fun isAcknowledged(hubId: String): Boolean = hubId in acked
    override fun acknowledge(hubId: String) { acked += hubId }
}

/**
 * Durable ack store over a platform **keyed** key-value primitive (the jvm actual = `java.util.prefs`, which
 * survives restarts — the reload-safety property). Absent/other value ⇒ fail-safe `false`.
 */
class PersistentDeviceCodesAckStore(
    private val load: (key: String) -> String?,
    private val store: (key: String, value: String) -> Unit,
) : DeviceCodesAckStore {
    override fun isAcknowledged(hubId: String): Boolean = load(ackKey(hubId)) == ACK_VALUE
    override fun acknowledge(hubId: String) = store(ackKey(hubId), ACK_VALUE)
}

internal fun ackKey(hubId: String): String = "$ACK_KEY_PREFIX$hubId"

internal const val ACK_KEY_PREFIX = "deviceCodesAck."
private const val ACK_VALUE = "true"

/** Platform default: durable (reload-safe) on JVM/Desktop; in-memory elsewhere (remote is jvm-only in the MVP). */
expect fun defaultDeviceCodesAckStore(): DeviceCodesAckStore
