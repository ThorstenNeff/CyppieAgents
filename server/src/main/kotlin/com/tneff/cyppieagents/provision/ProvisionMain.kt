package com.tneff.cyppieagents.provision

import com.tneff.cyppieagents.crypto.SecretCipherFactory
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * CYP-628 — the install-time **provisioning** entrypoint the Windows install wizard invokes (packaged as a second
 * app-image launcher, `CyppieHubProvision`, via jpackage `--add-launcher` — CYP-626). It mints, ON THE HOST at install
 * time (NEVER in the `.msi` — the installer ships zero secrets), everything the hub needs to boot standalone:
 *
 *  1. **`CYPPIE_MASTER_KEY`** — a Tink AES-256-GCM keyset ([SecretCipherFactory.newBoxKeyset]). This is the one secret
 *     that *cannot* be produced by a shell/PowerShell wizard (it needs Tink), which is exactly why provisioning lives
 *     here. It is the KEK that encrypts the ANTHROPIC_API_KEY at rest (entered later via the first-run operator GUI).
 *  2. **`OPERATOR_TOKEN` + `HUB_TOKEN_<PO>`** — the required boot tokens (`Secrets.fromEnv` throws without them);
 *     secure-random, no format constraint.
 *  3. A default **secret-free `platform.config.json`** — 1 PO agent (a boot invariant), loopback bind by default.
 *
 * Outputs: the config → `<dataDir>/platform.config.json`; the secrets → `--secrets-out` as a `KEY=VALUE` file
 * (owner-only perms) the wizard reads to set the **service account's ACL-protected environment**, then securely
 * DELETES. Secret VALUES are never printed to stdout — only a non-secret summary. Fail-closed: any write error aborts.
 */
fun main(args: Array<String>) {
    val opts = Options.parse(args)
    val dataDir = File(opts.dataDir).apply { mkdirs() }

    // (1) the master keyset — this IS CYPPIE_MASTER_KEY (box-local; treat as any master key, never a user DB).
    //     MINIFIED to a SINGLE LINE: the serialized Tink JSON is pretty-printed, but a multi-line value is hostile to
    //     env transport (a WinSW <env>, a service-account env var, a KEY=VALUE file) — the wizard's provisioning would
    //     break on the newline. Stripping structural whitespace (newlines) yields compact JSON that parseKeyset still
    //     accepts (JSON whitespace is insignificant; the base64 key material has no internal newlines).
    val masterKey = SecretCipherFactory.newBoxKeyset().replace("\r", "").replace("\n", "")
    // (2) required boot tokens — 32 secure-random bytes, url-safe base64, no padding.
    val operatorToken = randomToken()
    val hubTokenPo = randomToken()

    // (3) default secret-free platform.config.json (1 PO agent = boot invariant; loopback bind unless --host given).
    File(dataDir, "platform.config.json").writeText(defaultConfigJson(opts))

    // (4) secrets → the wizard's --secrets-out (owner-only); the wizard sets the service-account env from it + deletes.
    opts.secretsOut?.let { path ->
        val f = File(path)
        f.writeText(
            buildString {
                appendLine("CYPPIE_MASTER_KEY=$masterKey")
                appendLine("OPERATOR_TOKEN=$operatorToken")
                appendLine("HUB_TOKEN_PO=$hubTokenPo")
            },
        )
        ownerOnly(f) // best-effort POSIX 0600; on Windows the wizard applies the ACL (this JVM may be pre-service-acct)
    }

    // Non-secret summary ONLY (never the secret values).
    println("CYP-628 provisioned:")
    println("  config    = ${File(dataDir, "platform.config.json").absolutePath} (host=${opts.host} port=${opts.port} agents=[po])")
    println("  secrets   = ${opts.secretsOut ?: "(none — pass --secrets-out <file>)"}  [CYPPIE_MASTER_KEY, OPERATOR_TOKEN, HUB_TOKEN_PO]")
    println("  NEXT (wizard): set those 3 in the service account's ACL-protected env, then SECURELY DELETE the secrets file.")
}

private fun randomToken(): String {
    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Best-effort owner-only file perms (POSIX 0600). On non-POSIX (Windows) this is a no-op — the wizard ACL-locks. */
private fun ownerOnly(f: File) {
    runCatching {
        val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
        java.nio.file.Files.setPosixFilePermissions(f.toPath(), perms)
    }
}

/** A minimal, secret-free platform.config.json. Matches the PlatformConfig shape (boot invariant: exactly one PO). */
private fun defaultConfigJson(o: Options): String {
    val repo = o.repo.replace("\\", "\\\\").replace("\"", "\\\"")
    return """
        {
          "repo": { "url": "$repo", "branch": "${o.branch}" },
          "hub": { "host": "${o.host}", "port": ${o.port}, "tunnelPort": ${o.tunnelPort} },
          "agents": [ { "id": "po", "name": "Product Owner", "role": "PO", "launch": "${o.agentLaunch}" } ]
        }
    """.trimIndent() + "\n"
}

private data class Options(
    val dataDir: String,
    val host: String,
    val port: Int,
    val tunnelPort: Int,
    val repo: String,
    val branch: String,
    val agentLaunch: String,
    val secretsOut: String?,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            val m = HashMap<String, String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (a.startsWith("--") && i + 1 < args.size) { m[a.substring(2)] = args[i + 1]; i += 2 } else i += 1
            }
            return Options(
                dataDir = m["data-dir"] ?: error("--data-dir is required"),
                host = m["host"] ?: "127.0.0.1",
                port = (m["port"] ?: "8787").toInt(),
                tunnelPort = (m["tunnel-port"] ?: "8786").toInt(),
                repo = m["repo"] ?: "REPLACE_ME_set_the_repo_url_in_the_operator_GUI",
                branch = m["branch"] ?: "main",
                agentLaunch = m["agent-launch"] ?: "claude", // prod default; the wizard verifies `claude` is on PATH
                secretsOut = m["secrets-out"],
            )
        }
    }
}
