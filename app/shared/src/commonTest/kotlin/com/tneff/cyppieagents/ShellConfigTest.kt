package com.tneff.cyppieagents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShellConfigTest {

    @Test
    fun dev_targetsCyp24Port8787() {
        val config = ShellConfig.dev()
        assertEquals("ws://localhost:8787", config.hubWsBaseUrl)
        assertEquals("http://localhost:8787", config.hubHttpBaseUrl)
    }

    @Test
    fun dev_agentToken_matchesServerRegistryFallback() {
        assertEquals("dev-token-frontend", ShellConfig.dev().agentToken("frontend"))
        assertEquals("dev-token-po", ShellConfig.dev().agentToken("po"))
    }

    @Test
    fun dev_neverBakesOperatorToken() {
        assertNull(ShellConfig.dev().operatorToken)
    }

    @Test
    fun customHostPort_isHonored() {
        val config = ShellConfig.dev(host = "example.test", port = 9000)
        assertEquals("ws://example.test:9000", config.hubWsBaseUrl)
        assertEquals("http://example.test:9000", config.hubHttpBaseUrl)
    }

    // --- CYP-188: same-origin base resolution (the web `defaultShellConfig()` seam, hermetic) ---

    @Test
    fun forOrigin_https_derivesWss_sameOrigin() {
        // Deployed SPA: https page origin → same-origin http base + wss WS base, so the ory_kratos_session cookie
        // flows on the shell's reads/sockets (P2a browser path; CORS allowCredentials=false → cross-origin drops it).
        val c = ShellConfig.forOrigin("https://app.example.com")
        assertEquals("https://app.example.com", c.hubHttpBaseUrl)
        assertEquals("wss://app.example.com", c.hubWsBaseUrl)
    }

    @Test
    fun forOrigin_http_derivesWs() {
        val c = ShellConfig.forOrigin("http://localhost:8080")
        assertEquals("http://localhost:8080", c.hubHttpBaseUrl)
        assertEquals("ws://localhost:8080", c.hubWsBaseUrl)
    }

    @Test
    fun forOrigin_trailingSlash_trimmed() {
        assertEquals("https://x.example", ShellConfig.forOrigin("https://x.example/").hubHttpBaseUrl)
        assertEquals("wss://x.example", ShellConfig.forOrigin("https://x.example/").hubWsBaseUrl)
    }

    @Test
    fun forOrigin_publicBuild_isTokenless_failClosed() {
        // ⭐ CYP-188 item 2 — the PUBLIC build injects no operator global → null token → fail-closed participant
        // view. A mutation that defaulted a token here (or baked one) reddens. The operator SERVE passes the
        // host-injected token instead; it is NEVER a compiled-in literal (CYP-152).
        assertNull(ShellConfig.forOrigin("https://app.example.com").operatorToken)
        assertNull(ShellConfig.forOrigin("https://app.example.com", operatorToken = null).operatorToken)
        assertEquals("op-tok", ShellConfig.forOrigin("https://app.example.com", "op-tok").operatorToken)
    }
}
