package com.tneff.cyppieagents.db

import com.tneff.cyppieagents.CommJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** A store's lifecycle vs its bound instance (Design §1.1). MIGRATING/READ_ONLY gate writes during a switch (§4.3). */
enum class BindingState { ACTIVE, MIGRATING, READ_ONLY }

/**
 * CYP-720 (BE-8) — **WHY** a binding holds a write-lock. [BindingState] alone conflates two causes with
 * OPPOSITE operator actions, so the 409 the operator sees was right for one and actively misleading for the other:
 *
 *  - [MIGRATION_WINDOW] — a real migration is in flight (§4.3). Nothing is broken, the freeze is **temporary**,
 *    and "retry after the switch completes" is a real path. This is the pre-CYP-720 behaviour, kept verbatim.
 *  - [LEGACY_UNEVALUATED] — the CYP-714 fail-closed coercion: a row persisted before `state` existed loads as
 *    READ_ONLY because "unknown is not ACTIVE". For such a binding the migration copy is **triply false** —
 *    nothing is migrating, no switch is coming, and it is not temporary: it holds until someone deliberately
 *    re-evaluates the binding. Telling that operator to "wait for the switch" sends them to wait for an event
 *    that never arrives; the correct signal is action-required.
 *
 * Defaulted to [MIGRATION_WINDOW] so rows persisted before this field existed decode back-compat (and because a
 * reason is only ever CONSULTED while write-locked — on an ACTIVE binding it is moot).
 */
enum class BindingReason { MIGRATION_WINDOW, LEGACY_UNEVALUATED }

/**
 * Which named DSN a given `(storeKey, projectId)` is bound to (Design §1.1). Per-store AND per-project, so
 * different projects can point the same store at different instances. `dsnId` empty is not represented —
 * an unbound store simply has no [StoreBinding] (→ the store's file fallback, resolved in a later phase).
 */
@Serializable
data class StoreBinding(
    val storeKey: String,
    val projectId: String,
    val dsnId: String,
    val schema: String = "public",
    val state: BindingState = BindingState.ACTIVE,
    val boundAt: Long,
    /** CYP-720: WHY a write-lock is held — see [BindingReason]. Consulted only while [state] is not ACTIVE. */
    val reason: BindingReason = BindingReason.MIGRATION_WINDOW,
)

/**
 * CYP-220 Phase 2b — the per-`(storeKey, projectId)` binding registry (Design §1.1). A **bootstrap store, MUST
 * stay on our infra** (it decides where every other store's data lives). [bind] creates or **rebinds** (a switch
 * to a new instance is a bind with a new `dsnId`, done atomically after the migration completes — §1.3). No
 * secrets here (dsnIds only) — but still 0600 hygiene. `null` file → in-memory (tests / dry boots).
 */
interface BindingRegistry {
    /** Bind (or REBIND) `(storeKey, projectId)` to [dsnId]. Returns the new binding (state=ACTIVE). */
    fun bind(storeKey: String, projectId: String, dsnId: String, schema: String = "public"): StoreBinding
    /** Transition a binding's [state] (e.g. → MIGRATING / READ_ONLY during a switch). null if unbound. */
    fun setState(storeKey: String, projectId: String, state: BindingState): StoreBinding?
    fun binding(storeKey: String, projectId: String): StoreBinding?
    fun list(): List<StoreBinding>
    /** Remove a binding (→ the store falls back to its file impl). Idempotent. */
    fun unbind(storeKey: String, projectId: String): Boolean

    companion object {
        operator fun invoke(file: File?, clock: () -> Long = { System.currentTimeMillis() }): BindingRegistry =
            FileBindingRegistry(file, clock)
    }
}

class FileBindingRegistry(private val file: File?, private val clock: () -> Long) : BindingRegistry {
    private val lock = Any()
    private val log = LoggerFactory.getLogger("db.bindingregistry")
    private val byKey: MutableMap<String, StoreBinding> = mutableMapOf()

