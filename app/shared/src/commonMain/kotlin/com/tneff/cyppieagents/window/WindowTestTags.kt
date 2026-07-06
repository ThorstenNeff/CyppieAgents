package com.tneff.cyppieagents.window

/**
 * Centralized `Modifier.testTag` values for the window manager, so UI tests (Compose UI-test and
 * Maestro via semantics) have a single, stable vocabulary to address windows.
 *
 * Conforms to the finalized Test-Contract (CYP-7, `docs/TEST-CONTRACT.md` §2): prefixless
 * `<area>[.<scopeId>].<element>` with `window` instance-scoped by agent id
 * (`window.<id>.titlebar`, `window.<id>.resizeHandle`). Should the contract ever introduce a global
 * `cyp.` prefix, this object is the only place that changes.
 */
object WindowTestTags {
    /** The window host / desktop surface that lays out all floating windows. */
    const val HOST: String = "window.host"

    /** The "fit windows" host affordance — a user-triggered one-shot re-tile (CYP-26 §2.3). */
    const val FIT: String = "window.host.fit"

    // CYP-250: the desktop empty-state — shown on the canvas background ONLY while the active project has
    // 0 AGENTS (managedAgents.isEmpty(), NOT 0 windows — the tool windows always coexist). String values are
    // the `window.host.empty.*` family from the tags contract (CYP-7 re-sync with this slice).
    /** Empty-state container (0-agent desktop) — presence == "0-agent empty-state visible". */
    const val EMPTY: String = "window.host.empty"

    /** Empty-state primary CTA ("add agent") — routes into the existing openAdd flow; operator-gated (`enabled`). */
    const val EMPTY_ADD_BTN: String = "window.host.empty.addBtn"

    /** Empty-state operator-gate hint (non-operator only) — reused `workspace_operator_only`. */
    const val EMPTY_GATE_HINT: String = "window.host.empty.gateHint"

    /** Root of the floating window with the given [id]. */
    fun window(id: String): String = "window.$id"

    /** Draggable title bar of the window with the given [id]. */
    fun titleBar(id: String): String = "window.$id.titlebar"

    /** Bottom-end resize grip of the window with the given [id]. */
    fun resizeHandle(id: String): String = "window.$id.resizeHandle"

    /** Content slot of the window with the given [id] (where CYP-6's renderer plugs in). */
    fun content(id: String): String = "window.$id.content"

    /** CYP-211: the titlebar ⋮ settings button of the window with the given [id] (opens its settings panel). */
    fun settings(id: String): String = "window.$id.settings"
}
