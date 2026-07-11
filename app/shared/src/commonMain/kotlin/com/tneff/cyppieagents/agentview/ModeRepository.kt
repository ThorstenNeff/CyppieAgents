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
 * **Provisional shape (⚑ CYP-355 DTO pending).** The BE-2 motor (CYP-355) is not built yet; the exact
 * request/response DTO is being fixed by Backend. This interface is the etablished "build against a stub, swap in
 * the real HTTP impl when the endpoint lands" seam — [StubModeRepository] is the default (a local, always-confirm
 * ack that preserves the live CYP-333 interim view-flip with no server round-trip), replaced by an HTTP impl once
 * the motor + DTO are frozen. Reconcile [ModeConfirm] / [ModeChangeException] with the real DTO at that swap.
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
 * An explicit hand-off reject or transport failure. [code] is the server's reason code (provisional set, to be
 * reconciled with the CYP-355 DTO): `agent_busy` (a turn is in flight — the IDLE-gate normally defers before this),
 * `operator_required` (403), `agent_not_found` (404), `mode_unavailable` (no interactive session possible), or the
 * generic `mode_change_failed`. The VM maps it to an honest, non-optimistic error surface (stay in the old mode).
 */
class ModeChangeException(val code: String, cause: Throwable? = null) :
    RuntimeException("mode change rejected: $code", cause)

/**
 * The default, offline stand-in for [ModeRepository] until the CYP-355 motor lands: it **confirms the requested
 * target locally** with no server call — preserving the live CYP-333 interim behaviour (the toggle flips the
 * content view to the bash worktree shell / back) while routing it through the exact non-optimistic confirm path
 * the real HTTP impl will use. No hand-off is claimed: the mediated session keeps running, the CYP-354 feed stays
 * MEDIATED (so no marker), and the honest "Shell = worktree bash" note still applies. Swapped for the HTTP impl at
 * the CYP-355 real-swap; tests inject rejecting / holding variants to exercise reject + IDLE-gate paths.
 */
class StubModeRepository : ModeRepository {
    override suspend fun setMode(agentId: String, target: AgentContentMode): ModeConfirm =
        ModeConfirm(confirmed = target)
}
