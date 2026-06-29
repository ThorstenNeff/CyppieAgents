package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * One channel's cross-project authorization (S17 / CYP-93). The **owner consent is a Set** ([consents],
 * 1→N) — today the single owner project, S18 fills bilateral/multi-owner without a reshape (the
 * "Eins-auf-N"-Naht, CROSS-PROJECT.md §2.2/§6). `sharedBy` (who) is deferred S18.
 */
@Serializable
data class ChannelShareRecord(
    val channelId: String,
    val ownerProjectId: String,
    /** The directed (owner→grantee) projects the channel is authorized to reach (§2.3). */
    val sharedWith: Set<String>,
    /** Owner consent as a collection (1→N); today `{ownerProjectId}`. */
    val consents: Set<String>,
    val sharedAt: Long,
)

/**
 * The persisted **gate** for cross-project channel shares (S17 / CYP-93). Keyed by `channelId`,
 * granularity = per channel (§6.1). It is the single source of "which channels are authorized to reach
 * which project"; the [com.tneff.cyppieagents.model.AclMatrix] permit consults [sharedInboundChannelIds]
 * derived from here. **The share is the gate:** [revoke] drops the record, so the channel immediately
 * falls back to exact-match/fail-closed — independent of any AclEntry cleanup (§6.4).
 *
 * Persisted as an atomic 0600 JSON file under the gitRoot working dir (out-of-repo, gitignored), like
 * [com.tneff.cyppieagents.boot.ProjectRegistry]. `null` file → in-memory only (tests / dry boots).
 */
class ChannelShareStore(
    private val file: File?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val log = LoggerFactory.getLogger("comm.channelshare")
    private val records: MutableMap<String, ChannelShareRecord> = mutableMapOf()

    init {
        val f = file
        if (f != null && f.exists() && f.length() > 0) {
            runCatching { records.putAll(CommJson.decodeFromString<Map<String, ChannelShareRecord>>(f.readText())) }
                .onFailure { log.error("corrupt channel-share store at {}; starting empty", f) }
        }
    }

    /** Set (or replace) the directed share of [channelId] owned by [ownerProjectId] to [sharedWith]. */
    fun share(channelId: String, ownerProjectId: String, sharedWith: Set<String>): ChannelShareRecord = synchronized(lock) {
        // A share to nobody (or only to the owner's own project) is a no-op hole — treat empty as revoke.
        val grantees = sharedWith.filter { it.isNotBlank() && it != ownerProjectId }.toSet()
        if (grantees.isEmpty()) {
            records.remove(channelId); persist(); return@synchronized ChannelShareRecord(channelId, ownerProjectId, emptySet(), setOf(ownerProjectId), clock())
        }
        val rec = ChannelShareRecord(channelId, ownerProjectId, grantees, consents = setOf(ownerProjectId), sharedAt = clock())
        records[channelId] = rec
        persist()
        rec
    }

    /** Revoke [channelId]'s share (the gate closes). Returns true if a share existed. Idempotent. */
    fun revoke(channelId: String): Boolean = synchronized(lock) {
        val existed = records.remove(channelId) != null
        if (existed) persist()
        existed
    }

    fun record(channelId: String): ChannelShareRecord? = synchronized(lock) { records[channelId] }

    /**
     * The channel ids authorized to reach INTO [activeProjectId] — i.e. shares whose `sharedWith`
     * contains it. This is exactly the [com.tneff.cyppieagents.model.AclMatrix] permit input; strictly
     * per-channelId, so a share never widens to the owner's other channels.
     */
    fun sharedInboundChannelIds(activeProjectId: String): Set<String> = synchronized(lock) {
        if (activeProjectId.isBlank()) return@synchronized emptySet() // fail-closed: blank active reaches nothing
        records.values.filter { activeProjectId in it.sharedWith }.map { it.channelId }.toSet()
    }

    private fun persist() {
        val f = file ?: return
        f.parentFile?.let { it.mkdirs(); restrictDirToOwner(it) }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.delete(); tmp.createNewFile(); restrictToOwner(tmp)
        tmp.writeText(CommJson.encodeToString(records.toMap()))
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
        }.onFailure { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true) }
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
