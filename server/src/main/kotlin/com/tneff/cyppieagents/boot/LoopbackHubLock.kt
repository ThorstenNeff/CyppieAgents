package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.auth.isLoopbackHost
import java.io.Closeable
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CYP-811 / CYP-818 (PL-0110 fast-follow) — fail-closed-by-CONSTRUCTION against the on-loopback multi-hub cookie-jar
 * hole.
 *
 * **The hole (§9.5/§9.6):** cookies ignore the PORT, so two hubs on the SAME loopback IP but different ports
 * (`127.0.0.1:8787` and `127.0.0.1:8788`) SHARE the browser cookie jar → an operator's session/CSRF cookie for hub-A
 * is sent to hub-B → cross-hub operator-cookie replay, EVEN on loopback (where S-AAL2b otherwise permits the
 * browser-operator posture). The MVP-light constraint: **single hub per loopback IP** — either one hub per box, or
 * distinct loopback IPs (`127.0.0.1` vs `127.0.0.2` = separate cookie jars).
 *
 * This enforces the constraint at BOOT rather than trusting the operator to honor it: a hub that binds a loopback host
 * takes a **box-wide exclusive boot lock keyed by its resolved loopback IP**. A SECOND hub on the same IP fails to
 * take it → **boot is REJECTED loudly** (never a silent co-existence that opens the replay). Off-loopback hosts are
 * not applicable (S-AAL2b already disables the browser-operator posture there) → `null`, no lock.
 *
 * **CYP-818 B-2 — the lock is a NETWORK-NAMESPACE sentinel, NOT a `/tmp` FileLock.** The original CYP-811 lock was a
 * `FileLock` under `java.io.tmpdir`. On the shipped `.deb` BOTH systemd units run `PrivateTmp=true` (a private `/tmp`
 * mount namespace per service) + `ProtectSystem=strict` (no shared writable path), so two same-IP hubs put their lock
 * files in SEPARATE namespaces → the lock never contends → both boot (the hole reopens); a host-global *filesystem*
 * path is not writable either. The fix keys the lock on the **network namespace** instead: it binds a fixed sentinel
 * TCP port on the resolved loopback IP. `PrivateTmp` does NOT namespace the network, so two hubs sharing a loopback IP
 * (⟺ sharing the browser cookie jar) share the network namespace and CONTEND on the bind — while a hub on a distinct
 * loopback IP (`127.0.0.2`) binds a different address and co-exists. Contention scope now equals jar-sharing scope by
 * construction, immune to `PrivateTmp`/`ProtectSystem`, and needs no filesystem.
 *
 * **CYP-818 B-1 (liveness) — the lock handle MUST outlive [acquireOrReject].** The bound `ServerSocket` holds the port
 * only while it stays open; the original caller kept the handle in a never-read local `val` — NOT a durable GC root (a
 * local's liveness ends at its LAST USE, not lexical scope-end), so under `.start(wait=true)` it was collectable → the
 * socket closed → the port freed → a 2nd same-IP hub booted. So the acquire path itself routes the handle to a
 * **process-lifetime GC root** ([retainForProcessLifetime]: an `object` field, rooted by the loaded class for the
 * whole process) + a shutdown hook for clean early release. Retention is a PROPERTY of acquiring, not a caller duty.
 *
 * > Deploy sequencing (PL-owned): the acceptance test-twin must move to a DISTINCT loopback IP (`127.0.0.2`) before a
 * > hub carrying this lock deploys — otherwise the twin (still on `127.0.0.1`, prod's IP) is boot-REJECTED here.
 */
object LoopbackHubLock {

    /** The fixed loopback boot-sentinel port. A CONSTANT (all same-IP hubs must collide on it) chosen below the Linux
     *  ephemeral range (32768–60999, so a transient outbound connection can't false-hold it) and clear of the hub /
     *  tunnel ports (8787/8786, 18787/18786). It must NOT be configured as a hub or tunnel port. */
    const val SENTINEL_PORT: Int = 8818

    /** ★ B-1 — the durable GC root. Fields of this `object` are reachable via the loaded class for the whole process,
     *  so a handle placed here can never be collected (⟹ the ServerSocket stays open ⟹ the sentinel port is held). */
    private val retained = CopyOnWriteArrayList<Closeable>()

    /**
     * Acquire the per-loopback-IP boot lock, or THROW to reject the boot if another hub already holds it.
     * @param sentinelPort the loopback port to bind as the lock (defaults to [SENTINEL_PORT]; injectable for tests).
     * @param retain routes the live lock handle to a process-lifetime GC root (B-1). Defaults to
     *   [retainForProcessLifetime]; tests inject a probe to pin that the acquire path DOES retain (the liveness axis).
     * @return a [Closeable] holding the lock for the process lifetime (loopback host), or `null` (host not loopback →
     *   not applicable). @throws IllegalStateException if a second hub already binds this loopback IP on the box.
     */
    fun acquireOrReject(
        host: String,
        sentinelPort: Int = SENTINEL_PORT,
        retain: (Closeable) -> Unit = ::retainForProcessLifetime,
    ): Closeable? {
        if (!isLoopbackHost(host)) return null // off-loopback: browser-operator posture is disabled (S-AAL2b) → n/a
        val ip = InetAddress.getByName(host) // the SPECIFIC loopback address (127.0.0.1 ≠ 127.0.0.2 = distinct jars)
        val socket = ServerSocket()
        // Strict exclusivity: the sentinel NEVER accept()s → no ESTABLISHED connection → no TIME_WAIT on it → a closed
        // sentinel frees its port immediately (no restart false-reject), so reuseAddress=false is safe AND two LIVE
        // hubs on the same loopback IP always conflict on bind (SO_REUSEADDR would not let two live listeners co-bind
        // anyway — that needs SO_REUSEPORT, which we never set).
        socket.reuseAddress = false
        try {
            socket.bind(InetSocketAddress(ip, sentinelPort), 1)
        } catch (e: BindException) {
            runCatching { socket.close() }
            throw IllegalStateException(
                "CYP-818 boot REJECTED: another hub OR a foreign process already binds the loopback boot-sentinel " +
                    "${ip.hostAddress}:$sentinelPort on this box. Two hubs on the same loopback IP SHARE the browser cookie " +
                    "jar (cookies ignore port, §9.5/§9.6) → cross-hub operator-cookie replay. Use DISTINCT loopback IPs " +
                    "(127.0.0.1 vs 127.0.0.2) or one hub per box; if a non-CYPPIE process holds $sentinelPort, free it.",
                e,
            )
        }
        val handle = Closeable { runCatching { socket.close() } }
        retain(handle) // ★ B-1: WITHOUT this, the handle is GC-collectable → the socket closes → the port frees → 2nd hub boots.
        return handle
    }

    /**
     * Boot guard (CYP-818 ops fail-loud): on a loopback host the sentinel binds [sentinelPort] BEFORE `embeddedServer`,
     * so if it collides with the hub or tunnel port the sentinel wins the bind and the hub's OWN connector then fails
     * with a confusing "address already in use" — a misconfig that reads like a mystery. Fail LOUD and CLEAR here
     * instead. Only relevant on a loopback host (off-loopback binds no sentinel → no collision → no-op).
     */
    fun requireSentinelPortFree(host: String, hubPort: Int, tunnelPort: Int, sentinelPort: Int = SENTINEL_PORT) {
        if (!isLoopbackHost(host)) return // off-loopback: no sentinel is bound → the port can't collide with it
        require(hubPort != sentinelPort && tunnelPort != sentinelPort) {
            "CYP-818 misconfig: the loopback boot-sentinel port $sentinelPort must not equal the hub port ($hubPort) or " +
                "tunnel port ($tunnelPort) — the sentinel binds it first, so the hub's own connector would fail to bind. " +
                "Change hub.port / hub.tunnelPort (or LoopbackHubLock.SENTINEL_PORT)."
        }
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
