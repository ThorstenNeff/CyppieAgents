package com.tneff.cyppieagents.settings

/**
 * In-memory [ConfigRepository] for ungated development and tests (S15) until the backend seam (CYP-96)
 * lands — then the live REST client replaces this with no UI change. Honest about the two security
 * invariants the real server must keep:
 *
 * - **Key never round-trips in clear:** it stores only a masked form (`***<last4>`, mirroring the
 *   server's `Secrets.mask()`); [getApiKey] can return nothing else — the clear key is dropped on write.
 * - **Honest failures:** blank inputs throw [ConfigException] with the same wire codes the server uses
 *   (`invalid_repo_url` / `invalid_api_key`), so the VM's error path runs serverless. An optional
 *   [denyWrites] models the operator gate (server 403) for the fail-closed test.
 */
class StubConfigRepository(
    initialRepo: RepoConfigState = RepoConfigState.NotConfigured,
    initialApiKey: ApiKeyState = ApiKeyState(set = false, masked = null),
    /** When set, every write throws this code (e.g. `operator_required`) — models the server gate. */
    private val denyWrites: String? = null,
) : ConfigRepository {

    private var repo: RepoConfigState = initialRepo
    private var apiKey: ApiKeyState = initialApiKey

    override suspend fun getRepo(): RepoConfigState = repo

    override suspend fun putRepo(url: String, branch: String): RepoConfigState.Configured {
        denyWrites?.let { throw ConfigException(it) }
        // The server is the source of truth for URL validity; the stub only models the empty-URL reject.
        if (url.isBlank()) throw ConfigException("invalid_repo_url")
        val saved = RepoConfigState.Configured(url.trim(), branch.ifBlank { "main" }.trim())
        repo = saved
        return saved
    }

    override suspend fun getApiKey(): ApiKeyState = apiKey

    override suspend fun putApiKey(apiKey: String): ApiKeyState {
        denyWrites?.let { throw ConfigException(it) }
        if (apiKey.isBlank()) throw ConfigException("invalid_api_key")
        // Store ONLY the masked form — the clear key never survives the write (never readable again).
        val masked = "***" + apiKey.takeLast(4)
        val saved = ApiKeyState(set = true, masked = masked)
        this.apiKey = saved
        return saved
    }
}
