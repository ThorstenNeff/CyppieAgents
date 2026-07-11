package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ReportSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * CYP-415 (S-F / D2) — embedded-SQLite [ReportStore]. Like [FileReportStore] it **extends** [InMemoryReportStore]
 * (the single `rep-N` stamping point) and only overrides the durability hook: [onPersist] writes the newest
 * snapshot into a SQLite row (append-only — reports are never mutated), and [init] loads the persisted list back
 * (resuming the id counter via [InMemoryReportStore.loadSnapshots]). Benign switch (reports re-generatable), no
 * first-boot import. Single held connection + WAL, `synchronized` in the base (`generate` is `suspend`; the
 * JDBC write in `onPersist` runs after the append's critical section, same as the File flush).
 */
class SqliteReportStore(
    generator: ReportGenerator,
    projectId: String,
    dbPath: Path,
    clock: () -> Long = System::currentTimeMillis,
) : InMemoryReportStore(generator, projectId, clock), AutoCloseable {

    private val conn: Connection = run {
        dbPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val c = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        c.autoCommit = true
        c.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.executeUpdate(CREATE_TABLE)
        }
        c
    }
    private val writeLock = Any()

    init {
        loadSnapshots(readAll()) // resume the counter above the highest persisted rep-N (base contract)
    }

    private fun readAll(): List<ReportSnapshot> =
        conn.prepareStatement("SELECT snapshot_json FROM report ORDER BY generated_at").use { ps ->
            ps.executeQuery().use { rs ->
                val out = ArrayList<ReportSnapshot>()
                while (rs.next()) out.add(CommJson.decodeFromString(ReportSnapshot.serializer(), rs.getString(1)))
                out
            }
        }

    override fun onPersist() {
        // Append-only: the just-generated snapshot is the last one. INSERT OR REPLACE by id (idempotent).
        val newest = snapshotList().lastOrNull() ?: return
        synchronized(writeLock) {
            conn.prepareStatement(
                "INSERT INTO report(id, generated_at, snapshot_json) VALUES(?, ?, ?) " +
                    "ON CONFLICT(id) DO UPDATE SET generated_at=excluded.generated_at, snapshot_json=excluded.snapshot_json",
            ).use { ps ->
                ps.setString(1, newest.id)
                ps.setLong(2, newest.generatedAt)
                ps.setString(3, CommJson.encodeToString(ReportSnapshot.serializer(), newest))
                ps.executeUpdate()
            }
        }
    }

    override fun close() {
        runCatching { conn.close() }
    }

    private companion object {
        val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS report (
              id TEXT PRIMARY KEY,
              generated_at INTEGER NOT NULL,
              snapshot_json TEXT NOT NULL
            )
        """.trimIndent()
    }
}
