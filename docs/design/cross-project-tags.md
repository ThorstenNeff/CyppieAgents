# Cross-Projekt — testTags (CYP-93 / CYP-94)

> Owner: UIUX-Designer · Epic CYP-79 · Stand 2026-06-28 · Status: Vorlauf-Entwurf — wartet auf PO-/Dev-Gegenlesen
> Schema (Test-Contract v0.5 §2, Dev/QA-Vertrag CYP-7): **prefixlos**
> `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, Segmentwerte `[A-Za-z0-9-]+` (camelCase ok,
> **keine Punkte** im Wert). Neue Area = `crossProject` (CYP-93); CYP-94 = **Additionen** zu den
> bestehenden Areas `eventBrowse`/`eventTail` (CYP-41/42).
> **Code = Source of Truth:** Reuse unten gegen die echten Quellen (develop `3705b68`) verifiziert.

## Neue Tags — Area `crossProject` (CYP-93)
| Tag | Element |
|---|---|
| `crossProject.badge.<channelId>` | Cross-Projekt-Reichweiten-Badge am Kanal (scope = Kanal-id; Form/Glyph+Label, nicht Farb-only) |
| `crossProject.badge.<channelId>.unauthorized` | Qualifier: Kanal reicht über Projekte, aber (noch) **nicht freigegeben** (fail-closed) |
| `crossProject.status` | Status-Disclosure-Zeile (freigegeben: erreichte Agenten + `sharedAt` · ODER „nur dieses Projekt") |
| `crossProject.member.<agentId>` | Heimat-Projekt-Kennung eines fremd-projektigen Members in der Kanal-Mitglieder-/ACL-Ansicht (Membership-Transport-Disclosure) |
| `crossProject.authorize` | Aktion „Projektübergreifend freigeben" (öffnet Dialog) |
| `crossProject.revoke` | Aktion „Freigabe zurücknehmen" |
| `crossProject.gateHint` | Operator/Owner-Gate-Hinweis (fail-closed, read-only ohne Token) |
| `crossProject.dialog` | Wurzel des Autorisierungs-Dialogs |
| `crossProject.dialog.scope` | Reichweite/Folgen: welche Projekte + Agenten der Kanal danach erreicht (INFO) |
| `crossProject.dialog.ownerConsent` | bewusste Eigentümer-Zustimmungs-Affordanz (Checkbox/expliziter Schritt) |
| `crossProject.dialog.humanOnlyNote` | Anti-Injection-Hinweis „nur du, nie Agent/Nachricht" (INFO) |
| `crossProject.dialog.singleOwnerNote` | Single-Owner-Honesty (bilateral = S18 deferred) (INFO) |
| `crossProject.dialog.confirm` | Bestätigen („Freigeben") |
| `crossProject.dialog.cancel` | Abbrechen |
| `crossProject.dialog.error` | Freigabe-/Revoke-Fehler (Server) |

## Additionen — bestehende Areas `eventBrowse` / `eventTail` (CYP-94)
| Tag | Element |
|---|---|
| `eventBrowse.filter.project` | Projekt-Filter-Control (Addition zur `eventBrowse.filterBar`) |
| `eventBrowse.crossProjectView` | Cross-Projekt-Sicht-Indikator („Sicht: Projekt X / alle Projekte") |
| `eventBrowse.row.<index>.project` | Pro-Zeile-Projekt-Kennung — **nur** in projektübergreifender Sicht |
| `eventTail.filter.project` | Projekt-Filter-Control (Addition zur `eventTail`-Filterleiste) |
| `eventTail.crossProjectView` | Cross-Projekt-Sicht-Indikator |
| `eventTail.row.<index>.project` | Pro-Zeile-Projekt-Kennung — **nur** in projektübergreifender Sicht |

> Die `…row.<index>.project`-Kennung ist eine **zusätzliche** Qualifier-Achse neben der bestehenden
> Severity/`gap`-Qualifizierung (`eventBrowse.row.<index>.<error|warn|info|debug|gap>`) — Identität
> (Projekt) ist orthogonal zu Severity, beide am selben Zeilen-Scope.

## Reuse (gegen Code verifiziert — NICHT neu definieren)
| Reuse | Quelle (develop `3705b68`) | Zweck hier |
|---|---|---|
| `comm.channel.<id>` | `CommTags.channel(id)` | Host-Anker des Cross-Projekt-Badges in der Kanalliste |
| `comm.channelList` | `CommTags.CHANNEL_LIST` | Kanallisten-Container (Badge-Kontext) |
| `aclMatrix.rowHeader.<channelId>` | `AclMatrixTags` | alternativer Host-Anker (Kanal-Zeilenkopf in der ACL-Matrix) |
| `eventBrowse.*` / `eventTail.*` | `EventLogTags` (CYP-41/42) | Areas, in die der Projekt-Filter + Sicht-Indikator + Zeilen-Kennung additiv einfügen |
| `severity_rail` (Token) | `event-log-tokens.json` (CYP-34) | bleibt für Severity; Projekt-Kennung ist eine **getrennte** Achse |
| `ui/TonedHint.kt` + `HintTone` | CYP-99 (Komponente) | status/scope/humanOnly/singleOwner/gate/error rendern über `TonedHint(text, tone, tag)` — Code-Reuse, der `tag` trägt einen der **oben** definierten Tags |
| CYP-14 `ColorSlot` | `colorSlot` (Identität) | optionaler Projekt-Identitäts-Tint der Zeilen-Kennung (NIE alleiniger Träger) |

> **Host-Anker, nicht Eigentum:** `crossProject.*` und die `event*`-Additionen werden in **bestehende**
> Surfaces (Comm/ACL/Event-Log) eingehängt; die finalen Konstanten gehören in die jeweiligen Tag-Objekte
> (`CommTags`/`AclMatrixTags`/`EventLogTags`) bzw. ein neues `CrossProjectTags`-Objekt — Tags folgen den
> hier fixierten Werten.

## Self-Validation
- **21 neue Tags** (15 Area `crossProject` inkl. `member.<agentId>` + 6 Additionen zu `eventBrowse`/
  `eventTail`); Schema-konform (camelCase-Werte wie `ownerConsent`/`humanOnlyNote`/`crossProjectView`;
  keine Punkte in Segmentwerten).
- **Verschachtelung** folgt Präzedenz: `crossProject.badge.<channelId>[.unauthorized]` analog
  `aclMatrix.cell.<channelId>.<agentId>.<qualifier>`; `…row.<index>.project` analog der bestehenden
  `eventBrowse.row.<index>.<severity>`-Qualifizierung.
- **Reuse gegen Code verifiziert** (develop `3705b68`): `CommTags.channel(id)`=`comm.channel.<id>`,
  `CommTags.CHANNEL_LIST`=`comm.channelList`, `AclMatrixTags.rowHeader`, `EventLogTags`
  (eventBrowse/eventTail), `severity_rail`, `TonedHint`/`HintTone`, CYP-14 `ColorSlot`. **Kein Drift.**
- **Window-Präsenz/Omission** der Event-Surfaces bleibt unverändert (ohne Operator-Token kein
  `eventBrowse.*`/`eventTail.*`-Knoten — die Projekt-Additionen erben das, kein Cross-Projekt-Read ohne
  Token).
