package com.tneff.cyppieagents.auth

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** The platform's authorization roles (CYP-178). Distinct from the agent `Role` (PO/WORKER) — this is
 *  the HUMAN authZ layer. MEMBER is the default for any identity with no explicit assignment. */
enum class AuthRole { OPERATOR, MEMBER }

/**
 * CYP-178 / P1 — the platform's authorization store seam: a Kratos identity-id → [AuthRole] map with
 * a race-safe first-identity-bootstraps-OPERATOR grant (the ratified Middleway). Production is
 * [SqliteRoleStore] (durable, DB-enforced single OPERATOR); [InMemoryRoleStore] is the process-local
 * convenience default for the token-only [AuthDeps] path (the operator-token path never touches it).
 */
interface RoleStore {
    /** The role of [identityId] — the assigned role, or [AuthRole.MEMBER] by default (never null). */
    suspend fun roleOf(identityId: String): AuthRole

    /** Idempotently assign [identityId] a role and return it: OPERATOR iff none exists yet, else MEMBER. */
    suspend fun ensureAssigned(identityId: String, nowMs: Long): AuthRole
}

/**
 * A process-local [RoleStore] with the same first-identity-bootstraps-OPERATOR semantics as
 * [SqliteRoleStore], for the token-only [AuthDeps] convenience path + focused tests. Not durable and not
 * the production authZ store — the durable, DB-single-OPERATOR guarantee lives in [SqliteRoleStore].
 */
class InMemoryRoleStore : RoleStore {
    private val mutex = Mutex()
    private val assignments = HashMap<String, AuthRole>()

    override suspend fun roleOf(identityId: String): AuthRole =
        mutex.withLock { assignments[identityId] ?: AuthRole.MEMBER }

    override suspend fun ensureAssigned(identityId: String, nowMs: Long): AuthRole = mutex.withLock {
        assignments[identityId]?.let { return it }
        val role = if (assignments.none { it.value == AuthRole.OPERATOR }) AuthRole.OPERATOR else AuthRole.MEMBER
        assignments[identityId] = role
        role
    }
}

/**
 * CYP-178 / P1 — the platform's **authorization** store: a Kratos identity-id → [AuthRole] map. Kratos
 * owns identity; this owns authZ. The **first** identity to appear bootstraps **OPERATOR** (the ratified
 * Middleway); everyone else defaults MEMBER. On the established SQLite line (mirrors `SqliteEventSink`:
 * xerial jdbc, WAL, one [Connection] guarded by [mutex]).
 *
 * **RC3 — race-safe single-grant OPERATOR:** two guards, defense-in-depth: (1) a **partial UNIQUE index**
 * `WHERE role='OPERATOR'` makes a second OPERATOR row a constraint violation at the DB; (2) [ensureAssigned]
 * inserts OPERATOR **atomically** only `WHERE NOT EXISTS` an OPERATOR already, else MEMBER — under the
 * mutex the two are serialized, so N concurrent first-callers ⇒ **exactly one** OPERATOR.
 */
class SqliteRoleStore(
    dbPath: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RoleStore, AutoCloseable {
    private val log = LoggerFactory.getLogger("auth.rolestore")
    private val mutex = Mutex()
    private val conn: Connection

    init {
        dbPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS role_assignments (" +
                    "identity_id TEXT PRIMARY KEY, role TEXT NOT NULL, granted_at INTEGER NOT NULL)",
            )
            // RC3: at most ONE operator, enforced by the DB itself (defense-in-depth under the mutex).
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_single_operator ON role_assignments(role) WHERE role='OPERATOR'")
        }
    }

    /** The role of [identityId] — the assigned role, or [AuthRole.MEMBER] by default (never null). */
    override suspend fun roleOf(identityId: String): AuthRole = withContext(io) {
        mutex.withLock {
            conn.prepareStatement("SELECT role FROM role_assignments WHERE identity_id=?").use { ps ->
                ps.setString(1, identityId)
                ps.executeQuery().use { rs -> if (rs.next()) AuthRole.valueOf(rs.getString(1)) else AuthRole.MEMBER }
            }
        }
    }

    /**
     * Idempotently assign [identityId] a role and return it. If it already has one, return that. Otherwise
     * grant **OPERATOR iff none exists yet** (the Middleway bootstrap), else **MEMBER** — race-safe (§class).
     */
    override suspend fun ensureAssigned(identityId: String, nowMs: Long): AuthRole = withContext(io) {
        mutex.withLock {
            // Idempotent: an existing assignment wins.
            conn.prepareStatement("SELECT role FROM role_assignments WHERE identity_id=?").use { ps ->
                ps.setString(1, identityId)
                ps.executeQuery().use { rs -> if (rs.next()) return@withContext AuthRole.valueOf(rs.getString(1)) }
            }
            // Try OPERATOR only if none exists yet (atomic; the partial-unique index is the backstop).
            val grantedOperator = conn.prepareStatement(
                "INSERT INTO role_assignments(identity_id, role, granted_at) " +
                    "SELECT ?, 'OPERATOR', ? WHERE NOT EXISTS (SELECT 1 FROM role_assignments WHERE role='OPERATOR')",
            ).use { ps ->
                ps.setString(1, identityId); ps.setLong(2, nowMs); ps.executeUpdate() == 1
            }
            if (grantedOperator) {
                log.info("bootstrapped OPERATOR for the first identity")
                return@withContext AuthRole.OPERATOR
            }
            conn.prepareStatement("INSERT INTO role_assignments(identity_id, role, granted_at) VALUES (?, 'MEMBER', ?)").use { ps ->
                ps.setString(1, identityId); ps.setLong(2, nowMs); ps.executeUpdate()
            }
            AuthRole.MEMBER
        }
    }

    override fun close() { conn.close() }
}
