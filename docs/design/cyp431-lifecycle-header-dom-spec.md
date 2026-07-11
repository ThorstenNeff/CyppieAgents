# CYP-431 (P2-a) — Agent-Fenster Lifecycle-Header im DOM: Status + start/stop/restart

> Owner: UIUX-Designer · Ticket **CYP-431** (Epic **CYP-430** Voller-Ersatz-Cutover, P2-a) · Stand 2026-07-11
> Basis `origin/develop` `3213a1b3` · `09-UI-Funktionskatalog` §3 · Docs-only → **Referenz für Dev5**.
> Sitzt im Agenten-Fenster (dep **CYP-425**-Assembly). **Port der Compose-Quelle, kein Neuentwurf.** **Nichts gebaut.**
>
> **Quelle der Wahrheit:** `agentview/AgentWindow.kt` → `AgentHeader` / `AgentLifecycleControls` / `StatusIndicator`
> (CYP-396 `statusDotSpec`), `AgentViewModel` (Lifecycle-Aktionen + Pending), `net`-Feed `/ws/lifecycle`. Alle Keys
> (`agent_ctl_*`, `agent_status_*`, `agent_ctl_err_*`, `a11y_agent_status`) und Tags (`header`/`status`/`startBtn`/
> `stopBtn`/`restartBtn`/`lifecycleError`/`reconnecting`) sind **bestehender Vertrag** — portieren, **0 neue Keys/Tags**.
>
> **Nicht in CYP-431:** der ERROR-**Grund** (das *warum* eines ERROR-Zustands) — separat via **CYP-421** + Spec
> `error-reason-disclosure-spec.md`. Hier nur das **Zustands-Wort** ERROR (nicht sein Grund) und die **Aktions**-Fehler.

---

## 1. Anatomie (Mirror `AgentHeader`) — und die eine Verteilungs-Regel

Eine Kopfzeile über dem Content-Rechteck:

```
┌ agent.<id>.header ─────────────────────────────────────────────────────────────┐
│  [ Identitäts-Cluster  (schrumpfbar) ]              [ Lifecycle-Controls (fest) ] │
│  status · reconnecting? · (provider?) · (fidelity?)   ▶ Start  ■ Stopp  ↻ Neustart│
└──────────────────────────────────────────────────────────────────────────────────┘
```

- **P2-a-Scope:** `status` (§3) + die drei **Controls**. `reconnecting` (CYP-204, eigene Achse) gehört dazu, weil es
  Teil derselben Kopfzeile ist. **`provider`/`fidelity`-Badges** sind **andere** 09-Funktionen (Connector-
  Capabilities) — sie sitzen im selben Cluster, kommen aber in **eigenen** Slices; die Verteilungs-Regel muss sie
  **vorsehen**, dieser Slice liefert sie nicht.

**Die Verteilungs-Regel (CYP-369 — unverhandelbar, sonst wiederholt sich der Original-Bug):** in der Compose-`Row`
werden die **Controls zuerst** (ungewichtet, intrinsische Breite) gemessen, der **Identitäts-Cluster zuletzt**
(gewichtet, bekommt den Rest). Grund: früher lag der Cluster vorn und nahm bei 320 dp die ganze Zeile → **Stopp/
Neustart wurden mit `maxWidth = 0` gemessen = 0 dp breit = unsichtbar**; der Operator konnte einen gekachelten
Agenten weder stoppen noch neu starten, und **nichts sagte es** (ein 0-dp-Control und ein abwesendes Control sehen
von außen gleich aus). **Marker dürfen schrumpfen, ein Control nicht.**

> **DOM-Umsetzung der Regel:** die Controls-Gruppe = `flex-shrink: 0` (nie schmaler als ihr Inhalt); der
> Identitäts-Cluster = `flex: 1; min-width: 0` (schrumpft, seine Marker ellipsen/weichen **innerhalb ihrer eigenen
> Regeln**). **Nie** ein Control per `flex-shrink` opfern. **Abnahme-Zahn (§8):** bei enger Breite ist jeder Control
> im DOM present **und** hat eine gemessene Breite > 0 / ist klickbar — nicht bloß „im Baum".

