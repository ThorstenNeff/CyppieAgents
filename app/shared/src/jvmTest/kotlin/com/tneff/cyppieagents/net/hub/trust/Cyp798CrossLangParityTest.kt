package com.tneff.cyppieagents.net.hub.trust

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * CYP-798 — **independent cross-language parity** for the OOB hub-fingerprint (the §3 `OobFingerprintConfirmer`
 * MITM protection: a human console-compares the hub fingerprint). This is the SECOND lens the PL ratification gate
 * ("TS ≡ Kotlin") asked for — deliberately NOT a re-run of Dev's [Cyp798FingerprintGoldenVectorTest] anchor.
 *
 * **Angle (why this is not Dev's tooth again):**
 *  - Dev's anchor proves **Kotlin-derivation ≡ the frozen golden JSON** and (separately) will prove **TS ≡ frozen**.
 *    Transitively that implies TS ≡ Kotlin — but ONLY as long as the frozen file is itself trustworthy. If the
 *    golden JSON and ONE impl ever drift *together* (an edit that updates the file + one side), the "each side vs
 *    the frozen file" pair stays green while the two live derivations have silently diverged — a MITM hole.
 *  - This harness compares the two **live** derivations **directly** — Kotlin [HubFingerprintDisplay] output ==
 *    the TS-emitted derivation — on the SAME inputs, so a co-drift reds here even when it hides from the anchors.
 *    It is the PL statement "TS ≡ Kotlin" as ONE executable assertion, not an inference across two files.
 *
 * **Self-vacuity guard:** [inputSet_matchesRatifiedFreeze] pins that this harness drives EXACTLY the ratified
 * 6-positive + 4-negative vector set (loaded from the vendored freeze, not an inlined subset) — so the parity can
 * never silently shrink to a smaller/greener input set than the one the PL froze.
 *
 * **Wiring (Dev-confirmed 2026-07-22):** the TS derivation is consumed from a fixture on the jvmTest classpath at
 * `/cyp798-ts-derived.json` (`app/shared/src/jvmTest/resources/cyp798-ts-derived.json`), schema:
 *   { "pgpListSha256": "<ts-recomputed hex>",
 *     "positive": { "<name>": { "indices":[…], "words":[…], "hexColon":"…", "qrPayload":"…" }, … },
 *     "negative": { "<name>": null, … } }
 * Until that fixture lands, the three cross-language tests are **RED-until-wired** (they fail with an honest reason,
 * NOT @Ignore'd) — the CYP-798 merge gate is intentionally unsatisfiable until TS≡Kotlin can actually be evaluated.
 * The vendored `/cyp798-golden-vectors.json` is the ratified freeze @ docs/CYP-798-fingerprint-spec `8547dd79`;
 * reconcile to the promoted canonical path when CYP-798 lands.
 *
 * **LOAD-BEARING provenance (Dev, 2026-07-22):** the fixture MUST be Dev's *live* TS output (generated from
 * `deriveHubFingerprint` / `pgpWordTablesChecksum` as an emit step of his TS build), NOT re-derived from the frozen
 * golden vectors. A golden-re-derived fixture would be golden-bound and could not co-drift with the TS impl — which
 * would silently defeat exactly the co-drift this harness exists to catch (Kotlin-vs-fixture would go green while TS
 * had drifted). The emit provenance is Dev's to own; this harness assumes it and cannot itself prove it.
 */
@OptIn(ExperimentalEncodingApi::class)
class Cyp798CrossLangParityTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): String? =
        Cyp798CrossLangParityTest::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }

    private val golden by lazy {
        val text = resource("/cyp798-golden-vectors.json")
            ?: fail("ratified golden vectors missing at /cyp798-golden-vectors.json (vendored freeze @ 8547dd79)")
        json.parseToJsonElement(text).jsonObject
    }

    /** name -> dhPubKey_base64 for the 6 positive vectors (input set only; expected outputs come from the two live derivations). */
    private fun positiveInputs(): Map<String, String> =
        golden["vectors"]!!.jsonObject.mapValues { (_, v) -> v.jsonObject["dhPubKey_base64"]!!.jsonPrimitive.content }

    /** name -> dhPubKey_base64 for the 4 negative cases (all must decode to null on BOTH sides). */
    private fun negativeInputs(): Map<String, String> =
        golden["negative_vectors"]!!.jsonObject["cases"]!!.jsonArray.associate { c ->
            c.jsonObject["name"]!!.jsonPrimitive.content to c.jsonObject["dhPubKey_base64"]!!.jsonPrimitive.content
        }

    /** The TS-emitted derivation, or null when Dev has not landed the emit yet (→ the parity tests red-until-wired). */
    private fun tsDerivedOrRed(): kotlinx.serialization.json.JsonObject {
        val text = resource("/cyp798-ts-derived.json")
            ?: fail(
                "CYP-798 TS derivation fixture absent at /cyp798-ts-derived.json — Dev emits per the agreed schema; " +
                    "this gate is intentionally RED until TS≡Kotlin can be evaluated (not @Ignore'd, so it can never " +
                    "pass as an unwired no-op).",
            )
        return json.parseToJsonElement(text).jsonObject
    }

    // ---- ACTIVE now (no TS dependency): the self-vacuity guard on the ratified input set. -------------------------

    @Test
    fun inputSet_matchesRatifiedFreeze() {
        assertEquals(
            setOf("zeros32", "seq_0..31", "mul7", "mul5p1", "all_0x01", "all_0xFF"),
            positiveInputs().keys,
            "positive input set drifted from the ratified 6-vector freeze — parity would test the wrong set",
        )
        assertEquals(
            setOf("too_short_31B", "too_long_33B", "not_base64", "empty"),
            negativeInputs().keys,
            "negative input set drifted from the ratified 4-case freeze",
        )
    }

    /** Trust anchor for the direct-parity's Kotlin side: the Kotlin derivation is itself real (decodes 32B, derives
     *  11 tokens). Cheap sanity so a green parity can't come from a broken-but-symmetric no-op on both sides. */
    @Test
    fun kotlinSide_isLiveDerivation_notANoOp() {
        for ((name, b64) in positiveInputs()) {
            val raw = Base64.Default.decode(b64)
            assertEquals(32, raw.size, "$name: decodes to 32 raw bytes")
            assertEquals(11, HubFingerprintDisplay.indices(raw, 11).size, "$name: 11 indices")
            assertEquals(11, HubFingerprintDisplay.words(raw, 11).size, "$name: 11 words")
            assertTrue(HubFingerprintDisplay.hex(raw).count { it == ':' } == 31, "$name: 32-byte colon hex")
        }
    }

    // ---- RED-until-wired: the direct Kotlin-live ≡ TS-live parity (the additional lens). -------------------------

    @Test
    fun crossLang_positive_kotlinDerivationEqualsTsDerivation() {
        val ts = tsDerivedOrRed()["positive"]!!.jsonObject
        for ((name, b64) in positiveInputs()) {
            val raw = Base64.Default.decode(b64)
            val tsV = assertNotNull(ts[name]?.jsonObject, "TS derivation missing positive vector '$name'")
            val tsIndices = tsV["indices"]!!.jsonArray.map { it.jsonPrimitive.int }
            val tsWords = tsV["words"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(HubFingerprintDisplay.indices(raw, 11), tsIndices, "$name: indices Kotlin≡TS")
            assertEquals(HubFingerprintDisplay.words(raw, 11), tsWords, "$name: PGP words Kotlin≡TS")
            assertEquals(HubFingerprintDisplay.hex(raw), tsV["hexColon"]!!.jsonPrimitive.content, "$name: hex Kotlin≡TS")
            assertEquals(HubFingerprintDisplay.qrPayload(raw), tsV["qrPayload"]!!.jsonPrimitive.content, "$name: qrPayload Kotlin≡TS")
        }
    }

    @Test
    fun crossLang_negative_bothSidesDecodeToNull() {
        val ts = tsDerivedOrRed()["negative"]!!.jsonObject
        for ((name, b64) in negativeInputs()) {
            // Kotlin fail-closed seam: base64 → exactly 32 bytes else null (the decodePin/PinnedHubStore boundary).
            assertNull(decodePin(b64), "$name: Kotlin decodePin must be null (upstream error, not a fingerprint)")
            assertTrue(ts.containsKey(name), "TS derivation missing negative case '$name'")
            assertTrue(ts[name] is JsonNull, "$name: TS side must also be null (fail-closed parity)")
        }
    }

    @Test
    fun crossLang_pgpWordTableChecksum_kotlinEqualsTs() {
        // The two ported word tables must be byte-identical — a single diverging word breaks OOB equality. Compare the
        // live recompute on BOTH sides directly (not merely each vs the pinned const, which is Dev's anchor's job).
        val kotlinChecksum = Sha256
            .digest((PgpWordList.EVEN.joinToString("\n") + "\n" + PgpWordList.ODD.joinToString("\n")).encodeToByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        // Trust anchor: the Kotlin recompute matches the published const (so the const isn't stale vs the tables).
        assertEquals(PgpWordList.PGP_LIST_SHA256, kotlinChecksum, "Kotlin PGP table checksum drifted from its own const")
        val tsChecksum = tsDerivedOrRed()["pgpListSha256"]!!.jsonPrimitive.content
        assertEquals(kotlinChecksum, tsChecksum, "PGP word-table checksum Kotlin≡TS — the ported tables are not byte-identical")
    }
}
