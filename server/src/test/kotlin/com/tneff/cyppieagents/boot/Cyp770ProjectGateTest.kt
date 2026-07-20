package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.db.BindingRegistry
import com.tneff.cyppieagents.db.BindingState
import com.tneff.cyppieagents.crypto.MasterKeySource
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.db.ConnectionProvider
import com.tneff.cyppieagents.db.DsnRegistry
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.routing.ConflictException
import com.tneff.cyppieagents.tier.StoreResidencies
import com.tneff.cyppieagents.tier.StoreResidency
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-770 ① — the two ungated `userDbCapable` stores, handled OPPOSITE ways because they are not the same bug:
 *
 *  - **`project`** has a real Pg impl ([PgProjectRegistry]) → the missing placement point was a real gap: the
 *    migrator opens a MIGRATING window on any key, but with no accessor consulting it, the registry's writes
 *    were never frozen during the copy `A → B` (Finding B, on the project registry itself). Gate added.
 *  - **`avatar_blob`** has NO Pg impl → no path could ever route it, so a gate would have protected a
 *    non-existent path. De-classified instead (the CYP-769 dead-protection lesson).
 *
 * The window teeth carry their own **counter-proof**: each frozen mutation is paired with the SAME call on an
 * unbound registry, which must succeed. Without that pair, "it throws" could equally mean the registry is
 * simply broken, and the freeze tooth would be green for the wrong reason.
 */
class Cyp770ProjectGateTest {

    /** A real ConnectionProvider over an EMPTY DsnRegistry — `forStore` resolves to null, i.e. the file
     *  fallback, without stubbing the final class. No Postgres is needed: the window path returns before
     *  `activeDataSource` is ever consulted. */
    private fun noConnections(bindings: BindingRegistry): ConnectionProvider =
        ConnectionProvider(DsnRegistry(null, SecretCipherFactory.single(1, MasterKeySource.Box(SecretCipherFactory.newBoxKeyset()))), bindings)

    private fun registry(dir: File): BindingRegistry = BindingRegistry(File(dir, "b.json"), clock = { 1L })

    private fun fileRegistry(dir: File): ProjectRegistry =
        ProjectRegistry(File(dir, "projects.json"), seedProjectId = "default")

    private fun routed(dir: File, bindings: BindingRegistry): ProjectRegistry =
        PgStoreRouting.projectRegistry(
            projectId = "default",
            bindings = bindings,
            connections = noConnections(bindings),
            seedProjectId = "default",
            seedProjectName = "Default",
            fileFallback = { fileRegistry(dir) },
        )

    // ---- the gap that existed: `project` in a window ----

