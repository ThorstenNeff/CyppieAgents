package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.InMemoryReadCursorStore
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.MessageEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.ForbiddenException
import com.tneff.cyppieagents.routing.NotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-905 (Parity-Edit E-server) teeth for the [Hub.editMessage] chokepoint. Option A: the edited BODY rides
 * `message.body` in-place; the `editedAt` MARKER is out-of-band on the [DeliveredMessage] wrapper (never on the
 * `/ws/hub` `WireMessage`). `postAsAgent` stays the SEND chokepoint — `editMessage` is a parallel guarded EDIT
 * path (author-gate `from==editor` ∧ `canWrite`, secret-masked). Each tooth is red-provable vs a named mutant.
 */
class Cyp905EditMessageTest {
    private fun newHub(): Hub {
        val agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("backend", "BE", Role.WORKER, "backend"),
            Agent("frontend", "FE", Role.WORKER, "frontend"),
        )
        return Hub(HubState.hubAndSpoke(agents, HubState.OPERATOR_ID), InMemoryMessageStore(), readCursors = InMemoryReadCursorStore())
    }

    // Author edits own message: body replaced, editedAt marker set, seq UNCHANGED (edit ≠ new post), and the marker
    // SURVIVES a GET (persisted out-of-band, resolved back through deliveredMessages).
    // Mutant: store.update doesn't record editedAt / editedAtOf returns empty → editedAt null on GET → reds.
    @Test fun authorEditReplacesBodySetsEditedAtSameSeqAndPersists() {
        val hub = newHub()
        val posted = hub.postAsAgent("backend", "po-backend", "original")
        val edited = hub.editMessage("backend", "po-backend", posted.id, "corrected")

        assertEquals("corrected", edited.message.body, "body replaced in place")
        assertNotNull(edited.editedAt, "editedAt marker set on the returned wrapper")
        assertEquals(posted.seq, edited.message.seq, "edit keeps the same seq — not a new post")

        val fromGet = hub.deliveredMessages("backend", "po-backend").first { it.message.id == posted.id }
        assertEquals("corrected", fromGet.message.body, "edited body survives a GET")
        assertNotNull(fromGet.editedAt, "editedAt marker survives a GET (persisted out-of-band)")
        assertEquals(edited.editedAt, fromGet.editedAt, "same marker on live-return and GET")
    }

    // The edit re-emits the existing /ws/comm MessageEvent carrying the edited body + editedAt (client upserts by id).
    // Mutant: editMessage does not _events.tryEmit → no MessageEvent observed → reds.
    @Test fun editReEmitsMessageEventWithEditedAt() = runBlocking {
        val hub = newHub()
        val posted = hub.postAsAgent("backend", "po-backend", "original")
        val seen = mutableListOf<CommWsServerEvent>()
        val job = launch(Dispatchers.Unconfined) { hub.events.collect { seen.add(it) } }
        val edited = hub.editMessage("backend", "po-backend", posted.id, "corrected")
        job.cancel()

        val evt = seen.filterIsInstance<MessageEvent>().last()
        assertEquals(posted.id, evt.delivered.message.id, "re-emit targets the same message id (upsert key)")
        assertEquals("corrected", evt.delivered.message.body, "re-emit carries the edited body")
        assertEquals(edited.editedAt, evt.delivered.editedAt, "re-emit carries the editedAt marker")
    }

    // Author-gate: only the author may edit. `po` is a WRITER to po-backend but NOT the author of backend's message.
    // Mutant: drop the `existing.from == editorId` gate → po's edit succeeds → reds.
    @Test fun nonAuthorForbidden() {
        val hub = newHub()
        val posted = hub.postAsAgent("backend", "po-backend", "backend's message")
        assertFailsWith<ForbiddenException>("po is a writer but not the author → 403") {
            hub.editMessage("po", "po-backend", posted.id, "hijacked")
        }
        // and the body is untouched by the failed edit
        assertEquals("backend's message", hub.deliveredMessages("backend", "po-backend").first { it.message.id == posted.id }.message.body)
    }

    // canWrite-gate is independent and load-bearing: revoke the AUTHOR's canWrite, then the author edits its OWN
    // message → 403 (you may only edit where you can still write — same discipline as postAsAgent Gate #2).
    // Mutant: drop the canWrite gate → the author-gate passes (from==editor) → the edit SUCCEEDS → reds.
    @Test fun authorWithoutCanWriteForbidden() {
        val hub = newHub()
        val posted = hub.postAsAgent("backend", "po-backend", "original")
        hub.state.setAcl(AclEntry("po-backend", "backend", canRead = true, canWrite = false)) // revoke write, keep read
        assertFailsWith<ForbiddenException>("author lost canWrite → 403 even on its own message") {
            hub.editMessage("backend", "po-backend", posted.id, "corrected")
        }
    }

    // Missing message (in a channel the editor may write) → 404, not a silent no-op.
    // Mutant: skip the existence check / return the pre-update null silently → no 404 → reds.
    @Test fun missingMessageNotFound() {
        val hub = newHub()
        assertFailsWith<NotFoundException>("no such message → 404") {
            hub.editMessage("backend", "po-backend", "does-not-exist", "x")
        }
    }

    // Gate #3 egress: the edited body is secret-masked, so no secret is persisted or re-served (same as postAsAgent).
    // Mutant: editMessage skips SecretMasker.mask → the raw key is stored/returned → reds.
    @Test fun editedBodyIsSecretMasked() {
        val hub = newHub()
        val posted = hub.postAsAgent("backend", "po-backend", "original")
        val edited = hub.editMessage("backend", "po-backend", posted.id, "leaking sk-ant-abcd1234efgh5678 here")
        assertTrue(SecretMaskerRedacted in edited.message.body, "the anthropic key is redacted in the edited body")
        assertTrue("sk-ant-abcd1234efgh5678" !in edited.message.body, "the raw key never appears")
    }

    // Regression: postAsAgent is UNTOUCHED — a fresh post is unedited (editedAt null), the SEND path is unchanged.
    @Test fun freshPostIsUnedited() {
        val hub = newHub()
        val posted = hub.postAsAgent("backend", "po-backend", "hello")
        val delivered = hub.deliveredMessages("backend", "po-backend").first { it.message.id == posted.id }
        assertNull(delivered.editedAt, "an un-edited message has no editedAt marker")
    }

    private companion object {
        const val SecretMaskerRedacted = "***REDACTED***"
    }
}
