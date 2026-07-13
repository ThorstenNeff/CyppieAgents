# Device-Enroll — i18n-Keys (CYP-525, Epic CYP-427 Phase-2 Remote)

> Owner: UIUX-Designer · **Dev-AC (copy-paste-fertig)** · Stand 2026-07-13 · Begleit-Spec:
> `cyp525-device-enroll-ux-spec-delta.md` (Ratifikations-Paket). **Ratifiziert (a)+2-iii.**
> Konvention (verifiziert gg. `app/shared/.../composeResources/values/strings.xml` @ develop `4572a278`):
> **Underscore-Realkeys**, positionsbasierte Args `%1$s`. **DE = Default**, **EN** (`values-en/`). Parität Pflicht.
> **Reuse-schwer:** die Enroll-Schritt- (CYP-460) und Backup-Code-Keys (CYP-480) existieren bereits — Dev **wired**
> sie nur, legt sie **nicht neu** an. **Net-new nur `DeviceNotEnrolled`** (die Q3-Distinct-Cause).

## REUSE — existiert, Dev wired NUR (NICHT neu anlegen; verifiziert @ `4572a278`)

### Enroll-Schritt (CYP-460, `OperatorAuthStep.Enroll`) — ①/②
| Key | DE | EN | Rolle |
|---|---|---|---|
| `remote_pop_enroll_title` | Dieses Gerät einrichten | Set up this device | Enroll-Schritt-Titel (①, bei `isEnrolled()==false`) |
| `remote_pop_enroll_pin` | App-PIN festlegen | Set an app PIN | Software-Key-Pfad: PIN setzen (②a Raw) |
| `remote_pop_enroll_session_only` | Gilt nur für diese Sitzung — sicherer Geräte-Schlüsselbund folgt. | Applies to this session only — secure device keychain to come. | **session-only-Disclosure — NUR Raw-Pfad (GE3)**; Passkey zeigt sie nie |

### Backup-Codes-Ack (CYP-480, `RecoveryCodesReveal`) — ③, der load-bearing Join
| Key | DE | EN | Rolle |
|---|---|---|---|
| `remote_recovery_codes_title` | Wiederherstellungs-Codes | Recovery codes | Codes-Reveal-Titel (First-Enroll ③) |
| `remote_recovery_codes_body` | Bewahre diese Codes offline und sicher auf. Du siehst sie nur dieses eine Mal — sie können nicht erneut angezeigt werden. | Store these codes offline and safely. You'll see them only this once — they can't be shown again. | einmal-sichtbar-Disclosure |
| `remote_recovery_codes_single_use` | Jeder Code funktioniert nur einmal. | Each code works only once. | single-use-Disclosure |
| `remote_recovery_codes_copy` | Codes kopieren | Copy codes | Kopier-Aktion |
| `remote_recovery_codes_ack` | Ich habe die Codes sicher gespeichert | I've saved the codes safely | **★ Ack-Gate (GE2/HA) — Pflicht vor CONNECTED** |
| `a11y_remote_recovery_codes` | Wiederherstellungs-Codes, einmalig angezeigt — sicher speichern. | Recovery codes, shown once — save them safely. | a11y der Codes-Fläche |

### Recovery-Eingabe (Ersatz-/Zusatzgerät, CYP-480, `RecoveryInputContent`) — die zweite Enroll-Tür
| Key | DE | EN | Rolle |
|---|---|---|---|
| `remote_recovery_start_title` | Gerät wiederherstellen | Recover this device | Ersatzgerät-Enroll (`enrollWithBackupCode`) |
| `remote_recovery_start_body` | Gib einen deiner Wiederherstellungs-Codes ein, um dieses Gerät neu einzurichten. | Enter one of your recovery codes to set up this device again. | Anleitung |
| `remote_recovery_code_label` | Wiederherstellungs-Code | Recovery code | Code-Eingabefeld-Label |
| `remote_recovery_no_central` | Ein zentraler Login allein stellt den Zugriff nicht wieder her — das schützt dich, falls dein Konto kompromittiert wird. | A central login alone won't restore access — this protects you if your account is compromised. | **kein-Zentral-Login-Recovery (HE)** |
| `remote_recovery_invalid_code` | Code ungültig oder bereits benutzt. | Code invalid or already used. | Fehlerzeile |
| `remote_recovery_exhausted` | Keine Wiederherstellungs-Codes mehr — wende dich für die Wiederherstellung an die Hub-Konsole. | No recovery codes left — use the hub console to recover. | OOB-am-Hub-Verweis, kein Phantom-Weg |

### PoP-Folge (CYP-460) — für Distinktheit (NICHT wiederverwenden als DeviceNotEnrolled!)
| Key | DE | EN | Rolle |
|---|---|---|---|
| `remote_pop_rejected` | Vom Hub abgelehnt. Bitte neu anmelden. | Rejected by the hub. Please sign in again. | **`AuthRejected` — TERMINAL, Neu-Login**; muss DISTINKT von DeviceNotEnrolled bleiben (GE4) |

