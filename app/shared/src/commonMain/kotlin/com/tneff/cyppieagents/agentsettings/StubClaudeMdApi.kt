package com.tneff.cyppieagents.agentsettings

/**
 * CYP-310 — an in-memory [ClaudeMdApi] stub: a settled-EMPTY file (no CLAUDE.md yet), writes echo the content with
 * `exists=true` and a bumped version. Used as the VM's default (so non-live tests/screens don't need the HTTP port)
 * and as a hermetic fake; production wires [ClaudeMdHttpApi] at the shell. NOT fail-closed by itself — the VM's
 * fail-closed handling (a throwing `get()` → error state) is exercised by test fakes, not this happy-path stub.
 */
class StubClaudeMdApi(initial: String = "") : ClaudeMdApi {
    private var content: String = initial
    private var exists: Boolean = initial.isNotEmpty()
    private var version: Int = 0

    override suspend fun get(agentId: String): ClaudeMdView =
        ClaudeMdView(agentId = agentId, content = content, exists = exists, version = "v$version")

    override suspend fun update(agentId: String, content: String, expectedVersion: String): ClaudeMdView {
        this.content = content
        this.exists = true
        this.version += 1
        return ClaudeMdView(agentId = agentId, content = content, exists = true, version = "v$version")
    }
}
