package com.tneff.cyppieagents.operator

/**
 * CYP-514 — the **single source** of the Noise channel-binding INPUT bytes, in `:core` so the SERVER
 * (`IdentityToken.expectedChannelBinding`) and the CLIENT (`CpJwtProvider`, CYP-496) derive `cb` from ONE definition,
 * eliminating the order/delimiter drift class (previously each side hand-mirrored the concat). The full binding is:
 *
 *   `cb = base64url-no-pad(SHA-256(channelBindingInput(h, hubId)))`
 *
 * The SHA-256 + base64url are applied by each side with its platform primitive — the established `:core` pattern
 * (cf. [operatorAuthChallenge]: canonical bytes in `:core`, hash outside), which avoids a non-trivial multiplatform
 * crypto dependency (SHA-256/base64url over jvm/js/wasmJs/android/ios). A **golden-vector tooth on BOTH sides**
 * (a known `(h, hubId)` → known `cb` string) locks the residual (the base64url-no-pad encoding), which the shared
 * input helper alone does not cover.
 *
 * The input is `h ‖ hubId.utf8` — a **RAW concat, `h` FIRST, NO length-prefix**. Safe (no boundary ambiguity)
 * because `h` is a **fixed 32-byte** Noise handshake hash (BLAKE2s in the pinned suite), so the split point is
 * unambiguous; this matches the merged S-E derivation byte-for-byte (unlike [operatorAuthChallenge], which has
 * variable-length inputs and therefore MUST length-prefix).
 */
fun channelBindingInput(handshakeHash: ByteArray, hubId: String): ByteArray =
    handshakeHash + hubId.encodeToByteArray()
