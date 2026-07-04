# i18n-Keys — Per-Agent-Customization (CYP-209)

> Owner: UIUX-Designer · Story **CYP-209** · Stand: 2026-07-04 · Status: Vorschlag — **Key-Sync-Punkt** (PO koordiniert CYP-7
> zwischen UIUX/Dev/Tester; Keys landen MIT dem konsumierenden CYP-211-Slice — Shared-Key-Drift).
> Konvention (verifiziert gg. `app/shared/src/commonMain/composeResources/values/strings.xml`, develop `56567a1`):
> **Underscore-Realkeys** (keine Punkte), Argumente positional `%1$s`. **DE = Default** (`values/`), **EN** (`values-en/`).
> Parität Pflicht. Prefix `agent_` / `a11y_agent_` — Erweiterung des bestehenden Agenten-Katalogs (CYP-14/CYP-86/88).

## Neue Keys (14)

### Panel-Rahmen + Identität (§4.1)
| Key | DE | EN |
|---|---|---|
| `agent_settings_title` | „%1$s" anpassen | Customize %1$s |
| `agent_display_name_label` | Anzeigename | Display name |
| `agent_id_stable_label` | ID (fest) | ID (fixed) |

> `agent_display_name_label` = **„Anzeigename"** (bewusst nicht „Name") — ehrlich: es ist ein **Label**, `id` ist die Identität.
> `agent_id_stable_label` beschriftet den **read-only** `id` daneben (Ehrlichkeits-Anker, §7.1).

### Farbe — Palette + custom-hex (§4.2)
| Key | DE | EN |
|---|---|---|
| `agent_color_section` | Farbe | Color |
| `agent_color_palette_label` | Palette | Palette |
| `agent_color_custom_label` | Eigener Farbwert (Hex) | Custom color (hex) |
| `agent_color_custom_invalid` | Ungültiger Hex-Wert (z. B. #3B82F6) | Invalid hex value (e.g. #3B82F6) |
| `agent_color_adjusted_hint` | Für Lesbarkeit angepasst (Kontrast) | Adjusted for readable contrast |
| `agent_color_status_like_hint` | Ähnelt einer Status-Farbe – Farbe ist Identität, kein Status | Resembles a status color – color is identity, not status |

> `agent_color_custom_invalid` = ERROR-Ton (Format-Guard). `agent_color_adjusted_hint` = INFO-Advisory (ehrlich: effektive Farbe
> weicht ab, §5.6). `agent_color_status_like_hint` = **optional** (§-Ask 4) — INFO-Advisory, wenn eine Custom-Farbe einer
> CYP-12-Status-Hue ähnelt (kein Hard-Block; „Farbe ist Identität, kein Status").

### a11y (Farbe/Marker nie alleiniger Träger)
| Key | DE | EN |
|---|---|---|
| `a11y_agent_settings_open` | Einstellungen für %1$s öffnen | Open settings for %1$s |
| `a11y_agent_display_name` | Anzeigename eingeben | Enter display name |
| `a11y_agent_color_custom` | Eigenen Hex-Farbwert eingeben | Enter custom hex color |
| `a11y_agent_color_swatch` | Farbe %1$s | Color %1$s |
| `a11y_agent_color_preview` | Farbvorschau: %1$s | Color preview: %1$s |

> `a11y_agent_color_swatch` „Farbe %1$s" (%1$s = Swatch-Index 1–8) + `selected`-Semantik am gewählten Swatch → Auswahl ohne
> Farbe hörbar. `a11y_agent_settings_open` = contentDescription des Titelbar-⋮-Buttons.

---

## Reuse (keine neuen Keys — gg. Code verifiziert @ `56567a1`)

| Reused Key | Zweck in CYP-209 |
|---|---|
| `agent_add_persona_label` „Persona / CLAUDE.md" | Persona-Feld-Label (§4.4) — **kein** neuer Persona-Key |
| `a11y_agent_add_persona` | Persona-Feld-a11y |
| `agent_edit_effect_hint` „Gespeichert. Wirkt erst beim nächsten Start …" | **der** Restart-Hinweis für CLAUDE.md (EFFECT_DEFERRED) — kein zweiter Restart-Text (§1 Anti-Divergenz) |
| `agent_save` „Speichern" / `agent_cancel` „Abbrechen" | Panel-Aktionen |
| `workspace_operator_only` „Nur der Operator kann das ändern" | Operator-Gate-Hinweis (GATED), reused wie in `AgentManagementPanel` |
| `agent_role_po` / `agent_role_worker` | Rollen-Label (unangetastet — Rolle ≠ Anzeigename ≠ Farbe) |

> **Ehrlichkeits-Trennung:** Name/Farbe = **sofort** → **kein** `agent_edit_effect_hint`. Nur die **Persona** trägt den
> Restart-Hinweis → die „sofort vs. deferred"-Grenze ist sichtbar (§7.3).

---

## Zähl-/Validierungs-Block (Selbst-Validierung)

- **Neue Keys gesamt: 14** — 9 `agent_*` + 5 `a11y_agent_*`. **DE+EN-Parität 14/14.**
- **Argument-Keys (`%1$s`): 4** — `agent_settings_title`, `a11y_agent_settings_open`, `a11y_agent_color_swatch`,
  `a11y_agent_color_preview` (DE+EN gleiche Argument-Zahl).
- **0 Kollision** gg. `strings.xml`/`values-en` @ `56567a1` (im Push via `grep` gegengeprüft; kein bestehender
  `agent_display_name_label`/`agent_color_*`/`agent_settings_*`/`a11y_agent_color_*`/`a11y_agent_settings_open`).
- **Reuse (keine neuen Keys):** Persona-Label/-a11y, Restart-Hinweis, Save/Cancel, Operator-Gate, Rollen-Label.
- **Kein Secret in Keys:** keine E-Mail/Token/Endpoints; `id`/Name/Farbe/Persona = neutrale Anzeige-Werte.
- **⚠ Shared-Key-Drift:** landen in `:app:shared`-Resources → konsumierendes Modul (CYP-211-Impl + Test-Modul CYP-7) muss
  re-syncen. **Lieferung mit dem CYP-211-Slice timen** (PO koordiniert).
