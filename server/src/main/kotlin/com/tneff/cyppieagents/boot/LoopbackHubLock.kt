package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.auth.isLoopbackHost
import java.io.Closeable
import java.net.InetAddress
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CYP-811 (PL-0110 fast-follow) — fail-closed-by-CONSTRUCTION against the on-loopback multi-hub cookie-jar hole.
 *
 * **The hole (§9.5/§9.6):** cookies ignore the PORT, so two hubs on the SAME loopback IP but different ports
 * (`127.0.0.1:8787` and `127.0.0.1:8788`) SHARE the browser cookie jar → an operator's session/CSRF cookie for hub-A
 * is sent to hub-B → cross-hub operator-cookie replay, EVEN on loopback (where S-AAL2b otherwise permits the
 * browser-operator posture). The MVP-light constraint: **single hub per loopback IP** — either one hub per box, or
 * distinct loopback IPs (`127.0.0.1` vs `127.0.0.2` = separate cookie jars).
 *
 * This enforces the constraint at BOOT rather than trusting the operator to honor it: a hub that binds a loopback host
 * takes a box-wide EXCLUSIVE FILE LOCK keyed by its resolved loopback IP. A SECOND hub on the same IP fails to take it
 * → **boot is REJECTED loudly** (never a silent co-existence that opens the replay). Off-loopback hosts are not
 * applicable (S-AAL2b already disables the browser-operator posture there) → `null`, no lock.
 *
 * **CYP-818 B-1 (liveness) — the lock handle MUST outlive [acquireOrReject].** The advisory `FileLock` is held only
 * for as long as its backing `FileChannel` stays open; the JDK's `FileChannelImpl` registers a `Cleaner` that closes
 * the fd (⟹ releases the OS lock) once the channel becomes unreachable. The original caller kept the returned handle
 * in a never-read local `val` — NOT a durable GC root: a local's liveness ends at its LAST USE (the assignment), not
 * at lexical scope-end, so under `.start(wait=true)` the reference is collectable, GC runs, the Cleaner fires, the
 * lock frees, and a 2nd same-IP hub boots → the replay vector reopens. So the acquire path itself now routes the
 * handle to a **process-lifetime GC root** ([retainForProcessLifetime]: an `object` field, rooted by the loaded class
 * for the whole process) and registers a shutdown hook for a clean early release. The caller need not (and does not)
 * hold the handle — retention is a PROPERTY of acquiring, not a caller obligation.
 */
object LoopbackHubLock {

    /** ★ B-1 — the durable GC root. Fields of this `object` are reachable via the loaded class for the whole process,
     *  so a handle placed here can never be collected (⟹ the FileChannel stays open ⟹ the FileLock is held). */
    private val retained = CopyOnWriteArrayList<Closeable>()

    /**
     * Acquire the per-loopback-IP boot lock, or THROW to reject the boot if another hub already holds it.
     * @param retain routes the live lock handle to a process-lifetime GC root (B-1). Defaults to
     *   [retainForProcessLifetime]; tests inject a probe to pin that the acquire path DOES retain (the liveness axis).
     * @return a [Closeable] holding the lock for the process lifetime (loopback host), or `null` (host not loopback →
     *   not applicable). @throws IllegalStateException if a second hub already binds this loopback IP on the box.
     */
    fun acquireOrReject(
        host: String,
        lockDir: Path = Paths.get(System.getProperty("java.io.tmpdir")),
        retain: (Closeable) -> Unit = ::retainForProcessLifetime,
    ): Closeable? {
        if (!isLoopbackHost(host)) return null // off-loopback: browser-operator posture is disabled (S-AAL2b) → n/a
        val ip = InetAddress.getByName(host).hostAddress.replace(':', '_') // ::1 → filesystem-safe
        Files.createDirectories(lockDir)
        val lockFile = lockDir.resolve("cyppie-hub-loopback-$ip.lock")
        val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        // tryLock: another PROCESS holding it → null; the SAME JVM already holding it → OverlappingFileLockException.
        val lock = try { channel.tryLock() } catch (e: OverlappingFileLockException) { null }
        if (lock == null) {
            runCatching { channel.close() }
            throw IllegalStateException(
                "CYP-811 boot REJECTED: another hub already binds loopback ${InetAddress.getByName(host).hostAddress} on " +
                    "this box. Two hubs on the same loopback IP SHARE the browser cookie jar (cookies ignore port, §9.5) → " +
                    "cross-hub operator-cookie replay. Use DISTINCT loopback IPs (127.0.0.1 vs 127.0.0.2) or one hub per box.",
            )
        }
        val handle = Closeable { runCatching { lock.release() }; runCatching { channel.close() } }
        retain(handle) // ★ B-1: WITHOUT this, the handle is GC-collectable → Cleaner frees the lock → 2nd hub boots.
        return handle
    }

    /** ★ B-1 default retention: root the handle for the process lifetime AND release it cleanly on JVM shutdown. The
     *  field alone is sufficient for retention (process-rooted); the shutdown hook is the explicit early release. */
    private fun retainForProcessLifetime(handle: Closeable) {
        rootForProcessLifetime(handle)
        runCatching { Runtime.getRuntime().addShutdownHook(Thread { runCatching { handle.close() } }) }
    }

    /** Place [handle] in the process-lifetime GC root ([retained]). Separated from the shutdown-hook registration so a
     *  test can pin the rooting deterministically without polluting the JVM's shutdown-hook set. */
    internal fun rootForProcessLifetime(handle: Closeable) { retained.add(handle) }

    /** Test probe (B-1 liveness tooth): is [handle] held by the process-lifetime GC root? */
    internal fun isRooted(handle: Closeable): Boolean = retained.contains(handle)

    /** Test hygiene: drop all rooted handles (closing them) so a suite's locks do not leak across cases. */
    internal fun clearRootsForTest() { retained.forEach { runCatching { it.close() } }; retained.clear() }
}
