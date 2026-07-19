# Absender-/Kanal-Farbcodierung — Comm-Panel (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-14** (Vorarbeit Comm-Panel S6 / Epic CYP-3) · Status: **Entwurf — wartet auf Dev-Gegenlesen** · Stand: 2026-07-16
> **Kanonischer Ort:** geteiltes Repo `KMPCyppieAgents` unter `docs/COLOR-CODING.md`.
> Begleit-Artefakte: `docs/design/color-coding-tokens.json`, `docs/design/color-coding-keys.md`, **`scripts/contrast-check.py`** (§8, reproduzierbare Messung).
> **Brand:** CyppieAgents (Anti-Hype). NeonFi nur Referenz.

Definiert, wie Absender und Kanäle im Comm-Panel **deterministisch, barrierearm und konsistent** farblich kodiert werden — gegen das reale Comm-Modell gemappt. Keine Implementierungsvorgabe.

> **★ Bevor du „grau das mal aus" auf einer Titelleiste / einem Agenten-Fenster umsetzt: lies §8.** Auf
> agent-eingefärbten Flächen gibt es **keinen** AA-sicheren Sekundär-/Dimm-Ton (null Headroom by construction) —
> Zustand wird dort über **Glyph bei vollem Kontrast** getragen, nie über Ton/Alpha. Nachrechnen: `scripts/contrast-check.py`.

---

## 0. Bezugsrahmen (verifiziert im Code, 2026-06-26)

`core/.../model/CommModel.kt` (CYP-9, geteilter Vertrag `:core`):
- **Absender:** `Message.from` = `Agent.id`. `Agent(id, name, role, worktree)`, `enum Role { PO, WORKER }`.
- **Kanal:** `Channel(id, name, kind, members)`, `enum ChannelKind { DIRECT, GROUP, HUB }`.
- **Verwandte Dimension:** `MessageMeta.kind ∈ {TASK, STATUS, NOTE}` — eigenständig, siehe §6.
- Konsumiert in `:app:shared` (Comm-Panel, S6).

---

## 1. Prinzipien

1. **Deterministisch & stabil.** Gleiche `id` → gleiche Farbe, über Sessions und Clients hinweg. **Kein** Zufall, kein laufzeit-zufälliger Hash-Seed. Server und Client müssen dieselbe reine Funktion verwenden (§3).
2. **Farbe nie alleiniger Träger** (WCAG 1.4.1). Absender = **Avatar (Initialen) + Name + Farbe**; Kanal = **Kind-Icon + Name + Farbe**. Farbe ist Scan-Hilfe, nicht das Identifikationsmerkmal. Bei vielen Agenten trägt das Label/Avatar die Eindeutigkeit, nicht der Farbton.
3. **Keine Kollision mit Status-Semantik (CYP-12).** Die Absender-/Kanal-Palette meidet **bewusst** die reservierten Status-Hues (running-Blau, ok-Grün, error-Rot, waiting-Amber). Sonst läse sich ein grün eingefärbter Absender als „OK". **Status-Signale haben visuell immer Vorrang**; Identitätsfarbe sitzt auf Avatar/Name, nie auf Status-Chips.
4. **Kontrast (Pflicht).** Name-/Label-Text ≥ **4.5:1** gegen die Fläche; Farbe auf Avatar-Fill/Border ≥ **3:1**. Werte für dark (Default) + light. Auto-Validierung: **`scripts/contrast-check.py`** (gegen die *echte* Fläche messen, nicht Token-Namen-plausibel — §8). **Sonderfall agent-eingefärbte Flächen: null Headroom → kein Sekundär-Ton, nur Glyph — siehe §8.**
5. **RTL-tauglich.** Farb-/Avatar-Anordnung spiegelbar; Reihenfolge logisch (start/end), nicht hart links/rechts.

---

## 2. Was wird wie eingefärbt

| Element | Quelle | Farbträger | Redundanz |
|---|---|---|---|
| **Absender** | `Message.from` (`Agent.id`) | Avatar-Fill/Border + Name-Akzent | Initialen + voller Name |
| **PO-Absender** | `Agent.role == PO` | reservierter Hub-Slot (distinct) | „PO"-Badge/Icon |
| **Kanal** | `Channel.id` | Kanal-Punkt/Akzent in Liste | Kanal-Name |
| **Kanal-Typ** | `Channel.kind` | Kind-Icon (nicht Farbe) | DIRECT/GROUP/HUB-Icon + Label |

> **Eine Quelle pro Element:** Absenderfarbe kommt **immer** aus `Agent.id`, nicht aus dem Kanal — derselbe Agent ist überall gleich gefärbt (Wiedererkennung). Kanalfarbe kommt aus `Channel.id`.

---

## 3. Deterministischer Mapping-Algorithmus

**Zwei-Schicht-Trennung (mit Dev bestätigt, 2026-06-26):**
- **`:core` liefert nur den deterministischen Slot-Index (`Int`)** — reine, compose-freie Funktion. Damit bleibt `:core` UI-unabhängig und alle Clients/der Server berechnen denselben Index.
- **Das Mapping Index → Farbe (Palette/Theme) lebt im UI (`:app:shared`).** Diese Spec definiert die **Slot-Vergabe** (unten) **und die Palette** (`color-coding-tokens.json`); `:core` kennt die Farben nicht.

