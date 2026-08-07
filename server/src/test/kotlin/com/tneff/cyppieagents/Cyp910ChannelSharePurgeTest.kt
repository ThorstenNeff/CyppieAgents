package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.ChannelShareRecord
import com.tneff.cyppieagents.comm.ChannelShareStore
import com.tneff.cyppieagents.comm.SharePurge
import com.tneff.cyppieagents.comm.SqliteChannelShareStore
import com.tneff.cyppieagents.comm.purgeProjectFromShare
import com.tneff.cyppieagents.comm.sweepOrphanFromShare
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-910 — the project cascade-purge of the cross-project share gate ([ChannelShareStore.removeProject]).
 * The security bug: a share that survives its project's delete is a live inbound-reach grant that a
 * RE-CREATED project id (ids are caller-chosen) would inherit — an id-resurrection leak. removeProject drops
 * shares this project OWNS and strips it from any OTHER record's grantees; a record narrowed to no grantees is
 * revoked. Every tooth is red-provable vs a named mutant. Run against BOTH the File and SQLite impls (shared
 * [purgeProjectFromShare] decision → File/Sqlite/Pg cannot drift).
 */
class Cyp910ChannelSharePurgeTest {

    // ---- the shared per-record decision (single-sourced across File/Sqlite/Pg) ----

    @Test fun purgeDecision_ownerGranteeUntouched() {
        val owned = ChannelShareRecord("c", ownerProjectId = "old", sharedWith = setOf("beta"), consents = setOf("old"), sharedAt = 1L)
        assertEquals(SharePurge.Drop, purgeProjectFromShare(owned, "old"), "owner-orphan → drop the whole record")

        val granteeMulti = ChannelShareRecord("c", "keeper", sharedWith = setOf("old", "other"), consents = setOf("keeper"), sharedAt = 1L)
        val narrowed = purgeProjectFromShare(granteeMulti, "old")
        assertTrue(narrowed is SharePurge.Narrow && narrowed.record.sharedWith == setOf("other"), "grantee among several → narrow, keep the rest")

        val granteeSolo = ChannelShareRecord("c", "keeper", sharedWith = setOf("old"), consents = setOf("keeper"), sharedAt = 1L)
        assertEquals(SharePurge.Drop, purgeProjectFromShare(granteeSolo, "old"), "narrowing the last grantee → no-op hole → drop")

        val unrelated = ChannelShareRecord("c", "keeper", sharedWith = setOf("beta"), consents = setOf("keeper"), sharedAt = 1L)
        assertEquals(SharePurge.Untouched, purgeProjectFromShare(unrelated, "zzz"), "no mention of the project → untouched")
    }

    // the retroactive boot-sweep decision + its federation gate (the security-load-bearing branch).
    @Test fun sweepDecision_ownerGranteeFederationGate() {
        val live = setOf("alpha", "beta")
        val ownerGone = ChannelShareRecord("c", "ghost", setOf("beta"), setOf("ghost"), 1L)
        assertEquals(SharePurge.Drop, sweepOrphanFromShare(ownerGone, live, federationEnabled = false), "owner not live → drop")
        assertEquals(SharePurge.Drop, sweepOrphanFromShare(ownerGone, live, federationEnabled = true), "owner-orphan dropped even under federation (owner always local)")

        val granteeGone = ChannelShareRecord("c", "alpha", setOf("beta", "ghost"), setOf("alpha"), 1L)
        val narrowed = sweepOrphanFromShare(granteeGone, live, federationEnabled = false)
        assertTrue(narrowed is SharePurge.Narrow && narrowed.record.sharedWith == setOf("beta"), "dead grantee stripped when federation OFF")
        assertEquals(SharePurge.Untouched, sweepOrphanFromShare(granteeGone, live, federationEnabled = true), "federation ON → a foreign grantee is preserved")

        val allLive = ChannelShareRecord("c", "alpha", setOf("beta"), setOf("alpha"), 1L)
        assertEquals(SharePurge.Untouched, sweepOrphanFromShare(allLive, live, federationEnabled = false), "fully-live share untouched (false-positive guard)")
    }

