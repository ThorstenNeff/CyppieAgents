package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.routing.TokenRegistry
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * CYP-171 / E2.6 (S3) — durable, **secret-at-rest** store for runtime-minted remote-agent bearer tokens
 * (`agentId → token`), so an operator-pre-provisioned remote agent's credential survives a server restart
 * (the agent can reconnect). Mirrors [ProjectConfigStore] hardening 1:1: out-of-repo under the gitRoot,
 * **0700 dir + 0600 file**, atomic-move write, **0600-FIRST tmp** (the token never lands in a world-
 * readable file even briefly), and a corrupt store is **NOT logged/backed-up** (it holds secrets) — it
 * starts empty rather than bricking boot. The token is NEVER logged anywhere.
 */
class RemoteTokenStore(
    /** Persistence target; `null` → in-memory only (tests / dry boots). */
    private val file: File?,
) {
    private val lock = Any()
    private val log = LoggerFactory.getLogger("boot.remotetoken")
    private val tokenByAgent: MutableMap<String, String> = mutableMapOf()

    init {
        val f = file
        if (f != null && f.exists() && f.length() > 0) {
            try {
                tokenByAgent.putAll(CommJson.decodeFromString<Map<String, String>>(f.readText()))
            } catch (e: Exception) {
                // A torn/corrupt store must not brick boot. Start empty. We do NOT back up or log the
                // contents — the file holds secrets, so no token value is ever written elsewhere.
                log.error("corrupt remote-token store at {}; starting empty (contents not logged)", f)
            }
        }
    }

    /** Snapshot `agentId → token` (boot restore into the [TokenRegistry]). */
    fun all(): Map<String, String> = synchronized(lock) { tokenByAgent.toMap() }

    fun put(agentId: String, token: String) {
        synchronized(lock) { tokenByAgent[agentId] = token; persist() }
    }

    fun remove(agentId: String) {
        synchronized(lock) { if (tokenByAgent.remove(agentId) != null) persist() }
    }

    private fun persist() {
        val f = file ?: return
        f.parentFile?.let { parent ->
            parent.mkdirs()
            restrictDirToOwner(parent) // 0700 — a local non-owner must not list the dir
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        // 0600-FIRST: create + lock the empty tmp BEFORE the secret is written (writeText keeps perms).
        tmp.delete()
        tmp.createNewFile()
        restrictToOwner(tmp)
        tmp.writeText(CommJson.encodeToString(tokenByAgent.toMap()))
        restrictToOwner(tmp) // re-assert (defensive)
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        restrictToOwner(f)
    }

    private fun restrictToOwner(f: File) {
        runCatching {
            Files.setPosixFilePermissions(f.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }.onFailure {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
        }
    }

    private fun restrictDirToOwner(d: File) {
        runCatching {
            Files.setPosixFilePermissions(
                d.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            )
        }.onFailure {
            d.setReadable(false, false); d.setReadable(true, true)
            d.setWritable(false, false); d.setWritable(true, true)
            d.setExecutable(false, false); d.setExecutable(true, true)
        }
    }
}

/**
 * CYP-171 — composes the in-memory [TokenRegistry] (lookup) with the durable [RemoteTokenStore] (at-rest):
 * [issue] mints + persists in one op; [revoke] drops from both. The single point [AgentManagement] uses, so
 * the registry and the store can't drift. The minted token is returned to the caller (disclosed once) and
 * NEVER logged here.
 */
class RemoteTokenIssuer(
    private val registry: TokenRegistry,
    private val store: RemoteTokenStore,
) {
    /** Mint a token for [agentId], persist it, and return it (the once-disclosed credential). */
    fun issue(agentId: String): String {
        val token = registry.mint(agentId)
        store.put(agentId, token)
        return token
    }

    /** Revoke [agentId]'s token from the registry AND the store (idempotent; on agent removal). */
    fun revoke(agentId: String) {
        registry.revoke(agentId)
        store.remove(agentId)
    }
}
