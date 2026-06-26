package com.tneff.cyppieagents

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/** CYP-8 (S0 AC): liveness is GET /api/health → "ok" (no auth). */
class HealthTest {

    @Test
    fun health_returnsOk() = testApplication {
        application { module() }
        val res = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("ok", res.bodyAsText())
    }
}
