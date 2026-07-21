package com.tneff.cyppieagents.tier

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-772a — the DISCOVERY tooth that replaces the CYP-770 tautology. It enumerates `@StoreKey`-annotated stores
 * from the COMPILED CLASSES (an independent source, not [StoreResidencies]'s own sets) and asserts every declared
 * key is classified in the inventory. A store whose key drifted out of / was never added to the inventory reddens.
 *
 * The non-vacuity proof is [dummyUnclassifiedStore_isDiscovered_asAViolation]: a deliberately-unclassified dummy
 * store (annotated `@StoreKey("cyp772-unclassified-dummy")`, [UnclassifiedDummyStore] below) MUST be found as a
 * violation. That is the "dummy store without classification → red" acceptance the tautology could never satisfy —
 * it shows the tooth DISCOVERS, it does not merely COMPARE a list to a copy of itself.
 *
 * SCOPE (all 15 classified stores annotated): the 10 USER_DB_CAPABLE (dual-anchor — each key verified against its
 * `PgStoreRouting` binding key, see [userDbCapableStores_declaredKeys_matchThePgStoreRoutingBindingKeys]) + the 5
 * MUST_STAY_HOME-interface stores (single-anchor `∈ inventory`; they route to no user DB, so no binding
 * cross-check exists — boundary labelled). The 4 non-store config LABELS are excluded with a reason
 * ([configOnlyLabels]); the genuinely AMBIGUOUS unclassified stores (MessageStore, read_cursor, TokenUsageStore)
 * are CYP-772b/PL. This test guards every annotated store + the whole inventory, so 772b's additions self-check.
 */
class Cyp772aStoreKeyDiscoveryTest {

    /** Locate a compiled-classes dir by walking up to the repo root, then into the module's build output. */
    private fun classesDir(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("could not locate '$relative' from ${System.getProperty("user.dir")}")
    }

    private val mainClasses get() = classesDir("server/build/classes/kotlin/main")
    private val testClasses get() = classesDir("server/build/classes/kotlin/test")

    /**
     * CYP-772a — the 4 inventory keys that are NOT stores: pure config/toggle labels with no store class, so
     * nothing declares them and they are DELIBERATELY out of the discovery scan's scope. Listed here WITH the
     * reason (a labelled boundary, not a silent gap) so the completeness tooth below can account for the whole
     * inventory: `account` (managed-account row), `migration_audit` (the audit trail itself), `mcp_config`
     * (MCP toggle), `free_fallback_toggle` (the fallback flag). If one of these ever grows a real store, it must
     * be annotated + removed from here — the completeness tooth forces that.
     */
    private val configOnlyLabels = setOf("account", "migration_audit", "mcp_config", "free_fallback_toggle")

    /**
     * COMPLETENESS: every inventory key is accounted for — either DECLARED by a store's `@StoreKey` (15) or a
     * documented [configOnlyLabels] (4). This is what closes the CYP-770 tautology from the other side: a new
     * inventory key added without either a store annotation OR a config-label justification reddens here, instead
     * of silently sitting classified-but-ownerless. The ambiguous UNCLASSIFIED stores (MessageStore, read_cursor,
     * TokenUsageStore) are NOT here — they are not in the inventory yet; classifying them is CYP-772b/PL.
     */
    @Test
    fun everyInventoryKey_isEitherADeclaredStoreOrAKnownConfigLabel() {
        val declared = StoreKeyRegistry.scan(listOf(mainClasses)).map { it.storeKey }.toSet()
        val accountedFor = declared + configOnlyLabels
        assertEquals(
            StoreResidencies.inventory, accountedFor,
            "inventory key(s) with no owner: ${StoreResidencies.inventory - accountedFor} — annotate the store " +
                "or justify as a config label. Stray non-inventory declarations: ${accountedFor - StoreResidencies.inventory}",
        )
    }

    @Test
    fun everyDeclaredStoreKey_isClassifiedInInventory() {
        val declared = StoreKeyRegistry.scan(listOf(mainClasses))
        assertTrue(declared.isNotEmpty(), "sanity: the scan found the annotated stores (the class dir was reachable)")
        val violations = declared.filter { it.storeKey !in StoreResidencies.inventory }
        assertEquals(
            emptyList(), violations,
            "a store DECLARES a residency key that is NOT in StoreResidencies.inventory — classify it (CYP-772b) " +
                "or fix the key: $violations",
        )
    }

    /**
     * The SECOND ANCHOR (CYP-772a, PO): for the USER_DB_CAPABLE stores there is an independent second source of
     * the key — the `windowReason("…")` binding keys in `PgStoreRouting`. The set of declared keys that are
     * user-DB-capable MUST equal that binding-key set. Two independent sources (the `@StoreKey` annotations vs the
     * routing source) have to agree, so a mis-typed annotation on a capable store, or a placement point added
     * without an annotation, reddens here — a cross-check the single `∈ inventory` anchor cannot give.
     *
     * (MUST_STAY_HOME stores have NO such second anchor: 8/9 of their keys are classification-only labels bound
     * by no accessor, so they are guarded by `∈ inventory` alone — see [everyDeclaredStoreKey_isClassifiedInInventory]
     * and the boundary note on [StoreKey].)
     */
    @Test
    fun userDbCapableStores_declaredKeys_matchThePgStoreRoutingBindingKeys() {
        // Independent source B: the binding keys `PgStoreRouting` actually routes through — parsed from its source,
        // the same technique CYP-770's structural invariant uses. (A store routes to a user DB ONLY through one of
        // these accessors, so this set IS the live user-DB-capable key set.)
        val pgSrc = repoFile("server/src/main/kotlin/com/tneff/cyppieagents/boot/PgStoreRouting.kt").readText()
        val bindingKeys = Regex("""windowReason\("(\w+)"""").findAll(pgSrc).map { it.groupValues[1] }.toSet()
        assertTrue(bindingKeys.isNotEmpty(), "sanity: parsed the PgStoreRouting binding keys")

        // Independent source A: the @StoreKey declarations that are user-DB-capable.
        val declaredCapable = StoreKeyRegistry.scan(listOf(mainClasses))
            .map { it.storeKey }.filter { StoreResidencies.isUserDbCapable(it) }.toSet()

        assertEquals(
            bindingKeys, declaredCapable,
            "cross-source parity broke: the user-DB-capable @StoreKey declarations must EXACTLY match the " +
                "PgStoreRouting binding keys. Missing an annotation, or a typo'd/extra one. binding=$bindingKeys declared=$declaredCapable",
        )
    }

    /** Locate a repo-relative FILE by walking up to the repo root (settings.gradle.kts). */
    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return File(dir, relative)
            dir = dir.parentFile
        }
        error("could not locate the repo root from ${System.getProperty("user.dir")}")
    }

    /**
     * The ACCEPTANCE / non-vacuity tooth: the dummy store below is annotated with a key that is deliberately NOT
     * in the inventory. Scanning the TEST classes dir MUST discover it as a violation — proving the tooth would
     * catch a real store someone forgot to classify. If this returns empty, the discovery mechanism is hollow.
     */
    @Test
    fun dummyUnclassifiedStore_isDiscovered_asAViolation() {
        val violations = StoreKeyRegistry.unclassified(listOf(testClasses))
        assertTrue(
            violations.any { it.storeKey == "cyp772-unclassified-dummy" },
            "the discovery tooth must FIND an unclassified annotated store — else it only compares a list to itself; found=$violations",
        )
    }
}

/** CYP-772a non-vacuity fixture: an annotated store whose key is intentionally absent from the inventory. */
@StoreKey("cyp772-unclassified-dummy")
private interface UnclassifiedDummyStore
