package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.operator.UvReason
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-542 / B1 — [LivePassphrasePromptCoordinator] teeth (the auth-time UV ↔ dialog bridge, non-render). Pins:
 * prompt suspends + surfaces `Prompting` until [submit]; [cancel] ⇒ `null` (Denied(CANCELLED), fail-closed); the
 * AC-2 pre-arm reuses the just-set enroll passphrase for the first prompt (no re-prompt); state returns to Idle.
 */
class PassphrasePromptCoordinatorTest {

    @Test
    fun prompt_surfacesPrompting_thenSubmitResolves_backToIdle() = runTest {
        val c = LivePassphrasePromptCoordinator()
        val pending = async { c.prompt(UvReason.OPERATOR_AUTH) }
        // let the prompt coroutine reach the await + publish Prompting
        kotlinx.coroutines.yield()
        assertIs<PassphrasePromptState.Prompting>(c.state.value)
        c.submit("my-passphrase".toCharArray())
        assertContentEquals("my-passphrase".toCharArray(), pending.await())
        assertIs<PassphrasePromptState.Idle>(c.state.value, "state returns to Idle after resolve")
    }

    @Test
    fun cancel_resolvesNull_failClosed() = runTest {
        val c = LivePassphrasePromptCoordinator()
        val pending = async { c.prompt(UvReason.OPERATOR_AUTH) }
        kotlinx.coroutines.yield()
        c.cancel()
        assertNull(pending.await(), "cancel ⇒ null ⇒ Denied(CANCELLED), never a hub reject")
        assertIs<PassphrasePromptState.Idle>(c.state.value)
    }

    @Test
    fun preArm_firstPromptReusesIt_noPromptingSurface() = runTest {
        val c = LivePassphrasePromptCoordinator()
        c.preArm("just-enrolled-passphrase".toCharArray()) // AC-2
        val result = c.prompt(UvReason.OPERATOR_AUTH) // resolves immediately from the pre-arm, no submit needed
        assertContentEquals("just-enrolled-passphrase".toCharArray(), result)
        assertIs<PassphrasePromptState.Idle>(c.state.value, "AC-2: no Prompting surfaced — the in-hand passphrase is reused")
        // the pre-arm is one-shot: the NEXT prompt requires a real submit.
        val next = async { c.prompt(UvReason.OPERATOR_AUTH) }
        kotlinx.coroutines.yield()
        assertIs<PassphrasePromptState.Prompting>(c.state.value)
        c.cancel(); assertNull(next.await())
    }

    @Test
    fun clearPreArm_zeroizesAndDropsUnconsumedArm_nextPromptActuallyPrompts() = runTest {
        val c = LivePassphrasePromptCoordinator()
        val secret = "un-consumed-enroll-secret".toCharArray()
        c.preArm(secret)
        c.clearPreArm() // P1: the flow aborts before the first auth prompt ⇒ zeroize + drop the crown-jewel
        assertTrue(secret.all { it == '\u0000' }, "P1: the un-consumed pre-arm is zeroized (NUL), not left to GC")
        // the pre-arm is gone: the next prompt must actually prompt (no auto-resolve), proving it was dropped.
        val pending = async { c.prompt(UvReason.OPERATOR_AUTH) }
        kotlinx.coroutines.yield()
        assertIs<PassphrasePromptState.Prompting>(c.state.value, "P1: a cleared pre-arm does NOT auto-resolve the next prompt")
        c.cancel(); assertNull(pending.await())
    }

    @Test
    fun newPrompt_resolvesStaleWaiterNull_exactlyOne() = runTest {
        val c = LivePassphrasePromptCoordinator()
        val stale = async { c.prompt(UvReason.OPERATOR_AUTH) }
        kotlinx.coroutines.yield()
        val fresh = async { c.prompt(UvReason.OPERATOR_AUTH) } // a new prompt supersedes the stale one
        kotlinx.coroutines.yield()
        assertNull(stale.await(), "the stale waiter is resolved null (fail-closed) — exactly-one prompt in flight")
        c.submit("x".toCharArray())
        assertTrue(fresh.await() != null)
    }
}
