# Recovery-Codes-Save — Build-Pack (robuste Runde, bau-fertig) — Dev-Impl-Vorlage

> Owner: UIUX-Designer · **Bau-fertige Finalisierung** der robusten Runde (PO-GO nach Live-Retry) · Stand 2026-07-15 ·
> **Design/Copy, kein Code.** Companion zu `recovery-codes-save-ux-fix-spec.md` (Design-Rationale). Gegroundet READ-ONLY
> gg. develop `a1593d19`. **Dev zieht 1:1 (kein Ableiten = kein Drift).** Ticket-Key: PO beim Dispatch.
>
> **Scope = die robuste Runde (①②③), NICHT der akute CYP-595/596-Subset:** `_window_expired`/`_reconnect` +
> `codesWindowExpired`/`codesReconnect` gehören zu **CYP-595** (Expiry-Zustand, separat gemappt §10 der Spec) — **nicht hier**.

## 1. ① Tunnel-Liveness-Semantik (Backend — Verhaltens-AC, KEINE neuen Keys)
Die provisorische Enrollment wird **durch die Tunnel-Liveness + die Nutzer-Aktion (`SavedAck`/`abort`) begrenzt — NICHT
durch eine ~30-s-Uhr.**
- **AC-1:** solange der Tunnel lebt **und** der Reveal aktiv ist, **verwirft** der Hub die Provisorik **nicht** zeitbasiert.
- **AC-2:** Auflösung nur durch (a) `SavedAck` (→ finalize → CONNECTED) **oder** (b) expliziten `abort()` (Client sendet
  ihn schon bei leave/teardown, `HubConnectViewModel.kt:374`, fail-closed → Hub verwirft).
- **AC-3 (falls Obergrenze nötig):** großzügig (Minuten-/Tunnel-Lebensdauer), **Client-Keepalive** hält sie aktiv, solange
  der Reveal sichtbar ist; **bei Ablauf ein signalisiertes Reject** (nicht stiller Drop) → der Client rendert den
  **CYP-595-Expiry-Zustand** (nicht in diesem Pack). **Kein stiller 30-s-Kill.**

## 2. ② + ③ Neue Keys — paste-ready Strings-Manifest (Dev pullt 1:1)
> **DE** → `app/shared/src/commonMain/composeResources/values/strings.xml` · **EN** → `…/values-en/strings.xml`.
> Apostroph **roh** (Haus-Konvention der recovery-Sektion: „You'll"/„can't"/„I've" roh). Alle **0 Args**.

**DE (`values/strings.xml`):**
```xml
<string name="remote_recovery_codes_copied">In die Zwischenablage kopiert.</string>
<string name="remote_recovery_codes_save_file">In Datei speichern</string>
<string name="remote_recovery_codes_saved_file">Als Datei gespeichert.</string>
<string name="remote_recovery_codes_no_rush">Nimm dir Zeit — es gibt kein Zeitlimit. Speichere die Codes, dann bestätige.</string>
<string name="a11y_remote_recovery_codes_copied">Wiederherstellungs-Codes in die Zwischenablage kopiert.</string>
```
**EN (`values-en/strings.xml`):**
```xml
<string name="remote_recovery_codes_copied">Copied to clipboard.</string>
<string name="remote_recovery_codes_save_file">Save to file</string>
<string name="remote_recovery_codes_saved_file">Saved to file.</string>
<string name="remote_recovery_codes_no_rush">Take your time — there's no time limit. Save the codes, then confirm.</string>
<string name="a11y_remote_recovery_codes_copied">Recovery codes copied to clipboard.</string>
```
**5 Strings (4 Realkeys + 1 a11y), DE==EN Arg-Anzahl (0), 0 Kollision @ `a1593d19` (grep-verifiziert).**

## 3. Neue Tags — `RemoteRecoveryTags.kt` (exakte const-Ergänzungen)
```kotlin
const val CODES_COPIED = "remote.recovery.codesCopied"       // §2a copy-Feedback (Polite)
const val CODES_SAVE_FILE = "remote.recovery.codesSaveFile"  // §2b Save-Button
const val CODES_SAVED_FILE = "remote.recovery.codesSavedFile"// §2b Save-Bestätigung (Polite)
const val CODES_NO_RUSH = "remote.recovery.codesNoRush"      // §3 „nimm dir Zeit"
```
**4 Tags, im bestehenden `RemoteRecoveryTags`-Object, `remote.recovery.*`, camelCase `[A-Za-z0-9-]+`, 0 Kollision.**

