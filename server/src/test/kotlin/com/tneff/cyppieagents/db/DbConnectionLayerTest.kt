package com.tneff.cyppieagents.db

import com.tneff.cyppieagents.crypto.MasterKeySource
import com.tneff.cyppieagents.crypto.SecretCipher
import com.tneff.cyppieagents.crypto.SecretCipherException
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.zaxxer.hikari.HikariConfig
import java.io.PrintWriter
import java.nio.file.Files
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CYP-220 Phase 2b — the connection-layer teeth (Design §1). Hermetic: the DSN password uses a real (box)
 * [SecretCipher], and the pools use an injected fake [DataSource] factory (no live Postgres). Proves:
 * password encrypted at rest; **one pool per DSN instance** (shared across stores); rebind → new pool + the
 * orphaned pool evicted/closed; unbound → null (file fallback); the pool config carries the right
 * jdbcUrl/user/DECRYPTED-password.
 */
class DbConnectionLayerTest {

    private fun cipher(): SecretCipher =
        SecretCipherFactory.single(1, MasterKeySource.Box(SecretCipherFactory.newBoxKeyset()))

    private fun dsn(id: String, user: String = "u-$id") =
        DsnDescriptor(id, "label-$id", "db-$id.example", 5432, "app", user, createdBy = "op", createdAt = 1L)

