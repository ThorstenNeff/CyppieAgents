# Remote UV-Flow B1-Enroll — Design-QA-Checkliste (Design-System-Fidelity) (CYP-542, Epic CYP-427)

> Owner: UIUX-Designer · Story CYP-542 (Phase A/B1) · Stand 2026-07-14 · Status: **§-QA-Readiness, vorbereitet**
> (PO `1526629380…`, ungated Parallelarbeit Option 1 — läuft in dem Moment, wo B1s Enroll-UI gemergt ist). **Design/QA,
> kein Code.** Gegroundet READ-ONLY gg. `MaritimeTheme.kt` (CYP-268 R1 + CYP-304 Night, WCAG-AA) + `EventVisuals.kt`
> @ `eb705236`.

## Was DIESE Checkliste ist (und was sie NICHT dupliziert)
Die **visuelle Design-System-Fidelity**-Achse: rendern die vier gebauten B1-Enroll-Screens gg. dem **maritim+M3-System**
korrekt — Farb-**Rollen** (nicht Hardcode-ARGB), Spacing/Typo-Skala, Day/Night-Parität, `tertiary`-nie-als-Status,
Glyph+Farbe-Paarung (1.4.1-visuell), Hierarchie/Layout. **Komplement, kein Duplikat:**
- **Verhaltens-/Honesty-§-QA** (Flow, Sackgassen, Töne-als-Bedeutung, Enumeration, fail-closed, Copy) = `remote-uv-flow-qa-checklist.md` (die kanonische Rubrik).
- **Exakte Render-Werte** (ARGB je State, Fill-Dämpfung, Glyph-Grammatik), die **Tester** 1:1 assertet = `remote-uv-flow-render-oracle.md`.
- **DIESE Datei** = der **Designer**-Fidelity-Pass: „sieht es wie das System aus", nicht „verhält es sich ehrlich" (dort) und nicht „stimmt der ARGB-Wert" (Tester).
Bei Farb-/Glyph-/Fill-**Werten** gilt das **Render-Oracle**; bei Ton-**Bedeutung** die **§-QA-Rubrik**; hier prüfe ich **Rollen-Treue, Layout, Skala, Scheme-Parität**.

## Lese-Legende
- **Soll** = Visual-Spec-Anker (`remote-uv-flow-visual-spec.md` §-Nummer) + Token (`-tokens.json`).
- **Status:** ✅ conform (gg. gebautem Screen) · 🟡 **assert-at-merge** (Screen noch nicht inspizierbar — Zeile = der zu prüfende Soll-Wert) · ⚠ **DS-Violation** (falls beim Merge so vorgefunden → Befund an PO, nicht selbst fixen).

## Screen-Map (PO-Namen → mein Visual-Spec-Element → Tags/Keys)
| PO-Screen | Visual-Spec | Kern-Tags | Kern-Keys |
|---|---|---|---|
| **PassphrasePrompt** | Prompt-Shell / Auth-Nutzungs-Eingabe (Mount-Host §4.2) | `remote.authStep.*` (Host), `remote.connect.authenticating` | `remote_pop_passphrase_title`, `_passphrase_body`, `remote_pop_path_hint` |
| **DicewareReveal** | §1a — empfohlener Diceware-Default (prominent) | `enrollSuggested`, `enrollSuggest`, `enrollCopy`, `enrollClipboardNotice` | `_enroll_suggested`, `_suggest_use`, `_suggest`, `_suggested_save`, `_clipboard_notice` |
| **SetPassphrase** | §1b — type-your-own Feldpaar (sekundär) | `enrollTypeOwn`, `enrollPinConfirm` | `_enroll_type_own`, `_enroll_passphrase`, `_enroll_passphrase_confirm` |
| **StrengthMeter** | §2 — Meter + 3 Verdict-States | `enrollStrength`, `enrollError.tooWeak`, `enrollError.blocklisted` | `_strength_{weak,fair,strong}`, `_enroll_too_weak`, `_enroll_blocklisted` |

> **★ Honesty-Klarstellung „Reveal" (Namenskollision, wichtig für Dev/Tester):** der Screen-Name **DicewareReveal**
> meint die **Anzeige der *generierten* Passphrase** — die per HG2-Ehrlichkeit **gezeigt werden MUSS** (nie
> masked-at-generation; der Nutzer muss sie abschreiben können). Das ist **NICHT** ein Mask-Reveal-**Toggle** und
> widerspricht dem §1b-Ruling **nicht**: das **type-your-own**-Feld (SetPassphrase) bleibt **maskiert, ohne Reveal-Toggle**
> (`63e32c84`, kein `revealTag`). Generierter Default = angezeigt (by design); getipptes Secret = maskiert. Zwei getrennte
> Wahrheiten.

---

