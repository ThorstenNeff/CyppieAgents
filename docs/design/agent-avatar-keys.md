# i18n-Keys — Per-Agent-Avatar-Picker (CYP-214)

> Owner: UIUX-Designer · Story **CYP-214** (Feature CYP-212; Epic CYP-208 gemergt) · Stand: 2026-07-05 · Status: **final** —
> **Key-Sync-Punkt** (PO koordiniert CYP-7; Keys landen MIT dem CYP-216-Impl-Slice — Shared-Key-Drift).
> Konvention (verifiziert gg. `values/strings.xml`, develop `c4f2d53`): **Underscore-Realkeys** (keine Punkte), Argumente
> `%1$s`. **DE = Default** (`values/`), **EN** (`values-en/`). Parität Pflicht. Prefix `agent_avatar_` / `a11y_agent_avatar_`.

## Neue Keys (22)

### Avatar-Sektion + Aktionen (§3)
| Key | DE | EN |
|---|---|---|
| `agent_avatar_section` | Avatar | Avatar |
| `agent_avatar_preset_label` | Vorlagen | Presets |
| `agent_avatar_upload` | Bild hochladen | Upload image |
| `agent_avatar_shuffle` | Variante | Shuffle |
| `agent_avatar_remove` | Entfernen | Remove |
| `agent_avatar_crop_hint` | Wird mittig quadratisch zugeschnitten | Cropped to a centered square |

> `agent_avatar_shuffle` = Seed-Reroll innerhalb eines Styles (**MVP**, §-Ask 1 bestätigt). `agent_avatar_crop_hint` = INFO-Ton
> (ehrlich: der Zuschnitt ist mittig-quadratisch, server-seitig). `agent_avatar_remove` = neutral (zurück auf Initialen/Farbe).

### Friendly Style-Labels (§6 — sichtbar + a11y; rohe DiceBear-Namen bleiben intern)
| Key | DE | EN |
|---|---|---|
| `agent_avatar_style_bottts` | Roboter | Robots |
| `agent_avatar_style_avataaars` | Charaktere | Characters |
| `agent_avatar_style_adventurer` | Abenteurer | Adventurers |
| `agent_avatar_style_big_smile` | Frohe Gesichter | Happy Faces |
| `agent_avatar_style_fun_emoji` | Emojis | Emojis |

> Key-Name nutzt **Underscore** (`big_smile`/`fun_emoji`), der Style-**Key/Tag** bleibt hyphen-basiert (`big-smile`/`fun-emoji`).
> Das Friendly-Label ist der einzige angezeigte Style-Name; der rohe DiceBear-Bezeichner ist Style-Key/Seed (nie angezeigt).

### Credits / Attribution (§3.4 — PO-Entscheid: alle 5 + Credits-Fläche)
| Key | DE | EN |
|---|---|---|
| `agent_avatar_credits` | Bild-Vorlagen · Credits | Avatar presets · Credits |
| `agent_avatar_credit_line` | %1$s von %2$s — %3$s | %1$s by %2$s — %3$s |
| `agent_avatar_credit_modified` | bearbeitet | modified |

> `agent_avatar_credit_line` „%1$s von %2$s — %3$s": %1$s = Friendly-Label, %2$s = Artist, %3$s = **Lizenzname** (Daten aus
> `tokens.json → styles[]`; Lizenzname verlinkt die Lizenz-URL). `agent_avatar_credit_modified` „(bearbeitet)" wird **nur** an die
> **CC-BY-Zeilen** angehängt → erfüllt CC-BYs Änderungs-Hinweis (Derivate). Lizenznamen/Artist/URLs sind **Daten** (Eigennamen),
> keine übersetzbaren Keys.

