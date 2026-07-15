package com.tneff.cyppieagents.net.hub

import com.tneff.cyppieagents.net.hub.mux.StreamClass
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-620 — pins the **default** `HubTransport.baseUrlFor` class→leg mapping (the coarse 2-leg split used by Local/stub
 * modes + as the fallback): CONTROL/REST → the REST leg ([HubTransport.httpBaseUrl]); AGENT_WS/SINGLETON_WS → the WS leg
 * ([HubTransport.wsBaseUrl]). The remote tunnel transport OVERRIDES this with 4 distinct ports (the fine QoS routing,
 * teethed separately). A mutation of the default mapping (e.g. CONTROL → the WS leg) reddens here.
 */
class HubTransportBaseUrlForTest {

    private val transport = object : HubTransport {
        override val httpBaseUrl: String = "http://rest.example"
        override val wsBaseUrl: String = "ws://ws.example"
        override val httpClient: HttpClient get() = error("unused by baseUrlFor")
        override val wsHttpClient: HttpClient get() = error("unused by baseUrlFor")
        override fun sessionToken(): String? = null
        override fun close() {}
    }

    @Test
    fun defaultBaseUrlFor_mapsClassesToTheirCoarseLeg() {
        assertEquals("http://rest.example", transport.baseUrlFor(StreamClass.CONTROL), "CONTROL rides the REST leg")
        assertEquals("http://rest.example", transport.baseUrlFor(StreamClass.REST), "REST rides the REST leg")
        assertEquals("ws://ws.example", transport.baseUrlFor(StreamClass.AGENT_WS), "AGENT_WS rides the WS leg")
        assertEquals("ws://ws.example", transport.baseUrlFor(StreamClass.SINGLETON_WS), "SINGLETON_WS rides the WS leg")
    }
}
