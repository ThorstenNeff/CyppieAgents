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
 * CYP-223 (CYP-220 Phase 1): **store-seam interface;** default impl [FileChannelShareStore]; a future PG
 * impl implements this; companion `invoke` = current factory choice, no behavior change.
 */
@com.tneff.cyppieagents.tier.StoreKey("channel_share")
interface ChannelShareStore {
    /** Set (or replace) the directed share of [channelId] owned by [ownerProjectId] to [sharedWith]. */
    fun share(channelId: String, ownerProjectId: String, sharedWith: Set<String>): ChannelShareRecord

    /** Revoke [channelId]'s share (the gate closes). Returns true if a share existed. Idempotent. */
    fun revoke(channelId: String): Boolean

    fun record(channelId: String): ChannelShareRecord?

    /**
     * The channel ids authorized to reach INTO [activeProjectId] — i.e. shares whose `sharedWith`
     * contains it. This is exactly the [com.tneff.cyppieagents.model.AclMatrix] permit input; strictly
     * per-channelId, so a share never widens to the owner's other channels.
     */
    fun sharedInboundChannelIds(activeProjectId: String): Set<String>

    /**
     * CYP-910 — the project cascade-purge (the share partition of a project delete, mirroring the sibling
     * stores' `removeProject`, CYP-215-F2/256/325). Drops every trace of [projectId] from the gate: records
     * this project OWNS (`ownerProjectId==p`, whose channel is gone) are removed, and it is stripped from any
     * OTHER record's grantees (`sharedWith`); a record narrowed to no grantees is revoked (a share to nobody
     * is a no-op hole, the [computeShareRecord] rule). Returns the count of records dropped OR narrowed
     * (honest no-orphan reporting). **fail-closed:** a blank [projectId] purges NOTHING (never an unscoped
     * clear). **Security:** without this a deleted+re-created project id would inherit the stale
     * inbound-reach grant the operator never re-issued (id-resurrection leak).
     */
    fun removeProject(projectId: String): Int

    /**
     * CYP-910 — the one-time retroactive orphan sweep (boot-time cleanup of shares orphaned by deletes that
     * predate [removeProject]). Drops records whose OWNER is no longer a live project (`ownerProjectId ∉
     * [liveProjectIds]`, unconditional + federation-safe) and — only when [federationEnabled] is false — strips
     * grantees that are no longer live projects (narrowing to none → drop). Same drop/narrow semantics as
     * [removeProject]; returns the count of records dropped OR narrowed. **Idempotent** (a second run over a clean
     * gate returns 0). Run ONCE at boot after the project registry is loaded, NOT during a migration window.
     */
    fun sweepOrphans(liveProjectIds: Set<String>, federationEnabled: Boolean): Int

    companion object {
        /** Factory seam (CYP-223): the current impl choice is the file store. */
        operator fun invoke(
            file: File?,
            clock: () -> Long = { System.currentTimeMillis() },
        ): ChannelShareStore = FileChannelShareStore(file, clock)
    }
}

/**
 * CYP-220 Phase 6 — the **"empty grantees → revoke"** share rule, shared File+Pg so it cannot drift. Filters the
 * requested grantees (non-blank, not the owner's own project) and returns the record to PERSIST, or `null` = the
 * caller must REVOKE (a share to nobody / only the owner is a no-op hole). The no-op reply record for the null
 * case is [emptyShareRecord]. Pass a single captured `now` so both branches stamp the same time.
 */
internal fun computeShareRecord(channelId: String, ownerProjectId: String, sharedWith: Set<String>, now: Long): ChannelShareRecord? {
    val grantees = sharedWith.filter { it.isNotBlank() && it != ownerProjectId }.toSet()
    return if (grantees.isEmpty()) null
    else ChannelShareRecord(channelId, ownerProjectId, grantees, consents = setOf(ownerProjectId), sharedAt = now)
}

/** The no-op reply for an empty-grantees [share] (the record is removed; this is just the returned shape). */
internal fun emptyShareRecord(channelId: String, ownerProjectId: String, now: Long): ChannelShareRecord =
    ChannelShareRecord(channelId, ownerProjectId, emptySet(), setOf(ownerProjectId), now)

/**
 * CYP-910 — the per-record project-cascade-purge decision, single-sourced across File/Sqlite/Pg so the
 * teardown rule cannot drift (like [computeShareRecord]). Its outcome for the deleted [ChannelShareStore.removeProject] project id.
 */
internal sealed interface SharePurge {
    /** Remove the whole record (owner-orphan, or grantee-narrowing emptied the grantees → no-op hole). */
    object Drop : SharePurge
    /** The record survives as [record] — the deleted project stripped from its grantees (and consents). */
    data class Narrow(val record: ChannelShareRecord) : SharePurge
    /** The record does not mention the deleted project → leave it untouched (NOT counted). */
    object Untouched : SharePurge
}

/**
 * Decide how [rec] is affected by deleting [projectId]:
 * - owner-orphan (`ownerProjectId == p`): the share dies with its owning project (the channel is gone) → [SharePurge.Drop];
 * - grantee (`p ∈ sharedWith`): strip p from `sharedWith`/`consents`; if that empties the grantees → [SharePurge.Drop]
 *   (a share to nobody is a no-op hole, the [computeShareRecord] rule), else [SharePurge.Narrow];
 * - otherwise [SharePurge.Untouched].
 * The load-bearing security case is the GRANTEE branch: without it a deleted-then-re-created project id would
 * inherit the stale inbound-reach grant ([ChannelShareStore.sharedInboundChannelIds] still lists the channel).
 */
