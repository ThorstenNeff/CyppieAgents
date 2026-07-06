# Design-Spec — Empty-Project Empty-State (0 Agenten) (CYP-250)

> Owner: UIUX-Designer · Story **CYP-250** (Medium) · verlinkt CYP-246 (High Bug, Projekt-Isolation) + CYP-247 (L) · Stand: 2026-07-06 · Status: **v1.1** (UX-QA-Korrektur)
> **⚠ v1.1-Änderung ggü. v1.0 (UX-QA Jira 12560):** Platzierung §D2 **Hintergrundebene → Vordergrund-Overlay** (in `Surface`,
> NACH der Fenster-Schleife). Grund: bei 0 Agenten füllen die `tile()`-gekachelten Tool-Fenster den Canvas und verdeckten
> die Hintergrund-Fläche (CTA nicht klickbar). Rest unverändert. Speist Devs Vordergrund-Fix (CYP-250 reopened → In Arbeit).
> **Grounded gegen** `origin/develop 34b4fd4` (`window/WindowManager.kt` `WindowCanvas`, `AgentShell.kt` Fensterliste,
> `agentmgmt/AgentManagementPanel.kt` CYP-228-Empty-State + Add-Button, `AgentMgmtTags`, `WindowTestTags`,
> `window/PhonePager` Empty-State-Präzedenz, `project/ProjectSwitcherBar`, `strings.xml`).
> **Reine `commonMain`-UI** — kein neues visuelles Vokabular, **keine Server-/Protokoll-/DTO-Änderung, keine Backend-Dep.**
> Speist Dev CYP-250; UX-QA danach UIUX. Größe: **S**. **Near-pure-Reuse: 0 neue Copy-Keys.**

---

## §0 — Das Problem in einem Satz

Sobald CYP-246 (M) die Projekt-Isolation fixt, ist ein **frisch angelegtes Projekt = 0 Agenten** — ein Zustand, den es
nie gab (bisher immer Boot-Agenten). Ohne Empty-State sieht der Nutzer im Agenten-Bereich des Desktops **nichts** und
weiß nicht, dass + wie er den ersten Agenten anlegt. CYP-250 gibt diesem Zustand einen **klaren „Füg deinen ersten
Agenten hinzu"-Leerzustand** — konsistent mit dem CYP-228-Onboarding und dem bestehenden Add-Flow.

---

## §1 — Der ehrliche Kern: „leerer Desktop" heißt **0 AGENTEN**, nicht **0 Fenster** (verifiziert @ `34b4fd4`)

**Kritische Grundlage — nicht raten, verifiziert:** Die Fensterliste ist **nie wirklich leer**. `AgentShell.kt` Z. 256
baut sie als `managedAgents.map { it.id to it.name } + buildList { add(AGENT_MGMT_WINDOW_ID …); … }` — die **System-/
Werkzeug-Fenster** (Agenten-Verwaltung, Comm, ACL, Settings, Product-Lead, Event-Log, Roster) werden **immer** angehängt.
`WindowCanvas` (`WindowManager.kt` Z. 149–199) iteriert `state.windows.forEachIndexed { … FloatingWindow(…) }` **ohne
Empty-Branch**, aber `state.windows` ist praktisch **nie** `isEmpty()`.

**Konsequenz für das Design:** Der „leere Desktop" eines frischen Projekts ist **nicht** „0 Fenster" — es ist **0
Agenten-Fenster**, während die Werkzeug-Fenster weiter da sind. **Der Empty-State triggert auf `managedAgents.isEmpty()`**
(0 Agenten), **nicht** auf `state.windows.isEmpty()` (das wäre nie wahr). Die Copy muss ehrlich **„noch keine Agenten"**
sagen — **nie** „leerer Desktop / nichts hier", weil Werkzeug-Fenster koexistieren.

