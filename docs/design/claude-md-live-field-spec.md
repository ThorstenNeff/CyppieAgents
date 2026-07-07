# Design-Spec — CYP-310: CLAUDE.md Live-Feld + „Überschreiben" (harter Overwrite)

> **Status:** DESIGN-SPEC (kein Bau) · Owner: UIUX · Auftraggeber-Feature, HOCH-PRIO · PO-Go auf Vollspec 2026-07-07 (D1–D3 + Layer-1/2 + if-match/409-Contract bestätigt).
> **Deliverable:** dieses Spec. Docs-only auf `feature/CYP-310-claude-md-live-field` (off develop `60051f9`). Dev baut die Mechanik gegen Backends Endpoint-Contract, klemmt das Treatment an.
> **Grounding (echter Code):** `agentsettings/AgentSettingsPanel.kt:191–206` (Persona-Feld `state.persona`/`setPersona`, Label `agent_add_persona_label` „Persona / CLAUDE.md", Restart-Hinweis `agent_edit_effect_hint` via `TonedHint(EFFECT_DEFERRED, AgentSettingsTags.EFFECT_HINT)`) · `ui/LoadErrorRetry.kt` (CYP-288) · `AgentSettingsTags` (`agentSettings.*`, prefixless, shared QA/CYP-7).

---

## §1 — Honesty-Kern (der Rahmen, treibt alle States)

Das Feld ist ein **Live-Mirror** der worktree-`CLAUDE.md`: **Source of Truth = die Datei**, die auch **von außen abweichen kann** (der Agent editiert sich evtl. selbst, oder es wird im Ordner geändert) — das ist der **Sinn** des Features.

**Zwei-Hop-Kette — und damit ZWEI Disclosures, die sich NIE vermischen dürfen:**

```
   Feld  ──(„Überschreiben")──►  Datei  ──(Neustart des Agenten)──►  laufender Agent
        Hop ①: vorher „ungespeichert"      Hop ②: vorher „nicht aktiv"
```

- **„Überschreiben" schreibt nur Hop ①.** Der laufende Agent zieht die neue CLAUDE.md **erst beim Neustart**.
- **§9-KERN-INVARIANTE:** „Überschreiben" liest **nie** als „ist jetzt aktiv" und **nie** als „Merge/Anhängen".
- **Zwei getrennte Disclosures**, sequenziell, **nie gleichzeitig**:
  - **„ungespeichert"** (Feld ≠ Datei, **vor** dem Overwrite) — Hop ① offen.
  - **„Neustart nötig"** (Datei ≠ laufender Agent, **nach** dem Overwrite) — Hop ② offen. = der bestehende `agent_edit_effect_hint`.

---

## §2 — Layout (schlank — Feld + Button + EINE Hinweiszeile)

Lebt im bestehenden Persona-Abschnitt des `AgentSettingsPanel` (kein neues Panel):

1. **Feld** — Reuse `agentSettings.persona.input`, mehrzeilig, Label „Persona / CLAUDE.md" (`agent_add_persona_label`). Zeigt den **Live-Inhalt** der Datei.
2. **„Überschreiben"-Button** (darunter/trailing) — **Layer 1 Dauer-Affordanz (immer):** Label **„CLAUDE.md überschreiben"** (`agent_claudemd_overwrite`) + Mikrozeile **„Ersetzt die Datei — kein Merge."** (`agent_claudemd_overwrite_note`). Maritim/M3: gefüllter/tonaler Button, `colorScheme`-Rollen (folgt Theme automatisch).
3. **Hinweiszone** (genau **EINE** Disclosure zur Zeit): dirty-Hinweis **oder** Restart-Hinweis **oder** Lade-/Save-Fehler.

---

## §3 — States (S1–S6)

| State | Feld zeigt | Button | Hinweiszone |
|---|---|---|---|
| **S1 Live / clean** | Live-Datei (Refresh reflektiert externe Edits) | „Überschreiben" **disabled** (Feld == Datei → nichts zu schreiben) | — (optional dezent „mit Datei synchron") |
| **S2 Dirty (lokal editiert)** | lokaler **Buffer** (Live-Refresh **eingefroren**, D1) | **enabled/primär** | **dirty:** `agent_claudemd_unsaved` |
| **S3 Overwrite** | (Übergang) | (in-flight) | → Erfolg/Konflikt/Fehler, s.u. |
| **S4 Leer / keine CLAUDE.md (neu)** | leer + Placeholder (`agent_add_persona_placeholder`) | „Überschreiben" = **Erst-Anlegen** (kein Confirm) | ehrlicher Leerzustand `agent_claudemd_empty` |
| **S5 Lade-Fehler** | — | **disabled (fail-closed)** | **LoadErrorRetry** (CYP-288 Reuse) |
| **S6 Overwrite-Fehler** | Buffer (bleibt **dirty**) | enabled (Retry) | Save-Fehler `agent_claudemd_save_failed`; **KEIN** Restart-Hinweis |

