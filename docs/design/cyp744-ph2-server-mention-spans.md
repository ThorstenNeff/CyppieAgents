# CYP-744 — 704-Phase-2: Server besitzt die Mention-Regel · `DeliveredMessage`-Wrapper (Migration)

**Für:** Backend2 (server-compute) + Dev5 (render-only, Parser-Löschung) · **Von:** UIUX2 (Team-2) · **Baseline:** develop `b6679671` (am Objekt)
**Herkunft:** PL-Scope-Cut — **CYP-744** (diese Migration) **blockt** CYP-745 (§4-Unread). Frontend-Contract, beide Enden unser (PL-0067 §5).
**human-identity:** **n** (Mention = `@agent`, nie ein Mensch) · **Tooling-Grenze:** Server-Detection server-messbar (Tester2/CI), Client-Render + Parser-Löschung headless-messbar; Parität + §9-Frame-Guard als Objekt-Zähne.

---

## §0 Scope — die Regel wandert vom Client zum Server
Heute lebt die Mention-**Regel** im Client: `mentionSegments` (`web-ts/src/comm/mentionModel.ts`) läuft an **zwei** Render-Stellen — **`CommPanel:175`** (704-inline-Chips) + **`CommPanel:93`** (740-Kanal-Cue via `channelHasMention`). Phase-2 **verlagert die Regel auf den Server**: der Server **detektiert intern** (besitzt die Regel), **populiert** die Mention-Spans an der **Frontend-Transport-Grenze**, der Client **rendert** sie — der **Client-Parser wird gelöscht**. Voraussetzung für CYP-745 (§4): erst wenn der Server die Regel besitzt, kann er Unread∩Mention bilden **ohne** zweiten Resolver (CYP-704-Drift).

## §1 Der `DeliveredMessage`-Wrapper (Typ-Form GEPINNT: (a), nicht (b))
Drei Flags konvergieren auf **eine** Shape — ein **WRAPPER**, kein Feld-auf-`Message` und kein Flatten:
```
DeliveredMessage {
  message:  Message1        // die gespeicherte Nachricht — LITERAL UNTOUCHED
                            //   (inkl. seq / meta / projectId / body — object-verifiziert contract.ts:106–115)
  mentions: MentionSpan[]   // server-berechnete Mention-Positionen + roster-resolved Agent-Ids, ÜBER message.body
}
MentionSpan { start: Int; end: Int; id: String }   // exakte Feldnamen: Contract/Backend2 final; id = Agent-Id
```
Der Client liest `delivered.message.*` (bestehend — inkl. `body` + `seq`) **+** `delivered.mentions` (Spans über den Body). **Warum der Wrapper beide Grenzen zugleich löst:**
- **★ §9 (BYOA-Wire):** die gespeicherte `Message1` ist **literal unberührt (null-touch)** → die `/ws/hub`-`WireMessage(Message1)` bleibt **byte- UND schema-identisch** (PLs Regel-1-Platzierung, Backend2s **(a)**). Ein additives Feld auf `Message1` **(b)** berührte das wire-eingebettete Schema → **§9-inkompatibel**. **★ Die §9-Wunde sitzt auf der Contract-OBERFLÄCHE:** ein — selbst immer **leeres** — Frontend-Feld im wire-eingebetteten Schema **ist** die Wunde, auch wenn Daten nie leaken; nicht erst der Daten-Leak. Deshalb **(a) GEPINNT von PL (final, nicht mehr pending)** — (b) ist an der §9-Grenze inkompatibel.
- **★ CYP-705-seq (Dev5):** der Client liest `delivered.message.seq` → die Unread-Trennlinie + der `upToSeq`-Cursor bleiben intakt (inkl. `seq == 0` = unassigned-Sentinel). Ein **Flatten**, das `seq` (oder `meta`/`projectId`) droppt, bräche **705 STILL** — der Wrapper hält die Nachricht **ganz**.
- **Auslieferung:** die Spans reiten `/ws/comm` (MessageEvent) + REST (`restRepo.getMessages` → `DeliveredMessage[]`); der Client-Fold `messagesByChannel` trägt künftig `DeliveredMessage[]` (heute `Message1[]`). Die `/ws/hub`-Wire trägt weiterhin **bare `Message1`**.

