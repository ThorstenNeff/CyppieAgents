package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.GenerateReportRequest
import com.tneff.cyppieagents.model.ReportMeta
import com.tneff.cyppieagents.model.ReportSnapshot
import com.tneff.cyppieagents.routing.NotFoundException
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Holds the immutable report snapshots (S16 / CYP-89), per active project (MVP = 1). Each [generate]
 * **appends** a NEW snapshot with a monotonic id + a fresh server-clock `generatedAt` — a prior snapshot is
 * never mutated or merged (append-only ⇒ no read-modify-write). [list] is newest-first history. Stamping
 * (id + time) is owned here in one place; the [ReportGenerator] only builds content-free sections.
 *
 * CYP-220 Phase 6 S6: **store-seam interface** (CYP-223 pattern). Default = durable [FileReportStore] when a
 * `file` is given, else the ephemeral [InMemoryReportStore]; the Postgres impl ([PgReportStore]) implements the
 * same seam. The `rep-N` id counter is resumed above the highest persisted id on open (and realigned on import),
 * so ids never collide across a restart or a migration.
 */
interface ReportStore {
    suspend fun generate(req: GenerateReportRequest): ReportSnapshot
    fun list(): List<ReportMeta>
    fun get(id: String): ReportSnapshot

    companion object {
        /** Factory seam: durable [FileReportStore] when [file] is set, else the ephemeral in-memory store. */
        operator fun invoke(
            generator: ReportGenerator,
            projectId: String,
            file: File? = null,
            clock: () -> Long = System::currentTimeMillis,
        ): ReportStore = if (file == null) InMemoryReportStore(generator, projectId, clock) else FileReportStore(generator, projectId, file, clock)
    }
}

/** The `rep-N` numeric part, or null for a malformed id — used to resume/realign the id counter. */
internal fun reportIdNum(id: String): Int? = id.removePrefix("rep-").toIntOrNull()

/** Newest-first [ReportMeta] projection of a snapshot list — shared by every impl so `list()` can't drift. */
internal fun reportMetas(snapshots: List<ReportSnapshot>): List<ReportMeta> =
    snapshots.sortedByDescending { it.generatedAt }.map { ReportMeta(it.id, it.type, it.generatedAt, reportSummary(it)) }

internal fun reportSummary(s: ReportSnapshot): String = s.sections.sumOf { it.items.size }.let { "$it Beobachtungen" }

/**
 * Ephemeral in-memory [ReportStore] — the prior behaviour, now the tests/dry-boot default. A restart loses
 * history ([FileReportStore]/[PgReportStore] are the durable stores). The `counter` + snapshot list are the
 * single stamping point; [onPersist] is the durability hook the File subclass overrides.
 */
open class InMemoryReportStore(
    private val generator: ReportGenerator,
    private val projectId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ReportStore {
    private val lock = Any()
    private val snapshots = mutableListOf<ReportSnapshot>()
    private var counter = 0

    override suspend fun generate(req: GenerateReportRequest): ReportSnapshot {
        val built = generator.build(req.type, req.since, req.until) // reads sources OUTSIDE the lock
        val snap = synchronized(lock) {
            val s = ReportSnapshot(
                id = "rep-${counter++}",
                type = req.type,
                generatedAt = clock(),
                projectId = projectId,
                sources = built.sources,
                window = built.window,
                sections = built.sections,
            )
            snapshots.add(s)
            s
        }
        onPersist() // durability hook (no-op in memory; File flushes the full list)
        return snap
    }

    override fun list(): List<ReportMeta> = synchronized(lock) { reportMetas(snapshots) }

    override fun get(id: String): ReportSnapshot =
        (synchronized(lock) { snapshots.firstOrNull { it.id == id } })
            ?: throw NotFoundException("report '$id' not found", code = "report_not_found")

    /** Durability hook, called after each append (outside the append's critical section). */
    protected open fun onPersist() {}

    /** A stable snapshot of the current list (for the File flush). */
    protected fun snapshotList(): List<ReportSnapshot> = synchronized(lock) { snapshots.toList() }

    /** Replace the in-memory state on load, resuming the id counter above the highest persisted id. */
    protected fun loadSnapshots(loaded: List<ReportSnapshot>) = synchronized(lock) {
        snapshots.clear(); snapshots.addAll(loaded)
        counter = (loaded.mapNotNull { reportIdNum(it.id) }.maxOrNull()?.plus(1)) ?: 0
    }
}

/**
 * CYP-220 Phase 6 S6 — the durable **local** [ReportStore] (net-new; report was in-memory-only before). Mirrors
 * [com.tneff.cyppieagents.connector.JsonFileSessionStore]: loads the snapshot list on open (resuming the id
 * counter), flushes the full list atomically after each append. A torn/corrupt file is backed up and the store
 * starts empty, so a bad file never bricks boot (the report history just resets). Not a credential store.
 */
class FileReportStore(
    generator: ReportGenerator,
    projectId: String,
    private val file: File,
    clock: () -> Long = System::currentTimeMillis,
) : InMemoryReportStore(generator, projectId, clock) {
    private val flushLock = Any()
    private val log = LoggerFactory.getLogger("report.filestore")

    init {
        if (file.exists() && file.length() > 0) {
            try {
                loadSnapshots(CommJson.decodeFromString<List<ReportSnapshot>>(file.readText()))
            } catch (e: Exception) {
                val backup = File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
                runCatching { file.copyTo(backup, overwrite = true) }
                log.error("corrupt report store at {}; backed up to {} and starting empty", file, backup, e)
            }
        }
    }

    override fun onPersist() { flush() }

    private fun flush() = synchronized<Unit>(flushLock) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(CommJson.encodeToString(snapshotList()))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