    // ---- behavioural teeth on the real stores (File + SQLite) ----

    // owner-orphan: deleting the OWNING project drops the whole record (its channel is gone).
    // Mutant: skip the ownerProjectId==p branch → the record survives → record("chan") != null → red.
    private fun ownerOrphanDropped(s: ChannelShareStore) {
        s.share("chan", ownerProjectId = "old", sharedWith = setOf("beta"))
        assertEquals(1, s.removeProject("old"), "one record touched")
        assertNull(s.record("chan"), "owner-orphan record dropped")
        assertEquals(emptySet(), s.sharedInboundChannelIds("beta"), "the dangling gate is gone")
    }

    // grantee narrow (>1 grantee): the deleted grantee loses reach, the OTHER grantee keeps it (false-positive guard).
    // Mutant: drop-whole-record instead of narrow → 'other' loses reach → red.
    private fun granteeNarrowedKeepsOthers(s: ChannelShareStore) {
        s.share("chan", "keeper", setOf("old", "other"))
        assertEquals(1, s.removeProject("old"), "one record touched")
        assertEquals(emptySet(), s.sharedInboundChannelIds("old"), "deleted grantee's reach revoked")
        assertEquals(setOf("chan"), s.sharedInboundChannelIds("other"), "the surviving grantee KEEPS reach")
        assertEquals(setOf("other"), s.record("chan")?.sharedWith, "record narrowed, not dropped")
    }

    // THE security assertion — id-resurrection leak closure: after the grantee project is deleted, a project
    // RE-CREATED with the same id inherits NOTHING. Modelled as: grantee sole → record dropped → inbound empty.
    // Mutant: skip the grantee branch entirely → 'old' stays in sharedWith → sharedInboundChannelIds("old")={chan} → red.
    private fun idReuseLeakClosed(s: ChannelShareStore) {
        s.share("chan", "keeper", setOf("old"))
        assertEquals(setOf("chan"), s.sharedInboundChannelIds("old"), "precondition: 'old' can reach chan")
        s.removeProject("old") // 'old' project deleted
        assertEquals(emptySet(), s.sharedInboundChannelIds("old"), "a re-created 'old' inherits NO inbound reach")
        assertNull(s.record("chan"), "share narrowed to no grantees → revoked")
    }

    // false-positive guard: deleting an UNRELATED project touches nothing.
    // Mutant: over-broad purge → the intact share is dropped → red.
    private fun unrelatedProjectUntouched(s: ChannelShareStore) {
        s.share("chan", "keeper", setOf("beta"))
        assertEquals(0, s.removeProject("zzz"), "unrelated project → nothing purged")
        assertEquals(setOf("chan"), s.sharedInboundChannelIds("beta"), "the valid share survives untouched")
    }

    // fail-closed: a blank project id must never trigger an unscoped clear.
    // Mutant: drop the isBlank guard → every record wiped → red.
    private fun blankProjectPurgesNothing(s: ChannelShareStore) {
        s.share("chan", "keeper", setOf("beta"))
        assertEquals(0, s.removeProject(""), "blank id purges nothing (fail-closed)")
        assertEquals(setOf("chan"), s.sharedInboundChannelIds("beta"), "records intact after a blank purge")
    }

    // retroactive sweep: owner-orphan dropped + dead grantee stripped + a fully-live share SURVIVES (guard) + idempotent.
    // Mutant: sweep touches the fully-live share → false-positive → red; or re-run keeps finding work → not idempotent → red.
    private fun sweepPurgesOrphansKeepsLive(s: ChannelShareStore) {
        s.share("liveChan", ownerProjectId = "alpha", sharedWith = setOf("beta")) // fully live
        s.share("ownerGone", ownerProjectId = "ghost", sharedWith = setOf("beta")) // owner-orphan
        s.share("granteeGone", ownerProjectId = "alpha", sharedWith = setOf("beta", "ghost")) // dead grantee 'ghost'
        val live = setOf("alpha", "beta")
        assertEquals(2, s.sweepOrphans(live, federationEnabled = false), "two orphan records touched (owner-drop + grantee-narrow)")
        assertEquals(setOf("beta"), s.record("liveChan")?.sharedWith, "fully-live share survives untouched")
        assertNull(s.record("ownerGone"), "owner-orphan dropped")
        assertEquals(setOf("beta"), s.record("granteeGone")?.sharedWith, "dead grantee 'ghost' stripped, live 'beta' kept")
        assertEquals(0, s.sweepOrphans(live, federationEnabled = false), "idempotent: re-run over a clean gate = 0")
    }

