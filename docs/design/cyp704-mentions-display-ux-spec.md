# CYP-704 — Mentions DISPLAY (web-ts) · UX-Spec

**Ticket:** CYP-704 (High, Epic CYP-640 „Discord-Feel", Achse 1) · **Für:** Dev5 (baut parallel) · **Von:** UIUX2 (Team-2)
**Baseline:** develop `fa491eed` (am Objekt gemessen) · **Stand:** 2026-07-18
**Tooling-Grenze:** Zustände/Regeln unten sind headless render-test-**messbar** (JSDOM/Vitest). Runtime-„Feel"/Pixel = guided-human, hier nicht behauptet.

---

## 0. Zuschnitt (bestätigt mit PO/Dev5-Vor-Bau-Messung)
CYP-704 zerfällt in zwei Hälften:
- **DISPLAY — Mention rendern:** **hier vollständig spezifiziert. Dev5 baut es jetzt.**
- **NOTIFY — @you-Benachrichtigung:** **PENDING DECISION — NICHT spezifiziert.** Blockiert auf eine Selbst-Identitäts-/Security-Entscheidung: der Client kennt sein `identityId` **nicht** (`AuthMe` ist bewusst **content-free** = `{authenticated, role?, verified}`, keine id/email — CYP-515-Design, enumeration-safe). Ohne mentionbaren Viewer-Handle ist „@you für den Menschen" nicht baubar; das ist eine PL-/Backend2-Entscheidung, kein UX-Zuschnitt. **AC bleibt bindend, sobald NOTIFY gebaut wird: Notify = advisory, nie Zustell-/Lese-Garantie.**

> **DISPLAY ist viewer-unabhängig** — genau deshalb ist es von der Identitäts-Entscheidung entkoppelt: eine Mention `@frontend` sieht für **jeden** Betrachter gleich aus. Kein „me"-Begriff nötig.

---

## 1. Wire-Wahrheit (Quelle: `core/…/model/CommModel.kt`)
`Message = {id, channelId, from, body, ts, meta?, projectId}` · `MessageMeta = {inReplyTo?, kind?}`.
**Es gibt KEIN Mention-/Recipient-Feld.** DISPLAY leitet die Mention **rein client-seitig aus `body`** ab, gegen die **Roster-Ids** (`Agent.id`, bereits in App-State: `roster`). **Keine Backend2-Abhängigkeit.**

## 2. Kern-Invariante: fail-closed Roster-Auflösung
Ein `@token` bekommt **Mention-Optik nur, wenn `token` eine bekannte Roster-Id ist.** Sonst bleibt es **Klartext** — nie Mention-Optik.
- **Der Defekt wäre die Falsch-Positive:** ein beliebiges `@foo` als Mention zu rendern, obwohl niemand so heißt, lügt über „hier wird jemand adressiert". Fail-closed schließt das aus.
- **Roster noch nicht geladen / Ladefehler (CYP-288):** Mention-Auflösung ist auf den geladenen Roster **gated** → vor Auflösung / bei Ladefehler bleibt **alles Klartext** (nie eine geratene Mention auf unaufgelösten Daten).

## 3. Erkennungs-Regel (buildable, präzise)
Für jede Nachricht wird `body` in Segmente zerlegt (Klartext + Mention-Chips):
1. **Sigil-Grenze (E-Mail-sicher):** ein `@` zählt nur als Mention-Sigil, wenn ihm **String-Anfang oder Whitespace** vorausgeht. `user@frontend.com` ⇒ **keine** Mention (das `@` steht mitten im Wort).
2. **Longest-match gegen Roster:** ab dem `@` die **längste** Roster-Id greifen, die passt. `@frontend!` ⇒ Id `frontend`, `!` bleibt Text. `@front` (keine Id) ⇒ Klartext.
3. **Id-Match:** exakter Match gegen `Agent.id`. **Empfehlung: case-insensitiv auf der Id** (Nutzer tippen `@Frontend`) — kleine Dev5-Entscheidung, in einem Helper isoliert.
4. **Mehrfach:** alle auflösbaren Tokens einer Nachricht werden zu Chips; unauflösbare bleiben Text.
5. **Kein „me"-Sonderfall** (DISPLAY ist viewer-unabhängig; Selbst-Mention-Logik gehört zu NOTIFY, pausiert).

> Reine Ableitung, testbar in einem Model-Helper (`mentionSegments(body, rosterIds)` → `Segment[]`), UI rendert nur. Analog zu `senderAccent`/`commDisclosure`: **Honesty-Regeln in den getesteten Helper, die `.tsx` ist Orchestrierung.**

## 4. Layout & Rendering (Reuse, keine Divergenz)
Erweitert **nur** die Body-Zeile der Timeline (`CommPanel.tsx:98-103`) — Kanalliste, Status, Composer unverändert.

- **Heute:** `<span className="comm-body">{m.body}</span>` (flach).
- **Neu:** Body als Segment-Render. Ein **Mention-Chip** reuse't die **Sender-Identität**:
  - Farbe = `senderAccent(id, senderRole(id))` (dieselbe Identitäts-Abbildung wie der Absender-Name, `senderAccent.ts`).
  - **Farbe nie alleiniges Signal (WCAG 1.4.1):** der Chip trägt den **literalen Text `@{id}`** — der Text ist der Träger, der Akzent sekundär. (Kein icon-only, kein colour-only.)
  - Kontrast: Chip-Text als **Text-Rolle** ≥ 4.5:1 (nicht als receded glyph behandeln — [[token-contrast-is-role-dependent]]); `senderAccent` ist bereits contrast-safe (`senderAccent.test.ts` TEXT_MIN=4.5).
- **Klartext-Segmente** rendern unverändert (React-escaped, kein `dangerouslySetInnerHTML` — die W9-Invariante von CYP-456 gilt weiter).

**Kein neuer Zustand** (leer/laden/fehler) — DISPLAY ist reine Body-Anreicherung; die bestehenden Comm-Zustände (empty/CYP-288 load-error/revoked/disclosure) bleiben unangetastet und **beherrschen** wie gehabt (eine fehlgeschlagene Ladung zeigt weiter error+retry, nie Mentions auf Nichts).

## 5. Resource-Keys
DISPLAY ist **strukturell** — der sichtbare Text ist die literale `@{id}`, **keine neue user-facing Copy**. → **Netto null neue Sicht-Keys.**
- Optionaler a11y-Feinschliff: `a11y_comm_mention` = „Erwähnung: {id}". **Wenn** gewünscht, ist es ein **shared Key** → **Dev5 landet ihn mit der Impl** und re-synct den Shared-Check ([[shared-key-landing]]); ich liefere den DE/EN-String auf Zuruf. Ohne diesen Key trägt bereits der literale `@{id}`-Text die a11y-Bedeutung (vertretbar für MVP).

## 6. testTags (charset-safe)
- Chip: `data-testid={`comm.mention.${i}`}` **innerhalb** der bestehenden Row `comm.message.${m.id}` — **stabiler Index `i`** als Scope, die opake ULID bleibt nur Parent-Content ([[testtag-scopeid-charset-safe]]).
- Optional Klartext-Segment: kein eigener Tag nötig.

## 7. Parität
**Kein CMP-Comm-Mention-Display existiert** (verifiziert: `app/shared/.../comm` hat keine Mention-Render-Fläche; die CYP-55-Sachen sind der **Aktivitäts-Badge** = die NOTIFY/Attention-Seite, pausiert). DISPLAY ist damit **neu in web-ts**, kein Port. Reuse-Anker sind web-ts-intern: `senderAccent` (Identität), fail-closed-by-absence (`FidelityBadge`-Muster). Ein späterer CMP-Port spiegelt diese Regeln.

## 8. Render-Test-Zähne (diskriminierend, mutation-aware)
Der Test muss falsche Implementierungen fangen, nicht nur „rendert überhaupt" ([[test-must-discriminate]]):
1. `@frontend` mit `frontend` ∈ roster → **ein** Mention-Chip (Akzent **+ Text `@frontend`**); restlicher Body-Text intakt.
2. **★ fail-closed:** `@foo`, `foo` ∉ roster → **keine** Mention-Optik, `@foo` bleibt Klartext. *(Mutation: matcht die Impl jedes `@token`, RED.)*
3. **E-Mail-sicher:** `mail@frontend.de` → **keine** Mention (Sigil mitten im Wort). *(Non-vacuous false-positive-Guard.)*
4. **colour-never-sole:** der Chip enthält den literalen Text `@frontend` (nicht nur einen gefärbten Span). *(Mutation: colour-only-Chip, RED.)*
5. `@po und @frontend` → **zwei** Chips; Bindetext bleibt Text.
6. Punktuation: `@frontend!` → Chip `frontend`, `!` bleibt Text (longest-match + Grenze).
7. **Roster leer / noch nicht geladen** → **alles Klartext**, keine geratene Mention (gated auf Roster). *(Bindet an CYP-288: unaufgelöste/fehlgeschlagene Ladung ⇒ nie Mention-Optik.)*

## 9. Übergabe-Flags an den Koordinator
- **NOTIFY (@you) = pending decision** (Selbst-Identität/Security; `AuthMe` content-free). Nicht bauen bis PL entscheidet. Meine Grounding-Messung deckt sich mit Dev5s Vor-Bau-Messung.
- **Kein Backend2-Bedarf für DISPLAY** (rein client-seitige Ableitung aus `body` + roster).
- **Shared a11y-Key** (falls gewünscht) landet mit Dev5s Impl, nicht vorab.
- **AC (für NOTIFY, wenn es kommt):** advisory, nie Zustell-/Lese-Garantie; Abwesenheit ≠ „all-clear"; „gelesen" wäre lokal/session (durable server-`seen` = CYP-705, nicht hier einebnen — [[reconcile-not-collapse-distinct-states]]).
