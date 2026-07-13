package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.operator.BACKUP_CODE_COUNT
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-525 H3 (Reviewer) — [isValidCodeSet]: a first-enroll code set is valid iff it is exactly [BACKUP_CODE_COUNT]
 * non-blank codes; any other shape (empty / truncated / over-count / blank) is invalid ⇒ fail-closed (never shown,
 * never acked, no `SavedAck` ⇒ the hub finalizes against nothing the user never had).
 */
class Cyp525CodeSetValidationTest {

    @Test
    fun exactlyExpectedCount_nonBlank_isValid() {
        assertTrue(isValidCodeSet(List(BACKUP_CODE_COUNT) { "code-$it" }))
    }

    @Test
    fun empty_truncated_overCount_orBlank_areInvalid() {
        assertFalse(isValidCodeSet(emptyList()), "empty ⇒ invalid (never ack codes the user never had)")
        assertFalse(isValidCodeSet(List(BACKUP_CODE_COUNT - 1) { "c$it" }), "truncated ⇒ invalid")
        assertFalse(isValidCodeSet(List(BACKUP_CODE_COUNT + 1) { "c$it" }), "over-count ⇒ invalid")
        assertFalse(isValidCodeSet(List(BACKUP_CODE_COUNT) { if (it == 0) "  " else "c$it" }), "a blank entry ⇒ invalid")
    }
}
