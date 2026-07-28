package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.InMemoryReadCursorStore
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.ForbiddenException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-870 (OS-E) threading + task/status surface teeth (scope A — query/surface, :server-only). The reply-tree
 * reads the existing `meta.inReplyTo` structure; the Task/Status surface filters by the POSTER-LABEL `meta.kind`
 * (render≠authority — a pure read, never a routing authority). Both are ACL-`canRead`-filtered. The post path is
 * unchanged (the canWrite chokepoint was the authority at post time). Each tooth is red-provable vs a named mutant.
 */
class Cyp870ThreadingTaskStatusTest {
    private fun newHub(): Hub {
        val agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("backend", "BE", Role.WORKER, "backend"),
            Agent("frontend", "FE", Role.WORKER, "frontend"),
        )
        return Hub(HubState.hubAndSpoke(agents, HubState.OPERATOR_ID), InMemoryMessageStore(), readCursors = InMemoryReadCursorStore())
    }

    // Reply-tree: root + its transitive inReplyTo-descendants, ordered by seq, EXCLUDING an unrelated message.
    // Mutant: thread ignores inReplyTo (returns all channel messages) → the unrelated message appears → reds.
    @Test fun threadReturnsRootAndDescendantsBySeq_excludesUnrelated() {
        val hub = newHub()
        val root = hub.postAsAgent("po", "po-backend", "root")
        val reply1 = hub.postAsAgent("backend", "po-backend", "reply1", MessageMeta(inReplyTo = root.id))
        val reply2 = hub.postAsAgent("po", "po-backend", "reply2", MessageMeta(inReplyTo = reply1.id)) // nested
        val sibling = hub.postAsAgent("backend", "po-backend", "sibling", MessageMeta(inReplyTo = root.id))
        val unrelated = hub.postAsAgent("po", "po-backend", "unrelated") // no inReplyTo

        val ids = hub.thread("backend", "po-backend", root.id).map { it.message.id }
        assertEquals(listOf(root.id, reply1.id, reply2.id, sibling.id), ids, "root + descendants, ordered by seq")
        assertTrue(unrelated.id !in ids, "a message outside the reply-tree is excluded")
    }

    // Reply-tree is ACL-scoped fail-closed: a non-member (can't read the channel) is DENIED (403), not given the
    // tree — same fail-closed answer as GET …/messages (thread delegates to the ACL-gated channelMessages).
    // Mutant: thread bypasses the ACL gate → the non-member reads the tree → reds.
    @Test fun threadDeniedForNonReader() {
        val hub = newHub()
        val root = hub.postAsAgent("po", "po-backend", "root")
        hub.postAsAgent("backend", "po-backend", "reply", MessageMeta(inReplyTo = root.id))
        assertFailsWith<ForbiddenException>("frontend is not a member of po-backend → denied") {
            hub.thread("frontend", "po-backend", root.id)
        }
    }

    // Task/Status surface: `messagesOfKind` returns ONLY messages whose poster-label meta.kind matches.
    // Mutant: the kind filter is dropped (returns all) → the STATUS/NOTE/kindless messages appear → reds.
    @Test fun messagesOfKindFiltersToThatKind() {
        val hub = newHub()
        hub.postAsAgent("po", "po-backend", "a task", MessageMeta(kind = MessageKind.TASK))
        hub.postAsAgent("backend", "po-backend", "a status", MessageMeta(kind = MessageKind.STATUS))
        hub.postAsAgent("po", "po-backend", "a note", MessageMeta(kind = MessageKind.NOTE))
        hub.postAsAgent("po", "po-backend", "no kind")

        val tasks = hub.messagesOfKind("backend", "po-backend", MessageKind.TASK)
        assertEquals(listOf("a task"), tasks.map { it.message.body }, "only the TASK-labelled message")
        assertEquals(listOf("a status"), hub.messagesOfKind("backend", "po-backend", MessageKind.STATUS).map { it.message.body }, "STATUS deltas surface separately")
    }

    // The Task/Status surface is ACL-scoped fail-closed too: a non-member is DENIED (403), not given the labels.
    // Mutant: messagesOfKind bypasses the ACL gate → the non-member reads the labelled messages → reds.
    @Test fun messagesOfKindDeniedForNonReader() {
        val hub = newHub()
        hub.postAsAgent("po", "po-backend", "a task", MessageMeta(kind = MessageKind.TASK))
        assertFailsWith<ForbiddenException>("frontend cannot read po-backend → denied") {
            hub.messagesOfKind("frontend", "po-backend", MessageKind.TASK)
        }
    }
}
