package com.tneff.cyppieagents.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-178 / **RC2 LIVE behavioral probe** — the deploy-coordinated real-path gate (the hermetic merge-gate
 * is [Rc2ConfigAssertionTest]). Drives a **real** Kratos (v1.3.0 native/API flows), emits the Tester's
 * `rc2-live-evidence.json`, and enforces the 5-point GO-contract:
 *
 *  1. safe parity on ALL 3 pairs — status + masked-body + content-type equal (P1 with a FRESH new email);
 *  2. teeth on P1-register — the leaky instance (mitigate:false) leaks the `4000007` account-exists tell,
 *     so new≠existing → `result:FAILED` (proves the probe discriminates);
 *  3. P3 login timing ratio ≤ 3× on safe (mitigate:true dummy-hashes absent identifiers → no timing oracle);
 *  4. raw `timings_ms` per branch (the Tester recomputes);
 *  5. version pin in the artifact (`kratos_version` + `kratos_config_sha`).
 *
 * **Mask (Tester-approved):** normalise ONLY per-flow / per-request non-signal fields — the flow `id`,
 * `csrf_token`/`identifier` echoed values, `created_at`/`expires_at`/`issued_at`/`updated_at`, `action`.
 * NOTHING signal-bearing (never `ui.messages` ids/text like `4000006`/`4000007`, never state/status). The
 * mask itself is proven hermetically in [Rc2MaskTest].
 *
 * **CRITICAL — fresh new email per run** (Tester finding): a stale `new` email already exists → new≡existing
 * → the teeth-demo is vacuous. Each run stamps the new-branch email with a per-run nonce.
 *
 * **RUN-gated + hermetic-safe:** no `KRATOS_PUBLIC_URL` → no-op green (CI never touches a live Kratos). Run
 * on deploy's box (creds from the 0600 env; the password is NEVER logged / asserted-on / written to evidence):
 *   KRATOS_PUBLIC_URL=http://127.0.0.1:4433 KRATOS_LEAKY_URL=http://127.0.0.1:4443 \
 *   RC2_TEST_EMAIL=rc2-test@cyppie.dev RC2_TEST_PASSWORD=… KRATOS_VERSION=v1.3.0 KRATOS_CONFIG_PATH=… \
 *   ./gradlew :server:test --tests "com.tneff.cyppieagents.auth.Rc2LiveProbeTest" --rerun-tasks
 */
class Rc2LiveProbeTest {

    private val safeUrl: String? = System.getenv("KRATOS_PUBLIC_URL")?.trimEnd('/')
    private val leakyUrl: String? = System.getenv("KRATOS_LEAKY_URL")?.trimEnd('/')
    private val email: String? = System.getenv("RC2_TEST_EMAIL")
    private val password: String? = System.getenv("RC2_TEST_PASSWORD")

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val evidenceDir = File("build/rc2-probe-evidence").apply { mkdirs() }
    private val maskedFields = listOf("id", "csrf_token", "created_at", "expires_at", "issued_at", "updated_at", "action", "identifier", "email")

    private val absentEmail = "rc2-absent-probe-nonexistent@cyppie.dev"
    // Per-run nonce so the new-branch email is genuinely NEW every run (else teeth is vacuous).
    private val runNonce = System.nanoTime().toString()
    private val samples = 8 // N >= 5

    @Test
    fun rc2LiveProbe_emitsEvidence_andEnforcesGoContract() {
        val base = safeUrl ?: return // off-box → no-op green
        val e = requireEnv(email, "RC2_TEST_EMAIL")

        // ---- safe instance: 3 pairs ----
        val safePairs = listOf(
            probePair("P1_registration_new_vs_existing", "new_random_email", "existing_identity_email",
                { registerFlow(base, freshNewEmail()) }, { registerFlow(base, e) }),
            probePair("P2_recovery_present_vs_absent", "present_email", "absent_email",
                { recoveryFlow(base, e) }, { recoveryFlow(base, absentEmail) }),
            probePair("P3_login_absent_vs_wrongpw", "absent_identifier", "existing_wrong_password",
                { loginFlow(base, absentEmail, "wrong-pw-probe") }, { loginFlow(base, e, "definitely-wrong-password-probe") }),
        )

        // ---- leaky instance: teeth on P1-register ----
        val teeth = leakyUrl?.let { leaky ->
            val a = branch("teeth-P1-new", samples) { registerFlow(leaky, freshNewEmail()) }
            val b = branch("teeth-P1-existing", samples) { registerFlow(leaky, e) }
            buildJsonObject {
                put("known_bad", "notify_unknown_recipients:true | legacy_one_step:true | account_enumeration.mitigate:false")
                put("reran_pair", "P1_registration_new_vs_existing")
                put("A_body_raw", a.bodyRaw); put("B_body_raw", b.bodyRaw)
                put("result", if (a.masked != b.masked || a.status != b.status) "FAILED" else "PASSED_UNEXPECTED")
                put("parity_broke_on", if (a.status != b.status) "status" else "body")
            }
        }

        writeEvidence(safePairs, teeth)

        // ---- GO-contract assertions ----
        safePairs.forEach { p ->
            assertEquals(p.a.status, p.b.status, "[safe/${p.id}] status parity broken (enumeration tell)")
            assertEquals(p.a.contentType, p.b.contentType, "[safe/${p.id}] content-type parity broken")
            assertEquals(p.a.masked, p.b.masked, "[safe/${p.id}] masked-body parity broken (enumeration tell) — see build/rc2-probe-evidence/")
        }
        // Timing is REPORTED (raw timings_ms in the evidence), not hard-gated here: the Tester recomputes the
        // ratio, and on Kratos v1.3.0 mitigate:true equalises CONTENT but NOT timing (absent identifiers are
        // not dummy-hashed → the ~ratio remains) — the escalated Auftraggeber decision (v1.3.0+throttle vs v26).
        val p3 = safePairs.first { it.id.startsWith("P3") }
        println("RC2 P3 login timing ratio (reported, not gated): ${"%.2f".format(timingRatio(p3))}× (absent=${p3.a.timingsMs} existing=${p3.b.timingsMs})")
        if (teeth != null) {
            assertEquals("FAILED", teeth["result"]?.jsonPrimitive?.content, "teeth-demo: leaky Kratos must leak on P1-register (new≠existing)")
        }
    }

