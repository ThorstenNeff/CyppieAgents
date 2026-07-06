# Design-Spec — Restart-Hinweis am Home-Agentenfenster (CYP-239)

> Owner: UIUX-Designer · Story **CYP-239** · Epic: Per-Agent-Customization (CYP-208/209-Familie) · Stand: 2026-07-06 · Status: Vorschlag
> **Grounded gegen** `origin/develop e6f0882` (`agentview/AgentWindow.kt`, `AgentViewTags`, `ui/TonedHint.kt`,
> `agentsettings/AgentSettingsViewModel.needsRestart`, `agentmgmt/AgentManagementViewModel.editEffectHint`,
> `window/WindowBadge.kt`, `agentview/AgentLifecycleClient` (CYP-73 restart), `strings.xml` `agent_edit_effect_hint`).
> **Reine `commonMain`-UI-Platzierung eines BESTEHENDEN Hinweises** — kein neues visuelles Vokabular. Speist Dev CYP-239;
> UX-QA danach UIUX. Größe: **S** (UI) **+ 1 Backend-Abhängigkeit**, siehe §5. **Reuse-vor-Divergenz vom PO ratifiziert.**

---

## §0 — Das Problem in einem Satz

Der ehrliche „Neustart nötig, damit die Änderung greift"-Hinweis (`agent_edit_effect_hint`, amber `EFFECT_DEFERRED`)
existiert heute **nur in Panels** (Agent-Settings-Dialog `AgentSettingsPanel`, Agent-Management-Liste `AgentManagementPanel`).
**Sobald der Nutzer das Panel schließt, verschwindet das Signal** — der Agent läuft weiter mit der **alten** Konfiguration,
und das Home (die schwebenden Agentenfenster) zeigt **nichts** davon. CYP-239 bringt dasselbe Signal **auf das
Home-Agentenfenster**, sichtbar solange der Neustart aussteht.

---

## §1 — Was schon da ist (verifiziert @ `e6f0882`) — CYP-239 ist Platzierung, nicht Neubau

- **Der Hinweis-Text:** `agent_edit_effect_hint` (DE+EN, `strings.xml`):
  - DE: „Gespeichert. Wirkt erst beim nächsten Start des Agenten – jetzt neu starten, damit die neue Konfiguration zieht."
  - EN: „Saved. Takes effect on the agent's next start — restart now so the new configuration applies."
  - **Code-dokumentierter Ehrlichkeits-Anker (Kommentar `strings.xml` Z. 174):** *„Disclosure-true: ‚Gespeichert' ≠ ‚Aktiv'"*.
- **Die Hinweis-Komponente:** `TonedHint(text, tone, tag)` mit `HintTone.EFFECT_DEFERRED` → amber Container (`tertiaryContainer`)
  + führendes **„!"-Glyph** (Form trägt Bedeutung, **Farbe nicht alleiniger Träger**, WCAG 1.4.1). Genutzt in beiden Panels.
