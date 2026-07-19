package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclEvent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsClientEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.DeliveredMessage
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageEvent
import com.tneff.cyppieagents.model.Subscribe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommWsEventTest {

    private inline fun <reified T> roundTrip(value: T): T =
        CommJson.decodeFromString<T>(CommJson.encodeToString(value))

    @Test
    fun messageEventRoundTrips() {
        val ev: CommWsServerEvent = MessageEvent(DeliveredMessage(Message("1", "po-backend", "backend", "hi", 1)))
        assertEquals(ev, roundTrip(ev))
        assertIs<MessageEvent>(roundTrip(ev))
    }

    @Test
    fun aclEventRoundTrips() {
        val ev: CommWsServerEvent = AclEvent(AclEntry("po-backend", "backend", canRead = true, canWrite = false))
        assertEquals(ev, roundTrip(ev))
    }

    @Test
    fun channelsEventRoundTrips() {
        val ev: CommWsServerEvent = ChannelsEvent(listOf(Channel("po-backend", "po-backend", ChannelKind.HUB, listOf("po", "backend"))))
        assertEquals(ev, roundTrip(ev))
    }

    @Test
    fun subscribeRoundTrips() {
        val ev: CommWsClientEvent = Subscribe(listOf("po-backend", "po-frontend"))
        assertEquals(ev, roundTrip(ev))
        assertIs<Subscribe>(roundTrip(ev))
    }
}
