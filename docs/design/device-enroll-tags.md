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

> Der Wert `deviceNotEnrolled` erscheint heute **nur** als Server-Test-Methodenname
> (`Cyp485DeviceMgmtTest.recovery_…_deviceNotEnrolled`), **nicht** als Client-Tag → greenfield.

## Guard-ACs (present-iff / Verhalten — Pflicht-AC, wie CYP-527 G1–G5)
| # | Guard | Regel |
|---|---|---|
| **GE1** | **Enroll-nur-bei-nicht-enrolled** | Bei AUTHENTICATING rendert der **Enroll-Schritt** (`remote.authStep.enroll`) **iff** `OperatorDeviceKeyStore.isEnrolled() == false` (Operator/Remote-Pfad). Ein **enrolltes** Gerät geht direkt zum `Pin`/`Biometric`-PoP — **nie** der Enroll-Schritt. |
| **GE2** | **Backup-Ack Pflicht vor CONNECTED** (First-Device) | Der First-Enroll-Flow erreicht `RemoteConnState.CONNECTED` **erst nach** erfülltem `remote.recovery.codesAck` — **kein Skip, kein Überspringen mit Warnung** (HA: Codes = einzige Recovery). Nur First-Device (`enrollFirstDevice`); Ersatzgerät (`enrollWithBackupCode`) **gibt** keine neuen Codes, es **verbraucht** einen. |
| **GE3** | **session-only-Disclosure nur Raw-Pfad** | `remote_pop_enroll_session_only` (Node `remote.authStep.enroll`) rendert **iff** der Enroll-Pfad der Raw-Software-Key ist (`sessionOnly == true`). Auf einem Hardware-Passkey (Fido2, `sessionOnly == false`) ist die Zeile **absent** (keine DEVICE_SECURE-Überzeichnung, kein -Understatement). |
| **GE4** | **not-enrolled ≠ AuthRejected** | not-enrolled routet **in-flow** zum Enroll-Schritt (nie `false`→`AuthRejected`). Muss es als Connect-Failure erscheinen (Route unerreichbar / `already_enrolled`-Ersatzgerät), ist es die **distinkte** `remote.connect.error.deviceNotEnrolled` (aktionabel), **nie** in `authRejected` kollabiert. 3 nie-konflatierte Wahrheiten. |

## Fail-closed-/Ton-Anker (für §-QA)
- `remote.connect.error.deviceNotEnrolled` = **aktionabel** (Enroll-/Recovery-Affordance, wie ein retryable Failure),
  **nicht** terminal wie `authRejected`/`trustChanged`. Ton: neutral/WARN — es ist kein „kaputt"/„abgelehnt".
- `remote.recovery.codesAck` = **hartes Gate**; ohne Quittung kein `CONNECTED`.
- session-only-Disclosure ist **advisory** (WARN-Muster wie `workspace.remoteContext`), nie error-rot; **kein Tag trägt
  Erfolgs-Grün.**
- Enroll ist **Operator-only** (bindet den Geräteschlüssel an die Operator-Identität); ein MEMBER erreicht den Zweig nie.

## Reuse (bestehende Objekte/Areas — NICHT neu anlegen; verifiziert @ `4572a278`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `OperatorAuthTags` (`remote.authStep.*`) + `OperatorAuthStep.Enroll` | CYP-460 (SHIPPED) | der Enroll-Schritt-Kern |
| `RemoteRecoveryTags` (`remote.recovery.*`) + `RecoveryCodesReveal`/`RecoveryInputContent` | CYP-480 (SHIPPED, ungewired) | ③ Codes-Reveal + Ersatzgerät-Eingabe |
| `RemoteConnectTags.error(cause)` | CYP-471 (SHIPPED) | trägt die neue `deviceNotEnrolled`-Ursache (kein neuer Const) |
| `OperatorAuthError.NeedsEnroll` (`remote.authStep.error.needsEnroll`) | CYP-460 (SHIPPED) | lokaler nicht-terminaler not-enrolled — die Brücke zu GE1/GE4 |

## Self-Validation
- **Net-new: 1 Tag-Wert** (`remote.connect.error.deviceNotEnrolled`) über die **bestehende** `error(cause)`-fn — **kein
  neuer Const**, kein neues Tag-Objekt.
- **0 Kollision:** `deviceNotEnrolled` als Client-Tag greenfield @ `4572a278` (nur Server-Test-Methodenname existiert).
- **Charset/Konvention:** `remote.connect.error.deviceNotEnrolled` = camelCase-Segmente, `[A-Za-z0-9-]+`, kein
  Underscore/Punkt im Segment-Wert. ✓
- **Geteilte API mit QA (CYP-7):** Ursachen-Wert über den PO mit Tester + DS abstimmen (Frozen-Contract).
- **Guard-ACs GE1–GE4** = der behaviorale §-QA-Kern — testbar: Enroll iff `isEnrolled()==false`, kein CONNECTED ohne
  `codesAck`, session-only nur Raw, deviceNotEnrolled nie authRejected.
- Jeder net-new Tag/Key ist in `device-enroll-keys.md` + der Begleit-Spec verankert.
