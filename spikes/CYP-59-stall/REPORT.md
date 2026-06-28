# CYP-59 — Spike: Stall / Auto-Nudge de-risk (Scanner & Warden, S11 / Epic CYP-58)

> Status: abgeschlossen 2026-06-28 · Branch `feature/CYP-59-stall-spike` (Base develop `e6bd825`)
> Spec: `07-Mediator-Aufsicht-Scanner-Warden.md` §4/§5 · Begleitend: `06` (Event-Log-Bus)
> Auth aller Live-Runs: **OAuth-Abo-Creds (`~/.claude`)**, `ANTHROPIC_API_KEY` bewusst **unset** (Standing-Policy).

Klärt die drei §5-offenen Punkte **empirisch**, bevor Detector (CYP-61) / Policy (CYP-63) gebaut werden.
Methodik: (a) Wire-Vokabular aus der **ausgelieferten CLI** (`claude` 2.1.193, Bun-ELF) extrahiert
(Objekt-Literale, nicht Gedächtnis); (b) **Live-Run** einer echten stream-json-Session für die
tatsächliche Event-Gestalt + Nudge-Wirksamkeit. Roh-Evidenz: `evidence/` (siehe unten).

---

## TL;DR — Empfehlung für den Bau

| Knopf | Empfehlung (konfigurierbar) | Begründung |
|---|---|---|
| **Detector-Trigger** | `error.ratelimit` mit **`detail.status ∈ {blocked, rejected}`** **UND** danach **Stille > T** | Zwei Filter übereinander (PO-Leitplanke). Das **primäre** `status`-Feld unterscheidet Throttle-Hang von Routine-Beat. |
| **NICHT triggern auf** | `status ∈ {allowed, allowed_warning}` · **`overageStatus`** (egal welcher Wert) | Routine-Spend-Beat. `overageStatus` ist eine **andere Achse** (Overage-Verfügbarkeit) — live `"rejected"` bei kerngesundem Agent. |
| **Stille T** | **60 s** nach dem Throttle-Marker | Schon an den Marker gekoppelt → ein langer legitimer Tool-Run erzeugt **keinen** Marker, kann also nicht fälschlich stallen. |
| **Backoff** | **30 s → 60 s → 120 s → 240 s (Cap)** | Dauerfeuer verschärft das Limit; erst 30 s warten, ob der transiente Throttle von selbst aufgeht. |
| **Versuchslimit N** | **4** (≈ 7,5 min Selbstheilung), dann `stall.escalated` → PO | Persistenz jenseits davon ist meist ein hartes 5-h-Limit → PO-Sache, nicht weiter stupsen. |
| **Nudge-Format** | minimale user-message auf stdin: **„Bitte mach weiter mit der laufenden Aufgabe."** | `"Mach weiter."` **live als wirksam belegt**. Gleicher stdin-Weg wie PO-Aufgaben, kein neuer Kanal. |
| **Recovery** | erstes `turn.*`/Token/`tool.*` nach Nudge → `stall.recovered` (info), Incident schließen | Ein offener Incident pro Agent (Idempotenz). |

---

## Q1 — Rate-Limit-Erkennung: welches Feld trennt „gedrosselt-hängend" von „Routine-Beat"?

**Antwort: das *primäre* `status`-Feld in `rate_limit_info`.** Es ist **strukturiert** (kein Text-Scraping nötig).

### Wire-Vokabular (aus der CLI-Binary, autoritativ)
`rate_limit_info.status` nimmt genau diese Werte an (Objekt-Literal-Zuweisungen `status:"…"` im Bundle
`claude` 2.1.193):

```
status:"allowed"          # Routine — Agent arbeitet normal
status:"allowed_warning"  # Routine — Quota wird knapp, Agent arbeitet weiter
status:"blocked"          # Throttle — Anfragen werden geblockt
status:"rejected"         # Throttle — Anfrage abgewiesen
```

→ **Throttle-Hang ⇔ `status ∈ {blocked, rejected}`.** `allowed`/`allowed_warning` = Routine.

### Live-Gestalt (echte Session, nicht synthetisch) — und zwei Stolperfallen
Echtes `rate_limit_info` aus dem Live-Run (gesunder Agent):

```json
{"status":"allowed","resetsAt":1782657000,"rateLimitType":"five_hour",
 "overageStatus":"rejected","overageDisabledReason":"org_level_disabled","isUsingOverage":false}
```

