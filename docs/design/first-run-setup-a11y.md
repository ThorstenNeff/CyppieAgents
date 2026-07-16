# First-Run Operator-Setup — a11y & Interaktion

> Owner: UIUX-Designer · Epic CYP-623 · Story CYP-629 · Stand 2026-07-16 ·
> Status: **Vorschlag — wartet auf Dev-Gegenlesen.** Companion zu `first-run-setup-ux-spec.md` (Zustände/Copy) /
> `-keys.md` / `-tags.md` / `-tokens.json`. **Fläche PO-zugewiesen 2026-07-16** (die 5 §11-Punkte, jetzt meiner).
> Gegroundet READ-ONLY gg. develop `2f664e33` (reale Compose-a11y-Idiome, file:line).

## 0. Leitsatz (PO)

> **„Visuell ehrlich und assistiv stumm ist nicht ehrlich, sondern *selektiv* ehrlich."**

Der ganze First-Run-Flow macht Zustände ehrlich sichtbar (Zwei-Tier, GATED vs ERROR, Nag-Fix). Diese Ehrlichkeit
muss **assistiv** genauso ankommen — sonst kriegt ein Screenreader-Nutzer die halbe Wahrheit, und die fehlende
Hälfte ist genau die, die wir mühsam ehrlich gemacht haben.

---

## 1. ★ Prio #3 — der `GATED`-Grund programmatisch AM Control (die a11y-kritischste Stelle)

**Problem (heute belegt):** disabled-Controls tragen ihren Grund als **separaten** Nachbar-Knoten. Beispiel:
`SettingsPanel` rendert einen `Button(enabled=false)` + darunter eine eigenständige `TonedHint(GATED)` — der SR
liest beim Fokus auf den Button nur **„Start, deaktiviert"**, der Grund daneben bleibt ein getrennter Knoten,
der beim Control-Fokus **nicht** mitgelesen wird. Das ist „visuell ehrlich, assistiv stumm".

**Fix — Grund in die Semantik DES Controls, nicht daneben.** Zwei erprobte Compose-Idiome, beide im Repo real
genutzt:
- **`stateDescription`** am Control trägt den Grund (Idiom real: `ComposerHistorySizeStepper.kt:60`
  `stateDescription = help`; `AgentAvatarSection.kt:110`). ⟹ der SR liest **„Start, <stateDescription>"** =
  „Start — nicht verfügbar: Hub nicht eingerichtet (API-Key + Repository)".
- **`semantics(mergeDescendants = true)`** auf einem Wrapper um Control + Grund, sodass der Fokus die Gruppe als
  **eine** Einheit liest (Idiom real: `OverloadBanner.kt:50`, `RemoteContextBanner.kt:71`,
  `RemoteOperatingChrome.kt:113` u. a.). `OverloadBanner` ist die **nächste Vorlage**: eine Workspace-Warnung,
  die Inhalt zu **einem** a11y-Knoten merged.

**Empfohlene Umsetzung (`agent.<id>.ctlUnconfigured`, ux-spec §6.3c):**
```
// Skizze, illustrativ — Dev verankert
Box(Modifier.semantics(mergeDescendants = true) {
    // Grund ist Teil des Controls, nicht ein Nachbarknoten:
    stateDescription = a11y_agent_ctl_unconfigured   // "nicht verfügbar — Hub nicht eingerichtet …"
}) {
    Button(enabled = false, /* … agent_ctl_start … */) { Text(agent_ctl_start) }
}
```
- **Fokussierbarkeit:** der Grund muss **beim Fokus auf „Start" hörbar** sein. Ein `enabled=false`-Button kann je
  Plattform aus dem Fokus fallen → die **Merge-Gruppe** (nicht der nackte disabled-Button) trägt
  `stateDescription`, damit der Knoten fokussierbar bleibt und den Grund liefert. (Dev: plattformübergreifend
  verifizieren — Desktop-JVM + Web-Wasm.)
- **Ton bleibt `GATED`, nicht `ERROR`** (nichts fehlgeschlagen) — die a11y ändert den **Träger**, nicht die
  Semantik. Kein `role=alert`, kein Assertive (siehe §2): der Grund ist ein **Zustand des Controls**, kein Event.
