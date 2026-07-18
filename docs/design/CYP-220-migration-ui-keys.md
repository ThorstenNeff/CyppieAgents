# CYP-220 — Migrations-Screen — i18n-Keys

> Owner: UIUX-Designer · Companion zu `CYP-220-migration-ui-spec.md` / `-tags.md` · Stand 2026-07-18 ·
> **Design-Pass, kein Bau.**
> Konvention gg. `app/shared/src/commonMain/composeResources/values/strings.xml` (**DE = Quelle/Default**)
> + `values-en/strings.xml` (EN), snake_case, positional `%1$s`/`%2$s`, verifiziert @ develop `da6437de`.
> Namensform `<area>_<subarea>_<element>` wie `settings_*` (`strings.xml:193-212`); a11y gespiegelt mit
> `a11y_`-Präfix.

## Reuse — bewusst KEINE neuen Keys

| Zweck | Bestehender Key | Fundort |
|---|---|---|
| Operator-Gate-Hinweis | `workspace_operator_only` | `strings.xml:520` (schon von `SettingsPanel` genutzt) |
| Dialog-Abbruch | `project_cancel` | Haus-Dialogmuster `ProjectManagementPanel.kt` |

> Kein drittes Vokabular für „Abbrechen"/„nur Operator" — beide Wörter existieren bereits im Produkt.

---

## 1. Sektion & Umfang (§1–§2)

| Key | DE | EN |
|---|---|---|
| `migration_section` | Datenbank-Migration | Database migration |
| `migration_section_project` | Projekt: %1$s | Project: %1$s |
| `migration_group_migratable` | Migrierbar | Can be migrated |
| `migration_group_unavailable` | Nicht migrierbar | Cannot be migrated |
| `migration_state_local` | Lokal (SQLite) | Local (SQLite) |
| `migration_state_bound` | In %1$s | In %1$s |
| `migration_state_migrating` | Wird migriert … | Migrating … |
| `migration_state_readonly` | Nur lesend | Read-only |

### Gründe (§2.2) — die Anti-Lügen-Zeilen

| Key | DE | EN |
|---|---|---|
| `migration_reason_no_export` | Kein Export-Pfad — dieser Store kann derzeit nicht kopiert werden. | No export path — this store cannot currently be copied. |
| `migration_reason_no_target` | Kein Postgres-Ziel — dieser Store bleibt lokal. | No Postgres target — this store stays local. |
| `migration_reason_infra` | Bleibt auf der Plattform — verweist auf die Datenbanken der anderen Stores. | Stays on the platform — it points to the other stores' databases. |
| `migration_reason_pending_decision` | Noch nicht freigegeben. | Not yet released. |

> `migration_reason_pending_decision` ist die **form-neutrale** Zeile für `hub_secret`/`role_assignments`:
> sie sagt weder „geht" noch „geht nicht" und nimmt die Auftraggeber-Weiche (§6.4) nicht vorweg.

---

## 2. Ziel-DSN (§3)

| Key | DE | EN |
|---|---|---|
| `migration_dsn_section` | Ziel-Datenbank | Target database |
| `migration_dsn_field_label` | Bezeichnung | Label |
| `migration_dsn_field_host` | Host | Host |
| `migration_dsn_field_port` | Port | Port |
| `migration_dsn_field_database` | Datenbank | Database |
| `migration_dsn_field_user` | Benutzer | User |
| `migration_dsn_field_sslmode` | SSL-Modus | SSL mode |
| `migration_dsn_field_password` | Passwort | Password |
| `migration_dsn_password_set` | Hinterlegt: %1$s | Stored: %1$s |
| `migration_dsn_password_unset` | Kein Passwort hinterlegt | No password stored |
| `migration_dsn_ssl_warning` | SSL ist abgeschwächt — die Verbindung kann unverschlüsselt laufen. | SSL is weakened — the connection may run unencrypted. |
| `migration_dsn_origin_managed` | Von der Plattform verwaltet | Platform-managed |
| `migration_dsn_origin_byo` | Eigene Datenbank — Betrieb und Sicherung liegen bei dir. | Your own database — you run and back it up. |

