package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.net.Backoff
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Drive the VM's INJECTED `backgroundScope` work (init loads / the debounced writable re-fetch) on virtual
 * time — the proven `CommRevokeTerminationTest` idiom. (`advanceUntilIdle()` does not run `backgroundScope`
 * launches here; `advanceTimeBy` + `runCurrent` does, and 1s comfortably clears the 250 ms debounce.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() {
    testScheduler.advanceTimeBy(1_000)
    testScheduler.runCurrent()
}

/**
 * CYP-273 — the client binding of the caller's per-channel write permission. `CommUiState.canWrite` is DERIVED
 * from the server-authoritative writable set (`GET /api/channels/writable` → `List<String>`, the fixed
 * [WritableChannelsApi] shape) and the selected channel, and is **fail-closed**: only an explicit membership
 * enables the composer; an error / not-yet-known writable set is `null` (disabled), never optimistically open.
 * The live `AclEvent` on `/ws/comm` triggers a debounced re-fetch so a revoke/grant takes effect without a
 * restart (S7). The server 403 stays the real enforcement — this only drives the composer's honest state.
 *
 * Money teeth (each mutation-verified):
 *  - derive: in-set → true; readable-but-not-in-set → **false** (known read-only, not null).
 *  - fail-closed: a fetch error leaves `writable=null` → `canWrite=null` (mutate the swallow to `emptyList()` → REDs).
 *  - interim: no wired api → every readable channel writable (no regression until Backend's endpoint lands).
 *  - send() gate: writable → adds the optimistic message; read-only/unknown → blocked (no message).
 *  - live: an AclChanged burst coalesces (debounce) into ONE re-fetch that flips the composer live.
 */
class CommWritabilityTest {

    private class TwoChannelApi : CommApi {
        override suspend fun channels(): List<Channel> = listOf(
            Channel("a", "A", ChannelKind.HUB, listOf("operator")),
            Channel("b", "B", ChannelKind.HUB, listOf("operator")),
        )
        override suspend fun agents(): List<Agent> = emptyList()
        override suspend fun messages(channelId: String, since: Long?): List<Message> = emptyList()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?): Message =
            Message("srv-$channelId", channelId, "operator", body, 9L)
    }

    /** Never-completing live stream → collectLive just suspends (no reconnect churn). */
    private class IdleSource : CommLiveSource {
        override fun events(): Flow<CommLiveEvent> = flow { awaitCancellation() }
    }

    /** A pushable live source so a test can deliver an [CommLiveEvent.AclChanged] on demand (S7 live refetch). */
    private class PushSource : CommLiveSource {
        val flow = MutableSharedFlow<CommLiveEvent>(extraBufferCapacity = 16)
        override fun events(): Flow<CommLiveEvent> = flow
    }

    /** Controllable writable-set seam: [value] mutable so a test can simulate a live revoke; counts [calls]. */
    private class FakeWritable(var value: List<String>, var fail: Boolean = false) : WritableChannelsApi {
        var calls = 0
        override suspend fun writableChannels(): List<String> {
            calls++
            if (fail) throw RuntimeException("writable boom")
            return value
        }
    }

    @Test
    fun writableChannelIsTrue_readableButNotWritableIsFalse_switchReResolves() = runTest {
        val vm = CommViewModel(
            TwoChannelApi(), IdleSource(), viewerId = "operator",
            writableChannels = FakeWritable(listOf("a")), scope = backgroundScope,
        )
        settle()
        assertEquals(true, vm.state.value.canWrite, "auto-selected 'a' is in the writable set → editable")
        vm.select("b")
        settle()
        assertEquals(false, vm.state.value.canWrite, "'b' is readable but NOT writable → known read-only (false), not null")
        vm.select("a")
        settle()
        assertEquals(true, vm.state.value.canWrite, "switching back re-resolves per channel")
    }

    @Test
    fun writableFetchError_failsClosed_writableAndCanWriteNull() = runTest {
        val vm = CommViewModel(
            TwoChannelApi(), IdleSource(), viewerId = "operator",
            writableChannels = FakeWritable(emptyList(), fail = true), scope = backgroundScope,
        )
        settle()
        assertNull(vm.state.value.writable, "a writable fetch error must leave the set null (fail-closed), NOT an empty set")
        assertNull(vm.state.value.canWrite, "unknown writability → canWrite null → composer disabled, no false 'no permission' claim")
    }

    @Test
    fun noWiredApi_interimPosture_everyReadableChannelWritable_noRegression() = runTest {
        val vm = CommViewModel(TwoChannelApi(), IdleSource(), viewerId = "operator", scope = backgroundScope)
        settle()
        assertEquals(setOf("a", "b"), vm.state.value.writable, "interim (endpoint not wired): every readable channel is writable (CYP-17)")
        assertEquals(true, vm.state.value.canWrite, "no regression — the auto-selected channel stays writable")
    }

    @Test
    fun send_isFailClosed_writableAdds_readOnlyAndUnknownBlock() = runTest {
        // writable → the send adds the optimistic message.
        val vmOk = CommViewModel(
            TwoChannelApi(), IdleSource(), viewerId = "operator",
            writableChannels = FakeWritable(listOf("a")), scope = backgroundScope,
        )
        settle()
        vmOk.send("hi")
        settle()
        assertEquals(1, vmOk.state.value.messages.size, "writable channel → send adds the (optimistic→confirmed) message")

        // read-only (empty writable, 'a' selected) → blocked.
        val vmRo = CommViewModel(
            TwoChannelApi(), IdleSource(), viewerId = "operator",
            writableChannels = FakeWritable(emptyList()), scope = backgroundScope,
        )
        settle()
        assertEquals(false, vmRo.state.value.canWrite)
        vmRo.send("blocked")
        settle()
        assertEquals(0, vmRo.state.value.messages.size, "read-only channel → send blocked (no optimistic message)")

        // unknown (fetch error) → blocked.
        val vmUnk = CommViewModel(
            TwoChannelApi(), IdleSource(), viewerId = "operator",
            writableChannels = FakeWritable(emptyList(), fail = true), scope = backgroundScope,
        )
        settle()
        assertNull(vmUnk.state.value.canWrite)
        vmUnk.send("blocked")
        settle()
        assertEquals(0, vmUnk.state.value.messages.size, "unknown writability → send fail-closed (no optimistic message)")
    }

    @Test
    fun aclChange_liveRefetchesWritable_debouncedToOneCall_flipsComposer() = runTest {
        val api = FakeWritable(listOf("a")) // 'a' initially writable
        val source = PushSource()
        val vm = CommViewModel(
            TwoChannelApi(), source, viewerId = "operator",
            writableChannels = api, backoff = Backoff(initialMs = 1, maxMs = 1), scope = backgroundScope,
        )
        settle()
        assertEquals(true, vm.state.value.canWrite, "'a' writable at start")
        val callsAfterInit = api.calls

        // Server revokes write on 'a'; a burst of ACL events arrives on /ws/comm.
        api.value = emptyList()
        source.flow.emit(CommLiveEvent.AclChanged)
        source.flow.emit(CommLiveEvent.AclChanged)
        source.flow.emit(CommLiveEvent.AclChanged)
        settle()

        assertEquals(false, vm.state.value.canWrite, "S7: a live ACL revoke disables the composer without a restart")
        assertEquals(callsAfterInit + 1, api.calls, "debounce: a burst of ACL events coalesces into exactly ONE writable re-fetch")
    }
}
