package com.tneff.cyppieagents.boot

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.routing.ServiceUnavailableException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-598-C — the SERVER spawn-cause visibility fix. A spawn failure was logged with `e.message` ALONE at the exact
 * lines deploy greps (`LifecycleManager` boot :221 / start-restart :320). For an exception whose message is null (some
 * IOExceptions, an NPE, …) that rendered `failed to boot (null)` — the boot CAUSE (type, stack, cause chain) masked.
 * The fix logs the THROWABLE: `e.toString()` keeps the inline `(<reason>)` always carrying the class (+message when
 * present), and the trailing throwable attaches the full stack. These teeth pin exactly that.
 */
class Cyp598cSpawnCauseVisibilityTest {

    /** A spawn failure with a **null message** — the case that used to render a blank/`(null)` reason. */
    private class NullMessageSpawnBoom : IllegalStateException()

    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    private val logger = LoggerFactory.getLogger("lifecycle") as Logger

    init { logger.addAppender(appender) }

    @AfterTest fun tearDown() { logger.detachAppender(appender) }

    private fun managerWithThrowingSpawn() = LifecycleManager(
        initialWorktrees = mapOf("backend" to "backend"),
        sessions = ConnectorSessions(),
        ensureWorktree = { /* no-op: the worktree is fine; the SPAWN is what fails */ },
        spawn = { _, _ -> throw NullMessageSpawnBoom() },
        // spawnFresh = null → a start failure hits the :320 error log directly (no fresh-retry path)
    )

    private fun bootFailLog(): ILoggingEvent =
        appender.list.single { it.formattedMessage.contains("failed to boot") }

    @Test
    fun bootAgent_nullMessageSpawnThrow_logsThrowableTypeAndStack_notBlank_CYP598C() = runBlocking {
        val ok = managerWithThrowingSpawn().bootAgent("backend")
        assertFalse(ok, "the spawn threw → boot fails (unchanged)")
        val e = bootFailLog()
        // ★ the CAUSE is visible: the exception TYPE is in the inline reason (mutant `e.message` → `(null)` → RED),
        assertTrue(
            e.formattedMessage.contains("NullMessageSpawnBoom"),
            "the boot-cause TYPE is in the log reason, not a blank/(null) message — was: ${e.formattedMessage}",
        )
        // ★ and the full stack is attached as a throwable (mutant with no throwable arg → throwableProxy null → RED).
        assertTrue(e.throwableProxy != null, "the throwable (stack + cause chain) is attached to the boot-fail log")
        assertTrue(e.throwableProxy.className.contains("NullMessageSpawnBoom"), "the attached throwable is the real spawn cause")
    }

    @Test
    fun start_nullMessageSpawnThrow_logsThrowableTypeAndStack_CYP598C() = runBlocking {
        // start() on a never-booted (STOPPED) agent → spawnOrError → doSpawn throws → the :320 error log, then 503.
        assertFailsWith<ServiceUnavailableException> { managerWithThrowingSpawn().start("backend") }
        val e = appender.list.single { it.formattedMessage.contains("failed to start") }
        assertTrue(e.formattedMessage.contains("NullMessageSpawnBoom"), "start-cause TYPE visible in the reason — was: ${e.formattedMessage}")
        assertTrue(e.throwableProxy != null && e.throwableProxy.className.contains("NullMessageSpawnBoom"), "the real throwable is attached to the start-fail log")
    }
}
