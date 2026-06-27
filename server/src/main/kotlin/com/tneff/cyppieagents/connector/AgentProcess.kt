package com.tneff.cyppieagents.connector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A running agent OS process, abstracted so the connector is testable without spawning a real
 * `claude` (tests inject a fake that feeds canned stdout lines and captures stdin).
 */
interface AgentProcess {
    /** NDJSON lines from the agent's stdout. The flow completes when the process ends. */
    val stdoutLines: Flow<String>
    /** Write one NDJSON line (plus newline) to the agent's stdin. */
    suspend fun writeLine(line: String)
    fun destroy()
}

fun interface ProcessSpawner {
    fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess
}

/**
 * Real spawner over [ProcessBuilder] with piped stdio (Decision D4: piped stdio, not a PTY).
 *
 * Env hygiene (Reviewer #3): the agent process does NOT inherit the server's full environment —
 * only a minimal whitelist (PATH so `claude`/node resolve, HOME so the CLI finds its credentials,
 * locale) plus the explicitly-injected [env] (e.g. ANTHROPIC_API_KEY). This keeps unrelated server
 * secrets from leaking into a spawned agent.
 */
class ProcessBuilderSpawner(
    private val passthroughEnv: Set<String> = setOf("PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE"),
    /** Source of host env values for the whitelist (injectable so env isolation is testable). */
    private val envSource: (String) -> String? = System::getenv,
) : ProcessSpawner {
    override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess {
        val builder = ProcessBuilder(command).directory(cwd)
        val processEnv = builder.environment()
        val inherited = passthroughEnv.mapNotNull { name -> envSource(name)?.let { name to it } }.toMap()
        processEnv.clear()
        processEnv.putAll(inherited)
        processEnv.putAll(env)
        // Discard stderr so its pipe buffer can't fill and block the process (F-C deadlock). We do
        // NOT route it to logs to avoid any chance of a secret-bearing line leaking unmasked.
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        val process = builder.start()
        val writer = process.outputStream.bufferedWriter()
        return object : AgentProcess {
            override val stdoutLines: Flow<String> = flow {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) emit(line)
                }
            }.flowOn(Dispatchers.IO)

            override suspend fun writeLine(line: String) = withContext(Dispatchers.IO) {
                writer.write(line)
                writer.newLine()
                writer.flush()
            }

            override fun destroy() {
                runCatching { writer.close() }
                process.destroy()
            }
        }
    }
}