## §2 Client-Parser-Löschung (beide Sites)
- **`:175` inline-Chips** → rendern `delivered.message.body` mit Highlights an `delivered.mentions` (kein `mentionSegments(m.body, …)`-Aufruf mehr).
- **`:93` Kanal-Cue** → has-mention = `delivered.mentions.isNotEmpty()` über die geladenen Nachrichten (Interim, bis **CYP-745** die Datenquelle auf den operator-scope Unread-Fakt swappt). **Kein** Client-Parser.
- **`mentionSegments()`** (mentionModel.ts) → **raus aus dem Render-Pfad**; darf höchstens als **reine Fixture-Referenz** für den Paritäts-Test (§3-ii) überleben, nirgends im Live-Render. `rosterIds` client-seitig für Detection **entfällt** (Server resolved).

## §3 ★ Die drei Zähne (diskriminierend, PL)
**(i) §9-Frame-Guard — OBJEKT-Zahn:** eine Nachricht **MIT** Mentions serialisieren → den **`/ws/hub`-`WireMessage`-Frame inspizieren** → **KEIN** Mention-Feld vorhanden → sonst **RED**. Am Objekt beweisbar (der Frame trägt die Spans nicht), nicht bloß Konvention. *(Fängt jeden §9-Leak der Anreicherung auf die BYOA-Wire — [[sensitive-surface-boundary-by-absence]].)*
**(ii) Parität — verhaltenswahrend:** die Server-`mentions` müssen die alte Client-Regel **bit-genau** reproduzieren, bewiesen mit den **ALTEN CLIENT-FIXTURES** (`mentionSegments`-Testfälle → erwartete Positionen+Ids): roster-resolved `@agent`; **unbekanntes `@token` ≠ Mention**; **email-förmiger Body ≠ Mention**; **Code exempt**; **exakte Span-Grenzen**. *(Mutation: Server findet Mentions, weicht aber in **irgendeinem** Alt-Fixture-Fall ab → RED. „Server findet Mentions" ist NICHT hinreichend; sonst driften Chips/Cue STILL unter den Nutzern.)*
**(iii) seq-Preservation:** `delivered.message.seq` bleibt **intakt** (inkl. `0`=unassigned-Sentinel) → 705-Unread-Trennlinie + `upToSeq`-Cursor **ungebrochen**. *(Mutation: ein Flatten/Transform, das `seq` droppt oder umbenennt → RED = 705 bricht STILL.)*

**Vierter (aus §2), kein Rest-Client-Compute:** kein Client-Site re-derived Mentions aus rohem `body`; der Client rendert **nur** Server-Spans. *(Mutation: übrig gebliebener `mentionSegments(body)`-Aufruf irgendwo → RED.)*

## §4 Build-Split (gegen diesen Stub)
- **Backend2 (server-compute):** die Mention-Regel **besitzen** (Parität §3-ii gegen die Alt-Fixtures); die Spans server-seitig berechnen und im **`DeliveredMessage`-Wrapper (a)** an der Frontend-Grenze (`/ws/comm` + REST) populieren; die gespeicherte `Message1` **literal unberührt** lassen → `/ws/hub` byte+schema-identisch (§3-i). **(a) explizit — (b)-Feld ist an der §9-Grenze inkompatibel.**
- **Dev5 (render-only):** `delivered.message.body` + `delivered.mentions` an `:175` rendern, Cue an `:93` aus `delivered.mentions` ableiten (Interim), **Client-Parser löschen** (§2), `messagesByChannel`-Typ auf `DeliveredMessage[]` ziehen — `delivered.message.seq` für 705 durchreichen (§3-iii).
- **Kontrakt-Landung:** `DeliveredMessage` + `MentionSpan` landen über die `:core`-Contract-Codegen **mit Backend2s Impl** (nicht separat); Frontend `contract.ts`/`contractSchemas` **re-syncen** — Sync flaggen, sonst bricht der Shared-Check ([[shared-key-landing]]).
- **Keine neuen i18n-Keys** (reiner Struktur-/Regel-Umzug; das Render-Surface bleibt).

## §5 Honesty-Summe
server-owns-rule (EIN Resolver) · **§9-Frame-Guard** (Anreicherung nie auf der BYOA-Wire) · **Parität** (verhaltenswahrend, bit-genau vs. Alt-Fixtures) · **seq-Preservation** (705 ungebrochen) · **kein Rest-Client-Compute** (Parser gelöscht). Erst damit steht die Grundlage für CYP-745s operator-scope Unread∩Mention **ohne** zweiten Resolver.