    init {
        val f = file
        if (f != null && f.exists() && f.length() > 0) {
            runCatching {
                // CYP-714: an OLD binding row written before the `state` field existed carries NO "state" key.
                // Decoding it straight into [StoreBinding] would silently apply the data-class default (ACTIVE) —
                // making that legacy binding freely writable and losing the CYP-220 migration write-lock for
                // pre-existing data (PgStoreRouting.activeDataSource routes to Postgres iff state==ACTIVE).
                // "unknown" is NOT ACTIVE. The fix is at THIS read seam, not the write seam (bind/setState are
                // retroactively blind — they can't reach an already-persisted row): a row with no persisted state
                // loads fail-closed as READ_ONLY, so activeDataSource returns null AND inMigrationWindow returns
                // true → writes are rejected until the binding is deliberately re-evaluated. A row that DID persist
                // its state is decoded verbatim (an explicit ACTIVE stays ACTIVE — no over-coercion).
                val root = CommJson.parseToJsonElement(f.readText()).jsonObject
                for ((k, v) in root) {
                    val obj = v.jsonObject
                    val decoded = CommJson.decodeFromJsonElement(StoreBinding.serializer(), obj)
                    // CYP-720: this is the ONE site that stamps LEGACY_UNEVALUATED — the coercion IS the cause,
                    // so the reason is recorded exactly where it is known. Everything reachable through the live
                    // API (bind/setState) is a real migration and keeps MIGRATION_WINDOW.
                    byKey[k] = if ("state" in obj) {
                        decoded
                    } else {
                        decoded.copy(state = BindingState.READ_ONLY, reason = BindingReason.LEGACY_UNEVALUATED)
                    }
                }
            }.onFailure { log.error("corrupt store-binding registry at {}; starting empty", f) }
        }
    }

    private fun key(storeKey: String, projectId: String) = "$storeKey\u0000$projectId"

    override fun bind(storeKey: String, projectId: String, dsnId: String, schema: String): StoreBinding = synchronized(lock) {
        val b = StoreBinding(storeKey, projectId, dsnId, schema, BindingState.ACTIVE, clock())
        byKey[key(storeKey, projectId)] = b
        persist()
        b
    }

    override fun setState(storeKey: String, projectId: String, state: BindingState): StoreBinding? = synchronized(lock) {
        val cur = byKey[key(storeKey, projectId)] ?: return null
        // CYP-720: setState IS the migration path, so it stamps MIGRATION_WINDOW explicitly — which also RESETS a
        // re-evaluated legacy binding instead of leaving a stale LEGACY_UNEVALUATED reason behind on it.
        val next = cur.copy(state = state, reason = BindingReason.MIGRATION_WINDOW)
        byKey[key(storeKey, projectId)] = next
        persist()
        next
    }

    override fun binding(storeKey: String, projectId: String): StoreBinding? = synchronized(lock) { byKey[key(storeKey, projectId)] }

    override fun list(): List<StoreBinding> = synchronized(lock) { byKey.values.toList() }

    override fun unbind(storeKey: String, projectId: String): Boolean = synchronized(lock) {
        val removed = byKey.remove(key(storeKey, projectId)) != null
        if (removed) persist()
        removed
    }

    private fun persist() {
        val f = file ?: return
        f.parentFile?.let { it.mkdirs(); restrictDirToOwner(it) }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.delete(); tmp.createNewFile(); restrictToOwner(tmp)
        tmp.writeText(CommJson.encodeToString(byKey.toMap()))
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        restrictToOwner(f)
    }

    private fun restrictToOwner(f: File) {
        runCatching {
            Files.setPosixFilePermissions(f.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }.onFailure { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true) }
    }

    private fun restrictDirToOwner(d: File) {
        runCatching {
            Files.setPosixFilePermissions(
                d.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            )
        }.onFailure {
            d.setReadable(false, false); d.setReadable(true, true)
            d.setWritable(false, false); d.setWritable(true, true)
            d.setExecutable(false, false); d.setExecutable(true, true)
        }
    }
}
