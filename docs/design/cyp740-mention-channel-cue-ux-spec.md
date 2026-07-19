# CYP-740 — Mention-Channel-Level-Cue (web-ts) · UX-Spec (agent-scoped)

**Für:** Dev5 · **Von:** UIUX2 (Team-2) · **Baseline:** develop `f8a0960c` (am Objekt) · **Aus:** CYP-703 Gap-Map (n)-Gap #1
**human-identity:** **n** (agent-scoped — highlightet `@agent`, nie einen Menschen) · **Tooling-Grenze:** headless render-test-messbar.

---

## §0 Scope + Grenze
Ein **Cue an Kanal-Buttons**, wenn ein Kanal **`@agent`-Mentions** trägt — die Completeness der Mention-Highlight **über die inline-Chips hinaus** (heute unsichtbar, solange man den Kanal nicht offen hat). **Viewer-independent, KEIN „me".**
- **★ Grenze:** die stärkere Version „**DEINE** Mentions" (die einen Menschen adressierbar machen / „welcher Mensch?" wissen müsste) = **`human-identity: j` → PL-Weiche → NICHT hier.** Diese Spec ist rein „Kanal **enthält** eine `@agent`-Mention".

## §1 Der Cue hat DREI Zustände (Dev5-Fund) — der Honesty-Kern
Der Client hält Kanal-Nachrichten **nur für geöffnete Kanäle** (`loadMessages` feuert erst beim Kanalwechsel; live-empfangene Nachrichten kommen für aktive Kanäle dazu). Also je Kanal:
| Zustand | Bedingung | Render |
|---|---|---|
| **hat Mentions** | geladen, ≥1 `mention`-Segment | **„@"-Cue** |
| **keine** | geladen, kein `mention`-Segment | **nichts** (ehrlich — wir haben geschaut) |
| **★ unbekannt** | **nie geladen** (nie geöffnet, keine live-Nachricht) | **STILL — kein Marker, aber NIE „keine Mentions" (present-only-Verbot)** |
**★ Der Kern — der ehrliche Umgang mit dem 3. Zustand ist ein VERBOT, kein Marker:** die UI zeigt nur das Positive und **affirmiert NIE das Negative** — kein „0 Mentions", kein „nichts für dich". Ein nie-geladener Kanal ist **unbestimmt**, nicht mention-frei — ihn als „keine Mentions" zu lesen wäre die Lüge ([[absence-reads-as-all-clear]]). **Warum kein sichtbarer Unbekannt-Marker** (Revision, §1.5): client-seitig ist **unbekannt der DOMINANTE Zustand** (nur geöffnete/live Kanäle sind geladen) → ein Marker auf *nahezu jedem* Kanal wäre **Rauschen**, das man ignorieren lernt — kein Honesty-Gewinn, nur Unruhe. Stille **mit** dem Affirmations-Verbot ist das Ehrlichste, was ein nie-geladener Kanal hergibt; die echte Completeness-Antwort ist **(B)**. **fail-closed Roster:** `rosterIds` leer ⇒ keine Detection.
Detection (geladen): `(messagesByChannel.get(chId) ?? []).some(m => mentionSegments(m.body, rosterIds).some(s => s.kind === 'mention'))`.

