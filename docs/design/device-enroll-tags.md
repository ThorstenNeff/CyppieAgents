# Device-Enroll — testTag-Vertrag + Guard-ACs (CYP-525, Epic CYP-427 Phase-2 Remote)

> Owner: UIUX-Designer · **Dev-AC (copy-paste-fertig)** · Stand 2026-07-13 · Begleit-Keys: `device-enroll-keys.md`.
> Test-Contract v0.5 §2: **prefixless** `<area>[.<scopeId>].<element>`, Segment-Werte `[A-Za-z0-9-]+` (**camelCase**,
> kein Underscore, keine Punkte im Wert). **Geteilte API mit QA (CYP-7) — nicht still umbenennen, über den PO.**
> **Reuse-schwer:** die Enroll- (`remote.authStep.*`) und Recovery-Tags (`remote.recovery.*`) existieren bereits;
> net-new = **eine** neue Ursache am bestehenden `RemoteConnectTags.error(cause)`. Verifiziert @ develop `4572a278`.

## REUSE — existiert, Dev wired NUR (NICHT neu anlegen; verifiziert @ `4572a278`)
| Tag | Wert | Rolle |
|---|---|---|
| `OperatorAuthTags.ENROLL` | `remote.authStep.enroll` | Enroll-Schritt (session-only-Disclosure-Node) |
| `OperatorAuthTags.ENROLL_PIN_SET` | `remote.authStep.enrollPinSet` | „App-PIN festlegen"-Feld (Raw-Pfad) |
| `OperatorAuthTags.BIOMETRIC_PROMPT` | `remote.authStep.biometricPrompt` | Passkey/Fido2-Prompt-Node (②b) |
| `OperatorAuthTags.error("needsEnroll")` | `remote.authStep.error.needsEnroll` | lokaler, **NICHT-terminaler** not-enrolled (Dialog-Ebene) |
| `RemoteRecoveryTags.CODES` | `remote.recovery.codes` | Backup-Codes-Reveal-Container (③) |
| `RemoteRecoveryTags.CODES_LIST` | `remote.recovery.codesList` | Code-Liste (einmal sichtbar) |
| `RemoteRecoveryTags.CODES_COPY` | `remote.recovery.codesCopy` | „Codes kopieren" |
| `RemoteRecoveryTags.CODES_ACK` | `remote.recovery.codesAck` | **★ Ack-Gate (GE2/HA)** |
| `RemoteRecoveryTags.START` | `remote.recovery.start` | Ersatzgerät-Recovery-Container |
| `RemoteRecoveryTags.CODE_FIELD` | `remote.recovery.codeField` | Recovery-Code-Eingabe |
| `RemoteRecoveryTags.NO_CENTRAL` | `remote.recovery.noCentral` | kein-Zentral-Login (HE) |
| `RemoteConnectTags.AUTHENTICATING` | `remote.connect.authenticating` | der Connect-Schritt, an dem der Enroll-Zweig sitzt |
| `RemoteConnectTags.error("authRejected")` | `remote.connect.error.authRejected` | **AuthRejected — terminal**; DISTINKT halten (GE4) |

## NET-NEW — Dev legt an (0 Kollision @ `4572a278`)
| Tag | Wert | Rolle |
|---|---|---|
| `RemoteConnectTags.error("deviceNotEnrolled")` | `remote.connect.error.deviceNotEnrolled` | **Q3-Distinct-Cause** `RemoteFailure.DeviceNotEnrolled` — **aktionabel** (Enroll/Recovery-Affordance), **nie** terminal-„abgelehnt". Nutzt die **bestehende** `error(cause)`-fn → **kein neuer Const**, nur ein neuer Ursachen-String. |
| `RemoteRecoveryTags.CODES_NO_CENTRAL` | `remote.recovery.codesNoCentral` | **★ ①-Befund (GE7):** Node der no-central-Konsequenz-Zeile auf dem Reveal (vor dem Ack). Neue Const im **bestehenden** `RemoteRecoveryTags`-Objekt (wie `CODES_LIST`/`CODES_ACK`) — kein neues Tag-Objekt. Macht GE7 sauber assertbar (present-vor-`CODES_ACK`). |

> Der Wert `deviceNotEnrolled` erscheint heute **nur** als Server-Test-Methodenname
> (`Cyp485DeviceMgmtTest.recovery_…_deviceNotEnrolled`), **nicht** als Client-Tag → greenfield.

