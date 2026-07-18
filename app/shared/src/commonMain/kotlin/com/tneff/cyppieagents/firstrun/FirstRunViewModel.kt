package com.tneff.cyppieagents.firstrun

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * CYP-629 — drives the [FirstRunGate]. Loads the config status from the [FirstRunConfigSource] (stubbed until the
 * §7 seam lands) and exposes it as [status]. Seeds and re-seeds [FirstRunConfigStatus.Unknown] while (re)loading so
 * the gate is **fail-closed** in flight (LOADING, never a stale "done"); a load failure also stays `Unknown` →
 * the gate shows its load/retry surface, never passes through. [reload] backs the retry affordance.
 *
 * §7.3 poll: after the initial fetch, while a clone is **in progress** ([isCloneInProgress]) it re-polls the source
 * every [pollIntervalMs] and stops the instant the status reaches a TERMINAL value ([isTerminalCloneStatus]) — the
 * **ONLY** stop-path. There is deliberately NO timeout and NO attempt-counter: a long clone is not a failure
 * (ux-spec §4.4), so the client NEVER fabricates `CLONE_FAILED`. ([scope] is injectable for virtual-time tests.)
 */
class FirstRunViewModel(
    private val source: FirstRunConfigSource,
    private val pollIntervalMs: Long = 2_000,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _status = MutableStateFlow(FirstRunConfigStatus.Unknown)
    val status: StateFlow<FirstRunConfigStatus> = _status.asStateFlow()

    /** The active load/poll coroutine, tracked so [dispose] can cancel the §7.3 poll loop on teardown. */
    private var loadJob: Job? = null

    init { reload() }

    fun reload() {
        loadJob?.cancel() // a retry supersedes any in-flight poll — never stack two poll loops on the same source
        loadJob = runScope.launch {
            _status.value = FirstRunConfigStatus.Unknown // fail-closed while the status is being (re)fetched
            var s = fetchOnce()
            _status.value = s
            // Poll ONLY while a clone is actively in progress; exit the instant it is terminal. No timeout/counter.
            while (isCloneInProgress(s.cloneStatus)) {
                delay(pollIntervalMs)
                s = fetchOnce()
                _status.value = s
            }
        }
    }

    /**
     * CYP-629 (same class as CYP-443 Slice 3, [[cmp-remember-vm-no-oncleared]]) — the composition-disposal teardown.
     * [FirstRunGate] holds this VM via a plain `remember` (NOT a `ViewModelStore`), so `onCleared` never fires on
     * composition exit — without this, the §7.3 clone poll (a `while (in-progress) delay()` loop) would keep
     * re-fetching **forever** after the gate left composition. The gate's `DisposableEffect { onDispose { dispose() } }`
     * routes that exit here so the poll is cancelled. Idempotent.
     */
    fun dispose() {
        loadJob?.cancel()
        loadJob = null
    }

    private suspend fun fetchOnce(): FirstRunConfigStatus = try {
        source.status()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        FirstRunConfigStatus.Unknown // unreachable / undecodable → stay unknown (gate shows retry, never "done")
    }
}
