package com.tneff.cyppieagents.connect

/**
 * CYP-419 (S-L) — a scriptable [HubCredentialRepository] stub. [outcome] drives the tri-state so tests exercise
 * validated / invalid / unreachable honestly. Masking mirrors the server contract (`"***" + last4`) — the stub
 * never returns the plaintext key. Submitting always updates the stored **masked** line (hinterlegt), independent
 * of the [outcome] (hinterlegt ≠ validiert — H3).
 */
class StubHubCredentialRepository(
    private val outcome: CredentialValidation = CredentialValidation.VALIDATED,
    initialMasked: String? = null,
) : HubCredentialRepository {

    private var storedMasked: String? = initialMasked

    override suspend fun current(): String? = storedMasked

    override suspend fun submit(apiKey: String): CredentialOutcome {
        val masked = "***" + apiKey.takeLast(4)
        storedMasked = masked // hinterlegt is recorded regardless of the validation outcome (two truths)
        return CredentialOutcome(masked = masked, validation = outcome)
    }
}
