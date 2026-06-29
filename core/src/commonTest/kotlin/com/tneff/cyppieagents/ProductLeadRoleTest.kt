package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals

/** CYP-98: the PRODUCT_LEAD role is additive on the `:core` wire (Doc 09). Posture is enforced server-side. */
class ProductLeadRoleTest {

    private inline fun <reified T> roundTrip(value: T): T =
        CommJson.decodeFromString(CommJson.encodeToString(value))

    @Test
    fun roleProductLeadWirePinned() {
        assertEquals("\"PRODUCT_LEAD\"", CommJson.encodeToString(Role.PRODUCT_LEAD))
        assertEquals(Role.PRODUCT_LEAD, CommJson.decodeFromString<Role>("\"PRODUCT_LEAD\""))
    }

    @Test
    fun agentWithProductLeadRoleRoundTrips() {
        val a = Agent(id = "pl", name = "Product Lead", role = Role.PRODUCT_LEAD, worktree = "pl")
        assertEquals(a, roundTrip(a))
        assertEquals(Role.PRODUCT_LEAD, roundTrip(a).role)
    }

    @Test
    fun additive_existingRolesUnaffected() {
        assertEquals(Role.PO, CommJson.decodeFromString<Role>("\"PO\""))
        assertEquals(Role.WORKER, CommJson.decodeFromString<Role>("\"WORKER\""))
        assertEquals(3, Role.entries.size)
    }
}
