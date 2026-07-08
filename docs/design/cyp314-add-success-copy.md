# Design-Spec — CYP-314: Erfolgs-Copy fürs Agent-Anlegen

> **Status:** DESIGN-SPEC / reine Copy (kein Bau) · Owner: UIUX · Story (Low, Follow-up CYP-312) · docs-only auf `feature/CYP-314-add-success-copy-spec` (off develop `403120a`).
> **Auftrag (PO):** Copy für den panel-level Hinweis, der **nach erfolgreichem Anlegen** eines Agenten erscheint. Dialog ist da bereits geschlossen, der Agent ist bereits in der Liste. Ton = INFO (kein grüner SUCCESS-Ton — den gibt es im Design-System nicht). Ein neuer Copy-Key + Tag `ADD_SUCCESS`, DE+EN atomar. Referenziert den Agentennamen (Dev hält ihn als `addSuccessName`).
> **Grounding (echter Code):** `AgentManagementPanel.kt` — panel-Column Z.128-188; die einzige **immer gemountete** Region, die das Schließen des Dialogs überlebt, ist diese Top-Column (zwischen Add-Button Z.146 und Error/Empty Z.150). `AgentManagementViewModel.kt` — `AgentMgmtUiState` Z.47; `confirmAdd().onSuccess` Z.185-187 (dort wird `addOpen=false` gesetzt) ist der Set-Punkt; `editEffectHint` (Z.67, gecleart in `openEdit`/`closeEdit`/`setEdit*` Z.227-249) ist der Lifecycle-Präzedenzfall. `AgentMgmtTags.kt` — CYP-86-Add-Block Z.30-43. `TonedHint.kt` — `enum HintTone{EFFECT_DEFERRED,GATED,INFO,ERROR}` (kein SUCCESS), Signatur `TonedHint(text, tone, tag, modifier)` Z.41.

---

## §1 — Was hier entschieden wird

Der **exakte DE/EN-String** (inkl. Platzhalter), der **Key-Name**, der **Tag**, die **Platzierung** und die **a11y-Note** für die Erfolgsmeldung. Kein Code. Dev baut dagegen; `addSuccessName` (State-Feld) existiert **noch nicht** und wird von Dev ergänzt (siehe §6).

---

## §2 — Copy (DE + EN) — Empfehlung

| | DE | EN |
|---|---|---|
| **Empfehlung (A)** | `Agent „%1$s" angelegt.` | `Agent "%1$s" created.` |

**Warum diese Formulierung:**
- **Vokabular-Reuse, keine Divergenz.** Das Verb spiegelt exakt den Bestätigungs-Button `agent_add_confirm` = „Anlegen" / „Create" und den bestehenden `agent_add_spawn_hint` = „Angelegt." / „Created." → **kein** dritter Wortlaut fürs selbe Konzept.
- **Platzhalter-Präzedenz.** `%1$s` ist die etablierte Namens-Parametrisierung; direkter Vorläufer ist `agent_add_project_scope_note` = „Wird im aktiven Projekt %1$s angelegt." / „Added to the active project %1$s." — **gleicher Mechanismus** (positional `%1$s`, gefüllt via `stringResource(Res.string.agent_add_success, name)`).
- **Ehrlich, ohne Überzeichnung (Disclosure-Honesty, meine Kern-Verantwortung).** „angelegt" / „created" behauptet **exakt** den erfolgten Vorgang: der Agent ist **erstellt und in der Liste**, **nicht** gestartet/aktiv/laufend. Kein Wort suggeriert einen Zustand, der nicht garantiert ist. Der Laufzeit-Status (gestoppt) wird an der **Listen-Zeile** ehrlich getragen, nicht vom Hinweis behauptet — „created ≠ running" (analog zu failed≠remote / null≠0 / saved≠active).

---

## §3 — Honesty-Fund: die „noch nicht gestartet"-Zeile NICHT verdoppeln

`agent_add_spawn_hint` (EN „Created. The agent is not started yet — start it via the lifecycle controls." / DE „Angelegt. Der Agent startet noch nicht – über die Lifecycle-Steuerung starten.") trägt die „create ≠ run"-Aufklärung **bereits** — aber **dialog-scoped**: er lebt **im** AddDialog (AgentManagementPanel.kt:346) und **verschwindet mit dem Schließen**. Die panel-level Erfolgs-Copy ist damit die **erste persistente** Fläche nach dem Schließen.

