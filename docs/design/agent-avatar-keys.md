# i18n-Keys — Per-Agent-Avatar-Picker (CYP-212, DESIGN-AHEAD)

> Owner: UIUX-Designer · Story **CYP-212** · Stand: 2026-07-04 · Status: Vorschlag / DESIGN-AHEAD — **Key-Sync-Punkt** (PO
> koordiniert CYP-7; Keys landen MIT dem späteren CYP-212-Impl-Slice — Shared-Key-Drift; shipt nicht vor CYP-208).
> Konvention (verifiziert gg. `values/strings.xml`, develop `1988a15`): **Underscore-Realkeys** (keine Punkte), Argumente
> `%1$s`. **DE = Default** (`values/`), **EN** (`values-en/`). Parität Pflicht. Prefix `agent_avatar_` / `a11y_agent_avatar_`.

## Neue Keys (13)

### Avatar-Sektion + Aktionen (§3)
| Key | DE | EN |
|---|---|---|
| `agent_avatar_section` | Avatar | Avatar |
| `agent_avatar_preset_label` | Vorlagen | Presets |
| `agent_avatar_upload` | Bild hochladen | Upload image |
| `agent_avatar_shuffle` | Variante | Shuffle |
| `agent_avatar_remove` | Entfernen | Remove |
| `agent_avatar_crop_hint` | Wird mittig quadratisch zugeschnitten | Cropped to a centered square |

> `agent_avatar_shuffle` = optionaler Seed-Reroll innerhalb eines Styles (§-Ask 1). `agent_avatar_crop_hint` = INFO-Ton
> (ehrlich: der Zuschnitt ist mittig-quadratisch, server-seitig). `agent_avatar_remove` = neutral (zurück auf Initialen/Farbe).

### Upload-Security-Fehler (§3.3 / §7 — ehrlich, server-autoritativ)
| Key | DE | EN |
|---|---|---|
| `agent_avatar_upload_type_error` | Nur PNG, JPG oder WebP – kein SVG | Only PNG, JPG or WebP – no SVG |
| `agent_avatar_upload_size_error` | Bild zu groß (max %1$s) | Image too large (max %1$s) |
| `agent_avatar_upload_generic_error` | Upload fehlgeschlagen – erneut versuchen | Upload failed – try again |

> Alle drei = ERROR-Ton. `..._type_error` nennt **explizit „kein SVG"** (Script-/XSS-Vektor). `%1$s` in `..._size_error` = das
> vom Backend gesetzte Limit. Der **Server** validiert autoritativ (Magic-Bytes) — die UI spiegelt die ehrliche Ablehnung.

### a11y
| Key | DE | EN |
|---|---|---|
| `a11y_agent_avatar_preset` | Avatar-Vorlage %1$s | Avatar preset %1$s |
| `a11y_agent_avatar_upload` | Bild hochladen (PNG, JPG oder WebP) | Upload image (PNG, JPG or WebP) |
| `a11y_agent_avatar_current` | Aktueller Avatar: %1$s | Current avatar: %1$s |
| `a11y_agent_avatar_remove` | Avatar entfernen | Remove avatar |

> `a11y_agent_avatar_preset` „%1$s" = Style-Name (bottts…); Friendly-Labels = §-Ask 5. `a11y_agent_avatar_current` „%1$s" =
> die effektive Fallback-Stufe (Bild/Vorlage/Initialen) → der Avatar-Zustand ist ohne Farbe/Bild hörbar.

---

## Reuse (keine neuen Keys — gg. Code verifiziert @ `1988a15`)

| Reused Key | Zweck in CYP-212 |
|---|---|
| `agent_save` / `agent_cancel` | Panel-Aktionen (Avatar-Sektion im CYP-209-Panel) |
| `workspace_operator_only` (GATED) | Operator-Gate der Avatar-Edits (reuse-Muster) |

> **Initialen-Fallback** nutzt `initialsOf(name)` (Code, kein Key). **Farb-Ring** = CYP-209-`borderColor` (Token, kein Key).

---

## Zähl-/Validierungs-Block (Selbst-Validierung)

- **Neue Keys gesamt: 13** — 9 `agent_avatar_*` + 4 `a11y_agent_avatar_*`. **DE+EN-Parität 13/13.**
- **Argument-Keys (`%1$s`): 3** — `agent_avatar_upload_size_error`, `a11y_agent_avatar_preset`, `a11y_agent_avatar_current`.
- **0 Kollision** gg. `strings.xml`/`values-en` @ `1988a15` (Prefix `agent_avatar_`/`a11y_agent_avatar_` neu; im Push
  `grep`-gegengeprüft).
- **Reuse (keine neuen Keys):** Save/Cancel, Operator-Gate; Initialen-Helper + Farb-Ring aus Code/Token.
- **Kein Secret in Keys:** keine E-Mail/Token/Endpoints; Style-Namen/Seed = neutrale Anzeige-Werte.
- **⚠ Shared-Key-Drift:** landen in `:app:shared`-Resources → konsumierendes Modul (CYP-212-Impl + Test-Modul CYP-7) muss
  re-syncen. **Mit dem CYP-212-Impl-Slice timen** (DESIGN-AHEAD — nicht isoliert mergen).
