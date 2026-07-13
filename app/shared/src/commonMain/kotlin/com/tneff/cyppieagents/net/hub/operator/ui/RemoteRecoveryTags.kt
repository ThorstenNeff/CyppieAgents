package com.tneff.cyppieagents.net.hub.operator.ui

/**
 * CYP-479 — testTag contract for the Q6 device-recovery surfaces (CYP-480 Fläche ②, `remote.recovery.*`).
 * Sibling to [OperatorAuthTags] (`remote.authStep.*`) and `RemoteConnectTags` (`remote.connect.*`); DS-frozen +
 * PO-gegenchecked (0 collision @ develop). **Shared API with QA (CYP-7) — coordinate via the PO.**
 *
 * `MULTIDEVICE` is **seam-gated**: the tag exists, but the multi-device recommendation is NOT rendered until
 * the server multi-device-enroll lands (CYP-459/469) — a followable-advice-only-when-real rule (`null≠0`).
 * `ERROR` is a single tag covering both the retryable invalid-code and the terminal exhausted state (the copy
 * carries the distinction — no dynamic qualifier).
 */
object RemoteRecoveryTags {
    /** §3.1 one-time backup-codes reveal container. */
    const val CODES = "remote.recovery.codes"
    const val CODES_LIST = "remote.recovery.codesList"
    const val CODES_COPY = "remote.recovery.codesCopy"

    /** CYP-525 GE7 — the no-central consequence line on the Reveal, ABOVE/before the ack ("without these codes there
     *  is no way back; a central login won't restore access"). The ack is only honest once the stakes are visible. */
    const val CODES_NO_CENTRAL = "remote.recovery.codesNoCentral"

    /** Leave-gate — "I've saved the codes" (friction against loss); no leaving without it (HD). */
    const val CODES_ACK = "remote.recovery.codesAck"

    /** §3.2 recovery-input container (lost device → re-enroll = re-pin). */
    const val START = "remote.recovery.start"
    const val CODE_FIELD = "remote.recovery.codeField"

    /** "A central login alone won't restore access" (HE/Q6) — always shown. */
    const val NO_CENTRAL = "remote.recovery.noCentral"

    /** Multi-device recommendation — **seam-gated, absent** until server multi-device-enroll (②a, CYP-459/469). */
    const val MULTIDEVICE = "remote.recovery.multidevice"

    /** Invalid/used (retryable) OR exhausted (terminal, OOB-at-hub) — copy carries the distinction. */
    const val ERROR = "remote.recovery.error"
}
