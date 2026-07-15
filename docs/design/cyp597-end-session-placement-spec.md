# CYP-597 — „Fern-Sitzung beenden" oben rechts: **Platzierungs-Spec (Reuse-Move, kein Neubau)**

> Owner: UIUX-Designer · **Design/Spec + Verify, kein Bau** (die Wiring → Dev5) · Stand 2026-07-15 · Gegroundet
> READ-ONLY gg. develop `a1593d19` (file:symbol). Branch `feature/CYP-597-end-session-placement-spec`, docs-only.
> **Scope-Leitsatz: 100 % Reuse des bestehenden `RemoteRevokeControl` (CYP-479/480). KEIN neues Control, KEINE
> neuen Keys/Tags/Copy, KEIN Confirm-Guard-Neubau — sonst 3. Duplikat einer sicherheits-sensiblen destruktiven
> Aktion (Anti-Pattern).** Diese Spec bewegt/erhöht nur die **Auffindbarkeit**, sie erfindet nichts.

## 0. Was schon existiert (Reuse-Inventar — nichts davon neu bauen)
Der „End-remote-session"-Button **inkl. Confirm-Guard und ehrlicher Disclosure ist bereits gebaut+getestet:**
- **Control:** `RemoteRevokeControl.kt` (`connect/`, CYP-479 §4.1 / CYP-480 Fläche ③). Phasen `IDLE→CONFIRMING→ENDED`.
- **Copy (`values/strings.xml:740–745`, DE + EN-Parität):** `remote_revoke_end_action` „Fern-Sitzung beenden" ·
  `remote_revoke_confirm_title` „Fern-Sitzung beenden?" · `remote_revoke_confirm_body` „Diese Verbindung wird sofort
  getrennt." (garantiert) · `remote_revoke_scope_note` „Das beendet nur diese Verbindung auf diesem Gerät — es
  widerruft keine anderen Sitzungen." (advisory, **kein Global-Revoke**) · `remote_revoke_ttl_hint` (seam-gated) ·
  `remote_revoke_ended` „Fern-Sitzung beendet."
- **Tags:** `RemoteRevokeTags.END/CONFIRM/SCOPE_NOTE/TTL_HINT/ENDED` (DS-frozen, geteilt mit QA CYP-7).
- **Confirm-Guard:** `AlertDialog` (House-Scaffold) mit **Abbrechen** (`agent_cancel`, Dismiss = sicherer Default) —
  **das IST der „versehentliches Beenden verhindern"-Guard.** Neutral, kein Scare-Rot.
- **Teardown (garantiert):** Confirm → `onEndSession()` = `HubConnectViewModel.backToHubList()` / `RemoteHubSession.close()`.
- **Tests:** `RemoteRevokeControlRenderTest` + `Cyp482SbMountsRenderTest` + `Cyp523SeqB…` + `Cyp533RemoteOperatingChromeTest`.

