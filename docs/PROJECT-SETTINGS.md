# Projekt-Settings — Repo-Config & API-Key (Desktop) (v0.1)

> Owner: UIUX-Designer · Epic: **CYP-75** (S15) · Stories: **CYP-84** (Repo-Config-UI) + **CYP-85** (API-Key-UI) · Status: **Entwurf — wartet auf Dev-Gegenlesen** · Stand: 2026-06-28
> **Kanonischer Ort:** geteiltes Repo `KMPCyppieAgents` unter `docs/PROJECT-SETTINGS.md`.
> Begleit-Artefakte (Muster CYP-17): `docs/design/project-settings-tokens.json`, `project-settings-keys.md`, `project-settings-tags.md`.
> **Brand:** CyppieAgents (Anti-Hype). Desktop/`commonMain`-tauglich.

> **Bezug (im Code verifiziert, develop `4758360`):**
> - Config heute: `server/.../boot/PlatformConfig.kt` → `data class RepoConfig(url, branch="main")`, **eine globale** `PlatformConfig` (kein Pro-Projekt), geladen aus `platform.config.json` beim Boot. **Keine** Config-REST-Endpunkte (greenfield).
> - API-Key heute: `server/.../boot/Secrets.kt` → `apiKey: String?` aus `ANTHROPIC_API_KEY` (env, optional, **global**), bereits server-seitig maskiert `mask()= "***"+takeLast(4)`; injiziert in die Agenten-Session-ENV beim Spawn (`connector/ClaudeCodeConnector.kt`). **Keine** Key-REST-Endpunkte (greenfield).
> - Operator-Gate: `server/.../routing/Auth.kt` → `requireOperator()` wirft **403 `operator_required`** (401 ohne Token); Fehler-Envelope `{error:{code,message}}`.
> - Restart-Naht (Reuse für §4): **CYP-73** `POST /api/agents/{id}/{stop|start|restart}` operator-gated/fail-closed (401 / 403 `operator_required` / 404 `agent_not_found` / 409 `already_running` / 503 `spawn_failed`); UI-Control `agent.<id>.restartBtn` (`AgentViewTags.restartBtn`), Label `agent_ctl_restart`.

Spezifiziert die **Projekt-Einstellungen** als ein eigenes Desktop-Fenster mit zwei Abschnitten: **Repository** (CYP-84) und **API-Schlüssel** (CYP-85). Keine Implementierungsvorgabe — Verhalten, Disclosure-Leitplanken, Tokens/Keys/Tags + der **zu bauende Datenpfad** (greenfield).

---

## 0. Geltungsbereich & Nicht-Ziele

| | |
|---|---|
| **In Scope** | Ein **Settings-Fenster** (`window.<id>` = `settings`) im bestehenden Free-Floating-Window-Manager. Abschnitt **Repo-Config** (URL/Branch, pro Projekt) + Abschnitt **API-Key** (hinterlegen/ändern, maskiert). Operator-Gating, Disclosure (gespeichert ≠ aktiv), Effekt-erst-beim-Spawn/Restart-Transparenz. testTags + a11y de/en. |
| **Nicht-Ziele** | **Kein** Backend-Bau (nur der **Vertrag**, den Backend liefern muss — §2). Kein Secret-Round-Trip. Keine Agenten-Verwaltung (Hinzufügen/Entfernen von Agenten) — nur Repo + Key. Kein Multi-Projekt-Switcher-UI (S13/S17). Kein eigener Restart-Mechanismus — **Reuse** CYP-73. |

**Fenster-Einbettung (Quelle = `AgentShell.kt`):** Wie Comm/ACL ein Fenster in der `windows`-Liste + `when`-Branch in `windowContent`. **Kein** Content-Window i. S. v. CYP-26 (`contentWindowIds` = Agent/Comm wg. Composer-Min-Breite) — Settings ist ein Lese-/Formularfenster, nutzt den normalen `MIN_WINDOW_WIDTH`-Floor.

---

## 1. Disclosure-Prinzipien (gelten für beide Abschnitte)

