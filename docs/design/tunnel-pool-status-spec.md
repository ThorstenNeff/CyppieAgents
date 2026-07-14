# Per-Tunnel Pool-Status — UX-Spec (CYP-540 / WS5)

> Owner: UIUX-Designer · Epic CYP-427 (M2-A N-Tunnel) · Story **CYP-540** (WS5) · Stand 2026-07-14 · Status: **Frozen-Spec-Ready** (design-not-build)
> **Source of Truth:** `docs/design/M2-A-ntunnel-workstream-split-and-contract.md` @ develop `7358c853` — **§4 C3** (frozen shape) + §2 (architecture).
> Baut Dev5 (WS5-Impl) gegen; WS4-Harness assertiert gegen denselben Contract; ich UX-QA'e das Ergebnis.
> **Gegen echten Code gegroundet** (develop `7358c853`, read-only): area `remote.*` (`RemoteConnectTags`), Tone-Tokens (`state-tokens.json`),
> H4-Präzedenz (ONE neutral relay-drop surface, `HubConnectSelection.kt:222`), `rendezvousId: String` (opak, `NoiseRelayConnector.kt:28`).
> **testTag-Contract:** `tunnel-pool-status-tags.md` (dieselbe Story, FROZEN — der Teil, gegen den Dev5 baut / WS4 assertiert).

---

## 0. Scope, Haltung, Grounding

**Was diese Fläche ist:** **EINE** workspace-scoped Status-Fläche, die den **echten** Zustand des N-Tunnel-Pools ehrlich anzeigt —
per-Tunnel (DIALING/UP/BACKPRESSURED/DOWN) + das Aggregat (active/cap, anyBackpressured). Sie **rendert** den C3-Stream; sie
**erzeugt keinen** Zustand und **rät nie**.