## §1.5 ★ Zwei Wege für den 3. Zustand — **Empfehlung: (A) still jetzt, (B) für Vollständigkeit**
Der **Wert** des Cues liegt bei **UNGEÖFFNETEN** Kanälen (eine Mention im offenen Kanal ist schon inline sichtbar) — und genau die sind client-seitig **unbekannt**.
- **★ (A) Client-Scan, stiller 3. Zustand (Verbot statt Marker)** — Cue aus geladenen Nachrichten; ein nie-geladener Kanal bleibt **still** (kein Marker), und die UI **affirmiert nie** „keine Mentions". **Sofort, kein Backend, kein Resolver-Drift** (nur `mentionSegments`, EINE Quelle). **Grenze Ehrlichkeit vs. Nutzen:** (A) cued **nur geöffnete/live** Kanäle — dort ist die Mention schon inline sichtbar → (A) liefert **wenig** über die Chips hinaus. Ehrlich, aber marginal.
- **(B) Server liefert das Signal** — ein Feld je Kanal (z.B. `GET /api/channels`), **vollständig + sofort korrekt** (auch nie-geöffnete Kanäle → **kein** Unbekannt-Zustand mehr). **★ Harte Bedingung:** der **Server muss die Mention-Regel BESITZEN**, der Client nur anzeigen — sonst **zwei Resolver** (client-`mentionSegments` vs. Server-Regel) die **driften** (der CYP-704-Fehler). **(B) ist der einzige Weg, der die ungeöffneten Kanäle — den eigentlichen Use-Case — cued.**
> **Empfehlung: (A) still jetzt** (Dev5s `544b88fa` ist **korrekt so** — der Marker ist **NICHT** Pflicht), **(B) für den echten Nutzen** (loop Backend2, Server besitzt die Regel; Kontrakt-Shape → Client-gegen-Stub). (A) ist ein ehrlicher, cleaner Sofort-Schritt; (B) macht das Feature erst nützlich. **Verworfen (zurecht):** alle Kanäle im Hintergrund laden = Completeness mit Hub+Netz-Last erkauft.
> **★ Revision meines `8cfc12d9` (sichtbarer Unbekannt-Marker war „Pflicht") — zurückgezogen:** ich begründete den Marker mit „unbekannt-dominant → Stille = false all-clear". **Nach Abwägung falsch:** *weil* unbekannt dominant ist, säße der Marker auf **nahezu jedem** Kanal = **Rauschen**, das nichts Handlungsrelevantes sagt (er nennt nicht *welcher* Kanal Mentions trägt, nur „unbekannt überall") — er beruhigt die false-all-clear-Sorge **nicht** und trübt die Liste. Die echte Antwort auf false-all-clear ist **(B)**, nicht ein Marker. **Kontrast CYP-705-Unread:** dort wird der Unbekannt-Marker **zurecht** gezeigt, weil Unread **server-keyed** ist → unbekannt ist eine **bedeutsame Minderheit** (server-unavailable), nicht der pervasive Default. **Regel:** sichtbarer Unbekannt-Marker gehört zu **minderheitlichem** Unbekannt, Stille+Verbot zu **dominantem**.

## §2 Rendering (reuse den Nav-Badge-Pattern, CYP-705)
- **Has-mention → „@"-Cue** am Kanal-Button (`comm-channel`, dort wo der 705-Unread-Badge sitzt): Glyph **„@"** + `aria-label` „Erwähnungen in {channel}". **colour-never-sole** (der „@"-Glyph trägt, nicht Farbe allein). testTag `comm.channel.{id}.mentionCue`.
- **★ Unbekannt (nie geladen) & geladen-ohne-Mention → nichts** — beide rendern **kein** Element (present-only). **Kein** „•"-Mention-Marker: unter (A) wäre er Rauschen (§1.5), unter (B) gibt es keinen Unbekannt-Zustand.
- **reconcile-not-collapse:** zwei **distinkte** Kanal-Cues, eigene testids/Glyphen: 705-Unread-Badge (inkl. eigenem, **server-keyed** Unknown-Marker) · Mention-„@"-Cue. Nie einebnen ([[reconcile-not-collapse-distinct-states]]) — „jemand hat einen Agent erwähnt" ≠ „es gibt Ungelesenes".

## §3 Honesty-Zähne (diskriminierend)
1. **fail-closed Roster** — `rosterIds` leer → **kein** Cue (keine Detection aus unaufgelösten Daten). *(Mutation: Cue bei leerem Roster → RED.)*
2. **★ present-only, Abwesenheit STILL, Negativ nie affirmiert** — nie-geladener Kanal **und** geladen-ohne-Mention rendern **nichts**; nie „0 Mentions"/„keine Erwähnungen"/„nichts für dich". *(Mutation: Abwesenheit in ein „keine Erwähnungen"-Affirmativ verwandelt → RED.)*
3. **viewer-independent, `@agent` nur** — der Cue basiert auf `mentionSegments` (roster-resolved `@agent`), **kein „me"**, **kein `@Mensch`**. *(Mutation: der Cue braucht/zeigt „welcher Mensch" → RED = das wäre (j).)*
4. **Kanal mit Mention → Cue; geladen ohne Mention → nichts** (non-vacuum: ein Kanal mit `@foo` (foo ∉ roster) → **kein** Cue, fail-closed wie die inline-Chips).
5. **★ Einzel-Resolver (nur Weg B)** — der Cue-Fakt kommt vom **Server** (der die Regel besitzt), der Client **zeigt nur**; kein zweiter Client-Resolver daneben. *(Mutation: Client re-computet die Mention-Regel neben dem Server-Fakt → RED = CYP-704-Zwei-Resolver-Drift.)*

## §4 Optionale Verfeinerung (weiterhin `n`, nicht Basis)
„Kanal hat **UNGELESENE** `@agent`-Mentions" — kombiniert den 705-Read-State (server-Prinzipal-keyed, **agent-scoped**, kein Mensch) mit der Mention-Detection → nützlicher (hebt Mentions hervor, die man noch nicht gesehen hat). **Bleibt `n`** (Unread=705-Cursor, Mention=`@agent`; nirgends „welcher Mensch"). Als Enhancement flaggen, nicht in der Basis.

