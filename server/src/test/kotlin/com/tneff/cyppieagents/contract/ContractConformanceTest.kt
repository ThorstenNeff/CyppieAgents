package com.tneff.cyppieagents.contract

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclEvent
import com.tneff.cyppieagents.model.AgentMessage
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageEvent
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.RuntimeState
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.TextBlock
import com.tneff.cyppieagents.model.ToolResultBlock
import com.tneff.cyppieagents.model.ToolUseBlock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-234a — the **per-DTO schema-conformance tooth** (PO-A F1a): a REAL serialized instance of each wire DTO
 * (incl. the hard shapes — a nested-sealed `AssistantEvent`, an opaque-`JsonElement` `ToolResultBlock`, a
 * custom-serializer `SystemEvent.tools`) must VALIDATE against its GENERATED schema. This is the mis-projection
 * net the path/channel drift-test cannot provide: a schema that is wrong-but-self-consistent fails HERE. Plus
 * the **wire-name collision guard** (a cross-union discriminator clash that would silently drop a schema).
 * Validator is [SchemaConformance] — zero-dependency, test-only (CYP-226: no classpath surface).
 */
class ContractConformanceTest {

    /** Real, representative instances covering the union subtypes + the nested + the carved fields. */
    private fun cases(): List<Pair<KSerializer<StreamJsonEvent>, StreamJsonEvent>> = listOf(
        serializer<StreamJsonEvent>() to AssistantEvent(
            AgentMessage(
                id = "m1", role = "assistant",
                content = listOf(TextBlock("hi"), ToolUseBlock("t1", "Bash"), ToolResultBlock(toolUseId = "t1", content = JsonPrimitive("out"))),
            ),
            sessionId = "s1",
        ),
        serializer<StreamJsonEvent>() to SystemEvent(subtype = "init", sessionId = "s1", tools = listOf("Bash", "Read")),
        serializer<StreamJsonEvent>() to ResultEvent(subtype = "success", sessionId = "s1"),
    )

    private fun <T> conform(serializer: KSerializer<T>, instance: T): List<String> {
        val walker = SchemaWalker()
        val schema = walker.schemaFor(serializer.descriptor)
        val json: JsonElement = CommJson.encodeToJsonElement(serializer, instance)
        return SchemaConformance(walker.components).validate(json, schema)
    }

    @Test
    fun everyStreamJsonInstance_conformsToItsGeneratedSchema_inclNestedAndCarved() {
        for ((s, inst) in cases()) {
            val errors = conform(s, inst)
            assertTrue(errors.isEmpty(), "$inst must conform to its generated schema — violations: $errors")
        }
    }

    @Test
    fun commWsAndRestDtos_conformToTheirGeneratedSchemas() {
        val message = Message(id = "x", channelId = "po-be", from = "po", body = "hi", ts = 1L)
        val cases: List<Pair<KSerializer<CommWsServerEvent>, CommWsServerEvent>> = listOf(
            serializer<CommWsServerEvent>() to MessageEvent(message),
            serializer<CommWsServerEvent>() to AclEvent(AclEntry("po-be", "backend", canRead = true, canWrite = false)),
            serializer<CommWsServerEvent>() to ChannelsEvent(listOf(Channel("po-be", "po-be", ChannelKind.HUB, listOf("po", "backend")))),
        )
        for ((s, inst) in cases) assertTrue(conform(s, inst).isEmpty(), "$inst must conform — ${conform(s, inst)}")
        // a REST DTO with the additive nullable-with-default enum field (runtimeState)
        assertTrue(conform(serializer<Project>(), Project("alpha", "Alpha", RuntimeState.BACKGROUND)).isEmpty())
    }

    @Test
    fun conformanceIsNonVacuous_aMisProjectedSchemaIsRejected() {
        // Retire the correlated-error risk: hand a REAL instance to a DELIBERATELY WRONG schema and prove the
        // validator catches it — so a green conformance run genuinely means "instance satisfies its schema".
        val message = Message(id = "x", channelId = "c", from = "f", body = "b", ts = 1L)
        val json = CommJson.encodeToJsonElement(serializer<Message>(), message)
        // schema demanding a NON-EXISTENT required field → must be rejected
        val wrong = CommJson.decodeFromString<JsonObject>("""{"type":"object","required":["nope"]}""")
        val errors = SchemaConformance(emptyMap()).validate(json, wrong)
        assertTrue(errors.any { it.contains("nope") }, "a wrong required-field schema is caught (validator non-vacuous)")
        // and a type mismatch is caught
        val wrongType = CommJson.decodeFromString<JsonObject>("""{"type":"string"}""")
        assertTrue(SchemaConformance(emptyMap()).validate(json, wrongType).isNotEmpty(), "an object-as-string is caught")
    }

    @Test
    fun wireNameCollisionGuard_noTwoUnionSubtypesShareAComponentName() {
        // Walk the whole wire-DTO root set through ONE walker; assert its component-name ledger is injective
        // (no cross-union discriminator clash silently dropping a schema). Today clean; the guard fails loudly
        // the day two unrelated subtypes claim the same name.
        val walker = SchemaWalker()
        listOf(
            serializer<StreamJsonEvent>().descriptor,
            serializer<CommWsServerEvent>().descriptor,
            serializer<com.tneff.cyppieagents.model.CommWsClientEvent>().descriptor,
            serializer<com.tneff.cyppieagents.model.EventsWsServerEvent>().descriptor,
            serializer<com.tneff.cyppieagents.model.EventsWsClientEvent>().descriptor,
        ).forEach { walker.schemaFor(it) }
        assertEquals(emptyMap(), walker.nameCollisions(), "no component-name collision across the wire-DTO unions")
    }
}
