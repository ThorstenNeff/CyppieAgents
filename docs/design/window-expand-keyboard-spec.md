# Design-Spec — Tastatur-Pfad für Fenster-Expand/Restore (CYP-245 + CYP-248)

> Owner: UIUX-Designer · Stories **CYP-245** (Enter = Toggle Expand↔Restore) **+ CYP-248** (Escape = Restore-only) · beide **Low** ·
> Epic: Desktop/Fenster-Manager · Stand: 2026-07-06 · Status: Vorschlag · verlinkt mit CYP-241.
> **Grounded gegen** `origin/develop e6f0882` (`WindowManager.kt` `.onKeyEvent`/`.focusable`/`detectTapGestures`/`isExpanded`,
> `WindowManagerState.toggleExpand`/`isExpanded`, `AgentSettingsPanel` `AlertDialog`, `WindowTestTags`, `values*/strings.xml`).
> **Reiner `commonMain`-Fenster-Manager**, keine Server-/Protokoll-Änderung. **Ein kohärenter Key-Handling-Pfad, ein Build**
> (Dev macht Enter **und** Escape in einem Slice), **ein UX-QA-Abnahmesatz (§9) für beide.** Größe: **S**.
> **Schließt** meine offene CYP-241-UX-QA-Empfehlung (Tastatur-Äquivalent zur Titelleisten-Doppelklick-Geste, damals
> nicht-blockierend vertagt). CYP-248 = die vom Auftraggeber benannte konkrete **Escape=Restore**-Belegung auf demselben Pfad.

---

## §0 — Ziel in einem Satz