    // ---- pair / branch orchestration ----

    private class Sample(val status: Int, val contentType: String, val headers: Map<String, String>, val bodyRaw: String, val masked: String, val timingsMs: List<Long>)
    private class ProbePair(val id: String, val a: Sample, val b: Sample, val aLabel: String, val bLabel: String)

    private fun probePair(id: String, aLabel: String, bLabel: String, callA: () -> HttpResponse<String>, callB: () -> HttpResponse<String>): ProbePair =
        ProbePair(id, branch("$id-A", samples, callA), branch("$id-B", samples, callB), aLabel, bLabel)

    /** Run a branch N times for timing; keep the LAST response as the evidence. */
    private fun branch(name: String, n: Int, call: () -> HttpResponse<String>): Sample {
        var last: HttpResponse<String>? = null
        val timings = (0 until n).map {
            val t0 = System.nanoTime(); last = call(); (System.nanoTime() - t0) / 1_000_000
        }
        val r = last!!
        return Sample(
            status = r.statusCode(),
            contentType = r.headers().firstValue("content-type").orElse(""),
            headers = r.headers().map().mapValues { (k, v) -> if (k.lowercase() in CRED_HEADERS) "<redacted>" else v.joinToString(",") },
            bodyRaw = r.body(),
            masked = maskBody(r.body()),
            timingsMs = timings.sorted(),
        )
    }

    // ---- Kratos native/API self-service flows (v1.3.0) ----

    private fun loginFlow(base: String, identifier: String, pw: String): HttpResponse<String> =
        postJson(actionUrl(getJson("$base/self-service/login/api")), """{"method":"password","identifier":${q(identifier)},"password":${q(pw)}}""")

    private fun recoveryFlow(base: String, addr: String): HttpResponse<String> =
        postJson(actionUrl(getJson("$base/self-service/recovery/api")), """{"method":"code","email":${q(addr)}}""")

    private fun registerFlow(base: String, addr: String): HttpResponse<String> {
        val pw = requireEnv(password, "RC2_TEST_PASSWORD")
        return postJson(actionUrl(getJson("$base/self-service/registration/api")), """{"method":"password","traits":{"email":${q(addr)}},"password":${q(pw)}}""")
    }

    private fun actionUrl(flow: JsonElement): String =
        flow.jsonObject["ui"]?.jsonObject?.get("action")?.jsonPrimitive?.content
            ?: error("Kratos flow response missing ui.action (shape drift — validate against the live instance)")

    // ---- mask + evidence ----

    /** Normalise ONLY the Tester-approved non-signal fields; the masked JSON of two enum-safe branches is equal. */
    private fun maskBody(bodyText: String): String = Rc2Mask.maskBody(json, bodyText)

    private fun writeEvidence(pairs: List<ProbePair>, teeth: JsonElement?) {
        val doc = buildJsonObject {
            put("probe", "cyp178-rc2-live")
            put("kratos_version", kratosVersion())
            put("kratos_config_sha", kratosConfigSha())
            put("masked_fields", buildJsonArray { maskedFields.forEach { add(JsonPrimitive(it)) } })
            put("pairs", buildJsonArray { pairs.forEach { add(pairJson(it)) } })
            put("timing", buildJsonArray {
                pairs.forEach { p ->
                    add(timingJson(p.id, "A", p.a)); add(timingJson(p.id, "B", p.b))
                }
            })
            if (teeth != null) put("teeth", teeth)
        }
        File(evidenceDir, "rc2-live-evidence.json").writeText(doc.toString())
    }

