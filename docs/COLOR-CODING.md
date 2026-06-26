# Absender-/Kanal-Farbcodierung — Comm-Panel (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-14** (Vorarbeit Comm-Panel S6 / Epic CYP-3) · Status: **Entwurf — wartet auf Dev-Gegenlesen** · Stand: 2026-06-26
> **Kanonischer Ort:** geteiltes Repo `KMPCyppieAgents` unter `docs/COLOR-CODING.md`.
> Begleit-Artefakte: `docs/design/color-coding-tokens.json`, `docs/design/color-coding-keys.md`.
> **Brand:** CyppieAgents (Anti-Hype). NeonFi nur Referenz.

Definiert, wie Absender und Kanäle im Comm-Panel **deterministisch, barrierearm und konsistent** farblich kodiert werden — gegen das reale Comm-Modell gemappt. Keine Implementierungsvorgabe.

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
4. **Kontrast (Pflicht).** Name-/Label-Text ≥ **4.5:1** gegen die Fläche; Farbe auf Avatar-Fill/Border ≥ **3:1**. Werte für dark (Default) + light. Auto-Validierung steht aus (QA/Dev).
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
4. **Kontrast-Validierung** der Paletten automatisieren vor „Fertig" (QA/Dev).
