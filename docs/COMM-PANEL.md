# Comm-Panel-Layout — Kanalliste + Timeline (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-17** (Epic CYP-2, speist S6) · Status: **Entwurf — wartet auf Dev-Gegenlesen** · Stand: 2026-06-26
> **Kanonischer Ort:** geteiltes Repo `KMPCyppieAgents` unter `docs/COMM-PANEL.md`.
> Begleit-Artefakte: `docs/design/comm-panel-tokens.json`, `docs/design/comm-panel-keys.md`.
> **Reuse-First:** baut auf **CYP-14** (Farbcodierung), **CYP-12** (Status/Disclosure) und dem realen `CommModel` auf — keine Neuerfindung. **Brand:** CyppieAgents (Anti-Hype).

Definiert Layout, Zustände und Disclosure des Comm-Panels: **Kanalliste + Live-Timeline pro Kanal**. Keine Implementierungsvorgabe.

---

## 0. Bezugsrahmen (verifiziert im Code, 2026-06-26)

- **Datenmodell** `core/.../model/CommModel.kt` (auf develop): `Channel(id,name,kind,members)`, `ChannelKind{DIRECT,GROUP,HUB}`, `Message(id,channelId,from,body,ts,meta)`, `MessageMeta(inReplyTo,kind)`, `MessageKind{TASK,STATUS,NOTE}`, `Agent(id,name,role)`, `Role{PO,WORKER}`.
- **ACL** `core/.../model/AclMatrix.kt`: `readableChannels(agentId)` und `visibleMessages(agentId, …)` — **fail-closed**. Bindet, welche Kanäle/Nachrichten überhaupt erscheinen dürfen.
- **Farbcodierung:** `docs/COLOR-CODING.md` (CYP-14) — Absender = `colorSlot(Message.from)`, Kanal-Identität = `colorSlot(Channel.id)`, Kanal-Typ über Icon.
- **Status/Disclosure:** `docs/STATE-VISUALIZATION.md` (CYP-12) — Wortregeln, „Farbe nie allein".
- **Fenster-Host:** Comm-Panel ist Inhalt **eines** Fensters im Fenster-Manager (CYP-10, `WindowHost`/`content`-Slot). Chrome (Titelleiste/Resize) kommt vom Host — Panel rendert nur seinen Innenbereich.
- **Datenquellen (Vertrag, 02):** REST `GET /api/channels`, `GET /api/channels/{id}/messages?since={ts}`, `POST /api/channels/{id}/messages` (§7); WS-Push `MessageEvent`/`AclEvent`/`ChannelsEvent`, optional `Subscribe` (§8).

> **⚠ Dev-Ask (Vertrag fehlt noch):** Die WS-Frame-DTOs aus 02 §8 (`MessageEvent`/`AclEvent`/`ChannelsEvent`) existieren in `:core` **noch nicht**. Dieses Layout setzt sie voraus — bitte als eigenes Vertrags-Ticket vor der S6-Implementierung einplanen.

---

## 1. Grundlayout (Master-Detail)

Drei Bereiche im Panel-Innenraum:

```
┌──────────────┬─────────────────────────────────┐
│ Kanalliste   │ Timeline (gewählter Kanal)       │
│ (Master)     │  ─ Nachrichtenzeilen (scroll)    │
│              │                                   │
│              ├─────────────────────────────────┤
│              │ Composer  „Nachricht an #kanal"  │
└──────────────┴─────────────────────────────────┘
```

- **Wide (Two-Pane):** Kanalliste (start) + Timeline+Composer (main). Default im Desktop-Fenster.
- **Narrow (Single-Pane):** nur Kanalliste **oder** Timeline; Auswahl navigiert zur Timeline, **Zurück** kehrt zur Liste. Breakpoint an der Panel-(nicht Bildschirm-)Breite (das Panel ist ein Fenster) — Vorschlag: < ~480dp Innenbreite → Single-Pane.
- **RTL:** start/end statt links/rechts; Master spiegelt an die Start-Kante.

---

## 2. Kanalliste (Master)

**Nur ACL-lesbare Kanäle** (`AclMatrix.readableChannels(viewer)`) werden gelistet — ein nicht-lesbarer Kanal erscheint **gar nicht** (§5).

Kanal-Zeile:
- **Kanal-Typ-Icon** (HUB/DIRECT/GROUP, aus `Channel.kind`) — Typ über Icon, nicht Farbe (CYP-14).
- **Kanal-Identitäts-Punkt** (`colorSlot(Channel.id)`).
- **Name** (`Channel.name`).
- **Letzte-Nachricht-Vorschau** (1 Zeile, Ellipsis) + **Zeit** (relativ).
- **Ungelesen-Indikator** (Badge/Count) — Token `comm.unread`.
- **Ausgewählt-Zustand** — deutlicher als Hover; nicht nur Farbe (Border/Fill + ggf. start-Marker).

---

## 3. Timeline (Detail)

Historie via REST (`…/messages?since`), Live via WS-Push (`MessageEvent`). Sortierung nach `Message.ts`.