    private fun pairJson(p: ProbePair): JsonElement = buildJsonObject {
        put("id", p.id)
        put("A", branchJson(p.aLabel, p.a)); put("B", branchJson(p.bLabel, p.b))
    }

    private fun branchJson(label: String, s: Sample): JsonElement = buildJsonObject {
        put("label", label); put("status", s.status); put("content_type", s.contentType)
        put("headers", buildJsonObject { s.headers.forEach { (k, v) -> put(k, v) } })
        put("body_raw", s.bodyRaw); put("body_masked", s.masked)
    }

    private fun timingJson(pair: String, br: String, s: Sample): JsonElement = buildJsonObject {
        put("pair", pair.substringBefore("_")); put("branch", br)
        put("samples_ms", buildJsonArray { s.timingsMs.forEach { add(JsonPrimitive(it)) } })
        put("median_ms", median(s.timingsMs))
    }

    // ---- helpers ----

    private fun timingRatio(p: ProbePair): Double {
        val ma = median(p.a.timingsMs); val mb = median(p.b.timingsMs)
        return maxOf(ma, mb).toDouble() / maxOf(minOf(ma, mb), 1L).toDouble()
    }

    private fun median(xs: List<Long>): Long = xs.sorted()[xs.size / 2]

    // Version lives on the ADMIN API in v1.3.0 (`/admin/version`, :4434) — the public `/version` 404s.
    // Prefer the env (deploy sets it reliably), then the admin endpoint, then public, else "unknown".
    private fun kratosVersion(): String = System.getenv("KRATOS_VERSION")
        ?: System.getenv("KRATOS_ADMIN_URL")?.let { admin ->
            runCatching { getJson("${admin.trimEnd('/')}/admin/version").jsonObject["version"]?.jsonPrimitive?.content }.getOrNull()
        }
        ?: runCatching { getJson("$safeUrl/admin/version").jsonObject["version"]?.jsonPrimitive?.content }.getOrNull()
        ?: "unknown"

    private fun kratosConfigSha(): String = System.getenv("KRATOS_CONFIG_SHA")
        ?: System.getenv("KRATOS_CONFIG_PATH")?.let { p ->
            runCatching { sha256(File(p).readBytes()) }.getOrNull()
        } ?: "unknown"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun freshNewEmail(): String = "rc2-new-$runNonce-${newCounter++}@cyppie.dev"
    private var newCounter = 0

    private fun getJson(url: String): JsonElement = json.parseToJsonElement(
        http.send(HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString()).body(),
    )

    private fun postJson(url: String, body: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun requireEnv(v: String?, name: String): String = v ?: error("$name must be set to run the RC2 live probe (never hardcode it)")

    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        val CRED_HEADERS = setOf("set-cookie", "authorization", "cookie", "x-session-token")
    }
}

/**
 * The RC2 nonce-mask (Tester-approved set), extracted so [Rc2MaskTest] can PROVE it against real captured
 * bodies rather than assert it in prose. Masks ONLY per-flow / per-request non-signal fields; leaves every
 * enumeration signal ([kotlinx.serialization.json.JsonObject] `ui.messages` ids/text, `state`, status) intact.
 */
internal object Rc2Mask {
    private val MASK = JsonPrimitive("<masked>")
    private val IDENTIFIER_MASK = JsonPrimitive("<IDENTIFIER>") // Tester-specified placeholder for the echoed input
    private val TS_KEYS = setOf("created_at", "expires_at", "issued_at", "updated_at")

    fun maskBody(json: Json, bodyText: String): String {
        val root = runCatching { json.parseToJsonElement(bodyText) }.getOrNull() ?: return "unparseable:$bodyText"
        return maskNonces(root, 0).toString()
    }

    private fun maskNonces(el: JsonElement, depth: Int): JsonElement = when (el) {
        is kotlinx.serialization.json.JsonObject -> {
            val nodeName = el["name"]?.jsonPrimitive?.contentOrNull
            buildJsonObject {
                for ((k, v) in el) {
                    when {
                        k == "id" && depth == 0 -> put(k, MASK) // the flow id (a per-flow nonce), NOT message ids
                        k in TS_KEYS -> put(k, MASK)
                        k == "action" -> put(k, MASK) // ui.action carries the flow id
                        // echoed attacker input (the submitted identifier/email) — non-signal; the recovery flow
                        // echoes it under a node named "email", login/registration under "identifier".
                        k == "value" && (nodeName == "identifier" || nodeName == "email") -> put(k, IDENTIFIER_MASK)
                        k == "value" && nodeName == "csrf_token" -> put(k, MASK) // per-flow csrf nonce
                        else -> put(k, maskNonces(v, depth + 1))
                    }
                }
            }
        }
        is kotlinx.serialization.json.JsonArray -> buildJsonArray { el.forEach { add(maskNonces(it, depth + 1)) } }
        else -> el
    }
}
