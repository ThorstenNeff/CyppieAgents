# CYP-836 F-I4 — Native (Compose) Transient-State a11y-Parität (Design-Spec)

> Owner: UIUX · **Design-Spec, kein Bau** (Dev zieht den Render-Zahn → mein Gate). Prio LOW, ungated. Stand develop `9f8a4973`.
> Ziel: die native (Compose) transient-State-Anzeige soll a11y-seitig **dasselbe ansagen** wie die bereits gelieferte
> **web-Baseline** — für die vier transient-Feeds **busy · token · lifecycle · terminal**.
> **Doktrin-Quelle:** `docs/A11Y-ANNOUNCEMENTS.md` (Ansage-Dringlichkeit Polite/Assertive). Diese Spec ist eine
> **Anwendung** davon, kein neuer Grundsatz.

---

## 0. Der Befund (am Code geerdet)

| Fläche | web-Baseline (geliefert) | Compose (heute) |
|---|---|---|
| **lifecycle** | `LifecycleHeader.tsx:55` `role="status"` (= implizit `aria-live=polite`) + `aria-label="Status: {label}"`; ERROR `:116` **eigener** `role="alert"` (= assertive) | `AgentWindow.kt:878` `a11y_agent_status` als `contentDescription` — **KEIN `liveRegion`** |
| **terminal (mode)** | `ModeToggle.tsx:21` `role="radiogroup"` + `aria-busy={pending}`; Optionen `role="radio"` aria-checked | `AgentWindow.kt:367` `a11y_terminal_mode` viewDescription (`clearAndSetSemantics`) — **KEIN `liveRegion`** |
| **busy** | auf web in den lifecycle-Status-Dot (RUNNING) gefaltet; kein eigenes Live-Signal | `AgentWindow.kt:253/311` `busy`-Flag treibt Visuals — **KEIN `liveRegion`** |
| **token** | passives Label; **kein** `aria-live` (web sagt es NICHT live an) | Label — **KEIN `liveRegion`** |

**Kern-Lücke:** In `commonMain/agentview` existiert **kein einziges `liveRegion`** (bestätigt: die einzige Fundstelle
`AgentWindow.kt:1165` sagt explizit „NO liveRegion"). ⟹ ein Screenreader auf Compose **liest** die Zustände nur beim
Fokus, **kündigt** eine transiente Änderung **nie an**. web tut das (via `role=status`/`role=alert`). Das ist die zu
schließende Parität.

---

## 1. Die Doktrin, kurz (A11Y-ANNOUNCEMENTS.md)

- **Polite** = **Anfangs-/laufender Zustand einer Fläche, die der Nutzer betrachtet** (er schaut hin).
- **Assertive** = **Ergebnis einer abgeschickten/gestarteten Aktion** **oder** **unaufgefordert-Kritisches**, während
  der Blick woanders sein kann.
- Entscheidende Frage: **nicht „wie schwer?", sondern „schaut der Nutzer gerade hin?"**
- **§4:** braucht ein Zustand wirklich Assertive, kriegt er eine **eigene Fläche/Node** — nicht dieselbe Region lauter.

---

## 2. Soll-Announce pro Feed × Zustand (die Parität-Tabelle)

| Feed | Zustand | Soll-Announce (Compose) | Grund (Doktrin) | Node / Semantics |
|---|---|---|---|---|
| **lifecycle** | RUNNING / IDLE / STARTING / RESTARTING / STOPPED | **Polite** — Status-WORT bei Änderung | laufender Ambient-Zustand einer betrachteten Fläche; Parität web `role=status` | `liveRegion=Polite` auf dem Status-Node (`a11y_agent_status`) |
| **lifecycle** | **ERROR** | **Assertive** — **eigener** Node | unaufgefordert-kritisch, muss gehört werden auch bei abgewandtem Blick; Parität web `role=alert`; Doktrin §4 (eigene Fläche, nicht Region lauter) | separater ERROR-Node `liveRegion=Assertive` (trägt den kuratierten ERROR-Grund) |
| **terminal (mode)** | **bestätigter** Wechsel (Orchestrierung↔Terminal) | **Assertive** — Ergebnis-Ansage | **Ergebnis der vom Operator abgeschickten** (nicht-optimistischen, server-bestätigten) Umschaltung — er wartet darauf | Announce beim Server-Confirm-Flip (nicht optimistisch) |
| **terminal (mode)** | **swap_failed** | **Assertive** | Ergebnis/Fehlschlag einer abgeschickten Aktion | eigener/derselbe Ergebnis-Node, Assertive |
| **terminal (mode)** | **switching** (in-flight) | **kein** Live-Announce — `stateDescription` „wird umgeschaltet" | in-flight, kein Ergebnis; Parität web `aria-busy` | `stateDescription = terminal_mode_switching` (schon da), **kein** liveRegion-Puls |
| **terminal (mode)** | Ruhe-Anzeige (Fenster offen) | **kein** Announce — fokus-lesbar | Öffnen-Zustand, kein Ereignis | `a11y_terminal_mode` als Description (schon da) |
| **busy** | busy ↔ idle | **Polite** — **falls** nicht schon vom lifecycle-Status getragen (§3 De-Dup) | Ambient-Zustand einer betrachteten Fläche | siehe §3 — EIN Träger, nie doppelt |
| **token** | Zähler-Update | **KEIN liveRegion** (bewusst) — fokus-lesbares Label | hochfrequent-ambient; jede Ansage = **Lärm** („Lärm, wenn Aufmerksamkeit schon liegt", Doktrin §1/§5); Parität web (nicht live) | Label mit `contentDescription`, **explizit ohne** liveRegion |

---

## 3. Die load-bearing Urteile (nicht mechanisch aus der Regel ableitbar)

**a) Ambient Multi-Fenster-Status = Polite, NICHT Assertive — obwohl „unaufgefordert".**
Streng gelesen wäre jede Statusänderung in einem *nicht-fokussierten* Fenster „unaufgefordert → Assertive". Das wäre
bei N Agenten eine **Kakofonie** und verletzt „Lärm, wenn … Aufmerksamkeit schon liegt". Auflösung (= web-Wahl):
der Agent-Fenster-**Ambient-Status ist Polite**; **Assertive** bleibt reserviert für (i) **ERROR** (kritisch) und
(ii) **Ergebnisse der eigenen abgeschickten Aktionen** (mode-Umschaltung, ggf. lifecycle-Kontrollen). Das ist
doktrin-konsistent: die Assertive-Bestandsfälle sind alle Ergebnis-von-Eingabe oder Unaufgefordert-Kritisch
(`OverloadBanner`), **nie** ein ambienter Status-Tick.

**b) token ist bewusst STUMM.** Ein bei jedem Token-Tick feuerndes Live-Region wäre der Lehrbuch-Fall von Lärm. web
sagt es nicht an; Compose auch nicht. token bleibt fokus-lesbares Label. *(Der Render-Zahn assertet die **Abwesenheit**
eines liveRegion — sonst schleicht sich später ein „hilfreiches" Announce ein.)*

**c) ERROR kriegt einen EIGENEN Node (Doktrin §4).** Nicht dieselbe Status-Region von Polite auf Assertive flippen —
das änderte das Verhalten der nicht-ERROR-Ansagen mit. Ein separater Assertive-ERROR-Node (Parität web
`lifecycle-error role=alert`) trägt den kuratierten Grund; der Polite-Status-Node bleibt für die Nicht-ERROR-Zustände.