> `%1$s` in `migration_dsn_password_set` ist die **server-maskierte** Form `***last4`. Der Client kürzt
> **nie** selbst (Haus-Regel, `strings.xml:191`); es existiert auch kein Rückgabe-Pfad für das Klartext-
> Passwort (`DsnRegistry.kt:42-49`).

---

## 3. Start, Fenster, 409 (§4)

| Key | DE | EN |
|---|---|---|
| `migration_start` | Migrieren … | Migrate … |
| `migration_confirm_title` | %1$s nach %2$s migrieren? | Migrate %1$s to %2$s? |
| `migration_confirm_freeze` | Während der Migration werden Schreibvorgänge auf diesen Store abgelehnt. Lesen läuft weiter. Andere Stores sind nicht betroffen. | While the migration runs, writes to this store are rejected. Reads keep working. Other stores are unaffected. |
| `migration_confirm_source_kept` | Die bisherigen Daten bleiben erhalten — auch bei Abbruch. Gelöscht wird nichts. | The existing data is kept — even if this is aborted. Nothing is deleted. |
| `migration_confirm_start` | Migration starten | Start migration |
| `migration_window_active` | Schreibvorgänge auf %1$s werden gerade abgelehnt. | Writes to %1$s are currently being rejected. |
| `migration_write_rejected` | Wird gerade migriert. Bitte nach dem Umschalten erneut versuchen. | Migration in progress. Please try again after the switch completes. |

---

## 4. Phasen (§5)

| Key | DE | EN |
|---|---|---|
| `migration_phase_window` | Schreibsperre aktiv | Write freeze active |
| `migration_phase_copy` | Daten werden kopiert | Copying data |
| `migration_phase_verify` | Zeilenzahl und Prüfsumme werden geprüft | Verifying row count and checksum |
| `migration_phase_rebind` | Umschalten auf das Ziel | Switching over to the target |
| `migration_phase_value_rows` | %1$s Zeilen | %1$s rows |
| `migration_phase_value_verified` | %1$s / %2$s · Prüfsumme identisch | %1$s / %2$s · checksum identical |
| `migration_slow` | Läuft noch. Große Stores können mehrere Minuten dauern. | Still running. Large stores can take several minutes. |

> `migration_phase_value_*` sind die **Belegwerte, die den „fertig"-Glyph ersetzen** (Spec §5.2). Fehlt der
> Wert, bleibt die Zeile wertlos — **nie „0"**.

---

## 5. Ergebnis, Rollback, Decommission (§6–§7)

| Key | DE | EN |
|---|---|---|
| `migration_receipt_ok` | %1$s Zeilen kopiert und geprüft (Prüfsumme identisch). Jetzt gebunden an %2$s. | %1$s rows copied and verified (checksum identical). Now bound to %2$s. |
| `migration_receipt_source_kept` | Die bisherigen Daten liegen unverändert weiter am alten Ort. | The previous data remains untouched in its old location. |
| `migration_rollback` | Zurücksetzen | Roll back |
| `migration_rollback_explain` | Setzt auf die bisherigen Daten zurück. Es geht nichts verloren. | Switches back to the previous data. Nothing is lost. |
| `migration_decommission` | Alte Daten löschen … | Delete old data … |
| `migration_decommission_warning` | Löscht die bisherigen Daten am alten Ort. Danach ist kein Zurück mehr möglich. | Deletes the previous data in its old location. After this there is no way back. |
| `migration_decommission_confirm` | Endgültig löschen | Delete permanently |

> `migration_decommission_warning` ist die **einzige** Stelle im Flow mit „kein Zurück" — und sie ist wahr
> (`StoreMigrator.kt:38-39`: der Migrator löscht die Quelle nie; Decommission ist der separate Schritt).

---

## 6. Fehler (§7) — jede Zeile endet auf „Nichts umgestellt."

| Key | DE | EN |
|---|---|---|
| `migration_error_verify` | Prüfung fehlgeschlagen: %1$s von %2$s Zeilen angekommen. Nichts umgestellt. | Verification failed: %1$s of %2$s rows arrived. Nothing was switched over. |
| `migration_error_checksum` | Zeilenzahl stimmt, Inhalt nicht. Nichts umgestellt. | Row count matches, content does not. Nothing was switched over. |
| `migration_error_connect` | Ziel nicht erreichbar. Nichts umgestellt. | Target not reachable. Nothing was switched over. |
| `migration_error_unknown` | Migration fehlgeschlagen: %1$s. Nichts umgestellt. | Migration failed: %1$s. Nothing was switched over. |

