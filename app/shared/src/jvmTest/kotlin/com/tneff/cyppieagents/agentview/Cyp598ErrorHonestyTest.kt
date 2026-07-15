package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_err_agent_not_found
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_err_generic
import kmpcyppieagents.app.shared.generated.resources.agent_ctl_err_unreachable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-598-A — the **error-collapse** honesty fix for the agent lifecycle-control surface (component A of the
 * live-dogfood-incident triage; the CYP-580 diagnosis-masking class). The incident's "Action failed" was a
 * two-layer masquerade: the VM ([AgentViewModel.lifecycleAction]) flattened EVERY non-HTTP failure — the whole
 * transport class (server unreachable / refused / timeout / TLS / reset) — into the opaque `"lifecycle_failed"`
 * token, and the render ([LifecycleErrorRow]) mapped only 4 codes, so `agent_not_found` (a documented server
 * code) AND that transport token both fell to the generic `else` = "Action failed". An operator could not tell
 * "the server is down" from "no such agent" — both read the same blur.
 *
 * These teeth are **non-vacuous** — each asserts the SPECIFIC, distinct cause the fix surfaces, so a build that
 * re-collapses to the generic message (the pre-fix state) fails:
 *  - a **transport** failure → VM cause `unreachable` + the row reads "Server unreachable" (NOT the generic);
 *  - an **agent_not_found** reject → the row reads "Agent not found" (NOT the generic — the missing render arm);
 *  - an **unknown** server code still honestly falls to the generic (the `else` fallback is preserved).
 * Renders the REAL [AgentWindow] over the VM so the fix is proven end-to-end, not just at the row in isolation.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp598ErrorHonestyTest {

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    /** A lifecycle port whose `start` fails with a caller-chosen [startThrows]; STOPPED snapshot (Start is legal). */
    private class ThrowingStart(private val startThrows: Throwable) : AgentLifecycleApi, AgentLifecycleSource {
        private val events = MutableSharedFlow<AgentLifecycleEvent>(replay = 0, extraBufferCapacity = 8)
        override suspend fun snapshot(): Map<String, AgentLifecycleState> = mapOf("backend" to AgentLifecycleState.STOPPED)
        override fun events(): Flow<AgentLifecycleEvent> = events.asSharedFlow()
        override suspend fun start(agentId: String): Unit = throw startThrows
        override suspend fun stop(agentId: String) {}
        override suspend fun restart(agentId: String) {}
    }

    // A TRANSPORT failure (no HTTP response) is its own `unreachable` cause end-to-end — never the opaque token that
    // collapsed to "Action failed". RED on the pre-fix VM (`?: "lifecycle_failed"`) AND render (generic `else`).
    @Test
    fun transportFailure_surfacesUnreachable_notGenericBlur() = runComposeUiTest {
        val src = ThrowingStart(IOException("connection refused")) // stands for the Ktor/IO transport class (pre-response)
        lateinit var vm: AgentViewModel
        lateinit var unreachable: String
        setContent {
            MaterialTheme {
                unreachable = stringResource(Res.string.agent_ctl_err_unreachable)
                val v = remember {
                    AgentViewModel(emptySession(), agentId = "backend", lifecycle = src, lifecycleSource = src, canControl = true)
                }
                vm = v
                AgentWindow(agentId = "backend", viewModel = v)
            }
        }
        waitForIdle()
        runOnUiThread { vm.start() }
        waitForIdle()
        assertEquals("unreachable", vm.lifecycleError.value, "a transport failure is its OWN cause, not lifecycle_failed")
        onNodeWithTag(AgentViewTags.lifecycleError("backend"), useUnmergedTree = true).assertTextEquals(unreachable)
    }

    // A documented server code `agent_not_found` reads honestly — the previously-missing render arm. The VM already
    // passed HTTP `.code` through (unchanged); the fix is the render arm, so this is RED on the pre-fix render only.
    @Test
    fun agentNotFound_surfacesSpecific_notGenericBlur() = runComposeUiTest {
        val src = ThrowingStart(AgentLifecycleHttpException(status = 404, code = "agent_not_found"))
        lateinit var vm: AgentViewModel
        lateinit var notFound: String
        setContent {
            MaterialTheme {
                notFound = stringResource(Res.string.agent_ctl_err_agent_not_found)
                val v = remember {
                    AgentViewModel(emptySession(), agentId = "backend", lifecycle = src, lifecycleSource = src, canControl = true)
                }
                vm = v
                AgentWindow(agentId = "backend", viewModel = v)
            }
        }
        waitForIdle()
        runOnUiThread { vm.start() }
        waitForIdle()
        assertEquals("agent_not_found", vm.lifecycleError.value)
        onNodeWithTag(AgentViewTags.lifecycleError("backend"), useUnmergedTree = true).assertTextEquals(notFound)
    }

    // Regression pin: a genuinely UNKNOWN server code still falls to the honest generic fallback — the fix adds
    // distinct arms without swallowing the `else` (which remains the right answer for a cause we can't name).
    @Test
    fun unknownServerCode_stillFallsToGeneric_regression() = runComposeUiTest {
        val src = ThrowingStart(AgentLifecycleHttpException(status = 500, code = "some_unmapped_code"))
        lateinit var vm: AgentViewModel
        lateinit var generic: String
        setContent {
            MaterialTheme {
                generic = stringResource(Res.string.agent_ctl_err_generic)
                val v = remember {
                    AgentViewModel(emptySession(), agentId = "backend", lifecycle = src, lifecycleSource = src, canControl = true)
                }
                vm = v
                AgentWindow(agentId = "backend", viewModel = v)
            }
        }
        waitForIdle()
        runOnUiThread { vm.start() }
        waitForIdle()
        onNodeWithTag(AgentViewTags.lifecycleError("backend"), useUnmergedTree = true).assertTextEquals(generic)
    }
}
