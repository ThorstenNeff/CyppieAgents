package com.tneff.cyppieagents.agentview

/**
 * CYP-381 (Dev) — the client seam for the **hand-off command**: request that an agent's terminal-control mode move
 * to [AgentContentMode.TERMINAL] (attach the interactive `claude --resume` session) or back to
 * [AgentContentMode.ORCHESTRATION] (mediated). The command half of the CYP-331 hand-off; the *truth* half is the
 * read-only CYP-354 `/ws/terminal-state` feed ([TerminalControlSource]) that the marker mirrors.
 *
 * **Command + ack, not truth.** [setMode] POSTs `/api/agents/{id}/mode {target}` and returns the server's
 * CONFIRMED result (or throws [ModeChangeException] on reject / failure). The authoritative, fine-grained state
 * (HANDING_OVER/INTERACTIVE/…) still arrives on the CYP-354 feed — this ack only says "the switch to `target` was
 * accepted", so the VM can flip the view **non-optimistically** (only after the confirm, never before). Separating
 * command (this) from truth (the feed) keeps the client a mirror: it never *infers* the mode from its own action.
 *
 * **Real impl (CYP-355 motor merged).** The default is now [ModeHttpRepository] — the live `POST /api/agents/{id}/mode`
 * against the frozen BE-2 DTO ([com.tneff.cyppieagents.model.ModeChangeRequest] / [com.tneff.cyppieagents.model.ModeChangeResponse]).
 * [StubModeRepository] is retained as the **test-only** always-confirm stand-in (and the `:app:webAppDemo` Maestro
 * fixture); production wires the HTTP impl at [com.tneff.cyppieagents.AgentShell]. Tests inject rejecting / holding
 * variants to exercise the reject + IDLE-defer paths without a live server.
 */
interface ModeRepository {
    /**
     * Request the transition to [target] for [agentId]. Returns the server-CONFIRMED [ModeConfirm] on success;
     * **throws** [ModeChangeException] on an explicit reject (e.g. busy/forbidden) or a transport failure — the
     * caller stays in its current mode and surfaces the reason (never an optimistic flip).
     */
    suspend fun setMode(agentId: String, target: AgentContentMode): ModeConfirm
}

/**
 * The server's CONFIRMED hand-off ack. [confirmed] is the mode the server actually moved to (normally == the
 * requested target; the VM adopts THIS, never the drafted request). [heldBy] / [since] echo who now holds the
 * interactive session and since when (epoch-ms) — the same holder-identity the CYP-354 feed carries, surfaced on
 * the ack for immediate feedback before the feed's next push. Both null for a return to mediated / when unknown.
 */
data class ModeConfirm(
    val confirmed: AgentContentMode,
    val heldBy: String? = null,
    val since: Long? = null,
)

/**
 * An explicit hand-off reject or transport failure. [code] is the reason ([ModeHttpRepository] maps it from the
 * CYP-355 DTO): the business rejects `agent_busy` (BUSY_TIMEOUT — the IDLE-gate normally defers before this),
 * `mode_unavailable` (SPAWN_FAILED), `already_in_target`, `in_transition`; the structural failures `operator_required`
 * (403), `unauthorized` (401), `agent_not_found` (404); or the generic `mode_change_failed`. The VM maps any of them
 * to an honest, non-optimistic error surface (stay in the old mode).
 */
class ModeChangeException(val code: String, cause: Throwable? = null) :
    RuntimeException("mode change rejected: $code", cause)

/**
 * The **test-only / demo** offline stand-in for [ModeRepository]: it **confirms the requested target locally** with
 * no server call, routing through the exact non-optimistic confirm path the real [ModeHttpRepository] uses. It
 * claims no hand-off (no holder/since), so a window driven by it shows no marker. Production wires the HTTP impl
 * (see [com.tneff.cyppieagents.AgentShell]); tests inject rejecting / holding variants to exercise reject + IDLE-gate
 * paths without a live server, and `:app:webAppDemo` uses it for the Maestro flows.
 */
class StubModeRepository : ModeRepository {
    override suspend fun setMode(agentId: String, target: AgentContentMode): ModeConfirm =
        ModeConfirm(confirmed = target)
}
