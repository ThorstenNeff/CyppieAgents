package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID
import com.tneff.cyppieagents.model.ProjectScope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure project-scoping decision (S12 / CYP-81). Fail-closed / deny-wins, the tenant analogue of
 * the ACL decision: in scope ONLY on an exact, non-blank match. There is no wildcard anywhere.
 */
class ProjectScopeTest {

    @Test
    fun exactMatchPermits() {
        assertTrue(ProjectScope.permits("alpha", "alpha"))
        assertTrue(ProjectScope.permits(DEFAULT_PROJECT_ID, DEFAULT_PROJECT_ID))
    }

    @Test
    fun mismatchDenies() {
        assertFalse(ProjectScope.permits("alpha", "beta"))
    }

    @Test
    fun blankEntityProjectIdDenies() {
        // The dangerous adjacent vector: a missing/blank projectId must DENY, never fall through to
        // global/open. (Mutation: drop the isNotBlank guard / make blank match → this goes red.)
        assertFalse(ProjectScope.permits("", "alpha"))
    }

    @Test
    fun blankActiveProjectDeniesEverything() {
        // A misconfigured (blank) active project also fails closed: a non-blank entity can never
        // equal it, and a blank entity is rejected up front — so nothing is ever in scope.
        assertFalse(ProjectScope.permits("alpha", ""))
        assertFalse(ProjectScope.permits("", ""))
    }

    @Test
    fun defaultProjectIdIsNotAWildcard() {
        // PO-pinned guarantee: DEFAULT_PROJECT_ID is a CONCRETE single-project id, matched exactly
        // like any other — it never behaves as "all/global". So it neither admits another active
        // project nor is admitted by one. If it were ever treated as a wildcard, this goes red.
        assertFalse(ProjectScope.permits(DEFAULT_PROJECT_ID, "alpha"))
        assertFalse(ProjectScope.permits("alpha", DEFAULT_PROJECT_ID))
    }
}
