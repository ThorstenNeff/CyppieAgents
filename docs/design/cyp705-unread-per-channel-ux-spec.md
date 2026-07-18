# CYP-705 — Ungelesen-pro-Kanal (web-ts) · UX-Spec

**Ticket:** CYP-705 (Epic CYP-640 „Discord-Feel", Achse 2) · **Für:** Dev5 · **Reconcile mit:** Backend2 (server-`seen`-Messung, parallel) · **Von:** UIUX2 (Team-2)
**Baseline:** develop `1b15f6b3` (am Objekt gemessen) · **Stand:** 2026-07-18
**Tooling-Grenze:** Zustände/Regeln unten sind headless render-test-**messbar**. Runtime-„Feel"/Pixel = guided-human, hier nicht behauptet.

---

## §0 Ehrlichkeits-Haken (VORNE — bindend, prägt alles darunter)
Ein Ungelesen-Badge/-Trenner ist **nur dann ein wahrhaftiges „neu seit du zuletzt gelesen hast"**, wenn ein **server-autoritativer, pro-Prinzipal `lastRead`/`seen`-Cursor** dahinter steht. Konsequenzen:
- **Kein rein-lokaler Marker als Quelle.** Ein client-only `lastRead` (localStorage/Speicher) täuscht **durable Gewissheit** vor: nicht cross-session/-device, driftet, und sagt „gelesen", wo real nur „vorbeigescrollt" gilt. **Verboten** als Unread-of-Record-Quelle.
- **Server-`seen` nicht verfügbar → kein Unread-of-Record-Badge/-Trenner.** Und — kritisch — **Abwesenheit ≠ all-clear:** „keine Badge = **unbekannt**, nicht „alles gelesen"". **Nie** ein affirmatives „0 ungelesen / alles gelesen" rendern, das der Client nicht garantieren kann (die CYP-288-Klasse: unknown ≠ empty).
- **`seen=0` server-bestätigt** ist dagegen ein **ehrliches** „gelesen" (autoritativ) → legitime Abwesenheit der Badge. Der Unterschied zum degradierten Fall ist *Wissen*: bestätigte 0 vs. unbekannt.

## §1 Reconcile, nicht kollabieren — zwei distinkte Register ([[reconcile-not-collapse-distinct-states]])
Es gibt **zwei** „neu"-Signale; sie dürfen **nicht** ineinander kollabieren:
| Register | Was | Herkunft | Ehrlichkeits-Status |
|---|---|---|---|
| **Soft-Aktivität** (existiert: CMP CYP-55 B1 `unreadCount`) | „neu seit du zuletzt hingeschaut hast", **comm-weit** | **session-lokal, client-only, nie persistiert** | ehrlich **als solches** — CMP-Doc sagt selbst: *„not 'unread of record'"*. Täuscht keine Gewissheit, weil es keine beansprucht. |
| **Unread-of-Record** (NEU: CYP-705) | „ungelesen **pro Kanal**", durable, cross-session/-device | **server `lastRead`-Cursor** | nur wahrhaftig **mit** Server-Cursor; sonst gar nicht. |
- **Verboten:** den B1-Session-Zähler zu einem Per-Kanal-„Unread"-Badge **hochstufen** — das ist exakt der „lokale-Marker-täuscht-Gewissheit"-Fehler. Bleiben getrennt: Soft = weicher Dot/Hinweis („war was los, während du weg warst"), Unread-of-Record = autoritative Per-Kanal-Zahl.
- **web-ts hat heute weder** B1 **noch** Unread (verifiziert `1b15f6b3`) → CYP-705 baut den Unread-of-Record-Pfad neu; ein etwaiger B1-Port ist eine **separate** Entscheidung und darf nicht als Unread-of-Record etikettiert werden.

## §2 Wire-Wahrheit + benötigter Contract (die Reconcile-Fläche mit Backend2)
**Heute (Baseline `1b15f6b3`):** **kein** server-Read-State. `GET /api/inbox` liefert nur `arr<Message>` (ACL-gefiltert, optional `since`) — **kein Cursor**, kein `lastRead`/`seen`. `SqliteMessageStore` führt einen **monotonen `seq`** (AUTOINCREMENT) — der autoritative Ordnungsschlüssel (nicht `ts`; Client-Uhren/`ts` sind „observed", nicht autoritativ — analog Event-Log seq-Ordnung).

**Architektur-Schlüssel (löst die Identitäts-Frage, die CYP-704-NOTIFY pausierte):** der Server **löst den authentifizierten Prinzipal pro Request auf** (`hub.inbox(participant)`, `CommRoutes.kt:199`). Damit ist ein **pro-Prinzipal `lastRead` server-seitig keybar, OHNE dem Client ein `identityId` zu geben** (`AuthMe` bleibt content-free). **Der Server berechnet den Unread-Count** (er kennt Prinzipal + Nachrichten inkl. `from`) → der Client **zeigt nur an**, braucht selbst **kein** „me".

**Client-Contract, den Backend2s Fläche liefern muss (zu reconcilen):**
1. **Read-Cursor pro Kanal für den Aufrufer** — z. B. `lastReadSeq` je Kanal (in `GET /api/channels` additiv, oder dediziert `GET /api/read-state`).
2. **Unread-Count pro Kanal, server-berechnet** (`count(seq > lastReadSeq)`, eigene Nachrichten ausgeschlossen) — **nicht** client-abgeleitet über eine Teilseite (das unter-zählt; falls nur client-seitig möglich, ehrlich „N+" offenlegen).
3. **Mark-Read-Write** — `POST /api/channels/{id}/read {upToSeq}` → Server schreibt den Cursor **für den authentifizierten Prinzipal**, echo't den neuen Cursor (**non-optimistisch**, wie ACL/Share).
4. **Ordnungsschlüssel = `seq`** (nicht `ts`).

## §3 Layout & Zustände (Reuse, keine Divergenz)
Erweitert die Comm-Nav (`CommPanel.tsx`) + Timeline; alle bestehenden Zustände (empty / CYP-288 load-error / revoked / disclosure) bleiben und **beherrschen** weiter.

- **Per-Kanal-Badge** am Kanal-Button — **present-only**-Marker-Idiom (`FidelityBadge`/`WindowActivityBadge`): Glyph + **Count-Text** + `aria-label`, **Farbe nie allein** (WCAG 1.4.1), fail-closed by absence.
  - `unread>0` (server) → Badge mit Count.
  - `unread=0` **server-bestätigt** → keine Badge (ehrlich „gelesen").
  - **server-`seen` unverfügbar** → **keine Badge** + **kein** all-clear (present-only ⇒ still; falls die Nav eine Read-Spalte hätte, zeigt sie „Status unbekannt", nie „0/gelesen").
- **„Neue Nachrichten"-Trenner** in der Timeline — eine Trenner-Row im `<ol>` an der **ersten-ungelesen-Grenze** (erste `seq > lastReadSeq`). Nur mit Server-Cursor gerendert; ohne Cursor **kein** Trenner.
- **„Was ist neu"** = Badge + Trenner zusammen. Optional (Scope-Notiz, nicht MVP-bindend): „zum ersten Ungelesenen springen".
- **Mark-Read-Auslöser:** wenn der Kanal betrachtet wird (offen + ungelesene im Viewport / bis unten gescrollt) → Client `POST …/read {upToSeq}`; **Badge klärt erst auf den Server-Echo** des neuen Cursors (non-optimistisch — der Cursor ist Server-Wahrheit, kein lokaler Scroll-Optimismus). „Gelesen" = der Server hat deinen Cursor **notiert** (durable), nicht „du hast es verstanden".

## §4 Der degradierte/unverfügbare Pfad (Ehrlichkeits-Kern, ausgeführt)
- Read-State-Fläche fehlt (Backend2 nicht gebaut) **oder** Runtime-Fehler → Unread-of-Record-Badge **und** Trenner **unterdrückt** (present-only ⇒ einfach abwesend).
- **Nie** als „alles gelesen" präsentieren: kein „✓ alle gelesen", kein grünes all-clear. Read-State unbekannt = **unbekannt**.
- **Kein lokaler Fallback** als Unread-of-Record-Quelle (localStorage/Speicher täuscht durable Gewissheit). Ein lokaler Scroll darf den **Mark-Read-CALL** treiben, aber die **Anzeige** leitet sich aus dem Server-Cursor ab.
- Analog CYP-288: eine unverfügbare Read-State ist „unknown", nie ein fabriziertes empty/all-clear.

## §5 Resource-Keys (Design; Dev5 landet mit Impl — [[shared-key-landing]], Shared-Check re-syncen)
- `a11y_comm_unread` = „{n} ungelesen in {channel}" (Badge-a11y).
- `comm_unread_divider` = „Neu" (Trenner; EN „New").
- `comm_unread_unavailable` = „Ungelesen-Status nicht verfügbar" (nur falls ein Slot es sonst als all-clear läse; unknown, **nicht** all-clear).
DE/EN-Strings liefere ich auf Zuruf; **Keys landen mit Dev5s Impl**, nicht vorab (Shared-Drift-Flag).

## §6 testTags (charset-safe)
`comm.channel.{id}.unreadBadge` (Kanal-Id = stabile Config-Id, charset-ok) · `comm.unread.divider` · (degradiert) `comm.unread.unavailable`. Opake Werte nie roh als Scope ([[testtag-scopeid-charset-safe]]).

## §7 Parität
**Kein** Per-Kanal-Unread-of-Record existiert — weder Server (kein Cursor) noch CMP (nur B1-Soft-Aktivität) noch web-ts (nichts). CYP-705 ist **neu auf beiden Seiten** (Backend2-Fläche + Client). Reuse-Anker: present-only-Marker-Idiom, CYP-288 unknown≠empty-Ehrlichkeit, Event-Log `seq`-Autorität (nicht `ts`), CMP-B1-Register-Trennung.

## §8 Reconcile-Checkliste mit Backend2 (das ist die abzugleichende Fläche)
Backend2 misst/baut die server-`seen`-Fläche; **diese Punkte gleichen wir ab**, bevor Dev5 fest gegen den Contract baut:
1. Cursor-Träger: `lastReadSeq` in `/api/channels` **oder** `/api/read-state`? 2. Unread-Count **server-berechnet** (ja/nein; wenn client-seitig → „N+"-Ehrlichkeit). 3. Mark-Read: `POST /api/channels/{id}/read {upToSeq}`, non-optimistischer Echo. 4. Schlüssel `seq` (nicht `ts`). 5. Prinzipal-Keying server-seitig (kein Client-`identityId`). 6. Verhalten wenn Fläche fehlt (Client degradiert wie §4 — **kein** all-clear).

## §9 Render-Test-Zähne (diskriminierend, mutation-aware) ([[test-must-discriminate]])
1. server unread=3 (Kanal X) → Badge „3" (Glyph + **Count-Text** + aria-label); Kanal mit server-0 → **keine** Badge.
2. **★ degradiert:** Read-State unverfügbar → **keine** Badge **und kein** all-clear/„0 gelesen"-Text. *(Mutation: lokaler Fallback-Zähler ODER „alles gelesen" → RED — die Gewissheits-Grenze.)*
3. Trenner an erster `seq > cursor`; **kein** Cursor → **kein** Trenner.
4. **non-optimistisch:** Badge klärt **nur** auf Server-Echo des neuen Cursors, **nicht** auf lokalen Scroll allein. *(Mutation: optimistisches lokales Clear → RED.)*
5. **colour-never-sole:** Badge trägt Count-Text + aria-label (nicht nur gefärbter Dot). *(Mutation: colour-only → RED.)*
6. **reconcile-not-collapse:** ein Soft-Aktivitäts-Signal (falls je geportet) trägt **andere** testid/Copy als Unread-of-Record — nie als „ungelesen von record" etikettiert. *(Guard gegen Kollaps.)*
7. eigene Nachrichten heben Unread **nicht** (server-`from`-Ausschluss); Unread ist **pro Kanal**, nicht comm-weit.

## §10 Übergabe-Flags an den Koordinator
- **Blockiert auf Backend2s server-`seen`-Fläche** — Dev5 kann UI-Gerüst + degradierten Pfad (§4) **jetzt** bauen (present-only ⇒ ohne Fläche einfach still, ehrlich), aber der **echte** Unread-Pfad landet erst mit dem Server-Cursor. §8 vor dem Fest-Bau reconcilen.
- **Identitäts-Entkopplung:** anders als CYP-704-NOTIFY braucht CYP-705 **kein** Client-`identityId` (server-Prinzipal-keyed) → **nicht** vom NOTIFY-Pending blockiert.
- **Shared Keys** landen mit Dev5s Impl.