> Präzedenz: **PhonePager** hat bereits einen Empty-State (`phonePager.empty`, `pager_empty` = „Keine Fenster"/„No
> windows", `if (pages.isEmpty())`). Der Desktop-Canvas hat **kein** Äquivalent. **`pager_empty` NICHT wiederverwenden** —
> „Keine Fenster" wäre hier semantisch falsch (der Desktop ist **agentenlos**, nicht **fensterlos**).

---

## §2 — Entscheidungen

### D1 — Trigger: **`managedAgents.isEmpty()`** (0 Agenten-Fenster), self-clearing

Der Empty-State erscheint **genau dann**, wenn das aktive Projekt **0 Agenten** hat, und **verschwindet**, sobald ≥1
Agent existiert (dessen Fenster erscheint). Kein Trigger auf `state.windows` (System-Fenster verfälschen das).

### D2 — Platzierung: **Vordergrund-Overlay** über den Fenstern (`window.host`), zentriert, in `Surface`

> **⚠ UX-QA-Korrektur (2026-07-06, Jira 12560) — v1.1:** ursprünglich (v1.0) als **Hintergrundebene** („hinter den
> schwebenden Fenstern, kein Z-Fight") spezifiziert. **Das war falsch.** Bei 0 Agenten kacheln die Tool-Fenster via
> `resetTo → WindowReducer.tile()` (AgentShell:499) über den **ganzen** Canvas und **verdecken** eine Hintergrund-Fläche
> vollständig → Panel unsichtbar, **CTA nicht klickbar**. Korrekt = **Vordergrund-Overlay**. (Der v1.0-Impl folgte der
> falschen §D2 und wurde in der UX-QA gefangen; Feature reopened → Dev-Vordergrund-Fix.)

Eine ruhige, zentrierte „Get-started"-Fläche als **Vordergrund-Overlay**, in eine `Surface` gewickelt (Lesbarkeit über
den Fenstern), **über** den schwebenden Fenstern. Injektion: im `WindowCanvas`-`Box` (Z. 160), **NACH** der
`forEachIndexed`-Schleife (zeichnet oben), konditioniert auf `managedAgents.isEmpty()`.

**Warum Vordergrund strikt sicher ist (Z-Fight per Konstruktion unmöglich):** `agentsEmpty` ⟺ **es gibt keine
Agenten-Fenster.** Das Overlay überdeckt also **nie** ein Agenten-Fenster — nur die (bei 0 Agenten noch nutzlosen)
Tool-Fenster, und nur solange 0 Agenten. Sobald ≥1 Agent existiert, ist `agentsEmpty = false` → kein Overlay. Das
ursprüngliche Z-Fight-Bedenken (v1.0-§D2) kann damit **nie** eintreten — es war von Anfang an gegenstandslos.

### D3 — Inhalt = **CYP-228-Muster wiederverwendet + eine primäre CTA**

Struktur wie der CYP-228-Empty-State (`AgentManagementPanel.kt` Z. 146–165), **verbatim Copy-Reuse**:
- **Überschrift:** `agent_empty_title` („Noch keine Agenten" / „No agents yet") — `titleSmall`, `semantics { heading() }`.
- **Body:** `agent_empty_body` („Lege deinen ersten Agenten an — ID und Name genügen. Er startet erst über die
  Lifecycle-Steuerung." / EN analog) — `bodyMedium`, `onSurfaceVariant`. **Trägt „Anlegen ≠ Start" bereits im Text.**
- **Primäre CTA:** ein Button mit Label **`agent_add`** („Agent hinzufügen" / „Add agent") — reused String vom
  bestehenden Add-Button.

### D4 — CTA-Route = **bestehender Add-Flow**, keine zweite Mechanik

Die CTA ruft den **vorhandenen** `openAdd`-Pfad (CYP-86/87/88): sie **holt die Agenten-Verwaltung nach vorn** und
**öffnet deren Add-Dialog** (`focus(AGENT_MGMT_WINDOW_ID)` + `agentMgmtVm.openAdd()`) — **ein** Add-Mechanismus, nur ein
zusätzlicher Einstiegspunkt. (Fallback, falls Direkt-Öffnen unpassend: nur die Agenten-Verwaltung fokussieren, die
**intern** schon ihren eigenen CYP-228-Empty-State + Add-Button zeigt.)

### D5 — Operator-Gate: **ehrlich, kein toter CTA** (reused Gate)

- **Operator:** CTA `enabled = true`.
- **Nicht-Operator:** CTA `enabled = false` **+** reused Gate-Hinweis `workspace_operator_only` („Nur der Operator kann
  das ändern" / „Only the operator can change this", `TonedHint(HintTone.GATED)`) — der Nutzer sieht **warum**, kein
  Dead-End. Exakt wie der Add-Button in CYP-228.

### D6 — **0 neues visuelles Vokabular / 0 neue Copy**

M3-Typografie (Heading + Body) + bestehender `Button` + bestehender `TonedHint(GATED)`. **0 neue Farben/Tokens; 0 neue
Copy-Keys** (reuse `agent_empty_title`, `agent_empty_body`, `agent_add`, `workspace_operator_only`).

---

## §3 — Impl-Skizze (illustrativ, Dev besitzt den Code)

```kotlin
// WindowManager.kt — WindowCanvas, im Box(testTag = WindowTestTags.HOST):
state.windows.forEachIndexed { index, window -> /* unverändert */ }
// v1.1 (UX-QA-Korrektur): das Overlay NACH der Fenster-Schleife → zeichnet OBEN (Vordergrund), in Surface gewickelt.
if (agentsEmpty) {                                   // agentsEmpty = managedAgents.isEmpty() (hochgereicht)
    Surface(                                         // Lesbarkeit über den Fenstern (nicht transparent-über-opak)
        modifier = Modifier.align(Alignment.Center).testTag(WindowTestTags.EMPTY),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,                        // dezent abgehoben; keine neue Farbe (M3-Surface)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(Res.string.agent_empty_title), style = titleSmall,
                 modifier = Modifier.semantics { heading() })
            Text(stringResource(Res.string.agent_empty_body), style = bodyMedium, color = onSurfaceVariant)
            Button(onClick = onAddFirstAgent, enabled = isOperator,
                   modifier = Modifier.testTag(WindowTestTags.emptyAddBtn)) {
                Text(stringResource(Res.string.agent_add))
            }
            if (!isOperator) TonedHint(stringResource(Res.string.workspace_operator_only),
                   HintTone.GATED, WindowTestTags.emptyGateHint)
        }
    }
}
```
- `agentsEmpty` + `isOperator` + `onAddFirstAgent` werden aus `AgentShell` (wo `managedAgents`, `isOperator`,
  `agentMgmtVm`, Window-Focus in Scope sind) an `WindowCanvas` durchgereicht. **Kein neuer State, kein DTO.**
- `onAddFirstAgent = { state.focus(AGENT_MGMT_WINDOW_ID); agentMgmtVm.openAdd() }` (D4).

---

## §7 — Ehrlichkeit (mein Kern)

- **„Noch keine Agenten", nicht „nichts hier".** Der Empty-State beschreibt ehrlich den **Agenten**-Zustand; die
  Werkzeug-Fenster koexistieren (das Overlay liegt **sichtbar im Vordergrund** darüber). Keine Copy, die einen wirklich
  leeren Bildschirm behauptet.
- **Sichtbar = ehrlich.** Ein Empty-State, der (verdeckt) nicht sichtbar ist, wäre eine stille Lüge („Feature da, aber
  unbrauchbar"). Vordergrund-Overlay (D2, v1.1) garantiert Sichtbarkeit **und** Klickbarkeit der CTA.
- **„Anlegen ≠ Start" reused.** `agent_empty_body` sagt bereits „startet erst über die Lifecycle-Steuerung" — kein
  Vortäuschen, dass ein angelegter Agent schon läuft.
- **Kein zweiter Add-Weg.** Die CTA routet in den **bestehenden** `openAdd`-Flow (D4) — keine divergente Anlege-Logik.
- **Ehrliches Gate, kein Dead-End.** Nicht-Operator sieht die **Begründung** (`workspace_operator_only`), nicht nur einen
  toten Button.
- **Konsistent mit dem Projekt-Modell.** Das aktive Projekt + Scope nennt bereits die Projekt-Leiste
  (`project_switcher_active` + Scope-Hinweis „Alle Fenster und Daten gehören zu diesem Projekt."); der Empty-State
  wiederholt/widerspricht das nicht.
- **Self-clearing.** Nur solange 0 Agenten; nach dem ersten Agenten weg — nie ein stehendes „Füg deinen ersten hinzu"
  neben existierenden Agenten.

---

## §8 — Umfang & Abgrenzung

- **Im Scope:** Desktop-Canvas-Empty-State bei 0 Agenten (Heading + Body + CTA + Gate), reine `commonMain`-UI.
- **Nicht im Scope:** die CYP-246-Isolations-Korrektheit selbst (dort wird 0-Agenten überhaupt erst erreichbar); die
  Agenten-Erstellung (reuse CYP-86/87/88); der PhonePager-Empty-State (existiert; nicht angefasst); Copy-Neuerfindung.
- **Keine Backend-Abhängigkeit** (Trigger aus dem schon vorhandenen `managedAgents`; im Gegensatz zu CYP-239).
- **Verzahnung:** CYP-246 (M) macht 0-Agenten erreichbar; CYP-247 (L) macht das Spawnen lauffähig → der Empty-State
  führt dann zum **ersten echten** Agenten.

---

## §9 — Invarianten (= meine UX-QA-Abnahme, 9)

1. **Trigger = 0 Agenten:** sichtbar **genau dann**, wenn `managedAgents.isEmpty()`; **nicht** an `state.windows`
   gekoppelt (System-Fenster sind immer da). Self-clearing, sobald ≥1 Agent.
2. **Ehrliche Scope-Copy + SICHTBAR:** sagt „noch keine **Agenten**", nie „leerer Desktop/nichts hier". **Das Overlay
   liegt im Vordergrund** (NACH `forEachIndexed`, in `Surface`) → **sichtbar und CTA klickbar**, auch wenn die
   Tool-Fenster via `tile()` den Canvas füllen. (v1.1-Korrektur: v1.0-Hintergrundebene wurde verdeckt — UX-QA Jira 12560.)
   QA-Zahn: mit Tool-Fenstern via `tile()` platziert (echtes Vollcanvas-Layout) ist `window.host.empty`/`.addBtn`
   sichtbar **und** klickbar (nicht nur im Semantik-Baum präsent).
3. **Copy verbatim reused:** `agent_empty_title` + `agent_empty_body` (DE+EN) aus CYP-228 — **0 neue Copy** für Titel/
   Body; „Anlegen ≠ Start" bleibt („startet erst über die Lifecycle-Steuerung").
4. **CTA = bestehender Flow:** Button-Label reused `agent_add`; Klick routet in `openAdd` (Agenten-Verwaltung nach vorn
   + Add-Dialog) — **kein** zweiter Add-Mechanismus.
5. **Operator-Gate, kein toter CTA:** Nicht-Operator → CTA disabled + reused `workspace_operator_only`-Gate-Hinweis
   (ehrliches „warum"); Operator → CTA live. Wie CYP-228.
6. **Projekt-konsistent:** wiederholt/widerspricht nicht die Projekt-Leiste (aktives Projekt + Scope schon dort);
   nennt kein falsches Projekt.
7. **`pager_empty` NICHT reused:** „Keine Fenster" wäre falsch — der Zustand ist agentenlos, nicht fensterlos.
8. **0 neues Vokabular:** M3-Heading/Body + bestehender `Button` + `TonedHint(GATED)`; **0 neue Farben/Tokens.**
9. **QA-Anker sauber:** `window.host.empty` (Container) + CTA- + Gate-Hint-Tag in der `window.host.empty.*`-Familie;
   `window.host` selbst unverändert.

---

## §10 — Offene Punkte / optionale Forwards (nicht blockierend, PO-Call)

- **Projekt-gescopte Titel-Variante (§-Ask):** falls der Titel den Projektnamen nennen soll („Noch keine Agenten in
  %1$s") — ein optionaler Key mit `%1$s`. **Default bleibt Reuse-verbatim** (`agent_empty_title`), weil die Projekt-
  Leiste den Namen schon zeigt. Nur auf PO-Wunsch (siehe `-keys.md`).
- **Illustration/Icon:** ein dezentes Onboarding-Glyph über dem Titel — rein dekorativ, kein a11y-Träger; eigener
  Mini-Forward, nicht MVP.

---

## §11 — Hand-off

- **Neue Copy-Keys:** **0** (reuse `agent_empty_title`, `agent_empty_body`, `agent_add`, `workspace_operator_only` —
  alle DE+EN @ `34b4fd4` verifiziert). Optional 1 Projekt-Variante (§10, PO-Call). Details `-keys.md`.
- **Neue Tags:** **3** in der `window.host.empty.*`-Familie (`-tags.md`) — **⚠ mit CYP-7 timen** (Shared-Tag-Drift).
- **Neue Tokens/Farben:** **0** (`-tokens.json`).
- **Keine Backend-/DTO-Änderung:** Trigger aus vorhandenem `managedAgents`; CTA aus vorhandenem `openAdd`.
- **Konsument:** Dev CYP-250 (nach/mit CYP-246, das 0-Agenten erreichbar macht). Danach **UX-QA durch UIUX** gegen §9
  (9 Invarianten) = Abnahme.