```kotlin
// :core — nur der Index, keine Compose-/Color-Typen
fun colorSlot(id: String, paletteSize: Int): Int {
    knownSlots[id]?.let { return it }          // po/frontend/backend (Vorhersagbarkeit)
    var h = 2166136261u                          // FNV-1a, 32-bit
    for (c in id.encodeToByteArray()) { h = h xor c.toUInt(); h *= 16777619u }
    return (h % paletteSize.toUInt()).toInt()
}
```
```kotlin
// :app:shared — Index → Farbe (Palette aus dieser Spec / Theme)
val color = senderPalette[colorSlot(agentId, senderPalette.size)]
```

- **PO:** reservierter Slot (Hub) — fest über `knownSlots`, nie per Hash.
- **`knownSlots`:** `po` / `frontend` / `backend` (MVP, PO-bestätigt); Werte siehe `color-coding-tokens.json`.
- **Kollisionen** bei vielen dynamischen Agenten sind akzeptabel, weil Avatar+Name die Eindeutigkeit tragen (Prinzip 2).

---

## 4. Paletten

Vollständige Werte in `docs/design/color-coding-tokens.json`.

- **Sender-Palette:** 8 distinkte Identitäts-Hues (Teal, Violett, Magenta, Indigo, Bronze/Ocker, Pink, Slate-Blau, Olив — **bewusst weg von** reinem Blau/Grün/Rot/Amber der Status-Tokens). Jeder Slot: `avatarFill`, `onAvatar` (Initialen-Text), `nameAccent` — je dark/light.
- **PO-Slot:** distinct, ruhig-autoritativ (z. B. tiefes Indigo), klar von Workern unterscheidbar.
- **Kanal:** Kanal-Identitätsfarbe via gleichem Slot-Algorithmus aus `Channel.id`; **Kanal-Typ** über **Icon** (HUB/DIRECT/GROUP), nicht über Farbe — so bleibt Typ und Identität getrennt lesbar.

---

## 5. Disclosure-Honesty

- **Farbe ist reine Identität/Dekoration — nie eine Garantie oder Berechtigung.** Eine Absender- oder Kanalfarbe darf **nicht** implizieren, dass jemand „vertrauenswürdig", „freigegeben" oder schreibberechtigt ist.
- **ACL (`canRead`/`canWrite`) wird NICHT über Farbe kodiert.** Lese-/Schreibrechte haben ihre eigene explizite UI (ACL-Matrix, S7/CYP-12-Muster). Kein „grün = darf schreiben"-Kurzschluss.
- **PO-Hervorhebung** = Rollen-Kennzeichnung (Hub), keine Wertung der Inhalte.

---

## 6. Verwandte Dimension: `MessageMeta.kind` (TASK/STATUS/NOTE)

Eigenständig von Absender/Kanal. **Reuse statt Neuerfindung:** für `STATUS` die Status-Semantik wiederverwenden, wo sie passt — aber **getrennt** vom Identitäts-Farbsystem (kleines Kind-Badge/Label, nicht den Absender umfärben). Empfehlung: in S6 als eigener Spec-Punkt behandeln, nicht hier vermischen. Hier nur geflaggt.

---

## 7. Entscheidungen & offene Punkte

**Vom PO entschieden (2026-06-26), Devs finale Umsetzbarkeits-Bestätigung steht aus:**
1. ✅ **Algorithmus-Split:** `:core` liefert nur den Slot-**Index (`Int`)** (compose-frei); das **Index→Farbe-Mapping (Palette)** liegt im UI (`:app:shared`). Spec definiert Slot-Vergabe + Palette (§3).
2. ✅ **`knownSlots`:** MVP-Agenten = `po` / `frontend` / `backend`.
3. ✅ **Avatar-Quelle:** Initialen aus `Agent.name`, kein Bild-Asset im MVP.

**Offen:**
4. ✅ **Kontrast-Validierung automatisiert:** `scripts/contrast-check.py` (§8) — reproduzierbare WCAG-Messung gegen die echte Fläche. (Optional weiter: CI-Zahn, der die Paletten-Tokens gegen ihre Flächen prüft.)

---

## 8. Sekundär-Signale auf agent-eingefärbten Flächen — die Null-Headroom-Invariante

> **Produktweite Regel** (nicht comm-panel-spezifisch). Herkunft: UIUX-Designer, entdeckt bei **CYP-656**
> (Titelleisten-Token-Frische), 2026-07-16. Reproduzierbar: **`scripts/contrast-check.py`**.

### ① Die Invariante — mit den Zahlen, die sie unwiderlegbar machen

Agent-eingefärbte Flächen (Titelleiste via `SenderPalette.forAgent` → `:core` `deriveScheme`,
`core/.../model/ColorDerivation.kt`) haben **null Kontrast-Headroom *by construction*:** `deriveScheme` setzt
`barContent` = **reines Weiß/Schwarz** und zieht dann den **Hintergrund** nach, bis Weiß/Schwarz *gerade* 4.5:1
erreicht. Gemessen (`scripts/contrast-check.py`, WCAG 1.4.3):