---

## 2. Status-Indikator (Port CYP-396 `statusDotSpec` + Wort + Stimme)

`agent.<id>.status` — **ungated** (immer sichtbar, auch ohne Operator). Vier Träger, Farbe **nie** allein (WCAG 1.4.1):

| Zustand | Form (CYP-396) | Farbrolle | Wort (`agent_status_*`) |
|---|---|---|---|
| `RUNNING` | gefüllte Scheibe | `primary` | Läuft |
| `STOPPED` | gefüllte Scheibe | `outline` | Gestoppt |
| `ERROR` | gefüllte Scheibe | `error` | Fehler |
| **`UNKNOWN`** | **Ring** (`border`, offene Mitte) | `outline` | Unbekannt |
| Pending (Start/Restart in flight) | gefüllte Scheibe | `onSurfaceVariant` (neutral) | **Startet… / Neustart…** |

- **`statusDotSpec(state, pending)` als reine Funktion portieren** (wie in der Kotlin-Quelle nach CYP-396): Form+Rolle
  testbar ohne Pixel-Vergleich. DOM: Form → `RING` = `border: 2px solid var(--md-sys-color-outline)` mit offener
  Mitte, `FILL` = `background`; 8px-Punkt bleibt 8px. **`UNKNOWN` ist ein Ring, nie eine blasse Scheibe** (der
  1,41:1-`outlineVariant` ist verboten — CYP-396).
- **Wort + Stimme:** jeder Zustand rendert sein Label; `aria-label`/`contentDescription` = `a11y_agent_status(label)`
  (für `UNKNOWN` und `ERROR` verschieden). Der Status-Knoten = `role="status"` (höfliche Live-Region), damit ein
  Zustandswechsel angesagt wird.
- **Token-Voraussetzung:** `--md-sys-color-{primary,outline,error,on-surface-variant}` müssen als CSS-Variablen
  existieren (die Maritime-Token-Ebene, die W6 noch fehlt — dieselbe Abhängigkeit; **kein Alpha** auf `outline`,
  CYP-337/W6-Lektion).

---

## 3. Die Pending-Transienten — Ehrlichkeit vor der Server-Bestätigung

Ein Klick auf Start/Restart zeigt **sofort** den **client-seitigen** Transient `Startet…` / `Neustart…`
(`startPending`/`restartPending`), **nie** ein aufgelöstes Label, bevor der Server es bestätigt (CYP-262/330). Der
Transient ist die **ehrliche** Quittung „wir haben gefragt", nicht „es läuft". Er löst sich auf dem **nächsten
`AgentRunStateEvent`** (§4) auf — beide Flags können nie kleben.

> Das ist **nicht-Optimismus in der Zeit**: die Anzeige sagt „in Arbeit", nicht das gewünschte Ergebnis. Sie
> überschreibt den echten Zustand nicht — sie markiert das Warten.

---

## 4. Nicht-optimistischer Status — die Quelle ist der Feed, nie eine Ableitung

**Der Zustand spiegelt den `/ws/lifecycle`-Feed, er wird nie geraten.** In web-ts **existiert der Feed schon**:
`net/channels.ts` → `lifecycleFeed()` = `OneWayFeed<AgentRunStateEvent>` auf `/ws/lifecycle` (W2). Der Header muss ihn
nur **konsumieren**:

- `AgentRunStateEvent` ist **content-free** (`{agentId, runState}`, CYP-421) — genug für Form+Wort, **kein** Grund
  (der kommt separat, CYP-421-Consumer).
- **Regel:** die UI mappt `RUNNING/STOPPED/ERROR` 1:1; **`UNKNOWN`** ist **client-only** (vor dem ersten Snapshot) und
  wird nie aus dem Feed erzeugt — es ist der ehrliche „noch nichts gehört"-Zustand, **nie** auf STOPPED/RUNNING
  aufgelöst (Bindeglied CYP-351 §3).
- Ein Control-Klick ändert **nichts** am gezeigten Zustand außer dem Transient (§3); der **Server ist autoritativ**;
  ein abgelehnter POST landet auf der Aktions-Fehlerzeile (§6), **nicht** als vorgetäuschter Zustandswechsel.

