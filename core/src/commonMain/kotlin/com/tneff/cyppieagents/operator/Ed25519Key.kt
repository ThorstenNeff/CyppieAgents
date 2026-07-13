package com.tneff.cyppieagents.operator

/**
 * CYP-525 — **single-source Ed25519 public-key encoding** across the whole enroll path
 * (client-encode → wire → hub-persist → RR3-verify). The client's `KeyPair.public.encoded` is **X.509
 * SubjectPublicKeyInfo (SPKI, 44 bytes)**; the hub store + `RawKeys.ed25519Verify` want the **raw 32-byte** key. This
 * is the exact "grün-gebaut-nie-CONNECTED" trap if the two ends disagree — so BOTH ends convert through here.
 *
 * Ratified wire form (PO 2026-07-13): **raw-32B** — the client calls [ed25519SpkiToRaw] before sending; the hub reads
 * raw-32B. [ed25519PublicKeyToRaw] is the hub-side defensive reader (accepts raw-32B **or** a mistaken SPKI-44B) so a
 * client-encoding slip fails LOUD/handled, never as a silent bad-signature. Pure byte ops — no crypto/ASN.1 dependency
 * (commonMain-safe): an Ed25519 SPKI is a FIXED 12-byte prefix followed by the 32-byte raw key.
 */
// RFC 8410 §4: the fixed DER prefix of an Ed25519 SubjectPublicKeyInfo (SEQ, alg id 1.3.101.112, BIT STRING).
private val ED25519_SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

const val ED25519_RAW_LEN = 32
const val ED25519_SPKI_LEN = 44 // 12-byte prefix + 32-byte key

/** X.509 SPKI (44B) → raw 32B. Fail-closed: a wrong length or a non-Ed25519 prefix throws (never a silent truncation). */
fun ed25519SpkiToRaw(spki: ByteArray): ByteArray {
    require(spki.size == ED25519_SPKI_LEN) { "Ed25519 SPKI must be $ED25519_SPKI_LEN bytes, got ${spki.size}" }
    require(spki.copyOfRange(0, ED25519_SPKI_PREFIX.size).contentEquals(ED25519_SPKI_PREFIX)) {
        "not an Ed25519 SubjectPublicKeyInfo (wrong algorithm prefix)"
    }
    return spki.copyOfRange(ED25519_SPKI_PREFIX.size, ED25519_SPKI_LEN)
}

/** Raw 32B → X.509 SPKI (44B) — the inverse of [ed25519SpkiToRaw] (round-trip exact). */
fun ed25519RawToSpki(raw: ByteArray): ByteArray {
    require(raw.size == ED25519_RAW_LEN) { "Ed25519 raw key must be $ED25519_RAW_LEN bytes, got ${raw.size}" }
    return ED25519_SPKI_PREFIX + raw
}

/**
 * Hub-side defensive reader: normalize whatever the wire carried to raw-32B. A raw-32B key passes through; a mistaken
 * SPKI-44B is stripped (so a client that forgot to convert still enrolls, not silently bad-signature); anything else is
 * invalid (fail-closed). The ratified wire form IS raw-32B — this just keeps the encoding boundary robust.
 */
fun ed25519PublicKeyToRaw(encoded: ByteArray): ByteArray = when {
    encoded.size == ED25519_RAW_LEN -> encoded
    encoded.size == ED25519_SPKI_LEN -> ed25519SpkiToRaw(encoded)
    else -> throw IllegalArgumentException("not a valid Ed25519 public key (raw-$ED25519_RAW_LEN or SPKI-$ED25519_SPKI_LEN), got ${encoded.size}B")
}
