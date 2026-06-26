package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.JsonFileMessageStore
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Slice S4 AC: messages survive a server restart (JSON-file store reload). */
class PersistenceTest {

    private val agents = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("backend", "BE", Role.WORKER, "backend"),
    )

    @Test
    fun messagesSurviveRestart() {
        val file = File.createTempFile("hub-messages", ".json").also { it.delete() }
        try {
            // First "boot": post a message through the hub.
            val store1 = JsonFileMessageStore(file)
            val hub1 = Hub(HubState.hubAndSpoke(agents), store1)
            hub1.postAsAgent("backend", "po-backend", "survives restart")

            // Simulated restart: a fresh store over the same file must reload the message.
            val store2 = JsonFileMessageStore(file)
            val reloaded = store2.byChannel("po-backend")
            assertEquals(listOf("survives restart"), reloaded.map { it.body })
        } finally {
            file.delete()
        }
    }

    @Test
    fun corruptFileDoesNotBrickBoot() {
        val dir = java.nio.file.Files.createTempDirectory("hub-corrupt").toFile()
        val file = File(dir, "messages.json")
        try {
            // Simulate a torn flush: half-written / invalid JSON on disk.
            file.writeText("[{\"id\":\"1\",\"channelId\":\"po-backend\",\"from\":\"backend\",\"bo")

            // Boot must not throw; it starts empty and backs up the corrupt file.
            val store = JsonFileMessageStore(file)
            assertTrue(store.byChannel("po-backend").isEmpty())
            assertTrue(dir.listFiles()!!.any { it.name.startsWith("messages.json.corrupt-") })

            // And it can take new writes after recovery.
            val hub = Hub(HubState.hubAndSpoke(agents), store)
            hub.postAsAgent("backend", "po-backend", "after recovery")
            assertEquals(listOf("after recovery"), JsonFileMessageStore(file).byChannel("po-backend").map { it.body })
        } finally {
            dir.deleteRecursively()
        }
    }
}