**Was sie NICHT ist:** kein N-per-Agent-Chip-Zoo (das wäre das Divergente-Duplikat-Anti-Pattern) — sie folgt der **H4-Präzedenz**:
*ONE neutral relay-drop/reconnect surface* (`HubConnectSelection.kt:222`, „never alarm-red; in-flight honestly uncertain"). Genau
**eine** Fläche mit per-Tunnel-Zeilen darin, nicht N verstreute Indikatoren.

**Design-not-build:** Ich spezifiziere Präsentation + Ehrlichkeits-Invarianten + friere den testTag-Contract. Die Mount-Stelle
(welches Composable im Workspace-Chrome) ist **Dev5s Impl-Entscheidung** — ich nenne die Präzedenz, nicht die Datei.

**Reuse statt Neu-Design:** Tone aus `state-tokens.json` (keine neuen Hex), Icon-Set bestehend, area `remote.*` bestehend
(`remote.pool.*` = neuer Sibling zu `remote.connect.*`/`remote.relayDrop`, **kollisionsfrei** verifiziert @ `7358c853`).

---

## 1. Das C3-Zustandsmodell (frozen — restated, nicht verändert)

Aus dem Contract §4 C3 (WS2 besitzt den Emitter; enum + shape sind **dort** eingefroren, hier nur gerendert):

```
TunnelPoolState = {
  tunnels: List<{ rendezvousId: String, state ∈ {DIALING, UP, BACKPRESSURED, DOWN}, sinceTs: Long }>,
  aggregate: { active: Int, cap: Int, anyBackpressured: Boolean }
}
```

Ich ändere **nichts** an dieser Form. Falls die UX eine Form-Änderung bräuchte → **PO-ratifizierte Contract-Revision** (§4-Regel),
kein stiller UX-Sonderweg.

---

## 2. Präsentation je Tunnel-State

Jede Zeile im Pool-Surface repräsentiert **genau einen** real emittierten Tunnel. **Farbe ist nie das alleinige Signal**
(WCAG 1.4.1): jeder State = **Ton + Icon + Text-Label**. Tokens referenziert semantisch (nie Hex direkt).

| C3-State | Ton-Token | Icon | Label-Register | Ehrlichkeits-Regel (load-bearing) |
|---|---|---|---|---|
| **DIALING** | `state.running` (blau) | `progress` (Spinner) | „Verbindet…" | **In-flight = ehrlich unsicher**, NICHT „verbunden". Noch kein Datapath. Reduced-motion → statisches Progress-Icon. |
| **UP** | `state.neutral` / `state.idle` (**neutral, grau**) | `dot` (klein, gefüllt) — **neutral gefärbt, NIE state.ok-grün** | „Verbunden" / „aktiv" | **UP ist Baseline, kein Erfolg → NIE success-grün.** Spiegelt H1/H4 („never success-green, never 'connected'-hype"). Das ist die zentrale Anti-Optimismus-Regel. |
| **BACKPRESSURED** | `state.waiting` (**amber** #FFC857) | `attention` (priority_high) | „Gedrosselt" (flow-limited, **noch up**) | **WARN-Ton, NIE Rot** (nicht kaputt), **NIE still als plain-UP** gezeigt. Advisory: der Tunnel steht, aber der Durchsatz ist begrenzt (H7). Sichtbarkeit ist Pflicht — der Nutzer muss die Drosselung sehen. |
| **DOWN** | `state.offline` (grau #5A5E66) | `minus` | „Getrennt" | **Ehrlich down**, aber **kein Rot-Alarm**: ein einzelner Tunnel-Down im Headroom-Pool ist **kein** System-Error (Rot ist der Tool/Turn-Error-Taxonomie vorbehalten). Der Katastrophenfall „gar kein Live-Tunnel" gehört **nicht** hierher (§5.3). |

**Warum UP nicht grün:** die Anti-Hype-Linie durchs ganze Produkt (`available = neutral, nicht grün`; H1-Registry-Presence
„never success-green"; H4 „never 'connected'"). Ein grüner UP-Punkt würde eine Gesundheits-Garantie suggerieren, die der Pool
nicht gibt — die aggregate-Wahrheit (unten) kann trotz lauter „UP"-Zeilen `anyBackpressured` sein.

---

## 3. Das Aggregat (active / cap, anyBackpressured)

Der Kopf/Summenzeile der EINEN Fläche.

- **`active / cap`** — **literal aus dem Emitter**, nie gerundet, nie optimistisch. `active < cap` ist **kein Mangel** und wird
  **nicht** als Defizit gerendert (Pool-Headroom ist Normalzustand) — neutraler Zähler, neutraler Ton.
- **`anyBackpressured == true`** → das Aggregat trägt den **amber WARN**-Qualifier (`remote.pool.aggregate.backpressured`) + amber
  Ton auf der Summenzeile. **Ehrlich:** der Pool ist gedrosselt, **auch wenn** jede Einzelzeile „up-ish" aussieht. Diese
  aggregate-WARN ist der load-bearing Grund, warum BACKPRESSURED nie still verschwinden darf.
- **Kein grünes „healthy"-Badge** (Anti-Hype). Aggregat-Baseline = **neutral**; amber nur bei echter Drosselung.
- `anyBackpressured == false` → Qualifier **absent** (nicht „green-OK", einfach neutral) — fail-closed gegen Phantom-Positiv.

---

## 4. Ehrlichkeits-Invarianten (load-bearing — unsere Spezialität, testbar)

Die PO-genannten Invarianten, konkret + assertierbar gemacht (WS4 assertiert, ich QA'e):

1. **State nur auf echtem Signal — nie optimistisch-grün.** Eine Zeile/State-Marker existiert **nur**, wenn der C3-Emitter den
   Tunnel in diesem State liefert. Kein optimistisches Vor-Rendern von „UP" vor dem Emitter-Signal. Kein Erfolgs-Grün überhaupt
   (§2). → Test: State-Marker-Presence ⇔ Emitter-State.
2. **BACKPRESSURED = WARN, nie Rot, nie still-UP.** amber Ton + sichtbares „Gedrosselt"-Label; **niemals** als plain-UP kaschiert,
   **niemals** rot-alarmiert. → Test: bei state=BACKPRESSURED ist `…backpressured`-Marker present, Ton=amber, kein Rot, kein `…up`.
3. **DOWN ehrlich.** kein optimistisches Verstecken; offline-grau + „Getrennt". → Test: state=DOWN ⇒ `…down` present, offline-Ton.
4. **INERT-lokal bis remote-connected.** Die ganze Fläche ist **absent**, solange nicht remote-connected / kein Tunnel emittiert
   (§5). Fail-closed: **Absenz**, nicht ein Phantom-„0/cap" (das läse sich als „alles down" = Falschalarm). → Test: nicht-connected
   ⇒ `remote.pool` **absent**.
5. **Aggregat ehrlich.** `active/cap` literal; `anyBackpressured` ⇔ echter Flag; kein grünes healthy-Badge; `active<cap` ≠ Defizit.
   → Test: aggregate-Text == Emitter-Werte; backpressured-Qualifier ⇔ Flag.
6. **Kein Alarm-Duplikat.** Der Pool zeigt **per-Tunnel-Wahrheit**; „gar kein Live-Tunnel / Verbindung verloren" ist **fail-closed
   im Transport** (C2 `null` = RST, kein Local-Fallback) + gehört der **bestehenden** H4-Relay-Drop / `RemoteFailure`-Fläche —
   der Pool erfindet **kein** zweites Rot dafür (§5.3, Anti-Duplikat).

---

## 5. Placement, INERT & Fehler-Grenze

### 5.1 EINE workspace-scoped Fläche
Genau **eine** Fläche im Workspace-Chrome (Post-Connect), per-Tunnel-Zeilen **darin**, Aggregat als Kopf. Mount co-lokalisiert mit
der bestehenden Remote-Connection-Chrome — **nicht** eine neue frei schwebende Panel-Insel, **nicht** N per-Agent-Chips
(H4-Präzedenz). Exakte Composable-Stelle = Dev5 (design-not-build).

### 5.2 INERT bis remote-connected (fail-closed Default)
- **Absent**, wenn: Lokal-Modus / nicht-remote-connected / C3 emittiert 0 Tunnel. Das ist die `INERT-lokal`-Invariant (§4.4).
- **Erscheint**, sobald der Transport zu etablieren beginnt (erster Tunnel → DIALING) — **echtes Signal only**.
- **Nie** ein Phantom-Placeholder „0/cap" oder ein optimistisches „bereit" vor echtem Signal.

### 5.3 Fehler-Grenze (Anti-Duplikat)
Per-Tunnel DOWN = offline-grau in dieser Fläche. Der **Katastrophenfall** (kein Live-Tunnel, Pairing/PoP komplett gescheitert)
ist **nicht** diese Fläche: C2 macht den Transport fail-closed (RST), und die **bestehende** Connect/Failure-Chrome
(`RemoteConnectTags.RELAY_DROP` H4, `RemoteFailure*`) trägt den Verbindungs-Alarm. Der Pool **dupliziert** das nicht.

---

## 6. Barrierefreiheit

- **Farbe nie allein** (WCAG 1.4.1): jeder State = Ton **+ Icon + Text-Label** (§2). QA prüft, dass der State auch **ohne** Farbe
  aus Icon+Label lesbar ist.
- **contentDescription** je Zeile: „Tunnel %1$s: %2$s" (rid-Kurzform + State-Label); Aggregat: „Pool: %1$d von %2$d aktiv%3$s"
  (%3$s = „, gedrosselt" wenn anyBackpressured). Amber/Grau tragen ihre Bedeutung im **Text**, nicht in der Farbe.
- **Kontrast-Ziele** (state-tokens $meta): Text ≥ 4.5:1, Icon/Nicht-Text-UI ≥ 3:1 gegen die surface. Amber #FFC857 auf `raised`
  dark — QA rechnet den realen Kontrast im gebauten Ergebnis nach (kein Schätzen).

---

## 7. Reduced Motion
DIALING nutzt eine dezente Progress-Animation; unter `prefers-reduced-motion` → **statisches** Progress-Icon, keine
Endlos-Animation (state-tokens `motion.reduced_motion`).

---

## 8. Resource-Keys (neu — landen mit Dev5s Impl)

> **Shared-Key-Landing-Flag (an PO/Dev5):** diese Keys **entwerfe ich**, **landen** tut sie Dev5 **mit der WS5-Impl** — ich lande
> keine geteilten Keys allein (sonst bricht der Shared-Check bis das konsumierende Modul re-synct). Timing = mit CYP-540-Bau.

| Key | Text (de, Vorschlag) | Rolle |
|---|---|---|
| `tunnel_pool_state_dialing` | „Verbindet…" | DIALING-Label |
| `tunnel_pool_state_up` | „Verbunden" | UP-Label (neutral) |
| `tunnel_pool_state_backpressured` | „Gedrosselt" | BACKPRESSURED-Label (WARN) |
| `tunnel_pool_state_down` | „Getrennt" | DOWN-Label |
| `tunnel_pool_aggregate` | „%1$d von %2$d Tunneln aktiv" | Aggregat-Summe |
| `tunnel_pool_aggregate_backpressured` | „gedrosselt" | Aggregat-WARN-Qualifier (angehängt) |
| `a11y_tunnel_pool_row` | „Tunnel %1$s: %2$s" | Zeilen-contentDescription |
| `a11y_tunnel_pool_aggregate` | „Pool: %1$d von %2$d aktiv%3$s" | Aggregat-contentDescription |

Keine „since"-Keys im MVP-Scope (dwell-Disclosure = gehalten, §tags `tunnelSince` OPTIONAL — erst wenn PO sie will; Anti-Over-Production).

---

## 9. Self-Validation
- **4 Tunnel-States** vollständig abgebildet (DIALING/UP/BACKPRESSURED/DOWN), jeder mit Ton+Icon+Label+Ehrlichkeits-Regel — 1:1 zur frozen C3-enum.
- **Aggregat** (active/cap + anyBackpressured) abgebildet, honest, kein green-healthy.
- **6 Ehrlichkeits-Invarianten** (§4) sind jede als konkreter Test formuliert (WS4-assertierbar, QA-prüfbar).
- **0 neue Hex** — alle Töne aus `state-tokens.json`. **0 neue Area** — `remote.pool.*` erweitert `remote.*`.
- **Anti-Hype gewahrt:** UP neutral (nicht grün), BACKPRESSURED amber (nicht rot), DOWN grau (nicht rot), Aggregat neutral (kein healthy-grün).
- **Anti-Duplikat gewahrt:** ONE surface (H4-Präzedenz), Katastrophen-Alarm bleibt bei Transport/H4.
- testTag-Contract separat eingefroren in `tunnel-pool-status-tags.md`; Keys als shared-key-landing an Dev5 geflaggt.
