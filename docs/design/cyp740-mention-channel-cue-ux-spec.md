# CYP-740 — Mention-Channel-Level-Cue (web-ts) · UX-Spec (agent-scoped)

**Für:** Dev5 · **Von:** UIUX2 (Team-2) · **Baseline:** develop `f8a0960c` (am Objekt) · **Aus:** CYP-703 Gap-Map (n)-Gap #1
**human-identity:** **n** (agent-scoped — highlightet `@agent`, nie einen Menschen) · **Tooling-Grenze:** headless render-test-messbar.

---

## §0 Scope + Grenze
Ein **Cue an Kanal-Buttons**, wenn ein Kanal **`@agent`-Mentions** trägt — die Completeness der Mention-Highlight **über die inline-Chips hinaus** (heute unsichtbar, solange man den Kanal nicht offen hat). **Viewer-independent, KEIN „me".**
- **★ Grenze:** die stärkere Version „**DEINE** Mentions" (die einen Menschen adressierbar machen / „welcher Mensch?" wissen müsste) = **`human-identity: j` → PL-Weiche → NICHT hier.** Diese Spec ist rein „Kanal **enthält** eine `@agent`-Mention".

## §1 Detection (reuse `mentionSegments`)
- Ein Kanal „trägt `@agent`-Mentions" **iff irgendeine seiner geladenen Nachrichten** ein `mention`-Segment hat:
  `(messagesByChannel.get(chId) ?? []).some(m => mentionSegments(m.body, rosterIds).some(s => s.kind === 'mention'))`.
- **★ Daten-Verfügbarkeit (Honesty-Kern):** die Detection läuft über **geladene** Nachrichten (`messagesByChannel` = selektierter Kanal-History **+** live-empfangene je Kanal über die Session). Ein Kanal **ohne geladene Nachrichten** → die Detection **weiß es nicht**. Deshalb:
  - **present-only positiver Cue** (Kanal **HAT** eine Mention) — **Abwesenheit ist STILL, nie „keine Mentions"** ([[absence-reads-as-all-clear]]: no-cue ≠ „keine", könnte „nicht-geladen" sein). **Nie ein „0 Mentions"-Affirmativ.**
  - **fail-closed Roster:** `rosterIds` leer (nicht geladen / Ladefehler) ⇒ **keine** Detection, **kein** Cue (kein Cue aus unaufgelösten Daten — wie die inline-Chips, CYP-704).

## §2 Rendering (reuse den Nav-Badge-Pattern, CYP-705)
- Ein **Mention-Cue am Kanal-Button** (`comm-channel`, dort wo der 705-Unread-Badge sitzt): Glyph **„@"** + `aria-label` „Erwähnungen in {channel}". **colour-never-sole** (Glyph/Text trägt).
- **testTag:** `comm.channel.{id}.mentionCue`.
- **reconcile-not-collapse:** der Mention-Cue ist ein **distinkter** Marker vom 705-Unread-Badge (eigene testid, eigenes Glyph) — nie in den Unread-Badge einebnen; beide sind Kanal-Level-Cues, aber verschiedene Fakten ([[reconcile-not-collapse-distinct-states]]).

## §3 Honesty-Zähne (diskriminierend)
1. **fail-closed Roster** — `rosterIds` leer → **kein** Cue (keine Detection aus unaufgelösten Daten). *(Mutation: Cue bei leerem Roster → RED.)*
2. **present-only, Abwesenheit still** — no-cue ist **keine** Behauptung „keine Mentions" (könnte nicht-geladen sein); nie „0 Mentions". *(Mutation: „keine Mentions"-Affirmativ → RED.)*
3. **viewer-independent, `@agent` nur** — der Cue basiert auf `mentionSegments` (roster-resolved `@agent`), **kein „me"**, **kein `@Mensch`**. *(Mutation: der Cue braucht/zeigt „welcher Mensch" → RED = das wäre (j).)*
4. **Kanal mit Mention → Cue; Kanal ohne (geladene) Mention → kein Cue** (non-vacuum: ein Kanal mit `@foo` (foo ∉ roster) → **kein** Cue, fail-closed wie die inline-Chips).

## §4 Optionale Verfeinerung (weiterhin `n`, nicht Basis)
„Kanal hat **UNGELESENE** `@agent`-Mentions" — kombiniert den 705-Read-State (server-Prinzipal-keyed, **agent-scoped**, kein Mensch) mit der Mention-Detection → nützlicher (hebt Mentions hervor, die man noch nicht gesehen hat). **Bleibt `n`** (Unread=705-Cursor, Mention=`@agent`; nirgends „welcher Mensch"). Als Enhancement flaggen, nicht in der Basis.

## §5 Übergabe
- **Build-ready, klein, reuse-schwer:** `mentionSegments` (CYP-704) + der Nav-Badge-Pattern (CYP-705). Kein neuer Store.
- **Agent-scoped (`human-identity: n`)** — löst nur Kanäle/`@agent` auf, nie einen Menschen.
- **Grenze:** „DEINE Mentions" = **(j) STOP**; diese Spec ist rein „Kanal enthält `@agent`-Mention".
