package com.tneff.cyppieagents.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-421 (c1) — the real [InMemoryTerminalGrants] closes the CYP-394 admit→track TOCTOU. The socket admits
 * (`mayOpen == true`), then does async work (attach / spawn `bash -l`) BEFORE it registers the session's kill via
 * [InMemoryTerminalGrants.track]. A revoke landing in that window would, naively, fire no kill (none registered
 * yet) → the just-opened shell survives a revoke.
 *
 * [revokeInAdmitTrackWindow_track_endsTheShellImmediately] is the load-bearing tooth: it models the in-window
 * revoke deterministically (revoke BEFORE track) and asserts `track` kills NOW. Mutation — drop the re-check in
 * `track` (register the kill unconditionally) → the in-window revoke never fires it → `killed` stays false → red.
 */
class Cyp421TerminalGrantRaceTest {

    @Test
    fun mayOpen_isDefaultDeny_untilGranted() {
        val s = InMemoryTerminalGrants()
        assertFalse(s.mayOpen("backend", "member1"), "default-DENY: no grant ⇒ false")
        assertEquals(listOf("member1"), s.grant("backend", "member1"))
        assertTrue(s.mayOpen("backend", "member1"))
        assertEquals(emptyList(), s.revoke("backend", "member1"))
        assertFalse(s.mayOpen("backend", "member1"), "revoke removes the grant")
    }

    @Test
    fun revoke_endsAnAlreadyTrackedLiveShell() {
        val s = InMemoryTerminalGrants()
        s.grant("backend", "member1")
        var killed = false
        val handle = s.track("backend", "member1") { killed = true }
        assertFalse(killed, "a live grant is not killed until it is revoked")
        s.revoke("backend", "member1")
        assertTrue(killed, "revoke must fire the tracked kill (ACL-takes-effect-now, not just the next connect)")
        handle.close()
    }

    @Test
    fun revokeInAdmitTrackWindow_track_endsTheShellImmediately() {
        val s = InMemoryTerminalGrants()
        s.grant("backend", "member1")   // the socket's admit saw the grant...
        s.revoke("backend", "member1")   // ...then it is revoked in the admit→track window (no live kill yet)
        var killed = false
        s.track("backend", "member1") { killed = true }
        assertTrue(killed, "c1: track must re-check the grant and end a session whose grant vanished in the window")
    }

    @Test
    fun closedHandle_doesNotFireAStaleKillOnALaterRevoke() {
        val s = InMemoryTerminalGrants()
        s.grant("backend", "member1")
        var killed = false
        val handle = s.track("backend", "member1") { killed = true }
        handle.close()                   // the session closed normally → deregister the kill
        s.revoke("backend", "member1")
        assertFalse(killed, "a normally-closed session's kill must not fire on a later revoke")
    }

    @Test
    fun grantsAreSortedAndPerAgentIsolated() {
        val s = InMemoryTerminalGrants()
        s.grant("backend", "zeta")
        s.grant("backend", "alpha")
        assertEquals(listOf("alpha", "zeta"), s.listGrants("backend"), "sorted, stable")
        assertEquals(emptyList(), s.listGrants("frontend"), "a grant on one agent does not leak to another")
    }
}