**Design-Entscheidung:** Die Erfolgs-Copy bleibt bei der **knappen** Bestätigung (§2-A) und **wiederholt nicht** den vollen „…über die Lifecycle-Steuerung starten"-Satz. Grund:
1. Die Lifecycle-Steuerung ist nach dem Schließen **on-screen** an der frisch sichtbaren (gestoppten) Listen-Zeile — die verbale Wegbeschreibung aus dem Modal ist außerhalb des Modals weniger nötig.
2. Ein zweiter, leicht abweichender Klon des „nicht gestartet"-Satzes wäre genau die **divergente Doppel-Copy**, die wir vermeiden (ein Konzept = ein Wortlaut = ein Home).

**Falls das Team die persistente „wie starte ich"-Führung nach dem Schließen ausdrücklich will** (weil `spawn_hint` mit dem Dialog verschwindet), dann **eine** bewusste Wahl treffen, nicht driften — Variante B als **Reuse**, nicht als neuer Satz:

| | DE | EN |
|---|---|---|
| **Variante B (opt.)** | `Agent „%1$s" angelegt – noch nicht gestartet.` | `Agent "%1$s" created — not started yet.` |

B hängt nur den **Zustands-Halbsatz** an (kurz, ohne die volle Wegbeschreibung zu duplizieren) und bleibt INFO. **Meine Empfehlung bleibt A** (knapp, die Listen-Zeile + Lifecycle-Controls tragen den Rest); B nur, wenn der Auftraggeber/Dev die post-close-Führung ausdrücklich wünscht. **Nicht beides** — ein Key, ein Wortlaut.

---

## §4 — Ton, Platzierung, Rendering

- **Ton:** `HintTone.INFO` (verbindlich — kein grüner SUCCESS, existiert im System nicht). Rendert `secondary`-Text mit `i`-Glyph — konsistent mit `agent_add_autofields_note`/`agent_add_project_scope_note`.
- **Widget:** bestehendes `TonedHint(text, HintTone.INFO, AgentMgmtTags.ADD_SUCCESS)` — **kein** neues Hint-Widget.
- **Platzierung:** in der panel-Top-Column, **direkt unter dem Add-Button** (AgentManagementPanel.kt:146), **vor** dem Error/Empty/List-Block (Z.150). So steht die Bestätigung am selben Ort, an dem der Auslöser (Add-Button) sitzt, und über der Liste, in der der neue Agent nun erscheint.
- **Nur rendern, wenn vorhanden:** `state.addSuccessName?.let { name -> TonedHint(stringResource(Res.string.agent_add_success, name), HintTone.INFO, AgentMgmtTags.ADD_SUCCESS) }`. Ein null/leerer Name darf **nie** „Agent „" angelegt." erzeugen — der `?.let`-Guard erledigt das; Dev setzt `addSuccessName = f.name.trim()` (Name ist im Add-Form Pflichtfeld).

---

## §5 — a11y (liveRegion / announce)

**Announcement nötig — nicht optional.** Der Hinweis erscheint **reaktiv nach einer asynchronen Aktion**: der Dialog (der den Fokus hielt) ist geschlossen, der Fokus ist verschoben. Ein rein visueller Hinweis erreicht einen Screenreader-Nutzer, dessen Fokus am nun verschwundenen Dialog hing, **nicht**.

- **Empfehlung:** die Erfolgs-Copy mit `liveRegion = LiveRegionMode.Polite` versehen (announce, nicht-unterbrechend) — via den bestehenden `AnnouncingHint`-Wrapper, falls er `TonedHint` umschließen kann, sonst `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` am Hint. Gleiches `Polite`-Muster wie `LoadErrorRetry` (CYP-288).
- **Warum Polite (nicht Assertive):** eine Erfolgsbestätigung ist nicht dringlich genug, den laufenden SR-Sprechfluss zu unterbrechen; sie soll nach dem aktuellen Satz angesagt werden.
- **Kein separater a11y-Key nötig:** die sichtbare Copy IST die Ansage (Name inklusive) — anders als Icon-only-Affordanzen braucht dieser Text-Hinweis keinen zusätzlichen `contentDescription`.

---

## §6 — State-Lifecycle (an Dev, nur zur Erdung — kein UIUX-Bau)

`addSuccessName: String? = null` existiert **noch nicht**. Vorschlag zur Erdung, spiegelt den `editEffectHint`-Präzedenzfall (AgentManagementViewModel.kt):
- **Deklaration:** in `AgentMgmtUiState` bei den Add-Feldern (`addOpen`/`addForm`/`addError`, Z.54-57).
- **Set:** in `confirmAdd().onSuccess` (Z.185-187) — `addSuccessName = f.name.trim()`, gemeinsam mit `addOpen=false, addError=null`.
- **Clear:** dieselben Sites, an denen `addError` heute zurückgesetzt wird — `openAdd()` (Z.149), `closeAdd()` (Z.154), jeder `setAdd*`-Mutator (Z.155-160). Damit verschwindet die Bestätigung, sobald der Nutzer erneut mit dem Add-Flow interagiert (identisches Verhalten wie `editEffectHint`). Ein Auto-Dismiss/Timeout ist **nicht** MVP; falls gewünscht → separates Follow-up.

