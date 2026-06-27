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
}
