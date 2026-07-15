package com.tneff.cyppieagents.net.hub.operator.vault

import java.security.SecureRandom
import org.slf4j.LoggerFactory

/**
 * CYP-542 / B1 — jvm loading of the bundled **EFF Large Wordlist** (CC-BY 3.0 US, `THIRD-PARTY-LICENSES.md`) for the
 * [DicewareGenerator]. The raw file is `<5-dice-digits>\t<word>` per line; [loadEffLargeWordlist] parses the **word
 * column** (2nd tab field). The [DicewareGenerator] ctor guard then enforces exactly-7776-unique — so a corrupt /
 * truncated / wrong resource makes [defaultDicewareGenerator] return `null` (the enroll UX falls back to
 * type-your-own) rather than generate a low-entropy passphrase. iOS/web loaders = the ② follow-on (CYP-545).
 */
object DicewareResources {
    private const val RESOURCE = "/diceware/eff_large_wordlist.txt"

    fun loadEffLargeWordlist(): List<String> {
        val stream = DicewareResources::class.java.getResourceAsStream(RESOURCE)
            ?: error("EFF diceware wordlist resource missing: $RESOURCE")
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) null else line.substring(tab + 1).trim().ifEmpty { null } // the word column
            }.toList()
        }
    }
}

/** jvm CS-random for the diceware draw (`SecureRandom`, shared instance). */
val secureRandomBytes: SecureBytes = run {
    val rng = SecureRandom()
    SecureBytes { n -> ByteArray(n).also { rng.nextBytes(it) } }
}

/**
 * The bundled-EFF-wordlist diceware generator, or `null` if the resource is missing / fails the exact-7776-unique
 * guard (fail-closed — the enroll flow then offers only type-your-own, never a weak generated default).
 */
private val dicewareLog = LoggerFactory.getLogger("operator.diceware")

fun defaultDicewareGenerator(): DicewareGenerator? =
    runCatching { DicewareGenerator(DicewareResources.loadEffLargeWordlist(), secureRandomBytes) }
        // F2 (silent-swallow fix): the graceful null (→ type-your-own) stays, but a missing/corrupt bundled EFF
        // wordlist must not vanish WITHOUT A TRACE — the one-click strong-passphrase default (the no-hardware
        // security rests on it) is then disabled, and a packaging/bundling regression would otherwise be invisible
        // to operator AND logs. Log it so the loss is diagnosable. (Was a bare `.getOrNull()` — the CYP-575 class.)
        .onFailure {
            dicewareLog.warn(
                "EFF diceware wordlist unavailable ({}) — the one-click strong-passphrase default is DISABLED (enroll falls back to type-your-own only); verify the bundled resource /diceware/eff_large_wordlist.txt is packaged",
                it.message,
            )
        }
        .getOrNull()
