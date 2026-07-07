package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.agentsettings.ClaudeMdException
import com.tneff.cyppieagents.agentsettings.ClaudeMdHttpApi
import com.tneff.cyppieagents.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-310 — the INTEGRATION seam QA gate: the **real Dev client** [ClaudeMdHttpApi] driven against the
 * **real Backend server** ([e2ePlatform] = real `AgentMgmtRoutes` + `AgentManagement` + real `CommJson` +
 * real worktree files). Both halves self-verified against the CONTRACT; this is the first test that runs
 * them AGAINST EACH OTHER — which is where a null-vs-empty-string version representation drift lives.
 *
 * The client's [com.tneff.cyppieagents.agentsettings.ClaudeMdView.version] and `update(expectedVersion)`
 * are **non-null String**; the server's `:core` `ClaudeMdView.version` / `ClaudeMdUpdate.expectedVersion`
 * are **`String?`** and `null` means "the file does not exist yet". `CommJson.explicitNulls = false` means
 * the server OMITS `version` on an absent file, so the client collapses `null → ""` — and can never send
 * the `null` the server's create path requires.
 */
class Cyp310ClientServerWireTest {

    private fun boot() =
        listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))

    private fun api(p: E2ePlatform, http: HttpClient, token: String = E2ePlatform.OPERATOR_TOKEN) =
        ClaudeMdHttpApi(http, p.baseUrl, token)

    /**
     * THE SEAM BUG: a fresh agent has no CLAUDE.md, so the headline CYP-310 flow — "create the file via the
     * settings panel" — must work through the real client. It does not: the client reads the absent file as
     * `version=""` (its non-null rendering of the server's omitted null) and writes `expectedVersion=""`; the
     * server re-hashes the still-absent file to `null`, `"" != null` → 409 `claude_md_stale`. The very first
     * save spuriously reports an "external conflict" and NO file is ever written. (Backend's own e2e passes a
     * TYPED `expectedVersion=null`; the client's own e2e starts from `exists=true` — neither crosses the seam.)
     */
    @Test
    fun realClient_createsFirstClaudeMd_onFreshAgent(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp310wire-create").toFile()
        e2ePlatform(boot(), gitRootOverride = dir).use { p ->
            val http = HttpClient(CIO)
            try {
                val client = api(p, http)
                // 1. The real client reads the live (absent) file — the settings panel's initial GET.
                val v0 = client.get("backend")
                assertFalse(v0.exists, "a fresh agent has no CLAUDE.md")
                assertEquals("", v0.content)

                // 2. The user types content and clicks "Überschreiben": overwriteClaudeMd() calls
                //    update(content, expectedVersion = the base it just read). A first write must CREATE the file.
                val written = try {
                    client.update("backend", "# hello", expectedVersion = v0.version)
                } catch (e: ClaudeMdException) {
                    fail(
                        "SEAM BUG: the real client cannot CREATE a first CLAUDE.md — the server rejected the " +
                            "create with code='${e.code}' because the client sent expectedVersion='${v0.version}' " +
                            "(its non-null-String rendering of the server's null for an absent file). Every fresh " +
                            "agent's first save 409s and no file is written.",
                    )
                }
                assertTrue(written.exists, "the first write created the file")
                assertEquals("# hello", written.content)
                assertEquals(
                    "# hello",
                    File(dir, "projects/default/backend/CLAUDE.md").readText(),
                    "the create wrote the REAL worktree file",
                )
            } finally {
                http.close()
            }
        }
        dir.deleteRecursively()
    }

    /**
     * WIRE-COMPAT on the EXISTS path (isolates the bug above to create-only): with a file already present, the
     * real client must round-trip the real server's shape — GET (`agentId/content/exists/version`), a matching
     * update → 200 echo, and a stale update → 409 mapped to `ClaudeMdException("claude_md_stale")`.
     */
    @Test
    fun realClient_roundTripsExistingFile_andMaps409(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp310wire-exists").toFile()
        e2ePlatform(boot(), gitRootOverride = dir).use { p ->
            val http = HttpClient(CIO)
            try {
                val client = api(p, http)
                // Seed a real CLAUDE.md out-of-band (as if the repo checkout or the agent itself wrote it).
                val wtFile = File(dir, "projects/default/backend/CLAUDE.md")
                wtFile.parentFile.mkdirs()
                wtFile.writeText("# seeded persona")

                // GET: the real client parses the real server's real shape.
                val v = client.get("backend")
                assertTrue(v.exists, "the seeded file exists")
                assertEquals("# seeded persona", v.content)
                assertEquals("backend", v.agentId)
                // Post-fix: version is `String?` (null = absent); an EXISTING file must still carry a non-null hash.
                assertFalse(v.version.isNullOrEmpty(), "an existing file has a non-null content-hash version")

                // Matching update → 200 echo (hard overwrite).
                val w = client.update("backend", "# rewritten", expectedVersion = v.version)
                assertEquals("# rewritten", w.content)
                assertTrue(!w.version.isNullOrEmpty() && w.version != v.version, "the version tracks the change")
                assertEquals("# rewritten", wtFile.readText())

                // Stale base (v is now superseded) → 409 → ClaudeMdException("claude_md_stale"), NO write.
                val stale = assertFails { client.update("backend", "# clobber", expectedVersion = v.version) }
                assertIs<ClaudeMdException>(stale)
                assertEquals("claude_md_stale", stale.code)
                assertEquals("# rewritten", wtFile.readText(), "the stale write changed nothing")
            } finally {
                http.close()
            }
        }
        dir.deleteRecursively()
    }

    /**
     * WIRE-COMPAT: the 256 KB cap → 413 must surface through the real client as a mapped error code, not a hang
     * or a silent success. (Server throws `too_large`; the client maps the `{error:{code}}` envelope.)
     */
    @Test
    fun realClient_oversizeWrite_maps413(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp310wire-413").toFile()
        e2ePlatform(boot(), gitRootOverride = dir).use { p ->
            val http = HttpClient(CIO)
            try {
                val client = api(p, http)
                val v0 = client.get("backend") // absent → version ""
                val tooBig = "x".repeat(256 * 1024 + 1)
                val rejected = assertFails { client.update("backend", tooBig, expectedVersion = v0.version) }
                assertIs<ClaudeMdException>(rejected)
                assertEquals("too_large", rejected.code, "over-cap write → 413 too_large through the real client")
                assertFalse(
                    File(dir, "projects/default/backend/CLAUDE.md").exists(),
                    "the rejected over-cap write created no file",
                )
            } finally {
                http.close()
            }
        }
        dir.deleteRecursively()
    }
}
