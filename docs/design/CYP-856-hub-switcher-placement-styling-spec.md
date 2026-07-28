# CYP-856 — Compose Hub-Switcher: Placement + Styling-Spec

> Owner: UIUX · **Placement/Styling-Spec** (Dev baut die **Struktur** design-unabhängig, Slice-1; ich liefere die
> **Optik** + die Achsen-/Honesty-Regeln, die die Optik binden). **Display-only, nichts armt.** Stand develop `145e04f4`.
> **Parität-Ziel:** der gemergte web-ts `HubSwitcher.tsx` (CYP-852). **Struktur-Mirror:** Compose `ProjectSwitcherBar.kt`
> (die in-workspace Top-Bar-Idiomatik). **Trust-Achse = REUSE CYP-808 `HubTrustBadge`/`hubTrustTone`** (gemergte Spec).
> **NICHT** die pre-workspace Connect-Liste — dies ist eine **neue in-workspace** Display-Fläche.

---

## 0. Was das ist (ein Satz)
Eine **in-workspace Top-Bar**, die die N bekannten Hubs als **Liste** zeigt, jeder Eintrag mit **vier nie-vermengten
Achsen** (name · reachability · hub-key-trust · freshness), **non-optimistischer** Aktiv-Marker — **reine Anzeige**,
der echte Switch-Effekt liegt hinter einer injizierten Naht (armt später, M3).

---

## 1. Placement (in-workspace Top-Bar, analog `ProjectSwitcherBar`)
- **Ort:** in der **Workspace-Chrome oben, ÜBER dem `WindowHost`** — dieselbe Ebene wie `ProjectSwitcherBar` (die
  Top-Level-Navigation über den Fenstern). Eine **eigene Bar** (`hubSwitcher.bar`), kein Umbau der Projekt-Bar.
- **Stack-Ordnung (Empfehlung, Dev bestätigt):** Hub-Switcher **neben/unter** dem Projekt-Switcher (beide sind
  Top-Level-Kontext-Selektoren: Projekt = *was*, Hub = *wo*). Full-width `Column`/`Row`, Padding wie die Projekt-Bar
  (`horizontal = 12.dp, vertical = 6.dp`), testTag `hubSwitcher.bar`.
- **Nicht** pre-workspace / nicht die Connect-Liste (das ist die M1-Connect-Fläche; hier sind wir **im** Workspace, der
  Hub ist schon aktiv, man wechselt zwischen bekannten).

---

## 2. Die vier Achsen pro Eintrag — nie vermengt (der Honesty-Kern)

Jeder Hub-Eintrag ist eine **selektierbare Row** (Compose `selectable`/Button-Semantik), Aktiv-Marker **non-colour**
(analog Projekt-Bar `●`, nie Farb-only-Highlight). **Jede Achse = ein EIGENER Node/testTag; colour-never-sole (WCAG
1.4.1); nie zwei Achsen in ein Signal falten.**

