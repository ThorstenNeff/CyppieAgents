# CYP-387 — Eingabe-Historie im Agenten-Composer (Interaktions-Spec)

> Owner: UIUX-Designer · Ticket **CYP-387** (Story) · Stand 2026-07-11 · Basis **`origin/develop` = `cc3d2d59`** · Scope **WASM-App**
> Docs-only. Adressat: Developer5 (UI) + die parallel scaffoldete Datenschicht.
> **Scope: nur der Agenten-Composer** (`MessageComposer`, `AgentWindow.kt`). Der Comm-Composer bleibt außen vor.

---

## 0. Vereinfacht: einzeilig → reine Historie-Navigation

Der Composer ist `singleLine = true`. Damit ist die „nur ab erster/letzter Zeile"-Regel **gegenstandslos** (in
einer einzigen Zeile bewegen ↑/↓ den Cursor ohnehin nicht) — **↑/↓ sind reine Historie-Navigation.**

**Folge, die Arbeit spart:** Die Impl braucht **kein `TextFieldValue`** und keinen State-Umbau. Der bestehende
`String`-Entwurf genügt; die Navigation setzt den Feld-`String`. Ein `onKeyEvent` auf dem Feld fängt ↑/↓ ab —
Haus-Vorbild: `WindowManager.kt:604` (`Key.DirectionUp`/`Key.Enter`, `KeyEventType.KeyDown`).

---

## 1. Das Historien-Modell (Vertrag für die Datenschicht)

| Eigenschaft | Festlegung | Begründung |
|---|---|---|
| **Inhalt** | die **erfolgreich gesendeten** Nachrichten — der getrimmte `onSend`-Text (`AgentViewModel.onSend`, = `UserTurn.text`) | „gesendet" = was der Nutzer abschickte; **nicht** Agent-Antworten, **nicht** injizierte System-Nachrichten (`IncomingSystem`), **nicht** leere Sends (`onSend` trimmt + verwirft leer) |
| **Reihenfolge** | Liste, **neuestes zuletzt** | ↑ geht ins Ältere |
| **Scope** | **pro Agent** · **v1 session-scoped, in-memory** (PO-Defaults, übernommen) | jeder Agent hat seinen eigenen Faden |
| **Kapazität** | `N` (Default **20**, konfigurierbar §3); über `N` fällt das **älteste** heraus | Terminal-`HISTSIZE`-Modell |
| **Dedup** | **v1: keiner** | vorhersehbar; Shell ohne `HISTCONTROL`. *Optional (PO): aufeinanderfolgende Duplikate zusammenfassen — nenne ich, empfehle es v1 nicht.* |

**Angehängt wird beim Senden**, an genau der Stelle, an der `onSend` heute den `UserTurn` erzeugt.

---

## 2. Die Interaktion

Zustand pro Composer (pro Agent):

- `history: List<String>` — aus dem Store, neuestes zuletzt.
- `navIndex: Int?` — `null` = am Live-Entwurf; sonst `0..history.lastIndex`.
- `stash: String` — der Live-Entwurf, **festgehalten beim Eintritt** in den Verlauf, damit ↓ ihn zurückholt.

### 2.1 Übergänge

**↑ (`Key.DirectionUp`, `KeyDown`):**

| Zustand | Aktion |
|---|---|
| `history` leer | **No-op** (nichts zu recallen) |
| `navIndex == null` (Eintritt) | `stash = draft`; `navIndex = history.lastIndex`; `draft = history[navIndex]` |
| `navIndex > 0` | `navIndex -= 1`; `draft = history[navIndex]` |
| `navIndex == 0` (Ältestes) | **No-op** (Verlaufsanfang; v1 still — kein Ton/Blink) |

**↓ (`Key.DirectionDown`, `KeyDown`):**

| Zustand | Aktion |
|---|---|
| `navIndex == null` (schon am Entwurf) | **No-op** |
| `navIndex < history.lastIndex` | `navIndex += 1`; `draft = history[navIndex]` |
| `navIndex == history.lastIndex` (Neuestes) | `navIndex = null`; **`draft = stash`** (Entwurf zurück) |

