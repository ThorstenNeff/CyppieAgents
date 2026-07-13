# Workspace Remote-Context-Banner — i18n-Keys (CYP-527, Epic CYP-427)

> Owner: UIUX-Designer · **Dev-AC (copy-paste-fertig)** · Stand 2026-07-13 · Kontext: Design-QA-Thread zum
> CYP-427-Desktop-Dogfood (PO `1526153892…`→`1526163810…`), gelockt als „WARN-only"-Minimalvariante der
> CYP-449-Naht #4 (remote-operating context banner).
> Konvention (verifiziert gg. `app/shared/.../composeResources/values/strings.xml` @ develop `d9a3d6be`):
> **Underscore-Realkeys**, positionsbasierte Args `%1$s`. **DE = Default**, **EN** (`values-en/`). Parität Pflicht.

## Zweck (in einem Satz)
Eine persistente, **transport-getriebene** WARN-Zeile im Workspace, die ehrlich sagt: „du bedienst eine
**Remote-Hub-Sitzung** — aber die Workspace-Daten laufen **noch nicht** über den Tunnel." Kontext-Wahrheit +
CR3-Disclosure in einer Zeile, damit „Remote verbunden" **nie** als Daten-über-Tunnel gelesen wird.

## Diese Slice baut — 1 Realkey (WARN-only)
| Key | DE | EN |
|---|---|---|
| `workspace_remote_context_partial` | Remote verbunden mit %1$s — Workspace-Daten laufen noch nicht über den Tunnel. | Connected remotely to %1$s — workspace data isn't over the tunnel yet. |

`%1$s` = **`hub.name`** (der editierbare Anzeigename, **nie** die opake `hubId` — konsistent mit `HubRow`).
Der Glyph `▲` wird **separat** gerendert (`Text("▲ ", color = severityColor(Severity.WARN))` + `Text(<key>)`,
Muster wie `RemoteFailureView`), **nicht** in den String — WCAG 1.4.1: der Text trägt die Bedeutung, `▲`+Amber
ist nur Verstärkung.

## a11y (empfohlen — 1 Key)
| Key | DE | EN |
|---|---|---|
| `a11y_workspace_remote_context` | Warnung: Remote verbunden mit %1$s — Workspace-Daten laufen noch nicht über den Tunnel. | Warning: connected remotely to %1$s — workspace data isn't over the tunnel yet. |

> Announced die WARN-Natur explizit (der `▲` ist rein dekorativ). Optional: entfällt der a11y-Key, setzt die
> `contentDescription` der Banner-Row stattdessen den aufgelösten `workspace_remote_context_partial`-String — aber
> ohne das „Warnung:"-Präfix. Empfehlung: den a11y-Key nehmen (Parität zum `a11y_workspace_role` am ROLE_INDICATOR).

## Deferred — **NICHT in dieser Slice bauen** (dokumentiert für Forward-Compat)
| Key / Änderung | DE | EN | Wann |
|---|---|---|---|
| `workspace_remote_context_partial` (**Copy-Swap, gleicher Key**) | Remote verbunden mit %1$s — Live-Daten laufen noch nicht über den Tunnel. | Connected remotely to %1$s — live data isn't over the tunnel yet. | Wenn **CR3-①** landet (nur Roster über Tunnel, Live-I/O noch nicht) — dann ist „Workspace-Daten" leicht falsch, „Live-Daten" präzise. Reiner Copy-Update desselben Keys, **kein** neuer Key/Tag. |
| `workspace_remote_context_tunnel` (**neuer Key**, `●`-Vollform) | Remote — E2E-Tunnel zu %1$s | Remote — E2E tunnel to %1$s | Wenn **CR3 vollständig** (Workspace-Transport trägt Live-Daten) — `●`+`primary`-Ton statt `▲`+WARN. Bindet an ein CR3-Capability-Signal, das heute **nicht existiert** → seam-gated, **absent**. |

## Honesty-Anker (für §-QA)
- `workspace_remote_context_partial` = **Kontext, nicht Daten-über-Tunnel**: nennt die Remote-Sitzung UND
  disclosed in derselben Zeile, dass die Daten noch nicht fern fließen (CR3). „Remote verbunden" darf **nie**
  ohne diese CR3-Klausel stehen (sonst Überzeichnung).
- Ton = **`severityColor(Severity.WARN)`** (TrustChanged-Idiom) — **nie** `errorContainer`/`HintTone.ERROR`
  (es ist nicht „kaputt") und **nie** `tertiary`/Erfolgs-Grün (es ist nicht „alles gut"). Farbe nie alleiniger
  Träger (WCAG 1.4.1: Glyph `▲` + Text).
- **Kein** „RR5"/„Seam"/„Stub"/„Tunnel-Internals"-Jargon in der User-Copy — nur die schlichte Wahrheit.
- Die Präsenz/Guards (`remoteContext`) sind im **`-tags.md`** (Binding & Guards) verankert — das Banner ist
  **transport-getrieben**, nicht klick-getrieben.

## Reuse (bestehende Keys/Muster — NICHT neu anlegen; verifiziert @ `d9a3d6be`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `severityColor(Severity.WARN)` | `eventlog/severityColor` | WARN-Ton (Glyph + Text), wie `RemoteFailureView`/`WarnLine` |
| `hub.name` | `HubDescriptor` | `%1$s`-Füllung (editierbarer Name, nie die id) |
| `overloadBanner`-Slot-Idiom | `ProjectSwitcherBar` (CYP-417) | Platzierungs-Muster für die Full-Width-Bannerzeile (Details in `-tags.md`) |

## Self-Validation
- **In dieser Slice gebaut: 1 Realkey** (`workspace_remote_context_partial`) **+ 1 a11y-Key**
  (`a11y_workspace_remote_context`, empfohlen) = **2 Keys**. Beide DE+EN befüllt, gleiche Argument-Anzahl je Sprache.
- **Argument-Keys (genau ein `%1$s`, DE=EN):** beide Keys = **1 Arg** (`hub.name`). DE/EN-Argument-Anzahl identisch.
- **Kein content-tragender/sensibler Klartext** — `%1$s` = Hub-Anzeigename; **kein** Secret/Token/Fingerprint in einer Copy.
- **Kollision: 0** — `workspace_remote_context*` verifiziert greenfield gg. `strings.xml` @ `d9a3d6be`
  (kein `workspace_remote_context`/`remote_context` vorhanden).
- **DE/EN-Parität:** jede gebaute Zeile beidseitig.
- **Deferred-Keys (`_partial`-Copy-Swap, `_tunnel`) sind NICHT Teil dieser Slice** — dokumentiert, nicht ausgeliefert;
  ihre Absenz ist **kein** §-QA-Befund.
- Jeder gebaute Key ist im `-tags.md` (Binding & Guards) verankert.