### Upload-Security-Fehler (§3.3 / §8 — ehrlich, server-autoritativ)
| Key | DE | EN |
|---|---|---|
| `agent_avatar_upload_type_error` | Nur PNG oder JPG – kein SVG | Only PNG or JPG – no SVG |
| `agent_avatar_upload_size_error` | Bild zu groß (max %1$s) | Image too large (max %1$s) |
| `agent_avatar_upload_generic_error` | Upload fehlgeschlagen – erneut versuchen | Upload failed – try again |

> Alle drei = ERROR-Ton. `..._type_error` nennt **explizit „kein SVG"** (Script-/XSS-Vektor). `%1$s` in `..._size_error` = das
> vom Backend gesetzte Limit. Der **Server** validiert autoritativ (Magic-Bytes) — die UI spiegelt die ehrliche Ablehnung.
> **WebP entfällt** (Backend/CYP-215 ohne nativen Decoder → kleinere Angriffsfläche): die Texte nennen daher nur **PNG/JPG** —
> ehrlich = kein Format anbieten, das der Server ablehnt. Zwei Gründe: **SVG** = verboten (XSS); **WebP** = nicht unterstützt.

### a11y
| Key | DE | EN |
|---|---|---|
| `a11y_agent_avatar_preset` | Avatar-Vorlage %1$s | Avatar preset %1$s |
| `a11y_agent_avatar_upload` | Bild hochladen (PNG oder JPG) | Upload image (PNG or JPG) |
| `a11y_agent_avatar_current` | Aktueller Avatar: %1$s | Current avatar: %1$s |
| `a11y_agent_avatar_remove` | Avatar entfernen | Remove avatar |
| `a11y_agent_avatar_credits` | Lizenz-Credits der Bild-Vorlagen | Avatar preset license credits |

> `a11y_agent_avatar_preset` „%1$s" = **Friendly-Label** (§6, §-Ask 5 bestätigt — nicht der rohe Style-Name).
> `a11y_agent_avatar_current` „%1$s" = die effektive Fallback-Stufe (Bild/Vorlage/Initialen) → der Avatar-Zustand ist ohne
> Farbe/Bild hörbar.

---

## Reuse (keine neuen Keys — gg. Code verifiziert @ `c4f2d53`)

| Reused Key | Zweck in CYP-214 |
|---|---|
| `agent_save` / `agent_cancel` | Panel-Aktionen (Avatar-Sektion im CYP-209-Panel) |
| `workspace_operator_only` (GATED) | Operator-Gate der Avatar-Edits (reuse-Muster) |

> **Initialen-Fallback** nutzt `initialsOf(name)` (Code, kein Key). **Farb-Ring** = CYP-211-`borderColor` (Token, kein Key).

---

## Zähl-/Validierungs-Block (Selbst-Validierung)

- **Neue Keys gesamt: 22** — 17 `agent_avatar_*` (6 Sektion/Aktion + 5 Style-Labels + 3 Credits + 3 Upload-Fehler) + 5
  `a11y_agent_avatar_*`. **DE+EN-Parität 22/22.**
- **Argument-Keys (`%1$s`…): 4** — `agent_avatar_upload_size_error` (1), `a11y_agent_avatar_preset` (1), `a11y_agent_avatar_current`
  (1), `agent_avatar_credit_line` (`%1$s %2$s %3$s` = 3 Args).
- **0 Kollision** gg. `strings.xml`/`values-en` @ `c4f2d53` (Prefix `agent_avatar_`/`a11y_agent_avatar_` neu; im Push
  `grep`-gegengeprüft).
- **Reuse (keine neuen Keys):** Save/Cancel, Operator-Gate; Initialen-Helper + Farb-Ring aus Code/Token.
- **Kein Secret in Keys:** keine E-Mail/Token/Endpoints; Style-Namen/Seed/Artist = neutrale Anzeige-Werte.
- **⚠ Shared-Key-Drift:** landen in `:app:shared`-Resources → konsumierendes Modul (CYP-216-Impl + Test-Modul CYP-7) muss
  re-syncen. **Mit dem CYP-216-Impl-Slice timen** (nicht isoliert mergen).