## 4. Insertion-Point-Map — `RecoveryCodesReveal.kt` (line-verankert @ `a1593d19`)
| Ort | Änderung |
|---|---|
| **L43** (nach `val clipboard = …`) | `var copied by remember { mutableStateOf(false) }` **+** `var savedToFile by remember { mutableStateOf(false) }` (Muster = `DicewareReveal`). |
| **L68-70** (die Copy-`TextButton`) | `onClick` erweitern: `{ clipboard.setText(...); copied = true }`. **Direkt darunter:** `if (copied) Text(stringResource(remote_recovery_codes_copied), style=bodySmall, color=onSurfaceVariant, Modifier.testTag(CODES_COPIED).semantics{ liveRegion=Polite; contentDescription=a11y_remote_recovery_codes_copied })`. **Nur nach echtem Copy** (kein vorzeitiges). Kein Glyph. |
| **neu, nach dem Copy-Block** | „In Datei speichern"-`TextButton` (`remote_recovery_codes_save_file`, `testTag(CODES_SAVE_FILE)`), `onClick` = `saveRecoveryCodesToFile(codes)` (§5); bei Erfolg `savedToFile = true`. **Darunter:** `if (savedToFile) Text(remote_recovery_codes_saved_file, onSurfaceVariant, testTag(CODES_SAVED_FILE), liveRegion=Polite)`. |
| **vor L83** (der Ack-`Button`) | `Text(stringResource(remote_recovery_codes_no_rush), style=bodySmall, color=onSurfaceVariant, Modifier.testTag(CODES_NO_RUSH))`. Über dem Ack, unter der `_no_central`-Stakes-Zeile. |
| **L83** (Ack-`Button`) | **unverändert** — bleibt primärer Vorwärts-Weg, **nicht** auf `copied`/`savedToFile` gegatet (Nutzer-Wahl: manche fotografieren). |

**Unverändert:** `_title`/`_body`/`_single_use`/`_no_central`/`SelectionContainer`/`CODES_ACK` — kein Churn, rein additiv.

## 5. Save-to-File-Seam (NEU — kein bestehendes Client-`expect/actual`, Dev legt an)
Es gibt **kein** wiederverwendbares commonMain-Client-File-Write (die Store-Treffer sind server-seitig). Also **neuer**
Platform-Seam:
```kotlin
// commonMain
expect suspend fun saveRecoveryCodesToFile(codes: List<String>): Boolean // true = gespeichert
```
- **Desktop (jvm):** nativer Datei-Speichern-Dialog (`java.awt.FileDialog`/Swing) → `codes.joinToString("\n")` schreiben.
- **Web (wasmJs):** Blob-Download (`text/plain`).
- **Android/iOS:** SAF / Share-Sheet (MVP: Desktop-first, `false`-Stub tolerierbar bis Bedarf).
- **Honesty:** `savedToFile`/„Als Datei gespeichert." **nur nach echtem** Erfolg (Rückgabe `true`); Abbruch/Fehler ⇒ **keine**
  Bestätigung (kein Fake-Success). Kein Pfad-Zwang in der Copy (optional `%1$s`=Dateiname später).

## 6. Acceptance-Teeth (meine §-QA, sobald gebaut)
1. Copy-Klick → **sichtbare** „In die Zwischenablage kopiert."-Zeile (`CODES_COPIED`, Polite), **nur nach** echtem Copy.
2. „In Datei speichern" schreibt clipboard-**unabhängig**; „Als Datei gespeichert." **nur nach** echtem Erfolg.
3. „Nimm dir Zeit"-Zeile präsent über dem Ack; Ack **nicht** auf Copy/Save gegatet; `_no_central`-Stakes bleiben davor.
4. **Kein 30-s-Kill** (①): ein aktiv lesender Mensch wird nicht rausgeworfen (Backend-AC §1).
5. Töne neutral/`onSurfaceVariant` (Feedback/Guidance), **kein** Erfolgs-Grün, **kein** Glyph auf den Info-Zeilen; kein `errorContainer`.
6. DE=Default + EN-Parität; Live-Regions **Polite** (Copy/Save-Feedback).
7. Bestehende Reveal-Elemente unverändert (kein Churn).

## 7. Self-Validation
- **Bau-fertig:** paste-ready XML (DE+EN) + exakte Tag-consts + line-verankerte Insertion-Points + der neue File-Seam — Dev zieht 1:1.
- **Gegroundet** gg. `RecoveryCodesReveal.kt`/`RemoteRecoveryTags.kt`/recovery-Strings @ `a1593d19` (file:line); Apostroph-Konvention (roh) verifiziert; 5 Keys + 4 Tags **0-Kollision** grep-belegt.
- **Reuse-first:** Copy-Feedback = `DicewareReveal`-Muster · Tags = `remote.recovery.*` · bestehende Copy/Confirm/Ack unverändert.
- **Scope sauber getrennt:** robuste Runde (①②③) hier; CYP-595-Expiry-Subset separat (Spec §10). Kein Bau, docs-only auf `feature/recovery-codes-save-ux-fix`.
