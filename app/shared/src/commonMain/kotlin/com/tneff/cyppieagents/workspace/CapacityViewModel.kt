package com.tneff.cyppieagents.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * CYP-417 (Epic CYP-395 S-G) — the workspace capacity/overload state. **Advisory only (H5)** — the server owns the
 * fail-closed gate; this never pre-disables anything. **Q5 banner lifetime:** a real reject makes the banner
 * visible; it **self-clears** when headroom returns (capacity no longer full), and is **dismissable** anytime (the
 * durable record is the content-free WARN event in the log, not this banner).
 */
class CapacityViewModel(
    source: HubCapacitySource,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope

    /** Latest capacity (`null` = unknown ⇒ the readout is absent). */
    val capacity: StateFlow<HubCapacity?> =
        source.capacity().stateIn(runScope, SharingStarted.Eagerly, null)

    private val _overloadActive = MutableStateFlow(false)
    private val _dismissed = MutableStateFlow(false)

    /** The overload banner is visible ⇔ an active reject that has NOT been dismissed (Q5). */
    val overloadVisible: StateFlow<Boolean> =
        combine(_overloadActive, _dismissed) { active, dismissed -> active && !dismissed }
            .stateIn(runScope, SharingStarted.Eagerly, false)

    init {
        // A real server reject → show the banner (and un-dismiss: a NEW reject re-surfaces even after a prior dismiss).
        runScope.launch {
            source.rejections().collect {
                _overloadActive.value = true
                _dismissed.value = false
            }
        }
        // Headroom returns (capacity known and NOT full) → self-clear (Q5): the machine limit is no longer reached.
        runScope.launch {
            capacity.collect { c ->
                if (c != null && !c.isFull) {
                    _overloadActive.value = false
                    _dismissed.value = false
                }
            }
        }
    }

    /** User acknowledges the banner ("Verstanden") — hides it; the WARN event in the log stays the durable record. */
    fun dismissOverload() { _dismissed.value = true }
}
