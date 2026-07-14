package com.tneff.cyppieagents.net.hub.relay

import com.tneff.cyppieagents.controlplane.RendezvousResolveResponse
import com.tneff.cyppieagents.controlplane.RendezvousFailure as CoreRendezvousFailure

/**
 * CYP-494 — map the CYP-507 `:core` [RendezvousResolveResponse] onto the client [RendezvousResolution],
 * **FAIL-CLOSED**. The wire type carries `binding` and `failure` as **both nullable with NO exactly-one
 * invariant** (Reviewer note): a malformed / future / buggy CP could send **both-null** or **both-set**. Neither
 * yields a usable dial, so both collapse to [RendezvousResolution.Failed] (a [RelayUnreachable]-toned cause) —
 * **never a half [RendezvousResolution.Bound]** built from a `binding` that arrived alongside a `failure`.
 */
internal fun RendezvousResolveResponse.toResolution(): RendezvousResolution {
    val b = binding
    val f = failure
    return when {
        b != null && f == null -> RendezvousResolution.Bound(b.rendezvousId, b.relayUrl, b.rendezvousIds)
        f != null && b == null -> RendezvousResolution.Failed(f.toUnavailable())
        // both-null OR both-set → the response is not a clean either/or ⇒ fail closed (never a partial Bound).
        else -> RendezvousResolution.Failed(RendezvousUnavailable.RELAY_UNAVAILABLE)
    }
}

private fun CoreRendezvousFailure.toUnavailable(): RendezvousUnavailable = when (this) {
    CoreRendezvousFailure.NOT_REGISTERED -> RendezvousUnavailable.NOT_REGISTERED
    CoreRendezvousFailure.RELAY_UNAVAILABLE -> RendezvousUnavailable.RELAY_UNAVAILABLE
}