Nachrichtenzeile:
- **Absender-Avatar** (Initialen aus `Agent.name`, Farbe `colorSlot(Message.from)` — CYP-14).
- **Absendername** + **PO-Badge**, falls `Role.PO` (`agent_role_po`).
- **Zeitstempel** (`Message.ts`, lokalisiert).
- **Body** (`Message.body`, Text/Markdown im MVP).
- **`MessageKind`-Badge** (TASK/STATUS/NOTE) — klein, **getrennt** vom Identitäts-Farbsystem (CYP-14 §6). Für `STATUS` darf die Status-Semantik (CYP-12) wiederverwendet werden, **ohne** den Absender umzufärben.
- **Gruppierung:** aufeinanderfolgende Nachrichten desselben `from` werden gebündelt (Avatar/Name nur einmal), Zeitstempel pro Zeile dezent.

---

## 4. Composer

- Bedeutung: **„Nachricht an den Kanal"** (POST `…/messages`). Konsistent mit 05/CYP-12-Sprache (kein Shell-Prompt).
- **Sichtbarkeit an ACL gekoppelt:** nur wenn der Viewer im gewählten Kanal `canWrite` hat. **Kein Eingabefeld, das in einen 403 läuft.**
  - `canWrite=false` → Composer als **expliziter Read-Only-Zustand** mit ehrlichem Grund (`comm_readonly_hint`), nicht ein deaktiviertes Feld ohne Erklärung.
- **Optimistisches Senden (falls Dev so umsetzt):** gesendete Nachricht erscheint als **„wird gesendet"** (pending), bis der Server sie per `message.id` bestätigt — **nicht** als zugestellt darstellen, solange unbestätigt (§5).

---

## 5. Zustände & Disclosure-Honesty (verbindlich)

| Zustand | Darstellung | Disclosure-Regel |
|---|---|---|
| **Lädt** (Historie) | Skeleton/Spinner | nicht als „leer" zeigen |
| **Leerer Kanal** | Empty-State („Noch keine Nachrichten") | — |
| **Keine lesbaren Kanäle** | Empty-State Kanalliste | nie Kanäle listen, die der Viewer nicht lesen darf |
| **Live verbunden** | dezenter „Live"-Indikator | nur zeigen, wenn WS wirklich offen |
| **Reconnecting/Offline** | **ehrlicher Banner**: „Verbindung getrennt – Stand evtl. nicht aktuell" (`comm_status_offline`, no-arg) | Timeline **nicht** als live/aktuell ausgeben, solange getrennt |
| **Senden pending** | Nachricht als „wird gesendet" markiert | **nicht** als zugestellt zeigen, bis `message.id` bestätigt |
| **Senden abgelehnt (403/ACL)** | klare Fehlerzeile (`comm_send_denied`) | ehrlich: „keine Schreibrechte in diesem Kanal" |

**Kern-Disclosure:**
1. **ACL ist Sichtbarkeits-Wahrheit:** nur `readableChannels`/`visibleMessages` (fail-closed). Kein Kanal/keine Nachricht, die der Viewer nicht lesen darf — auch nicht ausgegraut.
2. **Verbindungs-Ehrlichkeit:** getrennte/veraltete Timeline nie als „live" darstellen.
3. **Zustellungs-Ehrlichkeit:** optimistische Nachrichten klar als unbestätigt kennzeichnen.
4. **Keine Garantie-Suggestion** durch `MessageKind`/Farbe (Status ≠ Garantie; Farbe = Identität, nicht Berechtigung).

---

## 6. Reconnect-Idempotenz (Design-Hinweis für Dev)

- **Dedup über `Message.id`** — die einzige Identitätsachse; nie über (from,ts,body) raten.
- **Reconnect-Muster:** beim Wiederverbinden Historie `since = ts(letzte bekannte Nachricht)` nachladen und per `id` in die bestehende Liste mergen → **keine Duplikate**, keine Lücke.
- **Ordering:** strikt nach `ts`; bei ts-Gleichstand `id` als Tiebreaker (stabil).
- **Live-Status** an den tatsächlichen WS-Zustand koppeln (treibt §5-Banner).

---

## 7. Tokens & i18n

- **Tokens:** überwiegend **Reuse** aus CYP-12 (`state-tokens.json`) + CYP-14 (`color-coding-tokens.json`). Nur **wenige neue** Comm-Panel-Tokens (Ungelesen-Badge, Kanal-Auswahl, Verbindungs-Banner, Composer-Read-Only) in `docs/design/comm-panel-tokens.json`.
- **i18n:** `compose.resources`/Underscore — Keys in `docs/design/comm-panel-keys.md`.

> **⚠ Shared-Key-Drift:** Keys landen in `:app:shared`-Resources → konsumierendes Modul (S6-Implementierung) muss re-syncen. **Lieferung mit der S6-Umsetzung timen.**

---

## 8. Offene Punkte / Dev-Asks (über PO)

1. **WS-Frame-DTOs** (`MessageEvent`/`AclEvent`/`ChannelsEvent`, 02 §8) fehlen in `:core` — Vertrags-Ticket vor S6.
2. **Viewer-Identität im MVP:** Ist der UI-Viewer der **Operator** (sieht alle Kanäle / sendet als Operator, 02 §14 / 05 D-Frage 5) oder ein Agent? Bestimmt `readableChannels(viewer)` und die Composer-Rechte. PO-Entscheidung nötig.
3. **Optimistisches Senden** ja/nein — beeinflusst den Pending-Zustand (§4/§5).
4. **Markdown-Umfang** im `Message.body` (voll vs. Teilmenge) im MVP.
5. **Panel als Fenster** (CYP-10-Host) bestätigt, oder dockbarer Sonderbereich?
