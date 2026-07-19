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