internal fun purgeProjectFromShare(rec: ChannelShareRecord, projectId: String): SharePurge {
    if (rec.ownerProjectId == projectId) return SharePurge.Drop // owner-orphan: the share dies with its owning project
    if (projectId !in rec.sharedWith) return SharePurge.Untouched // not a grantee → nothing to purge
    val narrowedShared = rec.sharedWith - projectId
    if (narrowedShared.isEmpty()) return SharePurge.Drop // narrowing emptied the grantees → no-op hole → revoke
    return SharePurge.Narrow(rec.copy(sharedWith = narrowedShared, consents = rec.consents - projectId))
}

/**
 * CYP-910 — the one-time boot orphan-SWEEP decision for ONE record (retroactive cleanup of orphans left by
 * deletes that predate [ChannelShareStore.removeProject]), single-sourced like [purgeProjectFromShare]:
 * - **owner-orphan** (`ownerProjectId ∉ liveProjectIds`) → [SharePurge.Drop]. UNCONDITIONAL and federation-safe —
 *   a share's owner is always a LOCAL project (you can only own/share a channel you own on this host).
 * - **grantee-orphan** (a grantee `∉ liveProjectIds`): the security-load-bearing class, swept ONLY when
 *   [federationEnabled] is false. Under federation a grantee may legitimately be a FOREIGN project id absent from
 *   the local registry, so sweeping it would wrongly revoke a valid cross-host grant. Strip the dead grantees;
 *   narrowing to none → [SharePurge.Drop]. **Forward-flag (§9.3 arming):** the boot caller must pass the real hub
 *   `federationEnabled` so foreign grantees are preserved once federation is live.
 * - otherwise [SharePurge.Untouched] — the false-positive guard: a fully-live share is never touched.
 */
internal fun sweepOrphanFromShare(rec: ChannelShareRecord, liveProjectIds: Set<String>, federationEnabled: Boolean): SharePurge {
    if (rec.ownerProjectId !in liveProjectIds) return SharePurge.Drop // owner-orphan: unconditional, federation-safe
    if (federationEnabled) return SharePurge.Untouched // grantees may be foreign under federation — never sweep them
    val liveGrantees = rec.sharedWith.filterTo(HashSet()) { it in liveProjectIds }
    if (liveGrantees.size == rec.sharedWith.size) return SharePurge.Untouched // all grantees live → nothing to strip
    if (liveGrantees.isEmpty()) return SharePurge.Drop // narrowed to no live grantees → no-op hole → drop
    return SharePurge.Narrow(rec.copy(sharedWith = liveGrantees, consents = rec.consents.filterTo(HashSet()) { it in liveProjectIds }))
}

/**
 * Persisted as an atomic 0600 JSON file under the gitRoot working dir (out-of-repo, gitignored), like
 * [com.tneff.cyppieagents.boot.ProjectRegistry]. `null` file → in-memory only (tests / dry boots).
 */
class FileChannelShareStore(
    private val file: File?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ChannelShareStore {
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

    override fun share(channelId: String, ownerProjectId: String, sharedWith: Set<String>): ChannelShareRecord = synchronized(lock) {
        val now = clock()
        val rec = computeShareRecord(channelId, ownerProjectId, sharedWith, now)
            ?: run { if (records.remove(channelId) != null) persist(); return@synchronized emptyShareRecord(channelId, ownerProjectId, now) }
        records[channelId] = rec
        persist()
        rec
    }

    override fun revoke(channelId: String): Boolean = synchronized(lock) {
        val existed = records.remove(channelId) != null
        if (existed) persist()
        existed
    }

    override fun record(channelId: String): ChannelShareRecord? = synchronized(lock) { records[channelId] }

    override fun sharedInboundChannelIds(activeProjectId: String): Set<String> = synchronized(lock) {
        if (activeProjectId.isBlank()) return@synchronized emptySet() // fail-closed: blank active reaches nothing
        records.values.filter { activeProjectId in it.sharedWith }.map { it.channelId }.toSet()
    }

    override fun removeProject(projectId: String): Int = synchronized(lock) {
        if (projectId.isBlank()) return@synchronized 0 // fail-closed: never an unscoped purge
        mutateBy { purgeProjectFromShare(it, projectId) }
    }

    override fun sweepOrphans(liveProjectIds: Set<String>, federationEnabled: Boolean): Int = synchronized(lock) {
        mutateBy { sweepOrphanFromShare(it, liveProjectIds, federationEnabled) }
    }

    /** Apply a per-record [decide] across the whole gate (caller holds [lock]); persist once if anything changed. */
    private fun mutateBy(decide: (ChannelShareRecord) -> SharePurge): Int {
        val drops = ArrayList<String>()
        val narrows = ArrayList<ChannelShareRecord>()
        for (rec in records.values) {
            when (val r = decide(rec)) {
                SharePurge.Drop -> drops.add(rec.channelId)
                is SharePurge.Narrow -> narrows.add(r.record)
                SharePurge.Untouched -> {}
            }
        }
        drops.forEach { records.remove(it) }
        narrows.forEach { records[it.channelId] = it }
        val touched = drops.size + narrows.size
        if (touched > 0) persist()
        return touched
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
