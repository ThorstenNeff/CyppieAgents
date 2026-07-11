package com.tneff.cyppieagents.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.EdECPrivateKey
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.EdECPoint
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * CYP-441 (S-C / F2) — **raw 32-byte** Ed25519 + X25519 key primitives on the JDK-native providers (JDK ≥15 EdEC /
 * ≥11 XDH; **reuse-first**, no new crypto dependency). The RAW format is the whole point: Phase-2 Noise_NK
 * (`noise-java`, confirmed by Dev's CR1 spike) consumes the X25519 static as raw bytes, and the registration/PoP
 * anchor travels as raw public bytes — a keystore-encoded (X.509/PKCS8) blob would break that anchor. So this
 * module extracts/round-trips exactly the RFC-native encodings:
 *  - **X25519 public** = the u-coordinate, 32-byte **little-endian** (RFC 7748 §5).
 *  - **X25519 private** = the 32-byte scalar (`XECPrivateKey.getScalar()`).
 *  - **Ed25519 public** = the compressed point: y little-endian (32B) with the x sign bit in the MSB of byte 31
 *    (RFC 8032 §5.1.2).
 *  - **Ed25519 private** = the 32-byte seed (`EdECPrivateKey.getBytes()`).
 *
 * Everything is length-checked to 32 bytes; a malformed raw key fails closed ([IllegalArgumentException]).
 */
object RawKeys {

    const val RAW_LEN = 32

    // ---- Ed25519 (signing / PoP) ----

    /** Generate a fresh Ed25519 keypair and return its raw seed + raw compressed public point (both 32 bytes). */
    fun generateEd25519(): RawKeyPair {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val seed = (kp.private as EdECPrivateKey).bytes.orElseThrow {
            SecretCipherException("Ed25519 private key exposed no seed bytes")
        }
        val pub = encodeEd25519Public((kp.public as EdECPublicKey).point)
        return RawKeyPair(privateRaw = require32(seed, "Ed25519 seed"), publicRaw = pub)
    }

    /** Sign [message] with a raw Ed25519 [seed] (32-byte). Deterministic (RFC 8032). */
    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("Ed25519")
            .generatePrivate(EdECPrivateKeySpec(NamedParameterSpec.ED25519, require32(seed, "Ed25519 seed")))
        return Signature.getInstance("Ed25519").run {
            initSign(key); update(message); sign()
        }
    }

    /** Verify an Ed25519 [signature] over [message] under a raw 32-byte [publicRaw]. False on any failure. */
    fun ed25519Verify(publicRaw: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
        val pub = KeyFactory.getInstance("Ed25519")
            .generatePublic(EdECPublicKeySpec(NamedParameterSpec.ED25519, decodeEd25519Public(publicRaw)))
        Signature.getInstance("Ed25519").run { initVerify(pub); update(message); verify(signature) }
    }.getOrDefault(false)

    // ---- X25519 (Noise static / DH) ----

    /** Generate a fresh X25519 keypair; the public is the raw little-endian u-coordinate (Noise-ready static). */
    fun generateX25519(): RawKeyPair {
        val kp = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val scalar = (kp.private as XECPrivateKey).scalar.orElseThrow {
            SecretCipherException("X25519 private key exposed no scalar bytes")
        }
        val pub = toLittleEndian((kp.public as XECPublicKey).u, RAW_LEN) // RFC 7748 u-coordinate, LE
        return RawKeyPair(privateRaw = require32(scalar, "X25519 scalar"), publicRaw = pub)
    }

    /** X25519 Diffie-Hellman: raw 32-byte [scalar] × peer raw 32-byte [peerPublicRaw] → 32-byte shared secret. */
    fun x25519Dh(scalar: ByteArray, peerPublicRaw: ByteArray): ByteArray {
        val priv = KeyFactory.getInstance("X25519")
            .generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, require32(scalar, "X25519 scalar")))
        val pub = KeyFactory.getInstance("X25519")
            .generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, fromLittleEndian(require32(peerPublicRaw, "X25519 peer public"))))
        return KeyAgreement.getInstance("X25519").run { init(priv); doPhase(pub, true); generateSecret() }
    }

    // ---- raw encodings (RFC 8032 / RFC 7748) ----

    /** RFC 8032 §5.1.2: y as 32-byte LE, with the x sign bit (isXOdd) in the MSB of the final byte. */
    internal fun encodeEd25519Public(point: EdECPoint): ByteArray {
        val out = toLittleEndian(point.y, RAW_LEN) // y < 2^255, so bit 255 (the MSB of byte 31) is free
        if (point.isXOdd) out[RAW_LEN - 1] = (out[RAW_LEN - 1].toInt() or 0x80).toByte()
        return out
    }

    /** Inverse of [encodeEd25519Public]: split the sign bit back out of the MSB, decode y from the low 255 bits. */
    internal fun decodeEd25519Public(raw: ByteArray): EdECPoint {
        require32(raw, "Ed25519 public")
        val bytes = raw.copyOf()
        val xOdd = (bytes[RAW_LEN - 1].toInt() and 0x80) != 0
        bytes[RAW_LEN - 1] = (bytes[RAW_LEN - 1].toInt() and 0x7F).toByte() // clear the sign bit → pure y
        return EdECPoint(xOdd, fromLittleEndian(bytes))
    }

    /** A non-negative [v] as a fixed [len]-byte little-endian array (fails closed if it doesn't fit). */
    internal fun toLittleEndian(v: BigInteger, len: Int): ByteArray {
        require(v.signum() >= 0) { "negative value has no unsigned LE encoding" }
        val be = v.toByteArray() // big-endian two's-complement; positive values may carry a leading 0x00 sign byte
        val out = ByteArray(len)
        var bi = be.size - 1 // least-significant byte first
        var oi = 0
        while (bi >= 0 && oi < len) {
            out[oi] = be[bi]; oi++; bi--
        }
        // Any remaining big-endian bytes must be zero (else v exceeds len bytes) — fail closed.
        while (bi >= 0) {
            require(be[bi].toInt() == 0) { "value does not fit in $len bytes" }
            bi--
        }
        return out
    }

    /** A fixed little-endian byte array back to a non-negative BigInteger. */
    internal fun fromLittleEndian(le: ByteArray): BigInteger = BigInteger(1, le.reversedArray())

    private fun require32(bytes: ByteArray, what: String): ByteArray {
        require(bytes.size == RAW_LEN) { "$what must be $RAW_LEN raw bytes, got ${bytes.size}" }
        return bytes
    }
}

/** A raw keypair: both halves in the RFC-native 32-byte format (Noise/PoP-ready). Private is sensitive — never log. */
class RawKeyPair(val privateRaw: ByteArray, val publicRaw: ByteArray) {
    // Identity equality only — value equality on secret bytes would be misleading + non-constant-time.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
    override fun toString(): String = "RawKeyPair(public=${publicRaw.size}B, private=<redacted>)"
}