- **Die Restart-Aktion:** **CYP-73** — per-Agent `AgentViewTags.restartBtn(id)` = `agent.<id>.restartBtn`,
  `AgentLifecycleClient.restart(id)` → `POST /api/agents/{id}/restart` (Operator-Bearer). Sitzt bereits im
  **Fensterkopf** (`agent.<id>.header` = „status + lifecycle controls, CYP-73"). Codeweit: *„no second restart
  mechanism; reuse CYP-73."*
- **Die Grenze (immediate vs. deferred):** Name/Farbe wirken **sofort** (Anzeige), Persona/CLAUDE.md (und Connector-Wechsel)
  sind **restart-deferred**. Der Hinweis erscheint in den Panels **nur** bei restart-deferred Änderungen — nie bei Name/Farbe.
- **Fenster-Kopf-Anker:** `agentview/AgentWindow.kt` rendert Kopf (`header`) + Stream (`stream`) je Agent — der Kopf ist
  der natürliche, per-Agent-eindeutige Ort für den Hinweis.

**Konsequenz:** CYP-239 **erfindet nichts** — es rendert den **bestehenden** `TonedHint(EFFECT_DEFERRED)` mit der
**bestehenden** Copy im **bestehenden** Fensterkopf, gebunden an eine **ehrliche Restart-Pending-Wahrheit** (§5). „Keinen
zweiten Hint-Chip" (PO) = **exakt dieselbe Komponente + Copy**, nur an einem neuen Ort.

---

## §2 — Entscheidungen

### D1 — **Reuse `TonedHint(EFFECT_DEFERRED)` + `agent_edit_effect_hint` verbatim**

Der Home-Hinweis ist **dieselbe** amber `EFFECT_DEFERRED`-Zeile mit **demselben** String wie im Settings-Panel
(`AgentSettingsPanel.kt` Z. 205–206). **0 neue Copy-Keys, 0 neues visuelles Vokabular, 0 Divergenz.** Die Copy nennt
bereits die Aktion („jetzt neu starten") — die passt genau, weil der CYP-73-Restart-Button im selben Kopf sitzt.

### D2 — Platzierung: **Fensterkopf des Agentenfensters** (`agent.<id>.header`), neben Status + CYP-73-Restart

Der Hinweis rendert im Kopf des `AgentWindow` — dort, wo Status **und** der Restart-Button schon leben. Nutzer sieht
Signal **und** Aktion an einem Ort. **1 neuer Tag:** `AgentViewTags.restartHint(id)` = `agent.<id>.restartHint` (Familie
`agent.<id>.*`, mit CYP-7 timen). Übergabe an `TonedHint(..., tag = AgentViewTags.restartHint(id))`.

### D3 — **Nicht** über den `WindowBadge`-Slot (bewusste Disclosure-Entscheidung)

Der Titelleisten-`WindowBadge` ist **genau eine Variante pro Fenster** (`Count`/`SeverityLevel`/`Attention`, „die
relevanteste") und kann keine ganze Hinweis-Zeile tragen. Sein `Attention`-Glyph ist laut eigenem Vertrag **„honest
`ERROR` only … never a faked `WAITING_FOR_INPUT`"** — ihn für „Restart nötig" zu missbrauchen würde einen **funktionierenden**
Agenten als **fehlerhaft** darstellen (Disclosure-Lüge) **und** ein echtes `ERROR`-Badge verdrängen. Darum ein **eigener**
Kopf-Hinweis, **kein** Badge. (Ein optionales glance-level Badge ist ein Forward, §9 / §10 — dann als **eigene**, nicht als
`Attention`-Variante.)

### D4 — Ehrliche Grenze: **nur restart-deferred**, nie immediate

Der Home-Hinweis erscheint **ausschließlich**, wenn eine **restart-deferred** Änderung aussteht (Persona/CLAUDE.md,
Connector-Wechsel) — **nie** für Name/Farbe (die wirken sofort, es gibt kein Pending). Das hält die vom CYP-208/209
etablierte „garantiert/sofort vs. advisory/deferred"-Grenze **auch auf Home** sichtbar. Ein Restart-Hinweis nach einer
reinen Farbänderung wäre eine Lüge.

### D5 — Fail-closed Sichtbarkeit

Sichtbar **nur solange** der Neustart aussteht; **Abwesenheit = ehrlicher Leerzustand** („nichts ausstehend") — spiegelt
die `WindowBadge`-Philosophie („no value for ‚nothing'; absence is the honest empty state"). Kein Dauer-Chrome, kein
„erledigt"-Zustand.

### D6 — Auflösung = **bestehender CYP-73-Restart**, keine zweite Mechanik

Der Hinweis **signalisiert**; die **Aktion** ist der schon vorhandene `agent.<id>.restartBtn` im selben Kopf. Nach echtem
Restart löst sich das Pending → der Hinweis verschwindet (§5). Der Hinweis fügt **keinen** neuen Restart-Weg hinzu.

---

## §5 — Source of Truth (der ehrliche Kern — + Backend-Abhängigkeit, PO-Routing)

**Restart-Pending heißt: „die Konfiguration, mit der der Agent-Prozess **läuft**, ≠ die **gespeicherte** Konfiguration."**
Nur der **Server** kennt **beide** Fakten autoritativ (er spawnt den Prozess mit einem Snapshot und speichert die Edits).
Heute lebt das Signal **nur transient**:
- `AgentSettingsViewModel.needsRestart = savedPersona != activePersona` — **Dialog-scoped** (eine Sitzung, ein Agent,
  weg beim Schließen).
- `AgentManagementViewModel.editEffectHint: Boolean` — **ein einzelnes Boolean** für den gerade editierten Agenten im
  Mgmt-Panel, **nicht** per-Agent für das Home abfragbar.

Keiner ist eine **durable, per-Agent, Home-abfragbare** Wahrheit. Für einen Hinweis, der **das Panel-Schließen überlebt**
und **nicht driftet/lügt**, braucht es eine autoritative Quelle:

### Empfohlen (ehrlich, single-source): **server-owned per-Agent `needsRestart`**
Ein `needsRestart: Boolean` pro Agent, geliefert über den **bestehenden, nicht-gegateten** Status-Kanal
(`GET /api/agents`-Snapshot + `/ws/lifecycle`-Deltas — AgentShell nutzt genau diesen für die Statusanzeige). Der Server
setzt es = „laufender Snapshot ≠ gespeicherte Config" und **löscht es automatisch beim Respawn** (Restart ⇒ Snapshot ==
Config ⇒ Pending weg). Der Home-Hinweis bindet an dieses Feld. **→ Kleine Backend-Ergänzung** (core `Agent`-DTO-Feld oder
Lifecycle-Status-Feld). **Flag an PO: an Backend routen** (Shared-DTO-Drift: `:core` + Consumer re-syncen).

> **Warum nicht rein client-seitig:** Jede Client-Rekonstruktion ist eine **Näherung** — sie ist client-lokal, geht beim
> Reload verloren (CYP-204 persistiert nur Geometrie), und ist **blind** für Restarts, die woanders/von anderen Operatoren
> ausgelöst wurden. Ergebnis: ein Badge, das **fälschlich „Neustart nötig"** zeigt, obwohl längst neugestartet — genau die
> Disclosure-Lüge, die ich verhindere. Deshalb ist die server-owned Wahrheit die **empfohlene** Grundlage.

### Interim-Fallback (nur falls Home-Sichtbarkeit VOR dem Backend-Feld gewünscht — explizit gekennzeichnet)
Ein shell-level per-Agent-Set, gesetzt aus dem **schon vorhandenen** `onSaved`-Signal (`AgentShell` ruft
`onSaved`/`agentMgmtVm.refresh()` nach Save), gelöscht beim **nutzer-ausgelösten** CYP-73-Restart (Lifecycle-Event).
**Pflicht-Caveats:** client-lokal, reload-flüchtig, blind für Fremd-Restarts. **Muss fail-toward-NOT-showing** (im Zweifel
**nicht** zeigen, nie fälschlich zeigen). **Meine Empfehlung:** für die durable Wahrheit auf das server-owned Feld gehen;
den Interim nur als bewusste PO-Entscheidung, nicht als Default.

---

## §7 — Ehrlichkeit (mein Kern)

- **„Gespeichert ≠ Aktiv" bleibt wahr.** Reused Copy sagt nie, die Config sei schon aktiv — sie sagt „wirkt erst beim
  nächsten Start". Der Home-Hinweis erbt das 1:1.
- **Kein Fehler-Anschein.** Restart-Pending ist **kein** `ERROR` — der Agent läuft korrekt (mit der alten Config). Darum
  **nicht** das `Attention`/`⚠`-Vokabular (D3), sondern der amber `EFFECT_DEFERRED`-Ton („aufschiebend", nicht „kaputt").
- **Grenze immediate/deferred sichtbar.** Nur restart-deferred Änderungen lösen den Hinweis aus; Name/Farbe nie (D4).
- **Fail-closed.** Kein Pending → kein Hinweis (D5). Und wenn die Quelle unsicher ist (Interim), **lieber nicht zeigen**
  als fälschlich zeigen (§5).
- **Auto-Clear.** Der Hinweis überlebt nur **echtes** Pending; nach Restart weg — nie ein stale „Neustart nötig" (§5,
  server-owned Auto-Clear).
- **Per-Agent-eindeutig.** Am `agent.<id>.header` verankert — der Hinweis sagt **welcher** Agent betroffen ist, nie ein
  ambientes „irgendwas ist ausstehend".

---

## §8 — Umfang & Abgrenzung

- **Im Scope (UI):** `TonedHint(EFFECT_DEFERRED)` + `agent_edit_effect_hint` im `AgentWindow`-Kopf, gebunden an
  `needsRestart` pro Agent; sichtbar nur solange Pending; 1 neuer Tag.
- **Abhängigkeit (Backend, PO-Routing):** server-owned per-Agent `needsRestart` über den bestehenden Status-Kanal (§5).
- **Nicht im Scope:** neue Restart-Mechanik (reuse CYP-73); neues visuelles Vokabular/Farben; ein glance-level Badge
  (Forward §10); die Panel-Hinweise selbst (bleiben unverändert).

---

## §9 — Invarianten (= meine UX-QA-Abnahme, 9 · Disclosure-Honesty)

1. **Nur bei echtem Pending:** Hinweis sichtbar **genau dann**, wenn dieser Agent restart-pending ist; sonst **absent**
   (fail-closed). Kein Dauer-Chrome.
2. **Nur restart-deferred:** erscheint für Persona/Connector-Pending; **nie** nach reiner Name-/Farb-Änderung (immediate).
   Die immediate/deferred-Grenze hält auch auf Home.
3. **Copy verbatim reused:** exakt `agent_edit_effect_hint` (DE+EN) — kein divergentes zweites Wording; „Gespeichert ≠
   Aktiv" bleibt (impliziert nie, die Config sei schon aktiv).
4. **Visual reused:** `TonedHint(EFFECT_DEFERRED)` (amber + „!"-Glyph); **Farbe nicht alleiniger Träger** (WCAG 1.4.1).
5. **Auto-Clear:** nach echtem Agenten-Restart verschwindet der Hinweis — **nie** ein stale „Neustart nötig" nachdem die
   Config schon aktiv ist.
6. **Kein Fehler-Anschein:** nutzt **nicht** das `WindowBadge.Attention`/`⚠`-ERROR-Vokabular; ein restart-pending Agent
   liest nie als kaputt.
7. **Auflösung = CYP-73:** verweist auf den **bestehenden** `agent.<id>.restartBtn`; fügt keinen zweiten Restart-Weg hinzu.
8. **Autoritative Quelle:** bindet an die server-owned `needsRestart`-Wahrheit (laufend-vs-gespeichert), **nicht** an
   „Settings wurden geöffnet". Interim-Client-Cache (falls überhaupt) ist dokumentiert **und fail-toward-NOT-showing**.
9. **Per-Agent-Scope:** am `agent.<id>.restartHint` verankert — nennt den betroffenen Agenten; nie ein globaler/ambienter
   Banner ohne Agenten-Zuordnung.

---

## §10 — Optionale Forwards (nicht blockierend, PO-Call)

- **Glance-level Restart-Badge** (Fenster eingeklappt/unfokussiert): eine **eigene** `WindowBadge`-Variante
  (`RestartPending`, amber „!", nicht `Attention`), mit klarer Präzedenz **unter** `ERROR`-`Attention` (ein Fehler
  schlägt „Neustart nötig"). Bräuchte 1 Badge-Variante + a11y-Label; eigener kleiner Slice.
- **Home-spezifische Copy-Variante:** falls „Gespeichert." auf einem länger stehenden Home-Banner zu „gerade eben"
  klingt, ein optionaler Sibling-Key ohne den Recency-Vorspann (z. B. „Neustart nötig, damit die neue Konfiguration
  greift."). **Default bleibt Reuse-verbatim** (Anti-Divergenz); nur auf PO-Wunsch. Siehe `-keys.md`.

---

## §11 — Hand-off

- **Neue Copy-Keys:** **0** — `agent_edit_effect_hint` verbatim reused (DE+EN). (Optional 1 Home-Sibling, §10 — PO-Call.)
- **Neue Tags:** **1** — `AgentViewTags.restartHint(id)` = `agent.<id>.restartHint` (Familie `agent.<id>.*`) — **⚠ mit
  CYP-7 timen** (Shared-Tag-Drift). Details `-tags.md`.
- **Neue Tokens/Farben:** **0** — reuse `EFFECT_DEFERRED`-Ton. `-tokens.json`.
- **⚠ Backend-Abhängigkeit (PO-Routing):** server-owned per-Agent `needsRestart` über `GET /api/agents` + `/ws/lifecycle`
  (§5). `:core` `Agent`-DTO-Drift → Consumer re-syncen. **Timing:** UI kann gegen das Feld/einen Stub gebaut werden;
  live, sobald Backend liefert.
- **Konsument:** Dev CYP-239 (UI-Bindung) + Backend (Feld). Danach **UX-QA durch UIUX** gegen §9 (9 Invarianten) = Abnahme.
