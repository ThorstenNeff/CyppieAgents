<#
  CYP-630 — uninstall the Cyppie hub (Windows). DATA-PRESERVING by default: it NEVER silently deletes a team's
  data dir. It stops + deregisters the service and (optionally) removes the app-image; the data dir — sqlite stores,
  git clones/worktrees, platform.config.json, the encrypted SecretStore — is LEFT INTACT so a reinstall/upgrade keeps
  the team's state. A destructive wipe is a SEPARATE, EXPLICIT, CONFIRMED action (-WipeData).

  ★ Master-key ↔ SecretStore coupling: the SecretStore in the data dir (.cyppie\hub-secrets.db) is encrypted under
  CYPPIE_MASTER_KEY (held in the service account's env, CYP-628). Preserve-mode therefore also PRESERVES the master
  key + tokens — otherwise the preserved SecretStore would be orphaned (undecryptable) on reinstall. Wipe-mode removes
  BOTH the data dir AND the account-scoped secrets, together, so nothing is left half-deleted.

  Scope: verified structurally (backend runs on Linux); a Windows runner executes it (README §9). No :server change.
#>
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = "High")]
param(
  [Parameter(Mandatory)] [string] $InstallDir,           # the app-image dir (contains CyppieHubService.exe)
  [Parameter(Mandatory)] [string] $DataDir,              # PLATFORM_GIT_ROOT — PRESERVED unless -WipeData
  [string] $ServiceAccount,                              # needed only for -WipeData (to clear the account-scoped secrets)
  [switch] $RemoveAppImage,                              # also delete the app-image dir (MSI uninstall usually does this)
  [switch] $WipeData                                     # ★ DESTRUCTIVE: also delete the data dir + the secrets
)
$ErrorActionPreference = "Stop"

# --- 1. stop + deregister the service (always) --------------------------------------------------------------------
$svc = Join-Path $InstallDir "CyppieHubService.exe"
if (Test-Path $svc) {
  Write-Host "stopping + deregistering the cyppiehub service…"
  & $svc stop      2>$null    # graceful (the XML's 30s stoptimeout lets the JVM flush stores)
  & $svc uninstall 2>$null    # deregister
} else {
  Write-Warning "service host not found at $svc — skipping service stop/deregister (already removed?)"
}

# --- 2. app-image removal (optional; MSI uninstall usually handles this) ------------------------------------------
if ($RemoveAppImage) {
  # NB: only the app-image. The data dir is a DIFFERENT path and is untouched here by design.
  if ($PSCmdlet.ShouldProcess($InstallDir, "remove app-image")) { Remove-Item -Recurse -Force $InstallDir }
}

# --- 3. DATA: preserve (default) or wipe (explicit + confirmed) ---------------------------------------------------
if (-not $WipeData) {
  # ★ THE DEFAULT: never silently delete the team's data. Say exactly what stayed.
  Write-Host "DATA PRESERVED: $DataDir is left intact (sqlite stores, clones/worktrees, platform.config.json, the"
  Write-Host "  encrypted SecretStore). The service account's CYPPIE_MASTER_KEY + tokens are also preserved so a"
  Write-Host "  reinstall reattaches to this data. To also delete everything, re-run with -WipeData."
} else {
  # DESTRUCTIVE — irreversible. ShouldProcess + ConfirmImpact=High force a confirmation (or -Confirm:`$false to script).
  Write-Warning "★ -WipeData: this IRREVERSIBLY deletes the team's data ($DataDir) AND the account-scoped secrets."
  if ($PSCmdlet.ShouldProcess($DataDir, "PERMANENTLY DELETE the hub data dir + secrets")) {
    Remove-Item -Recurse -Force $DataDir
    if ($ServiceAccount) {
      # Clear the account-scoped secrets together with the data (no half-deleted state: orphaned key XOR orphaned store).
      foreach ($n in @("CYPPIE_MASTER_KEY", "OPERATOR_TOKEN", "HUB_TOKEN_PO")) {
        Clear-ServiceAccountEnv -Account $ServiceAccount -Name $n   # wizard-supplied account-scoped clearer (mirror of provision.ps1's setter)
      }
    } else {
      Write-Warning "no -ServiceAccount given: the account-scoped CYPPIE_MASTER_KEY/tokens were NOT cleared — remove them manually."
    }
    Write-Host "WIPED: $DataDir and the account-scoped secrets are gone."
  }
}
