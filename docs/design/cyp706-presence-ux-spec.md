# CYP-706 — Menschen-Präsenz (web-ts) · UX-Spec (DESIGN-AHEAD)

**Ticket:** CYP-706 (Epic CYP-640 „Discord-Feel", Achse 3) · **Für:** PO/PL (Produktentscheidungen) + Dev5/Backend2 (später) · **Von:** UIUX2 (Team-2)
**Baseline:** develop `8d0db7f9` (am Objekt gemessen) · **Stand:** 2026-07-18 · **Modus:** design-ahead, während CYP-705 fertig wird.
**Tooling-Grenze:** Honesty-Regeln unten sind headless render-test-**messbar**; Runtime-„Feel"/Pixel = guided-human. Diese Spec ist **noch nicht build-fertig** — sie fixiert die Ehrlichkeits-Gerüste + die offenen Produktfragen, damit der spätere Bau nicht frei ist.

---

## §0 Ehrlichkeits-Haken (VORNE — bindend; reuse des in-repo „advisory"-Registers)
Präsenz ist ein **weiches, beobachtetes** Signal, kein hartes. Drei Regeln, alle mit Code-Präzedenz im Repo:
- **Advisory, nie Garantie.** „Hier/aktiv/zuletzt gesehen" ist eine **Beobachtung**, nie eine Zusage von **Zustellung, Antwort oder Aufmerksamkeit**. (Register wie HubDiscovery `online/lastSeen` = *„advisory (H1)"* und FidelityBadge = *„ADVISORY, content-free, colour never the sole carrier"*.)
- **Nie success-green „active now".** Code-Präzedenz `agentSettingsModel.ts:16` (restart-deferred → amber effect-hint, *„never a success-green ‚active now'"*). Präsenz ist neutral/informativ, kein triumphales Grün.
- **Absence = unknown, nicht „niemand da"** ([[absence-reads-as-all-clear]]; agentSettings failed≠remote-Regel: *„an unresolved/failed load renders NOTHING — never claim from an unknown"*). Kein Präsenz-Feed / nicht bestimmbar ⇒ **sichtbarer neutraler unknown**, nie ein stiller „leerer Raum".
- **Drei Register (wie CYP-705):** **present** (advisory) · **away/last-seen** (advisory, Alter ehrlich) · **unknown** (kein Feed → neutraler sichtbarer Marker, **nicht** „abwesend"). unknown ≠ away ≠ here.

## §1 Reconcile, nicht kollabieren — ZWEI Achsen (die load-bearing Unterscheidung)
| Achse | Was | Quelle (heute) |
|---|---|---|
| **Agent-Prozess-Liveness** (existiert) | „läuft/arbeitet der **Agent-Prozess**" | `AgentRunStateEvent` (run-state, CYP-431) + `AgentBusyStateEvent` (busy, CYP-641) |
| **Menschen-Präsenz** (NEU, = CYP-706) | „ist ein **Mensch** hier / schaut zu" | **kein Feed** (s. §2) |
- **Verboten:** Liveness als Präsenz **re-labeln**. Ein **laufender** Agent ≠ ein **anwesender** Mensch; ein **busy** Agent ≠ „eine Person tippt". Distinkte Signale, Copy, testids — nie denselben Dot für beides. (Das ist der Kern-Honesty-Punkt von CYP-706, mein AC-Haken aus der Epic-Zusage.)

## §2 Wire-Wahrheit + Backend-Contract (design-ahead, wie CYP-705)
- **Kein Menschen-Präsenz-Feed heute** (verifiziert `8d0db7f9`). Der Server hält `connectorSessions` (aktive Connector-/Agent-Sessions), exponiert aber **kein** Präsenz-/„wer ist verbunden"-Feld für die Comm-UI. Die natürliche Quelle wäre **/ws/comm verbundene Prinzipale** — braucht eine **Backend-Fläche** (reconcile mit Backend2, genau wie 705s read-state).
- **Agent-Liveness-Feeds existieren** (run-state/busy) — **andere Achse** (§1), **nicht** als Präsenz umwidmen.
- **HubDiscovery `online/lastSeen`** ist Präsenz-*nah*, aber für **Remote-Hubs** (Reachability, „no presence feed added", advisory H1) — als **Register-Vorbild** reusen, nicht als Menschen-Präsenz-Quelle.

## §2.1 Identitäts-Constraint (content-free `AuthMe` — dieselbe Wand wie CYP-704-NOTIFY)
Der Client kennt sein/das Menschen-`identityId` **nicht** (`AuthMe` = `{authenticated, role?, verified}`). Deshalb kann Präsenz die Person **nicht benennen** ohne dieselbe Selbst-Identitäts-/Security-Entscheidung, die NOTIFY pausierte. Optionen (grob → benannt):
- **Rolle** („ein Operator ist hier") · **Count** („N schauen zu") · **server-Prinzipal-keyed, grob angezeigt** (wie 705: Server kennt den Prinzipal, Anzeige bleibt grob) — **sidesteps** den identityId-Block. · **benannt** — braucht die PL-Identitäts-Entscheidung (gekoppelt an CYP-704-NOTIFY).
- **Empfehlung MVP:** Rolle/Count (oder server-keyed-grob), **benannt später** mit der NOTIFY-Identitätsentscheidung.

## §3 Offene Produktfragen (design-ahead — für PO/PL, NICHT raten)
- **(a) Wessen Präsenz?** nur Menschen, oder Menschen+Agenten? *(Agenten-„Präsenz" = Liveness = andere Achse → Empfehlung: **nur Menschen**; Liveness bleibt getrennt.)*
- **(b) Granularität?** benannt / Rolle / Count? *(content-free AuthMe → benannt = Identitäts-Entscheidung wie 704-NOTIFY; Empfehlung MVP: **Rolle/Count**.)*
- **(c) Skope?** per-Kanal (wer ist in DIESEM Kanal) oder global (wer ist in der App)? *(Empfehlung: global fürs MVP-„wer ist da"; per-Kanal später.)*
- **(d) „Zuletzt gesehen"-Zeitstempel?** *(advisory + staleness — Empfehlung: nur mit **ehrlicher Alterung** („vor 5 min"), nie „here now" impliziert; oder ganz weglassen fürs MVP.)*

## §4 Layout & Zustände (parametrisch — konkretisiert nach §3-Entscheidung)
- **Präsenz-Indikator**: Dot **+ Text/aria** (colour-never-sole, WCAG 1.4.1), reuse FidelityBadge-Idiom (advisory, content-free). Zustände:
  - **present** → neutraler (NICHT grüner) „hier"-Marker + aria.
  - **away / last-seen** → advisory, mit **sichtbarem Alter** („vor 5 min"), nie „jetzt hier".
  - **unknown** (kein Feed / nicht bestimmbar) → **sichtbarer neutraler** Marker, **nicht** „abwesend"/leer (die 705-Drei-Zustands-Lehre: unknown sichtbar ≠ Stille).
- **Nie**: green „active now" · „niemand da" aus unknown · derselbe Marker wie Agent-Liveness.

## §5 Honesty-Invarianten (die Zähne, wenn gebaut wird)
1. **advisory nie Garantie** — keine Copy/kein Marker impliziert Zustellung/Antwort/Aufmerksamkeit.
2. **nie green „active now"** (Code-Präzedenz).
3. **unknown ≠ away ≠ here** — drei distinkte, sichtbare Renders; unknown nie als Abwesenheit ([[absence-reads-as-all-clear]]).
4. **liveness ≠ presence** — Agent-run-state/busy und Menschen-Präsenz haben **distinkte** Marker/Copy/testids; ein Test muss sie **unterscheiden** (Mutation: Präsenz aus run-state ableiten → RED).
5. **staleness ehrlich** — „zuletzt gesehen" zeigt **Alter**, impliziert nie „jetzt hier".

## §6 Backend2-Reconcile-Contract (was ein Präsenz-Feed bräuchte — später, wie 705)
Ein **advisory** Präsenz-Signal pro Prinzipal (connected / last-active-ts), **server-Prinzipal-keyed** (kein Client-`identityId`), **grob angezeigt** (§2.1). Live via WS (eigener Event, nicht in Comm/704 falten — [[reconcile-not-collapse-distinct-states]]). **Kein Feed ⇒ unknown** (sichtbar), nie „niemand da". Wie 705: **blockiert auf eine Backend-Fläche** + die §3-Produktentscheidungen.

## §7 Parität & Reuse
- **HubDiscovery `online/lastSeen`** — advisory (H1) Register-Vorbild.
- **FidelityBadge** — advisory, content-free, colour-never-sole Marker-Idiom.
- **agentSettings** — „never green ‚active now'" + failed≠unknown-Regel.
- **CYP-705** — Drei-Zustands-unknown-**sichtbar**, present-only-mit-sichtbarem-unknown, non-optimistisch.
- **CMP CYP-55 B1** (`commFocused`/`markCommFocused`) — lokaler Fokus, **nicht** Cross-Teilnehmer-Präsenz; nicht als Präsenz umwidmen.

## §8 Übergabe-Flags an den Koordinator
- **Design-ahead:** **noch nicht build-fertig.** Blockiert auf **(i) PO/PL-Produktentscheidungen §3 (a)-(d)** und **(ii) eine Backend-Präsenz-Fläche** (kein Feed heute).
- **Load-bearing Honesty (jetzt schon fix):** **liveness ≠ presence** (Zwei-Achsen-Trennung), **advisory nie Garantie**, **nie green active-now**, **unknown sichtbar ≠ „niemand da"**.
- **Identitäts-Kopplung:** benannte Präsenz teilt die pausierte CYP-704-NOTIFY-Identitätsentscheidung; grob (Rolle/Count/server-keyed) ist **entkoppelt** baubar — wie 705.
- **Shared Keys / Backend-Contract** landen mit der Impl, nicht vorab.
