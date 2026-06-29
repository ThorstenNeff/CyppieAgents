package com.tneff.cyppieagents

import com.tneff.cyppieagents.connector.HubMcpConfigWriter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-146 / F1 ⭐ SECURITY — the per-agent mcp-config carries the agent token, so it must be written
 * **out-of-repo** (under the supplied dir, NOT a worktree) at **0600**, and pass the localhost hub URL.
 * A commit/push of this file would leak the token into the shared remote.
 */
class HubMcpConfigWriterTest {

    private val writer = HubMcpConfigWriter(
        mcpConfigDir = Files.createTempDirectory("mcp-cfg").toFile(),
        hubUrl = "http://127.0.0.1:8787/mcp/hub",
    )

    @Test
    fun writesTokenConfigOutOfRepoAt0600() {
        val f = writer.writeFor("po", "tok-secret-123")

        // Out-of-repo: absolute path, under the supplied gitRoot-adjacent dir (never the tracked tree).
        assertTrue(f.isAbsolute, "absolute path for --mcp-config")
        assertTrue(f.name.contains("po"), "per-agent file")

        // 0600 (POSIX where available).
        val perms = Files.getPosixFilePermissions(f.toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms, "owner-only rw")

        // Content: the token is the auth, the url is localhost, the structure is a valid http MCP server.
        val txt = f.readText()
        assertTrue(txt.contains("tok-secret-123"), "token present (it is the bearer auth)")
        val hub = CommJson.decodeFromString<JsonObject>(txt)["mcpServers"]!!.jsonObject["hub"]!!.jsonObject
        assertEquals("http", hub["type"]!!.jsonPrimitive.content)
        assertTrue(hub["url"]!!.jsonPrimitive.content.startsWith("http://127.0.0.1"), "localhost-bound (F3)")
        assertEquals("Bearer tok-secret-123", hub["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content)
    }

    @Test
    fun prefixedToolNameMatchesTheMcpConvention() {
        // F2 anchor: the CLI namespaces the tool as mcp__hub__hub_send (prefixed), NOT bare hub_send.
        assertEquals("mcp__hub__hub_send", HubMcpConfigWriter.PREFIXED_SEND_TOOL)
    }
}
