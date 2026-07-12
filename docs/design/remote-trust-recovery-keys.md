# Remote-Operator Trust/Recovery/Revocation — i18n-Keys (CYP-480, Epic CYP-427)

> Owner: UIUX-Designer · **Spec-Closure** · Stand 2026-07-12 · Begleit-Spec: `remote-trust-recovery-ux-spec.md`.
> Konvention (verifiziert gg. `app/shared/.../composeResources/values/strings.xml` @ develop `efc8eaf7`):
> **Underscore-Realkeys**, positionsbasierte Args `%1$s`. **DE = Default**, **EN** (`values-en/`). Parität Pflicht.
> Fläche ① konsolidiert in die **ausgelieferte `remote_connect_trust_*`-Familie** (§0.1 der Spec — CYP-429 §8
> `remote_trust_*` war nie gebaut; CYP-471/475 haben `remote_connect_trust_*` ausgeliefert). Neue Familien
> **`remote_recovery_*`** / **`remote_revoke_*`** (greenfield). **⏳ Namens-Bestätigung PO/DS ausstehend (Spec §6-Q7).**

## Fläche ① — Trust-Confirm (§2, NET-NEW an ausgelieferter `remote_connect_trust_*`)
| Key | DE | EN |
|---|---|---|
| `remote_connect_trust_first_title` | Erstverbindung mit %1$s | First connection to %1$s |
| `remote_connect_trust_first_body` | Bestätige die Identität über einen anderen Kanal, wenn Sicherheit zählt, und pinne sie. | Confirm the identity via another channel if security matters, then pin it. |
| `remote_connect_trust_wordlist_label` | Vergleichs-Wörter | Comparison words |
| `remote_connect_trust_hex_label` | Fingerprint (Hex) | Fingerprint (hex) |
| `remote_connect_trust_qr_label` | QR scannen | Scan QR |
| `remote_connect_trust_oob` | Über einen anderen Kanal bestätigen | Confirm via another channel |
| `remote_connect_trust_oob_console` | Vergleiche mit der Anzeige in der Hub-Konsole. | Compare with the display in the hub console. |
| `remote_connect_trust_pinned` | Identität gepinnt | Identity pinned |
| `remote_connect_trust_confirm` | Stimmt überein — pinnen | Matches — pin it |
| `remote_connect_trust_reject` | Stimmt nicht — abbrechen | Doesn't match — abort |
| `remote_connect_trust_aborted` | Nicht verbunden — Identität nicht bestätigt. | Not connected — identity not confirmed. |
| `remote_connect_trust_changed_repin` | Neu pinnen nur nach Out-of-Band-Abgleich des neuen Fingerprints. | Re-pin only after an out-of-band comparison of the new fingerprint. |

## Fläche ② — Recovery (§3, NET-NEW `remote_recovery_*`)
| Key | DE | EN |
|---|---|---|
| `remote_recovery_codes_title` | Wiederherstellungs-Codes | Recovery codes |
| `remote_recovery_codes_body` | Bewahre diese Codes offline und sicher auf. Du siehst sie nur dieses eine Mal — sie können nicht erneut angezeigt werden. | Store these codes offline and safely. You'll see them only this once — they can't be shown again. |
| `remote_recovery_codes_single_use` | Jeder Code funktioniert nur einmal. | Each code works only once. |
| `remote_recovery_codes_copy` | Codes kopieren | Copy codes |
| `remote_recovery_codes_ack` | Ich habe die Codes sicher gespeichert | I've saved the codes safely |
| `remote_recovery_start_title` | Gerät wiederherstellen | Recover this device |
| `remote_recovery_start_body` | Gib einen deiner Wiederherstellungs-Codes ein, um dieses Gerät neu einzurichten. | Enter one of your recovery codes to set up this device again. |
| `remote_recovery_code_label` | Wiederherstellungs-Code | Recovery code |
| `remote_recovery_no_central` | Ein zentraler Login allein stellt den Zugriff nicht wieder her — das schützt dich, falls dein Konto kompromittiert wird. | A central login alone won't restore access — this protects you if your account is compromised. |
| `remote_recovery_multidevice_hint` | Empfehlung: Richte ein zweites Gerät ein, damit du bei Verlust nicht ausgesperrt bist. | Recommended: set up a second device so you're not locked out if you lose one. |
| `remote_recovery_invalid_code` | Code ungültig oder bereits benutzt. | Code invalid or already used. |
| `remote_recovery_exhausted` | Keine Wiederherstellungs-Codes mehr — wende dich für die Wiederherstellung an die Hub-Konsole. | No recovery codes left — use the hub console to recover. |

## Fläche ③ — Revocation (§4, NET-NEW `remote_revoke_*`)
| Key | DE | EN |
|---|---|---|
| `remote_revoke_end_action` | Fern-Sitzung beenden | End remote session |
| `remote_revoke_confirm_title` | Fern-Sitzung beenden? | End remote session? |
| `remote_revoke_confirm_body` | Diese Verbindung wird sofort getrennt. | This connection is torn down immediately. |
| `remote_revoke_scope_note` | Das beendet nur diese Verbindung auf diesem Gerät — es widerruft keine anderen Sitzungen. | This ends only this connection on this device — it does not revoke other sessions. |
| `remote_revoke_ttl_hint` | Deine Berechtigung läuft ohnehin innerhalb von %1$s ab. | Your authorization expires within %1$s regardless. |
| `remote_revoke_ended` | Fern-Sitzung beendet. | Remote session ended. |

