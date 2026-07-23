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
 */
object LoopbackHubLock {

    /**
     * Acquire the per-loopback-IP boot lock, or THROW to reject the boot if another hub already holds it.
     * @return a [Closeable] holding the lock for the process lifetime (loopback host), or `null` (host not loopback →
     *   not applicable). @throws IllegalStateException if a second hub already binds this loopback IP on the box.
     */
    fun acquireOrReject(host: String, lockDir: Path = Paths.get(System.getProperty("java.io.tmpdir"))): Closeable? {
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
        return Closeable { runCatching { lock.release() }; runCatching { channel.close() } }
    }
}