> `%1$s` in `migration_error_unknown` ist `MigrationAuditEntry.error` — ausdrücklich **sekret-frei**
> (`MigrationAudit.kt:16-18`), darf also unverändert durchgereicht werden.

---

## 7. Historie (§8) — die zwei getrennten Leerzustände

| Key | DE | EN |
|---|---|---|
| `migration_history_section` | Verlauf | History |
| `migration_history_empty` | Noch keine Migration. | No migration yet. |
| `migration_history_unavailable` | Kein Migrations-Protokoll verfügbar — der Verlauf wird nicht aufgezeichnet. | No migration log available — history is not being recorded. |

> Die Trennung ist der Kern: „nichts passiert" ≠ „nichts wird aufgezeichnet". Hängt an **BE-1**; ohne das
> Server-Flag wird §8 **nicht gebaut** (Spec §8).

---

## 8. a11y

| Key | DE | EN |
|---|---|---|
| `a11y_migration_phase_running` | %1$s — läuft | %1$s — running |
| `a11y_migration_state_pending` | ausstehend | pending |
| `a11y_migration_state_running` | läuft | running |
| `a11y_migration_state_done` | fertig | done |
| `a11y_migration_state_failed` | fehlgeschlagen | failed |
| `a11y_migration_dsn_password` | Passwort der Ziel-Datenbank | Target database password |
| `a11y_migration_dsn_password_reveal` | Passwort anzeigen | Show password |
| `a11y_migration_dsn_password_hide` | Passwort verbergen | Hide password |

> `a11y_migration_state_done` ist **nicht optional**: weil „fertig" bewusst **keinen** Glyph trägt (Spec
> §5.2), muss der Zustand für Screenreader explizit als `stateDescription` gesetzt werden — sonst wäre die
> glyphlose Lösung eine a11y-Regression.

---

## 9. Self-Validation

- **61 neue Keys = 53 sichtbar + 8 a11y.** Abschnittsweise nachgezählt und aufaddiert:
  §1 = 12 (8 Zustand/Überschrift + 4 Gründe) · §2 = 13 · §3 = 7 · §4 = 7 · §5 = 7 · §6 = 4 · §7 = 3
  → **53 sichtbar**; §8 = **8 a11y**. Summe **61**.
- **Argument-Parität DE↔EN je Zeile geprüft:** 2 Args bei `migration_confirm_title`,
  `migration_phase_value_verified`, `migration_receipt_ok`, `migration_error_verify`; 1 Arg bei
  `migration_section_project`, `migration_state_bound`, `migration_dsn_password_set`,
  `migration_window_active`, `migration_phase_value_rows`, `migration_error_unknown`,
  `a11y_migration_phase_running`; alle übrigen 0 Args. **DE == EN in jeder Zeile.**
- **Kein sensibler Klartext:** kein Passwort, kein DSN mit Credentials, kein Token. Interpoliert werden nur
  Store-/DSN-**Labels**, Zeilen**zahlen** und der server-maskierte `***last4`.
- **Kollisionsprüfung** gg. `strings.xml` @ `da6437de`: Präfix `migration_` ist **frei** (kein bestehender
  Key beginnt so) — vor dem Bau erneut prüfen, da `strings.xml` geteilt ist.
- **Reuse belegt:** `workspace_operator_only` + `project_cancel` statt Neuanlage.
- **Ton-Konsistenz:** kein Alarm-Duktus außerhalb der ERROR-Zeilen; `migration_write_rejected` bewusst
  aufschiebend („nach dem Umschalten erneut"), nicht fehlerhaft — passend zu `EFFECT_DEFERRED` (Spec §4.2).
- **⚠ Geteilte Datei:** `strings.xml` wird von mehreren Modulen gelesen — beim Landen dieser Keys muss der
  konsumierende Modul-Check neu synchronisiert werden. **An den Entwickler flaggen** (Haus-Regel).
