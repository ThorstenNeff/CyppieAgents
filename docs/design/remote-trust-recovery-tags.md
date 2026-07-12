# Remote-Operator Trust/Recovery/Revocation — testTag-Vertrag (CYP-480, Epic CYP-427)

> Owner: UIUX-Designer · **Spec-Closure** · Stand 2026-07-12 · Begleit-Spec: `remote-trust-recovery-ux-spec.md`.
> Test-Contract v0.5 §2 (`docs/TEST-CONTRACT.md`): **prefixless** `<area>[.<scopeId>].<element>[.<qualifier>]`,
> **camelCase**-Segmente, **kein** Underscore, keine Punkte im Wert. **Geteilte API mit QA (CYP-7) — über den PO koordinieren.**
> Fläche ① **erweitert das ausgelieferte `RemoteConnectTags` (`remote.connect.*`)** (§0.1 der Spec — der FirstUse-Screen ist
> ein Branch von `RemoteConnectingView`; die Trust-Zustände liegen dort). Flächen ②/③ = neue Objekte `RemoteRecoveryTags`
> (`remote.recovery.*`) / `RemoteRevokeTags` (`remote.revoke.*`). **⏳ Namens-Bestätigung PO/DS ausstehend (Spec §6-Q7).**

## Fläche ① — Trust-Confirm (§2, NET-NEW an `RemoteConnectTags` / `remote.connect.*`)
| Tag | Wert | Zweck |
|---|---|---|
| `trustFirst` | `remote.connect.trustFirst` | FirstUse-Trust-Confirm-Screen-Container (§2.1). |
| `trustFingerprint` | `remote.connect.trustFingerprint` | Fingerprint-Block-Container (nur echt abgeleitet, HA). |
| `trustWordlist` | `remote.connect.trustWordlist` | Wort-/Emoji-Sequenz (primär, OOB-Abgleich). |
| `trustHex` | `remote.connect.trustHex` | Hex-Fingerprint (kopierbar). |
| `trustQr` | `remote.connect.trustQr` | QR (starker Scan-Pfad). |
| `trustOob` | `remote.connect.trustOob` | „über einen anderen Kanal bestätigen". |
| `trustOobConsole` | `remote.connect.trustOobConsole` | OOB-Abgleich gegen die Hub-Konsole (Option X). |
| `trustPinned` | `remote.connect.trustPinned` | neutraler „Identität gepinnt"-Indikator (§2.2, nie grün). |
| `trustConfirm` | `remote.connect.trustConfirm` | „Stimmt überein — pinnen" → pinnt, weiter zu AUTHENTICATING. |
| `trustReject` | `remote.connect.trustReject` | „Stimmt nicht — abbrechen" → **fail-closed**, nicht pinnen, Teardown (HB, S-B). |
| `trustAborted` | `remote.connect.trustAborted` | Ergebnis „nicht verbunden — Identität nicht bestätigt". |
| `trustChangedRepin` | `remote.connect.trustChangedRepin` | rein informativer „Re-Pin nur OOB"-Hinweis am Alarm (§2.3, HC/①b), kein Button. |

## Fläche ② — Recovery (§3, NET-NEW `RemoteRecoveryTags` / `remote.recovery.*`)
| Tag | Wert | Zweck |
|---|---|---|
| `recoveryCodes` | `remote.recovery.codes` | Container der einmaligen Backup-Codes-Anzeige (§3.1). |
| `recoveryCodesList` | `remote.recovery.codesList` | Code-Liste (lesbar, nicht maskiert, einmal sichtbar — HD). |
| `recoveryCodesCopy` | `remote.recovery.codesCopy` | „Codes kopieren". |
| `recoveryCodesAck` | `remote.recovery.codesAck` | Verlassen-Gate „Ich habe die Codes gespeichert" (Reibung gegen Verlust). |
| `recoveryStart` | `remote.recovery.start` | Recovery-Eingabe-Screen-Container (§3.2). |
| `recoveryCodeField` | `remote.recovery.codeField` | Code-Eingabefeld (Re-Enroll = Re-Pin). |
| `recoveryNoCentral` | `remote.recovery.noCentral` | „Kein Zentral-Login-Recovery"-Hinweis (HE/Q6). |
| `recoveryMultidevice` | `remote.recovery.multidevice` | Multi-Device-Empfehlung — **frame-only + Seam-gated** (②a, T3). |
| `recoveryError` | `remote.recovery.error` | „Code ungültig/benutzt" bzw. „erschöpft → OOB am Hub" (§3.2). |

