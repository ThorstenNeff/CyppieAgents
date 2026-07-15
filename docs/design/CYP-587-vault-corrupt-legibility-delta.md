# CYP-587 — Corrupt-Vault-Legibilität: Copy/Keys/Tags-Delta

> Owner: UIUX-Designer · Ticket **CYP-587** (Bug, Med, post-Live-Test) · Stand 2026-07-15 · **Design/Copy, kein Code.**
> Ursprung: der Visual-Completeness-Critic (`CYP-576-login-edge-states-legibility-critic.md`, Gap ④). Gegroundet READ-ONLY
> gg. develop `85553cd3`. Adjazent zur **CYP-584**-Recovery-Lane (Umsetzung: Team-1-Dev oder fold-in-584 = PO-Call).
>
> **⚠ KONVERGENZ (PO-Relay, vor Bau zusammenzuführen):** Team-2-UIUX2 designte parallel **dieselbe** Corrupt-Recovery
> (CYP-584 §3b, guided DeviceCustodyCorrupt-Recovery). **Gleiche CTA, zwei Keys** — meins `remote_pop_vault_corrupt_recover`
> vs UIUX2s `remote_connect_to_recovery`. **NICHT beide bauen.** Der PO führt zu EINEM Design zusammen (post-Test). Rollen-
> Split für die Zusammenführung: **meins = die Foundation** (der `VaultCorrupt`-Seam §5 + die Corrupt-Copy §2 `_vault_corrupt`/
> `_detail` — ohne die Wurzel-Trennung kann **keine** UI die Distinktion zeichnen); **UIUX2s = der Consumer** (Connect-Flow-
> Routing §3b). Die **CTA-Key/Routing-Zeile ist verhandelbar** → wenn `remote_connect_to_recovery` (Connect-Flow-benannt)
> die Recovery-Entry gleich erreicht, **cede ich meinen `_recover`-Key** dafür (ein Knopf, ein Key). Non-verhandelbar bleibt
> nur die **Foundation** (Seam + ehrliche Corrupt-Copy). Bis zum PO-Reconcile = **Vorschlag**, nicht bau-final.

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
- **Dreiwertiger Seam (Reconcile CYP-584 §3b ④):** `Corrupt`→Recovery · `Missing`→**Enroll ODER Recovery je nach Hub-Signal** (s. §5 — **nicht** bland `Unavailable`) · vorhanden→Unlock. Missing ≠ Corrupt ≠ present = drei distinkte Wahrheiten.

## 2. Neue Keys (DE = Default + EN)
| Key | DE | EN | Rolle / Honesty |
|---|---|---|---|
| `remote_pop_vault_corrupt` | Aus Sicherheitsgründen gesperrt — der Geräte-Schlüsselbund ist beschädigt oder wurde verändert. | Locked for your security — this device's keychain is damaged or was tampered with. | §1 Corrupt-Copy: **beabsichtigt** (nicht „kaputt"), benennt den fail-closed-Grund. error-Ton. |
| `remote_pop_vault_corrupt_detail` | Aus Sicherheit wird nicht automatisch neu eingerichtet. Stelle den Zugang mit einem Wiederherstellungs-Code wieder her. | For your security it won't re-enrol automatically. Restore access with a recovery code. | §1 warum-fail-closed + weist auf den Recovery-Weg (H1b-Erklärung, laienverständlich). |
| ~~`remote_pop_vault_corrupt_recover`~~ **CEDED** | — | — | **Entfällt** (Reconcile §3b ①) → **reuse** UIUX2s geteilten CTA-Key **`remote_connect_to_recovery`** „Mit Wiederherstellungs-Code neu einrichten" (→ `RecoveryInputContent`). Ein Knopf, ein Key. |

### a11y
| Key | DE | EN |
|---|---|---|
| `a11y_remote_pop_vault_corrupt` | Sicherheitssperre: Schlüsselbund beschädigt oder verändert — mit Wiederherstellungs-Code neu einrichten. | Security lock: keychain damaged or tampered — set up again with a recovery code. |

## 3. Neue Tags (Area `remote`, scopeId `authStep`)
| Tag | Element | liveRegion |
|---|---|---|
| `remote.authStep.error.vaultCorrupt` | §2 die Corrupt-Fehlerzeile (via `OperatorAuthTaxonomy.error("vaultCorrupt")`, neue Ursache) — error-Ton | **Assertive** (echter, terminaler-blockierender Zustand) |
| `remote.authStep.vaultCorruptRecover` | §2 die Recovery-CTA-Affordanz **auf der authStep-Fläche** — rendert den **geteilten** Copy-Key `remote_connect_to_recovery` (per-Fläche eigener Tag, geteilte Copy) | — |

## 4. Reuse (kein neuer Flow / keine neue Copy erfinden)
| Bestehend | Rolle im Fix |
|---|---|
| `remote_recovery_start_title` „Gerät wiederherstellen" · `remote_recovery_start_body` „Gib einen deiner Wiederherstellungs-Codes ein…" · `remote_recovery_code_label` | das **Ziel** der Recovery-CTA (CYP-479, verifiziert via `RecoveryCodeVerifier`) — die CTA **routet dorthin**, erfindet nichts. |
| `remote_pop_keystore_unavailable` „Schlüsselbund nicht verfügbar." | bleibt **Missing/transient** (unverändert) — **nicht** churnen. |
| `operatorAuthErrorCopy` / `OperatorAuthTaxonomy` (Mapping-Muster) | die neue `VaultCorrupt`-Ursache reiht sich ins bestehende `when`-Mapping ein. |
| `OperatorAuthError` (sealed interface) | + `data object VaultCorrupt` (die eine neue Ursache). |

