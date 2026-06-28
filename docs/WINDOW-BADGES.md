# Per-Fenster-Badges — Titelleisten-Aktivitätshinweise (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-55** (Task unter CYP-3) · Status: **Entwurf — wartet auf Dev-Gegenlesen + PO/Backend-Datenpfad-Entscheid** · Stand: 2026-06-28
> **Kanonischer Ort:** `KMPCyppieAgents` unter `docs/WINDOW-BADGES.md`. Begleit-Artefakte (Muster CYP-17): `docs/design/window-badges-tokens.json`, `window-badges-keys.md`, `window-badges-tags.md`.
> Bezug (im Code verifiziert, develop `ebdf442`): Titelleiste in `window/WindowManager.kt` (`FloatingWindow` Canvas-Titlebar; `PhonePager` Header), `agentview/AgentStatus.kt`, `comm/CommViewModel.kt` + `model/CommModel.kt`, `eventlog/EventTailViewModel.kt` + `model/EventModel.kt`, `AgentShell.kt` (Operator-Gating), Reuse-Tokens CYP-12 (`state-tokens.json`) / CYP-34 (`event-log-tokens.json`) / CYP-14 (`ColorSlot`). **Brand:** CyppieAgents (Anti-Hype).

Spezifiziert einen **Aktivitätshinweis-Badge** hinter dem Fenstertitel (Canvas-Titelleiste **und** Pager-Header). Der Badge ist ein **passiver Hinweis** — „hier gibt es etwas", **nie** „erledigt/zugestellt". Keine Implementierungsvorgabe.

> **PO-Abnahme (2026-06-28):** Spec **akzeptiert**. **MVP-Scope = B1 + A1 + C1** (null Backend, null Leak). **A2** (Stall-Signal) und **C2** (content-freies Per-Agent-Severity-Aggregat) sind **deferred → kein Backend-Pull für CYP-55**; **B2** (Last-Read) bleibt späteres Upgrade. **B1-Reset bei Fokus = ja.** `WAITING_FOR_INPUT` wird **nicht** gefakt.

---

## 0. Die drei Badge-Typen (nach Fenstertyp) & ihre Reife

| Fenster | Badge bedeutet | Datenquelle heute (develop `ebdf442`) | Reife |
|---|---|---|---|
| **Comm** | ungelesene/neue Nachrichten (Anzahl) | **fehlt** — kein Unread-/Last-Read-State (`CommUiState.messages` ist flache Liste) | ⚠ braucht Quelle (§2-B) |
| **Agent** (je Agent) | Agent **braucht evtl. Aufmerksamkeit** | `AgentStatus.WAITING_FOR_INPUT` **existiert, wird aber absichtlich nie gesetzt** (kein ehrliches stream-json-Signal) | ⚠ braucht ehrliche Quelle (§2-A) |
| **Event-Log** (operator-gated) | höchste offene **Severity** | Severity liegt pro Event vor; Fenster nur bei `operatorToken != null` | ✅ Daten da, **inhärent gated** (§2-C) |

> **Kernbefund:** Nur der **Event-Log-Severity-Badge** ist heute ohne neue Quelle ehrlich darstellbar — **und genau er** berührt die Operator-Gating-Grenze. Die anderen beiden brauchen eine ehrliche Datenquelle (Backend-Naht oder client-lokaler MVP-Fallback). Deshalb steht der **Datenpfad (§2) vor** dem Visuellen (§3).

---

## 1. Leitprinzipien (durchgehend, verbindlich)

