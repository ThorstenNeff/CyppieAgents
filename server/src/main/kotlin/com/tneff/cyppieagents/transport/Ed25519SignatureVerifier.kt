package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.model.ExperimentalFederation
import com.tneff.cyppieagents.model.SignatureVerifier
import java.util.Base64

/**
 * CYP-862 (S-Fed-1, DARK — pure crypto, no network) — the real Ed25519 implementation of the CYP-860
 * [SignatureVerifier] seam. Decodes the **standard-base64** raw-32B public key + base64 signature and delegates to
 * [RawKeys.ed25519Verify] (JDK-native EdEC, RFC 8032 — reuse-first, no new crypto dependency; the same primitive the
 * operator-PoP path uses).
 *
 * **Fail-closed:** malformed base64, a wrong-length / non-Ed25519 key, or a bad signature all return `false` — never
 * throw, never a silent `true`. ([RawKeys.ed25519Verify] is itself `runCatching→false`; the outer `runCatching`
 * additionally absorbs a base64-decode failure.)
 *
 * **DARK:** pure verification — it does NOT connect, handshake, or send. It replaces the deferred seam so the CYP-860
 * keyset/rotation logic (and the future `federation-peer` PoP verify) runs against real crypto. Wiring it into a live
 * peer handshake is arming-gated (Epic §9.3 + server re-auth + GO).
 */
@OptIn(ExperimentalFederation::class)
object Ed25519SignatureVerifier : SignatureVerifier {
    override fun verify(pubBase64: String, message: ByteArray, signatureBase64: String): Boolean = runCatching {
        val pub = Base64.getDecoder().decode(pubBase64)
        val sig = Base64.getDecoder().decode(signatureBase64)
        RawKeys.ed25519Verify(pub, message, sig)
    }.getOrDefault(false)
}