**Heutige Platzierung (gegroundet):** In `RemoteOperatingChrome.kt` (CONNECTED-Zweig) rendert eine
`Row(fillMaxWidth)` → `RemoteContextBanner` (`weight(1f)`) **+ trailing `RemoteRevokeControl`**. Diese Row sitzt im
`remoteContextBanner`-Slot der `ProjectSwitcherBar` (oben, über dem Fenster-Host). **G6-Invariante: revoke
present-iff `RemoteConnState.CONNECTED`** (absent Local/RECONNECTING/LOST — RECONNECTING hat bewusst KEIN revoke,
„session is not steadily connected"). Der globale Bar-`trailing`-Slot (echte obere-rechte Ecke) hält heute nur
**persönliche Prefs** (`ComposerHistorySizeStepper`, `ThemeModeToggle`).

## 1. Der Gap (ehrlich, konditional — ich behaupte die genaue Wurzel nicht)
Der Control ist heute ein **bloßer `TextButton`**, trailing in einem Banner-Streifen. Zwei plausible Lesarten der
Live-Beobachtung „nicht oben-rechts / nicht auffindbar" — **PO1 klärt die echte am Live-Deploy; ich asserte keine:**
- **(L1) Prominenz:** der Text-Link im Banner liest nicht als „**der** End-Session-Control" → Nutzer findet ihn nicht.
- **(L2) Orts-Erwartung:** Nutzer sucht die **App-Chrome-Ecke oben-rechts** (neben Theme-Toggle), nicht den Session-Banner.

Beide sind **Platzierungs-/Prominenz-Deltas — 100 % Reuse.** Die Spec deckt beide Lesarten (unten A/B), damit
Dev5 unabhängig vom exakten PO1-Befund den richtigen Move zieht.

## 2. Der Platzierungs-Move
### 2A. Empfohlen — im CONNECTED-Context-Row, aber prominent + klar rechts (deckt L1, und L2 „oben-rechts-Gefühl")
Den Control **an seine semantische Kopplung gebunden lassen** (er beendet **genau die** „du operierst auf Hub X"-
Session — G6), aber die **Affordanz heben**, sodass er als der obere-rechte Session-Control liest:
- Rechtsbündig am Ende der Context-Row (ist er schon) — **visuell als Button, nicht bloßer Text-Link** (klarer
  umrandeter/gefüllt-neutraler Affordance-Stil; House-Button-Rolle, weiter **neutral, kein Scare-Rot**).
- Ausreichendes Touch-Target (≥ House-Minimum) + sichtbarer Fokus-Ring.
- **Kein Relozieren** → keine neue Tap-Adjazenz zu Casual-Toggles, keine Entkopplung von der Session-Identität.

### 2B. Alternative — in den globalen Bar-`trailing`-Slot (echte obere-rechte Ecke), NUR falls PO1-Befund = L2
Falls die Live-Obs eindeutig „Nutzer suchen die App-Ecke" ist, den **einen** Control dorthin **verschieben** —
mit drei harten Leitplanken:
1. **Genau EIN Control (verschieben, nicht duplizieren):** die G6-Gating (`present-iff CONNECTED`, absent Local/
   RECONNECTING/LOST) **wandert mit** — sonst zwei End-Session-Affordanzen = genau das Duplikat, das wir vermeiden.
2. **Visuelle Trennung** von den Casual-Prefs (Theme/History): ein destruktiver Control darf **nicht** tap-adjazent
   zu häufig getippten Toggles sitzen (Divider/Spacing; eigene Gruppe).
3. **Confirm-Guard bleibt** — Ecken-Prominenz **erhöht** die Fehl-Tap-Wahrscheinlichkeit → der Guard (Dialog +
   Abbrechen-Default) ist **load-bearing**, nicht wegoptimieren.

> **Empfehlung: 2A**, außer PO1s Live-Obs sagt klar L2. Grund: G6 koppelt den Revoke bewusst an den „operierst-auf-
> Hub-X"-Banner (du beendest **diese** Session) — die Ecke neben persönlichen Toggles entkoppelt diese Bedeutung und
> schafft Fehl-Tap-Adjazenz. 2A macht ihn auffindbar **ohne** die Semantik/Sicherheit zu verwässern.

## 3. Honesty- & Safety-Invarianten (alle ERHALTEN — keine wird neu designt)
- **Confirm-Guard** (Dialog + Abbrechen als sicherer Default) bleibt — der Accidental-End-Schutz. Reuse, unverändert.
- **Ehrliche Disclosure** bleibt wortgleich: `_confirm_body` „…sofort getrennt" (garantiert) + `_scope_note` „kein
  Global-Revoke" (advisory) + `_ttl_hint` **seam-gated `null≠0`** (nie erfundene Expiry). Kein Overstatement.
- **State-gated:** present-iff CONNECTED; **nie** während RECONNECTING (dort gilt andere Teardown-Semantik) oder Local/LOST.
- **Neutraler Ton, kein Scare-Rot** (auch bei erhöhter Prominenz — Prominenz ≠ Alarm).
- **Guaranteed-vs-Advisory-Trennung** bleibt intakt (garantierter lokaler Teardown vs advisory Scope/TTL).

## 4. a11y
- Der END-Control ist **in der Fokus-Reihenfolge erreichbar**, sobald CONNECTED (Tastatur + Screenreader).
- `RemoteRevokeTags.END`/`.CONFIRM`/`.SCOPE_NOTE` **erhalten** (Test-Kontrakt QC-7 — nicht umbenennen).
- Confirm-`AlertDialog` behält Focus-Trap + `enableTestTagsAsResourceId()`; **Abbrechen = Default-Dismiss**.
- Label bleibt textuell (`remote_revoke_end_action`) — **Bedeutung nie nur über Position/Icon** getragen (bei 2B
  kein icon-only-Ecken-Button ohne sichtbares/contentDescription-Label).

## 5. Keys/Tags — **keine neuen** (der Reuse-Kern)
- **Copy:** 0 neue Keys. `remote_revoke_*` unverändert wiederverwenden.
- **Tags:** `RemoteRevokeTags.*` unverändert. **Einzige mögliche Additive** *falls* 2B einen distinkten Mount-Site-
  Test-Hook braucht: ein Mount-Container-Tag — **bevorzugt jedoch `RemoteRevokeTags.END` weiter als Anker.** Jeder
  Tag-Zusatz wäre shared/QA-CYP-7 → **Sync-Flag, landet mit der Impl** (nicht vorab). Default: **keine** neuen Tags.

## 6. Acceptance-Teeth (Verify — headless-messbar vs guided-runtime)
**Headless (`runComposeUiTest`, meine Lane — an Dev5s Move gehängt):**
1. CONNECTED → **genau ein** `RemoteRevokeTags.END` im Baum, im oberen/rechten Bereich (kein zweites End-Session-Affordance).
2. Local / LOST / **RECONNECTING** → `RemoteRevokeTags.END` **absent** (G6 erhalten nach dem Move).
3. Klick END → Confirm-Dialog (`.CONFIRM` + Abbrechen); `.SCOPE_NOTE` present; TTL-Hint nur wenn `ttl != null`.
4. Ton neutral (kein Error-/Scare-Farb-Token); Label = `remote_revoke_end_action` (nicht icon-only bei 2B).
5. (2B) keine Tap-Adjazenz-Regression: der END-Control ist von den Pref-Toggles separiert (eigene Gruppe/Divider).
**Guided-runtime (kein JS-Browser → ich leite via PO-Relay, Mensch beobachtet):**
6. „liest als **der** oben-rechts End-Session-Control / ist auffindbar" = **visuelles Urteil** = geführte Runde
   (bestätigt zugleich, welche Lesart L1/L2 die echte war). **Kein Pixel-Claim ohne Bestätigung.**

## 7. Scope-Grenze & Übergabe
- **Nur Platzierung/Prominenz.** Teardown-Logik (`backToHubList`/`close`), Confirm-Guard, gesamte Copy = **unangetastet**.
- **Dev5** zieht die kleine Wiring (2A Prominenz-Style **oder** 2B Reloc gemäß PO1-Befund). **Ich verifiziere** (Teeth
  1–5 headless; 6 geführt). **Off develop `a1593d19`, rebase-before-push, scoped-delta nur CYP-597.**
- **Branch-Punkt offen:** exakte Lesart (L1 Prominenz vs L2 Orts-Erwartung) ⇒ PO1s Live-Obs. Bis dahin: 2A als Default
  bauen (auffindbarer, semantik-erhaltend); 2B nur auf klaren L2-Befund.

## 8. Self-Validation
- Gegroundet gg. `RemoteRevokeControl.kt` / `RemoteOperatingChrome.kt` / `AgentShell.kt` (Seam #8, `remoteContextBanner`-
  Slot / `trailing`-Slot) / `values/strings.xml:740–745` @ `a1593d19` — Platzierung + G6-Gating + Confirm-Guard code-belegt.
- **Reuse-first, 0 neue Keys, 0 neue Tags** (Default) — der ganze Sinn: kein Duplikat.
- **Honesty:** Confirm-Guard + Scope-Note (kein Global-Revoke) + TTL-seam-gated + state-gated + neutral erhalten; Prominenz ≠ Alarm.
- **Ehrliche Konditionalität:** ich asserte die genaue Live-Wurzel (L1/L2) nicht — die Spec deckt beide, PO1s Live-Obs entscheidet.