1. **Aus bestehendem/ehrlichem State** — ein Badge wird **nie geraten**. Gibt es keine erlaubte Quelle → **kein Badge** (kein „0", kein Phantom). *Fail-closed.*
2. **Kein Kontext-Klau:** ein Hintergrund-Ereignis **erzeugt den Badge**, springt aber **nicht** automatisch zum Fenster/zur Seite (konsistent zu S10 §5 Deep-Link). Sprung nur auf **expliziten** Tap.
3. **Hinweis ≠ Garantie:** Badge = „es gibt Aktivität/Aufmerksamkeitsbedarf", **nicht** „zugestellt/erledigt/beantwortet".
4. **Farbe nie alleiniger Träger** (WCAG 1.4.1): Form + Icon + Text/Zahl + a11y-Label. **Identitätsfarbe (CYP-14) markiert nie eine Berechtigung oder Severity** — Identität und Badge sind getrennte Achsen.
5. **Omission-Honesty:** operator-gated abwesende Fenster (Event-Log ohne Token) haben **keinen** Badge, weil sie **kein** Fenster sind (kein „kein Zugriff"-Platzhalter).

---

## 2. Datenpfad — fail-closed & Leak-frei (DER kritische Teil)

> **PO/Backend-Auflage (2026-06-28, verbindlich):** Der Event-Severity-Badge sitzt in der **immer sichtbaren** Titelleiste, die Event-Daten liegen aber hinter dem **Operator-Token** (`/api/events`, fail-closed). Ein nicht-gateter Datenpfad darf **nur ein minimales, content-freies Severity-Aggregat** liefern (Enum), **niemals** Event-Details/Bodies/Metadaten — sonst hebelt die Titelleiste den operator-gated Event-Egress aus (Leak-Vektor). Liegt kein erlaubtes Aggregat vor → **kein Badge**.

### 2-A. Agent-Badge (Aufmerksamkeit)

- **Heute:** `AgentStatus` = `RUNNING/IDLE/WAITING_FOR_INPUT/ERROR/OFFLINE`. `WAITING_FOR_INPUT` wird **bewusst nie** aus dem Transkript abgeleitet (stream-json hat kein „wartet auf Eingabe"-Signal; es zu raten wäre eine **Disclosure-Verletzung**, CYP-12 §3.2). Serverseitig erkennt der **StallDetector** `stall.suspected`, aber dieses Signal fließt **nur in den (operator-gated) Event-Log**, nicht in den Agent-Stream.
- **Optionen:**
  - **A1 (empfohlen für MVP-Ehrlichkeit, kein Fake):** Agent-Badge **vorerst nur** für `ERROR` (ehrlich aus dem Transkript ableitbar) — „Agent-Fehler". **Kein** WAITING_FOR_INPUT-Badge, solange es kein Signal gibt.
  - **A2 (ehrliche Erweiterung, Backend-Naht):** ein **non-gated, content-freies Per-Agent-Attention-Flag** (z. B. aus `stall.suspected` abgeleitet) wird in den `/ws/agent`-Stream gespiegelt → Badge „braucht evtl. Aufmerksamkeit" (Wortlaut **vorsichtig**: „stockt/ggf. Eingabe nötig", **keine** erfundene Frage). Enum/Bool, **keine** Event-Bodies.
  - **A3 (Zukunft):** echtes WAITING_FOR_INPUT über die **Eskalations-Naht** (05 §5) — dann trägt der Badge die wahre Bedeutung.
- **Empfehlung:** **A1 jetzt** (ehrlich, null Backend), **A2** als schmale Naht falls der PO ambienten Stall-Hinweis will. **WAITING_FOR_INPUT nicht faken.**

### 2-B. Comm-Badge (Unread)

- **Heute:** kein Unread-/Last-Read-Tracking (weder Client noch Server); `Message` hat kein `read`-Feld.
- **Optionen:**
  - **B1 (empfohlen MVP, kein Backend):** **session-lokaler Aktivitäts-Zähler** — zähle Nachrichten, die in einem Kanal eintreffen, **während dessen Comm-Fenster nicht fokussiert** ist; **reset bei Fokus/Öffnen**. Ehrlicher Wortlaut = „**neu**" (Aktivität seit zuletzt), **nicht** „ungelesen von Record". Rein clientseitig, kein Persist.
  - **B2 (dauerhaft, Backend-Naht):** Per-Operator-Last-Read-Position (`lastReadTs/seq`) serverseitig → echtes Unread über Sessions.
- **Empfehlung:** **B1 jetzt** (ehrlich, null Backend), **B2** als Upgrade. Wortlaut/a11y machen die Bedeutung von B1 transparent (s. Keys).
- Comm ist **nicht** operator-gated → kein Leak-Thema.

### 2-C. Event-Log-Badge (max Severity) — die gated Grenze

- **Heute:** `Severity` = `DEBUG/INFO/WARN/ERROR` liegt pro Event vor; Event-Log-Fenster existieren **nur** bei `operatorToken != null` (`AgentShell`).
- **Optionen:**
  - **C1 (empfohlen MVP, kein Backend, kein Leak):** Badge sitzt **ausschließlich auf dem (gated) Event-Log-Fenster** selbst. `maxSeverity = events.maxOf { severity }` rein clientseitig aus dem **bereits operator-gated** Tail/Browse-State. Das Fenster **ist** die Gate → Omission automatisch (kein Token → kein Fenster → kein Badge). **Null** neue Quelle, **kein** Egress-Leak.
  - **C2 (nur falls ambiente Severity an immer-sichtbaren Titelleisten gewünscht):** erfordert das **minimale, content-freie Severity-Aggregat pro Agent** aus der PO/Backend-Auflage — **Enum** („höchste offene Severity"), **niemals** Event-Inhalt. **Fail-closed:** kein erlaubtes Aggregat → kein Badge. Das ist eine **schmale Backend-Naht** (PO zieht Backend).
- **Empfehlung:** **C1 für MVP** — erfüllt die Auflage trivial (gated Fenster = gated Daten, kein neuer Pfad). **C2 nur**, wenn das Produkt Severity-Awareness *ohne Öffnen des Event-Logs* will; dann strikt nach der Auflage (Enum-only, fail-closed).

### 2-D. Entscheidungsmatrix

| Badge | Daten da? | Gated? | Backend nötig (MVP)? | MVP-Pfad | Fail-closed-Regel |
|---|---|---|---|---|---|
| **Comm Unread** | nein | nein | **nein** (B1 client-lokal) | B1 „neu seit zuletzt" | keine Aktivität → kein Badge |
| **Agent Attention** | nur `ERROR` | nein | **nein** (A1); A2 = schmale Naht | A1 nur ERROR | kein ehrliches Signal → kein Badge (kein Fake-WAITING) |
| **Event Severity** | ja | **ja** | **nein** (C1 am gated Fenster) | C1 am Event-Log-Fenster | kein erlaubtes Aggregat/kein Fenster → kein Badge |

> **MVP-Empfehlung gesamt:** B1 + A1 + C1 — **null Backend, null Leak, voll ehrlich.** A2/B2/C2 sind klar umrissene Backend-Nähte, die der PO einzeln freigeben kann; ich liefere das Badge-**Design** so, dass die Verdrahtung pro Typ später nur „andocken" muss.

---

## 3. Anatomie & Varianten

Der Badge sitzt **am Ende der Titelleiste** (Canvas: `FloatingWindow`-Titlebar; Pager: `PhonePager`-Header — derselbe Slot, eine Design-Sprache). Reuse des bestehenden `KindBadge`-Musters (`CommPanel.kt`) als Bauform.

| Variante | Für | Form | Farbe (Reuse) | Text/Icon |
|---|---|---|---|---|
| **Count** | Comm Unread | Pille mit Zahl | `state.notice`/primary-container (neutral, **nicht** Severity) | Zahl, ab >9 → „9+"; a11y nennt Zahl |
| **Severity** | Event-Log | Pille/Punkt mit Severity-Icon | `event-log-tokens.json` `severity_rail` (error→state.error, warn→state.waiting, …) | Severity-Icon + a11y-Severityname |
| **Attention** | Agent (ERROR / opt. Stall) | Punkt/Dreieck | `state.error` (ERROR) bzw. `state.waiting` (Stall, A2) | Icon (`alert-triangle`/`attention`) + a11y-Text |

- **Form trägt Bedeutung:** Count = Zahl-Pille, Severity = Icon-Pille, Attention = Warn-Symbol — auch ohne Farbe unterscheidbar.
- **Max-Count:** „9+" statt großer Zahlen (Layout-Stabilität in der Titelleiste).
- **Ein Badge je Fenster** (der relevanteste); keine Badge-Stapelung im MVP.

---

## 4. Zustände & Disclosure-Honesty (verbindlich)

| Zustand | Darstellung | Disclosure-Regel |
|---|---|---|
| **keine Aktivität / count 0** | **kein** Badge | nie „0" anzeigen (kein Phantom) |
| **Comm: N neu seit zuletzt** | Count-Badge „N" | „neu seit zuletzt", **nicht** „ungelesen von Record" (B1) |
| **Fokus/Öffnen des Fensters** | Badge **verschwindet** (reset) | Hinweis erledigt = weg; kein dauerhafter „erledigt"-Zustand |
| **Agent ERROR** | Attention-Badge (error) | aus Transkript ehrlich; **kein** WAITING_FOR_INPUT-Fake |
| **Event-Log: max Severity = warn/error** | Severity-Badge | nur am **gated** Fenster (C1); Enum-only falls C2 |
| **kein Operator-Token** | **kein** Event-Log-Fenster, **kein** Badge | Omission; **kein** „kein Zugriff" |
| **kein erlaubtes Aggregat (C2-Pfad)** | **kein** Badge | **fail-closed** — nie Durchgriff auf gateten Inhalt |
| **Hintergrund-Aktivität (anderes Fenster/Seite)** | Badge erscheint am Ziel | **kein** Auto-Sprung/Fokuswechsel (S10 §5) |

**Kern-Disclosure:**
1. **Fail-closed überall:** keine erlaubte/ehrliche Quelle ⇒ kein Badge.
2. **Kein Leak:** der immer-sichtbare Titelleisten-Badge trägt **nie** operator-gated Inhalt — höchstens ein content-freies Enum/Count (C2/A2), sonst nur am gated Fenster (C1).
3. **Hinweis, keine Zustellung:** ein Badge behauptet nie „erledigt".
4. **Identität ≠ Badge:** CYP-14-Identitätsfarbe und Badge sind getrennt.

---

## 5. Per-Modus (Canvas + Pager) & Reuse zu S10

- **Canvas:** Badge als rechtsbündiges Element **in** der `FloatingWindow`-Titelleiste (neben `window.<id>.titlebar`-Titel).
- **Pager (S10):** Badge im `PhonePager`-Header **und** als Aktivitäts-Badge am Ziel-Dot/Seite — **reuse des bereits spezifizierten** `phonePager.page.<id>.badge` (CYP-54 §6, dort als „nach CYP-55" deferred). CYP-55 **füllt** diesen Slot jetzt. Wortlaut-Key `pager_activity_badge` (CYP-54 vorgesehen) wird hier real verdrahtet (s. Keys).
- Eine Bedeutung, zwei Orte — keine divergierende Badge-Sprache.

---

## 6. Tokens, i18n, testTags

- **Tokens:** `docs/design/window-badges-tokens.json` — **Reuse** Severity (`event-log-tokens.json`), Status/Attention (`state-tokens.json`); wenige Neue (Count-Pille, Max-Count-Schwelle, Badge-Maße).
- **i18n:** `docs/design/window-badges-keys.md` — Count-/Attention-/Severity-Wortlaut + a11y; **Reuse** `pager_activity_badge`/`a11y_pager_activity` (CYP-54).
- **testTags:** `docs/design/window-badges-tags.md` — Area `windowBadge`, scoped je `window.<id>`; **Fail-closed = Abwesenheits-Anker** (QA prüft: kein Badge ohne Quelle). Pager-Reuse `phonePager.page.<id>.badge`.

> **⚠ Shared-Key/Tag-Drift:** Keys + `WindowBadgeTags` landen in `:app:shared` → CYP-55-Impl + Test-Modul (CYP-7) müssen re-syncen. **Mit der Impl timen.**

---

## 7. Entscheide (PO 2026-06-28) — keine offenen Asks mehr

1. ✅ **Datenpfad = B1 + A1 + C1** (§2-D) übernommen — **null Backend, null Leak.**
2. ⏸ **A2** (Stall→non-gated Per-Agent-Attention) **deferred** → A2-Wortlaut vorerst moot; **kein** `stall.suspected`-Bridging in CYP-55.
3. ⏸ **C2** (content-freies Per-Agent-Severity-Aggregat) **nicht jetzt** → **kein** Backend-Severity-Vertrag, **kein** Backend-Pull. (C2-Grenze bleibt als Referenz hier dokumentiert, falls je gewollt.)
4. ⏳ **B2** (Last-Read) = späteres Upgrade, nicht MVP.
5. ✅ **B1-Reset bei Fokus** (Canvas) / aktiver Seite (Pager) = **ja**.
6. ✅ **A1 = nur ehrliches `ERROR`**; `WAITING_FOR_INPUT` wird **nicht** gefakt.

**Verbleibender MVP-Bauumfang (Dev):** Count-Badge (B1) + ERROR-Attention (A1) + Severity-Badge **am gated Event-Log-Fenster** (C1). **Gate:** Reviewer + Desktop-`runComposeUiTest` (Tags + Fail-closed-Abwesenheits-Anker).

---

## 8. Known limitation — Pager-Counter-Modus (>6 Fenster) hat keinen Per-Page-Badge-Slot

> **Bewusste Scope-Grenze (PO-bestätigt 2026-06-28), keine stille Lücke.** Aktiver Scope = **Desktop/Canvas** — dort ist jeder Badge in der `FloatingWindow`-Titelleiste sichtbar (kein Per-Fenster-Limit). Im **Phone-Pager** sitzt der Per-Page-Aktivitäts-Badge auf dem **Indikator-Dot** (`phonePager.page.<id>.badge`). Dots existieren laut CYP-54 §4 nur **bis 6 Seiten**; ab >6 Fenstern schaltet der Pager auf den kompakten **„N / M"-Counter** ohne Per-Page-Dots — **damit gibt es dort keinen Per-Page-Badge-Slot**.
>
> **Praktische Wirkung:** Der einzige Fall mit >6 Fenstern ist **Operator + Event-Log/Live-Tail** (7 Fenster). Genau dann fällt der C1-Severity-Badge im **Pager** weg — **Canvas/Desktop ist unberührt** (C1 dort voll sichtbar). Ohne Operator-Token bleibt es bei ≤5 Fenstern → Dot-Modus → B1/A1-Badges am Dot wie spezifiziert.
>
> **Ehrlichkeit gewahrt (kein lügendes Badge):** Im Counter-Modus wird **kein** falsches/leeres Badge erzeugt — **Abwesenheit** ist ehrlicher als ein irreführender Aggregat-Indikator. Ein Aggregat-Marker auf dem Counter-Chip ist ein **nice-to-have für später**, **nicht** Teil von CYP-55.
