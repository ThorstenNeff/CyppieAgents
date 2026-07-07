# Design-Pass — CYP-281 Kompakter Theme-Toggle auf schmalen Breiten

> **Status:** DESIGN-PASS (kein Bau) · Owner: UIUX-Designer · PO-getriggert 2026-07-07 (Devs Sweep-#3-Fund, R4-Selbst-Flag) · fällt in den R4-Redesign-Abnahme-Kontext.
> **Grounding:** `project/ProjectSwitcherBar.kt` (aktiv-Label `weight(1f)` + `Ellipsis`, Z.123–133; „Projekte ▾" CYP-233 Z.139), `ui/ThemeModeToggle.kt` (R3-Toggle „Thema: <mode> ▾", TextButton, testTag `themeToggle.menu`, a11y `a11y_theme_menu`), `AgentShell.kt` (Toggle als Trailing-Slot Z.268; `BoxWithConstraints`/`maxWidth` responsive-Idiom Z.531). Verifiziert gegen develop `e3bc79c`.

---

## §0 — Problem

Auf dem Phone (`ProjectSwitcherBar` ≈ 360dp) fressen der R3-Toggle **„Thema: System ▾"** (≈130–150dp, voller Wortlaut) + **„Projekte ▾"** (≈90dp) + Spacing/Padding (~24dp) den intrinsischen Platz → das **Aktiv-Projekt-Label** (`weight(1f)`, das primäre Kontext-Label) bekommt nur ~120dp und **ellipsized auf „Aktives Pro…"**. Kein Clip, aber das wichtigste Label ist kaum lesbar. Das Theme (client-lokale Präferenz) verdrängt den Projekt-Kontext — falsche Priorität.

---

## §1 — Entscheidung: Toggle wird **icon-only auf schmalen Breiten** (Reuse des bestehenden Toggles)

Auf schmalen Breiten rendert der **bestehende** `ThemeModeToggle` **icon-only**: ein **modus-Glyph + ▾**, **ohne** das Wort „Thema:" und **ohne** das Modus-Wort. Das gibt ~90–110dp an das `weight(1f)`-Aktiv-Label zurück → es liest wieder voll. **Kein zweiter Toggle** — nur eine kompakte Render-Variante (Anti-Divergenz).

**Warum nicht Wrap/2. Zeile** (die PO-Alternative): die Bar ist eine **schlanke Chrome** (die Rollen-Zeile liegt schon darüber); ein Umbruch macht sie höher/schwerer. Icon-only hält die Bar **einzeilig** und schlank → die sauberere Wahl. Aktiv-Label bleibt `weight(1f)`.

---

## §2 — Kompakte Render-Spezifikation

| Aspekt | Voll (≥ Schwelle) | Kompakt (< Schwelle) |
|---|---|---|
| Sichtbarer Inhalt | „Thema: {System/Hell/Dunkel} ▾" | **{Modus-Glyph} ▾** |
| Modus-Glyph (form-distinkt, **nie Farbe allein**, WCAG 1.4.1) | — | **System = ◐ · Hell = ○ · Dunkel = ●** (leer/halb/voll = shape-eindeutig, monochrom, konsistent mit dem ●-Vokabular der App) |
| Chevron ▾ | ja | **ja** (bleibt Menü-Affordanz — Discoverability wie „Projekte ▾" CYP-233) |
| Dropdown | System/Hell/Dunkel + ● Aktiv-Marker | **identisch** (unverändert) |
| Touch-Target | — | **≥ 48dp** (IconButton-Mindestmaß; Glyph klein, Target voll) |

**Glyph-Semantik:** ○ = hell/leer, ● = dunkel/voll, ◐ = System/folgt (halb) — eine **Helligkeits-Metapher**, form-eindeutig ohne Farbe. (Falls das Team ☀/☾/⚙ bevorzugt: auch ok, solange monochrom + shape-distinkt; ich empfehle ○/●/◐ wegen Konsistenz mit dem bestehenden ●-Marker-Vokabular.)

---

## §3 — a11y bleibt VOLL (der Kern — icon-only darf den Namen NICHT verlieren)

- **Gleicher `contentDescription`** wie voll: `a11y_theme_menu` = „Design-Auswahl, aktuell: {Modus}". Der Screen-Reader hört **denselben** vollen Zustand — nur der *sichtbare* Text schrumpft, die a11y-Nutzlast bleibt. (Icon-only ohne accessible name wäre ein a11y-Fail.)
- **Gleicher testTag** `themeToggle.menu` (kein neuer Tag → kein QA/CYP-7-Drift).
- **Gleicher `onChange`** + Dropdown + `a11y_theme_mode`-Items — 0 Verhaltensänderung, nur Button-Rendering.

---

## §4 — Trigger (Reuse des responsive-Idioms, kein neuer Magic-Breakpoint)

Der Toggle wird als Trailing-Slot in `AgentShell` erzeugt (Z.268). **Empfehlung:** die Kompakt-Entscheidung dort fällen, wo die Breite schon gemessen wird — das bestehende `BoxWithConstraints`/`maxWidth`-Idiom der App (AgentShell Z.531) — und `compact: Boolean` an `ThemeModeToggle` reichen. **Schwelle:** icon-only, wenn die Bar-Breite unter der Phone-Klasse liegt (~< 400dp) — **exakten Wert gegen die bestehenden CYP-156/158/159-Breakpoints abgleichen** (Reuse einer vorhandenen Compact-Klasse statt neuer Konstante; §6-Dep). Alternativ ein `BoxWithConstraints` lokal um die Bar (misst die tatsächlich verfügbare Breite, robust auch bei Tablet-Splitview).

---

## §5 — Invarianten (= UX-QA-Abnahme, 6)

1. **Aktiv-Projekt-Label lesbar** — auf 360dp-Phone kein „Aktives Pro…"-Ellipsis mehr; das primäre Kontext-Label gewinnt den zurückgegebenen Platz (`weight(1f)`).
2. **Toggle bleibt entdeckbar + bedienbar** — ▾-Chevron sichtbar; Touch-Target ≥ 48dp; öffnet dasselbe Dropdown.
3. **a11y voll erhalten** — `contentDescription` = voller Modus-Name in beiden Render-Modi; Screen-Reader-Parität.
4. **Farbe nie alleiniger Träger** — Modus-Glyph ist form-distinkt (○/●/◐), WCAG 1.4.1.
5. **Sauberer Übergang** — kein „halber" Zustand am Breakpoint (voll ↔ kompakt schaltet an EINER Schwelle, kein Flackern/Überlappen).
6. **0 Drift** — 0 neue `strings.xml` (Glyphe = Symbol, a11y reused), 0 neue testTags (`themeToggle.menu` reused), 0 neue Surface — reine kompakte Render-Variante des bestehenden Toggles. Maritim folgt automatisch (colorScheme).

---

## §6 — Offene Punkte / Hand-off

1. **Dep (Dev/Design-Detail):** exakte Kompakt-Schwelle gegen die bestehenden Responsive-Breakpoints (CYP-156/158/159) abgleichen — Reuse einer vorhandenen Compact-Klasse bevorzugt; ich nenne ~400dp als Richtwert, finalen Wert bestätigt Dev gegen die vorhandene Plumbing.
2. **§-Ask (nicht-blockierend):** Glyph-Set ○/●/◐ (empfohlen, konsistent) vs. ☀/☾/⚙ — Team-Call; beide erfüllen shape-distinkt + monochrom.
3. **Hand-off:** kein Bau — Design-Pass; Dev implementiert die `compact`-Variante am bestehenden `ThemeModeToggle` + den Trigger in `AgentShell`. **UX-QA:** die 6 §5-Invarianten (Kern = Aktiv-Label lesbar auf Phone + a11y-Name voll erhalten), gerendert light+dark, im R4-Nachgang oder als eigener kleiner Pass. Fold in R4-Kontext (Dev flaggte es als R4-Selbst-Flag).
