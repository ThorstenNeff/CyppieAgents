# Hub-Ressourcen-/Überlast-UX — i18n-Keys (CYP-417 / Slice S-G ResourceGovernor)

> Owner: UIUX-Designer · Story CYP-417 (Epic CYP-395) · Stand 2026-07-11 · Status: **Spec-Closure (Q1–Q5 geruled)**;
> eingefroren als UI-Vorlage für den S-G-Bau. Begleit-Spec: `hub-resource-capacity-ux-spec.md`.
> Konvention (verifiziert gg. `app/shared/.../composeResources/values/strings.xml` @ develop `2877fc8c`):
> **Underscore-Realkeys** (keine Punkte), positionsbasierte Argumente `%1$s`/`%2$s`. **DE = Default** (`values/`), **EN**
> (`values-en/`). Parität Pflicht. Neue Key-Familie **`hubcap_*`** (greenfield, 0 Kollision verifiziert).

## Kapazität + Überlast
| Key | DE | EN |
|---|---|---|
| `hubcap_readout` | %1$s/%2$s Agenten | %1$s/%2$s agents |
| `hubcap_readout_nomax` | %1$s aktiv | %1$s active |
| `hubcap_full` | voll | full |
| `hubcap_overload_title` | Ein weiteres Team würde diese Maschine überlasten — Spawn abgelehnt. | Another team would overload this machine — spawn rejected. |
| `hubcap_overload_dismiss` | Verstanden | Got it |

## a11y
| Key | DE | EN |
|---|---|---|
| `a11y_hubcap_readout` | Hub-Kapazität: %1$s von %2$s Agenten (geschätzt) | Hub capacity: %1$s of %2$s agents (estimated) |
| `a11y_hubcap_readout_nomax` | Hub-Kapazität: %1$s Agenten aktiv (Höchstzahl noch nicht geschätzt) | Hub capacity: %1$s agents active (max not yet estimated) |
| `a11y_hubcap_overload` | Überlast-Schutz: Spawn abgelehnt — ein weiteres Team würde die Maschine überlasten. | Overload protection: spawn rejected — another team would overload the machine. |

> **`hubcap_readout` / `a11y_hubcap_readout` (H1):** `%1$s`=aktuell, `%2$s`=**geschätztes** Max; das Wort „geschätzt"
> im a11y ist Teil der Bedeutung (keine harte SLA-Zahl). **`hubcap_full` (Q2)** tönt WARN-Amber erst bei
> `current == estimatedMax`.
> **`hubcap_readout_nomax` / `a11y_..._nomax` (Fold-in CYP-417, H1-Verfeinerung):** wenn **`current` bekannt, aber
> `estimatedMax` null** (Ceiling nicht geschätzt) → Readout „N aktiv" **ohne** Max — die bekannte Tatsache (N laufen)
> wird gezeigt, **ohne ein Max zu erfinden**. Das ist **nicht** der Q3-Fall: **Q3 „nichts zeigen" gilt nur, wenn `current`
> selbst unbekannt** ist. Drei Zustände: `current`+`estimatedMax` bekannt → „N/M"; nur `current` bekannt → „N aktiv"
> (`_nomax`); `current` unbekannt → **absent** (`null≠0`).
> **`hubcap_overload_title` (H2):** trennt die **Tatsache** („Spawn abgelehnt") vom **geschätzten Grund** („würde
> überlasten"). WARN, nicht Fehler, nie Grün (H3).

## Reuse (bestehende Keys — NICHT neu anlegen; verifiziert @ `2877fc8c`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `event_severity_warn` | Event-Log (CYP-34/300) | Severity-Label des content-freien WARN-Events im Event-Log (§6) |
| `badge_count_overflow` (`9+`) | WindowBadge (CYP-55) | Overflow-Muster, falls `current`/`estimatedMax` je > 9 |

## Self-Validation
- **8 neue Keys** (inkl. Fold-in CYP-417 `hubcap_readout_nomax` + `a11y_hubcap_readout_nomax`), alle DE+EN befüllt,
  gleiche Argument-Anzahl je Sprache: 5 `hubcap_*` + 3 `a11y_hubcap_*` = **8**.
- **Argument-Keys:** `hubcap_readout` + `a11y_hubcap_readout` tragen je **zwei** (`%1$s`/`%2$s`); `hubcap_readout_nomax`
  + `a11y_hubcap_readout_nomax` je **einen** (`%1$s`); alle übrigen **0 Arg**. DE/EN-Argument-Anzahl identisch.
- **Kein content-tragender/sensibler Klartext** — `%1$s`/`%2$s` sind nur Zähler (`current`/`estimatedMax`), keine
  Agenten-Ausgabe, kein Secret.
- **Kollision:** **0** — `hubcap_*` ist greenfield (`grep name="hubcap"` @ `2877fc8c` liefert nichts); auch kein
  `capacity_*`/`overload_*`/`resource_*`/`hub_*` bestehend. Distinktiv gewählt (nicht `hub_*`, das zu breit wäre).
- **DE/EN-Parität:** jede Zeile beidseitig.
- **Honesty-Keys verankert:** `hubcap_readout`=Schätzung (nie SLA, unbekannt⇒absent) · `hubcap_full`=WARN erst bei voll ·
  `hubcap_overload_title`=Tatsache⊕Grund WARN-nie-grün. Jeder Key ist in `hub-resource-capacity-ux-spec.md` / `-tags.md`
  verankert.
