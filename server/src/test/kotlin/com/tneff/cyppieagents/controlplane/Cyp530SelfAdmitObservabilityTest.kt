package com.tneff.cyppieagents.controlplane

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.crypto.HubIdentity
import com.tneff.cyppieagents.crypto.HubIdentityProvisioner
import com.tneff.cyppieagents.crypto.MasterKeyCustody
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.crypto.SecretStore
import com.tneff.cyppieagents.crypto.SqliteSecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-530 (S-J observability) — hub self-admit is fail-LOUD: a PARTIAL misconfig WARNs naming the missing env (so the
 * deploy env-check sees WHY `GET /api/cp/hubs` is empty), and a successful admit INFO-logs (the positive signal the
 * deploy e2e self-verify keys on). The silent `null`-factory dead-end (indistinguishable from "not yet admitted") is
 * exactly what this closes. The naming is single-sourced through [hubAdmissionInertReasons] (no drift with the gate).
 */
class Cyp530SelfAdmitObservabilityTest {

    private val fullEnv = mapOf(
        "CYPPIE_CP_URL" to "https://cp.test",
        "CYPPIE_CP_OPERATOR_TOKEN" to "op-bearer",
        "CYPPIE_OPERATOR_ID" to "op-1",
    )
    private val nonceB64 = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun withCustody(block: (HubIdentity, SecretStore, File) -> Unit) {
        val dir = Files.createTempDirectory("cyp530-obs")
        val file = dir.resolve(".cyppie/hub-identity.json").toFile()
        SqliteSecretStore(dir.resolve("s.db"), MasterKeyCustody { SecretCipherFactory.newBoxKeyset() }).use { store ->
            block(HubIdentityProvisioner(store, file.toPath()).ensure(), store, file)
        }
    }

    private fun captureSelfAdmit(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger("cyp530.selfadmit") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val prev = logger.level
        logger.level = Level.TRACE
        logger.addAppender(appender)
        try { block() } finally { logger.detachAppender(appender); logger.level = prev }
        return appender.list.toList()
    }

    // ── (1) the reasons helper NAMES exactly the missing prerequisites (the single-sourced gate) ──

    @Test fun reasons_partial_namesExactlyTheMissingEnv() = withCustody { id, store, file ->
        assertEquals(emptyList(), hubAdmissionInertReasons(id, store, file, fullEnv::get), "full env + custody → ready (no reasons)")
        assertEquals(
            listOf("CYPPIE_OPERATOR_ID"),
            hubAdmissionInertReasons(id, store, file, (fullEnv - "CYPPIE_OPERATOR_ID")::get),
            "a partial config names EXACTLY the one missing env (MUT: drop that check → empty list → RED)",
        )
    }

    @Test fun reasons_noCustody_namesMasterKey() = withCustody { _, _, _ ->
        val reasons = hubAdmissionInertReasons(null, null, null, fullEnv::get)
        assertTrue(reasons.any { it.contains("CYPPIE_MASTER_KEY") }, "absent custody → the custody prerequisite is named")
    }

    // ── (2) fail-LOUD emission: partial → WARN naming the env; all-missing → no WARN (quiet local-only) ──

    @Test fun build_partialEnv_warnsNamingMissingEnv() = withCustody { id, store, file ->
        val logs = captureSelfAdmit {
            assertNull(buildHubAdmission(id, store, file, "hub", 8787, env = (fullEnv - "CYPPIE_CP_OPERATOR_TOKEN")::get), "partial → INERT (null)")
        }
        val warn = logs.singleOrNull { it.level == Level.WARN }
        assertNotNull(warn, "a partial self-admit config WARNs (MUT: drop the WARN / make it DEBUG → no WARN captured → RED)")
        assertTrue(warn.formattedMessage.contains("CYPPIE_CP_OPERATOR_TOKEN"), "the WARN NAMES the missing env")
        assertTrue(warn.formattedMessage.contains("GET /api/cp/hubs"), "the WARN ties it to the empty discovery list")
    }

    @Test fun build_allMissing_isQuiet_noWarn() {
        val logs = captureSelfAdmit {
            assertNull(buildHubAdmission(null, null, null, "hub", 8787, env = { null }), "nothing configured → INERT (null)")
        }
        assertTrue(logs.none { it.level == Level.WARN }, "self-admit simply not configured (local-only) is NOT a warning — no noise")
    }

    // ── (3) positive confirmation: a successful admit INFO-logs (owner + hubId), the deploy self-verify signal ──

    @Test fun successfulAdmit_infoLogsOwnerAndHubId() = withCustody { id, store, file ->
        val hubId = "hub_selfadmit_ok"
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/cp/challenge") -> respond(CommJson.encodeToString(HubChallenge(nonceB64)), HttpStatusCode.OK, jsonHeaders())
                request.url.encodedPath.endsWith("/cp/admit") -> respond(CommJson.encodeToString(HubAdmissionResult(admitted = true, hubId = hubId)), HttpStatusCode.OK, jsonHeaders())
                else -> respond("nope", HttpStatusCode.NotFound)
            }
        }
        val runnable = buildHubAdmission(id, store, file, "hub", 8787, env = fullEnv::get,
            httpClientFactory = { HttpClient(engine) { install(ClientContentNegotiation) { json(CommJson) } } })
        assertNotNull(runnable, "full env + custody → a live self-admit runnable")
        val logs = captureSelfAdmit { runBlocking { assertTrue(runnable.invoke().admitted, "the mock CP admits") } }
        val info = logs.singleOrNull { it.level == Level.INFO }
        assertNotNull(info, "a successful admit INFO-logs (MUT: drop the INFO → no positive signal → RED)")
        assertTrue(info.formattedMessage.contains(hubId), "the INFO names the hubId (the discoverable hub)")
        assertTrue(info.formattedMessage.contains("op-1"), "the INFO names the owner operator")
    }
}
