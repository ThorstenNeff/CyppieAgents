# CYP-587 — Corrupt-Vault-Legibilität: Copy/Keys/Tags-Delta

> Owner: UIUX-Designer · Ticket **CYP-587** (Bug, Med, post-Live-Test) · Stand 2026-07-15 · **Design/Copy, kein Code.**
> Ursprung: der Visual-Completeness-Critic (`CYP-576-login-edge-states-legibility-critic.md`, Gap ④). Gegroundet READ-ONLY
> gg. develop `85553cd3`. Adjazent zur **CYP-584**-Recovery-Lane (Umsetzung: Team-1-Dev oder fold-in-584 = PO-Call).

## 0. Das Problem (Code-Ursache, exakt)
`VaultOpen.Corrupt` **und** `VaultOpen.Missing` mappen **beide** auf `UvOutcome.Unavailable`
(`PassphraseUserVerification.kt:43-44`) → **eine** Fehler-Ursache `OperatorAuthError.KeystoreUnavailable`
(`OperatorAuthTaxonomy.kt`) → **eine** bland Copy **„Schlüsselbund nicht verfügbar."** (`OperatorAuthCopy.kt:23`).
Folge (Legibilität): der **Corrupt**-Fall (Tamper-Signal, fail-closed by-design, braucht Recovery-Guidance) liest als
generischer **„kaputt"**-Fehler und ist **ununterscheidbar** vom transienten **Missing**-Fall. Der Code trennt die
Wahrheiten (`OperatorSecretVault.kt:15-17`), die **UI kollabiert sie**.

## 1. Fix-Prinzip (Honesty)
- **Corrupt = eigene Ursache** `OperatorAuthError.VaultCorrupt` ≠ `KeystoreUnavailable` (Missing/transient bleibt dort).
- **Corrupt-Copy benennt das Beabsichtigte:** „aus Sicherheitsgründen gesperrt — Schlüsselbund beschädigt/verändert" (nicht „kaputt"), das ist der **CYP-525-H1b-Schutz** (kein stilles Re-Enroll bei manipuliertem Vault).
- **Recovery-Signpost:** ein **konstruktiver Weg nach vorn** → die **bestehende** Recovery-Entry-Fläche (`remote_recovery_start_*`, CYP-479) „Gerät mit Wiederherstellungs-Code neu einrichten". **Kein** neuer Recovery-Flow erfunden.
- **Error-Ton** (es ist ein blockierter Zustand) **aber aktionabel** (Recovery-CTA) — kein hoffnungsloser Dead-End.
- **Missing bleibt separat** — `remote_pop_keystore_unavailable` unverändert (kein Churn).

## 2. Neue Keys (DE = Default + EN)
| Key | DE | EN | Rolle / Honesty |
|---|---|---|---|
| `remote_pop_vault_corrupt` | Aus Sicherheitsgründen gesperrt — der Geräte-Schlüsselbund ist beschädigt oder wurde verändert. | Locked for your security — this device's keychain is damaged or was tampered with. | §1 Corrupt-Copy: **beabsichtigt** (nicht „kaputt"), benennt den fail-closed-Grund. error-Ton. |
| `remote_pop_vault_corrupt_detail` | Aus Sicherheit wird nicht automatisch neu eingerichtet. Stelle den Zugang mit einem Wiederherstellungs-Code wieder her. | For your security it won't re-enrol automatically. Restore access with a recovery code. | §1 warum-fail-closed + weist auf den Recovery-Weg (H1b-Erklärung, laienverständlich). |
| `remote_pop_vault_corrupt_recover` | Mit Wiederherstellungs-Code neu einrichten | Set up again with a recovery code | §1 die **Recovery-CTA** → routet zu `remote_recovery_start_*` (CYP-479/584). Aktion. |

### a11y
| Key | DE | EN |
|---|---|---|
| `a11y_remote_pop_vault_corrupt` | Sicherheitssperre: Schlüsselbund beschädigt oder verändert — mit Wiederherstellungs-Code neu einrichten. | Security lock: keychain damaged or tampered — set up again with a recovery code. |

## 3. Neue Tags (Area `remote`, scopeId `authStep`)
| Tag | Element | liveRegion |
|---|---|---|
| `remote.authStep.error.vaultCorrupt` | §2 die Corrupt-Fehlerzeile (via `OperatorAuthTaxonomy.error("vaultCorrupt")`, neue Ursache) — error-Ton | **Assertive** (echter, terminaler-blockierender Zustand) |
| `remote.authStep.vaultCorruptRecover` | §2 die Recovery-CTA-Affordanz | — |

## 4. Reuse (kein neuer Flow / keine neue Copy erfinden)
| Bestehend | Rolle im Fix |
|---|---|
| `remote_recovery_start_title` „Gerät wiederherstellen" · `remote_recovery_start_body` „Gib einen deiner Wiederherstellungs-Codes ein…" · `remote_recovery_code_label` | das **Ziel** der Recovery-CTA (CYP-479, verifiziert via `RecoveryCodeVerifier`) — die CTA **routet dorthin**, erfindet nichts. |
| `remote_pop_keystore_unavailable` „Schlüsselbund nicht verfügbar." | bleibt **Missing/transient** (unverändert) — **nicht** churnen. |
| `operatorAuthErrorCopy` / `OperatorAuthTaxonomy` (Mapping-Muster) | die neue `VaultCorrupt`-Ursache reiht sich ins bestehende `when`-Mapping ein. |
| `OperatorAuthError` (sealed interface) | + `data object VaultCorrupt` (die eine neue Ursache). |

## 5. Nahtstellen (Seams → Dev, für die Umsetzung)
- **Vault-Layer (die Kern-Trennung):** `VaultOpen.Corrupt` → **`UvOutcome.VaultCorrupt`** (neu) statt `Unavailable`; `VaultOpen.Missing` bleibt `Unavailable`. Ohne diese Trennung greift die Copy-Distinktion nicht (die UI kann nur zeichnen, was der State trägt).
- **Taxonomie:** `OperatorAuthError.VaultCorrupt` in `operatorAuthErrorCopy` (→ `remote_pop_vault_corrupt` + Detail) und `OperatorAuthTaxonomy` (→ `error("vaultCorrupt")`).
- **Routing:** die Recovery-CTA (`remote.authStep.vaultCorruptRecover`) startet die bestehende Recovery-Entry (`remote_recovery_start_*`) — die Verdrahtung ist **adjazent zu CYP-584** (Recovery-Lane); ob eigenständig oder fold-in-584 = PO-Call.

## 6. Self-Validation
- **0 Kollision @ `85553cd3`:** `remote_pop_vault_corrupt` / `_detail` / `_recover` / `a11y_remote_pop_vault_corrupt` grep-verifiziert **nicht** vorhanden; `remote.authStep.error.vaultCorrupt` / `.vaultCorruptRecover` nicht in `OperatorAuthTags`.
- **Reuse verifiziert:** `remote_recovery_start_*` + `RecoveryCodeVerifier` existieren (CYP-479) — die CTA routet dorthin, kein neuer Flow.
- **DE = Default + EN paritätisch**, alle 0 Args. Namespace-konsistent (`remote_pop_*` / `remote.authStep.*`).
- **Missing bleibt separat** (kein Churn an `remote_pop_keystore_unavailable`).
- **Honesty:** by-design benannt · Recovery-Weg signposted · Corrupt≠Missing · kein Secret in Copy. Kein Bau, docs-only auf `feature/CYP-587-vault-corrupt-legibility`.