**S3 im Detail — Klick auf „Überschreiben" → POST mit `if-match = gelesene Basis-Version`:**
- **200 OK** → dirty → clean; Feld re-synct auf die (neue) Datei-Version; dirty-Hinweis weg; **Restart-Hinweis erscheint** (Reuse `agent_edit_effect_hint` / `EFFECT_DEFERRED`) — Hop ② offen.
- **409 Stale** (Datei seit dem Read extern geändert) → **Layer-2-Confirm** (§5): „Datei extern geändert … verwirft diese Änderung." mit **[Live-Version laden]** / **[Trotzdem überschreiben]**.
- **Sonstiger Fehler** (5xx/Netz) → **S6** (bleibt dirty, kein Fake-„gespeichert").

**S4 (Erst-Anlegen):** Overwrite auf eine **leere/fehlende** Datei erzeugt sie — **kein Confirm** (es wird nichts Bestehendes zerstört). Danach ebenfalls Restart-Hinweis (der neue Agent lädt beim Start).

**S5 (fail-closed, der scharfe Punkt):** Scheitert der **Live-Read** (worktree weg / keine Rechte) → **`LoadErrorRetry`** (nie ein leeres Feld — **failed ≠ empty**, sonst falsche „keine Persona"-Behauptung) **und der Overwrite-Button ist gesperrt** — **nie aus einer unbekannten Basis überschreiben.**

---

## §4 — D1: Buffer-Freeze während dirty

- **Live-Refresh nur wenn clean.** Sobald **dirty**, friert der Buffer → die Tipp-Eingabe wird **nie** von einem Live-Refresh überschrieben.
- Eine **externe Änderung während dirty** wird **atomar beim Overwrite** erkannt (`if-match` → 409, §5) — **kein aktives Polling im dirty-State nötig** (hält es schlank; der 409 fängt es zuverlässig, ohne TOCTOU).

---

## §5 — D2: Overwrite-Treatment (Layer 1 + Layer 2, 409-getrieben)

**Layer 1 — Dauer-Affordanz (IMMER, 0 Friktion):** Button-Label „CLAUDE.md überschreiben" + Mikrozeile „Ersetzt die Datei — kein Merge." → der Nutzer weiß **vor jedem Klick**, dass es ein harter Replace ist (kein Save/Merge). Das trägt die „ich ersetze die Live-Version"-Awareness **ohne** Klick-Steuer.

**Layer 2 — blockierender Confirm NUR bei echtem, ungesehenem Verlust (Stale-Base, 409-getrieben):**
- Client sendet die **gelesene Basis-Version** mit dem POST (`if-match`). Datei **seither extern geändert** → **409 Stale** → Confirm-Dialog:
  - Titel `agent_claudemd_conflict_title` „Datei extern geändert" · Body `agent_claudemd_conflict_body` „Die CLAUDE.md wurde extern geändert (evtl. vom Agenten). Überschreiben verwirft diese Änderung."
  - **[Live-Version laden]** (`agent_claudemd_reload_live`) → Feld auf Live, lokale Edits verworfen (der dirty-Hinweis hat sie als ungespeichert markiert; kurze Inline-Warnung genügt) · **[Trotzdem überschreiben]** (`agent_claudemd_overwrite_anyway`) → Re-POST mit der neuen Basis-Version (Force).
- **Kein Confirm** im Normal-Edit ohne externe Änderung (200 direkt) und **nie beim Erst-Anlegen** (S4, leere Datei → nichts zu zerstören).

**Warum geschichtet statt „immer Confirm":** der Interrupt bleibt **scharf** (selten + bedeutungsvoll) statt Dialog-Fatigue zu trainieren; die Awareness liegt dauerhaft in Layer 1. **`if-match`/409 schließt das Stale-Fenster atomar** (kein client-seitiges Re-Read-Race) — PO-approved Contract.

---

## §6 — D3: Restart-Disclosure nach Overwrite (die Zwei-Disclosure-Trennung)

Nach **erfolgreichem** Overwrite (200 / Erst-Anlegen): **Reuse `agent_edit_effect_hint`** (`EFFECT_DEFERRED`) — „Gespeichert. Wirkt erst beim nächsten Start des Agenten – jetzt neu starten …". „Gespeichert" = jetzt **in der Datei**; „wirkt erst beim Start" = **Datei ≠ laufender Agent** (Hop ②). Anti-Divergenz: bestehender Restart-Pfad, kein neues Wording.

**Trennung (harte §10-Invariante):** der **dirty-Hinweis** (Feld ≠ Datei) und der **Restart-Hinweis** (Datei ≠ Agent) sind **sequenziell**, nie gleichzeitig sichtbar: `dirty → (Overwrite 200) → Restart-Hinweis`. Genau **eine** Disclosure in der Hinweiszone.

---

## §7 — Copy-Keys (DE + EN)

**NEU (je DE+EN):**
| Key | DE | EN |
|---|---|---|
| `agent_claudemd_overwrite` | CLAUDE.md überschreiben | Overwrite CLAUDE.md |
| `agent_claudemd_overwrite_note` | Ersetzt die Datei — kein Merge. | Replaces the file — no merge. |
| `agent_claudemd_unsaved` | Ungespeicherte Änderung — erst mit „Überschreiben" in die Datei. | Unsaved change — written to the file only on Overwrite. |
| `agent_claudemd_empty` | Noch keine CLAUDE.md. Persona schreiben; „Überschreiben" legt sie an. | No CLAUDE.md yet. Write a persona; Overwrite creates it. |
| `agent_claudemd_conflict_title` | Datei extern geändert | File changed externally |
| `agent_claudemd_conflict_body` | Die CLAUDE.md wurde extern geändert (evtl. vom Agenten). Überschreiben verwirft diese Änderung. | The CLAUDE.md changed externally (possibly by the agent). Overwrite discards that change. |
| `agent_claudemd_overwrite_anyway` | Trotzdem überschreiben | Overwrite anyway |
| `agent_claudemd_reload_live` | Live-Version laden | Load live version |
| `agent_claudemd_save_failed` | Überschreiben fehlgeschlagen. | Overwrite failed. |

**REUSE (0 neu):** `agent_edit_effect_hint` (Restart, S3/S4) · `agent_add_persona_label` (Feld-Label) · `agent_add_persona_placeholder` (Leer-Feld-Placeholder) · `load_failed`/`load_retry`/`a11y_load_error` (S5 LoadErrorRetry) · `workspace_operator_only` (Operator-Gate).

> **⚠️ Shared-Key-Drift:** die 9 neuen Keys landen in `strings.xml` (DE) **und** `values-en/strings.xml` (EN) **synchron** mit dem konsumierenden Modul — sonst bricht ein Shared-Check. Timing mit dem Bau abstimmen.

---

## §8 — testTags (`agentSettings.*`, prefixless, shared QA/CYP-7)

**REUSE:** `agentSettings.persona.input` (Feld) · `agentSettings.effectHint` (Restart-Hinweis).
**NEU:** `agentSettings.persona.overwrite` (Button) · `agentSettings.persona.unsaved` (dirty) · `agentSettings.persona.empty` (Leerzustand) · `agentSettings.persona.loadError` (+ `.loadError.retry`) · `agentSettings.persona.conflict` (+ `.conflict.overwrite`, `.conflict.reload`) · `agentSettings.persona.saveError`.

> Vor Landung **gegen `AgentSettingsTags.kt` verifizieren** (Code = SoT; [[verify-reuse-testtags-against-code]]) und mit QA/CYP-7 syncen.

---

## §9 — Backend-Contract-Deps (was der Design braucht)

- **READ (live):** aktueller Datei-Inhalt **+ absent-vs-empty-Unterscheidung** (S4: Datei fehlt vs. existiert-leer) **+ ein Version/etag-Token** (Basis für `if-match`).
- **WRITE (Overwrite):** POST mit **`if-match = gelesene Version`** → **200** (neue Version) | **409 Stale** (externe Änderung) | Fehler. **Force-Pfad** für „Trotzdem überschreiben" (Re-POST mit aktueller Version). *(PO-approved.)*
- **Operator-gated:** Overwrite nur Operator; **Non-Operator = read-only Live-View, kein Button** (fail-closed; Reuse bestehendes Gate/`workspace_operator_only`).

---

## §10 — Invarianten (= UX-QA-Abnahme, 9)

1. **„Überschreiben" verspricht NIE „aktiv"** — nach jedem erfolgreichen Overwrite folgt der **Restart-Hinweis** (Datei ≠ Agent). *(HARTE Kante, §1-Kern.)*
2. **„Überschreiben" verspricht NIE „Merge"** — Label + „kein Merge"-Mikrozeile (Layer 1, immer).
3. **Zwei Disclosures nie gleichzeitig** — dirty (Feld≠Datei) und Restart (Datei≠Agent) strikt sequenziell; genau eine in der Hinweiszone.
4. **failed ≠ empty** — Lade-Fehler (S5) = `LoadErrorRetry`, **nie** ein leeres Feld; Overwrite **fail-closed gesperrt** bei unlesbarer Basis.
5. **Kein stiller Datenverlust** — Stale-Base (**409**) → Layer-2-Confirm mit **[Live-Version laden]**; Erst-Anlegen (leer) → **kein** Confirm.
6. **Kein Fake-„gespeichert"** — Overwrite-Fehler (S6) hält **dirty**, **kein** Restart-Hinweis.
7. **Buffer nie geclobbert** — Live-Refresh nur clean; dirty friert (D1).
8. **Operator-gated** — Non-Operator = read-only Live-View, kein Overwrite (fail-closed).
9. **i18n + a11y** — DE+EN-Parität; Hinweise als **Text** (nicht nur Farbe, WCAG 1.4.1); `liveRegion` für Zustandswechsel (dirty→Restart, Fehler) wo passend (Reuse der bestehenden Hinweis-Muster).

---

## §11 — Hand-off

- **Kein Bau, kein Merge.** Docs-only. Dev baut die Mechanik gegen den Contract (`if-match`/409, absent-vs-empty), klemmt das Treatment (Layer 1/2 + S1–S6) an.
- **Reuse-Anker:** Persona-Feld · Restart-Hinweis (`agent_edit_effect_hint`/`EFFECT_DEFERRED`) · `LoadErrorRetry` (CYP-288) · Operator-Gate.
- **Keys/Tags** synchron mit dem konsumierenden Modul landen (shared QA/CYP-7); Tags gegen `AgentSettingsTags.kt` verifizieren.
- **UX-QA nach Bau:** die 9 §10-Invarianten, gerendert, alle States **S1–S6**, DE+EN, Operator + Non-Operator.
