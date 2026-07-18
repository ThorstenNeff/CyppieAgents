# CYP-705 — Ungelesen-pro-Kanal (web-ts) · UX-Spec

**Ticket:** CYP-705 (Epic CYP-640 „Discord-Feel", Achse 2) · **Für:** Dev5 · **Reconcile mit:** Backend2 (server-`seen`-Messung, parallel) · **Von:** UIUX2 (Team-2)
**Baseline:** develop `1b15f6b3` (am Objekt gemessen) · **Stand:** 2026-07-18
**Tooling-Grenze:** Zustände/Regeln unten sind headless render-test-**messbar**. Runtime-„Feel"/Pixel = guided-human, hier nicht behauptet.

---

## §0 Ehrlichkeits-Haken (VORNE — bindend, prägt alles darunter)
Ein Ungelesen-Badge/-Trenner ist **nur dann ein wahrhaftiges „neu seit du zuletzt gelesen hast"**, wenn ein **server-autoritativer, pro-Prinzipal `lastRead`/`seen`-Cursor** dahinter steht. Konsequenzen:
- **Kein rein-lokaler Marker als Quelle.** Ein client-only `lastRead` (localStorage/Speicher) täuscht **durable Gewissheit** vor: nicht cross-session/-device, driftet, und sagt „gelesen", wo real nur „vorbeigescrollt" gilt. **Verboten** als Unread-of-Record-Quelle.
- **Drei distinkte Zustände, nicht zwei** (Backend2s `known:false`-Kante + Assist2s **Absence-of-Signal-Falle**):
  1. **server-confirmed-read** (Cursor bekannt, `unreadCount=0`) → **nichts** (legitimes all-clear — wir *wissen*, es ist gelesen).
  2. **UNKNOWN** (kein Cursor / lädt / `known:false`) → **sichtbarer neutraler Indikator** („•/unbekannt"), **NICHT Abwesenheit.** Denn „keine Badge" liest sich visuell als all-clear — Stille wäre also die Lüge, nicht die Ehrlichkeit. Neutral getönt, **kein Alarm** ([[over-alarm-is-also-dishonest]]): unknown ist kein Fehler, nur „nicht bestimmt".
  3. **unread>0** → **Count-Badge**.
- **Nie** ein affirmatives „0 ungelesen / alles gelesen" rendern, das der Client nicht garantieren kann; und **nie** UNKNOWN als Abwesenheit rendern (das *ist* das falsche all-clear). Die CYP-288-Klasse **am Render, nicht nur in der Intention**: unknown ≠ empty muss man *sehen*.
- **Kein „seed-at-join"** (entschieden mit PO): den Cursor beim Beitritt NICHT auf „latest" setzen — das fabriziert „alles davor gelesen", das du nie warst. Vor-Beitritts-Historie ist **unknown** (neutraler „•"), nicht „gelesen".

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

**Backend2s GEPINNTER `:core`-Contract (Stand 2026-07-18, Wahl A):** `ChannelReadState{lastReadSeq: Long (non-null), unreadCount: Int}` pro **präsentem** Kanal (cursorlose Kanäle werden omit-ted); **`seq` aus dem Store exponiert** (autoritativer Schlüssel, nicht `ts`); **Read-Receipts OUT** (kein „der Absender sieht, dass du gelesen hast" — Read-State ist **self-only**, das hält die Ehrlichkeit sauber, kein Über-Anspruch gegenüber Dritten). Der Server berechnet `unreadCount` pro Prinzipal (eigene Nachrichten ausgeschlossen) → Client zeigt nur an, **kein Client-`identityId`**.

**UNKNOWN-Kodierung — EINE Regel (Wahl A, bilateral ratifiziert 2026-07-18):** cursorlose Kanäle werden **OMIT-ted**, also trägt jeder **präsente** `ChannelReadState` einen echten Cursor → **`lastReadSeq: Long` non-null** (kein `| null`). Damit ist die Honesty **strukturell**, nicht per Konvention:
- **UNKNOWN ⟺ Kanal fehlt** in der Read-State-Antwort (neutraler „•"). Die **einzige** Unknown-Regel.
- **präsent, `unreadCount=0` ⇒ confirmed-read** (nichts) · **präsent, `unreadCount>0` ⇒ unread** (Count). Präsent ⟺ Cursor vorhanden ⟺ `unreadCount` **autoritativ** (es kann keinen präsenten-aber-cursorlosen Kanal geben → keine unknown-als-Count-Lüge möglich).
- **Anti-Drift (Typ-Ebene):** der Spec-/Contract-Typ ist **non-null**, weil Backend2 unter (A) nie null emittiert — kein `number|null` im Vertrag, kein Typ-Level-Unknown-Zustand. Dev5s `channelUnread()` (`entry===undefined → unknown`) matcht (A); sein Modell-Typ folgt dem gepinnten non-null `:core`-Typ.
- **Nicht verwechseln — zwei Ebenen, beide bleiben:** die Typ-Non-Null-Spitzung **verbietet NICHT** Dev5s **Fetch-Grenzen-Guard fail-closed** (Laufzeit: ein Wire-`null` auf einem präsenten Eintrag = **Vertragsbruch** ⇒ UNKNOWN, nie „0"/Count). Das ist **Durchsetzung am untrusted Rand** (verify-don't-trust), **kein** Typ-Level-null-Zustand. Typ = Vertrag; Guard = Defense-in-Depth. Beide korrekt, verschiedene Ebenen.

**Trenner-Eingabe-Vertrag (Tester2 #68, explizit gefoldet):** `firstUnreadIndex` = `findIndex(m.seq > cursor)` liefert nur dann die **erste** ungelesene Grenze, wenn die Timeline-`messages` **seq-aufsteigend** ankommen. Das ist ein **expliziter Eingabe-Vertrag** (garantiert durch Store/Merge, Tester2 #68) — der Client **sortiert nicht selbst nach** (redundant/riskant), er **verlässt sich** darauf. Zwei Preconditions, damit der Trenner beim Cursor-Landing stimmt: **(1)** die Nachricht trägt `seq` (heute wire-seitig noch nicht — `Message` = `{id,channelId,from,body,ts,meta,projectId}`; landet mit dem gepinnten Contract), **(2)** seq-aufsteigende Reihenfolge. Bricht (2), ist der Trenner out-of-contract (falsch platziert) — daher als Vertrag fixiert, nicht als Client-Fallback maskiert.

## §3 Layout & Zustände (Reuse, keine Divergenz)
Erweitert die Comm-Nav (`CommPanel.tsx`) + Timeline; alle bestehenden Zustände (empty / CYP-288 load-error / revoked / disclosure) bleiben und **beherrschen** weiter.

- **Per-Kanal-Indikator** am Kanal-Button — Glyph/Text + `aria-label`, **Farbe nie allein** (WCAG 1.4.1). **Nicht** rein present-only: UNKNOWN ist **sichtbar**, nicht Abwesenheit. Drei distinkte, **visuell unterscheidbare** Renders:
  - **unread>0** (server) → **Count-Badge** (Zahl + aria-label „{n} ungelesen in {channel}").
  - **confirmed-read** (`ChannelReadState` da, `unreadCount=0`) → **nichts** (ehrlich „gelesen", autoritativ).
  - **UNKNOWN** (kein `ChannelReadState` / `known:false` / lädt / Fläche unverfügbar) → **sichtbarer neutraler Indikator** „•" (neutral getönt, kein Alarm) + `aria-label` „Ungelesen-Status unbekannt". **Kein** Count, **kein** all-clear, **keine** Stille.
- **„Neue Nachrichten"-Trenner** in der Timeline — eine Trenner-Row im `<ol>` an der **ersten-ungelesen-Grenze** (erste `seq > lastReadSeq`). Nur mit Server-Cursor gerendert; ohne Cursor **kein** Trenner.
- **„Was ist neu"** = Badge + Trenner zusammen. Optional (Scope-Notiz, nicht MVP-bindend): „zum ersten Ungelesenen springen".
- **Mark-Read-Auslöser:** wenn der Kanal betrachtet wird (offen + ungelesene im Viewport / bis unten gescrollt) → Client `POST …/read {upToSeq}`; **Badge klärt erst auf den Server-Echo** des neuen Cursors (non-optimistisch — der Cursor ist Server-Wahrheit, kein lokaler Scroll-Optimismus). „Gelesen" = der Server hat deinen Cursor **notiert** (durable), nicht „du hast es verstanden".

## §4 Der degradierte/unverfügbare Pfad (Ehrlichkeits-Kern, ausgeführt)
- Read-State-Fläche fehlt (Backend2 nicht gebaut) / `known:false` / Runtime-Fehler → **UNKNOWN-Zustand: der sichtbare neutrale „•"-Indikator** (§3), **nicht** Abwesenheit. Der **Trenner** entfällt (kein Cursor → keine ehrliche Grenze), aber der Per-Kanal-Indikator zeigt **sichtbar „unbekannt"** — Stille würde als all-clear gelesen (**Absence-of-Signal-Falle**).
- **Nie** als „alles gelesen" präsentieren: kein „✓ alle gelesen", kein grünes all-clear. Read-State unbekannt = **unbekannt** (sichtbar, nicht still).
- **Kein lokaler Fallback** als Unread-of-Record-Quelle (localStorage/Speicher täuscht durable Gewissheit). Ein lokaler Scroll darf den **Mark-Read-CALL** treiben, aber die **Anzeige** leitet sich aus dem Server-Cursor ab.
- Analog CYP-288: eine unverfügbare Read-State ist „unknown", nie ein fabriziertes empty/all-clear.

## §5 Resource-Keys (Design; Dev5 landet mit Impl — [[shared-key-landing]], Shared-Check re-syncen)
- `a11y_comm_unread` = „{n} ungelesen in {channel}" (Badge-a11y).
- `comm_unread_divider` = „Neu" (Trenner; EN „New").
- `comm_unread_unknown` = „•" (Indikator-Glyph, neutral) + `a11y_comm_unread_unknown` = „Ungelesen-Status unbekannt" (der sichtbare UNKNOWN-Indikator §3 — unknown, **nicht** all-clear).
DE/EN-Strings liefere ich auf Zuruf; **Keys landen mit Dev5s Impl**, nicht vorab (Shared-Drift-Flag).

## §6 testTags (charset-safe)
`comm.channel.{id}.unreadBadge` (Count) · `comm.channel.{id}.unreadUnknown` (neutraler „•"-Indikator) · `comm.unread.divider` (Kanal-Id = stabile Config-Id, charset-ok). Opake Werte nie roh als Scope ([[testtag-scopeid-charset-safe]]).

## §7 Parität
**Kein** Per-Kanal-Unread-of-Record existiert — weder Server (kein Cursor) noch CMP (nur B1-Soft-Aktivität) noch web-ts (nichts). CYP-705 ist **neu auf beiden Seiten** (Backend2-Fläche + Client). Reuse-Anker: present-only-Marker-Idiom, CYP-288 unknown≠empty-Ehrlichkeit, Event-Log `seq`-Autorität (nicht `ts`), CMP-B1-Register-Trennung.

## §8 Reconcile mit Backend2 — gegen den GEPINNTEN Contract (Stand 2026-07-18)
Backend2 hat `ChannelReadState{lastReadSeq: Long non-null, unreadCount}` gepinnt (`seq` exponiert, Read-Receipts out, **Wahl A**). Vier Touch-Points — **alle geschlossen:**
- **① Carrier — RATIFIZIERT (2026-07-18, UIUX2 ⇄ Backend2, converged): standalone `GET /api/read-state`.** Begründung: Read-State ist **volatil** (jede Nachricht bewegt `unreadCount`, jedes Lesen `lastReadSeq`) — additiv auf dem **stabilen** `/api/channels` würde entweder den Kanal-List-Refetch an Read-State koppeln oder `/api/channels` volatil machen. Standalone + der standalone `ReadStateEvent` (③) bilden einen **kohärenten Read-State-Kanal**. **UNKNOWN-Naht GESCHLOSSEN (Wahl A):** cursorlose Kanäle omit-ted ⇒ **UNKNOWN ⟺ Kanal fehlt** (eine Regel); `lastReadSeq` **non-null** (Spec-Typ gespitzt, kein toter `null`-Zweig, keine Drift). Präsent ⟺ Cursor ⟺ `unreadCount` autoritativ (§2).
- **② UNKNOWN-Render — gefoldet:** neutraler sichtbarer „•", **kein seed-at-join** (§0/§3).
- **③ Live-Event — ENTSCHIEDEN: standalone `ReadStateEvent`** (mein UX-Call = Backend2-Lehnen). Begründung: eigener Register — **nicht** in CYP-704/Mentions falten ([[reconcile-not-collapse-distinct-states]]); trägt Cross-Device-Cursor-Advance und **ist** der non-optimistische Server-Echo (§3), der die Badge live + ehrlich klärt.
- **④ non-optimistisch — bestätigt:** read erst nach 200/`ReadStateEvent`, nie lokaler Scroll-Optimismus (§3).

- **⑤ Trenner-Eingabe-Vertrag (Tester2 #68) — gefoldet:** Timeline-`messages` seq-aufsteigend (+ `seq` am Message) als expliziter Vertrag; Client sortiert nicht nach (§2).
- **⑥ Reconnect → frischer Re-fetch (2b-AC, ratifiziert) — BESTÄTIGT:** nach WS-Reconnect wird `GET /api/read-state` **frisch** geholt (autoritativ); **UNKNOWN bis der Fetch da ist** — nie stale-als-aktuell zeigen (der Cursor kann während des Ausfalls anderswo advanct sein). Identisch zum Cold-Load-Pfad (§4/§9b); Omissionen in der frischen Antwort ⇒ UNKNOWN.
- **⑦ `ReadStateEvent` = VOLLER per-Kanal-State (kein Delta) (2b-AC, ratifiziert) — BESTÄTIGT:** `{channelId, lastReadSeq, unreadCount}` → der Renderer **ersetzt** den Kanal-Zustand wholesale, **keine Client-Merge/Akkumulation**. **Idempotent:** ein doppeltes/verpasstes/umsortiertes Event self-healt (der nächste volle State ist korrekt, egal der Historie) — vermeidet die Delta-Drift-Klasse, hält den Client **display-only** (rechnet nie Unread). Komponiert sauber mit ⑥ (Live-Upserts + Reconnect-Re-fetch = volles Bild inkl. Omissionen→UNKNOWN). *(3. Tester2-Frage `unreadCount clamp≥0` = rein Server; Client-`<=0→read` (§2) ist zusätzlich defensiv safe.)*

**Status:** **①②③④⑤ ALLE geschlossen; ① inkl. UNKNOWN-Naht = Wahl A (omit, `lastReadSeq` non-null) bilateral ratifiziert (2026-07-18).** Keine offene Reconcile-Naht mehr → **Backend2 baut Server, Dev5 den Cursor-Pfad** gegen diesen gepinnten Stand. Dev5s `channelUnread()` matcht (A) heute schon; sein Modell-Typ folgt dem non-null `:core`-Typ.

## §9 Render-Test-Zähne (diskriminierend, mutation-aware) ([[test-must-discriminate]])
1. **Drei distinkte, unterscheidbare Renders:** server unread=3 → Count-Badge „3" (Glyph+Count-Text+aria-label); confirmed-read (`ChannelReadState` da, `unreadCount=0`) → **nichts**; **UNKNOWN** (kein `ChannelReadState`) → **sichtbarer neutraler „•"** (+aria-label). *(Non-vacuous: die drei müssen visuell **unterscheidbar** sein — v. a. UNKNOWN ≠ confirmed-read.)*
2. **★ Absence-of-Signal-Guard:** UNKNOWN rendert **sichtbar** (nicht als Abwesenheit, nicht wie confirmed-read/„0 gelesen"). *(Mutation: UNKNOWN→Stille ODER UNKNOWN==nichts → RED — genau die Falle, dass „keine Badge" als all-clear liest.)* Plus: kein lokaler Fallback-Zähler, kein „alles gelesen"-Affirmativ.
3. Trenner an **erster** `seq > cursor` bei **seq-aufsteigender** Eingabe (Vertrag §2/Tester2 #68); **kein** Cursor → **kein** Trenner. *(Mutation: unsortierte Eingabe → Trenner fehlplatziert = out-of-contract, im Test seq-aufsteigend fixieren.)*
4. **non-optimistisch:** Badge klärt **nur** auf Server-Echo des neuen Cursors, **nicht** auf lokalen Scroll allein. *(Mutation: optimistisches lokales Clear → RED.)*
5. **colour-never-sole:** Badge trägt Count-Text + aria-label (nicht nur gefärbter Dot). *(Mutation: colour-only → RED.)*
6. **reconcile-not-collapse:** ein Soft-Aktivitäts-Signal (falls je geportet) trägt **andere** testid/Copy als Unread-of-Record — nie als „ungelesen von record" etikettiert. *(Guard gegen Kollaps.)*
7. eigene Nachrichten heben Unread **nicht** (server-`from`-Ausschluss); Unread ist **pro Kanal**, nicht comm-weit.

## §9b Cursor-Pfad UX-QA — VOR-REGISTRIERT (pairt mit Tester2s QA-Prep)
Wenn Dev5 den echten Cursor-Pfad baut (`readState: unavailable → available`, live `ReadStateEvent`, mark-read `POST`), prüft **diese Linse** — dieselbe, die den Gerüst-Drift fing — die **dynamischen Übergänge**, die das degradierte Gerüst nicht ausübte. Honesty-Lens, **komplementär** zu Tester2s Verhaltens-/Pointer-QA (nicht duplizierend).
1. **★ Load-Übergang — UNKNOWN bleibt sichtbar, kein all-clear-Flash:** während `GET /api/read-state` in-flight ist (vor erster Auflösung), bleibt jeder Kanal **UNKNOWN „•"** (Default-UNAVAILABLE) — **nie** kurz Stille/„0"/nichts. *(Mutation: Load rendert Abwesenheit/„0" → RED = der falsche all-clear-Flash.)*
2. **Available-Übergang — absent bleibt UNKNOWN:** nach Landung flippen Kanäle auf count/read; **in der Antwort fehlende Kanäle bleiben UNKNOWN „•"** (Wahl A omit), **nie** „0"/read. *(Mutation: absent → „0"/nichts → RED.)*
3. **Live `ReadStateEvent` — non-optimistisch:** Event advanct Cursor / ändert `unreadCount` → Badge+Trenner updaten **auf das Event**, nicht spekulativ.
4. **Mark-read — Server-Echo, nicht lokaler Scroll:** Kanal betrachten POSTet mark-read; Badge klärt **nur** auf `ReadStateEvent`/200, **nicht** auf den Scroll, der ihn auslöste. *(Mutation: optimistisches lokales Clear → RED.)*
5. **Trenner beim Cursor-Landing:** an erster `seq > cursor` (seq-aufsteigend, Vertrag §2/#68); kein Cursor → kein Trenner; ein Cursor-Update bewegt den Trenner korrekt.
6. **Fetch-Grenzen-Guard:** Wire-`null` auf präsentem Eintrag (Vertragsbruch) ⇒ UNKNOWN, nie „0" (§2 Laufzeit-Guard, distinkt vom non-null Typ).
7. **Reconnect (§8-⑥):** WS-Reconnect → frischer Re-fetch, **UNKNOWN während der Lücke** — nie stale-als-aktuell; Omissionen in der frischen Antwort → UNKNOWN. *(Mutation: reconnect zeigt alten Count weiter → RED = stale-als-Wahrheit.)*
8. **Voller-State-Event idempotent (§8-⑦):** dasselbe `ReadStateEvent` zweimal angewandt = identischer Render (kein Akkumulieren). *(Mutation: Delta-Akkumulation +N → RED bei Dup/Reorder.)*
**Tool-Grenze:** die Zustands-Übergänge sind **headless render-test-messbar** (readState-Prop wechseln / `ReadStateEvent` feuern im Render-Test); das echte WS-Timing/Flicker-*Feel* am Live-Server = guided-human. Ich prüfe die State-Machine, Tester2 die Verhaltens-/Pointer-Ebene.

## §10 Übergabe-Flags an den Koordinator
- **①②③④⑤ RATIFIZIERT (2026-07-18, converged mit Backend2), UNKNOWN-Naht = Wahl A (omit, `lastReadSeq` non-null) geschlossen** → **Backend2 baut Server, Dev5 den Cursor-Pfad** gegen den gepinnten Stand. Keine offene Reconcile-Naht.
- **UX-QA Gerüst `b52e21b4` → Fix `59dec48c` render-confirm PASS → PO1** (drei-Zustands-Render, Absence-of-Signal-Zahn geflippt, 18/18 headless). Optionale „•"-gedämpfte-Tönung = non-blocking-Politur, Kandidat fürs Cursor-Pfad-PR.
- **Identitäts-Entkopplung:** anders als CYP-704-NOTIFY braucht CYP-705 **kein** Client-`identityId` (server-Prinzipal-keyed) → **nicht** vom NOTIFY-Pending blockiert.
- **Identitäts-Entkopplung:** anders als CYP-704-NOTIFY braucht CYP-705 **kein** Client-`identityId` (server-Prinzipal-keyed) → **nicht** vom NOTIFY-Pending blockiert.
- **Shared Keys** landen mit Dev5s Impl.
