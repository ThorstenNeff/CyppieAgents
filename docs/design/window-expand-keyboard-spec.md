# Design-Spec — Tastatur-Äquivalent für Fenster-Expand/Restore (CYP-245)

> Owner: UIUX-Designer · Story **CYP-245** (Low) · Epic: Desktop/Fenster-Manager · Stand: 2026-07-06 · Status: Vorschlag
> **Grounded gegen** `origin/develop e6f0882` (`WindowManager.kt` `.onKeyEvent`/`.focusable`/`detectTapGestures`, `WindowManagerState.toggleExpand`, `WindowTestTags`, `values*/strings.xml`).
> **Reiner `commonMain`-Fenster-Manager**, keine Server-/Protokoll-Änderung. Speist Dev CYP-245; UX-QA danach UIUX. Größe: **S**.
> **Schließt** meine offene CYP-241-UX-QA-Empfehlung (Tastatur-Äquivalent zur Titelleisten-Doppelklick-Geste, damals nicht-blockierend vertagt).

---

## §0 — Ziel in einem Satz

Was der **Doppelklick auf die Titelleiste** seit CYP-241 tut (Fenster **vergrößern & zentrieren** ↔ **zurückstellen**),
muss ein **Tastaturnutzer am fokussierten Fenster** gleichwertig auslösen können — **dieselbe Aktion, dieselbe Geometrie,
dieselbe Ansage**, nur eine andere Eingabemodalität.

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
**gesamte** Fenster-a11y-Beschreibung inkl. des neuen `Enter`-Hinweises DE+EN und kohärent ist. **Dev-Wahl**, ob im
selben Slice mitgenommen; für den CYP-245-Kern **nicht erforderlich** (nur der eine `Enter`-Hint-Key ist Pflicht).

---

## §3 — Impl-Skizze (illustrativ, Dev besitzt den Code)

```kotlin
// WindowManager.kt — im BESTEHENDEN .onKeyEvent-when (Z. 479), neuer Zweig:
Key.Enter -> {            // CYP-245: Tastatur-Äquivalent zum Titelleisten-Doppelklick (CYP-241)
    onToggleExpand()     // identischer Pfad wie detectTapGestures(onDoubleTap) → state.toggleExpand(id)
    true
}
```
- **Nichts** an `toggleExpand`, `expandCentered`, den Clamps, dem transienten `expandAnchors`, an Tags oder Tokens
  ändert sich. Nur ein `when`-Zweig + ein a11y-Hinweis-String kommen dazu.
- Der Hinweis-String (`a11y_window_expand_key_hint`) wird an die a11y-Beschreibung des fokussierten Fensters gehängt
  (Impl-Wahl: an die Wurzel-`contentDescription` anfügen — das ist der fokussierbare Knoten, den die Tab-Navigation
  erreicht; konsistent mit D5, falls der Cleanup mitgenommen wird).

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

---

## §8 — Umfang & Abgrenzung (ehrlich)

- **Im Scope:** Hardware-Tastatur-Äquivalent (Desktop/Web/Android-mit-Tastatur) zum Maus-Doppelklick.
- **Nicht im Scope (kein Regress):** Touch-Screenreader-Aktivierung (TalkBack-Doppeltipp) — die bestehende
  Doppeltipp-Geste + `stateDescription` bleiben unverändert. → **Optionaler Forward** (§10).
- **Kein** neuer sichtbarer Control, **kein** neuer Tag, **kein** neues Token, **keine** neue Farbe, **keine**
  neue persistierte oder geteilte Zustandsvariable.

---

## §9 — Invarianten (= meine UX-QA-Abnahme, 9)

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
6. **Auffindbarkeit ohne Lüge:** der a11y-Hinweis nennt Taste **und** ehrliche Wirkung („vergrößert und zentriert …
   oder stellt zurück") — nicht „zeigt alles". Wortlaut = CYP-241-Vokabular.
7. **Transienter Anker unangetastet:** der Tastaturpfad nutzt dasselbe transiente `expandAnchors` (nicht persistiert)
   → kein stale Restore nach Reload (CYP-204). Keine neue persistierte Zustandsvariable.
8. **Reuse-Reinheit:** Geometrie, Tags und Tokens sind ausschließlich CYP-241; **0 neue Tags, 0 neue Tokens,
   0 Farben, 0 neue Controls.** Nur eine Eingabemodalität + ein a11y-Hinweis-String kommen dazu.
9. **DE+EN-Parität:** jeder neue/geänderte a11y-String existiert in `values/` **und** `values-en/`; 0 Kollision
   gg. `strings.xml` @ `e6f0882` (im Push `grep`-gegengeprüft).

---

## §10 — Optionale Forwards (nicht blockierend, PO-Call)

- **A11y-Custom-Action für Touch-Screenreader:** eine `CustomAccessibilityAction` „Vergrößern und zentrieren /
  Zurückstellen" an der Fenster-Wurzel, damit Rotor-/TalkBack-Nutzer auf Touch dieselbe Toggle-Aktion ohne physische
  Tastatur erreichen. Bräuchte 1 Label-Key (DE+EN). Sinnvolle a11y-Vervollständigung, aber eigener kleiner Slice.
- **Begleit-Cleanup D5** (i18n der 2 hartkodierten DE-only a11y-Strings) — optionaler Key-Satz in `-keys.md`.

---

## §11 — Hand-off

- **Neue Keys:** 1 Pflicht (`a11y_window_expand_key_hint`) + 2 optional (D5-Cleanup). DE+EN. Details `-keys.md`.
- **Neue Tags:** 0 (`-tags.md`). **Neue Tokens:** 0 (`-tokens.json`, reine Reuse-Referenz auf CYP-241).
- **Shared-Key-Drift:** die Keys landen in `:app:shared`-Resources → CYP-245-Impl + Test-Modul (CYP-7) re-syncen;
  **mit dem Impl-Slice timen** (Standard-Flag).
- **Konsument:** Dev CYP-245 (Fenster-Manager). Danach **UX-QA durch UIUX** gegen §9 (9 Invarianten) = die Abnahme.
