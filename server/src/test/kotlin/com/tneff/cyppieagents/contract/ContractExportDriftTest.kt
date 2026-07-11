package com.tneff.cyppieagents.contract

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * CYP-409 — the schema-side **regen-diff guard**: the committed [ContractExport.ASYNCAPI_RELATIVE_PATH] MUST equal
 * what [ContractGenerator] produces right now. A stale export (a `:core` DTO or a WS channel changed without a
 * re-run of `:server:exportContract`) reddens HERE — so the TS consumer (Dev5) can never build against a schema
 * that drifted from the server types.
 *
 * One level up from [AsyncApiContractTest] (which proves the doc is well-formed + channel-complete): this proves
 * the COMMITTED FILE is the CURRENT generation. Single-sourced with the producer — the expected bytes are
 * [asyncApiExportText], the exact text the export task writes — so the test and the task cannot disagree on format.
 */
class ContractExportDriftTest {

    @Test
    fun committedAsyncApiExport_matchesFreshlyGenerated() {
        val f = File(repoRoot(), ContractExport.ASYNCAPI_RELATIVE_PATH)
        if (!f.exists()) {
            fail("missing '${ContractExport.ASYNCAPI_RELATIVE_PATH}' — run `./gradlew :server:exportContract` and commit it")
        }
        assertEquals(
            asyncApiExportText(),
            f.readText(),
            "'${ContractExport.ASYNCAPI_RELATIVE_PATH}' is STALE — a :core DTO or a WS channel changed; " +
                "re-run `./gradlew :server:exportContract` and commit the result",
        )
    }

    /** CYP-426 — the same regen-diff guard for the OpenAPI (REST) export: a `:core` DTO or a `RestContract` op
     *  changed without a re-run reddens here, so the roster/Phase-2 TS types can't drift from the server. */
    @Test
    fun committedOpenApiExport_matchesFreshlyGenerated() {
        val f = File(repoRoot(), ContractExport.OPENAPI_RELATIVE_PATH)
        if (!f.exists()) {
            fail("missing '${ContractExport.OPENAPI_RELATIVE_PATH}' — run `./gradlew :server:exportContract` and commit it")
        }
        assertEquals(
            openApiExportText(),
            f.readText(),
            "'${ContractExport.OPENAPI_RELATIVE_PATH}' is STALE — a :core DTO or a RestContract op changed; " +
                "re-run `./gradlew :server:exportContract` and commit the result",
        )
    }

    /** Locate the repo root by walking up for `settings.gradle.kts` — same runtime-file strategy as Rc2ConfigAssertionTest. */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        fail("could not locate the repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
    }
}
