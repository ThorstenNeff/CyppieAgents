# CYP-742 — Capability-Zustände am Agentenfenster (Design-Vorarbeit)

> Owner: UIUX-Designer · Auftrag PO 2026-07-19 (Fill/Vorarbeit, adjazent zu Devs Zustandslogik) ·
> Stand develop `1183b870` · **Design-Pass, kein Bau.**
> Anwendung von: `A11Y-ANNOUNCEMENTS.md` · §4a safe-but-silent · Pre-Read-Regel
> (`CYP-738-composer-writability-keys.md §4`).

---

## 0. Der Kern in drei Sätzen

`caps == null` trägt heute **zwei verschiedene Wahrheiten**: *„wir warten auf die Meldung"* und *„es gibt
nichts zu melden, weil der Agent nie lief"*. Beide rendern identisch (`○` + „Fähigkeiten noch nicht
gemeldet"). Und ein **dritter** Fall — `loading` — rendert **wie volle Fidelity** (beide: gar kein Badge),
was **nur** solange vertretbar ist, wie „loading" wirklich vorübergeht.

**Der Bug, den Dev gerade fixt, hebt genau diese Voraussetzung auf** (§3). Damit ist CYP-742 nicht nur
„Unknown gestalten", sondern: **eine bestehende, bewusst eingegangene Abwägung ist ungültig geworden.**

---

## 1. Die Zustände — heute vs. vorgeschlagen

**Heute** (`ConnectorCapabilityViews.kt:205-222`):