    /**
     * The money-tooth. In a migration window the accessor hands out the gate and EVERY registry mutation is
     * rejected 409 — paired with the counter-proof that the identical calls succeed when unbound.
     *
     * Mutation: drop the `windowReason("project", …)` line from [PgStoreRouting.projectRegistry] (i.e. restore
     * the pre-CYP-770 state) → the live registry is handed out, the writes go through, and this reddens.
     */
    @Test
    fun projectRegistry_inMigrationWindow_freezesEveryMutation_butPassesWhenUnbound() {
        val dir = Files.createTempDirectory("cyp770-window").toFile()
        try {
            // ── counter-proof FIRST: unbound → a live registry, the very same mutations succeed ──
            val open = routed(dir, registry(dir))
            assertIs<FileProjectRegistry>(open, "unbound → the live file registry, not a gate")
            open.create(CreateProjectRequest("beta", "Beta"))
            assertTrue(open.exists("beta"), "counter-proof: create works when NOT in a window")
            open.rename("beta", "Beta2")
            open.setActive("beta")

            // ── now open a window on `project` and re-resolve ──
            val bindings = registry(dir).apply {
                bind("project", "default", "dsn-1")
                setState("project", "default", BindingState.MIGRATING)
            }
            val gated = routed(dir, bindings)
            assertIs<MigrationGatedProjectRegistry>(gated, "MIGRATING → the write-freeze gate, not a live registry")

            assertEquals("store_migrating", assertFailsWith<ConflictException> { gated.create(CreateProjectRequest("g", "G")) }.code)
            assertEquals("store_migrating", assertFailsWith<ConflictException> { gated.rename("default", "X") }.code)
            assertEquals("store_migrating", assertFailsWith<ConflictException> { gated.setActive("default") }.code)
            assertEquals("store_migrating", assertFailsWith<ConflictException> { gated.drop("default") }.code)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Reads stay served from source A throughout the window — the freeze is on writes only, never reads. */
    @Test
    fun projectRegistry_inWindow_readsStillServedFromSourceA() {
        val dir = Files.createTempDirectory("cyp770-reads").toFile()
        try {
            routed(dir, registry(dir)).create(CreateProjectRequest("gamma", "Gamma"))
            val bindings = registry(dir).apply {
                bind("project", "default", "dsn-1")
                setState("project", "default", BindingState.READ_ONLY)
            }
            val gated = routed(dir, bindings)
            assertTrue(gated.exists("gamma"), "reads pass through to source A during the window")
            assertTrue(gated.projects().any { it.id == "gamma" })
            assertEquals("default", gated.activeProjectId())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** CYP-720 interop: a LEGACY-caused lock on `project` surfaces the action-required code, not the migration one. */
    @Test
    fun projectRegistry_legacyLockedBinding_surfacesTheUnevaluatedCode() {
        val dir = Files.createTempDirectory("cyp770-legacy").toFile()
        try {
            // A legacy row = one the real registry wrote, with the `state` field stripped — exactly what a
            // pre-`state` row looks like. GENERATED rather than hand-written, so the key separator stays
            // whatever the registry actually uses and no literal control character lands in this source file.
            registry(dir).bind("project", "default", "d")
            val f = File(dir, "b.json")
            f.writeText(f.readText().replace(Regex(""","state":"[A-Z_]+""""), ""))
            val gated = routed(dir, BindingRegistry(File(dir, "b.json"), clock = { 1L }))
            assertIs<MigrationGatedProjectRegistry>(gated)
            assertEquals(
                "store_binding_unevaluated",
                assertFailsWith<ConflictException> { gated.create(CreateProjectRequest("z", "Z")) }.code,
                "a legacy binding is not migrating — the operator must act, not wait",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- the other half: avatar_blob de-classified ----

    /**
     * `avatar_blob` is no longer offloadable — and is still CLASSIFIED (in the inventory), not merely unknown.
     * The distinction is the point: an unclassified store is safe only via the fail-closed default, i.e.
     * unnoticed-safe. Mutation: put the key back into `userDbCapable` → this reddens.
     */
    @Test
    fun avatarBlob_isNotOffloadable_butStillClassified() {
        assertFalse(StoreResidencies.isUserDbCapable("avatar_blob"), "no PgAvatarBlobStore exists → not offloadable")
        assertEquals(StoreResidency.MUST_STAY_HOME, StoreResidencies.of("avatar_blob"))
        assertTrue("avatar_blob" in StoreResidencies.inventory, "still classified — never left to the fail-closed default")
    }

    /**
     * The structural invariant this ticket exists to protect: **every offloadable store has a write-freeze
     * placement point**. Derived from the code, not from a hand-copied list — `userDbCapable` on one side, the
     * `windowReason(...)` call sites in PgStoreRouting.kt on the other. This is what would have caught both
     * `project` and `avatar_blob` on the day they were classified.
     */
    @Test
    fun everyUserDbCapableStore_hasAMigrationPlacementPoint() {
        val gated = Regex("""windowReason\("(\w+)"""")
            .findAll(File("src/main/kotlin/com/tneff/cyppieagents/boot/PgStoreRouting.kt").readText())
            .map { it.groupValues[1] }.toSet()
        assertTrue(gated.isNotEmpty(), "sanity: the accessor source was found and parsed")
        val ungated = StoreResidencies.inventory.filter { StoreResidencies.isUserDbCapable(it) } - gated
        assertEquals(
            emptySet(), ungated.toSet(),
            "every offloadable store needs a PgStoreRouting write-freeze gate — ungated: $ungated",
        )
    }
}
