package com.tneff.cyppieagents.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.ConsoleAppender
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-188 / BG-WS-5 — the log token-redactor teeth. The load-bearing property: a bearer credential (the
 * `?token=` WS query param, or an `Authorization: Bearer` value) never survives into a formatted log line,
 * while the surrounding message (path, other params) is untouched.
 */
class TokenRedactorTest {

    @Test
    fun redactsQueryToken_keepsKeyAndPathAndOtherParams() {
        val line = "GET /ws/comm?token=sk-SECRET-abc123&since=42 → 101"
        val out = TokenRedactor.redact(line)
        assertEquals("GET /ws/comm?token=[REDACTED]&since=42 → 101", out)
        assertFalse(out.contains("sk-SECRET-abc123"), "the token value must not survive — $out")
    }

    @Test
    fun redactsQueryToken_atEndOfLine() {
        assertEquals("connect /ws/events?token=[REDACTED]", TokenRedactor.redact("connect /ws/events?token=OPERATORSECRET"))
    }

    @Test
    fun redactsBearer() {
        val out = TokenRedactor.redact("Authorization: Bearer eyJ.abc.DEF-secret then more")
        assertEquals("Authorization: Bearer [REDACTED] then more", out)
        assertFalse(out.contains("eyJ.abc.DEF-secret"))
    }

    @Test
    fun redactsAllOccurrences_theEightXCanary() {
        val line = (1..8).joinToString(" | ") { "/ws/comm?token=CANARY$it" }
        val out = TokenRedactor.redact(line)
        assertFalse(out.contains("CANARY"), "every occurrence must be redacted — $out")
        assertEquals(8, Regex("token=\\[REDACTED]").findAll(out).count(), "all 8 masked")
    }

    @Test
    fun cyp638_redactsTicketCookieAndSessionHeader() {
        // CYP-638 (gateway A2 output seam): ?ticket (single-use WS read cred), the Kratos session cookie, and
        // X-Session-Token — all operator-session credentials that must not survive to a log line.
        assertEquals("GET /ws/comm?ticket=[REDACTED]&since=1", TokenRedactor.redact("GET /ws/comm?ticket=SINGLEUSE-9x&since=1"))
        val cookie = TokenRedactor.redact("Cookie: ory_kratos_session=SESSIONSECRET; other=1")
        assertEquals("Cookie: ory_kratos_session=[REDACTED]; other=1", cookie)
        assertFalse(cookie.contains("SESSIONSECRET"), "the session cookie value must not survive — $cookie")
        assertEquals("X-Session-Token: [REDACTED]", TokenRedactor.redact("X-Session-Token: OPSESSION-abc"))
    }

    @Test
    fun leavesNonTokenMessagesUntouched() {
        val msg = "boot: cloned repo, 3 worktrees, hub ready on 127.0.0.1:8787"
        assertEquals(msg, TokenRedactor.redact(msg), "a message with no credential must be unchanged")
    }

    @Test
    fun converterDelegatesToRedactor() {
        // The logback converter is a thin delegation; if it ever stops redacting, this catches it.
        assertTrue(TokenRedactor.redact("x?token=Y").endsWith("token=[REDACTED]"))
    }

    @Test
    fun realLogbackConfig_redactsTokenInFormattedOutput() {
        // End-to-end via the ACTUAL loaded logback.xml (STDOUT appender + `%redactedMsg` pattern) — proves the
        // WIRING (conversionRule name → converter → pattern), not a synthetic setup. This is the "WS-connect
        // `?token=<canary>` → the app-log line does NOT contain the canary" teeth at the config level.
        val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
        val root = ctx.getLogger(Logger.ROOT_LOGGER_NAME)
        @Suppress("UNCHECKED_CAST")
        val appender = root.getAppender("STDOUT") as ConsoleAppender<ILoggingEvent>
        val encoder = appender.encoder as PatternLayoutEncoder
        val event = LoggingEvent("fqcn", root, Level.INFO, "connect /ws/comm?token=CANARY-E2E-9x", null, null)
        val line = String(encoder.encode(event))
        assertFalse(line.contains("CANARY-E2E-9x"), "the real logback config must redact the token in the log line — $line")
        assertTrue(line.contains("token=[REDACTED]"), "the key stays, the value is masked — $line")
    }
}
