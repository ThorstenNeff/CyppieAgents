# a0 — `tertiary` ent-überladen: Per-Site-Remap (Dev-Vorlage, CYP-299)

> **Status:** DESIGN-VORLAGE (kein Bau) · Owner: UIUX · Paket **a0** aus `maritime-design-system-migration-plan.md` · gegen Code `develop d89b5bd`.
> **Warum:** `tertiary` trägt heute **Semantik** (WARN, in-progress, offline, deferred, denied). E1 macht `tertiary` im Night **grün** → ohne Remap würden **Warnungen/Offline nachts grün** (liest als Erfolg/verbunden — invertierte Bedeutung). a0 hängt jede Semantik auf die **korrekte** Rolle um; danach ist `tertiary` reiner Marken-Akzent und Grün ehrlich (§9-Inv.1).
> **Reiner Rollen-Swap:** 0 strings, 0 testTags, 0 Layout. Nur `colorScheme.<Rolle>`-Zuordnungen + **eine** geteilte Severity-Quelle.

---

## Zwei Kategorien + eine Konsolidierung

- **Kat 1 — Severity/Offline = amber (HART).** Grün ist hier eindeutig falsch (Warnung/Offline als „ok/verbunden"). → geteilte **Severity-Palette** (CYP-274-Familie).
- **Kat 2 — „in-progress" = neutral (Empfehlung).** Reconnecting/Startet/Running: Grün liest als „fertig/verbunden". → **`onSurfaceVariant`** (neutral, honest-transient); Text trägt die Bedeutung (WCAG 1.4.1). Green damit **komplett** status-frei (Inv.1 absolut).
- **Konsolidierung:** WARN hat heute **3–4 dupl. `when(severity)`-Blöcke** (EventVisuals-Rails CYP-274 · WindowBadge · ProductLeadPanel · AclPanel). a0 exponiert **eine** Quelle mit **Foreground-** und **Container(+onColor)-Variante**, scheme-adaptiv → alle Severity-Renderings ziehen aus einem Ort. Schließt den latenten „WARN auf Marken-Rolle"-Bug.

---

## Per-Site-Tabelle (11 Sites)

| # | Site (Datei:Zeile) | Heute | Kat | **Ziel-Rolle** | Begründung |
|---|---|---|---|---|---|
| 1 | `window/WindowBadge.kt:100` — SeverityBadge WARN | `tertiary`→`onTertiary` (Pill-**Container**) | 1 | **Severity-WARN-Container** (+onColor, geteilte Quelle) | Warnung nie grün; Container-Nutzung braucht amber-Fläche + AA-onColor |
| 2 | `report/ProductLeadPanel.kt:301` — `severityColor(WARN)` | `tertiary` (Fg) | 1 | **Severity-WARN-Foreground** (geteilte Quelle) | dito; auf die eine Severity-Quelle (= CYP-274-Rails) konsolidieren |
| 3 | `acl/AclPanel.kt:110` — DISCONNECTED-Banner | `tertiaryContainer`→`on` | 1 | **Severity-WARN-Container** (amber) | Offline/stale = Warnung; grün läse als „verbunden" (**invertiert**). Der Code-Kommentar sagt schon „amber warning" — `tertiary` war nur ein Platzhalter |
| 4 | `ui/TonedHint.kt:52,87` — EFFECT_DEFERRED-Banner | `tertiaryContainer`→`onTertiaryContainer` | — | **`secondaryContainer`→`onSecondaryContainer`** (blau) | „gespeichert, noch nicht aktiv" = neutrale Attention; blau ≠ Erfolg-grün ≠ Fehler-rot; **amber wäre falsch** (kollidiert mit WARN) |
| 5 | `connector/ConnectorCapabilityViews.kt:90,213` — EFFECT_DEFERRED-Äquiv. | `onTertiaryContainer` | — | **`onSecondaryContainer`** (blau) | Parität mit #4 |
| 6 | `agentview/AgentWindow.kt:222` — ReconnectingChip | `tertiary` (Fg-Text) | 2 | **`onSurfaceVariant`** (neutral) *[Empf.]* | Reconnecting ist transient; grün läse als „verbunden" (invertiert); Text trägt die Bedeutung |
| 7 | `agentview/AgentWindow.kt:241` — StatusIndicator `startPending`-Dot | `tertiary` | 2 | **`onSurfaceVariant`** (neutral) *[Empf.]* | „Startet…" = noch nicht laufend; neutral, distinkt von RUNNING=`primary` |
| 8 | `agentview/AgentWindow.kt:332` — ToolCallRow RUNNING ⟳ | `tertiary` | 2 | **`onSurfaceVariant`** (neutral) *[Empf.]* | hält OK=`primary`(blau) distinkt; RUNNING neutral, nie Erfolg (Erfolg ist hier blau, nicht grün) |
| 9 | `report/ProductLeadPanel.kt:72` — access_denied HintLine | `tertiary` (Fg) | — | **`onSurfaceVariant`** (neutral, = GATED-Ton) | Denied = Gate, kein Fehler; grün läse als „ok"; matcht `HintTone.GATED`-Konvention |
| 10 | `report/ProductLeadPanel.kt:83` — generating HintLine | `tertiary` (Fg) | 2 | **`onSurfaceVariant`** (neutral) *[Empf.]* | „wird generiert" = in-progress; neutral honest |
| 11 | `agentmgmt/AgentManagementPanel.kt:319` — addPoBlocked-Note | `tertiary` (Fg) | — | **`secondary`** (INFO-Ton) | informative Constraint („PO existiert bereits"); matcht INFO=`secondary` |

**Unverändert:** `TonedHint(INFO)` = `secondary` (nutzt nie `tertiary` — `ui/TonedHint.kt:89`). Keine Aktion.

---

## Kat-2-Design-Sub-Entscheidung (nicht-blockierend)

`onSurfaceVariant` (neutral) ist meine Empfehlung für #6/#7/#8/#10 — **hält Grün 100 % status-frei** (Inv.1 absolut) und ist distinkt von done=`primary`/error=`error`/warn=amber. **Trade:** neutral ist *leiser* als das heutige `tertiary`; der Text-Label trägt die Dringlichkeit. Falls für Reconnecting (#6) mehr Betonung gewünscht ist, wäre `secondary` (blau) die Alternative — aber **nicht** für #8 (RUNNING), weil `secondary`≈`primary`=OK die RUNNING/OK-Distinktion verwischt. Empfehlung: durchgehend neutral; Emphasis via Text/Glyph.

## Geteilte Severity-Quelle (für #1/#2/#3)

CYP-274 `EventVisuals.railColor(dark)` liefert schon WARN-**Foreground** (amber; dark `#FFC857`, light `#9A6400`). a0 ergänzt eine **Container-Variante** `severityContainer(sev, dark) → (container, onColor)` (amber-Fläche + AA-onColor, scheme-adaptiv) für die Badge/Banner-Nutzung (#1/#3) und exponiert `severityColor(sev, dark)` als Foreground (#2). **Exakte Container-Hex liefere ich beim a0-Schnitt** (amber-Container ≥ AA-onColor, abgeleitet aus dem CYP-274-WARN-Ton, beide Schemes). Damit: **eine** Severity-Quelle für Rails + Badges + Banner.

---

## Abnahme (meine UX-QA bei a0-Bau)

- **§9-Inv.1:** nach a0 zieht **kein** Semantik-Element Farbe aus `tertiary*`; Grün ist status-frei (grep `colorScheme.tertiary`/`tertiaryContainer` → nur noch bewusster Dekor/Akzent, 0 Semantik).
- **§9-Inv.3:** Severity semantisch **und konsolidiert** (WARN amber überall, eine Quelle; Rails=Badges).
- **WCAG:** Container-Sites (#1/#3/#4/#5) onColor ≥ 4.5:1; Foreground-Sites (#2/#6–#11) ≥ 4.5:1 auf ihrer Surface (bzw. ≥3:1 falls Icon/Groß) — **beide Schemes**, gerendert.
- **0 Drift:** 0 strings, 0 testTags, 0 Layout — reiner Rollen-Swap + 1 geteilte Severity-Helfer-Funktion.

**Hand-off:** a0 zuerst (gated Grün/Paket a), eigenständig wertvoll (schließt WARN-Doppel-Darstellungs-Bug). Kat-2-Sub-Entscheidung + exakte WARN-Container-Hex beim Schnitt final; sonst dispatch-fertig.
