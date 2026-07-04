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
/** A single role assignment row — CYP-186 BE3a, for the OPERATOR-only workspace roster. */
data class RoleAssignment(val identityId: String, val role: AuthRole, val grantedAtMs: Long)

interface RoleStore {
    /** The role of [identityId] — the assigned role, or [AuthRole.MEMBER] by default (never null). */
    suspend fun roleOf(identityId: String): AuthRole

    /**
     * Idempotently ensure [identityId] has a role and return it. **CYP-196 (explicit-assignment hardening):**
     * a verified identity is **MEMBER by default** — there is **no implicit auto-OPERATOR grant** (the old
     * "first identity bootstraps OPERATOR" was a latent land-grab on the public app). OPERATOR is granted
     * **only** to the store's explicitly-pinned `bootstrapOperatorId` (deploy-owned config), and only while
     * the single OPERATOR slot is free — upgrading that identity from a prior MEMBER row. No pin → **no
     * identity ever becomes OPERATOR** (fail-closed). The static OPERATOR_TOKEN stays the break-glass
     * machine-operator, independent of this store.
     */
    suspend fun ensureAssigned(identityId: String, nowMs: Long): AuthRole

    /** All assignments (identityId → role) — CYP-186 BE3a workspace roster (OPERATOR-only surface). */
    suspend fun list(): List<RoleAssignment>

    /** Does a role-OPERATOR already exist? — CYP-186 C.2 never-lock-out guard for the token kill-switch. */
    suspend fun hasOperator(): Boolean
}

/**
 * A process-local [RoleStore] with the same explicit-assignment semantics as [SqliteRoleStore] (CYP-196),
 * for the token-only [AuthDeps] convenience path + focused tests. Not durable and not the production authZ
 * store — the durable, DB-single-OPERATOR guarantee lives in [SqliteRoleStore]. [bootstrapOperatorId] is the
 * explicitly-pinned OPERATOR identity (null = no OPERATOR is ever auto-granted; fail-closed default).
 */
class InMemoryRoleStore(private val bootstrapOperatorId: String? = null) : RoleStore {
    private val mutex = Mutex()
    private val assignments = HashMap<String, AuthRole>()

    override suspend fun roleOf(identityId: String): AuthRole =
        mutex.withLock { assignments[identityId] ?: AuthRole.MEMBER }

    override suspend fun ensureAssigned(identityId: String, nowMs: Long): AuthRole = mutex.withLock {
        val existing = assignments[identityId]
        val slotFree = assignments.none { it.value == AuthRole.OPERATOR }
        val pinned = bootstrapOperatorId != null && identityId == bootstrapOperatorId
        // CYP-196: OPERATOR ONLY for the pinned identity while the single slot is free (upgrades a prior
        // MEMBER); everyone else is MEMBER. No implicit auto-grant → a random verified identity never grabs
        // OPERATOR, even on an empty store.
        val role = when {
            pinned && slotFree -> AuthRole.OPERATOR
            existing != null -> existing
            else -> AuthRole.MEMBER
        }
        assignments[identityId] = role
        role
    }

    override suspend fun list(): List<RoleAssignment> =
        mutex.withLock { assignments.map { RoleAssignment(it.key, it.value, 0L) } }

    override suspend fun hasOperator(): Boolean =
        mutex.withLock { assignments.any { it.value == AuthRole.OPERATOR } }
}