## A. PassphrasePrompt (Prompt-Shell / Mount-Host)
| # | Check | Soll | Status |
|---|---|---|---|
| A1 | Titel `_passphrase_title` in M3-Titel-Rolle (`titleMedium`/`titleLarge` pro Dialog-Konvention), `onSurface` | vs `OperatorAuthDialog`-Kopf-Muster | 🟡 |
| A2 | Body/`pathHint` `bodyMedium`, `onSurfaceVariant` (neutral, kein Alarm) | §5, `_path_hint` credential-flexibel `%1$s` | 🟡 |
| A3 | Dialog-Container = System-`Surface`-Rolle (kein Custom-Hintergrund); Radius/Elevation = Dialog-Konvention (reuse CYP-460, kein Neu-Chrome) | Anti-duplicate (Sibling `OperatorAuthDialog`) | 🟡 |
| A4 | Mount **im AUTHENTICATING-`popPrompt`-Slot** rendert den Dialog (kein leerer Spinner an dessen Stelle) — **visuelle** Kontinuität; das *Verhalten* prüft §-QA B1 | §4.2 | 🟡 |
| A5 | **Day+Night beide** gerendert: Text/Container-Rollen scheme-adaptiv (kein Hardcode, kein hell-gefrorener Wert im Dark) | §5 „beide Schemes" | 🟡 |

## B. DicewareReveal (§1a — prominenter Default)
| # | Check | Soll | Status |
|---|---|---|---|
| B1 | **Karten-Container:** `surfaceVariant`-Fill, `outline`-Rahmen **1dp**, **12dp** Radius, **16dp** Padding | §1a, tokens `enroll.passphraseSuggested.container` | 🟡 |
| B2 | **Label** „Empfohlen — 6 zufällige Wörter" = `labelMedium`, `onSurfaceVariant` — **kein Grün, kein ✓** (Text trägt „Empfohlen") | §1a | 🟡 |
| B3 | **Die Wörter** = `bodyLarge`, **monospace**, `onSurface`, wort-getrennt (großzügiges Spacing), sichtbar (nicht maskiert) | §1a (HG2) | 🟡 |
| B4 | **Primär-Aktion** „Diese Passphrase verwenden" = **gefüllter `primary`-Button**, `onPrimary`-Text, **volle Breite** (visuell stärkste Aktion) | §1a, `_suggest_use` | 🟡 |
| B5 | **Regenerate** „Andere vorschlagen" = **Text-/Outlined-Button**, `primary`-Text — sichtbar **sekundär** zur Primär-Aktion | §1a, `_suggest` | 🟡 |
| B6 | **Save-Hint** „Notiere sie sicher …" = `labelSmall`, `onSurfaceVariant`, **neutral** (kein WARN-Amber — es ist Guidance) | §1a, `_suggested_save` | 🟡 |
| B7 | **Copy-Affordanz** = Icon-Rolle (Sibling `remote.recovery.codesCopy`), **kein** neues Sichtbar-Label — konsistent mit Recovery-Copy-Muster | §1a, `enrollCopy` | 🟡 |
| B8 | **Clipboard-Notice** (⇔ Copy genutzt) = `labelSmall`, `onSurfaceVariant`, **neutral** — **kein** WARN-Amber, **kein** Erfolgs-Grün, **kein** Glyph | §1a, `enrollClipboardNotice` · Render-Oracle | 🟡 |
| B9 | **Hierarchie:** Default-Block **visuell dominant** über dem type-your-own-Einstieg (Größe/Position/Füllung) — die Ehrlichkeit ist die Hierarchie | §1 (Common-Path dominant) | 🟡 |

## C. SetPassphrase (§1b — type-your-own, sekundär)
| # | Check | Soll | Status |
|---|---|---|---|
| C1 | **Einstieg** „Eigene Passphrase eingeben" = **leiser Text-Button** (`onSurfaceVariant`/`primary`), **unter** dem Default — „Ausnahme, nicht Default" | §1b, `enrollTypeOwn` | 🟡 |
| C2 | **Feldpaar** (Setz + Bestätigung) = **reuse `AuthPasswordField`** (Haus-Feld, kein Custom) — konsistente Feld-Optik | §1b | 🟡 |
| C3 | **★ Maskiert, KEIN Reveal-Toggle** — Klartext nie on-screen; **kein `revealTag`** im Baum (§1b-Ruling `63e32c84`) | §1b, PO-Security-Ruling | 🟡 ⚠-falls-Toggle-vorhanden |
| C4 | **isError-Kopplung:** bei tooWeak/mismatch/blocklisted → `error`-Outline am **richtigen** Feld, Fehlerzeile direkt darunter = **eine** Einheit | §3 | 🟡 |
| C5 | **Day+Night** Feld-Rollen scheme-adaptiv (Outline/Text/Placeholder) | §5 | 🟡 |