    // sweep under federation ON: a grantee absent from the local registry may be a legit FOREIGN project → NOT stripped;
    // only owner-orphans (owner always local) are dropped. Mutant: strip grantees under federation → red.
    private fun sweepUnderFederationKeepsForeignGrantees(s: ChannelShareStore) {
        s.share("granteeForeign", ownerProjectId = "alpha", sharedWith = setOf("beta", "remoteX"))
        s.share("ownerGone", ownerProjectId = "ghost", sharedWith = setOf("beta"))
        val live = setOf("alpha", "beta")
        assertEquals(1, s.sweepOrphans(live, federationEnabled = true), "only the owner-orphan dropped; foreign grantee untouched")
        assertEquals(setOf("beta", "remoteX"), s.record("granteeForeign")?.sharedWith, "foreign grantee 'remoteX' preserved under federation")
        assertNull(s.record("ownerGone"), "owner-orphan still dropped (owner is always local)")
    }

    @Test fun file_allTeeth() {
        ownerOrphanDropped(ChannelShareStore(null, clock = { 1234L }))
        granteeNarrowedKeepsOthers(ChannelShareStore(null, clock = { 1234L }))
        idReuseLeakClosed(ChannelShareStore(null, clock = { 1234L }))
        unrelatedProjectUntouched(ChannelShareStore(null, clock = { 1234L }))
        blankProjectPurgesNothing(ChannelShareStore(null, clock = { 1234L }))
        sweepPurgesOrphansKeepsLive(ChannelShareStore(null, clock = { 1234L }))
        sweepUnderFederationKeepsForeignGrantees(ChannelShareStore(null, clock = { 1234L }))
    }

    @Test fun sqlite_allTeeth() {
        withSqlite { ownerOrphanDropped(it) }
        withSqlite { granteeNarrowedKeepsOthers(it) }
        withSqlite { idReuseLeakClosed(it) }
        withSqlite { unrelatedProjectUntouched(it) }
        withSqlite { blankProjectPurgesNothing(it) }
        withSqlite { sweepPurgesOrphansKeepsLive(it) }
        withSqlite { sweepUnderFederationKeepsForeignGrantees(it) }
    }

    // SQLite durability: the purge is committed — it survives a full close/reopen (the restart).
    // Mutant: removeProject mutates an in-memory view but doesn't DELETE/UPDATE the row → reopen resurrects the
    // grant → sharedInboundChannelIds non-empty after restart → red.
    @Test fun sqlite_purgeSurvivesRestart() {
        val dir = Files.createTempDirectory("cyp910-sqlite")
        val db = dir.resolve("channel-shares.db")
        SqliteChannelShareStore(db, clock = { 1234L }).use { s ->
            s.share("chan", "keeper", setOf("old", "other"))
            s.removeProject("old")
        }
        SqliteChannelShareStore(db, clock = { 1234L }).use { s ->
            assertEquals(emptySet(), s.sharedInboundChannelIds("old"), "purge survived restart: 'old' still has no reach")
            assertEquals(setOf("chan"), s.sharedInboundChannelIds("other"), "the surviving grantee persisted")
        }
        dir.toFile().deleteRecursively()
    }

    private fun withSqlite(block: (ChannelShareStore) -> Unit) {
        val dir = Files.createTempDirectory("cyp910-sqlite")
        try {
            SqliteChannelShareStore(dir.resolve("channel-shares.db"), clock = { 1234L }).use(block)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