## Fläche ③ — Revocation (§4, NET-NEW `RemoteRevokeTags` / `remote.revoke.*`)
| Tag | Wert | Zweck |
|---|---|---|
| `revokeEnd` | `remote.revoke.end` | „Fern-Sitzung beenden"-Kontrolle (§4.1, reuse `close()`/`backToHubList()`). |
| `revokeConfirm` | `remote.revoke.confirm` | Destructive-Confirm-Dialog (reuse Haus-Scaffold). |
| `revokeScopeNote` | `remote.revoke.scopeNote` | „nur diese Verbindung — kein globales Revoke" (HF/③b). |
| `revokeTtlHint` | `remote.revoke.ttlHint` | advisory ≤TTL — **Seam-gated**, absent ohne Operator-Session-TTL (③a/CYP-459, `null≠0`). |
| `revokeEnded` | `remote.revoke.ended` | Ergebnis „Fern-Sitzung beendet" → LOST → Hub-Liste. |

## Fail-closed-Anker (für §-QA)
- `remote.connect.trustReject` = **fail-closed** (nicht pinnen, Teardown); `remote.connect.trustAborted` ehrlicher
  Nicht-verbunden-Zustand. `trustConfirm` pinnt **nur** nach OOB-Abgleich; **kein** Auto-Pin.
- `remote.connect.trustChangedRepin` = **kein** Ein-Klick-Weiter am Alarm (reuse ausgeliefertes `error.trustChanged`,
  terminal, WARN-Amber) — **kein Re-Pin im MVP** (①b).
- `remote.recovery.codes*` = **einmal sichtbar**, danach nie erneut; **kein** „Codes-erneut-ansehen"-Tag.
  `remote.recovery.multidevice` als befolgbare Affordance **erst**, wenn Multi-Device-Enroll gebaut (②a).
- `remote.revoke.ttlHint` **absent**, solange keine Operator-Session-TTL existiert (③a). `remote.revoke.scopeNote` trägt
  die „kein-globales-Revoke"-Wahrheit. **Kein Tag trägt Erfolgs-Grün.**

## Reuse (bestehende Tags/Areas — NICHT neu anlegen; verifiziert @ `efc8eaf7`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `remote.connect.trustCheck` / `remote.connect.trustProvisional` (`RemoteConnectTags`) | CYP-471/475 (SHIPPED) | Trust-Check-Zeile + Provisorisch-Disclosure (§2.1) |
| `remote.connect.error.trustChanged` (`RemoteConnectTags.error("trustChanged")`) | CYP-471 (SHIPPED) | TrustChanged-Alarm-Anker (§2.3) — terminal, WARN-Amber |
| `remote.authStep.{enroll,enrollPinSet}` | CYP-460 (SHIPPED) | Backup-Codes-Anzeige hängt an den Enroll-Schritt (§3.1) |
| `remote.context.*` / `WorkspaceTags.ROLE_INDICATOR` | CYP-429/Workspace | Host der „Sitzung beenden"-Kontrolle (§4.1) |
| Muster `CrossProjectTags.REVOKE` (`crossProject.revoke`) | CrossProject | Vorbild-Muster für eine Revoke-Affordance (nur Muster, eigener Tag) |

> **⚠ Drift-Notiz (Spec §0.1):** die CYP-429 §8 `remote.trust.{fingerprint,wordlist,hex,qr,pinPrompt,changedAlarm}`-Tags
> wurden **nie gebaut**; Fläche ① konsolidiert in `RemoteConnectTags`/`remote.connect.trust*` (dort liegen die ausgelieferten
> Trust-Zustände). **Kein** Bezug mehr auf ein `remote.trust.*`-Areal.

## Self-Validation
- **26 neue Tags**: 12 `remote.connect.trust*` (§2, an `RemoteConnectTags`) + 9 `remote.recovery.*` (§3) + 5
  `remote.revoke.*` (§4) = **26**. Kein Tag trägt einen dynamischen Qualifier (`recoveryError` deckt invalid/erschöpft über
  die Copy).
- **0 Kollision:** die 12 neuen `remote.connect.trust*` (nicht `trustCheck`/`trustProvisional`/`error.trustChanged`),
  `remote.recovery.*`, `remote.revoke.*` verifiziert greenfield gg. `*Tags.kt` @ `efc8eaf7`; `RemoteRecoveryTags`/
  `RemoteRevokeTags` existieren noch nicht (neue Objekte).
- **Geteilte API mit QA (CYP-7):** Werte über den PO mit dem Tester + DS abstimmen (Frozen-Contract; §6-Q7 Namens-Bestätigung).
- Jeder Tag ist in `remote-trust-recovery-ux-spec.md` verankert und trägt (wo Text) einen Copy-/a11y-Key aus `-keys.md`.