## §5 Übergabe
- **★ Empfehlung: (A) still jetzt, (B) für Vollständigkeit.** **(A)** = client-`mentionSegments`, present-only, 3. Zustand **still** (Verbot statt Marker) — **Dev5s `544b88fa` ist korrekt so; KEIN Marker nachzuziehen.** **(B)** = Server-Fakt je Kanal, vollständig, **nur** wenn der Server die Regel besitzt (sonst CYP-704-Drift) — loop Backend2; erst (B) cued die ungeöffneten Kanäle (den eigentlichen Use-Case).
- **★ Revision:** mein `8cfc12d9` verlangte einen sichtbaren Unbekannt-Marker als „Pflicht" — **zurückgezogen** (§1.5): bei **dominantem** Unbekannt ist der Marker Rauschen; die Completeness-Antwort ist (B). Sichtbarer Unbekannt-Marker bleibt richtig für **minderheitliches** Unbekannt (CYP-705-Unread, server-keyed).
- **Rendering:** Nav-Badge-Pattern (CYP-705); die Datenquelle wechselt (A: client-`mentionSegments` · B: Server-Fakt), der „@"-Cue bleibt distinkt vom Unread-Badge (§2).
- **Agent-scoped (`human-identity: n`)** — löst nur Kanäle/`@agent` auf, nie einen Menschen.
- **Grenze:** „DEINE Mentions" = **(j) STOP**; diese Spec ist rein „Kanal enthält `@agent`-Mention".

## §6 (B) server-computed — Framing für PL/Backend2 (Follow-up-Kandidat)
**Was:** ein **server-berechneter, viewer-independent-public** Fakt je Kanal „enthält ≥1 `@agent`-Mention", auf der Kanalliste ausgeliefert. Ersetzt die client-`mentionSegments`-Detection; **derselbe „@"-Render** (§2), nur die Datenquelle wechselt.
**Warum:** der Server sieht **alle** Nachrichten → **vollständig**, cued auch **ungeöffnete** Kanäle (der eigentliche Use-Case, den (A) strukturell nicht erreicht). Der 3. Zustand **entfällt** — Abwesenheit des Flags = **autoritativ keine Mention** (legitim still, kein Unbekannt mehr).

**★ Harte Invariante — der Server BESITZT die Regel, der Client zeigt nur:** der Server resolved `@agent` mit **derselben** Regel wie `mentionSegments`/die inline-Chips (roster-resolved Agent-ID; unbekanntes `@token` ≠ Mention; email-förmiger Body ≠ Mention; Code exempt — CYP-704-Regeln). **Ein** Resolver, sonst driftet der Cue gegen die Chips (der CYP-704-Zwei-Resolver-Fehler). **Der Client re-computet NICHTS.**

**Kontrakt-Shape (Vorschlag, PL/Backend2 final):**
- **Minimal:** `Channel.hasAgentMention: Boolean` (present-only-Fakt).
- **Forward-kompatibel (empfohlen):** `Channel.mentionedAgentIds: List<String>` (welche Agenten erwähnt sind; Cue = `isNotEmpty()`). Ein Feld, viewer-independent, ermöglicht später die §4-Verfeinerung + per-Agent-Filter — **bleibt `n`** (Agent-IDs, nie ein Mensch).
- **Auslieferung:** Initialwert in `GET /api/channels`; **Live-Update** über den bestehenden `ChannelsEvent` (`/ws/comm`, 02 §8.2) bei neuer Mention → der Cue eines **ungeöffneten** Kanals leuchtet ohne Refetch. (Ohne Live = stale-complete = eigene Unehrlichkeit: Mention kommt, Cue bleibt dunkel.) **Kein** Client-Re-Derive aus Message-Events (das wäre der zweite Resolver).
- **ACL-scoped:** den Flag nur für Kanäle liefern, die der Aufrufer **lesen** darf (wie die Messages) — viewer-independent-public heißt „nicht viewer-*spezifisch*", nicht „an ACL vorbei".

**Honesty unter (B):**
- **present-only** bleibt (Cue bei has-mention, sonst nichts) — Abwesenheit ist jetzt **autoritativ** clear (Server-vollständig), **kein** Unbekannt-Marker nötig.
- **fail-closed Degradation:** fehlt der Flag (alter Server / Fehler), zeigt der Client **keinen** Cue (nie einen geratenen) — still, present-only.
- **viewer-independent → `human-identity: n`:** der Fakt hat **keinen** Viewer-Parameter → „DEINE Mentions" bleibt strukturell unerreichbar. „@Mensch"/human-targeting bräuchte Identität → `j` → PL-Weiche, **nicht** hier.

**Diskriminierende Zähne (B):**
1. **Server-owns-rule** — Client zeigt nur; ein Client-Re-Derive neben dem Server-Fakt → RED (CYP-704-Drift).
2. **Live-Update** — Mention in ungeöffnetem Kanal → Cue leuchtet ohne Refetch (via `ChannelsEvent`). *(Mutation: Flag nur beim initialen GET → RED = stale-complete.)*
3. **ACL-scoped** — Flag nur für lesbare Kanäle. *(Mutation: Flag für nicht-lesbaren Kanal geliefert → RED.)*
4. **viewer-independent** — kein Viewer-Param im Fakt. *(Mutation: der Fakt hängt vom Aufrufer ab → RED = wäre human-identity `j`.)*

**Migration (A)→(B):** reiner Datenquellen-Swap hinter demselben Surface — `channelHasMention(loadedMessages, roster)` → `channel.mentionedAgentIds.isNotEmpty()`; der 3. Zustand + das Verbot entfallen (Daten vollständig). Kein Render-Umbau, keine neuen i18n-Keys.