/**
 * CYP-178 / P1 — the platform's **authorization** store: a Kratos identity-id → [AuthRole] map. Kratos
 * owns identity; this owns authZ. On the established SQLite line (mirrors `SqliteEventSink`: xerial jdbc,
 * WAL, one [Connection] guarded by [mutex]).
 *
 * **CYP-196 — explicit OPERATOR assignment (fail-closed).** A verified identity defaults to **MEMBER**;
 * OPERATOR is granted **only** to the explicitly-pinned [bootstrapOperatorId] (deploy-owned config), and
 * only while the single slot is free (upgrading it from a prior MEMBER row). `null` pin ⇒ **no identity is
 * ever auto-granted OPERATOR**. This replaces the old "first identity bootstraps OPERATOR" Middleway, which
 * was a latent land-grab once the app is publicly reachable with real verified identities.
 *
 * **RC3 — race-safe single OPERATOR:** two guards, defense-in-depth: (1) a **partial UNIQUE index**
 * `WHERE role='OPERATOR'` makes a second OPERATOR row a DB constraint violation; (2) [ensureAssigned]
 * upgrades the pinned id to OPERATOR **only** `WHERE NOT EXISTS` another OPERATOR — under the mutex the two
 * are serialized, so the slot holds **exactly one** OPERATOR.
 */
class SqliteRoleStore(
    dbPath: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val bootstrapOperatorId: String? = null,
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
     * Idempotently ensure [identityId] has a role and return it (CYP-196). A verified identity gets a
     * **MEMBER** row by default (no implicit auto-OPERATOR). If it is the pinned [bootstrapOperatorId], it is
     * upgraded to **OPERATOR** — but only while the single slot is free (`WHERE NOT EXISTS` another OPERATOR;
     * the partial-unique index is the backstop). No pin ⇒ never OPERATOR (fail-closed).
     */
    override suspend fun ensureAssigned(identityId: String, nowMs: Long): AuthRole = withContext(io) {
        mutex.withLock {
            val pinned = bootstrapOperatorId != null && identityId == bootstrapOperatorId
            // Default: ensure a MEMBER row (idempotent; never overwrites an existing OPERATOR row).
            conn.prepareStatement(
                "INSERT OR IGNORE INTO role_assignments(identity_id, role, granted_at) VALUES (?, 'MEMBER', ?)",
            ).use { ps -> ps.setString(1, identityId); ps.setLong(2, nowMs); ps.executeUpdate() }
            if (pinned) {
                // Upgrade the PINNED identity to OPERATOR iff the single slot is free (no OTHER operator).
                // The NOT EXISTS makes it a no-op when a stale/other OPERATOR still holds the slot; the
                // partial-unique index is the race backstop. This also upgrades a prior MEMBER row for the
                // pin (the bootstrap flow: the pinned human logs in as MEMBER, deploy pins them → OPERATOR).
                val upgraded = conn.prepareStatement(
                    "UPDATE role_assignments SET role='OPERATOR', granted_at=? WHERE identity_id=? AND role<>'OPERATOR' " +
                        "AND NOT EXISTS (SELECT 1 FROM role_assignments WHERE role='OPERATOR' AND identity_id<>?)",
                ).use { ps -> ps.setLong(1, nowMs); ps.setString(2, identityId); ps.setString(3, identityId); ps.executeUpdate() == 1 }
                if (upgraded) log.info("assigned OPERATOR to the pinned bootstrap identity")
            }
            // Read back the effective role.
            conn.prepareStatement("SELECT role FROM role_assignments WHERE identity_id=?").use { ps ->
                ps.setString(1, identityId)
                ps.executeQuery().use { rs -> if (rs.next()) AuthRole.valueOf(rs.getString(1)) else AuthRole.MEMBER }
            }
        }
    }

    override suspend fun list(): List<RoleAssignment> = withContext(io) {
        mutex.withLock {
            conn.prepareStatement("SELECT identity_id, role, granted_at FROM role_assignments ORDER BY granted_at").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(RoleAssignment(rs.getString(1), AuthRole.valueOf(rs.getString(2)), rs.getLong(3)))
                        }
                    }
                }
            }
        }
    }

    override suspend fun hasOperator(): Boolean = withContext(io) {
        mutex.withLock {
            conn.prepareStatement("SELECT 1 FROM role_assignments WHERE role='OPERATOR' LIMIT 1").use { ps ->
                ps.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    override fun close() { conn.close() }
}
