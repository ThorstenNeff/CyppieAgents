package com.tneff.cyppieagents.crossproject

/**
 * In-memory [CrossProjectRepository] for ungated development + hermetic tests (S17) until the backend
 * permit seam lands. Models the directed owner-authorization per channel: [authorize] records the owner
 * consent (a collection — today N=1) and stamps [sharedAtStamp]; [revoke] drops it → immediately
 * project-local (fail-closed, independent of any ACL-entry cleanup). [reachByChannel] supplies the concrete
 * cross-project members a channel would reach (home project + access). [denyWrites] models the operator
 * gate (server 403) for the fail-closed test.
 */
class StubCrossProjectRepository(
    private val reachByChannel: Map<String, List<CrossMember>> = emptyMap(),
    private val denyWrites: String? = null,
    private val sharedAtStamp: Long = 1_700_000_000_000L,
    initiallyShared: Set<String> = emptySet(),
) : CrossProjectRepository {

    /** channelId → the owner-consent collection (1→N-able). Non-empty = shared. */
    private val consents: MutableMap<String, MutableSet<String>> =
        initiallyShared.associateWith { mutableSetOf("operator") }.toMutableMap()

    override suspend fun status(channelId: String): CrossShareStatus {
        val shared = consents[channelId]?.isNotEmpty() == true
        return CrossShareStatus(
            channelId = channelId,
            shared = shared,
            sharedAt = if (shared) sharedAtStamp else null,
            reachableMembers = reachByChannel[channelId] ?: emptyList(),
        )
    }

    override suspend fun authorize(channelId: String): CrossShareStatus {
        denyWrites?.let { throw CrossProjectException(it) }
        // Owner consent recorded in the collection (today just the single operator/owner).
        consents.getOrPut(channelId) { mutableSetOf() }.add("operator")
        return status(channelId)
    }

    override suspend fun revoke(channelId: String): CrossShareStatus {
        denyWrites?.let { throw CrossProjectException(it) }
        consents.remove(channelId) // immediately fail-closed — the authorization is the gate
        return status(channelId)
    }
}
