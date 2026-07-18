# BYOA-M1 — Abnahme-Kriterien (Entwurf, Tester/QA)

> Epic **CYP-687** (BYOA-M1) · Route-Story **CYP-688** (M1.7, po2-owned; Round-Trip-Acceptance = **Tester2**).
> Erstellt 2026-07-18 vom Tester (Team 1) als **Kriterien-Vorarbeit**, ungegated — PO stimmt mit Backend/po2 ab.
> Alle Observables sind **am Code verifiziert** (Datei:Zeile), nicht angenommen.

## Grundlage (code-verifiziert)

| Fakt | Beleg |
|---|---|
| `/ws/hub` = Hub-Wire-Protokoll v1 für remote/BYOA-Connectoren, **auth-first** | `HubWireRoutes.kt:74`, `:77-81` |
| Identität = **das Token**, nie ein Frame-Feld; `from`/`projectId` **server-gestempelt** | `:39-41`, `:159+` (`hub.postAsAgent`) |
| `WireSend` → einziger Schreibpfad, `canWrite` erzwungen, Ack trägt **hub-vergebene Message-ID** | `:176-181` (`WireAck("sent ${posted.id}")`) |
| `WireDeliver` = Hub→Agent-Push, nur möglich wenn Session am Handshake registriert | `:145-151` (`connectorSessions.register`) |
| `WireEvent` → Event-Log mit **server-gestempeltem `source=remote`** | `:218-220` (`WireEventIngest.toDraft`) |
| Caps werden auf die **REMOTE-Decke geklemmt** (strukturell, nie frame-abgeleitet) | `:131-136` |
| **Ein Remote-Agent ist beim Boot bereits registriert (STOPPED) und wartet auf `/ws/hub`** | `BootOrchestrator.kt:885-890`, `PlatformConfig.kt:187` |

---

## 1) M1.1 — „Agenten kommen hoch" (Abnahme am laufenden, `.deb`-installierten Hub)

### Was **NICHT** als Beweis zählt (die Fallen, benannt)

