package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.CommJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * CYP-146 / E1.7 — generates the per-agent `--mcp-config` that exposes the in-process **Hub MCP server**
 * (`/mcp/hub`) to a Connector-A (stream-json) spawn, so the agent has a callable `hub_send` tool (the
 * emission half the RB1 gap was missing). The CLI surfaces the tool as `mcp__hub__hub_send`.
 *
 * **F1 ⭐ SECURITY:** the config carries the agent's **bearer token** → it must NEVER enter the tracked
 * worktree (a commit/push would leak the token into the shared remote). It is written **out-of-repo,
 * under [mcpConfigDir] (gitRoot-adjacent), 0600** — exactly like `project-config.json`/`projects.json` —
 * and handed to the spawn by **absolute path** via `--mcp-config`. Only `CLAUDE.md` belongs in the tree
 * (auto-discovery); the mcp-config is passed explicitly, so it stays outside.
 *
 * **F3:** [hubUrl] is **localhost-bound** (`http://127.0.0.1:<port>/mcp/hub`), not the open net; the token
 * is the only auth and the handler binds the agent identity server-side (the agent can't claim another).
 */
class HubMcpConfigWriter(
    /** Out-of-repo dir under the gitRoot (NOT a worktree), e.g. `<gitRoot>/mcp`. */
    private val mcpConfigDir: File,
    /** The in-process hub MCP endpoint, localhost-bound: `http://127.0.0.1:<port>/mcp/hub`. */
    private val hubUrl: String,
) {
    private val log = LoggerFactory.getLogger("connector.mcpconfig")

    /** Server name in the mcp-config → the CLI namespaces the tool as `mcp__<SERVER_NAME>__hub_send`. */
    companion object {
        const val SERVER_NAME = "hub"
        const val SEND_TOOL = "hub_send"
        /** The PREFIXED tool name as it appears in the stream-json tool_use (F2): `mcp__hub__hub_send`. */
        const val PREFIXED_SEND_TOOL = "mcp__${SERVER_NAME}__$SEND_TOOL"
    }

    /**
     * Write [agentId]'s mcp-config (0600, out-of-repo) carrying [agentToken] and return its **absolute**
     * path for `--mcp-config`. 0600-FIRST: the file is created and locked down BEFORE the token is written.
     */
    fun writeFor(agentId: String, agentToken: String): File {
        mcpConfigDir.mkdirs()
        val f = File(mcpConfigDir, "$agentId.mcp.json")
        if (!f.exists()) f.createNewFile()
        lockDown(f) // 0600 BEFORE the secret hits disk
        f.writeText(configJson(agentToken))
        lockDown(f) // re-assert after write
        return f.absoluteFile
    }

    /** kotlinx-encoded so the token is safely escaped (never string-concatenated into JSON). */
    private fun configJson(agentToken: String): String {
        val cfg: JsonObject = buildJsonObject {
            putJsonObject("mcpServers") {
                putJsonObject(SERVER_NAME) {
                    put("type", "http")
                    put("url", hubUrl)
                    putJsonObject("headers") {
                        put("Authorization", "Bearer $agentToken")
                    }
                }
            }
        }
        return CommJson.encodeToString(JsonObject.serializer(), cfg)
    }

    /** Owner-only rw (0600) — the file holds the agent token. POSIX where available; best-effort otherwise. */
    private fun lockDown(f: File) {
        runCatching {
            Files.setPosixFilePermissions(
                f.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }.onFailure { log.debug("could not set 0600 on {} (non-POSIX fs?)", f) }
    }
}
