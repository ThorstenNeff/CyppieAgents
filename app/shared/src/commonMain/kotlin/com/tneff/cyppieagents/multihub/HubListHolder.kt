package com.tneff.cyppieagents.multihub

import com.tneff.cyppieagents.connect.HubDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * CYP-856 — the read-only source of the hub list (seam **S-1** `GET /hubs`). Kept as a NARROW port (only [hubs]) so
 * the display-only switcher can never reach registration or the connect/arming path. **Stub-first:** Slice-1 builds
 * against [StubHubListSource]; the real control-plane-backed source (wrapping `ControlPlaneClient.hubs()`) is the
 * arming-seam wiring landed later (M3), NOT here.
 */
interface HubListSource {
    /** The hubs registered to the signed-in account. **Throws** when the control-plane is unreachable — an honest
     *  error the holder maps to a fail-closed [failHubList], never a silent hang and never a faked empty. */
    suspend fun hubs(): List<HubDescriptor>
}

/**
 * CYP-856 — the **hub-list holder**: owns the [HubListState] and refreshes it from an injected [HubListSource]. The
 * Compose twin of web-ts's `hubListStore` — deliberately DECOUPLED from the active hub's comm/ACL/lifecycle state
 * (its own holder, no coupling), so the switcher's list dimension is independent of the connected workspace.
 *
 * [refresh] maps the source: success → [loadHubList] (loaded, even for zero hubs = an honest empty); any failure →
 * [failHubList] (fail-closed: Error+Retry, never a misleading empty). A [CancellationException] propagates (never
 * swallowed to an error). This holder does display only — it never derives an endpoint or connects.
 */
class HubListHolder(
    private val source: HubListSource,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(HubListState.EMPTY)
    val state: StateFlow<HubListState> = _state.asStateFlow()

    /** (Re)load the hub list from the injected source. Success → loaded (honest-empty possible); failure → fail-closed. */
    fun refresh() {
        scope.launch {
            _state.value = try {
                loadHubList(source.hubs())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Fail-closed: an unreachable CP / decode failure is an HONEST load-error, never a "no hubs" empty.
                failHubList()
            }
        }
    }
}

/**
 * CYP-856 — an in-memory [HubListSource] for the UI scaffold + tests (no control-plane). Returns [descriptors]; when
 * [failing] is true, throws to exercise the fail-closed load-error path. Replaced by the real CP-backed source at the
 * arming seam (M3).
 */
class StubHubListSource(
    var descriptors: List<HubDescriptor> = emptyList(),
    var failing: Boolean = false,
) : HubListSource {
    override suspend fun hubs(): List<HubDescriptor> {
        if (failing) throw IllegalStateException("stub_hub_list_unreachable")
        return descriptors
    }
}