- ❌ `systemctl is-active` / „Prozess existiert" — beweist, dass die Unit startete, nichts über Funktion.
- ❌ `GET /api/health` = ok — Liveness. (Epic CYP-687 sagt es selbst: *„health 200 beweist läuft, nicht funktionsfähig"*.)
- ❌ **Agent erscheint in `GET /api/agents`** — ⚠️ **die schärfste Falle**: das ist **Konfiguration, nicht Verbindung**.
  `BootOrchestrator.kt:885-890` registriert einen Remote-Agenten beim Boot in Topologie/ACL/Lifecycle als **STOPPED**
  und loggt *„awaiting /ws/hub connection"*. Der Eintrag ist **grün, bevor der Agent je verbunden war.**

### Abnahme = 5 gemessene Schritte am laufenden Hub

| # | Schritt | Gemessenes Observable |
|---|---|---|
| **A** | **Baseline (Nicht-Vakuositäts-Anker)** — vor dem Connect | Agent im Roster, **keine** Wire-Session, **keine** `source=remote`-Events. Macht ein späteres Grün überhaupt aussagekräftig (kein Vorzustand). |
| **B** | **Auth-gebundener Connect** — Agent verbindet `/ws/hub` mit **seinem eigenen** Agent-Token (`POST /api/agents {remote:true}`), sendet `WireHello` | `WireAck("hello")` empfangen. Token→`agentId` wird **vor jedem Frame** aufgelöst (`:77-81`). |
| **C** | **Agent→Hub (Richtung 1)**, fälschungsresistent — `WireSend{channel, text:<NONCE>}` | `WireAck("sent <messageId>")` (**hub-vergebene** ID) **und** `GET /api/channels/{id}/messages` enthält **genau diese ID** mit **server-gestempeltem `from=<agentId>`** + korrektem `projectId`. Nicht bloß „eine Nachricht ist da". |
| **D** | **Hub→Agent (Richtung 2)** — jemand postet in einen Kanal, den der Agent lesen darf | Der verbundene Agent empfängt **`WireDeliver`** mit demselben NONCE. Geht nur, wenn die Session am Handshake registriert wurde (`:145-151`). |
| **E** | **Remote-Nachweis (der Diskriminator)** | `GET /api/events` zeigt Einträge dieses Agenten mit **`source=remote`** (`:218-220`). **Ohne E könnte ein lokal gespawnter Agent A–D erfüllen** — E trennt „remote über den Draht" von „lokaler Prozess". |

### Fail-closed-Kontrollen (damit das Kriterium **scheitern kann**)

Ohne diese ist ein Grün nicht unterscheidbar von „alles wird akzeptiert":

1. **Operator-Token oder unbekanntes Token** auf `/ws/hub` → close **1008 `unauthorized`** (`:77-81`) — nur Agenten emittieren.
2. **`WireSend` vor `WireHello`** → `WireError(PROTOCOL)` + close (`:154-157`).
3. **`WireHello` mit erhöhten/LOCAL-Caps** → auf **REMOTE-Decke geklemmt** → Registry zeigt **DEGRADED, nie ENABLED** (`:131-136`) — Vertrauen ist strukturell, nicht frame-abgeleitet.
4. **`WireSend` in einen Kanal ohne `canWrite`** → uniformes `WireError(FORBIDDEN)` (kein „kein solcher Kanal"-Leak).

### M1.1-spezifisch (weil M1.1 = das `.deb`)

- Alles oben läuft gegen den **`.deb`-installierten, systemd-verwalteten Hub auf einer ECHTEN Ubuntu-26-Box** (nicht Build-Host).
- **★ Restart-Wiederholung:** nach `systemctl restart` C+D **erneut** grün. Das ist zugleich der **CYP-172-Wächter** (no-spawn-Marker-Persistenz) — das Epic flaggt, dass der Marker sonst *„keinen Restart überlebt"*.

---

## 2) M1.7 / CYP-688 — „Koordination über den Hub, nicht Discord"

Abnahme laut Ticket: ein **vollständiger Koordinations-Round-Trip ohne Discord-Hop**, beide Richtungen, ACL durchgesetzt.

### Positiv (der Round-Trip, korreliert)

Ein **eindeutiger NONCE** trägt durch den ganzen Round-Trip — sonst ist nicht unterscheidbar, *welcher* Austausch gemessen wurde:

1. **PO2 dispatcht** eine Aufgabe in Kanal `po2-backend2` mit NONCE → Hub-Record mit **server-gestempeltem `from=po2`**.
2. **Backend2 empfängt über den Hub** — `WireDeliver` an Backend2s Wire-Session (bzw. `WireSubscribe`-Read), NONCE enthalten.
3. **Backend2 antwortet** per `WireSend` → `WireAck("sent <id2>")`.
4. **PO2 empfängt den Status über den Hub** — `WireDeliver`/Subscribe, NONCE enthalten.
5. `GET /api/channels/po2-backend2/messages` enthält **beide** Nachrichten, geordnet, mit `from=po2` bzw. `from=backend2` (**server-gestempelt**), beide mit NONCE, im Zeitfenster.
6. `GET /api/events` zeigt **`source=remote`**-Einträge für Backend2 im Fenster (beweist: der Wire-Agent, nicht ein lokaler Prozess).

### ACL (Ticket-Kriterium — **entvakuosiert**)

7. Ein **Nicht-Member** (dritter Agent-Token, nicht in `po2-backend2`) liest/subscribed `po2-backend2` → **empfängt die Nachrichten nicht**.
   ⚠️ **Vakuositäts-Warnung:** „Nicht-Member sieht nichts" ist eine **Verweigerungs-Behauptung** — grün auch bei leerem Kanal oder kaputtem Lesepfad.
   **Nicht-vakuose Form braucht einen positiven Downstream:** *derselbe* Nicht-Member muss **im selben Lauf, mit demselben Token, in derselben Aufrufform** einen Kanal erfolgreich lesen, in dem er **Member ist**. Erst dann misst „sieht nichts auf po2-backend2" die **ACL** — und nicht einen toten Lesepfad.

### Negativ („nicht Discord") — hier liegt die eigentliche Falle

8. ⚠️ **„Keine Nachricht in Discord" allein ist eine ABWESENHEITS-Behauptung und damit vakuos.**
   Grün auch, wenn gar nichts passiert ist oder das Discord-Instrument schlicht nicht gelesen wurde. Nicht-vakuos braucht:
   - **(a) Korrelation:** der **NONCE** steht im Hub-Record und **nirgends** im Discord-Kanal-Transkript desselben Fensters. (NONCE, nicht „eine Status-Nachricht" — sonst ist der Austausch nicht identifizierbar.)
   - **(b) Instrument-lebt-Kontrolle:** das Discord-Transkript des Fensters muss **irgendetwas** zeigen → beweist, dass die Abwesenheit eine echte Abwesenheit ist und kein totes/ungelesenes Instrument.
     **Hier natürlich erfüllbar, gerade weil Discord NICHT entfernt wird** (SCOPE OUT: die anderen 5 Spokes bleiben auf Discord, Strecken laufen parallel).
   - **(c) Strukturell (stärkste Form, falls erreichbar):** für diese Spoke existiert kein Discord-Egress → „nicht Discord" gilt **by construction**. Wegen der bewusst **parallelen/umkehrbaren** Strecken vermutlich **nicht** gegeben ⟹ **(a)+(b) sind das Arbeitspaar.**

### ★ Zusatz-Falle aus dem Parallelbetrieb: der Doppel-Send

Weil die Strecken **parallel** laufen (Discord bleibt bestehen), ist der realistische Fehlerfall **nicht** „nichts fließt", sondern:
**der Agent postet aus Gewohnheit in BEIDE** — Hub *und* Discord. Ein naives „es steht im Hub" wäre dann **grün, während Discord weiterhin die echte Arbeit trägt** (kosmetische Ablösung).

⟹ Das Kriterium muss **exakt-einmal auf dem Hub-Pfad UND null auf dem Discord-Pfad** für den NONCE fordern — nicht „mindestens einmal im Hub".

---

## 2b) Konkreter Prüfschritt für (a)+(b) — „NONCE nicht in Discord" + „Instrument lebt"

> Der wackligste Teil der ganzen Abnahme, weil es eine **Abwesenheits-Behauptung** ist. Die Schärfung:
> **statt passiv „das Transkript zeigt irgendetwas" ein GEPFLANZTES, form-gleiches Kontroll-Signal.**
> Damit wird aus einer passiven Abwesenheit eine **aktive Diskriminierung**: derselbe Leser, derselbe Kanal,
> dasselbe Fenster, dieselbe Aufrufform — findet das eine, findet das andere nicht.

### Zwei NONCEs

| Symbol | Wo | Zweck |
|---|---|---|
| **`NONCE_H`** | trägt durch den **Hub**-Round-Trip (Task + Status) | die Sache selbst — darf in Discord **nie** auftauchen |
| **`NONCE_D`** | wird **absichtlich in DENSELBEN Discord-Kanal gepflanzt** | **Instrument-lebt-Kontrolle** — MUSS gefunden werden |

**Format (beide):** `CYP688-<uuid4>` — hohe Entropie (keine natürliche Kollision), rein alphanumerisch + Bindestrich
(überlebt Markdown/Transport-Formatierung unverändert), **literal** gesucht, nicht als Teilstring-Heuristik.

### Ablauf (Tester2, mechanisch — kein Augenmaß)

1. **`T0`** notieren. **`NONCE_D` in den po2↔backend2-Discord-Kanal posten** — ⭐ **form-gleich**: von **po2 oder backend2
   über deren normalen Discord-Antwortpfad**, nicht von einem Menschen. (Ein menschlicher Post beweist nur, dass der Kanal
   lesbar ist — **nicht**, dass eine *agenten-gepostete Koordinationsnachricht* dort erscheinen würde. Genau das ist die
   behauptete Abwesenheit.) Text explizit als Artefakt markieren: `CYP688-<uuid> instrument-alive control — NOT coordination`,
   damit die Kontrolle später nicht als Routen-Regression fehlgelesen wird.
2. **Hub-Round-Trip mit `NONCE_H`** fahren (Positiv-Schritte 1–6 oben).
3. **`T1`** notieren (nachdem PO2 den Status über den Hub empfangen hat).
4. **Discord-Transkript lesen** für `[T0 − Marge, T1 + Marge]` (Marge ≈ 2 min für Uhr-Drift + Zustell-Latenz),
   mit **einem** Leser / **einer** Aufrufform, **chat_id des po2↔backend2-Kanals gepinnt**, paginierend.

### Assertions (in dieser Reihenfolge)

| # | Assertion | Bei Verletzung |
|---|---|---|
| **Ⅰ** | **Fenster-Abdeckung bewiesen**: ältester gelesener Eintrag `≤ T0−Marge` **und** neuester `≥ T1+Marge` | **INCONCLUSIVE** — eine zu schmale Seite lässt Abwesenheit fälschlich grün aussehen |
| **Ⅱ** | **(b) Instrument lebt**: `NONCE_D` **wird gefunden** | **INCONCLUSIVE**, nie PASS — Leser/Kanal/Fenster falsch oder Pfad tot |
| **Ⅲ** | **(a) Abwesenheit**: `NONCE_H` **kommt im Transkript 0×** vor | **FAIL** — Doppel-Send / Strecke nicht abgelöst |
| **Ⅳ** | **Exakt-einmal auf dem Hub-Pfad**: `NONCE_H` im Hub-Record **genau 1× je Richtung** (Task, Status) | **FAIL** — Mehrfach-Zustellung / kosmetische Ablösung |

### Verdikt

- **PASS** ⟺ Ⅰ ∧ Ⅱ ∧ Ⅲ ∧ Ⅳ.
- **INCONCLUSIVE** (⚠️ **nicht** PASS) wenn Ⅰ oder Ⅱ fällt — die Abwesenheit ist dann **unbewiesen**, nicht widerlegt.
- **FAIL** wenn Ⅲ oder Ⅳ fällt.

**Warum Ⅱ trägt:** findet derselbe Leser `NONCE_D` (form-gleich, gleicher Kanal, gleiches Fenster) und `NONCE_H` **nicht**,
dann ist die Abwesenheit von `NONCE_H` eine **echte** Abwesenheit — nicht ein totes Instrument, kein falscher Kanal, keine
verpasste Seite. Ohne Ⅱ ist Ⅲ grün, sobald man einfach nicht hinschaut.

**Alternative zu Ⅱ ohne Pflanzung (schwächer, nur falls Pflanzen unerwünscht):** Live-Traffic der anderen 5 Spokes als
Beleg — aber der liegt in **anderen Kanälen** und beweist **nicht**, dass *dieser* Kanal gelesen/lesbar ist. ⟹ Pflanzung
bevorzugt.

---

## Zusammenfassung für den PO

- **M1.1:** Abnahme = **A–E** (Baseline · Auth-Connect · Agent→Hub mit hub-vergebener ID + server-gestempeltem `from` · Hub→Agent `WireDeliver` · `source=remote`) **+ 4 Fail-closed-Kontrollen** + **Restart-Wiederholung** (= CYP-172-Wächter). Ausdrücklich **nicht**: Prozess/health/`/api/agents`-Eintrag.
- **M1.7:** Abnahme = **NONCE-korrelierter Round-Trip in beiden Richtungen im Hub-Record** (server-gestempelte `from`) + **`source=remote`** + **entvakuosierte ACL** (Nicht-Member braucht positiven Downstream) + **„nicht Discord" nur mit Korrelation + Instrument-lebt-Kontrolle** + **exakt-einmal/null gegen den Doppel-Send**.
