package com.tneff.cyppieagents.contract

import com.tneff.cyppieagents.routing.docsJson
import java.io.File

/**
 * CYP-409/CYP-426 (W1 producer) — export the generated contracts from `:core` to committed files the TS consumer
 * (Dev5) reads, so a Web/TS frontend gets its types from the SAME single source the server does: the **AsyncAPI
 * (WS) contract** (CYP-409) AND the **OpenAPI (REST) contract** (CYP-426 — the roster `GET /api/agents → Agent`
 * and the Phase-2 REST screens).
 *
 * **Offline + secret-free by construction.** This runs [ContractGenerator.asyncApi]/[ContractGenerator.openApi] —
 * pure projections of the `:core` `@Serializable` descriptors (via [SchemaWalker]) — with **no server boot, no
 * network, no secrets**. (The `/docs` json routes are auth-gated and would need a live localhost server + a
 * token; the export deliberately does NOT hit them — it calls the generators directly, so the build stays hermetic.)
 *
 * **Single source.** The exact bytes are [asyncApiExportText]: [docsJson] — the SAME pretty-print serialization
 * the `/docs` route serves — plus a trailing newline (POSIX/git convention). The `:server:exportContract` Gradle
 * task writes it, and [ContractExportDriftTest] regenerates + compares it, so the committed file can never drift
 * from the generator (a stale export reddens the drift test).
 */
object ContractExport {
    /** Repo-relative path of the committed AsyncAPI (WS) export (the Gradle task + the drift test agree here). */
    const val ASYNCAPI_RELATIVE_PATH: String = "web-ts/contract/asyncapi.json"

    /** CYP-426 — repo-relative path of the committed OpenAPI (REST) export (same producer/guard agreement). */
    const val OPENAPI_RELATIVE_PATH: String = "web-ts/contract/openapi.json"
}

/**
 * The canonical AsyncAPI (WS) export bytes — the ONE definition the export task writes and the drift test asserts
 * against, so the "+newline" and the serialization can't drift between producer and guard. Pretty-printed
 * (readable diffs) via [docsJson]; trailing newline so git records no "\ No newline at end of file".
 */
fun asyncApiExportText(): String = docsJson(ContractGenerator.asyncApi()) + "\n"

/**
 * CYP-426 — the OpenAPI (REST) export bytes, single-sourced like [asyncApiExportText]. Uses the FULL first-party
 * [ContractGenerator.openApi] (NOT the Bearer-only `hostedOpenApi()` the external `/docs` surface serves): the
 * first-party TS consumer needs the complete REST surface + component schemas — e.g. `GET /api/agents → Agent`
 * (with `id`/`name`/`role`), from which the roster + `poAgentId = role==PO` are derived.
 */
fun openApiExportText(): String = docsJson(ContractGenerator.openApi()) + "\n"

/**
 * Write BOTH generated contracts to their repo-relative committed paths ([ContractExport.ASYNCAPI_RELATIVE_PATH],
 * [ContractExport.OPENAPI_RELATIVE_PATH]) — resolved against the Gradle task's `workingDir` (the repo root).
 * Creates parent dirs; prints each path + byte count. Offline + secret-free (runs the generators directly).
 */
fun main(args: Array<String>) {
    writeExport(ContractExport.ASYNCAPI_RELATIVE_PATH, asyncApiExportText())
    writeExport(ContractExport.OPENAPI_RELATIVE_PATH, openApiExportText())
}

private fun writeExport(relativePath: String, text: String) {
    val out = File(relativePath)
    out.absoluteFile.parentFile?.mkdirs()
    out.writeText(text)
    println("exportContract: wrote ${text.toByteArray().size} bytes → ${out.absolutePath}")
}
