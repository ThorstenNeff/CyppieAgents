package com.tneff.cyppieagents.connect

/**
 * CYP-419 (Epic CYP-395 S-L) — the credential-validation tri-state (spec §7 / seam **S-3**). **`UNREACHABLE` ≠
 * `INVALID`** is load-bearing (H3): the user must tell "key is wrong" (error-red) from "Anthropic is down right
 * now" (WARN-amber). `VALIDATED` is INFO, **never** success-green.
 */
enum class CredentialValidation { VALIDATED, INVALID, UNREACHABLE }

/**
 * CYP-419 — outcome of submitting a credential: the server-produced [masked] line (`***<last4>`, never plaintext —
 * H3) plus the separate [validation] truth. "hinterlegt" (the masked line) and "validiert" (this outcome) are two
 * distinct truths and are labelled separately.
 */
data class CredentialOutcome(val masked: String, val validation: CredentialValidation)

/**
 * CYP-419 (S-L) — the Anthropic-credential port for the A3 onboarding step. **Stub-first**
 * ([StubHubCredentialRepository]); S-J wires it to the real `ConfigRepository`/`Secrets.mask()` server contract
 * (masking stays server-side, S-5). The client never masks and never renders plaintext.
 */
interface HubCredentialRepository {
    /** The currently-stored masked credential (`***<last4>`), or `null` when none is set. */
    suspend fun current(): String?

    /** Store [apiKey] (server masks) and run the Anthropic test-call → [CredentialOutcome]. Never returns plaintext. */
    suspend fun submit(apiKey: String): CredentialOutcome
}
