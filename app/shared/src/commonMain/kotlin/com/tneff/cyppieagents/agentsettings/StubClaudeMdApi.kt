package com.tneff.cyppieagents.agentsettings

/**
 * CYP-310 — an in-memory [ClaudeMdApi] stub, a FAITHFUL model of the server's null-semantics (`AgentManagement`):
 * an ABSENT file reports `exists=false` + `version=null` (NOT a fake "v0"); the write enforces the same
 * optimistic-concurrency guard (`expectedVersion == currentVersion` where an absent file's current version is
 * `null`), so a first write MUST come with `expectedVersion=null`. Used as the VM's default (so non-live
 * tests/screens need no HTTP port) and as a hermetic fake; production wires [ClaudeMdHttpApi] at the shell.
 *
 * Modeling absent→`null` (not the old always-`v0`) is deliberate: the always-a-version stub is exactly what let
 * the first-write 409-loop drift slip past the tests (CYP-310 NO-GO).
 */
class StubClaudeMdApi(initial: String = "") : ClaudeMdApi {
    private var content: String = initial
    private var exists: Boolean = initial.isNotEmpty()
    private var version: Int = if (initial.isNotEmpty()) 1 else 0

    /** The server's `currentVersion`: `null` for an absent file, else the (stubbed) content hash. */
    private fun currentVersion(): String? = if (exists) "v$version" else null

    override suspend fun get(agentId: String): ClaudeMdView =
        ClaudeMdView(agentId = agentId, content = content, exists = exists, version = currentVersion())

    override suspend fun update(agentId: String, content: String, expectedVersion: String?): ClaudeMdView {
        // Same if-match guard as the server: absent ⇒ current=null ⇒ a first write needs expectedVersion=null.
        if (expectedVersion != currentVersion()) throw ClaudeMdException("claude_md_stale")
        this.content = content
        this.exists = true
        this.version += 1
        return ClaudeMdView(agentId = agentId, content = content, exists = true, version = "v$version")
    }
}