## D. StrengthMeter (§2 — Meter + 3 Verdicts) — **Fidelity, Werte via Render-Oracle**
| # | Check | Soll | Status |
|---|---|---|---|
| D1 | **Track** = `surfaceVariant`, Höhe **6dp**, voll gerundet | §2 | 🟡 |
| D2 | **Fill** = proportional, Rolle **`primary`** (nie `tertiary`/Grün) | §2 · Render-Oracle | 🟡 |
| D3 | **Level-Label** (`_strength_{weak,fair,strong}`) = `labelSmall`, `onSurfaceVariant`, **Text** vorhanden (Farbe nie alleiniger Träger) | §2, WCAG 1.4.1 | 🟡 |
| D4 | **OK-Verdict:** `●`-Marker (**separater** Node) + „Stark", `primary`/`onSurfaceVariant` — **kein** `▲`, **kein** Grün | §2, Render-Oracle Z.37 | 🟡 |
| D5 | **TOO_WEAK-Verdict:** `▲` (separater Node) + WARN-Amber (`severityColor(WARN)`) — Fill klein-proportional (nicht error-rot gefärbt) | §2, Render-Oracle Z.38 | 🟡 |
| D6 | **★ BLOCKLISTED-Verdict:** Verdict-Zeile **error-Ton** (`colorScheme.error`) **+ Fill auf `outline` gedämpft — NICHT voll/`primary`** (Optik lügt nie „stark") | §2 Kern-Ehrlichkeit, Render-Oracle Z.39/★ | 🟡 ⚠-falls-Fill-voll |
| D7 | **Ton-Trennung visuell distinkt:** OK ≠ TOO_WEAK ≠ BLOCKLISTED an Farbe **und** Marker/Copy erkennbar (nicht nur an Farbe) | §2, WCAG 1.4.1 | 🟡 |
| D8 | **`errorContainer` nirgends** im Meter/Enroll (nur terminaler Hub-Reject nutzt es); **`tertiary`/Grün nirgends** | §2, §7-Inv. | 🟡 |

## E. Cross-cut Design-System (alle vier Screens)
| # | Check | Status |
|---|---|---|
| E1 | **Keine Hardcode-Farbe:** alle Töne = `MaterialTheme.colorScheme`-Rollen **by name** / `severityColor(WARN)` — kein literales `Color(0x…)` im UI-Code der Enroll-Elemente | 🟡 |
| E2 | **Day/Night-Parität** über alle vier Screens: jeder Screen in **beiden** Schemes gerendert, Kontrast hält **AA** (Fill `#6FBEEA`, WARN `#FFC857`, error `#F2B8B5` im Dark) | 🟡 |
| E3 | **`tertiary`/#40D6A0 = NIE Status** — nicht bei OK, nicht bei granted, nicht als Häkchen (§9-Inv.1) | 🟡 |
| E4 | **Glyph+Farbe-Paarung** (visuelles 1.4.1): jeder farb-getragene Zustand hat Glyph **oder** Text als **separaten** Träger (`▲` WARN, `●` OK, Copy bei BLOCKLISTED) | 🟡 |
| E5 | **M3-Typo-Skala konsistent:** Titel/Body/Label aus der Theme-Typography (kein Ad-hoc-`sp`); Diceware-Wörter monospace als bewusste Ausnahme | 🟡 |
| E6 | **Reuse statt Neu-Chrome:** Dialog/Feld/Button = bestehende Haus-Komponenten (CYP-460-Dialog, `AuthPasswordField`, M3-Buttons) — kein divergenter One-off | 🟡 |
| E7 | **Spacing-Rhythmus** konsistent (Karten-Padding 16dp, Radius 12dp, Meter 6dp) — keine willkürlichen Abstände | 🟡 |

---

## Ausführung
Beim B1-Merge inspiziere ich die vier Screens (Day+Night) gg. diese Zeilen; jede 🟡 wird ✅ oder ⚠. ⚠-Befunde gehen als
**priorisierte Liste (Severity + konkreter Fix)** an den PO — **nicht** selbst gefixt. Werte-Abweichungen (ARGB/Fill/Glyph)
verweise ich auf das **Render-Oracle** (Testers Assert-Quelle); Ton-**Bedeutungs**-Abweichungen auf die **§-QA-Rubrik**.

## Self-Validation
- **Kein Duplikat:** Achsen-Split zur `-qa-checklist.md` (Verhalten/Honesty) und `-render-oracle.md` (exakte Werte) explizit gezogen.
- **Alle Soll-Werte** stammen aus `-visual-spec.md`/`-tokens.json` (meine ratifizierten Anker) + `MaritimeTheme.kt`/`EventVisuals.kt` @ `eb705236` — keine erfundene Farbe/Skala.
- **§1b-Ruling gewahrt** (C3: kein Reveal-Toggle) + **Reveal-Namenskollision** entschärft (DicewareReveal = Anzeige ≠ Toggle).
- Screen-Map deckt alle vier PO-Namen; Tags/Keys gg. `-tags.md`/`-keys.md` konsistent. Kein Bau, keine develop-Berührung; docs-only auf `feature/CYP-542-uv-ui-spec`.
