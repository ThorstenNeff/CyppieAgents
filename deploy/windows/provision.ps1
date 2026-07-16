<#
  CYP-628 — install-time provisioning for the Cyppie hub (Windows, Topology A / standalone-local).
  Invoked by the install wizard (UIUX owns the wizard UX; this is the provisioning MECHANISM it drives). Secret-free
  installer: every secret is MINTED HERE on the host at install time, never shipped in the .msi.

  Flow:
    1. Create + ACL-lock the data dir.
    2. Run the CyppieHubProvision launcher (CYP-628 ProvisionMain) to mint CYPPIE_MASTER_KEY (a Tink keyset — the one
       secret PowerShell can't make itself) + OPERATOR_TOKEN + HUB_TOKEN_PO, and write a default platform.config.json.
    3. Set the 3 secrets in the SERVICE ACCOUNT's ACL-protected environment; SECURELY DELETE the transient file.
    4. Substitute the WinSW XML placeholders + install the service (CYP-627).

  Scope: this script is verified structurally (backend runs on Linux); a Windows runner executes it (README §Verify).
  The CORE — that CyppieHubProvision mints a master key the real crypto accepts + a loadable config — is unit-teethed
  (ProvisionMainTest) AND proven end-to-end on Linux (provision -> boot -> /api/health=ok).
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory)] [string] $InstallDir,                 # the CYP-626 app-image (contains CyppieHub.exe + CyppieHubProvision.exe)
  [Parameter(Mandatory)] [string] $DataDir,                    # PLATFORM_GIT_ROOT (e.g. %PROGRAMDATA%\CyppieHub)
  [string] $BindHost = "127.0.0.1",                            # loopback default (Option A); LAN = 0.0.0.0 / an IP
  [int]    $Port = 8787,
  [int]    $TunnelPort = 8786,
  [string] $Repo = "REPLACE_ME_set_the_repo_url_in_the_operator_GUI",
  [Parameter(Mandatory)] [string] $ServiceAccount              # the dedicated low-priv service account (NOT LocalSystem)
)
$ErrorActionPreference = "Stop"

# --- 0. preconditions (PO-call: `claude` is a documented prereq the wizard CHECKS, not bundled) -----------------------
foreach ($tool in @("git","claude")) {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
    Write-Warning "prerequisite '$tool' is not on PATH — the hub needs it (git: clone/worktrees; claude: agent runs). Install it before starting the service."
  }
}

# --- 1. data dir + ACL (Administrators + the service account only; the secrets-at-rest stores live here) -------------
New-Item -ItemType Directory -Force -Path $DataDir, (Join-Path $DataDir "logs") | Out-Null
$acl = New-Object System.Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)  # break inheritance — explicit ACL only
foreach ($id in @("BUILTIN\Administrators", $ServiceAccount)) {
  $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
    $id, "FullControl", "ContainerInherit,ObjectInherit", "None", "Allow")))
}
Set-Acl -Path $DataDir -AclObject $acl

# --- 2. mint the master key + tokens + config via the provisioning launcher (ProvisionMain) -------------------------
$secretsFile = Join-Path $env:TEMP ("cyp-provision-" + [guid]::NewGuid().ToString("N") + ".env")
& (Join-Path $InstallDir "CyppieHubProvision.exe") `
    --data-dir $DataDir --host $BindHost --port $Port --tunnel-port $TunnelPort --repo $Repo `
    --secrets-out $secretsFile
if ($LASTEXITCODE -ne 0) { throw "provisioning failed (CyppieHubProvision exit $LASTEXITCODE)" }

# --- 3. set the secrets in the SERVICE ACCOUNT's env (ACL-protected), then securely delete the transient file -------
# NB: these go to the service account's user environment (readable only by that account + Admins) — NOT the machine/
# system environment, which is world-readable. The master key = an ACL-keyset held here per the PO decision; the
# ANTHROPIC_API_KEY is NOT set here — it is entered at first-run via the operator GUI and stored encrypted-at-rest.
foreach ($line in Get-Content $secretsFile) {
  if ($line -match '^(?<k>[^=]+)=(?<v>.*)$') {
    # Set the var in the service account's environment (implementation: the wizard runs this in the account's context,
    # or writes HKU\<sid>\Environment). Placeholder call — the wizard supplies the account-scoped setter.
    Set-ServiceAccountEnv -Account $ServiceAccount -Name $Matches.k -Value $Matches.v
  }
}
# Overwrite then delete (best-effort secure erase of the transient secrets file).
Set-Content -Path $secretsFile -Value ((" " * 4096)) -NoNewline; Remove-Item $secretsFile -Force

# --- 4. WinSW service (CYP-627): substitute placeholders + install ---------------------------------------------------
$svcXml = Get-Content (Join-Path $InstallDir "CyppieHub-service.xml") -Raw
$svcXml = $svcXml.Replace("@INSTALL_DIR@", $InstallDir).Replace("@DATA_DIR@", $DataDir)
Set-Content -Path (Join-Path $InstallDir "CyppieHubService.xml") -Value $svcXml -Encoding UTF8
& (Join-Path $InstallDir "CyppieHubService.exe") install
Write-Host "CYP-628 provisioned. Start with: CyppieHubService.exe start  — then open the operator GUI to enter the ANTHROPIC_API_KEY (encrypted-at-rest) and set the repo/roster."