**d) busy/lifecycle-De-Dup.** busy (RUNNING) und der lifecycle-Status können **denselben** „läuft/bereit" ansagen.
**Ein Träger, nie zwei** (sonst doppelte Screenreader-Ausgabe). Empfehlung: der **lifecycle-Status-Node ist der eine
Announce-Träger** für running/idle; das separate `busy`-Flag treibt nur die **Visuals** (Titelbar-Punkt), **ohne**
eigenes liveRegion. Falls busy semantisch feiner ist als der lifecycle-Status (z. B. Tool-läuft-aber-Status-IDLE),
dann trägt busy den Polite-Announce und der Status-Node schweigt für diese Achse — Dev entscheidet am Code, aber
**genau EIN** Polite-Träger für „beschäftigt".

---

## 4. Semantics / testTags (Dev-Zahn)

- **lifecycle-Status:** `liveRegion = LiveRegionMode.Polite` auf dem Status-Node; testTag bestehend (`a11y_agent_status`-
  Träger). Announce = das Status-**WORT** (`agent_status_running`/`_stopped`/… — schon vorhanden), nie Farbe/Glyph allein.
- **lifecycle-ERROR:** separater Node `liveRegion = LiveRegionMode.Assertive`, eigener testTag (z. B.
  `agent.<id>.statusError`), present-iff-ERROR.
- **terminal-mode Ergebnis:** Assertive-Announce am non-optimistischen Confirm-Flip + bei `terminal_mode_swap_failed`;
  `switching` bleibt `stateDescription` ohne Puls.
- **busy:** **ein** Polite-Träger (§3d), kein zweites liveRegion.
- **token:** **kein** liveRegion (Zahn assertet Abwesenheit).

**Render-Zahn (was Dev testet):** je Feed/Zustand die spezifizierte `liveRegion` (Polite / Assertive / **keine**);
Mutation der liveRegion (Polite↔Assertive, oder auf token/switching hinzugefügt, oder ERROR→Polite) → **RED**. Das
pinnt die Ansage-Klasse maschinell, nicht nur als Prosa.

---

## 5. Cross-Ref / Nicht-Ziele
- **Cross-Surface:** matcht die web-Baseline (`role=status`/`role=alert`/`aria-busy`) auf Compose-Idiom (`liveRegion` +
  `stateDescription`). Semantik-Parität, nicht Markup-Parität.
- **Nicht in Scope:** die revoke-Demotion (CYP-819 A2, schon im Bau — `AgentWindow.kt:234-240` `statusRevoked`): dort
  demoten Busy/token/control auf „unknown", das deckende Banner ist Assertive. Dieses Ticket ist die **normale**
  transient-Ansage; die revoke-Kante ist separat und bereits spezifiziert.
- **Kein Bau:** reine Vorlage; Keys/Tags final am Bau, DE+EN zusammen landen (i18n-Parität).

---

## 6. Self-Validation
- **Befund am Code geerdet:** web-Baseline (`LifecycleHeader`/`ModeToggle` `role`/`aria-busy`) + Compose-Lücke
  (kein `liveRegion` in `agentview`, `:1165` explizit) real gelesen — file:line-belegt.
- **Doktrin angewandt, nicht neu erfunden:** jede Zeile begründet über `A11Y-ANNOUNCEMENTS.md` (Aufmerksamkeit, nicht
  Schwere); die drei nicht-mechanischen Urteile (ambient=Polite, token=stumm, ERROR-eigener-Node) explizit ausgewiesen.
- **token-Stille als Zahn** (Abwesenheit assertet) — kein Overclaim „mehr Ansage = besser".
- **De-Dup ehrlich** (§3d): ein Träger für „beschäftigt", nie doppelte Ausgabe.
- **Scope sauber:** nur die 4 benannten Feeds; revoke-Demotion (CYP-819) explizit ausgeklammert; STALE-Copy-Forward
  (5-State-Badge) NICHT hier (separat vermerkt, kein Skew heute).
- **Kein Bau** — Dev zieht den Render-Zahn, mein Gate am Bau.
