# Design-Spec — CYP-316: Live Context-Token-Zahl in der Agenten-Fenster-Titelzeile

> **Status:** DESIGN-SPEC (kein Bau) · Owner: UIUX · Story (Med) · docs-only auf `feature/CYP-316-context-tokens-spec` (off develop `21199e1`).
> **Ziel:** In der **Titelzeile jedes Agenten-Fensters** erscheint die aktuelle **Context-Token-Zahl** live, kompakt (z. B. „137k"), klar dem Fenster/Agenten zugeordnet, **kein Layout-Bruch**. Rein anzeigend.
> **Grounding (echter Code):**
> - **Titelzeilen-Row** (`window/WindowManager.kt`, `FloatingWindow`): `[titleBarLeading Avatar] · Text(window.title, weight(1f, fill=false), maxLines=1, Ellipsis) · [WindowBadgeView badge] · [TextButton „⋮" onSettings]`, `Row(padding 12/8)`. **Alle Titelzeilen-Farben = `barContent`** (die agent-abgeleitete onColor **auf dem farbigen `barBg`** — nicht `onSurfaceVariant`); Fokus/Unfokus dimmt den Hintergrund, **onColor bleibt voll** (UX-QA-[Low]① Kommentar: 0.75-Alpha könnte < 4.5:1 fallen → keine Alpha-Reduktion).
> - **Host-injizierte Live-Naht:** `badgeFor: (String) -> WindowBadge? = { null }` (fail-closed), in `AgentShell` verdrahtet `badgeFor = { id -> badges[id] }` aus einer WS-gespeisten Map. Die Token-Zahl spiegelt **genau diese Naht** (Anti-Divergenz).
> - **CYP-36 = context.usage-Banding (existiert):** der Server emittiert einen **`bandPct`** (Prozent-Band, capability-gated) als `EventType.CONTEXT_USAGE`-Event — **nicht** absolute Tokens. `ConnectorCapabilities.structuredUsage` gated die Fähigkeit → **Connector-B hat sie evtl. nicht → `contextTokens == null`** (gründet den null-Fall).
> - **Kein Kompakt-Zahlen-Formatter** im `:app` → neuer Helfer nötig. `WindowTestTags`-Konvention: `window.$id.<element>` (z. B. `window.$id.titlebar`, `window.$id.settings`).
> - **Backend liefert** (Ticket-Kontrakt): Live-WS-Push `contextTokens: Int?` **pro Agent**.

---

## §1 — Format & Platzierung

**Platzierung:** ein kompaktes Element in der bestehenden Titelzeilen-`Row`, **hinter dem Titel, vor dem Badge** →
`Avatar · Titel(weight) · [137k] · Badge · ⋮`.
Der Titel trägt `weight(1f, fill=false)` und **ellipsiert zuerst**; die Token-Zahl ist ein **kleines Element fester Prägung** → sie rendert immer, der Titel weicht, **kein Überlauf/Umbruch** (§8-1).

**Format:**
- **Monospace** (`FontFamily.Monospace`), `labelSmall`, Farbe **`barContent`** (voll, keine Alpha-Reduktion → Kontrast auf `barBg`; Unterordnung via **Größe**, nicht Alpha). Monospace = **stabile Ziffernbreite** → ein Live-Tick erzeugt **keinen Reflow-Jitter** (§8-6).
- **Kompakt-Regel** (neuer reiner Helfer `formatCompactTokens(n: Int): String`, commonMain, testbar):
  - `n < 1000` → exakt („842")
  - `1_000 … 999_999` → `"${n / 1000}k"` („137k", „8k") — Ganzzahl-k für Glanzbarkeit; (Option: 1 Dezimale < 10k „8.5k", falls mehr Auflösung gewünscht — **Empf. Ganzzahl-k**)
  - `≥ 1_000_000` → `"%.1fM"` („1.2M")
  - keine Tausender-Trenner in der Kompaktform.
- **Sichtbar = nur die Zahl** (kompakt); die **Bedeutung** trägt das a11y-Label (§5) — kein sichtbares Text-Label (spart Titelzeilen-Breite; `k`/`M` sind sprachneutral).

---

## §2 — Zustände

| Zustand | Bedingung | Darstellung |
|---|---|---|
| **Z1 bekannt** | `contextTokens != null` | kompakte Zahl („137k"), monospace, `barContent` |
| **Z2 unbekannt** | `contextTokens == null` (Connector-B / vor 1. Turn / unbekannt) | **Element weggelassen** (nichts) — **nie „0"** |

**Empfehlung Z2 = weglassen** (kein Element): sauberste Lösung, kein Dauer-Rauschen auf Fähigkeits-losen Connectoren; der Wert **erscheint einfach**, sobald der erste WS-Push kommt. Ein dezentes „—" ist eine **akzeptable Variante**, falls ein sichtbarer Dauer-Slot gewünscht ist — aber **Empfehlung: weglassen**.

**⚠️ Harte Honesty-Invariante (§8-3):** `null` heißt **unbekannt**, **nie `0`**. „0" wäre die falsche Behauptung „leerer Kontext"; ein unbekannter/Vor-erster-Turn/remote-Zustand darf **keine erfundene Zahl** und **keine 0** zeigen.

---

## §3 — Honesty: kein client-seitiges Max/Prozent, kein Fake

- **Absolutwert steht allein.** Der WS-Push liefert **absolute** Tokens. Ein **Prozent** bräuchte ein **Modell-Max** — ein **hartkodiertes „200k" wäre falsch** (Modelle haben unterschiedliche Kontextfenster; PO-Warnung explizit). Also **v1: nur der Absolutwert**, kein Max/kein %.
- **Wert spiegelt den letzten Server-Push.** Vor dem ersten Push ist die Zahl **abwesend** (Z2), nicht 0.
- **Kein Farb-SUCCESS.** v1 ist **neutral** (`barContent`), kein grün/amber/rot. (Deckt die System-Regel a0/CYP-300: grün ist status-frei.)

---

## §4 — Optionales Annäherungs-Band (deferred, dokumentiert — NICHT v1)

Der PO fragt ein Farbband **nur** an, falls (a) an die **CYP-36-Bänder** andockbar **und** (b) das Modell-Max **zuverlässig vom Server** kommt.

- **Befund:** CYP-36 emittiert bereits ein **server-seitiges `bandPct`** (context.usage-Event, capability-gated) — das ist das **zuverlässige, server-berechnete** Band (der Server kennt das Modell-Max, der Client nicht).
- **Empfehlung: KEIN Band in v1** (= PO-Default-Lean). Der WS-Push dieses Tickets (`contextTokens`) ist **absolut** und trägt **kein** Max/Band — ein Client-abgeleitetes % wäre unehrlich.
- **Falls je gewünscht:** ein Titelzeilen-Band **dockt an den CYP-36-`bandPct`-Stream** (semantische Bänder — Annäherung via amber/rot in der CYP-274-Severity-Sprache, **kein** grüner „ok"-Erfolg), **nicht** an ein Client-Prozent. Das ist ein **eigener Follow-up** (koppelt die Titelzeile an den context.usage-Band-Stream + dessen Capability-Gate). v1 bleibt band-frei/neutral.

---

## §5 — Copy-Keys (DE + EN)

**NEU (required, je DE+EN):**
| Key | DE | EN |
|---|---|---|
| `a11y_agent_context_tokens` | Kontext-Tokens: %1$s | Context tokens: %1$s |

`%1$s` = der kompakte Wert („137k"). Als `contentDescription` am Zahl-Element → ein Screenreader hört „Kontext-Tokens: 137k" (Bedeutung, die dem sichtbaren kompakten Glyph fehlt).

**OPTIONAL (nur falls Desktop-Hover-Tooltip gebaut wird):**
| Key | DE | EN |
|---|---|---|
| `agent_context_tokens_tooltip` | %1$s Tokens im Kontext | %1$s tokens in context |

`%1$s` = **exakte** gruppierte Ganzzahl („137.214" / „137,214") → Hover zeigt die Präzision, die die kompakte Zeile weglässt. Nice-to-have, kein v1-Muss.

> **⚠️ Shared-Key-Drift:** die Keys landen in `values/strings.xml` (DE) **und** `values-en/strings.xml` (EN) **synchron** mit dem konsumierenden Modul. Timing mit dem Bau abstimmen.

---

## §6 — testTags (`window.*`, shared QA/CYP-7)

**NEU:** `WindowTestTags.contextTokens(id: String) = "window.$id.contextTokens"` — das Zahl-Element (nur in Z1 vorhanden; Abwesenheit in Z2 ist der Testvertrag „null → kein Knoten").

> Konvention wie `window.$id.titlebar` / `window.$id.settings`. Vor Landung gegen `WindowTestTags.kt` verifizieren ([[verify-reuse-testtags-against-code]]) + mit QA/CYP-7 syncen.

---

## §7 — Impl-/Contract-Deps

1. **Backend-WS-Push `contextTokens: Int?` pro Agent** (Ticket-Kontrakt) — ✅ Backend liefert. Capability-gebunden (`structuredUsage`): **Connector-B → `null`**; vor dem ersten Turn → `null`.
2. **Host-Naht (spiegelt `badgeFor`):** neuer Parameter `contextTokensFor: (String) -> Int? = { null }` (fail-closed) an `WindowManager`/`WindowCanvas`/`FloatingWindow`; in `AgentShell` verdrahtet `contextTokensFor = { id -> contextTokens[id] }` aus einem WS-gespeisten `StateFlow<Map<String, Int?>>` — **genau das Muster der Badge-Naht** (Anti-Divergenz). Default `{ null }` → System-Fenster/Tests **regressions-frei**.
3. **Neuer Formatter** `formatCompactTokens(Int): String` (commonMain, pure, unit-testbar).
4. **Kontrast:** Textfarbe = `barContent` (voll), monospace, kleine Größe zur Unterordnung — **nie** `onSurfaceVariant` (falscher Kontrast auf dem farbigen `barBg`).
5. **Phone-Pager:** **nicht v1.** Der schlanke Pager-Header (`PhonePager`, CYP-54 §4) lässt Badge/⋮ bereits weg („keine lügenden Badges dort", WINDOW-BADGES §8) → die Token-Zahl folgt derselben dokumentierten Grenze; kein falscher Wert im Pager. (Optionaler Follow-up, falls gewünscht.)

---

## §8 — Invarianten (= UX-QA-Abnahme)

1. **Kompakt, kein Layout-Bruch** — kleines Element fester Prägung; der Titel (`weight(1f, fill=false)`) ellipsiert zuerst; nie Überlauf/Umbruch/Push des ⋮.
2. **Klar zugeordnet** — sitzt in **dieser** Fenster-Titelzeile; a11y-Label nennt „Kontext-Tokens".
3. **`null ≠ 0`, kein Fake** — `null` → Element **abwesend** (oder dim „—"), **nie „0"**, nie erfundene Zahl.
4. **Kein Client-Max/-Prozent** — nur Absolutwert; **kein** hartkodiertes 200k, **kein** abgeleitetes %.
5. **Kein Farb-SUCCESS** — v1 neutral `barContent`, kein grün/amber/rot; ein etwaiges Band dockt an CYP-36-Server-`bandPct` (deferred, §4).
6. **Live ohne Jitter** — Monospace stabile Ziffernbreite; WS-Update ohne Reflow der Nachbar-Elemente.
7. **Kontrast + a11y** — `barContent` voll (AA auf `barBg`, Fokus **und** Unfokus), `contentDescription` gesetzt; es ist Text (Farbe nie alleiniger Träger, WCAG 1.4.1).
8. **Fail-closed Naht** — `contextTokensFor` default `{ null }`; unbekannt = abwesend; System-Fenster/Alt-Tests unverändert.

---

## §9 — Hand-off

- **Kein Bau, kein Merge.** Docs-only auf `feature/CYP-316-context-tokens-spec` (distinkter Worktree `CYP-316-spec`).
- **Reuse-Anker:** Titelzeilen-`Row`/`barContent` · Host-Naht `badgeFor`-Muster · `FontFamily.Monospace` · `WindowTestTags`-Konvention.
- **Keys/Tags** synchron mit dem konsumierenden Modul landen (shared QA/CYP-7); Tags gegen `WindowTestTags.kt` verifizieren.
- **UX-QA nach Bau:** die 8 §8-Invarianten, gerendert, Z1 **und** Z2 (Connector-B/null), DE+EN, Fokus + Unfokus (Kontrast), Live-Tick ohne Jitter.
