// CYP-798 — byte-identical TypeScript port of the OOB hub-fingerprint derivation (:core HubFingerprintDisplay.kt +
// PgpWordList.kt, CYP-482). It MUST reproduce the Kotlin golden vectors: a divergent derivation silently defeats the
// §3 OobFingerprintConfirmer console-compare (a MITM hole), so this is security-load-bearing.
//
// SHA-256 via async crypto.subtle (browser + node-vitest safe; no sync hash in the tree). Fail-closed decode mirrors
// :core PinnedHubStore.decodePin: base64 -> exactly 32 bytes, else null (never a partial / attacker-shaped key).
import { EVEN, ODD } from './pgpWordList'
import { base64ToBytes, bytesToBase64 } from '../terminal/base64'

export const QR_SCHEME = 'cyppie-hub-key'
export const FINGERPRINT_TOKEN_COUNT = 11
const HUB_STATIC_KEY_SIZE = 32

export interface HubFingerprint {
  /** The first 11 digest bytes as indices 0..255 (the content-independent word mapping). */
  readonly indices: readonly number[]
  /** token[i] = (i even ? EVEN : ODD)[byte] — the primary, security-bearing OOB word sequence. */
  readonly words: readonly string[]
  /** Colon-separated LOWERCASE hex of the full 32-byte SHA-256 digest (secondary, copyable). */
  readonly hex: string
  /** `${QR_SCHEME}:${base64(rawKey)}` — the raw key, NOT the digest (a scanner re-derives). */
  readonly qrPayload: string
}

/**
 * Fail-closed decode of a hub descriptor's `dhPubKey` (base64) to its raw 32 bytes — mirrors :core `decodePin`.
 * A ≠32-byte / non-base64 / empty value is an UPSTREAM error (invalid hub-descriptor, CYP-798 §4b), NOT a trust
 * reject: it returns `null`, never a fabricated key. This is the client-distinguishable malformed seam.
 */
export function decodeHubKey(dhPubKeyB64: string | null | undefined): Uint8Array | null {
  if (!dhPubKeyB64) return null
  let bytes: Uint8Array
  try {
    bytes = base64ToBytes(dhPubKeyB64)
  } catch {
    return null
  }
  return bytes.length === HUB_STATIC_KEY_SIZE ? bytes : null
}

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  const digest = await crypto.subtle.digest('SHA-256', bytes as unknown as ArrayBuffer)
  return new Uint8Array(digest)
}

function colonHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join(':')
}

/**
 * The full OOB fingerprint from a hub descriptor's `dhPubKey`. Fail-closed: an invalid key → `null` (the malformed
 * upstream path, distinct from a trust reject). All three forms fold the SAME SHA-256 digest → word/hex/QR agree.
 */
export async function deriveHubFingerprint(
  dhPubKeyB64: string | null | undefined,
): Promise<HubFingerprint | null> {
  const raw = decodeHubKey(dhPubKeyB64)
  if (raw === null) return null
  const digest = await sha256(raw)
  const indices = Array.from(digest.slice(0, FINGERPRINT_TOKEN_COUNT))
  const words = indices.map((b, i) => (i % 2 === 0 ? EVEN : ODD)[b])
  return {
    indices,
    words,
    hex: colonHex(digest),
    qrPayload: `${QR_SCHEME}:${bytesToBase64(raw)}`,
  }
}

/**
 * The PGP word-table freeze checksum, RECOMPUTED over THESE TS tables (never a copied literal — else vacuous; a
 * single ported-word drift must break OOB equality). `SHA-256(EVEN.join('\n') + '\n' + ODD.join('\n'))`, plain
 * lowercase hex. The test asserts it == the :core `PGP_LIST_SHA256` pin.
 */
export async function pgpWordTablesChecksum(): Promise<string> {
  const joined = EVEN.join('\n') + '\n' + ODD.join('\n')
  const digest = await sha256(new TextEncoder().encode(joined))
  return Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('')
}
