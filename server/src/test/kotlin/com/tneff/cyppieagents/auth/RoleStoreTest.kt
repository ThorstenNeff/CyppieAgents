package com.tneff.cyppieagents.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-178 / P1 — RC3: the race-safe Middleway bootstrap. N concurrent first-callers ⇒ **exactly one**
 * OPERATOR (the partial-unique index + atomic INSERT-WHERE-NOT-EXISTS under the mutex); the rest MEMBER.
 */
class RoleStoreTest {

    private val db = Files.createTempFile("roles", ".db")
    private val store = SqliteRoleStore(db)
    @AfterTest fun tearDown() { store.close(); Files.deleteIfExists(db) }

    @Test
    fun nConcurrentBootstraps_grantExactlyOneOperator() = runBlocking {
        val n = 32
        val roles = (1..n).map { i ->
            async(Dispatchers.Default) { store.ensureAssigned("id-$i", nowMs = 1_000L + i) }
        }.awaitAll()
        assertEquals(1, roles.count { it == AuthRole.OPERATOR }, "RC3: exactly one OPERATOR across $n racers")
        assertEquals(n - 1, roles.count { it == AuthRole.MEMBER })
    }

    @Test
    fun assignment_isIdempotent_sameIdentityKeepsItsRole() = runBlocking {
        val first = store.ensureAssigned("alice", 1_000L) // OPERATOR (the first)
        assertEquals(AuthRole.OPERATOR, first)
        assertEquals(AuthRole.OPERATOR, store.ensureAssigned("alice", 2_000L), "re-appearing identity keeps its role")
        assertEquals(AuthRole.MEMBER, store.ensureAssigned("bob", 3_000L), "a later identity is MEMBER")
        assertEquals(AuthRole.OPERATOR, store.roleOf("alice"))
        assertEquals(AuthRole.MEMBER, store.roleOf("bob"))
        assertEquals(AuthRole.MEMBER, store.roleOf("nobody"), "unknown identity defaults MEMBER")
    }
}