> Das ist Dev-Territorium (State-Feld + Verdrahtung). Hier nur als Grounding, damit Key/Tag/Copy sauber andocken.

---

## §7 — Key + Tag (DE+EN atomar)

**Neuer Copy-Key** (folgt der `agent_add_*`-Konvention, parallel zu `agent_add_error`):

| Key | DE (`values/strings.xml`) | EN (`values-en/strings.xml`) |
|---|---|---|
| `agent_add_success` | `Agent „%1$s" angelegt.` | `Agent "%1$s" created.` |

*(Variante B, falls gewählt statt A — derselbe Key, anderer Wert: DE `Agent „%1$s" angelegt – noch nicht gestartet.` / EN `Agent "%1$s" created — not started yet.`)*

**Neuer Tag** (folgt der dotted `agentMgmt.add.*`-Konvention des CYP-86-Blocks, camelCase-nach-Punkt wie `spawnHint`/`projectNote`):

| Konstante | Wert |
|---|---|
| `AgentMgmtTags.ADD_SUCCESS` | `agentMgmt.add.success` |

> **⚠️ Shared-Key-Drift & Tag-API:** Key **synchron** in DE **+** EN `strings.xml` mit dem Bau landen (sonst bricht der Parität-/Shared-Check). Tag ist geteilte QA-API (CYP-7, Header-Warnung `AgentMgmtTags.kt:6-7) — nicht still umbenennen; vor Landung gegen `AgentMgmtTags.kt` verifizieren ([[verify-reuse-testtags-against-code]]).

---

## §8 — Invarianten (= UX-QA-Abnahme nach Bau)

1. **Copy erscheint panel-level nach Erfolg** — nach `confirmAdd`-Erfolg, Dialog geschlossen, Agent in Liste; Hinweis in der Top-Column unter dem Add-Button, `HintTone.INFO`.
2. **Name referenziert** — der real angelegte Name steht via `%1$s` im Text; DE „Agent „Frontend" angelegt.", EN „Agent "Frontend" created.".
3. **Kein grüner SUCCESS** — INFO-Ton (`secondary`/`i`), keine grüne Fläche/Farbe.
4. **Honesty: created ≠ running** — der Text behauptet **nicht**, der Agent laufe/sei aktiv; nur „angelegt"/„created". (Bei Variante B zusätzlich ehrlich „noch nicht gestartet".)
5. **Kein Null-/Leer-Render** — ohne `addSuccessName` (oder leer) erscheint **kein** Hinweis; nie „Agent „" angelegt.".
6. **Clear beim Re-Interagieren** — Öffnen/Schließen des Add-Dialogs bzw. Feld-Edit räumt die Bestätigung weg (kein Dauer-Banner).
7. **a11y-Announce** — der Hinweis wird per `liveRegion = Polite` angesagt (SR-Nutzer erfährt den Erfolg trotz Fokus-Verlust am geschlossenen Dialog).
8. **i18n-Parität + Reuse** — 1 Key DE+EN synchron; Verb-Reuse zu `agent_add_confirm`/`agent_add_spawn_hint`; kein divergenter Klon des „nicht gestartet"-Satzes; Tag gegen `AgentMgmtTags.kt` verifiziert.

---

## §9 — Hand-off

- **Kein Bau, kein Merge.** Docs-only auf `feature/CYP-314-add-success-copy-spec` (distinkter Worktree `CYP-314-spec`).
- **An Dev:** 1 Key `agent_add_success` (DE+EN, §7-Wert A empfohlen), 1 Tag `AgentMgmtTags.ADD_SUCCESS = "agentMgmt.add.success"`, Platzierung §4, State-Lifecycle-Erdung §6, a11y §5. `addSuccessName` ist Dev-seitig neu.
- **Offene Team-Wahl:** A (knapp, empfohlen) vs. B (mit „noch nicht gestartet"-Halbsatz) — **eine** wählen, nicht beide; falls B, bewusst als Reuse des Zustands-Halbsatzes, nicht als neuer Klon von `agent_add_spawn_hint`.
- **UX-QA nach Bau:** die 8 §8-Invarianten, gerendert — DE+EN, mit/ohne Name, Announce, Clear-Verhalten, INFO-Ton, Honesty.