| # | Achse | Optik (Compose, maritim/M3) | testTag | Honesty |
|---|---|---|---|---|
| 1 | **name / Identität** | Primär-Text `onSurface`, `bodyMedium`/`titleSmall` | `hubSwitcher.name.<hubId>` | die stabile Identität (hubId = key) |
| 2 | **reachability** | **NEUTRALER** Online/Offline-Marker: **Form** (gefüllt `●` = online / hohl `○` = offline) **+ Wort** „Online"/„Offline", **beide `onSurfaceVariant`** | `hubSwitcher.reach.<hubId>` (+ stateDescription) | ⚠ **reachability ≠ trust; „offline ≠ untrusted"** — **NIE grün** für online (grün-de-overload + falsches „gut/vertraut"), **NIE error-rot** für offline (offline ist nicht „kaputt/untrusted"). Ein neutraler Netz-Fakt. |
| 3 | **freshness (lastSeen)** | „zuletzt gesehen HH:MM", `onSurfaceVariant`, `labelSmall` — klar **advisory** | `hubSwitcher.lastSeen.<hubId>` | rein informativ, keine Garantie; eigene Achse ≠ reachability (online-jetzt ≠ zuletzt-gesehen) |
| 4 | **hub-key-trust (Achse a)** | **REUSE `HubTrustBadge`** (CYP-808), gefüttert **UNKNOWN** (pre-arming) → neutrale `◯`-Pille „Vertrauen nicht geprüft" | die badge-eigenen Tags `hubTrust.<hubId>.<state>` | **UNKNOWN pre-arming:** der Switcher verdrahtet das Badge **NIE** an eine Live-Trust-Entscheidung; echter beobachteter Trust kommt an der Arming-Naht (M3). Fail-closed, never-green-by-default. |

**Bewusst ABSENT (nie ein Switcher-Badge):**
- **axis-c `issuerTrust`** — ein **ganzes Zone-2-Connect-Verdikt** (M4-Failure-Region). Ein axis-c-Feld über zwei Zonen
  zu splitten wäre inkohärent → **nie** im Switcher.
- **tier** — Sache des **Active-Headers**, nicht per-Eintrag.

---

## 3. Verhalten (non-optimistisch, Parität `ProjectSwitcher`/web-ts)
- **Aktiv-Marker folgt dem server-bestätigten `activeHubId`** (Prop), **nie dem Klick** (non-optimistisch) — analog
  Projekt-Bar + web `aria-current`. Compose: `selected`-Semantik am aktiven Eintrag.
- **Pending-Switch disabled die ganze Liste** (kein in-flight re-point); der **aktive Hub ist kein Switch-Target**
  (switch-to-active = no-op, kein Phantom-Eintrag).
- **Der echte Switch-Effekt** (Teardown/Setup des neuen aktiven Hubs, CYP-755 §3) liegt hinter einer **injizierten
  `onSwitch`-Naht** — **hier NICHT gebaut** (Display-only, nichts armt).

---

## 4. Drei distinkte Store-Ausgänge (Empty ≠ Load-Error ≠ Unknown)
Parität web-ts — nie ein irreführendes „keine Hubs" bei einem Fehler:
- **loadError** → `LoadErrorRetry` (`hubSwitcher.error`), **nie** ein leeres „keine Hubs".
- **!loaded** (noch nicht geladen) → **nichts rendern** (kein confident-empty).
- **empty** (0 Hubs, erfolgreich) → ehrliches „Keine Hubs" (`hubSwitcher.empty`).

---

## 5. Styling-Detail (maritim/M3)
- **Bar:** `Column`/`Row` full-width, Padding `12.dp`/`6.dp`, testTag `hubSwitcher.bar`; Idiomatik wie
  `ProjectSwitcherBar` (Top-Level-Nav über `WindowHost`).
- **Eintrag:** eine `Row` je Hub, `selectable`; horizontales `Arrangement.spacedBy(…)` zwischen den vier Achsen-Nodes;
  Aktiv = **non-colour** Marker (Reuse `●`-Idiom / `selected`), nie Farb-only.
- **reachability-Marker:** Form (`●`/`○`) + Wort, `onSurfaceVariant` — **kein** severity-Ton (nicht WARN/ERROR/grün);
  reachability ist keine Severität.
- **lastSeen:** `onSurfaceVariant`, `labelSmall`.
- **Trust-Badge:** unverändert aus CYP-808 (`hubTrustTone`), UNKNOWN = neutral `onSurfaceVariant` `◯`.
- **Enge Bar:** wenn nötig, dieselbe responsive Idiomatik wie die Projekt-Bar (Achsen dürfen unter ~schmaler Breite
  umbrechen/kürzen — `lastSeen` ist der erste Kürzungs-Kandidat, `name`+`reach` bleiben; `TextOverflow.Ellipsis` am
  `name`). Kein neuer Breakpoint erfinden.

---

## 6. a11y
- **Bar:** `contentDescription` „Aktiven Hub wechseln" (Parität web `aria-label`); Container-Nav-Semantik.
- **Aktiver Eintrag:** `selected = true` (Parität `aria-current`) — **non-optimistisch** (folgt `activeHubId`).
- **reachability:** `stateDescription` „Online"/„Offline" (Wort trägt, nie Marker-Form allein).
- **Trust-Badge:** trägt seine eigene a11y (CYP-808, `role=status`/Polite, „Vertrauen nicht geprüft" bei UNKNOWN).
- **Jede Achse fokus-/lesbar getrennt** — nie zu einem Satz verschmolzen, der zwei Achsen konflatiert.

---

## 7. Abhängigkeit / Naht
- **CYP-808 `HubTrustBadge`/`hubTrustTone`** (Spec gemergt; Composable-Bau evtl. noch ausstehend) ist die **Trust-Achse**.
  Landet der Switcher vor dem Badge-Bau, baut Dev das Badge (per CYP-808-Spec) mit / stubt es auf UNKNOWN — die Optik
  hier setzt das neutrale UNKNOWN-Rendering voraus.
- **Arming-Naht (M3):** die injizierte `onSwitch` + der spätere Live-Trust-Feed sind **nicht** diese Fläche.

---

## 8. Self-Validation
- **Parität am Code geerdet:** web-ts `HubSwitcher.tsx` (CYP-852) real gelesen — vier Achsen/testids, non-optimistisch,
  UNKNOWN-pre-arming, drei Store-Ausgänge, axis-c/tier absent — 1:1 auf Compose-Idiom übertragen (nicht Markup, sondern
  Struktur/Optik). Struktur-Mirror `ProjectSwitcherBar.kt` real gelesen (Placement/Padding/non-colour-Marker/`●`).
- **Vier-Achsen-Nie-Vermengt als Kern:** jede Achse eigener Node/testTag; **reachability strikt neutral** (der
  load-bearing Honesty-Punkt: offline ≠ untrusted; nie grün/rot) — sonst liest ein Netz-Fakt als Trust-/Health-Verdikt.
- **axis-c/tier bewusst absent** mit Grund (Zone-2-Connect-Verdikt; Split inkohärent) — dokumentiert, nicht vergessen.
- **UNKNOWN pre-arming ehrlich:** der Switcher behauptet keinen Trust, den er nicht evaluiert hat (fail-closed,
  never-green-by-default) — die Badge-Reuse trägt genau das.
- **Non-optimistisch + display-only:** Aktiv folgt Server nicht Klick; der Switch-Effekt liegt hinter der Naht — nichts
  armt hier.
- **Reuse vor Neu:** Badge (CYP-808), Bar-Idiom (ProjectSwitcherBar), LoadErrorRetry, non-optimistic-Muster — kein
  divergenter One-off.
- **Kein Bau** — Optik-Vorlage; Dev baut Struktur (Slice-1) + zieht die Optik dagegen; mein Gate am Bau.
