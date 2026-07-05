package com.tneff.cyppieagents.tier

import com.tneff.cyppieagents.CommJson
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

@Serializable
private data class ToggleState(val freeFallbackEnabled: Boolean)

/**
 * CYP-220 Phase 5 — the operator lock-toggle `freeFallbackEnabled` (Design §7.3). **Default ON** (grandfather-
 * safe). When an operator turns it OFF: **new** Free accounts get no fallback → BYO required; **existing**
 * accounts are grandfathered (that decision is per-account, in [TierPolicy]). **FAIL-SAFE: if the state can't
 * be read (missing / corrupt), default to ON** — never accidentally lock out onboarding. A bootstrap store on
 * OUR infra; changes are operator-gated + audited at the route. `null` file = in-memory (tests).
 */
interface FreeFallbackToggle {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)

    companion object {
        operator fun invoke(file: File?): FreeFallbackToggle = FileFreeFallbackToggle(file)
    }
}

class FileFreeFallbackToggle(private val file: File?) : FreeFallbackToggle {
    private val log = LoggerFactory.getLogger("tier.freefallbacktoggle")
    private val lock = Any()

    @Volatile private var cached: Boolean = load()

    override fun isEnabled(): Boolean = cached

    override fun setEnabled(enabled: Boolean) = synchronized(lock) {
        cached = enabled
        persist(enabled)
    }

    /** FAIL-SAFE: any read failure (missing / corrupt / IO) → ON (true). Only an explicit `false` disables. */
    private fun load(): Boolean {
        val f = file ?: return true
        if (!f.exists() || f.length() == 0L) return true
        return runCatching { CommJson.decodeFromString<ToggleState>(f.readText()).freeFallbackEnabled }
            .getOrElse {
                log.warn("free-fallback toggle unreadable at {} — defaulting to ON (fail-safe): {}", f, it.message)
                true
            }
    }

    private fun persist(enabled: Boolean) {
        val f = file ?: return
        f.parentFile?.let { it.mkdirs(); restrict(it, dir = true) }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(CommJson.encodeToString(ToggleState(enabled)))
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        restrict(f, dir = false)
    }

    private fun restrict(f: File, dir: Boolean) {
        val perms = if (dir) {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        runCatching { Files.setPosixFilePermissions(f.toPath(), perms) }
    }
}
