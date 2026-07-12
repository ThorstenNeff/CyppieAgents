package com.tneff.cyppieagents.net.hub.trust

/**
 * CYP-478 — a minimal, dependency-free **SHA-256** (FIPS 180-4) for `commonMain`. There is no shared hash
 * primitive in `:app:shared`/`:core` (the Noise BLAKE2s `h` is internal to the JVM transport), yet the
 * human-comparable hub-key fingerprint ([HubKeyFingerprint]) must be derived identically on every target —
 * Desktop/JVM, Web/JS, Web/Wasm — so it lives here in pure Kotlin, not behind an `expect`/`actual`.
 *
 * This is deliberately NOT a general crypto API: it is used ONLY to fold a 32-byte hub static key into a
 * stable fingerprint for the OOB confirmation. Correctness is pinned by the NIST test vectors ("" and "abc")
 * in [Sha256Test]. All arithmetic is 32-bit `Int` (wrap-around = the spec's mod-2^32).
 */
internal object Sha256 {

    // Round constants K[0..63] (first 32 bits of the fractional parts of the cube roots of the first 64 primes).
    private val K: IntArray = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).let { src -> IntArray(64) { src[it].toInt() } }

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    /** The 32-byte big-endian SHA-256 digest of [message]. */
    fun digest(message: ByteArray): ByteArray {
        // Initial hash values H0..H7 (first 32 bits of the fractional parts of the square roots of the first 8 primes).
        var h0 = 0x6a09e667L.toInt(); var h1 = 0xbb67ae85L.toInt(); var h2 = 0x3c6ef372L.toInt()
        var h3 = 0xa54ff53aL.toInt(); var h4 = 0x510e527fL.toInt(); var h5 = 0x9b05688cL.toInt()
        var h6 = 0x1f83d9abL.toInt(); var h7 = 0x5be0cd19L.toInt()

        // Pre-processing: append 0x80, then zero-pad to 56 mod 64, then the 64-bit big-endian bit length.
        val bitLen = message.size.toLong() * 8L
        var padLen = 56 - (message.size + 1) % 64
        if (padLen < 0) padLen += 64
        val padded = ByteArray(message.size + 1 + padLen + 8)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 1 - i] = (bitLen ushr (8 * i)).toByte()
        }

        val w = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (t in 0 until 16) {
                val j = offset + t * 4
                w[t] = ((padded[j].toInt() and 0xff) shl 24) or
                    ((padded[j + 1].toInt() and 0xff) shl 16) or
                    ((padded[j + 2].toInt() and 0xff) shl 8) or
                    (padded[j + 3].toInt() and 0xff)
            }
            for (t in 16 until 64) {
                val s0 = rotr(w[t - 15], 7) xor rotr(w[t - 15], 18) xor (w[t - 15] ushr 3)
                val s1 = rotr(w[t - 2], 17) xor rotr(w[t - 2], 19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }

            var a = h0; var b = h1; var c = h2; var d = h3
            var e = h4; var f = h5; var g = h6; var h = h7
            for (t in 0 until 64) {
                val bigS1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + bigS1 + ch + K[t] + w[t]
                val bigS0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = bigS0 + maj
                h = g; g = f; f = e; e = d + temp1; d = c; c = b; b = a; a = temp1 + temp2
            }

            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e; h5 += f; h6 += g; h7 += h
            offset += 64
        }

        val out = ByteArray(32)
        intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).forEachIndexed { i, v ->
            out[i * 4] = (v ushr 24).toByte()
            out[i * 4 + 1] = (v ushr 16).toByte()
            out[i * 4 + 2] = (v ushr 8).toByte()
            out[i * 4 + 3] = v.toByte()
        }
        return out
    }
}
