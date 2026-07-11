package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.AgentErrorCode
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.AgentRunStateEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-421 (b2) — the additive `errorCode` field must NOT break an un-updated consumer. Adding a field to a
 * server→client DTO is a wire change; the safety property is that a consumer built before the field still decodes
 * the NEW-shape payload without throwing. [CommJson] provides it (`ignoreUnknownKeys = true`), and
 * `explicitNulls = false` keeps non-ERROR frames byte-identical to the old `{agentId, runState}` shape.
 *
 * The "old consumer" is modelled by [OldAgentRunStateEvent] — the DTO exactly as it was BEFORE errorCode (no such
 * field). Mutation: decode the same NEW-shape bytes with a STRICT `Json { ignoreUnknownKeys = false }` → it
 * throws on the unknown `errorCode` key, proving the tolerance is what carries the migration (not a coincidence).
 */
class AgentRunStateEventMigrationTest {

    /** The `/ws/lifecycle` DTO as it stood BEFORE CYP-421 — an un-updated consumer that has never heard of errorCode. */
    @Serializable
    private data class OldAgentRunStateEvent(val agentId: String, val runState: AgentRunState)

    private val strict = Json { ignoreUnknownKeys = false; classDiscriminator = "type"; encodeDefaults = true }

    @Test
    fun newShape_roundTrips() {
        val ev = AgentRunStateEvent("backend", AgentRunState.ERROR, AgentErrorCode.SIGNALLED)
        val json = CommJson.encodeToString(AgentRunStateEvent.serializer(), ev)
        assertEquals(ev, CommJson.decodeFromString(AgentRunStateEvent.serializer(), json))
    }

    @Test
    fun nonErrorFrame_omitsErrorCode_keepingTheOldShape() {
        val json = CommJson.encodeToString(AgentRunStateEvent.serializer(), AgentRunStateEvent("backend", AgentRunState.RUNNING))
        assertTrue("errorCode" !in json, "explicitNulls=false must omit a null errorCode → non-ERROR frames keep {agentId,runState}")
    }

    @Test
    fun newShape_decodedByOldConsumer_doesNotThrow() {
        val newShape = CommJson.encodeToString(
            AgentRunStateEvent.serializer(),
            AgentRunStateEvent("backend", AgentRunState.ERROR, AgentErrorCode.CRASHED),
        )
        assertTrue("errorCode" in newShape, "precondition: the new-shape ERROR frame actually carries the field")
        // The un-updated consumer (no errorCode field) tolerates the extra key via CommJson.ignoreUnknownKeys.
        val old = CommJson.decodeFromString(OldAgentRunStateEvent.serializer(), newShape)
        assertEquals("backend", old.agentId)
        assertEquals(AgentRunState.ERROR, old.runState)
    }

    @Test
    fun mutation_strictDecoderThrowsOnTheNewKey() {
        val newShape = """{"agentId":"backend","runState":"ERROR","errorCode":"CRASHED"}"""
        // Prove the tolerance is what carries the migration: a strict decoder reddens on the same bytes.
        assertFailsWith<SerializationException> {
            strict.decodeFromString(OldAgentRunStateEvent.serializer(), newShape)
        }
        // And CommJson (tolerant) does NOT — the actual consumer contract.
        assertNull(CommJson.decodeFromString(AgentRunStateEvent.serializer(), """{"agentId":"backend","runState":"RUNNING"}""").errorCode)
    }
}
