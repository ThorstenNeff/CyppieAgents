package com.tneff.cyppieagents.net.hub.operator.vault

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * CYP-542 / B1 — the jvm [PassphraseKdf] actual: **Argon2id** via BouncyCastle `Argon2BytesGenerator` (pure-Java, no
 * native/JNI — PO-ratified vs `argon2-jvm`'s JNI). Derives the 32-byte AES-256 KEK from the operator passphrase +
 * per-install salt at the frozen cost ([Argon2Params.FROZEN] = m≥64 MiB, t≥3, p=1) — the ONLY offline barrier against
 * an exfiltrated vault, so the memory-hard cost must hold (freeze ①; Reviewer verifies the params + usage at code).
 *
 * H-1: the passphrase stays a `CharArray` end-to-end — BC's `generateBytes(char[], …)` encodes it internally (UTF-8),
 * never through a `String`. The caller zeroizes the `CharArray` after use.
 */
class BcArgon2PassphraseKdf : PassphraseKdf {
    override fun deriveKek(passphrase: CharArray, salt: ByteArray, params: Argon2Params): ByteArray {
        val argon = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .build()
        val generator = Argon2BytesGenerator().apply { init(argon) }
        val kek = ByteArray(KEK_LEN)
        generator.generateBytes(passphrase, kek) // char[] overload — no String materialized (H-1)
        return kek
    }

    private companion object {
        const val KEK_LEN = 32 // AES-256
    }
}
