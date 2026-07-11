package com.tneff.cyppieagents.workspace

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * CYP-417 (S-G) — the stub [HubCapacitySource] the UI builds against until Backend's ResourceGovernor WARN-event
 * seam lands (S-2). Default = unknown capacity + no rejections (⇒ the readout is absent, no banner) — the honest
 * cold-start. Tests inject scripted capacity/rejection flows.
 */
class StubHubCapacitySource(
    private val capacityFlow: Flow<HubCapacity?> = flowOf(null),
    private val rejectionFlow: Flow<Unit> = emptyFlow(),
) : HubCapacitySource {
    override fun capacity(): Flow<HubCapacity?> = capacityFlow
    override fun rejections(): Flow<Unit> = rejectionFlow
}
