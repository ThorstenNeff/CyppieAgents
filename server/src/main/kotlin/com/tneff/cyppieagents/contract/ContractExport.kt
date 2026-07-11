package com.tneff.cyppieagents.contract

import com.tneff.cyppieagents.routing.docsJson
import java.io.File

/**
 * CYP-409 (W1 producer) — export the **AsyncAPI (WS) contract** generated from `:core` to a committed file the
 * TS consumer (Dev5) reads, so a Web/TS frontend gets its WS types from the SAME single source the server does.
 *
 * **Offline + secret-free by construction.** This runs [ContractGenerator.asyncApi] — a pure projection of the
 * `:core` `@Serializable` descriptors (via [SchemaWalker]) — with **no server boot, no network, no secrets**.
 * (The `/docs/asyncapi.json` route is auth-gated and would need a live localhost server + a token; the export
 * deliberately does NOT hit it — it calls the generator directly, so the build stays hermetic.)
 *
 * **Single source.** The exact bytes are [asyncApiExportText]: [docsJson] — the SAME pretty-print serialization
 * the `/docs` route serves — plus a trailing newline (POSIX/git convention). The `:server:exportContract` Gradle
 * task writes it, and [ContractExportDriftTest] regenerates + compares it, so the committed file can never drift
 * from the generator (a stale export reddens the drift test).
 */
object ContractExport {
    /** Repo-relative path of the committed export (the Gradle task + the drift test agree on this one location). */
    const val ASYNCAPI_RELATIVE_PATH: String = "web-ts/contract/asyncapi.json"
}

/**
 * The canonical export bytes — the ONE definition the export task writes and the drift test asserts against, so
 * the "+newline" and the serialization can't drift between producer and guard. Pretty-printed (readable diffs)
 * via [docsJson]; trailing newline so git records no "\ No newline at end of file".
 */
fun asyncApiExportText(): String = docsJson(ContractGenerator.asyncApi()) + "\n"

/**
 * Write the AsyncAPI export to `args[0]` (an absolute path from the Gradle task), or the repo-relative
 * [ContractExport.ASYNCAPI_RELATIVE_PATH] by default. Creates parent dirs. Prints the path + byte count.
 */
fun main(args: Array<String>) {
    val out = File(args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: ContractExport.ASYNCAPI_RELATIVE_PATH)
    out.absoluteFile.parentFile?.mkdirs()
    val text = asyncApiExportText()
    out.writeText(text)
    println("CYP-409 exportContract: wrote ${text.toByteArray().size} bytes → ${out.absolutePath}")
}