| Bedingung | Fläche |
|---|---|
| `loading` | **nichts** (`:206` — „a transient `○` on a full-fidelity agent is actively wrong") |
| `caps != null && !isDegraded` | **nichts** (Abwesenheit = „alles verfügbar") |
| `caps == null` | `○` + „Fähigkeiten noch nicht gemeldet", `onSurfaceVariant` (GATED) |
| degraded | `!` + „Eingeschränkt", `onSecondaryContainer` (EFFECT_DEFERRED) |

**Vorgeschlagen — fünf Zustände, klar getrennt:**

| # | Zustand | Ableitung (client-seitig, **ohne neuen BE-Feld**) | Badge | Ton |
|---|---|---|---|---|
| **A** | volle Fidelity | `caps != null && !isDegraded` | **kein Badge** | — |
| **B** | eingeschränkt | `caps != null && isDegraded` | `!` „Eingeschränkt" | `EFFECT_DEFERRED` |
| **C** | wird geprüft | `loading` | **kein Badge**, aber **befristet** (§3) | — |
| **D** | **unbekannt** | `caps == null` **&&** Lifecycle `RUNNING` | `○` „noch nicht gemeldet" | `GATED` |
| **E** | **nicht gestartet** | `caps == null` **&&** Lifecycle ≠ `RUNNING` | **kein Fidelity-Badge** (§2) | — |

⭐ **D vs E ist heute schon ableitbar** — aus `AgentLifecycleState`, das das Fenster ohnehin führt
(`AgentViewModel.kt:258,267`; Punkt im Titel via `statusDotSpec`, `AgentWindow.kt:721-730`).
**Kein Backend-Seam nötig**, kein neues Feld, keine Wartezeit auf CYP-720-artige Ketten.

> ⚠ **Ehrliche Einschränkung:** „nicht gestartet" ist **nicht** unterscheidbar von „lief mal, hat aber nie
> gemeldet" — beide sind `STOPPED` + `caps == null`. Deshalb sagt die Copy für **E** ausdrücklich **nicht**
> „lief noch nie", sondern nur, dass der Agent **nicht läuft** und Fähigkeiten **beim Start** gemeldet
> werden. Das ist in **beiden** Unterfällen wahr. (`AgentRunState = RUNNING|STOPPED|ERROR`,
> `core/…/CommModel.kt:35-39` — es gibt kein `NEVER_STARTED`, und ich erfinde keins.)

---

## 2. Warum E **kein** eigenes Glyph bekommt — und trotzdem pre-read unterscheidbar ist

Naheliegend wäre ein zweites Zeichen für E. **Das wäre hier falsch:** die verfügbaren ruhigen Glyphen sind
`·` (GATED) und `○` (heute für „unbekannt"). **`·` und `○` sind bei Badge-Größe praktisch nicht
unterscheidbar** — das erfüllt die Pre-Read-Regel nur auf dem Papier und fällt im Betrieb zusammen.

**Stattdessen trägt die Unterscheidung ein Element, das ohnehin auf derselben Fläche sitzt: der
Lifecycle-Punkt im Fenstertitel.** Bei E steht dort sichtbar „nicht laufend" — damit liest sich „kein
Fidelity-Badge" korrekt als *„keine Aussage, weil nichts läuft"*, nicht als *„alles verfügbar"*.

⭐ **Das ist kein Kunstgriff, sondern das im Haus bereits gelöste Muster.** `statusDotSpec`
(`AgentWindow.kt:721-730`) unterscheidet `UNKNOWN` von `STOPPED` **über die Form** (`RING` statt `FILL`),
und der Kommentar darüber schreibt die Doktrin wörtlich aus:

> *„…disc that collapses onto STOPPED's look; **„unknown" is a different axis, not weaker certainty**."*

Das ist **exakt** die Pre-Read-Regel und die unknown≠Abwesenheit-Disziplin — **schon angewandt, schon
gefixt** (`// ← the fix`). CYP-742 folgt diesem Präzedenzfall, statt ein zweites Vokabular aufzumachen.

**Pre-Read-Prüfung der fünf Zustände** (Frage: unterscheidbar **vor** dem Lesen?):

| Paar | Unterscheidungsträger | ok? |
|---|---|---|
| A vs B | Badge da/nicht da + `!` | ✓ |
| A vs D | Badge da/nicht da + `○` | ✓ |
| D vs E | `○`-Badge vs kein Badge, **plus** Lifecycle-Punkt (`FILL/PRIMARY` vs `OUTLINE`) | ✓ |
| **A vs E** | **nur** der Lifecycle-Punkt | ⚠ **schwach — s.u.** |
| **A vs C** | **nichts** | ⛔ **s. §3** |

⚠ **A vs E ist die dünnste Stelle** und ich benenne sie statt sie zu glätten: ein laufender Agent mit
voller Fidelity und ein gestoppter Agent ohne Meldung zeigen **beide kein Badge**; nur der Lifecycle-Punkt
trennt sie. Das halte ich für vertretbar, weil der Punkt **im selben Titel** sitzt und ein gestoppter Agent
ohnehin als gestoppt gelesen wird — **aber es ist eine Abwägung, keine saubere Trennung.** Wäre sie zu
dünn, ist die Antwort **nicht** ein neues Glyph, sondern die Detail-Fläche (§4, das Panel sagt es im
Klartext).

---

## 3. ⛔ Der eigentliche Befund: eine Abwägung, deren Voraussetzung entfällt

**CYP-280 hat bewusst entschieden, während `loading` gar nichts zu zeigen** (`ConnectorCapabilityViews.kt:206`):

> *„while the caps are still loading … show no fidelity claim at all — a transient `○` on a full-fidelity
> agent is actively wrong (worse than absence)."*

**Diese Entscheidung ist richtig — aber sie ist an ein Wort gebunden: `transient`.** Sie kauft
Ununterscheidbarkeit (C sieht aus wie A) gegen die Zusicherung, dass der Zustand **von selbst vergeht**.

**Der Bug, den Dev fixt (Caps-VM re-liest nicht), hebt genau diese Zusicherung auf.** Wenn der Ladezustand
**nicht** vergeht, gilt: *kein Badge* bedeutet dauerhaft entweder „alles verfügbar" **oder** „wir haben nie
nachgesehen" — und der Nutzer hat **keine Möglichkeit**, das zu unterscheiden. Aus einer vertretbaren
Momentaufnahme wird ein **dauerhafter Ununterscheidbarkeits-Zustand**, und zwar auf der Seite, die
**beruhigt** („alles verfügbar").

> **Deshalb ist Devs Fix nicht nur ein Bugfix, sondern die Bedingung, unter der die bestehende UX ehrlich
> bleibt.** Das gehört ins Ticket, nicht nur in den Code: Wer die Wiederholung des Reads später als
> „Optimierung" wegnimmt, nimmt der CYP-280-Abwägung ihre Grundlage.

**Zwei Absicherungen, damit das nicht wieder still passiert:**
1. **Befristung (Design):** bleibt `loading` länger als eine Schwelle bestehen, **fällt C nach D** —
   `○` „noch nicht gemeldet". Ehrlich, weil es dann **stimmt**: wir haben keine Meldung. Schwelle:
   Vorschlag **10 s**, ausdrücklich als **Messfrage** (§6), nicht geraten. Präzedenz für das Muster:
   `CLONE_SLOW_THRESHOLD_MS` (`FirstRunSteps.kt:60`).
2. **Zahn (§5-1):** ein Test, der beweist, dass ein hängender Ladezustand **nicht** dauerhaft wie volle
   Fidelity aussieht.

---

## 4. Copy

**Bestehende Keys bleiben** (`connector_fidelity_unknown`, `connector_fidelity_badge`, `connector_cap_*`) —
kein Umbenennen einer geteilten API.

| Key | DE | EN | Zustand |
|---|---|---|---|
| `connector_fidelity_unknown` *(bestehend)* | Fähigkeiten noch nicht gemeldet | Capabilities not yet reported | **D** |
| `connector_caps_not_running` *(NEU)* | Agent läuft nicht — Fähigkeiten werden beim Start gemeldet. | Agent isn't running — capabilities are reported when it starts. | **E** (Detail-Panel) |
| `a11y_connector_caps_not_running` *(NEU)* | Agent läuft nicht, Fähigkeiten noch nicht bekannt | Agent isn't running, capabilities not known yet | **E** |

**Warum E-Copy ins Detail-Panel und nicht als Badge:** der Badge-Platz ist knapp und E braucht keinen
Alarm — aber wer das Panel öffnet (der Fall, in dem die Frage tatsächlich aufkommt), bekommt **eine klare
Aussage statt einer leeren Liste.** Das schließt zugleich die dünne A-vs-E-Stelle aus §2: im Panel ist es
eindeutig.

⚠ **Formulierung bewusst ohne Auflösungs-Zusage mit Zeitbezug** — kein „gleich", kein „vorübergehend"
(die CYP-738/730-Klasse). „**beim Start**" ist ein **Ereignis**, kein Zeitversprechen: es tritt ein, wenn
der Nutzer startet — und wenn er nie startet, bleibt der Satz wahr.

**Ansage:** alle **`Polite`** — Anfangszustand einer geöffneten Fläche (`A11Y-ANNOUNCEMENTS.md §1`).

---

## 5. Zähne (je mit rot-Bedingung)

| # | Zahn | Mutation ⇒ MUSS rot |
|---|---|---|
| **1** | **Hängendes Laden ≠ volle Fidelity:** bleibt `loading` über der Schwelle, erscheint `○` (Zustand D) | Ladezustand bleibt dauerhaft badge-los (**der heutige Bug**) |
| **2** | **D vs E getrennt:** `caps == null` + `RUNNING` ⇒ `○`-Badge; `caps == null` + ≠`RUNNING` ⇒ **kein** `○`-Badge | beide Fälle rendern dasselbe |
| **3** | **E sagt es im Panel:** Panel bei `caps == null` + ≠`RUNNING` zeigt `connector_caps_not_running`, **keine leere Liste** | Panel zeigt Leere ohne Aussage |
| **4** | **Volle Fidelity bleibt badge-frei:** `caps != null && !isDegraded` ⇒ kein Badge | ein `○`/`!` erscheint auf voller Fidelity (CYP-280-Regression) |
| **5** | **Kein erfundenes „verfügbar":** `caps == null` ⇒ **nirgends** wird eine Dimension als `available` gerendert | null wird als „alles verfügbar" gerendert |

**Zahn 1 rötet gegen den heutigen Stand** — er ist der Regressionstest zu Devs Fix und hält die
CYP-280-Abwägung an ihre Voraussetzung gebunden.

---

## 6. Offen / Entscheide

1. **Schwelle für C→D:** Vorschlag 10 s, **zu messen** (wie lange dauert ein normaler Caps-Report?).
   **Messfrage an Dev/Backend**, keine Design-Frage — geraten wäre sie wertlos.
2. **A vs E-Dünne (§2):** akzeptiert mit dem Lifecycle-Punkt als Träger. Falls QA das in der Praxis als zu
   schwach meldet, ist die Antwort die **Detail-Fläche**, nicht ein zweites Glyph.
3. **Nicht in meiner Hand:** ob der Caps-Read nach dem Fix wiederholt wird und in welchem Intervall —
   Devs Zustandslogik. Ich beschreibe nur, **was die Fläche zeigen muss**, wenn er ausbleibt.

---

## 7. Self-Validation

- **Alle Zustände am Code abgeleitet, nicht angenommen:** Render-Logik `ConnectorCapabilityViews.kt:205-222`,
  Lifecycle `AgentViewModel.kt:258,267` + `AgentWindow.kt:721-730`, `AgentRunState`
  (`core/…/CommModel.kt:35-39`).
- **D vs E braucht KEINEN Backend-Seam** — ableitbar aus Daten, die das Fenster bereits führt. Vor dem
  Schreiben geprüft, statt eine BE-Nahtstelle zu fordern.
- **Kein neues Glyph erfunden:** `·` vs `○` wäre bei Badge-Größe nicht unterscheidbar → die Unterscheidung
  liegt bei einem Element, das ohnehin da ist. **Präzedenz im Haus zitiert** (`statusDotSpec`, „unknown is
  a different axis, not weaker certainty") — das Muster war bereits gelöst.
- **Die dünnste Stelle ist benannt statt geglättet** (A vs E, §2) — inkl. der Antwort, falls sie nicht
  trägt (Detail-Fläche, nicht Glyph-Inflation).
- **Der eigentliche Befund ist nicht der Unknown-State, sondern die entfallene Voraussetzung** einer
  bestehenden Abwägung (§3) — das war ohne das Lesen des CYP-280-Kommentars nicht sichtbar.
- **Keine erfundene Ursache:** „nie gestartet" wird **nicht** behauptet, weil `AgentRunState` es nicht
  hergibt; die Copy ist in beiden Unterfällen wahr.
- **Keine Auflösungs-Zusage mit Zeitbezug** (§4) — „beim Start" ist ein Ereignis, kein Versprechen.
- **Schwelle als Messfrage ausgewiesen**, nicht geraten (§6).
