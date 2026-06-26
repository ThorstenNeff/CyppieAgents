# i18n-Keys — Absender-/Kanal-Farbcodierung (Vorschlag v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-14** · Status: **Vorschlag** · Stand: 2026-06-26
> Begleitend zu `docs/COLOR-CODING.md`. Konvention identisch zu CYP-12 (`docs/design/i18n-keys.md`): `de` Quelle/Default + `en`, namespaced Keys.

Farbcodierung ist überwiegend visuell — wenige Keys nötig, vor allem für **Kanal-Typ-Labels** und **a11y** (damit Identität ohne Farbe zugänglich ist).

## 1. Kanal-Typ-Labels (`ChannelKind`)

| Key | DE (Quelle) | EN |
|---|---|---|
| `channel.kind.hub` | Hub | Hub |
| `channel.kind.direct` | Direkt | Direct |
| `channel.kind.group` | Gruppe | Group |

## 2. Rollen-Label (für PO-Badge)

| Key | DE | EN |
|---|---|---|
| `agent.role.po` | PO | PO |
| `agent.role.worker` | Worker | Worker |

## 3. Message-Kind-Labels (verwandt, `MessageMeta.kind` — für S6, hier vorbereitet)

| Key | DE | EN |
|---|---|---|
| `message.kind.task` | Aufgabe | Task |
| `message.kind.status` | Status | Status |
| `message.kind.note` | Notiz | Note |

## 4. Accessibility-Keys (Screenreader — Farbe nie alleiniger Träger)

| Key | DE | EN |
|---|---|---|
| `a11y.message.from` | Nachricht von {agent} | Message from {agent} |
| `a11y.channel` | Kanal {name}, Typ {kind} | Channel {name}, type {kind} |
| `a11y.agent.po` | {agent}, Koordinator | {agent}, coordinator |

> **⚠ Shared-Key-Drift:** landen in `:app:shared`-Resources → konsumierendes Modul (Comm-Panel, S6) muss re-syncen. Lieferung mit der S6-Umsetzung timen, nicht isoliert mergen.