1. **Falle A — `overageStatus` ist NICHT der Throttle-Indikator.** Hier `overageStatus:"rejected"`
   bei **kerngesundem** Agent (Grund: `overageDisabledReason:"org_level_disabled"`, `isUsingOverage:false`).
   Wer auf `overageStatus` (oder ein generisches „enthält *status* == rejected") triggert,
   bekommt **100 % False-Positives**. **Nur das primäre `status` zählt.**
2. **Falle B — Feldnamen-Drift gegen den Test-Korpus/Projector.** Live sind die Felder **camelCase**
   (`resetsAt`, `rateLimitType`, `overageStatus`); die Felder `remaining`/`used_pct`/`reset_at`/`retry_after`
   aus der synthetischen Korpus-Fixture **tauchen live gar nicht auf**. Der `EventProjector`-Whitelist
   `RATE_LIMIT_KEYS = [status, remaining, reset_at, used_pct, retry_after]` wurde gegen den **synthetischen
   Korpus** gebaut, nicht gegen ein echtes Event → **Projector-Follow-up nötig** (s. u.). Wichtig: der
   **`status`-Key matcht** — der Detector-Trigger funktioniert mit dem heutigen Projector bereits.
3. **Kein server-seitiges `retry_after`** im echten Event → **der Warden besitzt die Backoff-Kurve selbst**,
   verlässt sich nicht auf einen Server-Hinweis. (`resetsAt` ist nur das Fenster-Reset, hier 5-h-Typ.)

### Zweites Sicherheitsnetz: Stille-Kopplung (PO-Leitplanke)
Selbst ein periodischer Beat mit `status:blocked` darf **nicht allein** auslösen: der Spec-Trigger ist
`rate_limit ∧ nachfolgende Stille > T`. Folgt auf einen Marker **weiter Aktivität** (`turn`/`tool`/Token),
ist der Agent nicht hängend → **kein `stall.suspected`**. Beide Filter sind nötig; das ist als
**Mutationsbeweis** im Detector (CYP-61) zu verankern: „Routine-Beat + danach Aktivität → kein Signal"
und „blocked + danach Aktivität → kein Signal".

---

## Q2 — Stille-Timer T + Backoff + N

Timer pro Agent, **armiert erst durch einen Throttle-Marker** (`status ∈ {blocked,rejected}`), Reset bei
jeder Aktivität. Defaults s. TL;DR-Tabelle. Kernargument: weil der Timer an den Marker gekoppelt ist,
kann ein **langer legitimer Tool-Run** (Build/Test, der zwischen `tool.call` und `tool.result` schweigt)
den Stall **nicht** auslösen — er erzeugt keinen Rate-Limit-Marker. Daher darf T kurz (60 s) sein, ohne
arbeitende Agenten zu stören. Backoff wächst (30→240 s), weil schnelles Dauerstupsen das Limit verschärft.
Nach N=4 erfolglosen Nudges → `stall.escalated` (error) an PO statt endlos zu stupsen.

Alle drei (T, Backoff-Kurve, N) gehören in die Config (`07` §7) — pro Agententyp justierbar.

---

## Q3 — Nudge-Wirksamkeit (LIVE belegt)

**Belegt:** Eine **echte** stream-json-Session, Abo-Creds. Ablauf (Konsole, gekürzt):

```
sent turn-1
result #1 (subtype=success, is_error=False)
turn-1 done; sleeping 5s to simulate idle, then NUDGE
sent NUDGE ('Mach weiter.')
result #2 (subtype=success, is_error=False)   ← Nudge erzeugt frischen Turn
event type counts: {system/init:2, rate_limit_event:1, assistant:5, result/success:2, user:1, ...}
```

Eine **„Mach weiter."**-user-message auf **stdin** nach Idle löst zuverlässig einen **neuen,
erfolgreichen Turn** aus — über **denselben stdin-Weg** wie PO-Aufgaben (kein neuer Kanal, kein MCP).
Das ist exakt der **Actuator-Pfad** des Wardens (`Actuator.nudge` → Mediator-Eingang).

**Ehrliche Scope-Grenze:** Live verifiziert ist (a) die **Mechanik** (stdin-Nudge → frischer Turn) und
(b) die **gesunde** `status:"allowed"`-Gestalt. **Nicht** live verifiziert ist, dass ein Nudge einen
real **`blocked`/`rejected`**-gedrosselten Agenten löst — das erforderte das **absichtliche Erschöpfen
des 5-h-Limits** des Menschen (verbrennt reale Quota, stört seine eigene Nutzung). Das ist eine
**bewusst aufgeschobene** Verifikation, die der Mensch out-of-band freigeben müsste; alternativ deckt der
Detector-Test (CYP-61) den Throttle-Fall über **injizierte `blocked`/`rejected`-Events** ab. Der
Throttle-`status`-String selbst ist autoritativ aus der Binary (oben), nicht geraten.

---

## Auswirkungen auf die Folge-Tickets

- **CYP-61 (Stall-Detector):** Trigger = `error.ratelimit ∧ detail.status ∈ {blocked,rejected}` **UND**
  Stille > T. Mutationsbeweise: Routine-Beat+Aktivität → kein Signal; blocked+Aktivität → kein Signal;
  blocked+Stille → genau ein Signal. **NICHT** auf `overageStatus` triggern.
- **CYP-63 (Stall-Policy):** Backoff 30/60/120/240, N=4, Recovery-Erkennung, ein Incident/Agent, Eskalation.
  Warden besitzt die Backoff-Kurve (kein server `retry_after`).
- **CYP-64 (Event-Typen):** `stall.suspected`(warn), `nudge.sent`(info), `stall.recovered`(info),
  `stall.escalated`(error). **Plus Projector-Fix:** `RATE_LIMIT_KEYS` an die echten camelCase-Felder
  angleichen (`status`, `resetsAt`, `rateLimitType`, ggf. `retryAfter`) — die synthetische Korpus-Fixture
  ist nicht repräsentativ. (Heute schadet es nicht, weil nur Zusatzfelder fehlen; `status` matcht.)
- **CLI-Pin-Drift:** Connector pint 2.1.193, installiert ist 2.1.195. Flag-Set unverändert wirksam im
  Live-Run; vor CYP-61-Merge Pin/Install abgleichen (kleines Betriebs-Follow-up).

## Evidenz
- `evidence/nudge-spike.console.log` — Live-Konsolenlauf (oben zitiert).
- `evidence/rate_limit_info.sample.json` — echtes `rate_limit_info` aus dem Live-Stream.
- Status-Enum: extrahiert aus `claude` 2.1.193 (`status:"…"`-Objekt-Literale). Keine Secrets in der Evidenz.
