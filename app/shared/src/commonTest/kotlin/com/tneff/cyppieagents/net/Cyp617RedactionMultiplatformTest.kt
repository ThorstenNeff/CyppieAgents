package com.tneff.cyppieagents.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-617 — [redactUrlSecrets] must be MULTIPLATFORM-safe. It was written with a `(?i)` inline flag, which is a
 * JVM-only regex construct: constructing the Regex on Kotlin/JS + Kotlin/Wasm throws `SyntaxError` ("Invalid group"),
 * so EVERY call (logWsError/logWsPool/logMux) blew up on the web/mux path — invisible to the JVM-only redaction test.
 * This test lives in commonTest so it runs on JVM **and** JS **and** Wasm: merely calling [redactUrlSecrets] here
 * would throw on JS/Wasm under the old `(?i)`, so a green run across targets IS the fix's proof.
 *
 * Reddening mutation: revert the pattern to `Regex("(?i)…")` ⇒ these cases throw on the JS/Wasm browser runs ⇒ RED.
 */
class Cyp617RedactionMultiplatformTest {

    @Test
    fun redactsTokenValue_keepsShapeAndOtherParams() {
        val red = redactUrlSecrets("GET ws://127.0.0.1:8080/ws/comm?token=SECRET_OP_TOKEN&since=3 failed")
        assertFalse(red.contains("SECRET_OP_TOKEN"), "the operator token value must be redacted")
        assertTrue(red.contains("token=***"), "the token param is redacted to token=*** (shape preserved)")
        assertTrue(red.contains("since=3"), "non-secret params are untouched")
    }

    @Test
    fun redactionIsCaseInsensitive_viaIgnoreCaseOption_notInlineFlag() {
        // The whole point of CYP-617: case-insensitivity must come from RegexOption.IGNORE_CASE (multiplatform), and it
        // must still actually be case-insensitive — a mixed/upper-case param name is still a leaking credential.
        assertTrue(redactUrlSecrets("x?Token=SEKRET").contains("Token=***"), "?Token= (mixed case) is redacted")
        assertTrue(redactUrlSecrets("x?ACCESS_TOKEN=SEKRET").contains("ACCESS_TOKEN=***"), "?ACCESS_TOKEN= (upper) is redacted")
        assertFalse(redactUrlSecrets("x?TOKEN=SEKRET").contains("SEKRET"), "?TOKEN= (upper) still redacts the value")
    }

    @Test
    fun nullMessage_isEmpty_noThrow() {
        assertEquals("", redactUrlSecrets(null))
    }

    @Test
    fun logSinks_doNotThrow_onAnyTarget() {
        // Smoke: the sinks construct/apply the shared regex — under the old `(?i)` these threw at Regex construction on
        // JS/Wasm. They must run (println) without throwing on every target. (Output-capture is JVM-only; not asserted here.)
        logWsPool("resolve", "held=21 inUse=21 id=abc?token=SEKRET")
        logMux("hello", "G7 peer hello verified carrier=deadbeef mode=0 version=1")
        logWsTeardown("transport", "closed ws://h/ws/agent?token=SEKRET")
    }
}