## Guard-ACs (present-iff / Verhalten — Pflicht-AC, wie CYP-527 G1–G5)
| # | Guard | Regel |
|---|---|---|
| **GE1** | **Enroll-nur-bei-nicht-enrolled** | Bei AUTHENTICATING rendert der **Enroll-Schritt** (`remote.authStep.enroll`) **iff** `OperatorDeviceKeyStore.isEnrolled() == false` (Operator/Remote-Pfad). Ein **enrolltes** Gerät geht direkt zum `Pin`/`Biometric`-PoP — **nie** der Enroll-Schritt. |
| **GE2** | **Backup-Ack Pflicht vor CONNECTED** (First-Device) | Der First-Enroll-Flow erreicht `RemoteConnState.CONNECTED` **erst nach** erfülltem `remote.recovery.codesAck` — **kein Skip, kein Überspringen mit Warnung** (HA: Codes = einzige Recovery). Nur First-Device (`enrollFirstDevice`); Ersatzgerät (`enrollWithBackupCode`) **gibt** keine neuen Codes, es **verbraucht** einen. |
| **GE3** | **session-only-Disclosure nur Raw-Pfad** | `remote_pop_enroll_session_only` (Node `remote.authStep.enroll`) rendert **iff** der Enroll-Pfad der Raw-Software-Key ist (`sessionOnly == true`). Auf einem Hardware-Passkey (Fido2, `sessionOnly == false`) ist die Zeile **absent** (keine DEVICE_SECURE-Überzeichnung, kein -Understatement). |
| **GE4** | **not-enrolled ≠ AuthRejected (2-Wege am Connect-Surface)** | not-enrolled routet **in-flow** zum Enroll-Schritt (nie `false`→`AuthRejected`). Muss es als Connect-Failure erscheinen (Route unerreichbar / `already_enrolled`-Ersatzgerät), ist es die **distinkte** `remote.connect.error.deviceNotEnrolled` (aktionabel), **nie** in `authRejected` kollabiert. **Am Connect-Surface sind es 2 nie-konflatierte Wahrheiten: `DeviceNotEnrolled ≠ AuthRejected`.** — **③ ge-ruled (PO `1526254113…`):** `cpSessionExpired` ist **bewusst KEINE** distinkte gerenderte Connect-Ursache; der CpJwt ist die zentrale Operator-Session, ihr Ablauf = zentrales **Re-Login über den bestehenden AuthGate** (CYP-176), architektonisch **getrennt** von der Remote-Connect-Failure-Taxonomie. Gebautes `RemoteFailure` (`RemoteSessionState.kt` L18-26) hat korrekt kein `CpSessionExpired`-Member → **keine fehlende Ursache**. ⚠**LOW · Post-Dogfood-Politur (non-blocking):** eine CP-Session-Expiry kollabiert heute in `authRejected`-Copy (Attributions-Unschärfe) — Remedy (Re-Login) bleibt ~richtig, daher nicht blockierend; nur geloggt. |
| **GE5** | **Codes einmal, nie erneut** | Die Backup-Codes werden beim First-Enroll **einmal** gezeigt (`remote.recovery.codesList`) und sind danach **nie** erneut abrufbar — es gibt **keine** „Codes erneut ansehen"-Affordance/Tag. Wer sie nicht gespeichert hat, muss neu enrollen/regenerieren (Re-Zeigen bräche die Einmal-Sicherheit). Reuse der `RecoveryCodesReveal`-Einmal-Semantik. |
| **GE6** | **session-only-Ton = WARN-amber (④ ge-ruled)** | Die session-only-Disclosure (`remote.authStep.enroll`, `remote_pop_enroll_session_only`) rendert **`severityColor(Severity.WARN)` (amber) + `▲` separater Node (WCAG 1.4.1)**, **nicht** `onSurfaceVariant` (gebaut heute neutral-grau `OperatorAuthDialog.kt` L96-104 = Understatement). Security-Downgrade-Wahrheit, doktrin-konsistent mit `workspace.remoteContext`. **Nie** error-rot, **nie** tertiary/grün. Ton-Swap am **bestehenden** Node — kein neuer Tag. |
| **GE7** | **no-central-Konsequenz auf dem Reveal, vor dem Ack (①)** | Beim First-Enroll rendert `remote.recovery.codesNoCentral` (`remote_recovery_codes_no_central`) **auf dem Reveal**, **oberhalb/vor** dem Ack-Button (`remote.recovery.codesAck`) — nicht nur auf der Verlust-Fläche. Der Ack ist nur ehrlich, wenn die Einsätze (kein Zentral-Login = einziger Weg zurück) **vor** der Quittung sichtbar sind. |

