# i18n-Keys — Comm-Panel (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-17** · Status: **Entwurf** · Stand: 2026-06-26
> Begleitend zu `docs/COMM-PANEL.md`. `compose.resources` → **Underscore-Real-Keys** (gepunktete Form = menschlicher Namespace). Platzhalter positional (`%1$s`).
> **Reuse:** Kanal-Typ-/Rollen-/MessageKind-Labels kommen aus CYP-14 (`color-coding-keys.md`) — hier **nicht** dupliziert.

## 1. Kanalliste / Navigation

| Namespace (human) | Real-Key | DE (Quelle) | EN |
|---|---|---|---|
| `comm.channels.title` | `comm_channels_title` | Kanäle | Channels |
| `comm.channels.empty` | `comm_channels_empty` | Keine zugänglichen Kanäle | No accessible channels |
| `comm.back` | `comm_back` | Zurück | Back |
| `comm.unread.count` | `comm_unread_count` | %1$s ungelesen | %1$s unread |

## 2. Timeline / Zustände

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `comm.timeline.loading` | `comm_timeline_loading` | Lädt… | Loading… |
| `comm.timeline.empty` | `comm_timeline_empty` | Noch keine Nachrichten | No messages yet |
| `comm.status.live` | `comm_status_live` | Live | Live |
| `comm.status.offline` | `comm_status_offline` | Verbindung getrennt – Stand %1$s | Disconnected – as of %1$s |

## 3. Composer

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `comm.composer.placeholder` | `comm_composer_placeholder` | Nachricht an %1$s | Message to %1$s |
| `comm.composer.send` | `comm_composer_send` | Senden | Send |
| `comm.msg.pending` | `comm_msg_pending` | Wird gesendet… | Sending… |
| `comm.send.denied` | `comm_send_denied` | Keine Schreibrechte in diesem Kanal | No write access in this channel |
| `comm.readonly.hint` | `comm_readonly_hint` | Nur Lesezugriff in diesem Kanal | Read-only access in this channel |

## 4. Accessibility-Keys (Farbe nie alleiniger Träger)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `a11y.comm.channel.selected` | `a11y_comm_channel_selected` | Kanal %1$s ausgewählt | Channel %1$s selected |
| `a11y.comm.unread` | `a11y_comm_unread` | %1$s ungelesene Nachrichten | %1$s unread messages |
| `a11y.comm.connection` | `a11y_comm_connection` | Verbindungsstatus: %1$s | Connection status: %1$s |

### Disclosure-kritische Wortwahl
- `comm_status_offline` nennt **immer den Zeitstempel** des letzten Stands — Timeline nie als aktuell ausgeben, solange getrennt.
- `comm_msg_pending` = **„Wird gesendet"**, nicht „Gesendet/Zugestellt", bis `message.id` bestätigt ist.
- `comm_readonly_hint`/`comm_send_denied` benennen die ACL-Realität ehrlich, statt ein totes Eingabefeld zu zeigen.

> **⚠ Shared-Key-Drift:** landen in `:app:shared`-Resources → konsumierendes Modul (S6) muss re-syncen. Lieferung mit der S6-Umsetzung timen.
