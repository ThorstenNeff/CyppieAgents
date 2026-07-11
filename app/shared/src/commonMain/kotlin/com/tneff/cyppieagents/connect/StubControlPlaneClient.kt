package com.tneff.cyppieagents.connect

/**
 * CYP-419 (S-L) — the stub [ControlPlaneClient] the hubConnect screens build against (the real HTTP client lands
 * in S-J). Scriptable so tests exercise the honest paths: a populated list, the empty state (→ register), and the
 * CP-unreachable error (H5). [unreachable] flips both surfaces to [ControlPlaneUnreachableException].
 */
class StubControlPlaneClient(
    private val hubs: List<HubDescriptor> = emptyList(),
    private val unreachable: Boolean = false,
    /** Fail ONLY [registerHub] (while [hubs] still lists) — exercises the A2 offline-error phase. */
    private val registerFails: Boolean = false,
    private val registerResult: HubRegistration = HubRegistration("hub-stub", "stub-host"),
) : ControlPlaneClient {

    override suspend fun hubs(): List<HubDescriptor> {
        if (unreachable) throw ControlPlaneUnreachableException()
        return hubs
    }

    override suspend fun registerHub(name: String): HubRegistration {
        if (unreachable || registerFails) throw ControlPlaneUnreachableException()
        return registerResult.copy(name = name)
    }
}
