package com.tneff.cyppieagents.window

/**
 * Centralized `Modifier.testTag` values for the window manager, so UI tests (Compose UI-test and
 * Maestro via semantics) have a single, stable vocabulary to address windows.
 *
 * PROVISIONAL: these follow the short `<area>.<element>` form the coordinator specified (`window.*`).
 * The cross-team Test-Contract (CYP-7) is still being consolidated and may settle on a
 * `cyp.`-prefixed form (`cyp.window.*`). If it does, this object is the only place that changes.
 */
object WindowTestTags {
    /** The window host / desktop surface that lays out all floating windows. */
    const val HOST: String = "window.host"

    /** Root of the floating window with the given [id]. */
    fun window(id: String): String = "window.$id"

    /** Draggable title bar of the window with the given [id]. */
    fun titleBar(id: String): String = "window.$id.titlebar"

    /** Bottom-end resize grip of the window with the given [id]. */
    fun resizeHandle(id: String): String = "window.$id.resize"

    /** Content slot of the window with the given [id] (where CYP-6's renderer plugs in). */
    fun content(id: String): String = "window.$id.content"
}
