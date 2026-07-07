# Design-Spec — CYP-315: read-only Worktree-Pfad im Agenten-Settings-Panel

> **Status:** DESIGN-SPEC (kein Bau) · Owner: UIUX · Story (Med) · docs-only auf `feature/CYP-315-worktree-path-spec` (off develop `649453c`).
> **Ziel:** Der **exakte absolute Worktree-Pfad** des Agenten erscheint **read-only** im bestehenden `AgentSettingsPanel` (dasselbe Panel wie Name/Farbe/Avatar). Nutzen = **Copy&Paste ins Terminal**. Rein anzeigend — **kein Editieren, kein Öffnen** aus der UI (abgegrenzt).
> **Grounding (echter Code):**
> - Backend liefert schon (WIP `feature/CYP-315-agent-worktree-path`): `AgentDetail.worktreePath: String?` = **absoluter** Pfad `<git-root>/projects/<projectId>/<worktree>`, server-seitig via `WorktreeManager` aufgelöst; **`null` = kein lokaler Worktree (remote/BYOA-Agent, CYP-197)**, gleiche `remoteAgents`-Naht wie die CLAUDE.md-Endpunkte. Display-only. ⚠️ **Distinkt von** dem bestehenden `AgentDetail.worktree: String` (nur der Ordner-**Name**, z. B. `frontend`) — die neue Zeile zeigt den **vollen Pfad**, nicht den Namen.
> - `AgentSettingsPanel.kt` Identity-Block: `NAME_INPUT` + read-only `ID_READONLY`-Zeile (`agentSettings.idReadonly`, `labelSmall`/`onSurfaceVariant`). Dahinter Farbe/Persona/Avatar.
> - `FontFamily.Monospace` ist das etablierte Muster für maschinen-lesbare Strings (`EventRowUi.kt:135/139`, `AgentWindow.kt:360/393`).
> - `TonedHint(text, tone, tag)` mit `HintTone.INFO` = `secondary` (blau) + Glyph `i`, Text trägt die Bedeutung (WCAG 1.4.1). **Kein grüner SUCCESS im System** (a0/CYP-300: grün ist status-frei).
> - **Kein Clipboard-Seam im gesamten `:app` vorhanden** → Copy-to-Clipboard ist eine **neue** Fähigkeit (§7).

---

## §1 — Platzierung & Layout