- **Race-Fail (dieselbe Copy, ux-spec §6.3c):** rutscht ein Start doch durch, trägt die Fehlerfläche **dieselbe**
  `agent_ctl_unconfigured`-Copy — der SR hört nie zwei verschiedene Erklärungen für eine Ursache.

---

## 2. Live-Region-Politeness (autoritativ — ersetzt die Kurzfassung in -tags.md)

Idiom real: `semantics { liveRegion = LiveRegionMode.Polite/Assertive }`. Precedents im Repo: **`OverloadBanner.kt:52`
= Assertive** (dringende Workspace-Warnung), **`RemoteContextBanner.kt:73` = Polite** („*a persistent context, not a
just-happened event*"), **`AgentSettingsPanel.kt:419` = Polite** (Speicher-Quittung).

| Surface (Tag) | Politeness | Begründung |
|---|---|---|
| `firstRun.repo.cloneFailed` | **Assertive** | echter Fehlschlag, der Nutzer soll ihn sofort erfahren (wie `OverloadBanner`) |
| `firstRun.repo.cloning` | Polite | laufender Fortschritt, nicht dringend |
| `firstRun.repo.cloningSlow` | **Polite, GENAU EINMAL** | Lebenszeichen nach ~15s; **kein** wiederholtes Announce (sonst wird die Anti-Hänger-Zeile selbst zum Nag) |
| `firstRun.repo.cloneOk` / `firstRun.repo.saved` / `firstRun.apiKey.saved` | Polite | Quittung, nicht dringend (wie `AgentSettingsPanel`) |
| `firstRun.degradedNote` / `firstRun.complete` | Polite | Orientierung/Abschluss |
| `workspace.unconfiguredBanner` (Erstauftritt) | Polite | „*persistent context, not a just-happened event*" (wie `RemoteContextBanner`) |
| `workspace.unconfiguredChip` | **kein** liveRegion | passiv/statisch (Nag-Fix) — der eingeklappte Chip **announced nicht** (sonst Back-Door-Nag auch assistiv) |
| `agent.<id>.ctlUnconfigured` | **Polite** (NICHT Assertive) | ein **Zustand**, kein Fehler — Assertive würde einen Defekt suggerieren, den es nicht gibt (Ton-Konsistenz zu `GATED`) |

**Kadenz-Ehrlichkeit (Nag-Fix, ux-spec §6.3a):** der volle Banner announced **einmal** beim Erstauftritt; nach dem
Einklappen ist der Chip **assistiv stumm** (kein liveRegion) — der Nag-Fix gilt **auch** für den Screenreader,
nicht nur visuell. Sonst wäre der Chip visuell leise, aber assistiv laut = inkonsistent ehrlich.

---

## 3. Fokus-Management

- **Gate-Auftakt:** Fokus landet auf dem **ersten offenen Schritt** (erstes Eingabefeld), **nicht** auf dem Titel
  `firstRun.intro`. Ein Nutzer soll direkt handeln können, nicht durch die Orientierung tabben müssen. (Idiom:
  `focusRequester` + `LaunchedEffect` beim Mount.)
- **Schritt-Wechsel:** nach Abschluss eines Schritts wandert der Fokus auf den **nächsten offenen** Schritt (nicht
  zurück an den Anfang) — spiegelt die visuelle „erster offener Schritt"-Logik (ux-spec §6.3b).
- **Nag-Fix Einklappen (§6.3a):** beim Einklappen des Banners wandert der Fokus **auf den Chip**
  (`workspace.unconfiguredChip`) — **nicht** ins Leere (kein Fokus-Verlust). Beim Chip-Tap→Wiederaufklappen
  wandert der Fokus **in den Banner** (auf die erste Aktion/Resume-CTA), damit die Interaktion nicht ins Nichts führt.
- **Clone-Zustands-Wechsel:** der Fokus **springt nicht** bei `cloning→cloneOk/cloneFailed` (kein Fokus-Klau
  mitten in der Arbeit); die Transition wird via liveRegion (§2) *announced*, der Fokus bleibt beim Nutzer.

---

## 4. Tab-/Traversal-Ordnung

- **Stepper tastatur-navigierbar:** die Schritte + Felder sind in **logischer Reihenfolge** erreichbar
  (`isTraversalGroup = true` am Gate-Container; `traversalIndex` nur wo die visuelle Ordnung von der Deklarations-
  ordnung abweicht — Idiom real im Repo, z. B. WindowManager/Chrome-Merges).
- **★ Skip/Resume nie erster Tab-Stop:** `firstRun.skip` und `workspace.setupResume` kommen in der Tab-Ordnung
  **nach** den primären Eingaben/Aktionen — sonst tabbt sich jemand **versehentlich aus der Einrichtung** (oder
  re-öffnet sie ungewollt). Destruktiv-adjazente/abkürzende Controls gehören ans Ende der Gruppe, nicht an den Anfang.
- **Einklapp-Control** (`workspace.unconfiguredCollapse`) trägt sein a11y-Label `a11y_workspace_unconfigured_collapse`
  („Hinweis einklappen") — ein reines Icon ohne Label wäre für den SR stumm.

---

## 5. Nicht-Farb-Signal (WCAG 1.4.1) — Reuse

Der Ton-Glyph ist bereits der Nicht-Farb-Träger und **im a11y-Baum** (`TonedHint.kt:80` `hintGlyph`): `INFO="i"`,
`GATED="·"`, `ERROR="✕"`, `EFFECT_DEFERRED="!"`. First-Run erbt das 1:1 — **kein neues Glyph-Schema.** Farbe ist
nie alleiniger Träger; jeder Zustand = distinkter Text + Ton-Glyph + (wo announcing) liveRegion. (Der animierte
Clone-Indikator §4.4 ist Bewegung als Lebenszeichen — er ersetzt **nicht** den Text, er ergänzt ihn.)

---

## 6. Reuse & Seams

- **Reuse (Code-Idiome, gg. `2f664e33` verifiziert):** `liveRegion` (OverloadBanner Assertive / RemoteContextBanner
  + AgentSettingsPanel Polite), `stateDescription` (ComposerHistorySizeStepper / AgentAvatarSection),
  `semantics(mergeDescendants=true)` (OverloadBanner u. v. a.), `hintGlyph` (TonedHint). **Kein neues a11y-Primitiv.**
- **`TonedHint` heute ohne liveRegion:** die Komponente (`TonedHint.kt`) setzt selbst **kein** liveRegion — der
  **Aufrufer** muss es setzen (`Modifier.semantics { liveRegion = … }`), gemäß §2. Kein Umbau an `TonedHint` nötig
  (bewusst: die Politeness ist kontextabhängig, gehört zum Aufruf, nicht in die geteilte Komponente).
- **Seams → Dev:** (1) die Merge-Gruppe + `stateDescription` am `agent.<id>.ctlUnconfigured`-Control (§1) —
  ⟂ AgentView/Lifecycle, Verankerung gg. `AgentViewTags` gegenlesen; (2) `focusRequester`-Landung (§3); (3)
  `liveRegion`-Zuweisung je Surface (§2); (4) plattformübergreifende Verifikation Desktop-JVM + Web-Wasm
  (disabled-Fokus-Verhalten variiert). **Behaviorale a11y-QA (Tester CYP-7):** SR-Fokus auf „Start" liest den
  Grund; `cloneFailed` Assertive vs `cloning` Polite; Chip announced nicht; Skip/Resume nicht erster Tab-Stop.

---

## 7. Self-Validation

- **Gegroundet** gg. develop `2f664e33` (file:line): reale Idiome `liveRegion`/`stateDescription`/`mergeDescendants`/
  `hintGlyph` — nicht erfunden, im Repo belegt.
- **Prio #3 konkret gelöst:** Grund via Merge-Gruppe + `stateDescription` **am** Control (nicht daneben); Ton bleibt
  `GATED`/Polite (Zustand, kein Event); Race-Fail dieselbe Copy.
- **Nag-Fix assistiv konsequent:** eingeklappter Chip **ohne** liveRegion (assistiv genauso leise wie visuell).
- **Keine neuen Keys/Tags:** nutzt bestehende (`a11y_agent_ctl_unconfigured`, `a11y_workspace_unconfigured_collapse`,
  die Surface-Tags) — reine Interaktions-/a11y-Schicht. (a11y-Copy `a11y_agent_ctl_unconfigured` als
  `stateDescription`-tauglich formuliert, siehe -keys.md.)
- Verankert die 5 PO-Punkte aus ux-spec §11; behaviorale a11y-QA an Tester (CYP-7) übergeben. Kein Bau, docs-only.
