package com.tneff.cyppieagents.controlplane

import com.tneff.cyppieagents.crypto.HubIdentity
import com.tneff.cyppieagents.crypto.HubIdentityProvisioner
import com.tneff.cyppieagents.crypto.MasterKeyCustody
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.crypto.SecretStore
import com.tneff.cyppieagents.crypto.SqliteSecretStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-512 — the env-gated boot factory [buildHubAdmission] is **fail-closed to null (INERT)**: the hub self-admits
 * ONLY when CYPPIE_CP_URL + CYPPIE_CP_OPERATOR_TOKEN + CYPPIE_OPERATOR_ID are ALL set AND local-hub custody exists.
 *
 * ★ Reviewer test-rigor fix: the env-guard teeth build with **custody PRESENT** (real identity/store/file, env
 * complete MINUS the one variable under test) so the env-guard — NOT the custody guard — is what returns null. Were
 * they built with custody=null (the earlier vacuous form), the custody guard would mask an env-guard removal and a
 * regression that drops an env-guard would ship green yet enable a PARTIAL-env live path in prod. Now each
 * `MUT-DROP-<env>-GUARD` reds its own tooth non-vacuously; [fullEnvButNoLocalCustody_isInert] is the custody dimension.
 */
class Cyp512HubAdmissionBootTest {

    private val fullEnv = mapOf(
        "CYPPIE_CP_URL" to "https://cp.test",
        "CYPPIE_CP_OPERATOR_TOKEN" to "op-bearer",
        "CYPPIE_OPERATOR_ID" to "op-1",
    )

    /** Run [block] with REAL local-hub custody (a temp SqliteSecretStore + provisioned HubIdentity + identity file). */
    private fun withCustody(block: (HubIdentity, SecretStore, File) -> Unit) {
        val dir = Files.createTempDirectory("cyp512-boot")
        val file = dir.resolve(".cyppie/hub-identity.json").toFile()
        SqliteSecretStore(dir.resolve("s.db"), MasterKeyCustody { SecretCipherFactory.newBoxKeyset() }).use { store ->
            val id = HubIdentityProvisioner(store, file.toPath()).ensure()
            block(id, store, file)
        }
    }

    private fun build(env: Map<String, String?>, id: HubIdentity?, store: SecretStore?, file: File?) =
        buildHubAdmission(id, store, file, hubName = "hub", hubPort = 8787, env = env::get)

    @Test fun fullEnv_withCustody_buildsLive() = withCustody { id, store, file ->
        // the POSITIVE control — all env + real custody → a live admission runnable (so the env-guard nulls below are meaningful).
        assertNotNull(build(fullEnv, id, store, file), "full env + local custody → the hub self-admits (live runnable)")
    }

    @Test fun missingCpUrl_withCustody_isInert() = withCustody { id, store, file ->
        assertNull(build(fullEnv - "CYPPIE_CP_URL", id, store, file), "no relay URL → INERT (env-guard, not masked by custody)")
    }

    @Test fun missingOperatorToken_withCustody_isInert() = withCustody { id, store, file ->
        assertNull(build(fullEnv - "CYPPIE_CP_OPERATOR_TOKEN", id, store, file), "no operator bearer → INERT")
    }

    @Test fun missingOperatorId_withCustody_isInert() = withCustody { id, store, file ->
        assertNull(build(fullEnv - "CYPPIE_OPERATOR_ID", id, store, file), "no operator id (ownerId claim) → INERT")
    }

    @Test fun fullEnvButNoLocalCustody_isInert() =
        assertNull(build(fullEnv, id = null, store = null, file = null), "full env but absent local-hub custody → INERT (custody dimension)")
}