Beide Pfeile werden im Recall-Fall **konsumiert**; bei leerer Historie (↑) bzw. am Entwurf (↓) **nicht**
konsumiert, damit sich nichts Unerwartetes verschluckt.

### 2.2 Recall editieren, dann senden — deine Frage, explizit beantwortet

> „recall-dann-editiert → beim Senden neu erfassen?" — **Ja.**

- Tippt der Nutzer, während `navIndex != null`, ist der Text eine **transiente Arbeitskopie**. **Der Verlauf
  ändert sich nie.**
- Die nächste ↑/↓ **verwirft** die Arbeitskopie und zeigt den Nachbar-Eintrag (kein Pro-Slot-Puffer in v1 —
  `readline`s „edited copies persist" nenne ich, empfehle es v1 nicht).
- **Senden** schickt die Arbeitskopie **und erfasst sie als neuen neuesten Eintrag** (das Original bleibt);
  danach `navIndex = null`, `stash = ""`, Feld leer. Reines Shell-Verhalten.

### 2.3 Grenzfälle — jeder aufgelöst

| Fall | Verhalten |
|---|---|
| **Leere Historie, ↑** | No-op. |
| **Leerer Entwurf, ↑** | `stash = ""`; Neuestes wird gerufen. ↓ am Neuesten → leeres Feld zurück. |
| **Am neuesten Eintrag ↓** | `navIndex → null` + `stash` (Entwurf zurück). |
| **↓ ohne im Verlauf zu sein** | No-op. |
| **Recall editiert** | transiente Arbeitskopie; ↑/↓ verwirft; Verlauf immutabel; Senden erfasst neu (§2.2). |
| **`0 = aus` (§3)** | ↑/↓ tun nichts (Recall abgeschaltet). |

---

## 3. Settings — und eine begründete Scoping-Korrektur

**Deine Defaults übernehme ich (Inhalt pro Agent, v1 session-in-memory). Die *Zahl* korrigiere ich:**

> **Die „Anzahl" ist eine persönliche UI-Präferenz — keine Operator-/Projekt-Einstellung — und EINE Zahl,
> nicht eine pro Agent.**

1. **Falsches Gate sonst.** Die globale Settings-Fläche heute ist `SettingsPanel` (`settings/`) — die
   **operator-gegatete** Projekt-Konfiguration (Repo + API-Key). Eine Verlaufslänge dort hieße: ein
   Nicht-Operator darf seine eigene Tipphilfe nicht einstellen. Falsches Gate. Die richtige Familie ist
   **`ThemePreferences`** — die persönliche, **ungegatete** UI-Präferenz.
2. **Ein Knopf, nicht N.** Terminal-Modell: **ein** `HISTSIZE` regelt alles; die *Inhalte* sind getrennt. Also:
   **Zahl global, Inhalt pro Agent.** Niemand pflegt 20 pro Agent.

**Empfehlung:** die Zahl lebt bei den persönlichen UI-Präferenzen (Theme-Familie), ungegatet. Gibt es dafür
keine sichtbare Präferenz-Fläche als Panel, ist es ein kleiner Zusatz neben der Theme-Steuerung. **Willst du
bewusst pro-Agent-Zahlen**, sag es — dann klären wir das Gate im `AgentSettingsPanel`. Bis dahin: eine Zahl,
ungegatet.

### 3.1 Steuerung + Microcopy

- **Control:** kleiner **Stepper** (− / Zahl / +) oder numerisches Feld, **Bereich `0..200`**, Default **20**.
  **`0 = Verlauf aus`** (ehrlich: schaltet Recall ab). Obergrenze 200, damit der session-in-memory-Store nicht
  unbeschränkt wächst.
- **Keys (DE/EN, Paritäts-Guard):**

| Key | DE | EN |
|---|---|---|
| `composer_history_size_label` | „Nachrichten im Eingabeverlauf" | „Messages in input history" |
| `composer_history_size_help` | „Mit ↑/↓ im Eingabefeld durchblättern. 0 schaltet den Verlauf aus." | „Scroll with ↑/↓ in the input. 0 turns history off." |

**Beide Sprachen landen mit der Impl im selben Commit** (geteilter Key → sonst bricht der Paritäts-Guard).

---

## 4. Barrierefreiheit

- **↑/↓ in einem einzeiligen Feld für Historie zu nutzen ist Standard** (Browser-Adressleiste, Shell-Prompt,
  DevTools-Konsole) — in einer Zeile bewegen die Pfeile den Cursor nicht, das Abfangen stiehlt also nichts.
- **Kein Keyboard-Trap:** ↑ bei leerer/ältester Historie und ↓ am Entwurf sind No-ops; Fokus bleibt frei
  (Tab normal).
- **Wertänderung wird angesagt:** beim Recall ändert sich der Feldinhalt; Screenreader sagen den neuen Wert an.
  **Optional** (v1 nicht zwingend, nur wenn im Test nicht geschwätzig): diskreter Positions-Hinweis via
  `liveRegion`, Key bereitgehalten: `a11y_composer_history_position` = „Verlauf %1$d von %2$d" /
  „History %1$d of %2$d".
- **`0 = aus` ist kein toter Schalter:** abgeschaltet tun ↑/↓ nichts, statt so zu wirken, als täten sie etwas.

---

## 5. Abnahme

1. **Grundfluss:** ↑ aus leerem Entwurf → Neuestes; weiter ↑ → älter; ↓ → neuer; ↓ am Neuesten → Entwurf (leer).
   **Mutation:** ↓ am Neuesten setzt `history[0]` statt `stash` ⇒ Entwurf kehrt nicht zurück ⇒ rot.
2. **Entwurf überlebt:** „foo" tippen, ↑↑, ↓↓ zurück ⇒ „foo" steht wieder. **Mutation:** `stash` nicht setzen ⇒
   „foo" verloren ⇒ rot.
3. **Verlauf immutabel:** einen Recall editieren, ↑ ⇒ Nachbar **im Original**; Store unverändert.
   **Mutation:** Arbeitskopie in den Store schreiben ⇒ Original mutiert ⇒ rot. **(Kerntest.)**
4. **Senden erfasst + resettet:** editierten Recall senden ⇒ neuer neuester Eintrag, Original bleibt, Feld leer,
   `navIndex == null`. **Mutation:** beim Senden nicht anhängen ⇒ Text fehlt oben ⇒ rot.
5. **Kapazität + Aus:** `N=3`, vier senden ⇒ ältestes weg, Länge 3. `0` ⇒ ↑ kein Recall. **Mutation:** Cap
   ignorieren ⇒ Länge 4 ⇒ rot.
6. **Leere Historie:** ↑ ⇒ No-op (kein Absturz, keine Konsumtion).
7. **Nur Agenten-Composer:** der Comm-Composer zeigt **kein** Recall. **Mutation:** Historie an den Comm-Composer
   hängen ⇒ dessen ↑ recallt ⇒ rot.
8. **Key-Parität:** `composer_history_size_*` in DE **und** EN. **Mutation:** EN-Key raus ⇒ Paritäts-Guard rot.

**Test 3 ist der Kern** — die Immutabilität des Verlaufs. Alles andere ist Navigation; Test 3 ist die Zusage,
dass Browsen nichts kaputtmacht.

---

## 6. Self-Validation

- **Schlank durch die Einzeiligkeit** (§0): kein `TextFieldValue`, kein State-Umbau, keine Zeilen-Regel. Der
  `String`-Entwurf genügt.
- **Reuse:** Feld = `AgentViewTags.input`; Keyboard = `WindowManager.kt:604`-Muster; Settings = `SettingsPanel`/
  `ThemePreferences`-Familie. Kein neues Muster.
- **Deine explizite Frage beantwortet** (§2.2): editierter Recall wird beim Senden als neuer Eintrag erfasst;
  der Verlauf bleibt immutabel.
- **Ein Scoping-Default begründet korrigiert** (§3): die *Zahl* ist persönlich, **ungegatet**, und **eine**
  (Inhalt pro Agent, Zahl global) — sonst sitzt eine Tipphilfe hinter dem Operator-Gate.
- **Scope eingehalten** (§0, Test 7): nur der Agenten-Composer, Comm außen vor.
- **a11y ist Vorgabe, nicht Zusatz** (§4): Standard-Muster, kein Trap, `0 = aus` kein toter Schalter.
- **Docs-only.**
