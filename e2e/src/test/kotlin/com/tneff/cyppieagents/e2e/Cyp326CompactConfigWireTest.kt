package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.compact.CompactException
import com.tneff.cyppieagents.compact.CompactHttpRepository
import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * CYP-326 INTEGRATION gate (my QA tooth) — the **real client [CompactHttpRepository]** driven against the **real
 * server `compactRoutes`** (`GET /api/compact/status` read-tier, `POST /api/compact/config` OPERATOR) on [e2ePlatform].
 *
 * Anti-fake-shape (the PO's named real-seam, same class as CYP-324): dev's `CompactHttpRepositoryTest` runs the real
 * repo against a HAND-ROLLED embedded route; the `MemberTier403MatrixTest` covers the auth matrix on the real route —
 * but neither stitches the real repo↔real route end-to-end. This closes it over the real HTTP seam:
 *  - **Z1 read + round-trip:** operator `setConfig(allowed=false, threshold=700K)` on the REAL route → the real store
 *    persists → a fresh `getStatus()` decodes the REAL `CompactStatus` reflecting it (allowed=false, threshold=700K).
 *  - **Z2 operator gate:** a read-tier (agent) token's `setConfig` → 403 → `CompactException` (write stays operator-only).
 */
class Cyp326CompactConfigWireTest {

    private fun boot() = listOf(
        SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))),
    )

    private fun httpClient() = HttpClient(CIO)

    private fun repo(p: E2ePlatform, client: HttpClient, token: String) =
        CompactHttpRepository(client, p.baseUrl, token)

    /** Z1 — a real operator write round-trips through the real route + store and the real read decodes it. */
    @Test
    fun realRepo_operatorSetConfig_roundTripsAndReadsBack_overRealRoute(): Unit = runBlocking {
        e2ePlatform(boot()).use { p ->
            val client = httpClient()
            try {
                val op = repo(p, client, E2ePlatform.OPERATOR_TOKEN)
                val applied = withTimeout(10_000) { op.setConfig(CompactConfig(allowed = false, thresholdTokens = 700_000)) }
                assertFalse(applied.allowed, "operator write reflected in the returned real CompactStatus")
                assertEquals(700_000, applied.thresholdTokens)
                // fresh read decodes the persisted config off the real route (not the returned value)
                val got = withTimeout(10_000) { repo(p, client, E2ePlatform.agentToken("backend")).getStatus() }
                assertFalse(got.allowed, "read-tier GET decodes the real persisted CompactStatus")
                assertEquals(700_000, got.thresholdTokens)
            } finally { client.close() }
        }
    }

    /** Z2 — the config WRITE is operator-only: a read-tier (agent) token gets 403 → CompactException. */
    @Test
    fun realRepo_memberSetConfig_isForbidden_overRealRoute(): Unit = runBlocking {
        e2ePlatform(boot()).use { p ->
            val client = httpClient()
            try {
                val member = repo(p, client, E2ePlatform.agentToken("backend"))
                assertFailsWith<CompactException> {
                    withTimeout(10_000) { member.setConfig(CompactConfig(allowed = true, thresholdTokens = 500_000)) }
                }
            } finally { client.close() }
        }
    }
}
