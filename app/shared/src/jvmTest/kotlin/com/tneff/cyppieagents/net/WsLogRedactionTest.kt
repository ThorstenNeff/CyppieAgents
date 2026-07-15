package com.tneff.cyppieagents.net

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Security: the WS log sinks must never leak the operator/agent `?token=…` credential. The WS clients dial URLs
 * with a raw `?token=$token` (browsers can't set the WS `Authorization` header, CYP-230), so a connect/handshake
 * exception whose `.message` embeds the request URL would otherwise print the token to stdout — exactly the logs
 * the instrumented dogfood harness parses. [redactUrlSecrets] sanitizes it at the ONE log-message chokepoint.
 */
class WsLogRedactionTest {

    @Test
    fun redactUrlSecrets_replacesTokenValue_keepsShape() {
        val raw = "GET ws://127.0.0.1:8080/ws/comm?token=SECRET_OP_TOKEN&since=3 failed"
        val red = redactUrlSecrets(raw)
        assertFalse(red.contains("SECRET_OP_TOKEN"), "the operator token value must be redacted")
        assertTrue(red.contains("token=***"), "the token param is redacted to token=*** (shape preserved for debugging)")
        assertTrue(red.contains("since=3"), "non-secret params are untouched")
    }

    @Test
    fun logWsError_doesNotLeakTokenToStdout() {
        val out = ByteArrayOutputStream()
        val prev = System.out
        System.setOut(PrintStream(out))
        try {
            // A real Ktor WS-connect failure carries the request URL (with the raw ?token=) in its message.
            logWsError("comm", RuntimeException("Fail: GET ws://h/ws/comm?token=SECRET_OP_TOKEN&since=3"))
        } finally {
            System.setOut(prev)
        }
        val logged = out.toString()
        // Reddening mutation: `logWsError` prints `error.message` raw (drop the redactUrlSecrets wrap) ⇒ the token
        // reaches stdout ⇒ RED.
        assertFalse(logged.contains("SECRET_OP_TOKEN"), "logWsError must not leak the operator token to stdout")
        assertTrue(logged.contains("token=***"), "the logged message is token-redacted")
    }
}
