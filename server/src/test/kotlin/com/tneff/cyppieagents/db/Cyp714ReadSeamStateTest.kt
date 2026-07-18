package com.tneff.cyppieagents.db

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * CYP-714 — the read-seam state default. **Bug tooth.**
 *
 * A [StoreBinding] persisted before the `state` field existed has no `"state"` key. Decoding it straight into
 * the data class applies the default (`ACTIVE`), which makes `PgStoreRouting.activeDataSource` route the legacy
 * binding to Postgres and accept writes — silently losing the CYP-220 migration write-lock for pre-existing
 * data. The fix loads a state-less row fail-closed as `READ_ONLY` at the read seam.
 *
 * MUTATION: revert the init to the plain `decodeFromString<Map<..>>` (default ACTIVE) → the legacy row loads
 * ACTIVE and this test reds. Non-vacuous: the control row proves an explicit ACTIVE is NOT over-coerced, so a
 * green here means "coerced only the state-less row", not "coerces everything".
 */
class Cyp714ReadSeamStateTest {

    @Test
    fun legacyRowWithoutState_loadsReadOnly_notActive_whileExplicitActiveIsPreserved() {
        val dir = createTempDirectory("cyp714").toFile()
        val file = File(dir, "bindings.json")

        // 1. New-format writes — each persists "state":"ACTIVE". projA will be aged; projB is the control.
        val reg1 = FileBindingRegistry(file) { 42L }
        reg1.bind("secretStore", "projA", "dsn-1")
        reg1.bind("secretStore", "projB", "dsn-2")

        // 2. Model a LEGACY row: strip the FIRST "state":"ACTIVE" (projA, bound first → first in the file),
        //    leaving projB's state intact as the control. No NUL is typed here — the map keys (which carry the
        //    NUL separator) are untouched; only the value field is removed.
        val text = file.readText()
        val legacy = text.replaceFirst("\"state\":\"ACTIVE\",", "")
        assertNotEquals(text, legacy, "fixture precondition: a state field was present to strip")
        file.writeText(legacy)

        // 3. Reload through the read seam.
        val reg2 = FileBindingRegistry(file) { 42L }

        // 4. The state-less legacy row must load fail-closed — READ_ONLY, never ACTIVE.
        assertEquals(
            BindingState.READ_ONLY,
            reg2.binding("secretStore", "projA")?.state,
            "CYP-714: a binding with no persisted state must load fail-closed (READ_ONLY), never ACTIVE",
        )
        // 5. A row that DID persist state=ACTIVE is unchanged (no over-coercion).
        assertEquals(
            BindingState.ACTIVE,
            reg2.binding("secretStore", "projB")?.state,
            "a binding with an explicit ACTIVE state is preserved",
        )
    }
}