| Test | Ergebnis |
|---|---|
| Weiß @ Alpha 0.75 über eine 4.5:1-Bar | **3.30** (@0.90 = **3.96**) — jedes Dimmen bricht AA |
| fester „Grau"-Token gg. die Farb-Familie | **1.0–2.7:1** (fällt bei fast allen Mitgliedern) |
| bester **fester** Slot, pink `#C24D6A`, Headroom für Weiß | **+0.10** — es ist keine Luft da |
| Custom-`#RRGGBB` Agent-Farben | landen bei **exakt ~4.5:1** (deriveScheme) — Worst Case ist generisch |
| Unfokus-Blend `lerp(agent, surface, 0.45)` | fällt zusätzlich **je Theme** (1.15 light / 1.18 dark) |

⟹ Bei **unbeschränkter** Agent-Farb-Familie (jeder Operator kann ein `#RRGGBB` setzen) gibt es **keinen**
sekundären/gedimmten Ton, der für *alle* Mitglieder ≥ 4.5:1 trägt. Das ist nicht „der falsche Grauwert" — die
**Mechanik ist an dieser Fläche unmöglich**, weil die Fläche so konstruiert ist, dass sie exakt das Minimum liefert.

### ② Die Regel

**Auf agent-eingefärbten Flächen wird ein Zustand / Sekundär-Signal über einen GLYPH bei vollem `barContent`
getragen — nie über Ton, Alpha oder Dimmen.** Farbe darf nur *verstärken*, nie alleiniger/primärer Träger sein
(Verschärfung von Prinzip 2 + 4 für den Null-Headroom-Fall). Das ist das **Hausmuster**, dreifach belegt — keine
Ausweich-Erfindung:

- **Mode-Marker `◉/→/←/∅`** (`WindowManager.kt` §5.1) — Kontroll-Zustand: Glyph + Label, Farbe nur Verstärkung.
- **Busy-`*`** — Turn-in-flight: Glyph bei vollem `barContent`.
- **Token-Frische `~137k`** (CYP-656) — „zuletzt bekannt/ungefähr": Glyph-Marker statt Grau.

Der nächste, der **etwas Sekundäres** in die Titelleiste / ein Agenten-Fenster will (Timestamp, Sekundär-Label,
„veraltet"-Hinweis, Zähler zweiter Ordnung), nutzt **diese Achse** — nicht einen erfundenen Grau-Ton.

### ③ Reproduzierbar (nicht Prosa)

```
python3 scripts/contrast-check.py                 # self-test = die Zahlen aus ①
python3 scripts/contrast-check.py FG BG           # Kontrast zweier #RRGGBB
python3 scripts/contrast-check.py --dim FG BG A   # „grau das aus"-Probe: FG @Alpha A über BG vs BG
python3 scripts/contrast-check.py --survives CAND f1 f2 …   # trägt ein Sekundär-Ton die ganze Familie?
```
Kein Vertrauen auf „onSurfaceVariant klingt gedimmt genug" — **nachrechnen gegen die echte Fläche.**

### ④ Wo es NICHT gilt (präzise, kein pauschales Verbot)

Die Invariante greift **nur** auf agent-eingefärbten Flächen (per Konstruktion auf 4.5:1 gedrückt). Auf Flächen
mit **garantiertem Headroom** — Standard-M3-`surface`, **nicht** agent-eingefärbt — ist ein sekundärer Ton völlig
legitim: `onSurfaceVariant` vs maritime-Surface = **8.69** (light) / **9.80** (dark), beide ≥ 4.5:1. Ein pauschales
Ton-Verbot wäre falsch (und würde umgangen) — die Regel ist **flächen-spezifisch.**

### Warum das eine Regel ist, kein Ticket-Detail

Genau die **CYP-643-Klasse:** „`onSurfaceVariant` klingt gedimmt genug" kommt durch Token-Flip-Gates durch, weil
der Token-**Name** plausibel ist — aber die **gemessene** Fläche trägt nicht. Diese Regel + der Script fangen das
**vor** dem Bau. Ziel: wer „grau das mal aus" gesagt bekommt, sieht in 30 Sekunden, dass es an dieser Fläche nicht
geht — und kriegt die Antwort (Glyph bei vollem Kontrast) gleich mitgeliefert.

---

## 9. Verwandte Design-System-Regeln

- **`A11Y-ANNOUNCEMENTS.md`** — Ansage-Dringlichkeit `Polite` vs `Assertive`. Die Achse ist
  **Aufmerksamkeit, nicht Schwere**: Ergebnis einer gestarteten Aktion oder unaufgefordert ⇒ `Assertive`;
  Anfangszustand einer gerade geöffneten Fläche ⇒ `Polite`. Ergänzt §8: dort geht es darum, **womit** ein
  Zustand getragen wird (Glyph statt Ton), hier **wie laut** er angesagt wird.