### UV-Fail-Fallback (F3 → CYP-460-App-PIN-Dialog) — die letzte Enroll-Flow-Copy-Lücke (PO `1526283271…`)
Die provisorische Connect-Copy `remote_connect_uv_failed` wird **retired**: eine UV-Fehlprüfung (`PopResult.UvFailed(reason)`,
`UvFailReason { WRONG_PIN, CANCELLED, LOCKED_OUT }`) faltet in die **bestehende CYP-460-Taxonomie** (`OperatorAuthError`),
NICHT in eine generische Connect-Zeile. Alle **retryable/lokal, nie terminal** (H2/GE4 — nur `HubRejected` terminal).
| UvFailReason | Key | DE | EN | Rolle |
|---|---|---|---|---|
| `WRONG_PIN` | `remote_pop_wrong_pin` **(REUSE, final)** | Falsche PIN. Noch %1$s Versuche. | Wrong PIN. %1$s attempts left. | `OperatorAuthError.WrongPin(attemptsLeft)` — **retryable**, der Rest-Zähler `%1$s` = impliziter Retry (kein „abgelehnt"). Wortlaut bleibt, kein Change. |
| `LOCKED_OUT` | `remote_pop_locked` **(REUSE, final)** | Zu viele Fehlversuche. Erneut in %1$s. | Too many attempts. Try again in %1$s. | `OperatorAuthError.LockedOut(retryAfter)` — eigenes Tag `LOCKED_OUT`; **Vollständigkeit** (3. Reason schon gedeckt). |
| `CANCELLED` | `remote_pop_cancelled` **(NET-NEW)** | Abgebrochen — erneut versuchen. | Cancelled — try again. | `OperatorAuthError.Cancelled` — heute `""` (stiller Dismiss im Solo-Dialog); als **Connect-Fallback** braucht es eine **neutral/retryable** Zeile, damit ein abgebrochenes UV den Connect nicht stumm-unfertig/hängend zurücklässt. **Nicht** error-rot, **nicht** terminal. Tag existiert (`remote.authStep.error.cancelled`), nur Copy net-new. |

## NET-NEW — Dev legt an (0 Kollision @ `4572a278`)
| Key | DE | EN | Rolle |
|---|---|---|---|
| `remote_connect_device_not_enrolled` | Dieses Gerät ist noch nicht eingerichtet — richte es ein, um fortzufahren. | This device isn't set up yet — set it up to continue. | **Q3-Distinct-Cause** `RemoteFailure.DeviceNotEnrolled`: aktionabel (→Enroll/Recovery), **nie** „abgelehnt" |
| `a11y_remote_connect_device_not_enrolled` | Gerät nicht eingerichtet — Einrichtung nötig, um fortzufahren. | Device not set up — setup needed to continue. | a11y (optional-empfohlen) |
| `remote_recovery_codes_no_central` | Ohne diese Codes gibt es keinen Weg zurück — ein zentraler Login stellt den Zugriff nicht wieder her. | Without these codes there's no way back — a central login won't restore access. | **★ ①-Befund (PO `1526254113…` BAU): die load-bearing Konsequenz auf dem *Reveal*, VOR dem Ack** — wer „gespeichert" quittiert, muss die Einsätze kennen. Variante von `remote_recovery_no_central` (das nur auf der *Verlust*-Fläche `RecoveryInputContent` steht), fürs Reveal (`RecoveryCodesReveal`) neu getextet: Konsequenz-Rahmen (kein Weg zurück) statt Schutz-Rahmen. |

## Honesty-Anker (für §-QA)
- **HA — Ack-Gate Pflicht + informierter Ack:** kein `CONNECTED` beim First-Enroll ohne quittiertes
  `remote_recovery_codes_ack` (einzige Recovery, kein Zentral-Login → sonst Lockout-Falle). **★ Neu (①):** der Ack ist
  nur ehrlich, wenn die **Einsätze auf dem Reveal stehen** — `remote_recovery_codes_no_central` rendert **auf dem
  Reveal, VOR dem Ack** (nicht nur auf der Verlust-Fläche). Ohne diese Zeile quittiert der Nutzer blind, was er
  aufs Spiel setzt. Gebauter Reveal (`RecoveryCodesReveal.kt` L49-53) sagt „einmalig", nicht „einziger Weg zurück".
- **HB — not-enrolled ≠ rejected (2-Wege, ③ ge-ruled):** `remote_connect_device_not_enrolled` ist **aktionabel**
  (führt zu Enroll/Recovery), **nie** `remote_pop_rejected`/`AuthRejected`-terminal. Am Connect-Surface **2 getrennte
  Wahrheiten: `DeviceNotEnrolled ≠ AuthRejected`**; `cpSessionExpired` = bewusst AuthGate-Re-Login (CYP-176), keine
  Connect-Ursache (GE4/③).
- **HC — session-only nur Raw + WARN-amber-Ton:** `remote_pop_enroll_session_only` erscheint **nur** auf dem
  Raw-Software-Pfad (`sessionOnly==true`); ein Hardware-Passkey (Fido2) zeigt sie **nicht** (keine
  DEVICE_SECURE-Überzeichnung). **★ Ton ge-ruled (④, PO `1526254113…` BAU): `severityColor(Severity.WARN)`
  (amber) + `▲` separater Node (WCAG 1.4.1), NICHT `onSurfaceVariant`** — es ist eine Security-Downgrade-Wahrheit
  (schwächerer Session-Key, kein HW-Keychain), doktrin-konsistent mit `workspace.remoteContext`s reduced-guarantee-
  Offenlegung. Gebaut heute neutral-grau (`OperatorAuthDialog.kt` L96-104) = Understatement; da (a)+Persist der
  ratifizierte Default ist, ist Session-only die **Ausnahme** → amber-flaggen ist richtig, nicht laut. **Nie** error-rot
  (kein „kaputt"), **nie** tertiary/grün (kein Erfolg). Kein neuer Key/Tag — Ton-Swap am **bestehenden** Node
  `remote.authStep.enroll`, Dev verdrahtet die Farbe in Inc 3.
- **HD — Codes einmal:** `remote_recovery_codes_body` = einmal sichtbar, nie erneut (kein „Codes-erneut-ansehen"-Key).
- **HE — kein Zentral-Login-Recovery:** `remote_recovery_no_central` / `_exhausted` = OOB-am-Hub, kein Phantom-Weg.
- **HF — UV-Fail retryable ≠ terminal (F3, PO `1526283271…`):** eine UV-Fehlprüfung (WrongPin/Cancelled/LockedOut) ist
  **lokal + retryable** und faltet in die CYP-460-Taxonomie — WrongPin=`remote_pop_wrong_pin` (Rest-Zähler=Retry),
  Cancelled=`remote_pop_cancelled` (neutral „erneut versuchen"), LockedOut=`remote_pop_locked`. **Nie** die terminale
  `remote_pop_rejected`/`AuthRejected`-Zeile (nur ein Hub-Verdikt ist terminal, H2/`isTerminal`). Die provisorische
  Connect-Zeile `remote_connect_uv_failed` wird **retired** (keine generische Connect-UV-Copy mehr). Cancelled-Ton =
  **neutral, nicht error-rot** (Nutzer-Entscheidung, kein Fehler); WrongPin = mild error-tone (etwas stimmte nicht).
- **Kein „RR5"/„Seam"/„Passkey-vs-Raw-Internals"-Jargon in der User-Copy** — nur die schlichte Wahrheit.

## Self-Validation
- **Net-new: 3 Realkeys** (`remote_connect_device_not_enrolled` + `remote_recovery_codes_no_central` [①] +
  `remote_pop_cancelled` [HF/UV-Fail]) **+ 1 a11y** (`a11y_remote_connect_device_not_enrolled`, empfohlen) = **4 Keys**.
  Alle DE+EN. Args: `remote_pop_cancelled` = 0 Args; die net-new sonst 0 Args (die reused `wrong_pin`/`locked` tragen
  je 1 `%1$s`). ④ = **kein neuer Key** (Ton-Swap am bestehenden `remote_pop_enroll_session_only`-Node).
- **①-Kollision: 0** — `remote_recovery_codes_no_central` greenfield gg. `strings.xml` @ `4572a278` (das bestehende
  `remote_recovery_no_central` ist die *Verlust*-Fläche, distinkter Key/Kontext — kein Reuse, bewusst neu getextet).
- **HF-Kollision: 0** — `remote_pop_cancelled` greenfield @ `4572a278` (`Cancelled` mappt heute auf `""`); Tag existiert
  (`remote.authStep.error.cancelled`, kein net-new Tag). Provisorisches `remote_connect_uv_failed` = **retired** (nicht
  finalisieren). WrongPin/LockedOut = **Reuse** (`remote_pop_wrong_pin`/`remote_pop_locked`, kein Change).
- **Reuse: 18 bestehende Keys** (3 enroll-step + 6 codes/a11y + 6 recovery-input + 1 `remote_pop_rejected` +
  **2 UV-Fail** `remote_pop_wrong_pin`/`remote_pop_locked`) — verifiziert vorhanden @ `4572a278`, Wortlaut oben 1:1 aus
  `strings.xml`. **NICHT neu anlegen.**
- **Kollision: 0** — `remote_connect_device_not_enrolled` greenfield gg. `strings.xml` @ `4572a278`
  (`device_not_enrolled`/`deviceNotEnrolled` existiert dort nicht; nur als Server-**Test-Methodenname**, kein Key/Tag).
- **Kein content-tragender/sensibler Klartext** — keine `%1$s` in den net-new Keys; Backup-Codes/Fingerprint sind
  gerenderte Werte, keine Copy-Strings.
- **DE/EN-Parität:** jede net-new Zeile beidseitig; die Reuse-Werte sind bereits paritätisch (CYP-460/480).
- Jeder net-new Key ist in `device-enroll-tags.md` (Guard-ACs) verankert.
