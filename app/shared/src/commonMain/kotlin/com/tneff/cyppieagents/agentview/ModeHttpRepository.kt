package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.ModeChangeOutcome
import com.tneff.cyppieagents.model.ModeChangeRejection
import com.tneff.cyppieagents.model.ModeChangeRequest
import com.tneff.cyppieagents.model.ModeChangeResponse
import com.tneff.cyppieagents.model.TerminalMode
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * CYP-381 (Dev) — the **live** [ModeRepository]: the real HTTP hand-off command against CYP-355's BE-2 motor
 * (`POST /api/agents/{id}/mode`). This is the CYP-381 **stub→real swap** — it replaces [StubModeRepository] as the
 * `AgentShell` default now that the motor is in develop; the VM/UI are unchanged (the non-optimistic confirm path
 * is identical, only the ack is now a real server round-trip instead of a local always-confirm).
 *
 * **Non-optimistic by contract.** The POST is synchronous through the whole transition (IDLE-defer → spawn/attach
 * → settle), so its [ModeChangeResponse] reports the SETTLED outcome. The VM flips its view ONLY on
 * [ModeChangeOutcome.CONFIRMED]; a [ModeChangeOutcome.REJECTED] (a **200** body, per the BE-2 contract) leaves the
 * agent in its prior mode and is surfaced as a [ModeChangeException] the VM maps to its honest error row (§3.4).
 * The fine-grained control state (INTERACTIVE / CONTEXT_LOST / holder / since) still arrives on the CYP-354
 * `/ws/terminal-state` feed — this ack only says "the switch to `target` was accepted"; the client stays a mirror.
 *
 * **Fail-closed.** Only structural failures are non-2xx: 401/403 (auth — the route is operator-gated) and 404
 * (`agent_not_found`). Each maps to a [ModeChangeException] with the server's reason code, so a non-operator or an
 * unknown id can never look like a silent success.
 */
class ModeHttpRepository(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : ModeRepository {

    override suspend fun setMode(agentId: String, target: AgentContentMode): ModeConfirm {
        val response = client.post("$baseUrl/api/agents/$agentId/mode") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CommJson.encodeToString(ModeChangeRequest.serializer(), ModeChangeRequest(target.toTerminalMode())))
        }
        val text = response.bodyAsText()
        ensureSuccess(response, text) // 401/403/404 → ModeChangeException before we try to decode a body that isn't ours
        val settled = CommJson.decodeFromString(ModeChangeResponse.serializer(), text)
        return when (settled.outcome) {
            // CONFIRMED: the mode DID move to `target` (a memory-less hand-back is still CONFIRMED, with the loss
            // carried orthogonally on control.state == CONTEXT_LOST). Adopt the requested target; echo holder/since
            // from the shared CYP-354 control shape for immediate feedback before the feed's next push.
            ModeChangeOutcome.CONFIRMED -> ModeConfirm(
                confirmed = target,
                heldBy = settled.control.heldBy,
                since = settled.control.since,
            )
            // REJECTED is a 200 body — throw so the VM stays in the OLD mode and surfaces the reason (never flips).
            ModeChangeOutcome.REJECTED -> throw ModeChangeException(settled.reason.toCode())
        }
    }

    /** Map a structural non-2xx to the server's `{error:{code,message}}` reason, else by status. */
    private fun ensureSuccess(response: HttpResponse, text: String) {
        if (response.status.isSuccess()) return
        val code = runCatching { CommJson.decodeFromString(ApiErrorBody.serializer(), text).error.code }
            .getOrElse {
                when (response.status.value) {
                    401 -> "unauthorized"
                    403 -> "operator_required"
                    404 -> "agent_not_found"
                    else -> "mode_change_failed"
                }
            }
        throw ModeChangeException(code)
    }
}

/** Client content-mode → the BE-2 request target. The two client modes map 1:1 onto the two stable request poles. */
private fun AgentContentMode.toTerminalMode(): TerminalMode = when (this) {
    AgentContentMode.ORCHESTRATION -> TerminalMode.ORCHESTRATION
    AgentContentMode.TERMINAL -> TerminalMode.TERMINAL
}

/** BE-2 rejection reason → the client reason code the VM surfaces. A null reason (contract: present iff REJECTED)
 *  defensively falls back to the generic code rather than crashing on a malformed body. */
private fun ModeChangeRejection?.toCode(): String = when (this) {
    ModeChangeRejection.BUSY_TIMEOUT -> "agent_busy"
    ModeChangeRejection.SPAWN_FAILED -> "mode_unavailable"
    ModeChangeRejection.ALREADY_IN_TARGET -> "already_in_target"
    ModeChangeRejection.IN_TRANSITION -> "in_transition"
    null -> "mode_change_failed"
}