## 5. Nahtstellen (Seams → Dev, für die Umsetzung) — dreiwertig, gegroundet
- **Corrupt (Kern-Trennung):** `VaultOpen.Corrupt` → **`UvOutcome.VaultCorrupt`** (neu, Recovery) statt `Unavailable`. Ohne diese Trennung kann **keine** UI (meine noch UIUX2s §3b) die Distinktion zeichnen — die UI zeichnet nur, was der State trägt.
- **Missing ist NICHT einfach „Enroll" — zwei Sub-Fälle (die ④-Verfeinerung):**
  - **Truly-fresh** (nie enrolled) trifft die UV/Unlock-Fläche **gar nicht**: der Hub meldet `RemoteFailure.DeviceNotEnrolled` **upstream** → `SetPassphrase`/Enroll (`HubConnectViewModel.kt:288`). Ein frischer Nutzer sieht **schon heute** „Passphrase setzen", **nicht** Corrupt — der ④-Kern ist über den Hub-Signal-Pfad **bereits erfüllt**, unabhängig von meinem Seam. ✅
  - **Lokaler `VaultOpen.Missing` an der UV/Unlock-Stelle** (`PassphraseUserVerification.kt:44`, heute → `Unavailable`) ist der **raced/verlorene** Fall: der Vault verschwand, während der Hub den Schlüssel noch **pinnt**. Ein Fresh-Enroll dort erzeugt einen **neuen Schlüssel ≠ Hub-Pin** → **stiller Downstream-Reject** (falsche Affordanz). **Ehrlicher: Recovery** (wie Corrupt). ⚠ **Flag:** die raced-Missing-Route braucht das **Hub-Enrollment-Signal**, um Enroll (Hub un-pinnt) vs Recovery (Hub pinnt noch) zu entscheiden — **nicht** die lokale Vault-State allein. Auf keinen Fall die bland `Unavailable`-Copy behalten.
- **Taxonomie:** `OperatorAuthError.VaultCorrupt` in `operatorAuthErrorCopy` (→ `remote_pop_vault_corrupt` + `_detail`) und `OperatorAuthTaxonomy` (→ `error("vaultCorrupt")`).
- **Routing:** die Recovery-CTA (`remote.authStep.vaultCorruptRecover`, Copy = geteilt `remote_connect_to_recovery`) startet die bestehende Recovery-Entry (`RecoveryInputContent`/`remote_recovery_start_*`) — **adjazent zu CYP-584** §3b; eigenständig vs fold-in-584 = PO-Call.

## 5b. Reconcile-Ergebnis (CYP-584 §3b, PO-Relay) — aligned, eine Corrupt-Wahrheit
- **① CTA geteilt:** `remote_connect_to_recovery`; mein `remote_pop_vault_corrupt_recover` **entfällt** (cede). Ein Knopf, ein Key, an beiden Corrupt-Flächen reused (per-Fläche eigener Tag).
- **② Zwei distinkte Ursachen-Copys (= zwei Zustände):** UIUX2 `remote_connect_device_custody_corrupt` (**Geräteschlüssel**, Connect-Fläche) · **meins `remote_pop_vault_corrupt`** (**Operator-Schlüsselspeicher**, authStep-Fläche). Gleiches Register, distinkte Wahrheit (der Mensch soll wissen WAS beschädigt ist). Meine Foundation-Copy + Seam **bleiben unverändert**.
- **③ Geteiltes Honesty-Muster (deckt sich):** ERROR-Ton · „beschädigt"=beobachtet (`BlobRead.Corrupt`) · Ursache→echte-Recovery, nie blanket-Retry · „Wiederherstellungs-Code" nie „Backup-Code".
- **④ Dreiwertiger Seam** — s. §5 (bestätigt + um den raced-Missing-Flag verfeinert).
- **Non-negotiable (Foundation, bleibt):** Seam `VaultOpen.Corrupt→VaultCorrupt` + meine Vault-Corrupt-Ursachen-Copy. **Verhandelt/ceded:** der CTA-Key.

## 6. Self-Validation (post-Reconcile)
- **Net-new nach Cede: 2 Copy-Keys** (`remote_pop_vault_corrupt`, `_detail`) **+ 1 a11y** (`a11y_remote_pop_vault_corrupt`) — `_recover` **entfällt** (CTA = geteilt `remote_connect_to_recovery`). **1 neue Ursache** `OperatorAuthError.VaultCorrupt`.
- **0 Kollision @ `85553cd3`:** die 3 verbleibenden Keys grep-verifiziert **nicht** vorhanden; `remote.authStep.error.vaultCorrupt` nicht in `OperatorAuthTags`. `remote_connect_to_recovery` = **UIUX2 fügt hinzu** (heute noch nicht in develop — nicht meins zu landen).
- **Reuse verifiziert @ `85553cd3`:** `remote_recovery_start_*` + `RecoveryInputContent`/`RemoteRecoveryViewModel` existieren (CYP-479) — die CTA routet dorthin, kein neuer Flow. Fresh-Enroll-Pfad (`DeviceNotEnrolled→SetPassphrase`) existiert (`HubConnectViewModel.kt:288`).
- **DE = Default + EN paritätisch**, alle 0 Args. Namespace-konsistent.
- **Missing korrigiert (nicht mehr „bleibt Unavailable"):** truly-fresh→Enroll (Hub-Signal, schon gebaut); raced-Missing→Recovery (Flag §5). Die bland `remote_pop_keystore_unavailable`-Copy soll den raced-Fall **nicht** behalten.
- **Honesty:** by-design benannt · Recovery-Weg signposted · Corrupt≠Missing≠present (dreiwertig) · zwei distinkte Ursachen-Copys · kein Secret in Copy. Kein Bau, docs-only auf `feature/CYP-587-vault-corrupt-legibility`.
