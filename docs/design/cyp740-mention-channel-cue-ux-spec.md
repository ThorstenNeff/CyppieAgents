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
| **★ unbekannt** | **nie geladen** (nie geöffnet, keine live-Nachricht) | **Weg A: sichtbarer „•"-Unbekannt-Marker · Weg B: entfällt — NIE „keine Mentions"** |
**★ Der Kern (CYP-705 unknown≠zero, an der Kanalliste):** **absence ≠ „nichts für dich".** Ein nie-geladener Kanal ist **unbestimmt**, nicht mention-frei — ihn als „keine Mentions" zu lesen wäre genau die Lüge ([[absence-reads-as-all-clear]]). Wie der 3. Zustand **surfaced** wird, hängt vom Weg ab (§1.5): client-seitig ist **unbekannt der dominante Zustand** → er muss **sichtbar** sein; server-seitig (vollständig) gibt es ihn nicht. **present-only positiv** (Cue = HAT; nie ein „0 Mentions"-Claim). **fail-closed Roster:** `rosterIds` leer ⇒ keine Detection.
Detection (geladen): `(messagesByChannel.get(chId) ?? []).some(m => mentionSegments(m.body, rosterIds).some(s => s.kind === 'mention'))`.

## §1.5 ★ Zwei ehrliche Wege für den 3. Zustand — **Empfehlung: (A) jetzt, (B) später**
Der **Wert** des Cues liegt bei **UNGEÖFFNETEN** Kanälen (eine Mention im offenen Kanal ist schon inline sichtbar) — und genau die sind client-seitig **unbekannt**. Zwei ehrliche Auswege (Dev5):
- **★ (A) Client-Scan + sichtbarer Unbekannt-Marker** — Cue aus geladenen Nachrichten; ein **nie-geladener** Kanal trägt einen **neutralen „•"-Unbekannt-Marker** (dieselbe Form wie CYP-705-Ungelesen / CYP-733-Tier), **nicht Nichts**. **★ Warum der Marker Pflicht ist:** client-seitig ist **unbekannt der DOMINANTE Zustand** (nur der offene Kanal ist geladen) — ein **stilles** Nichts läse die ganze Liste als „nirgends Mentions" = **false all-clear** ([[absence-reads-as-all-clear]]). Der sichtbare „•" macht *„weiß ich noch nicht"* ehrlich unterscheidbar von *„geladen, keine"*. Kosten: Liste zeigt anfangs überwiegend „•" — ehrlich, optisch geräuschvoll. Vorteil: **keine Backend-Arbeit, sofort, kein Resolver-Drift** (nur `mentionSegments`, EINE Quelle).
- **(B) Server liefert das Signal** — ein Feld je Kanal (z.B. `GET /api/channels`), **vollständig + sofort korrekt** (auch nie-geöffnete Kanäle → kein Unbekannt-Zustand mehr). **★ Harte Bedingung:** der **Server muss die Mention-Regel BESITZEN**, der Client nur anzeigen — sonst **zwei Resolver** (client-`mentionSegments` vs. Server-Regel) die **driften** (der CYP-704-Zwei-Resolver-Fehler): der Cue widerspräche den inline-Chips. **(B) ist nur dann besser als (A), wenn dieser Einzel-Resolver garantiert ist.**
> **Empfehlung: (A) jetzt, (B) später** (deckt sich mit Dev5). (A)-mit-sichtbarem-Unbekannt ist **voll ehrlich** und **sofort**: sie *behauptet* die ungeöffneten Kanäle nicht zu kennen, sie **markiert** sie ehrlich als unbekannt. (B) ist die **Vollständigkeits-Stufe** — lohnt den Vertrag, wenn der Server die Regel besitzt (dann sind **beide** Kanal-Cues konsistent vollständig: 705-Unread ist server-komplett, Mention wäre es dann auch). **Verworfen (zurecht):** alle Kanäle im Hintergrund laden = Vollständigkeit mit Hub+Netz-Last erkauft, unehrlichste Variante. Bei (B): Backend2-Naht (Server besitzt die Regel; Kontrakt-Shape → Client-gegen-Stub).
> **Korrektur zu meinem früheren Call:** ich hatte (B/server) empfohlen mit dem Argument „client-only kann ungeöffnete Kanäle nicht cuen". Dev5s **sichtbarer** Unbekannt-Marker löst genau das — nicht durch Cuen, sondern durch **ehrliches Markieren** (mein eigenes 3-Zustand-Prinzip). Damit ist **(A) der richtige Sofort-Weg**, (B) das spätere Vollständigkeits-Upgrade.

## §2 Rendering (reuse den Nav-Badge-Pattern, CYP-705)
- **Has-mention → „@"-Cue** am Kanal-Button (`comm-channel`, dort wo der 705-Unread-Badge sitzt): Glyph **„@"** + `aria-label` „Erwähnungen in {channel}". **colour-never-sole** (Glyph trägt). testTag `comm.channel.{id}.mentionCue`.
- **★ Unbekannt (nur Weg A) → neutraler „•"-Marker:** reuse die CYP-705/CYP-733-„•"-Form, `aria-label` „Erwähnungen in {channel} noch nicht geladen". testTag `comm.channel.{id}.mentionUnknown`. **colour-never-sole** (Form/Label trägt); **neutral** — nicht Alarm, nicht Positiv ([[over-alarm-is-also-dishonest]]).
- **Geladen, keine → nichts** — nur hier ist Abwesenheit ehrlich („geschaut, keine").
- **reconcile-not-collapse:** drei **distinkte** Marker, eigene testids: 705-Unread-Badge · Mention-„@"-Cue · Mention-Unbekannt-„•". Nie einebnen ([[reconcile-not-collapse-distinct-states]]). **Achtung:** 705-Unread ist **server-Prinzipal-keyed** → auch für nie-geöffnete Kanäle **bekannt**; der Mention-Unbekannt-„•" ist **spezifisch** „Mention-State nicht geladen", **nicht** dasselbe wie ungelesen. **Weg B eliminiert den „•" ganz** (dann sind beide Cues vollständig).

## §3 Honesty-Zähne (diskriminierend)
1. **fail-closed Roster** — `rosterIds` leer → **kein** Cue (keine Detection aus unaufgelösten Daten). *(Mutation: Cue bei leerem Roster → RED.)*
2. **★ Unbekannt sichtbar, nie „keine" (Weg A)** — ein **nie-geladener** Kanal zeigt den **Unbekannt-„•"**, **nicht** Nichts und **nicht** „keine Mentions"; nie ein „0 Mentions"-Affirmativ. *(Mutation: nie-geladen rendert Nichts/„keine" → RED = false all-clear, weil unbekannt der dominante Zustand ist.)*
3. **viewer-independent, `@agent` nur** — der Cue basiert auf `mentionSegments` (roster-resolved `@agent`), **kein „me"**, **kein `@Mensch`**. *(Mutation: der Cue braucht/zeigt „welcher Mensch" → RED = das wäre (j).)*
4. **Kanal mit Mention → Cue; geladen ohne Mention → nichts** (non-vacuum: ein Kanal mit `@foo` (foo ∉ roster) → **kein** Cue, fail-closed wie die inline-Chips).
5. **★ Einzel-Resolver (nur Weg B)** — der Cue-Fakt kommt vom **Server** (der die Regel besitzt), der Client **zeigt nur**; kein zweiter Client-Resolver daneben. *(Mutation: Client re-computet die Mention-Regel neben dem Server-Fakt → RED = CYP-704-Zwei-Resolver-Drift.)*

## §4 Optionale Verfeinerung (weiterhin `n`, nicht Basis)
„Kanal hat **UNGELESENE** `@agent`-Mentions" — kombiniert den 705-Read-State (server-Prinzipal-keyed, **agent-scoped**, kein Mensch) mit der Mention-Detection → nützlicher (hebt Mentions hervor, die man noch nicht gesehen hat). **Bleibt `n`** (Unread=705-Cursor, Mention=`@agent`; nirgends „welcher Mensch"). Als Enhancement flaggen, nicht in der Basis.

## §5 Übergabe
- **★ Empfehlung: (A) jetzt, (B) später** (deckt sich mit Dev5). **(A)** = client-`mentionSegments` + sichtbarer „•"-Unbekannt-Marker für nie-geladene Kanäle — voll ehrlich, sofort, kein Backend, kein Resolver-Drift. **(B)** = Server-Fakt je Kanal, vollständig — **nur** wenn der **Server die Mention-Regel besitzt** (Client zeigt nur), sonst CYP-704-Drift; bei (B) loopst du Backend2 (Kontrakt-Shape → Client-gegen-Stub).
- **Der 3-Zustands-Honesty-Kern (§1) gilt für BEIDE**; unter (A) ist der 3. Zustand **sichtbar** („•"), unter (B) **entfällt** er (vollständige Daten).
- **Rendering:** Nav-Badge-Pattern (CYP-705); die Datenquelle wechselt (A: client-`mentionSegments` · B: Server-Fakt), die Marker bleiben distinkt (§2).
- **Agent-scoped (`human-identity: n`)** — löst nur Kanäle/`@agent` auf, nie einen Menschen.
- **Grenze:** „DEINE Mentions" = **(j) STOP**; diese Spec ist rein „Kanal enthält `@agent`-Mention".
