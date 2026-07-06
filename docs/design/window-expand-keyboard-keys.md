# i18n-Keys — Tastatur-Pfad Fenster-Expand/Restore (CYP-245 + CYP-248)

> Owner: UIUX-Designer · Stories **CYP-245** (`Enter`) **+ CYP-248** (`Escape`) · Stand: 2026-07-06 · Status: Vorschlag —
> Keys landen MIT dem gemeinsamen Impl-Slice (Shared-Key-Drift → mit Dev/CYP-7 timen).
> Konvention (verifiziert gg. `values/strings.xml` @ `e6f0882`): **Underscore-Realkeys**, **DE = Default** (`values/`),
> **EN** (`values-en/`), Parität Pflicht. a11y-Beschreibungs-Keys → Prefix `a11y_window_`.

---

## 1. Pflicht — neue Keys (2): a11y-Auffindbarkeit der `Enter`- und `Escape`-Verknüpfungen

| Key | Ticket | DE | EN |
|---|---|---|---|
| `a11y_window_expand_key_hint` | CYP-245 | Eingabetaste: Fenster vergrößern und zentrieren oder zurückstellen | Enter: enlarge and center the window, or restore it |
| `a11y_window_restore_key_hint` | CYP-248 | Escape: vergrößertes Fenster an seinen Platz zurückstellen | Escape: restore the enlarged window to its place |

> **Ehrlichkeits-Anker (mein Kern):**
> - `a11y_window_expand_key_hint` — bewusst **„vergrößern und zentrieren … oder zurückstellen"**, spiegelt das
>   CYP-241-Zustands-Vokabular (`window_state_expanded` = „Vergrößert und zentriert") **verbatim**. **Kein** „zeigt alles" /
>   „alle Inhalte sichtbar" (Event-Stream bleibt ein Ausschnitt, Spec §7). **Toggle-ehrlich** — nennt beide Richtungen.
> - `a11y_window_restore_key_hint` — nennt **nur Restore** („zurückstellen"), passend zur Restore-only-Semantik von
>   `Escape` (Spec §2b/D6). Kein Wort von „vergrößern" — `Escape` expandiert nie.
>
> **Platzierung:**
> - `a11y_window_expand_key_hint`: **immer** an die a11y-`contentDescription` des fokussierten Fenster-Knotens (die
>   `.focusable()`-Wurzel, die die Tab-Navigation erreicht).
> - `a11y_window_restore_key_hint`: **nur anhängen, während `isExpanded == true`** — `Escape` wird sonst nicht
>   beworben, weil es im Normalzustand nichts tut (No-op, Spec §9-Invariante 10). Kein a11y-Versprechen einer
>   wirkungslosen Taste.

---

## 2. Reuse — KEINE neuen Zustands-Keys (Ansage-Parität = automatisch)

| Reused Key (CYP-241, @ `e6f0882`) | DE | EN | Rolle in CYP-245 |
|---|---|---|---|
| `window_state_expanded` | Vergrößert und zentriert | Enlarged and centered | `stateDescription` nach `Enter`-Expand — **identisch** zum Doppeltipp |
| `window_state_normal` | Normalgröße | Normal size | `stateDescription` nach `Enter`-Restore — **identisch** zum Doppeltipp |

> **0 neue Zustands-Keys.** Weil `Enter` und Doppeltipp **denselben** `toggleExpand` aufrufen, flippt derselbe
> `stateDescription`-Wert am selben Knoten → Screenreader liest DE+EN wortgleich dasselbe. Das **ist** die
> Ansage-Parität, die CYP-241 verlangt (Spec §D3 / Invariante §9-④).

---

## 3. Optional — Begleit-Cleanup (D5, nicht blockierend): i18n der 2 hartkodierten DE-only a11y-Strings

> Heute hartkodiert & **nur DE** in `WindowManager.kt` (i18n-Lücke, unabhängig von CYP-245 vorhanden). Wenn Dev sie im
> selben Slice zieht, wird die **gesamte** Fenster-a11y-Beschreibung DE+EN und kohärent (inkl. Pflicht-Key aus §1).
> **Dev-Wahl.** Für den CYP-245-Kern **nicht erforderlich.**

| Key (Vorschlag) | ersetzt hartkodiert | DE | EN |
|---|---|---|---|
| `a11y_window_root` | `"Agentenfenster {title}"` (Wurzel-`contentDescription`, `%1$s` = Titel) | Agentenfenster %1$s | Agent window %1$s |
| `a11y_window_move_hint` | `"…, mit Pfeiltasten verschieben"` (Bewegungs-Hinweis) | Pfeiltasten verschieben, Umschalt+Pfeiltasten ändern die Größe | Arrow keys move, Shift+arrow keys resize |

> **Kohärente Gesamt-Beschreibung** (falls Cleanup gezogen): `a11y_window_root` + `a11y_window_move_hint` +
> `a11y_window_expand_key_hint` → „Agentenfenster {title}. Pfeiltasten verschieben, Umschalt+Pfeiltasten ändern die
> Größe. Eingabetaste vergrößert und zentriert oder stellt zurück." — nennt alle drei Tastatur-Affordanzen ehrlich,
> DE+EN. `a11y_window_move_hint` dokumentiert zusätzlich das bislang **unbeschriebene** Umschalt+Pfeil-Resize (kleiner
> a11y-Gewinn nebenbei).

---

## Zähl-/Validierungs-Block (Selbst-Validierung)

- **Neue Keys Pflicht: 2** — `a11y_window_expand_key_hint` (CYP-245), `a11y_window_restore_key_hint` (CYP-248).
  **DE+EN-Parität 2/2.**
- **Neue Keys optional (D5-Cleanup): 2** — `a11y_window_root` (1 Arg `%1$s`), `a11y_window_move_hint` (0 Args). DE+EN 2/2.
- **Argument-Keys (`%…$s`):** 0 im Pflicht-Set; 1 im optionalen Set (`a11y_window_root`, positional `%1$s`).
- **Reuse-gegen-Code verifiziert @ `e6f0882`:** `window_state_expanded`/`window_state_normal` existieren bereits in
  `values/` **und** `values-en/` (CYP-241). **0 neue Zustands-Keys.**
- **0 Kollision** gg. `strings.xml`/`values-en` @ `e6f0882` (Prefix `a11y_window_*` neu für die drei Vorschläge; im
  Push `grep`-gegengeprüft).
- **Kein Secret in Keys:** keine E-Mail/Token/Endpoints; neutrale a11y-Beschreibung.
- **Ehrlichkeit:** Hint = „vergrößern & zentrieren / zurückstellen" (kein „alles sichtbar"); Ansage-Parität über die
  **bestehenden** Zustands-Keys, nicht über eine neue Tastatur-only-Meldung.
- **⚠ Shared-Key-Drift:** landen in `:app:shared`-Resources → CYP-245-Impl + Test-Modul (CYP-7) re-syncen.
  **Mit dem Impl-Slice timen.**