    /** A fake pool that captures its config and tracks close() — no real connection. */
    private class FakeDataSource(val cfg: HikariConfig) : DataSource, AutoCloseable {
        var closed = false; private set
        override fun close() { closed = true }
        override fun getConnection(): Connection = throw UnsupportedOperationException()
        override fun getConnection(username: String?, password: String?): Connection = throw UnsupportedOperationException()
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) {}
        override fun setLoginTimeout(seconds: Int) {}
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = Logger.getGlobal()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }

    // ---- DsnRegistry ----

    @Test fun dsn_roundTrips_andPasswordIsEncryptedAtRest() {
        val f = Files.createTempFile("dsn", ".json").toFile()
        val c = cipher()
        val reg = DsnRegistry(f, c)
        reg.put(dsn("a"), "sup3r-secret-pw")

        val r = reg.resolve("a")!!
        assertEquals("sup3r-secret-pw", r.password)
        assertEquals("jdbc:postgresql://db-a.example:5432/app?sslmode=require", r.descriptor.jdbcUrl())
        // at rest: the plaintext password NEVER appears on disk (only ciphertext)
        assertFalse(f.readText().contains("sup3r-secret-pw"), "password must be encrypted at rest")
        assertEquals(listOf("a"), reg.list().map { it.dsnId })
        assertTrue(reg.remove("a")); assertNull(reg.resolve("a"))
    }

    @Test fun dsn_wrongMasterKey_failsClosed() {
        val f = Files.createTempFile("dsn-wrong", ".json").toFile()
        DsnRegistry(f, cipher()).put(dsn("a"), "pw") // stored under cipher A
        val reopened = DsnRegistry(f, cipher())             // a DIFFERENT master key
        assertFailsWith<SecretCipherException> { reopened.resolve("a") }
    }

    // ---- BindingRegistry ----

    @Test fun binding_bind_rebind_state_perProjectIsolation_persist() {
        val f = Files.createTempFile("bind", ".json").toFile()
        val reg = BindingRegistry(f) { 100L }
        reg.bind("events", "projA", "dsn1")
        reg.bind("events", "projB", "dsn2") // same store, different project → independent
        assertEquals("dsn1", reg.binding("events", "projA")!!.dsnId)
        assertEquals("dsn2", reg.binding("events", "projB")!!.dsnId)
        // rebind projA to a new instance
        reg.bind("events", "projA", "dsn9")
        assertEquals("dsn9", reg.binding("events", "projA")!!.dsnId)
        assertEquals(BindingState.MIGRATING, reg.setState("events", "projA", BindingState.MIGRATING)!!.state)
        // survives reopen
        assertEquals("dsn9", BindingRegistry(f).binding("events", "projA")!!.dsnId)
        assertTrue(reg.unbind("events", "projB")); assertNull(reg.binding("events", "projB"))
    }

    // ---- ConnectionProvider ----

    private fun provider(dsns: DsnRegistry, bindings: BindingRegistry): Pair<ConnectionProvider, MutableList<FakeDataSource>> {
        val created = mutableListOf<FakeDataSource>()
        val cp = ConnectionProvider(dsns, bindings, poolFactory = { cfg -> FakeDataSource(cfg).also { created += it } })
        return cp to created
    }

    @Test fun forStore_buildsPoolWithDecryptedCreds_andUnboundIsNull() {
        val c = cipher()
        val dsns = DsnRegistry(null, c).apply { put(dsn("dsn1", user = "appuser"), "pw-123") }
        val bindings = BindingRegistry(null).apply { bind("events", "projA", "dsn1") }
        val (cp, created) = provider(dsns, bindings)

        assertNull(cp.forStore("avatars", "projA"), "an unbound store → null (file fallback)")
        val ds = cp.forStore("events", "projA") as FakeDataSource
        assertEquals("jdbc:postgresql://db-dsn1.example:5432/app?sslmode=require", ds.cfg.jdbcUrl)
        assertEquals("appuser", ds.cfg.username)
        assertEquals("pw-123", ds.cfg.password, "the pool gets the DECRYPTED password")
        assertEquals(1, created.size)
        cp.close()
    }

    @Test fun onePoolPerInstance_sharedAcrossStores() {
        val c = cipher()
        val dsns = DsnRegistry(null, c).apply { put(dsn("shared"), "pw"); put(dsn("other"), "pw") }
        val bindings = BindingRegistry(null).apply {
            bind("events", "projA", "shared"); bind("avatars", "projA", "shared"); bind("configs", "projA", "other")
        }
        val (cp, created) = provider(dsns, bindings)
        val a = cp.forStore("events", "projA")
        val b = cp.forStore("avatars", "projA")
        val d = cp.forStore("configs", "projA")
        assertSame(a, b, "two stores on the SAME dsn share ONE pool")
        assertNotSame(a, d, "a different dsn → a different pool")
        assertEquals(2, created.size, "exactly 2 pools for 2 distinct instances")
        cp.close()
    }

    @Test fun rebind_switchesPool_andEvictsTheOrphanedOne() {
        val c = cipher()
        val dsns = DsnRegistry(null, c).apply { put(dsn("old"), "pw"); put(dsn("new"), "pw") }
        val bindings = BindingRegistry(null).apply { bind("events", "projA", "old") }
        val (cp, created) = provider(dsns, bindings)

        val oldPool = cp.forStore("events", "projA") as FakeDataSource
        // switch the store to a new instance (the atomic rebind after a migration)
        bindings.bind("events", "projA", "new")
        val newPool = cp.forStore("events", "projA") as FakeDataSource
        assertNotSame(oldPool, newPool, "rebind → the store now resolves to the new instance's pool")
        assertFalse(oldPool.closed, "old pool still open until eviction")

        assertEquals(1, cp.evictUnreferenced(), "the orphaned old pool is evicted")
        assertTrue(oldPool.closed, "orphaned pool closed (drained)")
        assertFalse(newPool.closed)
        assertEquals(0, cp.evictUnreferenced(), "idempotent")
        cp.close()
        assertTrue(newPool.closed, "close() shuts every pool")
    }

    @Test fun forStore_boundToUnknownDsn_failsClosed() {
        val dsns = DsnRegistry(null, cipher()) // empty
        val bindings = BindingRegistry(null).apply { bind("events", "projA", "ghost") }
        val (cp, _) = provider(dsns, bindings)
        assertFailsWith<IllegalStateException> { cp.forStore("events", "projA") }
        cp.close()
    }
}
