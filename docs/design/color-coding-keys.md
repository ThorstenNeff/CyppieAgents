# i18n-Keys — Absender-/Kanal-Farbcodierung (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-14** · Status: **Mechanismus bestätigt (compose.resources)** · Stand: 2026-06-26
> Begleitend zu `docs/COLOR-CODING.md`. Konvention identisch zu CYP-12: `de` Quelle/Default + `en`; **compose.resources → Underscore-Real-Keys** (gepunktete Form = menschlicher Namespace). Platzhalter positional (`%1$s`).

Farbcodierung ist überwiegend visuell — wenige Keys nötig, vor allem für **Kanal-Typ-Labels** und **a11y** (damit Identität ohne Farbe zugänglich ist).

## 1. Kanal-Typ-Labels (`ChannelKind`)

| Namespace (human) | Real-Key (compose.resources) | DE (Quelle) | EN |
|---|---|---|---|
| `channel.kind.hub` | `channel_kind_hub` | Hub | Hub |
| `channel.kind.direct` | `channel_kind_direct` | Direkt | Direct |
| `channel.kind.group` | `channel_kind_group` | Gruppe | Group |

## 2. Rollen-Label (für PO-Badge)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `agent.role.po` | `agent_role_po` | PO | PO |
| `agent.role.worker` | `agent_role_worker` | Worker | Worker |

## 3. Message-Kind-Labels (verwandt, `MessageMeta.kind` — für S6, hier vorbereitet)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `message.kind.task` | `message_kind_task` | Aufgabe | Task |
| `message.kind.status` | `message_kind_status` | Status | Status |
| `message.kind.note` | `message_kind_note` | Notiz | Note |

## 4. Accessibility-Keys (Screenreader — Farbe nie alleiniger Träger)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `a11y.message.from` | `a11y_message_from` | Nachricht von %1$s | Message from %1$s |
| `a11y.channel` | `a11y_channel` | Kanal %1$s, Typ %2$s | Channel %1$s, type %2$s |
| `a11y.agent.po` | `a11y_agent_po` | %1$s, Koordinator | %1$s, coordinator |

> **⚠ Shared-Key-Drift:** landen in `:app:shared`-Resources → konsumierendes Modul (Comm-Panel, S6) muss re-syncen. Lieferung mit der S6-Umsetzung timen, nicht isoliert mergen.
