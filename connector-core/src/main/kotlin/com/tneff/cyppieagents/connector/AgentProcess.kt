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
    /**
     * NDJSON lines from the agent's stdout.
     *
     * The flow completes when the **stream** ends — which is **not** the process ending (CYP-351). A process can
     * close its stdout and keep running: `sh -c 'exec 1>&-; sleep 3'` completes this flow at once and lives for
     * three more seconds. The old wording, *"completes when the process ends"*, is why the exit path inferred a
     * death from an EOF, and why in-memory doubles that complete immediately looked like they modelled a live
     * agent while, by the contract, they were claiming an instant death.
     *
     * The only observation of the **process** is [awaitExitCode] / [awaitTerminated]. This flow ending is only
     * the end of our hearing.
     */
    val stdoutLines: Flow<String>
    /** Write one NDJSON line (plus newline) to the agent's stdin. */
    suspend fun writeLine(line: String)
    fun destroy()

    /**
     * Suspend until the OS process has actually terminated (CYP-73). [destroy] only *requests*
     * termination; a Stop must confirm the process is gone before reporting STOPPED, otherwise a
     * still-dying agent could keep emitting onto the bus (a zombie). Default is a no-op for in-memory
     * test doubles that have no real process; the real spawner overrides it with `Process.waitFor`.
     */
    suspend fun awaitTerminated() {}

    /**
     * Suspend until the process has terminated and report **how** it ended (CYP-351): `0` = clean exit,
     * non-zero = crash/signal, `null` = this process has no observable exit status.
     *
     * [awaitTerminated] already blocks on `Process.waitFor()` and throws the returned `Int` away, which is
     * why the server could never distinguish "the agent finished" from "the agent died": the exit status was
     * observed and discarded at the same instruction. This is the same observation, kept.
     *
     * Defaults to `null` so the in-memory doubles and the remote bridge (whose process lives in the user's
     * infrastructure and is only ever self-reported) inherit "unknown" rather than a fabricated `0` — an
     * unknown status must never be read as a clean exit.
     */
    suspend fun awaitExitCode(): Int? = null

    /**
     * CYP-374 — a HARD kill (SIGKILL) for [ClaudeCodeSession.closeAndAwait]'s escalation. When [destroy] (SIGTERM)
     * is ignored and the process holds its stdout open past the flush timeout, this closes the pipe
     * UNCONDITIONALLY so the reader can drain and closeAndAwait can reach true quiescence (join) — instead of
     * severing the reader mid-turn, which breaks CYP-247's switch-attribution barrier. Defaults to [destroy] for
     * in-memory doubles / the remote bridge (no real local process to hard-kill); the real spawner overrides it
     * with `Process.destroyForcibly()`.
     */
    fun destroyForcibly() = destroy()
}

fun interface ProcessSpawner {
    fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess
}

/**
 * Real spawner over [ProcessBuilder] with piped stdio (Decision D4: piped stdio, not a PTY).
 *
 * Env hygiene (Reviewer #3): the agent process does NOT inherit the server's full environment —
 * only a minimal whitelist (PATH so `claude`/node resolve, HOME so the CLI finds its credentials,
 * USER/LOGNAME so the macOS Keychain OAuth lookup can resolve the calling user, locale) plus the
 * explicitly-injected [env] (e.g. ANTHROPIC_API_KEY). This keeps unrelated server secrets from
 * leaking into a spawned agent.
 *
 * CYP-168: `USER`/`LOGNAME` are REQUIRED for OAuth on macOS — the CLI reads its credential from the
 * Keychain keyed by the calling user, and without these vars the lookup fails with "Not logged in"
 * (only the `ANTHROPIC_API_KEY` path worked). Verified on a real staging turn. They carry no secret
 * (just the login name), so adding them does not weaken the hygiene invariant.
 */
class ProcessBuilderSpawner(
    private val passthroughEnv: Set<String> = setOf("PATH", "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE"),
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

            // CYP-374: SIGKILL — closes the pipe unconditionally (a SIGTERM-ignoring process can't hold it open),
            // so closeAndAwait's reader can drain and reach quiescence instead of being severed mid-turn.
            override fun destroyForcibly() {
                runCatching { writer.close() }
                process.destroyForcibly()
            }

            override suspend fun awaitTerminated() {
                // Block the IO dispatcher (not the caller's thread) until the process is really gone.
                withContext(Dispatchers.IO) { process.waitFor() }
            }

            // CYP-351: the same waitFor, but the status is kept instead of dropped. 0 = clean, non-zero =
            // crash or signal (a SIGTERM'd `claude` reports 143), which is what tells RUNNING from dead.
            override suspend fun awaitExitCode(): Int = withContext(Dispatchers.IO) { process.waitFor() }
        }
    }
}
