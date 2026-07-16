package com.tneff.cyppieagents.firstrun

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * CYP-629 — drives the [FirstRunGate]. Loads the config status from the [FirstRunConfigSource] (stubbed until the
 * §7 seam lands) and exposes it as [status]. Seeds and re-seeds [FirstRunConfigStatus.Unknown] while (re)loading so
 * the gate is **fail-closed** in flight (LOADING, never a stale "done"); a load failure also stays `Unknown` →
 * the gate shows its load/retry surface, never passes through. [reload] backs the retry affordance.
 */
class FirstRunViewModel(private val source: FirstRunConfigSource) : ViewModel() {

    private val _status = MutableStateFlow(FirstRunConfigStatus.Unknown)
    val status: StateFlow<FirstRunConfigStatus> = _status.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _status.value = FirstRunConfigStatus.Unknown // fail-closed while the status is being (re)fetched
            _status.value = try {
                source.status()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                FirstRunConfigStatus.Unknown // unreachable / undecodable → stay unknown (gate shows retry, never "done")
            }
        }
    }
}
