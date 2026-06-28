# Comm-Panel i18n — Key-Verdrahtungsliste (CYP-53)

> Owner: UIUX-Designer · Ticket: **CYP-53** „Comm-Panel i18n-Abdeckung schließen" · Status: **Entwurf — für Dev** · Stand: 2026-06-28
> Begleitend zu `docs/COMM-PANEL.md` + `docs/design/comm-panel-keys.md` (kanonische Keys). Backlog, **nicht blockierend** (CYP-21 ist Erledigt → kein Reopen, eigenes Ticket).
> **Befund (UX-QA bei CYP-51):** `CommPanel.kt` ist breit **DE-hardcodiert**; bis auf `comm_back` + `comm_status_offline` (CYP-51) sind die CYP-17-Keys **nicht verdrahtet / nicht in den Resources**. Diese Liste sagt **welcher Hardcode auf welchen Key** zeigt. Danach: Dev legt die Keys in `:app:shared` an (DE+EN) und verdrahtet sie.

**Anker-Hinweis:** Fundstellen sind über **Funktion + `CommTags`-testTag** stabil benannt (Zeilennummern @ CYP-51/`d4edd72` als Orientierung — können nach Merges leicht driften, die testTags nicht).

---

## 1. Verdrahtungstabelle

| # | Fundstelle (Funktion / testTag) | Aktueller Hardcode (DE) | **Soll-Key** | In Resources? | Hinweis |
|---|---|---|---|---|---|
| 1 | Kanalliste leer · `CommTags.EMPTY_CHANNELS` (L83) | „Keine lesbaren Kanäle" | `comm_channels_empty` | ❌ anlegen | **Wortlaut auf Spec:** „Keine zugänglichen Kanäle" / „No accessible channels" (vereinheitlichen, nicht „lesbar"). |
| 2 | Timeline leer · `CommTags.EMPTY_TIMELINE` (L138) | „Noch keine Nachrichten" | `comm_timeline_empty` | ❌ anlegen | exakt Spec („No messages yet"). |
| 3 | Nachricht pending · `MessageRow` (L179) | „· wird gesendet" | `comm_msg_pending` | ❌ anlegen | „·" bleibt **Layout-Separator** außerhalb des Keys; Key-Text = „Wird gesendet…" / „Sending…". |
| 4 | Verbindungs-Banner CONNECTING · `CommTags.CONNECTION` (L202) | „Verbinde…" | **NEU `comm_status_connecting`** | ❌ anlegen | neuer Key (s. §2) — „Verbinde…" / „Connecting…". |
| 5 | Composer read-only (`!canWrite`) · `CommTags.COMPOSER_READONLY` (L223) | „Keine Schreibrechte in diesem Kanal" | `comm_readonly_hint` | ❌ anlegen | **Disclosure-Trennung (wichtig):** der **proaktive Read-Only-Zustand** = „Nur Lesezugriff in diesem Kanal" / „Read-only access in this channel" — **getrennt** von #6 (abgelehnter Sende-Versuch). Aktuell konfundiert (beide „Keine Schreibrechte"). |
| 6 | Sende-Fehler `sendError == "comm_send_denied"` · Composer (L239) | „Keine Schreibrechte in diesem Kanal" | `comm_send_denied` | ❌ anlegen | abgelehnter **Sende-Versuch** (Fehlerpfad). Notice-Key → `stringResource` mappen (Muster wie ACL `noticeText()`). |
| 7 | Sende-Fehler else-Zweig · Composer (L239) | „Senden fehlgeschlagen" | **NEU `comm_send_failed`** | ❌ anlegen | neuer Key (s. §2) — generischer Sende-Fehler ≠ ACL-Ablehnung. |
| 8 | Composer-Platzhalter · `CommTags.COMPOSER_INPUT` (L251) | „Nachricht an den Kanal…" | `comm_composer_placeholder` | ❌ anlegen | Spec-Key hat **`%1$s` (Kanalname)**: „Nachricht an %1$s" / „Message to %1$s" — **mit Kanalname** verdrahten (nicht generisch). |
| 9 | Senden-Button · `CommTags.COMPOSER_SEND` (L257) | „Senden" | `comm_composer_send` | ❌ anlegen | exakt Spec („Send"). |

> **#5 vs #6 ist der einzige Disclosure-relevante Punkt:** „Nur Lesezugriff" (du *darfst* lesen, nur nicht schreiben — proaktiver Zustand) ≠ „Keine Schreibrechte – Senden abgelehnt" (Fehlerpfad nach Versuch). Beide ehrlich, aber die Spec (CYP-17 §4/§5) trennt sie bewusst. Bitte beim Verdrahten trennen, nicht beide auf denselben Text legen.

---

## 2. Zwei NEUE Keys (in `comm-panel-keys.md` ergänzt)

Diese fehlten in CYP-17 und sind für #4 und #7 nötig — sind in `comm-panel-keys.md` (§2/§3) jetzt enthalten:

| Real-Key | DE | EN | Stelle |
|---|---|---|---|
| `comm_status_connecting` | Verbinde… | Connecting… | #4 (Verbindungs-Banner) |
| `comm_send_failed` | Senden fehlgeschlagen | Sending failed | #7 (Composer-Fehler) |

---

## 3. Abdeckungs-Hinweise (Vollständigkeit)

- Bereits in Resources (CYP-51): `comm_back`, `comm_status_offline` — **nicht** erneut anlegen.
- In `comm-panel-keys.md` spezifiziert, aber in `CommPanel.kt` (noch) **ohne Fundstelle** — anlegen **wenn** das UI-Element erscheint, sonst kein toter Key: `comm_channels_title`, `comm_status_live` (Live-Indikator wird bei `LIVE` aktuell nur über Early-Return/keinen Text gelöst), `comm_unread_count`/`comm_unread`, `comm_timeline_loading`, sowie die a11y-Keys `a11y_comm_channel_selected`/`a11y_comm_unread`/`a11y_comm_connection`.
- **MessageKind-Badge** `KindBadge(it.name)` zeigt den Enum-Namen (TASK/STATUS/NOTE) — Lokalisierung dieser Labels liegt bei CYP-14 (`color-coding-keys.md`), **nicht** hier; nur referenzieren.
- **`agent_role_po`** (PO-Badge) ist bereits via CYP-51 verdrahtet — nicht doppeln.

> **⚠ Shared-Key-Drift:** Die Keys landen in `:app:shared`-Resources. Dev legt DE **und** EN an und verdrahtet `CommPanel.kt`; Test-Modul (CYP-7) ggf. nachziehen. **Wortlaute aus `comm-panel-keys.md` sind kanonisch** — bei Abweichung Hardcode dem Key anpassen, nicht umgekehrt.
