# CYP-745 — §4 Operator-scope „ungelesene `@agent`-Mentions" je Kanal · Kontrakt-Stub

**Für:** Backend2 (server-compute) + Dev5 (render-only) · **Von:** UIUX2 (Team-2) · **Baseline:** develop `b6679671` (am Objekt)
**Blocked-by:** **CYP-744** (Server besitzt die Mention-Regel via `DeliveredMessage.mentions`) — §4 **konsumiert** die Server-Regel, re-derived **nichts**.
**Herkunft:** PL-ratifiziert **§4** (viewer-dependent unread, actionable) über §6 (ever-had, low-signal). **Supersedet** den früheren CYP-740-§4-Draft (`76ea6030`). · **human-identity:** **n** — strukturell (operator-scope, §2).

---

## §0 Warum §4 (der Merit-Call, PL)
- **§6 „Kanal hatte JE eine `@agent`-Mention"** = permanent-once-true → klärt nie → low-signal.
- **★ §4 „der Operator hat UNGELESENE `@agent`-Mentions hier"** klärt **beim Lesen** → **actionable**. Das ist der Nutzen.
- **§4 supersedet** die ausgelieferte (A)-client-only-Cue (nur geöffnete Kanäle) **und** §6: **dasselbe Cue-Surface** (`CommPanel:93`), **bessere Datenquelle** (server, operator-scope, vollständig, klärt-beim-Lesen).

## §1 Der Fakt + die Kontrakt-Shape (der Stub)
**Fakt je (Kanal, Operator) — heute:** „Gibt es in diesem Kanal eine für den **Operator ungelesene** Nachricht, die eine `@agent`-Mention trägt?"
```
Channel.hasUnreadAgentMention: Boolean          // Fakt-only, present-only, operator-scope
Channel.unreadAgentMentionCount: Int?           // optional, gleiche Bedingung gezählt
```
- **true iff** ∃ Nachricht im Kanal mit **`seq > lastReadSeq`** des **Operators** (das 705-`OPERATOR_ID`-`ReadState`, object: `ReadState.lastReadSeq`) **UND** nicht-leeren **`mentions`** (die 744-Server-Regel, `DeliveredMessage.mentions`).
- **★ Kein zweiter Resolver:** die **Unread**-Hälfte reused den **705-`OPERATOR_ID`-Cursor** (`lastReadSeq`); die **Mention**-Hälfte reused die **744-Server-Spans**. **Neu = allein die server-seitige Konjunktion** (unread ∩ has-mention). Der Client zeigt nur, re-computet **nichts** (sonst CYP-704-Drift — genau, was 744 schließt).
- **Operator-scope heute:** 705s Cursor ist **operator-scoped** (fester Subject `OPERATOR_ID`), nicht generisch per-Prinzipal.

## §2 Der strukturelle `n`-Guard + die benannte Grenze (PL, zweistufig)
- **★ Strukturell `n`:** der „Viewer" ist der **fixe `OPERATOR_ID`-Subject** (Operator-Seat, server-fest über das Operator-Token) — **nie** eine client-gelieferte Id, und **keine** Auflösung, *welcher Mensch*. `OPERATOR_ID` ist ein einzelner fester Seat, kein resolved Mensch → honest-in-the-TYPE, nicht Caller-Disziplin ([[scope-boundary-is-semantic-not-labeled]]).
- **★ Benannte Grenze (kein stiller Drift) — zweistufig:** **(heute) operator-scope** = keyed auf `OPERATOR_ID`. **(später) member-tier** = „hat **dieses Member** ungelesene Agent-Mentions" = **dieselbe Identitäts-Weiche wie CYP-705-member** → **explizite `j`-Weiche**, **ein** Unblock löst beide. **Vertraglich benannt:** dieses Feld ist **per Definition operator-scoped**; ein member-/menschen-scoped Unread braucht ein **neues, separat adjudiziertes** Feld + die PL-Weiche — **kein** stiller Re-Key.

