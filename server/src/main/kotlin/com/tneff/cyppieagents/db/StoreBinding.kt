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
                    byKey[k] = if ("state" in obj) decoded else decoded.copy(state = BindingState.READ_ONLY)
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
        val next = cur.copy(state = state)
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
