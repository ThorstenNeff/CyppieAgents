# i18n-Keys — Titelleisten-Expand/Restore (CYP-241)

> Owner: UIUX-Designer · Story **CYP-241** · Stand: 2026-07-05 · Status: Vorschlag — Keys landen MIT dem CYP-241-Impl-Slice
> (Shared-Key-Drift → mit Dev/CYP-7 timen).
> Konvention (verifiziert gg. `values/strings.xml`, develop `328e7ed`): **Underscore-Realkeys**, keine Argumente. **DE = Default**
> (`values/`), **EN** (`values-en/`). Parität Pflicht. Prefix `window_state_`.

## Neue Keys (2) — `stateDescription` des Fensters (Expand-Zustand)

| Key | DE | EN |
|---|---|---|
| `window_state_expanded` | Vergrößert und zentriert | Enlarged and centered |
| `window_state_normal` | Normalgröße | Normal size |

> Beide sind der `stateDescription`-Wert des Fenster-/Titelleisten-Knotens (a11y + QA-Assertion). Er spiegelt die **Restore-Anker-Präsenz**,
> nicht „ist groß": nach einem Drag eines vergrößerten Fensters (Anker gelöscht) fällt der Zustand ehrlich auf `window_state_normal`
> zurück — Restore ist dann nicht mehr verfügbar.
>
> **Ehrlichkeits-Anker (mein Kern):** bewusst **„vergrößert und zentriert"**, **nicht** „volle Größe" / „alle Inhalte sichtbar". Der
> scrollende Event-Stream-Renderer zeigt weiterhin nur einen Ausschnitt; die Copy darf keine vollständige Content-Sichtbarkeit
> implizieren (siehe Spec §0/§1/§7).

---

## Reuse (keine neuen Keys — gg. Code verifiziert @ `328e7ed`)
| Reused Element | Zweck in CYP-241 |
|---|---|
| `WindowTestTags.titleBar(id)` (`window.<id>.titlebar`) contentDescription „Titelleiste …, mit Pfeiltasten verschieben" | bleibt — Doppelklick sitzt auf derselben Titelleiste; der bestehende a11y-Text ist unverändert korrekt |

> **Optionales Tastatur-Äquivalent (Spec §7, a11y-Parität):** falls in der Impl mitgenommen, bräuchte es **keinen** neuen sichtbaren
> String (Geste/Tastenkürzel), höchstens einen Hilfetext — dann hier als Nachtrag ergänzen. Nicht blockierend.

---

## Zähl-/Validierungs-Block (Selbst-Validierung)
- **Neue Keys gesamt: 2** — `window_state_expanded`, `window_state_normal`. **DE+EN-Parität 2/2.**
- **Argument-Keys (`%…$s`): 0.**
- **0 Kollision** gg. `strings.xml`/`values-en` @ `328e7ed` (Prefix `window_state_*` neu; im Push `grep`-gegengeprüft).
- **Kein Secret in Keys:** keine E-Mail/Token/Endpoints; neutrale Zustands-Beschreibung.
- **Ehrlichkeit:** Copy = „vergrößert & zentriert" (kein „alles sichtbar"); `stateDescription` = Anker-Präsenz (Restore-Verfügbarkeit),
  nicht Fenstergröße.
- **⚠ Shared-Key-Drift:** landen in `:app:shared`-Resources → CYP-241-Impl + Test-Modul (CYP-7) re-syncen. **Mit dem Impl-Slice timen.**