Was der **Doppelklick auf die Titelleiste** seit CYP-241 tut (Fenster **vergrößern & zentrieren** ↔ **zurückstellen**),
muss ein **Tastaturnutzer am fokussierten Fenster** gleichwertig auslösen können — **dieselbe Aktion, dieselbe Geometrie,
dieselbe Ansage**, nur eine andere Eingabemodalität. **Zwei Tasten, ein Pfad:** **`Enter`** toggelt (CYP-245, volles
a11y-Äquivalent), **`Escape`** stellt ein expandiertes Fenster **zurück** (CYP-248, die vertraute „Zurück"-Taste) — beide
rufen denselben CYP-241-Restore-Zweig, keiner ist ein zweites Schema.

---

## §1 — Ausgangslage (verifiziert @ `e6f0882`)

Der Tastaturpfad **existiert bereits** — CYP-245 fügt **eine Taste** hinzu, kein neues System:

- **Fenster-Wurzel ist fokussierbar & fängt Tasten:** `WindowManager.kt` `FloatingWindow` hat am Wurzel-`Box`
  `.onFocusChanged { if (it.isFocused) onFocus() }` → `.focusable()` → `.onKeyEvent { … }` (Z. 473–497).
  Heute behandelt: **Pfeiltasten** = verschieben (`KEYBOARD_MOVE_STEP=16`), **Umschalt+Pfeiltasten** = Größe
  (`KEYBOARD_RESIZE_STEP=16`). Bedingung: `event.type == KeyDown`; behandelte Tasten geben `true` zurück, sonst `false`.
- **Doppelklick-Geste (CYP-241):** separater `.pointerInput { detectTapGestures(onDoubleTap = { onToggleExpand() }) }`
  **neben** dem Drag-Detektor (Z. 537–540). `onToggleExpand` → `state.toggleExpand(id)`.
- **Zustands-Exposition (CYP-241):** die Wurzel-Semantik trägt
  `stateDescription = expandStateDesc`, mit
  `expandStateDesc = stringResource(if (isExpanded) window_state_expanded else window_state_normal)` (Z. 451/469).
- **Logischer Fokus:** `focusedId = windows.lastOrNull()?.id`; `onFocus()` ruft `state.focus(id)` → `bringToFront`.
- **`toggleExpand(id)`** (`WindowManagerState.kt` Z. 525): kein Anker → **Expand** (aktuelle Geometrie als transienter
  Anker sichern, Ziel = `expandCentered`, nach vorn); Anker vorhanden → **Restore** (Anker-Geometrie geclampt
  wiederherstellen, Anker löschen, nach vorn). `moveBy`/`resizeBy` löschen den Anker; `fit()` löscht alle Anker.

**Konsequenz:** Weil sowohl Maus- als auch Tastaturpfad **denselben** `toggleExpand` aufrufen, sind Geometrie **und**
Ansage automatisch identisch — CYP-245 muss **keine Logik duplizieren** und **keinen Zustands-String erfinden**.

---

## §2 — Entscheidung: die Taste

### D1 — **Eingabetaste (`Enter`/`Return`), einfach, ohne Modifier — toggelnd**

Am **fokussierten Fenster** (dessen Wurzel den Tastaturfokus hält) löst **`Enter`** `onToggleExpand()` aus:
kein Anker → Expand+Center, Anker → Restore. **Ein Tastendruck ↔ ein Doppelklick.**

**Warum `Enter`:**
1. **Vokabular-Konsistenz.** Die Fenster-Wurzel ist bereits die „Manipulationsfläche" der Tastatur
   (Pfeile verschieben, Umschalt+Pfeile skalieren). `Enter` = *„die fokussierte Fläche aktivieren"* = ihr Default-Verhalten,
   und ihr Default ist hier Expand/Restore. Das reiht sich nahtlos ein, statt eine fremde Mechanik einzuführen.
2. **Doppelklick-Parität durch Toggle.** Ein einzelner toggelnder Key spiegelt die einzelne toggelnde Geste 1:1 —
   inklusive CYP-241-„kein toter Klick": nach einem manuellen Move/Resize (Anker gelöscht) macht `Enter` einen
   **frischen** Expand, nie ein No-op. Das ist gratis, weil es **derselbe** `toggleExpand` ist.
3. **Kollisionsfrei.** Heute sind **nur** Pfeile und Umschalt+Pfeile belegt; `Enter` ist frei. Slot: dieselbe
   `.onKeyEvent`-`when`-Verzweigung (Z. 479–496), neuer Zweig `Key.Enter -> { onToggleExpand(); true }`.
4. **Cross-Target.** Physische Tastatur auf Desktop/Web/Android liefert `Enter` an `.onKeyEvent`. Kein plattform-
   divergierender Modifier (Ctrl vs. Cmd), konsistent mit dem heutigen Code, der **nur** `isShiftPressed` kennt.

> **Kein `F11`** (kollidiert auf Web mit Browser-Vollbild), **kein `Space`** (scrollt), **kein Ctrl/Cmd-Combo**
> (führt eine plattformspezifische Modifier-Abfrage ein, die der Code heute nicht hat). `Enter` ist die schlankste,
> parität-treueste Wahl.

### D2 — Fokus-Adressierung (Antwort auf „wie wird der Fokus adressiert")

Die Aktion greift **am Fenster, das den Compose-Tastaturfokus hält** — dieselbe `.focusable()`-Wurzel, an der die
Pfeiltasten schon wirken. Tab-Reihenfolge bringt den Nutzer auf die Fenster-Wurzel; `.onFocusChanged { onFocus() }`
hält den **logischen** Fokus (`windows.lastOrNull()`) mit dem Tastaturfokus synchron und holt das Fenster nach vorn.
**Keine neue Fokus-Verdrahtung** nötig.

**Kritische Naht — kein Hijack des Composers:** `.onKeyEvent` an der Wurzel feuert nur für Tasten, die ein
**fokussiertes Kind nicht konsumiert** hat. Hat ein Kind-Eingabefeld (der Nachrichten-Composer) den Fokus, gehört
dessen `Enter` weiterhin dem Feld (**Nachricht senden**) — die Expand-Verknüpfung **stiehlt es nie**. Das spiegelt
exakt das heutige Verhalten der Pfeiltasten (die im Textfeld den Cursor bewegen, nicht das Fenster). `Enter`-Expand
wirkt also nur, wenn die **Fenster-Chrome/Wurzel** selbst den Fokus hat — genau dort, wo auch die Pfeiltasten wirken.

### D3 — Ansage-/`stateDescription`-Parität (Antwort auf „Parität zu CYP-241, DE+EN")

**Automatisch und ohne neuen Zustands-Key.** Beide Pfade rufen `toggleExpand` → derselbe `window_state_expanded` /
`window_state_normal`-Flip an **demselben** Wurzel-Knoten. Ein Screenreader liest nach `Enter` **wortgleich** dasselbe
wie nach dem Doppeltipp — in DE **und** EN, weil beide Keys seit CYP-241 in `values/` **und** `values-en/` existieren.
→ **0 neue Zustands-Keys.** (QA-Invariante §9-④.)

### D4 — Auffindbarkeit (ein neuer a11y-Hinweis-Key)

Damit ein Tastaturnutzer die Verknüpfung **kennt**, wird die a11y-Beschreibung des fokussierten Fensters um einen
kurzen Hinweis erweitert: **`a11y_window_expand_key_hint`** (DE+EN, siehe `-keys.md`).
Wortlaut spiegelt bewusst das **CYP-241-Zustands-Vokabular** („vergrößert und zentriert … oder stellt es zurück") —
kein „zeigt alles / alle Inhalte sichtbar" (Ehrlichkeits-Anker, §7).

### D5 — Empfohlener Begleit-Cleanup (optional, nicht blockierend)

Zwei bestehende a11y-Strings sind **hartkodiert und nur DE** (i18n-Lücke, unabhängig von CYP-245 vorhanden):
`"Agentenfenster {title}"` (Wurzel-`contentDescription`) und `"Titelleiste {title}, mit Pfeiltasten verschieben"`
(Titelleisten-`contentDescription`). Ich liefere in `-keys.md` **optionale** Keys, um beide zu i18n-isieren, damit die
**gesamte** Fenster-a11y-Beschreibung inkl. der neuen `Enter`/`Escape`-Hinweise DE+EN und kohärent ist. **Dev-Wahl**, ob im
selben Slice mitgenommen; für den Kern **nicht erforderlich** (nur die zwei Hint-Keys `a11y_window_expand_key_hint` +
`a11y_window_restore_key_hint` sind Pflicht).

---

## §2b — CYP-248: `Escape` = Restore-only (Auftraggeber-benannte Taste)

### D6 — **Escape stellt ein expandiertes Fenster zurück — nur Restore, nie Expand**

Am **fokussierten, aktuell expandierten** Fenster (Anker gültig) stellt **`Escape`** die alte Position/Größe wieder her —
instant, `stateDescription` → `window_state_normal` (DE+EN). Ist das Fenster **nicht** expandiert (kein Anker, oder Anker
durch echtes Move/Resize invalidiert), macht `Escape` **für dieses Feature nichts** — **No-op, kein toter Sprung, nie ein
versehentlicher Expand.**

**Warum eine eigene Restore-Taste neben dem `Enter`-Toggle:** `Escape` ist die kulturell verankerte „Zurück/Abbrechen"-Geste;
sie auf **Restore-only** zu binden trifft die Nutzererwartung („mach das Groß-Machen rückgängig") präziser als ein Toggle und
ist die vom Auftraggeber ausdrücklich benannte Taste. Der `Enter`-Toggle (CYP-245) bleibt das vollständige Äquivalent.

**Reuse, kein zweiter Pfad:** `Escape` ruft **denselben** CYP-241-Restore-Zweig. Weil es **nur** feuert, wenn das Fenster
bereits expandiert ist (`isExpanded == true`), ist `onToggleExpand()` dort deckungsgleich mit „Restore" — es kann per
Konstruktion **nie** expandieren. Gate: `if (isExpanded) { onToggleExpand(); true } else false`.

> **Optionale Robustheits-Verfeinerung (Dev-Wahl):** statt auf den recomposten `isExpanded`-Param zu vertrauen, einen
> dedizierten `onRestore = { if (state.isExpanded(id)) state.toggleExpand(id) }` am **Call-Site** (wo `state` autoritativ
> ist) — eliminiert die winzige Stale-Param-Kante. Für Low genügt der Param-Gate; die Verfeinerung ist notiert.

### D7 — Kollisions-Präzedenz von `Escape` (der Ehrlichkeits-Kern hier)

`Escape` ist stark überladen — es darf **kein** offenes Dialog-/Menü-/Popup-Close stehlen. Präzedenz (höchste zuerst):

1. **Offener modaler Layer** — Agent-Settings-`AlertDialog` (verifiziert `AgentSettingsPanel.kt` Z. 99–100:
   `AlertDialog(onDismissRequest = onDismiss)`), Dropdown-Menü, `Popup`, jeder `Dialog`: **fängt den Fokus** → `Escape`
   schließt diesen Layer. Der Fenster-Wurzel-`.onKeyEvent` **empfängt `Escape` gar nicht**, solange ein Modal den Fokus
   hält. → **Dialog-Close gewinnt — strukturell** (nicht durch eine Sonderregel, sondern durch Compose-Fokus-Semantik).
2. **Fokussiertes Kind-Eingabefeld** (Composer o. ä.): konsumiert es `Escape`, wirkt die Fenster-Wurzel nicht.
3. **Fokussiertes expandiertes Fenster** (kein Modal, Wurzel hat Fokus, `isExpanded == true`): `Escape` restauriert.
4. **Sonst** (nicht expandiert / Anker durch Move/Resize invalidiert): **No-op**, `Escape` **wird nicht konsumiert**
   (`return false`) → bleibt frei für alles andere. Kein toter Sprung.

> **Pflicht-Vet (nicht behaupten, beweisen):** Punkt 1 ist strukturell, muss aber per QA-Probe **bestätigt** werden —
> Settings-Dialog offen → `Escape` schließt den Dialog, Fenstergeometrie **unverändert** (§9-Invariante).

---

## §3 — Impl-Skizze (illustrativ, Dev besitzt den Code)

```kotlin
// WindowManager.kt — im BESTEHENDEN .onKeyEvent-when (Z. 479), zwei neue Zweige:
Key.Enter -> {                 // CYP-245: Toggle-Äquivalent zum Titelleisten-Doppelklick (CYP-241)
    onToggleExpand()           // identischer Pfad wie detectTapGestures(onDoubleTap) → state.toggleExpand(id)
    true
}
Key.Escape -> {                // CYP-248: Restore-only — nur wenn dieses Fenster expandiert ist
    if (isExpanded) { onToggleExpand(); true }  // isExpanded==true ⇒ toggleExpand == Restore; nie Expand
    else false                 // nicht expandiert ⇒ No-op, Escape bubbelt (Dialog/Menü/Popup gewinnt)
}
```
- **Nichts** an `toggleExpand`, `expandCentered`, den Clamps, dem transienten `expandAnchors`, an Tags oder Tokens
  ändert sich. Nur **zwei** `when`-Zweige + **zwei** a11y-Hinweis-Strings kommen dazu.
- **`isExpanded` ist bereits Param** von `FloatingWindow` (verifiziert Z. 438) und treibt schon die `stateDescription` —
  der `Escape`-Zweig liest denselben Wert; keine neue Zustands-Verdrahtung.
- **`Escape`-Bubbling ist der Sicherheits-Default:** der Zweig konsumiert **nur**, wenn dieses Fenster expandiert ist;
  in allen anderen Fällen (`false`) bleibt `Escape` für Dialog-/Menü-/Popup-Close frei (D7).
- Die Hinweis-Strings (`a11y_window_expand_key_hint` immer; `a11y_window_restore_key_hint` **nur wenn** `isExpanded`)
  werden an die a11y-Beschreibung des fokussierten Fensters gehängt (Impl-Wahl: Wurzel-`contentDescription` — der
  fokussierbare Knoten, den die Tab-Navigation erreicht; konsistent mit D5, falls der Cleanup mitgenommen wird).

---

## §7 — Ehrlichkeit (mein Kern)

- **„vergrößert und zentriert", nicht „alles sichtbar".** Der Hinweis-Wortlaut übernimmt das CYP-241-Vokabular
  verbatim. Der scrollende Event-Stream-Renderer zeigt weiterhin nur einen Ausschnitt; die Tastatur-Affordanz darf
  **keine vollständige Content-Sichtbarkeit** implizieren.
- **Nie Vollbild, nie Überlauf.** Der `Enter`-Pfad ist geometrisch identisch mit dem Maus-Pfad (`EXPAND_MARGIN` +
  `clampSizeToBounds`/`clampToBounds`). Eine Tastatur kann keine „größere" oder randlose Variante erzeugen.
- **Zustand spiegelt Anker-Präsenz, nicht Größe.** `stateDescription` sagt nach einem manuellen Move eines
  vergrößerten Fensters ehrlich „Normalgröße" (Restore nicht mehr verfügbar) — für Tastatur wie Maus identisch.
- **Kein Ansage-Doppelmund.** Genau **ein** Zustands-Signal (`stateDescription`), von beiden Modalitäten geteilt —
  keine parallele Tastatur-only-Meldung, die driften könnte.
- **Fokus-Ehrlichkeit.** `Enter`-Expand nur, wenn die Fenster-Chrome fokussiert ist; der Composer behält sein `Enter`
  („senden") — die Verknüpfung täuscht nie eine Sende-Aktion als Fenster-Aktion vor (oder umgekehrt).
- **`Escape`-Ehrlichkeit (CYP-248).** `Escape` **stiehlt nie** ein Dialog-/Menü-/Popup-Close (Präzedenz D7, strukturell
  durch Fokus-Fang des Modals) und **springt nie tot** (No-op, wenn nicht expandiert — nie ein versehentlicher Expand).
  `Escape` = **Restore-only**, nie Expand: die „Zurück"-Taste macht ausschließlich rückgängig, nie etwas Neues.
- **`Escape` advertised nur, wenn es wirkt.** Der `a11y_window_restore_key_hint` erscheint **nur, während das Fenster
  expandiert ist** — kein a11y-Versprechen einer Taste, die im Normalzustand nichts täte.

---

## §8 — Umfang & Abgrenzung (ehrlich)

- **Im Scope:** Hardware-Tastatur am fokussierten Fenster — **`Enter`** = Toggle Expand↔Restore (CYP-245),
  **`Escape`** = Restore-only (CYP-248). Desktop/Web/Android-mit-Tastatur.
- **Nicht im Scope (kein Regress):** Touch-Screenreader-Aktivierung (TalkBack-Doppeltipp) — die bestehende
  Doppeltipp-Geste + `stateDescription` bleiben unverändert. → **Optionaler Forward** (§10).
- **Kein** neuer sichtbarer Control, **kein** neuer Tag, **kein** neues Token, **keine** neue Farbe, **keine**
  neue persistierte oder geteilte Zustandsvariable. `Escape` nutzt den **bestehenden** `isExpanded`-Param + den
  CYP-241-Restore-Zweig.

---

## §9 — Invarianten (= meine UX-QA-Abnahme, EIN Satz für CYP-245 + CYP-248, 13)

**`Enter` — Toggle (CYP-245):**
1. **Geometrie-Parität:** `Enter` erzeugt exakt dieselbe Expand-/Restore-Geometrie wie der Doppeltipp (derselbe
   `toggleExpand` → `expandCentered` bzw. Anker-Restore). Nie eine andere/größere Größe.
2. **Nie Vollbild / nie Überlauf:** geerbt aus CYP-241 (`EXPAND_MARGIN` + Clamps). `Enter` kann kein Vollbild und
   keinen Viewport-Überlauf erzeugen.
3. **Toggle, kein toter Key:** zweites `Enter` restauriert; nach einem manuellen Move/Resize (Anker gelöscht) macht
   `Enter` einen **frischen** Expand — nie ein No-op.
4. **Ansage-Parität:** nach `Enter` ist `stateDescription` == der CYP-241-Wert für denselben Zustand
   (`window_state_expanded`/`_normal`), in **DE und EN**. Screenreader hört dasselbe wie beim Doppeltipp.
5. **Fokus-scoped, kein Hijack:** `Enter` expandiert **nur**, wenn die Fenster-Wurzel/Chrome den Tastaturfokus hält;
   ein fokussiertes Kind-Eingabefeld (Composer) behält sein `Enter` (senden). Die Verknüpfung stiehlt es nie —
   spiegelt das Pfeiltasten-Verhalten im Textfeld.

**`Escape` — Restore-only (CYP-248):**
6. **Restore-only, nie Expand:** `Escape` feuert **nur** wenn `isExpanded == true` und stellt dann exakt die
   Anker-Geometrie wieder her (== `Enter`-Restore == Doppeltipp-Restore). Es kann per Konstruktion **nie** expandieren.
7. **No-op ohne toten Sprung:** ist das Fenster nicht expandiert (kein Anker / Anker durch echtes Move/Resize
   invalidiert), tut `Escape` **nichts** und **wird nicht konsumiert** (`return false`) — kein Sprung, kein Expand.
8. **Kollisions-Präzedenz (Pflicht-Vet):** bei offenem Modal (Agent-Settings-`AlertDialog`, Menü, `Popup`) schließt
   `Escape` **den Modal**, nicht das Fenster — QA-Probe: Dialog offen → `Escape` schließt Dialog, **Fenstergeometrie
   unverändert**. Dialog-Close gewinnt strukturell (Fokus-Fang), muss aber bewiesen werden.
9. **`Escape`-Ansage-Parität:** nach `Escape`-Restore ist `stateDescription == window_state_normal` (DE+EN) — dasselbe
   Signal wie nach `Enter`-Restore; **ein** geteiltes Zustands-Signal, kein zweiter Mund.

**Gemeinsam (beide Tasten):**
10. **Auffindbarkeit ohne Lüge:** `a11y_window_expand_key_hint` nennt `Enter` + ehrliche Wirkung („vergrößert und
    zentriert … oder stellt zurück", nie „zeigt alles"); `a11y_window_restore_key_hint` nennt `Escape` **nur während
    das Fenster expandiert ist** (kein Versprechen einer im Normalzustand wirkungslosen Taste).
11. **Transienter Anker unangetastet:** beide Tasten nutzen dasselbe transiente `expandAnchors` (nicht persistiert)
    → kein stale Restore nach Reload (CYP-204). Keine neue persistierte Zustandsvariable.
12. **Reuse-Reinheit:** Geometrie, Tags und Tokens sind ausschließlich CYP-241; **0 neue Tags, 0 neue Tokens,
    0 Farben, 0 neue Controls.** Nur zwei Eingabe-Zweige + zwei a11y-Hinweis-Strings kommen dazu.
13. **DE+EN-Parität:** jeder neue/geänderte a11y-String existiert in `values/` **und** `values-en/`; 0 Kollision
    gg. `strings.xml` @ `e6f0882` (im Push `grep`-gegengeprüft).

---

## §10 — Optionale Forwards (nicht blockierend, PO-Call)

- **A11y-Custom-Action für Touch-Screenreader:** eine `CustomAccessibilityAction` „Vergrößern und zentrieren /
  Zurückstellen" an der Fenster-Wurzel, damit Rotor-/TalkBack-Nutzer auf Touch dieselbe Toggle-Aktion ohne physische
  Tastatur erreichen. Bräuchte 1 Label-Key (DE+EN). Sinnvolle a11y-Vervollständigung, aber eigener kleiner Slice.
- **Begleit-Cleanup D5** (i18n der 2 hartkodierten DE-only a11y-Strings) — optionaler Key-Satz in `-keys.md`.

---

## §11 — Hand-off

- **Neue Keys:** **2 Pflicht** — `a11y_window_expand_key_hint` (CYP-245, `Enter`) + `a11y_window_restore_key_hint`
  (CYP-248, `Escape`, nur-wenn-expandiert) — **+ 2 optional** (D5-Cleanup). DE+EN. Details `-keys.md`.
- **Neue Tags:** 0 (`-tags.md`). **Neue Tokens:** 0 (`-tokens.json`, reine Reuse-Referenz auf CYP-241).
- **Shared-Key-Drift:** die Keys landen in `:app:shared`-Resources → Impl + Test-Modul (CYP-7) re-syncen;
  **mit dem Impl-Slice timen** (Standard-Flag).
- **Konsument:** Dev — **CYP-245 + CYP-248 in EINEM Slice** (`Enter` + `Escape` am selben `.onKeyEvent`-Pfad), damit
  nicht zwei Pässe entstehen. Danach **UX-QA durch UIUX** gegen §9 (**13 Invarianten, ein Abnahmesatz für beide**).
- **Verlinkung:** CYP-245 + CYP-248 verlinkt mit CYP-241 (Design-Erbe: derselbe `toggleExpand`/Restore-Zweig).