## a11y
| Key | DE | EN |
|---|---|---|
| `a11y_remote_recovery_codes` | Wiederherstellungs-Codes, einmalig angezeigt — sicher speichern. | Recovery codes, shown once — save them safely. |
| `a11y_remote_revoke_ended` | Fern-Sitzung beendet, Verbindung getrennt. | Remote session ended, connection closed. |

> **Honesty-Anker:**
> - `remote_connect_trust_reject`/`_aborted` = **fail-closed Abbruch** (nicht pinnen), sichere Wahl bei
>   Fingerprint-Nichtübereinstimmung — neutral, **kein** Schreck-Rot (HB). `_oob_console` = OOB-Abgleich gegen die
>   Hub-Konsole (Option X). `_changed_repin` = **rein informativ, kein Aktions-Button, kein Re-Pin im MVP** (HC/①b),
>   WARN-Amber-Kontext neben dem ausgelieferten `remote_connect_trust_changed`-Alarm.
> - `remote_recovery_codes_body` = **einmal sichtbar, nie erneut** (HD). `_codes_ack` = Verlassen-Gate gegen Verlust.
>   `_no_central` = **bewusst kein Zentral-Login-Recovery** (HE/Q6/RR7). `_multidevice_hint` = **frame-only + Seam-gated**
>   (②a; befolgbar erst mit Server-Multi-Device-Enroll CYP-459/469). `_exhausted` = ehrlicher OOB-am-Hub-Verweis, **kein**
>   Phantom-Weg. Backup-Code-Anzahl/Format = Reviewer-Threat-Model (②b) — nicht Copy.
> - `remote_revoke_confirm_body` = **garantiert/sofort** (lokaler Teardown, gebaut); `_scope_note` = **kein globales
>   Revoke** (HF/③b); `_ttl_hint` = **advisory, Seam-gated** — **absent**, solange keine Operator-Session-TTL existiert
>   (③a/CYP-459, `null ≠ 0`). **Kein „RR5"/„Seam"-Jargon in der User-Copy.**

## Reuse (bestehende Keys — NICHT neu anlegen; verifiziert @ `efc8eaf7`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `remote_connect_trust_check` | CYP-471 (SHIPPED) | Trust-Check-Fortschrittszeile, an der der FirstUse-Screen ansetzt (§2.1) |
| `remote_connect_trust_changed` | CYP-471 (SHIPPED, **ein** terser String) | TrustChanged-Alarm (§2.3) — identisch, keine Varianten |
| `remote_connect_trust_provisional` | CYP-475 (SHIPPED) | Provisorisch-Disclosure am Trust-Screen (§2.1, bleibt ①a) |
| `remote_pop_enroll_*` / `remote_pop_pin_*` | CYP-460 (SHIPPED) | Backup-Codes-Anzeige hängt an den Enroll-Schritt (§3.1) |
| `remote_context_operating` / `WorkspaceTags.ROLE_INDICATOR` | CYP-429/Workspace | Host der „Fern-Sitzung beenden"-Kontrolle (§4.1) |

> **⚠ Drift-Notiz (Spec §0.1):** CYP-429 §8 `remote_trust_first_*`/`_wordlist_label`/`_hex_label`/`_qr_label`/`_oob`/
> `_pinned`/`_changed_title`/`_changed_body` wurden **nie gebaut**. Ihre Copy ist hier **1:1 in die ausgelieferte
> `remote_connect_trust_*`-Familie re-homed** (superseded CYP-429 §8). **Kein** Bezug mehr auf die alte `remote_trust_*`-Familie.

## Self-Validation
- **32 neue Keys**: 12 `remote_connect_trust_*` (§2) + 12 `remote_recovery_*` (§3) + 6 `remote_revoke_*` (§4) = **30
  Real-Keys** + **2 a11y** = **32**. Alle DE+EN befüllt, gleiche Argument-Anzahl je Sprache.
- **Argument-Keys (genau ein `%1$s`, DE=EN):** `remote_connect_trust_first_title`, `remote_revoke_ttl_hint` = **2**; alle
  übrigen **0 Arg**. DE/EN-Argument-Anzahl identisch.
- **Kein content-tragender/sensibler Klartext** — `%1$s` = Hub-Name / TTL-Dauer (Anzeige); **kein Secret/Code/Fingerprint-
  Rohwert** in einer Copy (Backup-Codes/Fingerprint/Wordlist/QR sind gerenderte Werte, keine Copy-Strings).
- **Kollision: 0** — die 12 neuen `remote_connect_trust_*` (nicht `check/changed/provisional`), `remote_recovery_*`,
  `remote_revoke_*` verifiziert greenfield gg. `strings.xml` @ `efc8eaf7`.
- **DE/EN-Parität:** jede Zeile beidseitig.
- Jeder Key ist in `remote-trust-recovery-ux-spec.md` und `-tags.md` verankert.
