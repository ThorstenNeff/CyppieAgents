package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.operator.UserVerification
import com.tneff.cyppieagents.net.hub.operator.UvOutcome
import com.tneff.cyppieagents.net.hub.operator.UvReason
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * CYP-547 — the **1-UV-for-N guard** (M2-A sweep Finding 2 / the prod-wiring-fixture-fidelity pattern), against the
 * **final B1 merge tree** (`batch/CYP-542-b1`: develop + CYP-556 per-connection pool + B1 Vault store-swap).
 *
 * ## The claim under guard
 * A **shared** `CachingUserVerification` → **1 UV prompt for N tunnels** (the dogfood-critical F⑥-1 promise). The
 * existing property tests (`Cyp537PoolUvReuseTest`, the joint `Cyp536JointNTunnelAuthE2eTest`) share the UV **manually**
 * — none drives the REAL `liveRemoteConnectComponentsFactory`. So a refactor that gives the N-tunnel pool its OWN
 * `cachingUv` (or a store-swap splitting the one instance in two) stays green there while breaking 1-UV-for-N in prod.
 *
 * ## What THIS tooth guards (behavioral half — A)
 * It drives the REAL post-B1 factory's shared `CachingUserVerification` — exposed as
 * [RemoteConnectComponents.operatorUvCache] (Dev's B1 seam) — directly with N `verify(OPERATOR_AUTH)` and a
 * **CountingUv** injected via the factory's `rawUserVerification` override seam ⇒ the raw UV prompts **exactly once**
 * (the bounded reuse window caches the other N-1). This is the cheap A-half: it needs NO live CP/relay (the full
 * `authenticate()` can't be driven headlessly — `ClientOperatorAuth` fetches the cpJwt over the network BEFORE the
 * PoP/UV, short-circuiting to Rejected). It guards the **caching property on the real shared instance the session-auth
 * runs through**. **Prod stays INERT** — the `rawUserVerification` prod default is `null` ⇒ the real fail-closed
 * `PassphraseUserVerification`; the CountingUv reaches the cache only through the override seam, never a prod prompt.
 *
 * ## What is NOT in this file (structural half — B, DEFERRED — see the CYP-547 seam note routed to the PO)
 * The pure wiring backstop — `session.authenticator === pool-dialer.authenticator` ⇒ exactly ONE shared cachingUv,
 * which is what catches the **pool-owns-its-own-auth** regression (invisible to the A-half, since A drives only the
 * single EXPOSED instance) — required three `private`→`internal` widenings on the pre-CYP-556 tree. **CYP-556 rebuilt
 * the pool and left `RemoteHubSession.authenticator`, `PooledTunnelSource.dialer` and `NoisePoolTunnelDialer.authenticator`
 * all `private`, with NO public/internal accessor tying the pool to the shared auth.** A pool-SIDE structural assertion
 * inherently needs pool-side visibility, so B cannot be expressed against the final tree's public API without a seam
 * decision (re-widen those 3 fields, or expose the authenticator identity from BOTH sides on RemoteConnectComponents).
 * That decision is flagged to the PO rather than forced; this file ships the A-half now (a real partial guard: it reds
 * if the reuse window is removed/broken). B lands as a follow-on the moment the seam is chosen.
 */
class Cyp547UvWiringGuardTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clients = mutableListOf<HttpClient>()
    private var savedHome: String? = null

    @BeforeTest fun redirectHome() {
        // The post-B1 factory's vault file (`defaultVaultFile()`) + pinned store live under user.home; redirect to a
        // temp dir so this construction-only test never touches the real ~/.cyppie (no vault/key side effect).
        savedHome = System.getProperty("user.home")
        System.setProperty("user.home", Files.createTempDirectory("cyp547-home").toString())
    }

    @AfterTest fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        savedHome?.let { System.setProperty("user.home", it) }
        scope.cancel()
    }

    private fun hub() = HubDescriptor(hubId = "hub-547", name = "n", online = true, defaultPort = 8787, lastSeen = 1L)

    /** Build the REAL post-B1 components with [rawUv] injected via the factory's `rawUserVerification` override seam
     *  (prod default `null` ⇒ the real fail-closed PassphraseUserVerification; override ⇒ this test UV). No network. */
    private fun buildRealComponents(rawUv: UserVerification): RemoteConnectComponents {
        val cp = HttpClient(CIO).also { clients += it }
        val relay = HttpClient(CIO).also { clients += it }
        return liveRemoteConnectComponentsFactory(
            cpBaseUrl = "http://127.0.0.1:1/",          // never connected — construction only
            cpHttpClient = cp,
            operatorToken = { "op-token" },
            relayWsClient = relay,
            rawUserVerification = rawUv,                 // the override seam (prod stays INERT / fail-closed)
        ).create(hub(), scope)
    }

    /** Counts the REAL user-verification prompts (the raw delegate under the shared CachingUserVerification). */
    private class CountingUv : UserVerification {
        val prompts = AtomicInteger()
        override suspend fun verify(reason: UvReason): UvOutcome { prompts.incrementAndGet(); return UvOutcome.Verified }
    }

    @Test
    fun oneUvForN_behavioral_sharedCachingUv_promptsExactlyOnceForN() = runBlocking {
        val counting = CountingUv()
        val components = buildRealComponents(counting)
        val cache = components.operatorUvCache
        assertNotNull(cache, "the live factory exposes the shared CachingUserVerification (operatorUvCache)")

        // ★ N verify() on the ONE shared cachingUv → the raw UV prompts EXACTLY once (the bounded window caches the
        //   other N-1). A broken/removed reuse window would re-prompt each time → prompts == N (the non-vacuity RED,
        //   reproduced by OPERATOR_UV_REUSE_WINDOW_MS → 0 in buildOperatorUvCache).
        val n = 8
        repeat(n) {
            assertEquals(UvOutcome.Verified, cache.verify(UvReason.OPERATOR_AUTH), "verify #$it authorizes")
        }
        assertEquals(
            1, counting.prompts.get(),
            "★ 1 UV for N: $n verify() on the shared cachingUv prompted the raw UV exactly ONCE (F⑥-1)",
        )
    }
}