## Fail-closed-/Ton-Anker (für §-QA)
- `remote.connect.error.deviceNotEnrolled` = **aktionabel** (Enroll-/Recovery-Affordance, wie ein retryable Failure),
  **nicht** terminal wie `authRejected`/`trustChanged`. Ton: neutral/WARN — es ist kein „kaputt"/„abgelehnt".
- `remote.recovery.codesAck` = **hartes Gate**; ohne Quittung kein `CONNECTED`. **Der Ack ist nur ehrlich mit der
  no-central-Konsequenz davor (GE7, `remote.recovery.codesNoCentral`).**
- session-only-Disclosure = **WARN-amber** (`severityColor(Severity.WARN)` + `▲` separater Node, GE6), doktrin-konsistent
  mit `workspace.remoteContext` — **nicht** neutral-grau (Understatement), **nie** error-rot; **kein Tag trägt
  Erfolgs-Grün.**
- **cpSessionExpired am Connect-Surface = bewusst ABSENT (GE4/③):** zentrales AuthGate-Re-Login, nicht Connect-Ursache;
  seine Abwesenheit ist **kein Befund**.
- Enroll ist **Operator-only** (bindet den Geräteschlüssel an die Operator-Identität); ein MEMBER erreicht den Zweig nie.

## Reuse (bestehende Objekte/Areas — NICHT neu anlegen; verifiziert @ `4572a278`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `OperatorAuthTags` (`remote.authStep.*`) + `OperatorAuthStep.Enroll` | CYP-460 (SHIPPED) | der Enroll-Schritt-Kern |
| `RemoteRecoveryTags` (`remote.recovery.*`) + `RecoveryCodesReveal`/`RecoveryInputContent` | CYP-480 (SHIPPED, ungewired) | ③ Codes-Reveal + Ersatzgerät-Eingabe |
| `RemoteConnectTags.error(cause)` | CYP-471 (SHIPPED) | trägt die neue `deviceNotEnrolled`-Ursache (kein neuer Const) |
| `OperatorAuthError.NeedsEnroll` (`remote.authStep.error.needsEnroll`) | CYP-460 (SHIPPED) | lokaler nicht-terminaler not-enrolled — die Brücke zu GE1/GE4 |

## Self-Validation
- **Net-new: 2 Tag-Werte** — `remote.connect.error.deviceNotEnrolled` (über die **bestehende** `error(cause)`-fn, **kein
  neuer Const**) + `RemoteRecoveryTags.CODES_NO_CENTRAL` = `remote.recovery.codesNoCentral` (**neue Const im
  bestehenden `RemoteRecoveryTags`-Objekt**, ① / GE7). **Kein neues Tag-Objekt.** ④ = **kein neuer Tag** (Ton-Swap am
  bestehenden `remote.authStep.enroll`).
- **0 Kollision:** `deviceNotEnrolled` als Client-Tag greenfield @ `4572a278` (nur Server-Test-Methodenname existiert);
  `codesNoCentral` greenfield @ `4572a278` (bestehen nur `codes`/`codesList`/`codesCopy`/`codesAck`).
- **Charset/Konvention:** beide = camelCase-Segmente, `[A-Za-z0-9-]+`, kein Underscore/Punkt im Segment-Wert. ✓
- **Geteilte API mit QA (CYP-7):** beide Werte über den PO mit Tester + DS abstimmen (Frozen-Contract).
- **Guard-ACs GE1–GE7** = der behaviorale §-QA-Kern — testbar: Enroll iff `isEnrolled()==false`, kein CONNECTED ohne
  `codesAck`, session-only nur Raw (GE3) + WARN-amber-Ton (GE6), deviceNotEnrolled nie authRejected [2-Wege] (GE4),
  Codes einmal (GE5), no-central-Zeile vor Ack (GE7).
- Jeder net-new Tag/Key ist in `device-enroll-keys.md` + der Begleit-Spec verankert.