1. **„Gespeichert" ≠ „Aktiv".** Ein Save schreibt den Wert in die Projekt-Config; **wirksam wird er erst beim nächsten Spawn/Restart** des Agenten (Key in Session-ENV, Repo bei Worktree-Anlage). Die UI sagt das **explizit** und in **Hinweis-Ton (amber/tertiary), nicht Erfolg-grün**. Niemals impliziert ein Save, dass der laufende Agent den neuen Wert schon nutzt. → Eskalation auf **CYP-73-Restart** (Reuse).
2. **Secret wird nie zurückgerendert.** Der API-Key verlässt den Server **nie** im Klartext (GET liefert nur `{set, masked}` = `***<last4>`). Das Eingabefeld ist **write-only** (neuen Key tippen = ersetzen; der hinterlegte Key ist nie sichtbar/abrufbar). Ein „Anzeigen"-Toggle entmaskt **nur die aktuelle Eingabe des Nutzers**, nie den gespeicherten Wert.
3. **Operator-gated, fail-closed, aber Gate beobachtbar.** Bearbeiten nur mit Operator-Token (Server erzwingt 403 `operator_required`). Die Controls sind **sichtbar** (damit das Gate erkennbar ist — Muster CYP-73/ACL), aber **disabled/read-only** ohne Operator-Token. Liegt kein erlaubter Wert vor → **kein** Wert vorgaukeln (kein „leer = ok").
4. **Honest-Omission beim Gating-Effekt.** Repo-Config **gated den Betrieb**: ohne Repo kein Worktree → Agenten können nicht starten. Der „nicht konfiguriert"-Zustand wird **ehrlich** angezeigt (nicht stilles Leerfeld).
5. **Farbe nie alleiniger Träger.** Jeder Zustand (Effekt-deferred, Fehler, Gate) = Farbe + Icon/Form + Text + a11y-Label.

---

## 2. Datenpfad — zu bauender Vertrag (Backend-Naht, **flag an PO/Backend**)

> **Wichtig:** Beide Abschnitte sind **greenfield am Backend**. Heute ist Config **datei-/env-getrieben und global**. Die Spec definiert den **Pro-Projekt-Vertrag**; das Backend muss ihn liefern, **bevor** die UI echte Werte zeigt. Bis dahin baut Dev gegen diesen Vertrag/Stub.

**Vom Backend zu liefern (Vorschlag, finale Form = PO/Backend):**

| Endpunkt | Gate | Body / Response | Fehler |
|---|---|---|---|
| `GET /api/config/repo` | auth (Operator empfohlen, URL ist nicht geheim) | → `{ url: String, branch: String }` oder `{ configured: false }` | 401 |
| `PUT /api/config/repo` | **Operator** | `{ url, branch }` → `{ url, branch }` | 401 / **403 `operator_required`** / 400 `invalid_repo_url` |
| `GET /api/config/apikey` | auth | → `{ set: Boolean, masked: String? }` — **nie** der Klartext-Key | 401 |
| `PUT /api/config/apikey` | **Operator** | `{ apiKey: String }` → `{ set: true, masked: String }` | 401 / **403 `operator_required`** / 400 `invalid_api_key` |

**Drei Backend-Auflagen, die die UI voraussetzt:**
1. **Pro-Projekt-Auflösung.** `RepoConfig` + API-Key müssen pro Projekt/Team aufgelöst werden (heute: globale `PlatformConfig` + globaler `ANTHROPIC_API_KEY`). Entspricht 05-Doc **D3** („pro Projekt/Team aufgelöst, keine globale Server-Konstante"). → Backend-Seam.
2. **Key nie ausliefern.** `GET .../apikey` gibt **ausschließlich** `masked` (`mask()` existiert bereits in `Secrets.kt`) — der Klartext-Key wird nie an den Client gesendet, nie geloggt. PUT akzeptiert ihn, GET nie.
3. **Effekt-Timing ist real.** Key → Session-ENV beim **Spawn**; Repo → bei **Worktree-Anlage/Boot**. Kein Hot-Reload. Deshalb die Restart-Transparenz (§1.1) — **kein** stiller Nicht-Effekt.

> **Shared-Key-Sync-Hinweis (meine stehende Disziplin):** Die neuen i18n-Keys landen als `:app:shared` compose.resources (DE+EN). Das konsumierende Modul muss re-syncen — **mit der Impl timen** (CYP-84/85), sonst bricht ein Shared-Check.

---

## 3. CYP-84 — Repo-Config (URL / Branch, pro Projekt)

### 3.1 Layout
Vertikales Formular im Abschnitt `settings.section.repo` (Überschrift `settings_repo_section`):
- **Repository-URL** — `OutlinedTextField`, einzeilig, Label `settings_repo_url_label`, Tag `settings.repo.url.input`, a11y `a11y_settings_repo_url`.
- **Branch** — `OutlinedTextField`, einzeilig, Default-Anzeige „main", Label `settings_repo_branch_label`, Tag `settings.repo.branch.input`, a11y `a11y_settings_repo_branch`.
- **Speichern** — `Button`, Label `settings_save` (geteilt), Tag `settings.repo.save`. Nur **enabled** mit Operator-Token + geänderten/validen Feldern.

### 3.2 Zustände & Disclosure
| Zustand | UI | Tag |
|---|---|---|
| **Nicht konfiguriert** (`configured:false`) | Hinweiszeile „Kein Repository konfiguriert – Agenten können nicht starten." (Info-/Warnung-Ton, **nicht** Fehler-rot) | `settings.repo.status` |
| **Konfiguriert** | URL + Branch in den Feldern, vorbefüllt | `settings.repo.url.input` / `.branch.input` |
| **Kein Operator-Token** | Felder read-only, Save disabled + Gate-Hinweis „Nur mit Operator-Token änderbar." | `settings.repo.gateHint` |
| **Nach Save** | Effekt-Hinweis „Änderung wirkt auf neu angelegte Worktrees / beim nächsten Hochfahren – bestehende Worktrees bleiben unverändert." (amber/tertiary) | `settings.repo.effectHint` |
| **Ungültige URL** (`400 invalid_repo_url`) | Feldfehler „Ungültige Repository-URL" (Fehler-Ton) | `settings.repo.error` |

> **Disclosure:** Der Effekt-Hinweis ist **Pflicht** — eine geänderte Repo-URL ändert **nicht** rückwirkend bestehende Checkouts. Ehrlich: bestehende Worktrees bleiben auf dem alten Remote, bis sie neu angelegt werden.

### 3.3 a11y / RTL
- Felder linksbündig im Lesefluss; RTL spiegelt automatisch (LTR-Eingabe von URLs bleibt LTR-isoliert — `OutlinedTextField` handhabt BiDi; Spec verlangt keine Sonderlogik, nur den Standard).
- Save als echter Button mit Rolle; Gate-/Effekt-/Fehler-Hinweise sind Text + Ton + a11y-Label.

---

## 4. CYP-85 — API-Key (hinterlegen / ändern)

### 4.1 Layout
Abschnitt `settings.section.apiKey` (Überschrift `settings_apikey_section`):
- **Status-Zeile** (read, nicht editierbar): hinterlegt = „Hinterlegt: %1$s" mit `%1$s` = `***<last4>` (`settings_apikey_masked`); unset = „Kein Schlüssel hinterlegt" (`settings_apikey_unset`). Tag `settings.apiKey.masked`.
- **Neuer Schlüssel** — `OutlinedTextField`, **write-only**, `PasswordVisualTransformation` (maskiert beim Tippen), Placeholder `settings_apikey_placeholder` („Neuen Schlüssel eingeben…"), Tag `settings.apiKey.input`, a11y `a11y_settings_apikey_input`.
- **Anzeigen/Verbergen-Toggle** — entmaskt **nur die aktuelle Eingabe**, nie den gespeicherten Wert; Tag `settings.apiKey.reveal`, a11y `a11y_settings_apikey_reveal` (Zustand „anzeigen"/„verbergen" im Label).
- **Speichern** — `Button`, Label `settings_save`, Tag `settings.apiKey.save`. Enabled nur mit Operator-Token + nicht-leerer Eingabe.

### 4.2 Zustände & Disclosure
| Zustand | UI | Tag |
|---|---|---|
| **Kein Operator-Token** | Eingabe+Save disabled, Status-Zeile bleibt sichtbar, Gate-Hinweis „Nur mit Operator-Token änderbar." | `settings.apiKey.gateHint` |
| **Hinterlegt** | „Hinterlegt: ***ef456" (nur maskiert, **nie** Klartext) | `settings.apiKey.masked` |
| **Nach Save** | „Gespeichert. Wirkt erst beim nächsten Start des Agenten – jetzt neu starten, damit der neue Schlüssel zieht." (amber/tertiary) **+ Verweis/Reuse** auf CYP-73-Restart (`agent.<id>.restartBtn`, Label `agent_ctl_restart`) | `settings.apiKey.effectHint` |
| **Save fehlgeschlagen** (`400 invalid_api_key` / 403) | „Speichern fehlgeschlagen" bzw. Gate-Ablehnung (Fehler-Ton) | `settings.apiKey.error` |

> **Disclosure (Kern, sensible/finanzielle Aktion):** Der API-Key ist kostenrelevant. Die UI **darf nicht** suggerieren, der laufende Agent nutze den neuen Key sofort. „Gespeichert" ist **amber**, nicht grün; die einzige ehrliche Aktivierung ist ein **Restart** (CYP-73). Der hinterlegte Key ist **nie** sichtbar — nur `***last4`; das verhindert versehentliches Leaken in Screenshots/Logs/Screensharing.

### 4.3 a11y / RTL
- `PasswordVisualTransformation` für die Eingabe; der Reveal-Toggle hat zwei a11y-Zustände.
- Maskierte Status-Zeile ist reiner Text (kein Secret im a11y-Baum außer den letzten 4 Stellen, die bereits server-maskiert sind).

---

## 5. Reuse (statt Eigenbau)

| Element | Reuse-Quelle | Verifiziert |
|---|---|---|
| Fenster-Chrome / Mount | `WindowTestTags` (`window.<id>`/`.content`/`.titlebar`), `AgentShell.kt` windows-Liste + `windowContent`-`when` | ✅ Code |
| Formular-Muster | `OutlinedTextField` + `Button` in `CommPanel.kt` / `AgentWindow.kt` | ✅ Code |
| Operator-Gate-Muster | `editable = operatorToken != null` (ACL `AclViewModel`), `canControl` (CYP-73 `AgentHeader`) | ✅ Code |
| Banner-/Hinweis-Töne | offline=**tertiary/amber**, info=**secondary**, Fehler=**error** (ACL-Re-Pass-Korrektur; nicht alles error-rot) | ✅ Design-Entscheid |
| Server-Maskierung | `Secrets.mask()` = `***`+letzte 4 | ✅ Code |
| Restart-Aktivierung | **CYP-73** `agent.<id>.restartBtn` / `agent_ctl_restart` / `POST /api/agents/{id}/restart` | ✅ Code |
| Save-Label | **ein** geteilter Key `settings_save` für beide Abschnitte | — |

**Keine Dubletten:** kein zweiter Restart-Button (Reuse CYP-73), kein client-seitiges Re-Mask (Server liefert bereits maskiert), kein eigener Operator-Banner-Stil (Töne wie ACL/Comm).

---

## 6. Offene Punkte (PO / Backend)

1. **Endpunkt-Form bestätigen** (§2-Tabelle): Pfade/Codes (`invalid_repo_url`, `invalid_api_key`), Read-Gate für `GET /api/config/repo` (Operator vs. auth).
2. **Pro-Projekt-Auflösung**: Wann zieht das Backend von global → pro Projekt (D3)? Ohne sie zeigt die UI global geteilte Werte. → Backend-Seam, Priorität klären.
3. **Restart-Kopplung CYP-85**: Reicht der Verweis/Reuse auf den bestehenden CYP-73-Restart, oder soll die Settings-UI einen direkten „jetzt neu starten"-Trigger (gleicher Endpoint) bieten? (Default-Vorschlag: Verweis + optionaler Inline-Trigger, der `agent.<id>.restartBtn`-Pfad wiederverwendet.)
4. **Repo-URL-Validierung**: Wo (Client-Form vs. Server-`400`)? Vorschlag: Server ist Source-of-Truth, Client zeigt nur den Fehler.
5. **Scope-Klärung**: Bleibt es bei einem geteilten Settings-Fenster für beide Abschnitte (so spezifiziert), oder zwei getrennte Fenster? (Empfehlung: ein Fenster, zwei Abschnitte — verhindert divergierende Mounts.)

**Gate (später):** Reviewer + Desktop-`runComposeUiTest` (Tags in `project-settings-tags.md`). Diese Spec ist **docs-only**; sie baut kein Backend, sondern definiert den Vertrag.