## §3 Auslieferung & Honesty
- **Auslieferung:** Initialwert **operator-scope** in `GET /api/channels`; **Live-Update** über den bestehenden `ChannelsEvent` (`/ws/comm`, Spec 02 §8.2) — neu berechnet & gepusht bei **(i) neuer Nachricht UND (ii) Vorrücken des `OPERATOR_ID`-Cursors** (`lastReadSeq`/`upToSeq`-Commit → Lesen **klärt** den Cue = der §4-Mehrwert). Ohne Recompute-bei-Read wäre der Cue **stale-lit** (gelesen, Cue bleibt an) — die Unehrlichkeit, die §4 gegenüber §6 gerade schlägt.
- **ACL-scoped:** Feld **nur** für Kanäle liefern, die der Operator **lesen** darf.
- **present-only Render:** Cue bei `true`; bei `false` **nichts** — `false` ist **autoritativ** „für dich keine ungelesene Mention hier" (server-vollständig → legitim still, **kein** Unbekannt-Marker).
- **fail-closed Degradation:** fehlt das Feld (alter Server / Fehler) → **kein** Cue (nie geraten).
- **Fakt/Count-only:** **kein** Mentioner-Identitätsfeld — nie „wer hat erwähnt".
- **Render-Detail (Dev5):** der `:93`-Cue swappt seine Datenquelle von der 744-Interim-Ableitung (`delivered.mentions` über geladene) auf **`channel.hasUnreadAgentMention`**; `aria-label` „**Ungelesene** Erwähnungen in {channel}". Distinkt vom 705-Unread-Badge (eigene testid/Glyph) — „ungelesene Agent-Erwähnung" ≠ „ungelesen allgemein".

## §4 Diskriminierende Zähne
1. **Server-owns-rule (via 744)** — Client zeigt nur; ein Client-Re-Derive der Mention-Regel neben dem Server-Fakt → **RED** (CYP-704-Drift, den 744 schließt).
2. **★ Live-bei-Read-Advance** — Operator liest die Mention (`lastReadSeq` rückt vor) → Cue **klärt** ohne Refetch (via `ChannelsEvent`). *(Mutation: Recompute nur bei neuer Nachricht, nicht bei Read-Advance → RED = stale-lit, „klärt nie" = der §6-Fehler.)*
3. **★ Adversarial Subject-Isolation (PL) — am OBJEKT beweisbar:** eine Anfrage mit **client-geliefertem Subject/Viewer-Id** (z.B. ein Member), die dessen Ungelesen-Stand lesen will → **REFUSE/403**; das Feld wird **ausschließlich** für `OPERATOR_ID` (server-fest aus dem Operator-Token) berechnet. *(Mutation: Server ehrt ein client-geliefertes Subject und liefert fremdes Unread → RED = Cross-Subject-Leak. „keyed on `OPERATOR_ID`" muss ein **Test** beweisen — gefälschtes Subject → 403/nur-Operator-Daten — nicht bloß die Absicht.)*
4. **ACL-scoped** — Feld nur für lesbare Kanäle. *(Mutation: Feld für nicht-lesbaren Kanal → RED.)*
5. **Fakt-only** — kein Mentioner-Identitätsfeld. *(Mutation: Response nennt „wer erwähnt hat" → RED = Über-Scope Richtung Menschen-Identität.)*
6. **present-only** — `false`/absent rendert **nichts**, nie „0 ungelesene Erwähnungen"-Affirmativ. *(Mutation: Abwesenheit als Affirmativ → RED.)*

## §5 Build-Split (gegen diesen Stub)
- **Backend2 (server-compute, nach 744):** die Konjunktion (705-`OPERATOR_ID`-`lastReadSeq` ∩ 744-`mentions`) **operator-scope**, ACL-scoped, live via `ChannelsEvent` (inkl. Read-Advance); **Subject-Isolation erzwingen** (Zahn 3, am Objekt — nur `OPERATOR_ID`).
- **Dev5 (render-only):** `:93`-Cue-Datenquelle = `channel.hasUnreadAgentMention`; `aria-label` „ungelesen"; **kein** Client-Re-Derive.
- **Kontrakt-Landung:** die `Channel`-Feld(er) landen über die `:core`-Contract-Codegen **mit Backend2s Impl**; `contract.ts`/`contractSchemas` **re-syncen** — Sync flaggen ([[shared-key-landing]]). aria-label-String mit der Impl landen; sonst keine neuen i18n-Keys.
- **Reihenfolge:** **erst 744** (Server besitzt die Regel), **dann 745** — sonst kein Server-`mentions` für die Konjunktion.