**⟂BE:** keine neue Backend-Naht — `/ws/lifecycle` + die operator-gated `POST /api/agents/{id}/{start|stop|restart}`
existieren (Compose `AgentLifecycleRepository`). Der DOM-Header braucht denselben REST-Aufruf (operator-Bearer) —
Naht in `net/rest.ts`.

---

## 5. Lifecycle-Controls — Sichtbarkeit, Gate, Compact

Drei Controls, **immer alle drei präsent** (nicht bedingt entfernt), Enablement variiert:

| Control | Glyph | Key | `enabled` (Compose-Vertrag) |
|---|---|---|---|
| Start | ▶ | `agent_ctl_start` | `canControl && state != RUNNING` |
| Stopp | ■ | `agent_ctl_stop` | `canControl && state == RUNNING` |
| Neustart | ↻ | `agent_ctl_restart` | `canControl` — **jeder Operator** (CYP-330: Neustart darf in UNKNOWN/STOPPED nicht hart-wirkungslos sein; der Server bleibt autoritativ) |

- **Operator-Gate, fail-closed:** ohne Operator (`!canControl`) sind **alle drei disabled** — **present-but-disabled**
  (`aria-disabled="true"`), **kein** Entfernen, **kein** Fake-Control (CYP-317 „no fake switch"). Der Status bleibt
  ungated sichtbar (man sieht den Zustand, treibt ihn nur nicht).
- **Compact (CYP-350):** unter einer Breite, bei der die beschrifteten Buttons nicht mehr passen, → **Glyph-Buttons**
  (Wort weg, Glyph + `aria-label`=Label bleiben). **Kein gekürzter Text** — das Label überlebt als Glyph+Stimme, die
  Bedeutung geht nicht verloren. **Die Schwelle wird gemessen, nicht gewählt** (Compose: `AgentHeaderControlsGuardTest`
  leitet sie aus der gerenderten Komposition ab) → im DOM ebenso **messen** (Controls passen? sonst Glyph-Modus),
  keine hartkodierte px-Zahl.
- **Zielgröße ≥ 24px** (WCAG 2.5.8) je Control, auch im Glyph-Modus.

---

## 6. Aktions-Fehlerzeile (`lifecycleError`) — ≠ ERROR-Zustands-Grund

Ein abgelehnter Control-Aufruf (409/403/503/404) erscheint auf `agent.<id>.lifecycleError` (error-Ton, klein, volle
Breite, **über** der Kopfzeile). Codes → lokalisiert: `already_running`→`agent_ctl_err_already_running`,
`spawn_failed`→`_spawn_failed`, `operator_required`→`_operator_required`, sonst `agent_ctl_err_generic`.

> **Nicht verwechseln mit dem ERROR-Zustands-Grund (CYP-421):** diese Zeile ist der Fehler **einer Aktion**
> (transient, beim nächsten Event gelöscht); der ERROR-Grund ist das *warum* eines **durablen Zustands** (eigener
> Knoten `errorReason`, CYP-421). Getrennte Knoten, getrennte Quellen — s. `error-reason-disclosure-spec.md` §1.

---

## 7. Reconnecting-Chip (CYP-204) & der Restart-bei-Key-Wechsel-Hinweis

- **`reconnecting`** (CYP-204): präsent **nur** während der Agent-WS **nicht LIVE** ist (Auto-Reconnect). **Eigene
  Achse** — Socket-Zustand ≠ Prozess-Zustand; nie in den Lifecycle-Status mischen. DOM: eigenes Element, `aria-live`.
- **Restart-bei-Key-Wechsel:** der Hinweis-Text **wohnt in der Settings-Fläche**, nicht im Header — `settings_apikey_
  effect_hint` „Gespeichert. Wirkt beim nächsten Start — jetzt neu starten, damit der neue Key greift." Er **zeigt auf
  genau diesen `restartBtn`** (Reuse, **kein** neuer Key, CYP-73). **Parität-Konsequenz:** der Header rendert **keinen**
  eigenen Key-Hinweis; er stellt nur den Neustart-Control bereit, auf den der Settings-Hinweis verweist. *(Falls
  gewünscht wäre ein header-residenter „Key veraltet — Neustart nötig"-Nudge — das wäre **net-neu**, über die Compose-
  Parität hinaus, und bräuchte einen neuen Key + ein „Key-Generation"-Signal am Agenten. Als **Scope-Frage** markiert,
  nicht erfunden.)*

---

## 8. Abnahme-Zähne (diskriminierend) — je mit der falschen Impl, die er ablehnt

1. **Status spiegelt den Feed, nie geraten.** `/ws/lifecycle`-Event `RUNNING` → Status „Läuft". **Mutation:** Status
   aus einer lokalen Vermutung (z. B. „gerade Start geklickt → RUNNING") ⇒ rot.
2. **Pending nie ein aufgelöstes Label.** Start-Klick → „Startet…", **nicht** „Läuft", bis das Event kommt.
   **Mutation:** optimistisch „Läuft" direkt nach Klick ⇒ rot.
3. **`UNKNOWN` = Ring, nie blasse Scheibe; nie aufgelöst.** **Mutation:** `UNKNOWN`→FILL/`outlineVariant` ⇒ rot;
   Mapping `UNKNOWN→STOPPED` ⇒ rot. (Reuse der CYP-396-Zähne.)
4. **Enablement-Matrix.** Start enabled ⇔ `≠RUNNING`; Stopp ⇔ `=RUNNING`; Neustart ⇔ Operator (auch UNKNOWN/STOPPED,
   CYP-330). **Mutation:** Neustart in UNKNOWN disabled ⇒ rot.
5. **Non-Operator = alle disabled, present.** `!canControl` → alle drei `aria-disabled`, **im Baum**; Status weiter
   sichtbar. **Mutation:** Controls entfernt (statt disabled) **oder** ein aktives Fake-Control ⇒ rot.
6. **Controls weichen nie (CYP-369).** Bei enger Breite hat jeder Control eine Breite > 0 / ist klickbar; der
   Identitäts-Cluster schrumpft. **Mutation:** Controls `flex-shrink`-bar → 0-Breite bei enger Kachel ⇒ rot.
7. **Compact hält die A11y (CYP-350).** Glyph-Modus: `aria-label` = volles Label je Control. **Mutation:** Glyph ohne
   `aria-label` ⇒ rot.
8. **Aktions-Fehler ≠ Zustands-Grund.** `lifecycleError` (Aktion) getrennt von `errorReason` (Zustand, CYP-421).
   **Mutation:** ein Control-Reject erscheint als ERROR-Zustands-Grund ⇒ rot.

---

## 9. Keys & Tags — alles bestehend (0 neu aus dem Port)

**Keys (Reuse):** `agent_ctl_start`/`_stop`/`_restart`, `agent_status_running`/`_stopped`/`_error`/`_unknown`,
`agent_status_starting`/`_restarting`, `a11y_agent_status`, `agent_ctl_err_already_running`/`_spawn_failed`/
`_operator_required`/`_generic`, `settings_apikey_effect_hint` (settings-resident, referenziert). **Tags (Reuse):**
`agent.<id>.header`/`.status`/`.startBtn`/`.stopBtn`/`.restartBtn`/`.lifecycleError`/`.reconnecting` — im DOM als
`data-testid`, punktfrei-Schema unverändert (Test-Contract v0.5, geteilt mit QA CYP-7).

**Falls** die DOM-Umsetzung ein Element zeigt, das Compose nicht hat (§7-Nudge), liefere ich den Key impl-nah
(Shared-Key-Drift: ich entwerfe, Dev landet mit der Impl). Sonst **kein** neuer Key.

**Nichts gebaut — Spec + Referenz für Dev5. Der Status-Feed ist schon verdrahtet (`lifecycleFeed`), die Controls-
REST-Naht (`net/rest.ts`, operator-Bearer) ist die einzige neue Client-Verkabelung; die Token-Ebene (§2) ist die
gemeinsame Voraussetzung mit W6/W8/W9.**