Die Worktree-Pfad-Zeile lebt im **Identity-Block**, **direkt unter der read-only `ID`-Zeile** — beide sind read-only, unveränderliche Fakten über den Agenten (der bestehende `agent_edit_id_locked_hint` sagt bereits „ID und Worktree sind fest"). Kein neues Panel, kein neuer Abschnitt.

```
[Identity]
  Anzeigename            (editierbar)          ← NAME_INPUT
  ID (stabil): po                              ← ID_READONLY (unverändert)
  Worktree-Pfad                        [⧉]     ← NEU: Label + Copy-Button (trailing)
  /abs/…/projects/p1/po   (monospace, 1 Zeile, horizontal scrollbar)   ← NEU: PATH-Wert
  › „Pfad kopiert" (INFO, transient)   |  „Nicht lokal …" (INFO)       ← NEU: EINE Statuszeile
[Farbe] … [Persona/CLAUDE.md] … [Avatar]
```

- **Label** „Worktree-Pfad" (`agent_worktree_path_label`), `labelSmall`/`onSurfaceVariant` — konsistent zur `ID`-Zeile.
- **Wert** = der absolute Pfad, **`FontFamily.Monospace`**, `bodySmall`, `onSurface`. **Einzeilig + `Modifier.horizontalScroll(rememberScrollState())`** in einer `fillMaxWidth`-Box: Überlänge **scrollt horizontal**, der Pfad bleibt **ein integraler String** (kein Mid-Path-Umbruch, der ihn zerreißen/falsch lesbar machen würde), **kein Layout-Bruch**. Zusätzlich in `SelectionContainer` gewickelt → manuelles Markieren/Kopieren als Fallback bleibt immer möglich (auch wenn der Copy-Button je scheitert).
- **Copy-Button** trailing an der Label-Zeile (kleiner Icon-Button, „content-copy"-Affordanz), a11y-Name „Worktree-Pfad kopieren" (`a11y_agent_worktree_copy`).

---

## §2 — Zustände

Genau **eine** Statuszeile unter dem Wert (nie doppelt), nach Präzedenz:

| Zustand | Bedingung | Wert-Feld | Statuszeile |
|---|---|---|---|
| **Z1 Lokal** | Detail geladen · `worktreePath != null` | Pfad (monospace, scrollbar) + Copy-Button | — (leer; nach Copy transient „Pfad kopiert") |
| **Z2 Nicht lokal** | Detail geladen · `worktreePath == null` | **kein Feld/kein Copy-Button** | **INFO-Hinweis** `agent_worktree_not_local` |
| **Z3 Copy-Bestätigung** | nach Copy-Klick (Z1) | Pfad bleibt | **INFO** `agent_worktree_copied`, transient (~2 s, dann self-clear) |
| **Z4 Unbekannt / lädt** | `loading` / Detail noch nicht aufgelöst | dezenter Platzhalter „—" oder Zeile ausgeblendet | — (**NIE** „nicht lokal") |

**⚠️ Honesty-Kern (Z2 vs Z4):** `worktreePath == null` bedeutet **nur dann** „nicht lokal", wenn das **Detail erfolgreich geladen** wurde (positives Server-Signal). Der Client-Default ist ebenfalls `null` (VM `load()` kollabiert einen **fehlgeschlagenen** Detail-Load auf Defaults) — dieser Null-Zustand darf **niemals** als „remote/BYOA" ausgegeben werden (das wäre eine falsche Locality-Behauptung, `failed ≠ remote`, analog [[CYP-288]] `failed ≠ empty`). Der „nicht lokal"-Hinweis hängt an einem **aufgelöst-null**-Signal, nicht am bloßen `field == null` (§7).

---

## §3 — Copy-Affordanz & Bestätigung (INFO, kein grüner SUCCESS)

- **Klick** auf den Copy-Button → Pfad wird in die Zwischenablage geschrieben → **transiente Bestätigung** „Pfad kopiert" (`agent_worktree_copied`).
- **Ton = `HintTone.INFO`** (blau/`secondary`, Glyph `i`) — **bewusst nicht grün**: das Kopieren ist eine **informative Quittung**, keine folgenreiche Erfolgs-/Guarantee-Aussage. Deckt sich mit der System-Regel „grün ist status-frei" (a0/CYP-300).
- **Transient:** erscheint bei Copy, verschwindet nach ~2 s (Dev-Detail; Timer bei erneutem Copy zurücksetzen). Trägt **`liveRegion = Polite`**, damit der Wechsel für Screenreader **angesagt** wird (transiente Hinweise sonst leicht verpasst) — Muster wie die bestehenden Hinweis-Surfaces.
- **Wahrhaftigkeit:** „Pfad kopiert" wird **nur** gezeigt, wenn das Kopieren tatsächlich ausgelöst wurde. Nie faken (§7 Web-Clipboard-Caveat). Der Copy-Button erscheint **nur in Z1** (lokaler Pfad vorhanden) — in Z2/Z4 gibt es nichts zu kopieren.

---

## §4 — Copy-Keys (DE + EN)

**NEU (je DE + EN):**
| Key | DE | EN |
|---|---|---|
| `agent_worktree_path_label` | Worktree-Pfad | Worktree path |
| `agent_worktree_not_local` | Nicht lokal — dieser Agent läuft remote; sein Worktree liegt nicht auf diesem Server. | Not local — this agent runs remotely; its worktree isn't on this server. |
| `agent_worktree_copied` | Pfad kopiert | Path copied |
| `a11y_agent_worktree_copy` | Worktree-Pfad kopieren | Copy worktree path |

**REUSE (0 neu):** — keine bestehenden Copy-Keys tragen diese Semantik; bewusst dediziert (Anti-Divergenz zu `agent_add_worktree_*`, das den **Ordner-Namen** meint, nicht den Pfad).

> **⚠️ Shared-Key-Drift:** die 4 Keys landen in `values/strings.xml` (DE) **und** `values-en/strings.xml` (EN) **synchron** mit dem konsumierenden Modul — sonst bricht ein Shared-Check. Timing mit dem Bau abstimmen.

---

## §5 — testTags (`agentSettings.*`, prefixless, shared QA/CYP-7)

**NEU:**
| Tag | Element |
|---|---|
| `agentSettings.worktree.path` | der Pfad-Wert (monospace) |
| `agentSettings.worktree.copy` | Copy-Button |
| `agentSettings.worktree.copied` | Copy-Bestätigung (Z3) |
| `agentSettings.worktree.notLocal` | „nicht lokal"-Hinweis (Z2) |

> Vor Landung **gegen `AgentSettingsTags.kt` verifizieren** (Code = SoT; [[verify-reuse-testtags-against-code]]) und mit QA/CYP-7 syncen. Muster wie bestehende `agentSettings.<gruppe>.<element>` (z. B. `agentSettings.persona.*`).

---

## §6 — Design-System-Konformität (maritim/M3)

- **Farben nur über `colorScheme`-Rollen** → folgt Day/Night automatisch (kein hartcodierter Hex). Label/Pfad: `onSurfaceVariant`/`onSurface`; INFO-Hinweise: `TonedHint(INFO)` = `secondaryContainer`/`secondary` (blau).
- **Monospace** für den Pfad = etabliertes Muster (§Grounding); der eigentliche UI-Font bleibt M3-Body.
- **Kein grüner SUCCESS** (a0/CYP-300 — grün ist status-frei); Bestätigung + „nicht lokal" beide **INFO** (blau).
- **Farbe nie alleiniger Träger** (WCAG 1.4.1): Glyph `i` + Text tragen die Bedeutung; Copy-Button hat sichtbares Icon **und** a11y-Namen.

---

## §7 — Impl-/Contract-Deps (was der Design braucht)

1. **`AgentDetail.worktreePath: String?`** — ✅ Backend baut es bereits (WIP `feature/CYP-315-agent-worktree-path`): absoluter Pfad server-seitig, `null` = remote/BYOA. **Distinkt vom bestehenden `worktree`-Ordnernamen** — die UI liest `worktreePath`, nicht `worktree`.
2. **VM-Naht:** `AgentSettingsViewModel.load()` muss `worktreePath` aus dem Detail **in den UiState** durchreichen (heute trägt der State nur name/role/color/avatar). **Plus** ein **positives „Detail aufgelöst"-Signal**, damit Z2 („nicht lokal") von Z4 (nicht geladen / Load-Fehler) unterscheidbar ist (Honesty-Kern §2). Minimal: ein `worktreePathKnown`/`detailResolved`-Boolean, das **nur** bei erfolgreichem Detail-Load (`d != null`) auf true geht.
3. **Clipboard-Seam (NEU):** kein bestehender Clipboard-Nutzer im `:app`. Compose Multiplatform bietet `LocalClipboardManager.setText(AnnotatedString)` in `commonMain`. **Per-Target-Caveat (Honesty):** Desktop/Android zuverlässig; **Web (wasmJs)** ist die Clipboard-API permission-/async-gated → dort das „Pfad kopiert" **nur bei tatsächlichem Erfolg** zeigen (bzw. still bleiben), nie faken. Der `SelectionContainer`-Fallback (§1) trägt Copy&Paste auch dort. Der Seam ist Dev-Owned; die **Wahrhaftigkeits-Regel** (§3) ist die Design-Vorgabe.
4. **Out of scope (abgegrenzt, PO-bestätigt):** kein Editieren, kein „Öffnen im Finder/Terminal" aus der UI — rein read-only Anzeige + Copy.

> **Forward (out-of-scope, [[CYP-288]]-Muster):** Der Detail-Load selbst hat heute **keine** Fehler-Surface im Settings-Panel (`load()` kollabiert einen Fehlschlag still auf Defaults). CYP-315 verlangt das nicht — die Zeile blendet bei unaufgelöstem Detail nur aus (Z4) statt zu lügen. Falls ein persistenter Detail-Load-Fehler je sichtbar gemacht werden soll, ist das ein eigener LoadErrorRetry-Schritt (nicht dieser Ticket).

---

## §8 — Invarianten (= UX-QA-Abnahme)

1. **Read-only, kein Handlungspfad** — Pfad ist nicht editierbar; kein Öffnen/Editieren aus der UI (nur Anzeige + Copy).
2. **Voller absoluter Pfad, unzerissen** — monospace, einzeilig, horizontal scrollbar; Überlänge bricht **kein** Layout und zerreißt den Pfad nicht (`SelectionContainer`-Fallback vorhanden).
3. **`nicht lokal ≠ unbekannt`** — der „nicht lokal"-Hinweis erscheint **nur** bei aufgelöstem Detail mit `worktreePath == null`; ein nicht-geladener/fehlgeschlagener Detail-Load zeigt **nie** „nicht lokal" (Honesty-Kern §2).
4. **Genau EINE Statuszeile** — Copy-Bestätigung / „nicht lokal" nie gleichzeitig; Bestätigung nur in Z1, „nicht lokal" nur in Z2.
5. **INFO, kein grüner SUCCESS** — Bestätigung + „nicht lokal" beide `HintTone.INFO` (blau); grün bleibt status-frei.
6. **Kein Fake-„kopiert"** — „Pfad kopiert" nur wenn tatsächlich kopiert (Web-Clipboard-Caveat §7).
7. **i18n + a11y** — 4 Keys DE+EN-Parität; Copy-Button trägt a11y-Namen; Farbe nie alleiniger Träger (Glyph+Text); Copy-Bestätigung `liveRegion=Polite` (transiente Ansage).
8. **Design-System-konform** — nur `colorScheme`-Rollen (Day/Night automatisch), Monospace nur für den Pfad, `agentSettings.*`-Tags gegen Code verifiziert.

---

## §9 — Hand-off

- **Kein Bau, kein Merge.** Docs-only auf `feature/CYP-315-worktree-path-spec` (getrennt von Backends Impl-Branch `feature/CYP-315-agent-worktree-path`, dessen Worktree nicht angefasst).
- **Reuse-Anker:** Identity-Block/`ID_READONLY`-Nachbarschaft · `FontFamily.Monospace` · `TonedHint(INFO)` · `SelectionContainer`.
- **Keys/Tags** synchron mit dem konsumierenden Modul landen (shared QA/CYP-7); Tags gegen `AgentSettingsTags.kt` verifizieren.
- **UX-QA nach Bau:** die 8 §8-Invarianten, gerendert, alle Zustände Z1–Z4, DE+EN, Operator + Non-Operator (Anzeige ist read-only für beide — kein Operator-Gate nötig, der Pfad ist keine privilegierte Aktion; nur Konsistenz mit dem Panel-Rahmen prüfen).
