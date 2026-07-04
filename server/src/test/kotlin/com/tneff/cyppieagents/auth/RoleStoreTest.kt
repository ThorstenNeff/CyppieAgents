package com.tneff.cyppieagents.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-196 — explicit OPERATOR assignment (fail-closed). A verified identity is MEMBER by default; OPERATOR
 * is granted ONLY to the explicitly-pinned `bootstrapOperatorId` (deploy config), never to a random
 * "first" identity. This replaces the old first-verified-wins Middleway (a public land-grab). The RC3
 * single-slot guarantee (partial-unique index) is preserved for the pinned grant.
 */
class RoleStoreTest {

    private fun db() = Files.createTempFile("roles", ".db")
    private fun store(db: Path, pin: String? = null) = SqliteRoleStore(db, bootstrapOperatorId = pin)

    @Test
    fun pinnedIdentity_isOperator_everyOtherIsMember() = runBlocking<Unit> {
        val db = db()
        store(db, pin = "alice").use { s ->
            assertEquals(AuthRole.OPERATOR, s.ensureAssigned("alice", 1_000L), "the pinned identity is OPERATOR")
            assertEquals(AuthRole.OPERATOR, s.ensureAssigned("alice", 2_000L), "re-appearing pinned identity keeps OPERATOR (idempotent)")
            assertEquals(AuthRole.MEMBER, s.ensureAssigned("bob", 3_000L), "a non-pinned identity is MEMBER")
            assertEquals(AuthRole.MEMBER, s.roleOf("bob"))
            assertEquals(AuthRole.MEMBER, s.roleOf("nobody"), "unknown identity defaults MEMBER")
        }
        Files.deleteIfExists(db)
    }

    @Test
    fun noPin_neverGrantsOperator_evenOnEmptyStore() = runBlocking<Unit> {
        // THE fail-closed guard: with no pin, NO identity is ever OPERATOR — closes the land-grab structurally.
        val db = db()
        store(db, pin = null).use { s ->
            val roles = (1..8).map { s.ensureAssigned("id-$it", 1_000L + it) }
            assertEquals(0, roles.count { it == AuthRole.OPERATOR }, "no pin → ZERO operators, all MEMBER: $roles")
            assertEquals(8, roles.count { it == AuthRole.MEMBER })
            assertEquals(false, s.hasOperator(), "hasOperator must be false with no pin")
        }
        Files.deleteIfExists(db)
    }

    @Test
    fun nonPinnedFirstCaller_neverBecomesOperator() = runBlocking<Unit> {
        // Even as the FIRST-ever caller on an empty store, a non-pinned identity is MEMBER (no first-verified grab).
        val db = db()
        store(db, pin = "alice").use { s ->
            assertEquals(AuthRole.MEMBER, s.ensureAssigned("bob-first", 1_000L), "first caller != pin -> MEMBER, NOT OPERATOR")
            assertEquals(false, s.hasOperator(), "no OPERATOR exists until the pinned identity appears")
            assertEquals(AuthRole.OPERATOR, s.ensureAssigned("alice", 2_000L), "the pinned identity, appearing later, is OPERATOR")
        }
        Files.deleteIfExists(db)
    }

    @Test
    fun concurrentCallers_onlyPinnedIsOperator_exactlyOne() = runBlocking<Unit> {
        // RC3 preserved: N racers incl. the pinned id -> exactly ONE OPERATOR (the pinned), the rest MEMBER.
        val db = db()
        store(db, pin = "id-7").use { s ->
            val roles = (1..32).map { i ->
                async(Dispatchers.Default) { s.ensureAssigned("id-$i", nowMs = 1_000L + i) }
            }.awaitAll()
            assertEquals(1, roles.count { it == AuthRole.OPERATOR }, "exactly one OPERATOR (the pinned id-7)")
            assertEquals(AuthRole.OPERATOR, s.roleOf("id-7"), "and it is the pinned identity")
            assertEquals(31, roles.count { it == AuthRole.MEMBER })
        }
        Files.deleteIfExists(db)
    }

    @Test
    fun pinnedMemberRow_isUpgradedToOperator_theBootstrapFlow() = runBlocking<Unit> {
        // The bootstrap flow: the intended operator logs in BEFORE being pinned (-> MEMBER), deploy pins them,
        // restart -> the SAME db reopened WITH the pin upgrades their MEMBER row to OPERATOR.
        val db = db()
        store(db, pin = null).use { unpinned ->
            assertEquals(AuthRole.MEMBER, unpinned.ensureAssigned("alice", 1_000L), "pre-pin login -> MEMBER")
        }
        store(db, pin = "alice").use { pinned ->
            assertEquals(AuthRole.OPERATOR, pinned.ensureAssigned("alice", 2_000L), "reopening with the pin upgrades MEMBER -> OPERATOR")
            assertEquals(AuthRole.OPERATOR, pinned.roleOf("alice"))
            assertEquals(true, pinned.hasOperator())
        }
        Files.deleteIfExists(db)
    }
}
