# Design-Spec — Entwickler-API-Doku-Experience (CYP-234: hosted reference 234a-3 + narrative guide 234c)

> Owner: UIUX-Designer · CYP-234-Pfad (speist **234a-3** gehostete Referenz + **234c** narrative Anleitung) · Stand: 2026-07-06 · Status: **v1.1 — PO-RATIFIZIERT; Auth-Sektion konkret gemacht (1 PO-Korrektur eingearbeitet).**
> **⚠ v1.1-Änderung ggü. v1.0:** Auth-Sektion war ratifikations-agnostisch gehedged. **Der Auftraggeber hat den Access-Kontrakt §2.2 + §6 SCHON ratifiziert** → jetzt **konkret**: participant-scoped **Token-Klasse** (BYO-Maschine, read-Default, revocable, per-Token-Rate-Limit) + nativer **Kratos-Login** (Mensch) + kontrollierte **CORS-Allow-Liste**. Kein Konjunktiv mehr. + Impl-Notiz: Swagger-UI-„Try it" braucht die Docs-Origin auf der CORS-Allow-Liste (§6). Rest unverändert.
> **Zielgruppe der Doku:** ein **fremder Entwickler**, der ein Go-/Godot-/Web-/CLI-Frontend gegen unseren Server baut, **ohne Kotlin zu lesen**.
> **Grounding:** ratifizierter **CYP-234-Access-Kontrakt** (`backend/plans/CYP-234-frontend-agnostic-contract-design.md`, Auth-Modell §2, Versioning §3, Doku-Cut §4) + **maritime Design-Sprache** (blau/weiß, Material 3) — [[design-language-maritime-m3]].
> **Zwei Deliverables:** **Teil 1** = Präsentations-/Theming-Design der gehosteten, klickbaren API-Referenz (Swagger-UI/Redoc über OpenAPI 3.1 + AsyncAPI-Renderer über die WS-Seite). **Teil 2** = **Informations-Architektur** der narrativen „Frontend-von-Null"-Anleitung (die Struktur/der Fluss — 234c füllt den Text).
> **Was ich NICHT tue:** Prosa schreiben (234c), Renderer einbetten/Beispiele generieren (234a-3/234c-Dev), das Auth-Modell entscheiden (Auftraggeber ratifiziert die offenen Access-Punkte, Kontrakt §2). Ich designe/spezifiziere/verifiziere.

---

## §0 — Zwei Sätze

**Teil 1:** Die generierte OpenAPI/AsyncAPI wird in eine **eine, gebrandete maritime Doku-Shell** gerendert (nicht der Default-Swagger-Look), mit einer **Onboarding-geordneten** Landing/Navigation, **prominenter Auth-Sektion** und einem ehrlichen **„accurate by construction"-Vertrauens-Signal**. **Teil 2:** Die narrative Anleitung folgt der **Reihenfolge, in der ein fremder Entwickler die Dinge braucht** — Auth zuerst, dann REST-Reads, dann WS + Reconnect/Replay, dann Writes/Control, dann Error-Handling — jeder Schritt verlinkt in die generierte Referenz (nie parallel gepflegt → kein Drift).

**Roter Faden (Ehrlichkeits-Kern, mein Mandat):** Die Doku darf **nur das als garantiert darstellen, was garantiert IST**. Drei Anker:
1. **Accurate-by-construction ist echt** (Schema generiert aus `:core` + bidirektionaler Drift-Test + per-DTO-Conformance, Kontrakt §1) → **darf** als Garantie stehen.
2. **Tier-Ehrlichkeit:** jeder Endpunkt trägt sein Tier (public/participant/operator); ein Participant-Token gewährt **nie** Control (Default = read); Operator braucht **explizit** ein Operator-Credential.
3. **Token-only-WS-Constraint nie verwischen:** `/ws/agent` (einen Agenten treiben) braucht Agent-/Operator-Credential — ein read-only-Session-Frontend **kann keinen Agenten treiben**; `/ws/hub` ist **kein** Frontend-Transport. Als First-Class-Constraint dokumentiert, nicht begraben.

---

## §1 — Was existiert / gegroundet (Kontrakt-Audit, nicht neu erfunden)

Aus dem ratifizierten Access-Kontrakt (`CYP-234-frontend-agnostic-contract-design.md`):

- **Neutral-Contract (234a):** OpenAPI 3.1 (REST) + AsyncAPI 2.6 (WS), **generiert aus `:core`**, drift-getestet + per-DTO-conformance-validiert → **accurate by construction**.
- **Auth-Tiers (§2, Auftraggeber ratifiziert die offenen Punkte):** **public** (health, `auth/me` unauth, register) · **participant/read** (Token ODER verifizierte Human-Session; Default) · **operator** (Operator-Token ODER verifizierte OPERATOR-Session; Control/Write).
- **Credential-Pfade (RATIFIZIERT, Kontrakt §2.2 + §6 — Auftraggeber-beschlossen):** **Menschen/Browser-Frontend** → nativer **Kratos-Login** → Session-Cookie (same-origin, CYP-229/230/232); **Maschinen-/Non-Browser-Frontend** → eine **neue participant-scoped Token-Klasse** (`Authorization: Bearer`; WS-Fallback `?token=` weil Browser keinen WS-`Authorization`-Header setzen können). **CORS = kontrollierte Allow-Liste** (§6, deploy-verwaltet) — ein BYO-Web-Frontend braucht seine Origin auf der Liste. **Kein Konjunktiv: das ist der beschlossene BYO-Credential-Weg.**
- **Token-only-WS:** `/ws/agent` (Agent treiben/beobachten), `/ws/hub` (Remote-**Agent**-Wire, **OUT of frontend contract**, Kontrakt §2.5).
- **REST-Versioning (§3):** `/api/v1` Pfad-Prefix **kanonisch**; unversioniertes `/api` = dokumentierter deprecated-Alias.
- **WS-Kanäle:** `/ws/comm`, `/ws/events`, `/ws/lifecycle` (read-tier) + `/ws/agent` (token/operator).
- **Error-Envelope:** uniform `{ error: { code, message } }`, passende Status (400/401/403/404/409/503).
- **Pagination:** `afterSeq` + `limit` (Message-/Event-Historie).
- **Reconnect/Replay:** CYP-198 history-then-live, CYP-204 Cursor-Resume (gapless replay ab seq-Cursor).
- **App-Theme heute:** bares `MaterialTheme {}` (M3-Default) — **keine** kodifizierte maritime Palette. → Ich definiere die **maritime Doku-Theme-Tokens** frisch für diese **neue, separate gehostete Surface** (nicht der Compose-App-Theme; kein Konflikt).

> **Konvention-Adaption:** Diese Surface ist eine **gehostete HTML-Doku-Site** (Swagger-UI/Redoc/AsyncAPI-Renderer), **nicht** die Compose-App. Darum: **kein** `strings.xml`-Key-Set, **keine** Compose-`testTag`-Familie. Statt `-keys.md`/`-tags.md` liefert dieses Paket **`-spec.md`** (Design + IA) + **`-tokens.json`** (maritime Doku-Theme → CSS-Variablen der Renderer, der baubare Teil). Die Doku-Chrome-Copy wird in 234a-3/234c direkt am Renderer/Text autoriert (single-language, nicht app-i18n).

---

## §2 — TEIL 1: Gehostete API-Referenz (→ 234a-3)

### D1 — Eine gebrandete Shell, zwei eingebettete Renderer

Die REST- und die WS-Seite werden von **verschiedenen** Tools gerendert (OpenAPI-Renderer + AsyncAPI-Renderer). Das Design ist **eine maritime Doku-Shell**, die **beide** unter **einer** Chrome (Header, Nav, Footer, Theme) hostet — der Entwickler erlebt **eine** Referenz, nicht zwei Tools.

**Renderer-Entscheidung (REST, PO-ratifiziert 2026-07-06): Redoc — statische Referenz.** Ruhige, lesbare 3-Spalten-Referenz (Nav · Inhalt · Code-Beispiele rechts), themt sauber auf eine Marke, „reference documentation"-Charakter. **„Try it" (Swagger-UI-Konsole) = NEIN für v1** (bräuchte Docs-Origin auf der CORS-Allow-Liste + Auth-in-der-Doku = Flächen-Weitung, für eine reine Referenz nicht wert). Der Spec hält „Try it" als späteres Enhancement ready (D4). AsyncAPI-Seite: der Standard-AsyncAPI-React-Renderer, gleich getheme-t, unter derselben Chrome.

### D2 — Landing / Index = Onboarding-Reihenfolge, NICHT alphabetisch

Über dem rohen Renderer eine **gebrandete Landing** (Orientierung für den Erstkontakt):
- **Hero:** Produktname + ein Satz „was diese API ist" + maritime Visual + **Vertrauens-Zeile** (D6).
- **Endpunkt-Gruppen in bewusster Reihenfolge** (deckungsgleich mit Teil-2-Fluss — der Dev liest die Referenz in derselben Ordnung, in der die Anleitung ihn führt):
  1. **Auth & Zugang** — *fang hier an*: die 3 Tiers, Token vs. Session. **Prominent, zuerst** (D4).
  2. **Kern-Reads** — Roster (`GET /api/agents`), Channels, Messages, Inbox, Projects, Config (masked).
  3. **Real-time (WebSocket)** — `/ws/comm`, `/ws/events`, `/ws/lifecycle`; Frame-Protokoll; Reconnect/Replay.
  4. **Writes & Control** — Message senden (participant-write), Lifecycle/ACL/Projects/Config (**operator**, klar markiert).
  5. **Referenz-Grundlagen** — Error-Envelope, Pagination, Versioning, Schemas.
- Jede Gruppe: ein „wofür"-Satz + Deep-Link in die Referenz + **Tier-Badge** auf Gruppen- **und** Endpunkt-Ebene.

### D3 — Navigation

- **Linke Leiste** nach den D2-Onboarding-Gruppen (nicht nach roher Tag-Alpha-Sortierung).
- **REST ⇄ WebSocket-Umschalter** oben (die zwei Renderer unter einer Chrome), sichtbar dass die WS-Seite existiert.
- **Suche** über Endpunkte/Schemas/Frames. **Deep-linkbare Anker** je Endpunkt/Frame (teilbare URLs).
- Persistente Nav; aktueller Abschnitt hervorgehoben.

### D4 — Auth-Sektion **prominent** (explizite Auftraggeber-Anforderung)

- **Zuerst** in der Nav + eigener „Authentication"-Landing-Block über den Endpunkten.
- **Die 3 Tiers als kleine Matrix:** public / participant(read) / operator — was jedes freischaltet; **Default = participant/read**.
- **Zwei ratifizierte Credential-Pfade, ehrlich getrennt (KONKRET, Kontrakt §2.2):**
  - **Mensch / Browser-Frontend →** nativer **Kratos-Login** → Session-Cookie (same-origin). Die Doku zeigt den Login-Flow konkret.
  - **Maschine / Non-Browser-Frontend →** die **participant-scoped Token-Klasse** (der beschlossene BYO-Maschinen-Credential): wie man sie erhält/setzt (`Authorization: Bearer`; WS `?token=`-Fallback + warum), ihr Default-Tier = **participant/read**, ihre Lifecycle (revocable + optional expiry, Kontrakt §2-F8), ihr Rate-Limit (per-Token-Bucket, §2-F7). **Klar, welcher Pfad für welchen Frontend-Typ.**
- **CORS (§6, konkret):** ein BYO-Web-Frontend serviert von einer **anderen Origin** → seine Origin muss auf der **kontrollierten Allow-Liste** stehen (deploy-verwaltet). Non-Browser-Clients (Go/CLI) sind CORS-unabhängig. Cross-Origin-Web-Frontends authentifizieren per **Token** (nicht Cookie) → kein `allowCredentials`, kein Cross-Origin-CSRF (Kontrakt §2-F9.a).
- **Per-Endpunkt-Tier-Badge inline** (jeder Endpunkt zeigt sein benötigtes Tier — kein Raten).
- **Ehrliche Disclosure (mein Kern):** die **token-only-WS-Constraints** stehen hier als First-Class-Notiz (`/ws/agent` braucht Agent-/Operator-Credential; `/ws/hub` = kein Frontend-Transport). Die Auth-Sektion impliziert **nie**, dass die participant-scoped Token-Klasse / eine read-Session Control gewährt — **Default = read; Operator braucht explizit ein Operator-Credential.**
- **Falls „Try it":** ein prominenter „Authorize"-Button, vorkonfiguriert für Token- **und** Session-Flow. **⚠ Impl-Constraint (an Backend beim 234a-3-Bau):** Swagger-UI-„Try it" macht **Live-Requests aus der Doku-Origin** → die **Docs-Origin muss auf der CORS-Allow-Liste** stehen (§6). Redoc (reine Referenz) hat diesen Bedarf **nicht**.

### D5 — Maritime Theming (blau/weiß, M3) — Tokens in `-tokens.json`

- **Palette:** Primary = maritim-blau; Surface = weiß/sehr hell; ein sekundärer Tiefsee-Ton + ein heller Akzent. Voll in `-tokens.json` (→ Redoc/Swagger/AsyncAPI-CSS-Variablen).
- **Typografie:** M3-Type-Scale (Headings/Body) + **Monospace** für Code/Schemas/Frames.
- **Tier-Badges — farbkodiert ABER WCAG-sicher + label-tragend (nie Farbe allein, WCAG 1.4.1):** public = neutral; participant = info-blau; **operator = distinkter Control-Akzent (NICHT Alarm-Rot — Operator ist kein Fehler, sondern „mehr Zugang nötig"; ein kräftigeres Blau oder Caution-Amber)**. Ehrlichkeits-Anker: Operator-Badge liest „Operator erforderlich", nie „Gefahr/Fehler".
- **Method-Badges (GET/POST/PUT/DELETE):** ein zurückhaltendes, maritim-konsistentes Set (nicht der grelle Swagger-Default).
- **Branding:** Wortmarke/Logo, Favicon, Footer mit **Version + Vertrauens-Zeile** (D6).
- **Dark-Mode:** eine maritime-dark-Variante (optional, `-tokens.json` trägt beide Schemata).

### D6 — Vertrauens-Signal: „accurate by construction" (ehrlich, weil wahr)

Da die Referenz **generiert aus `:core` + drift-getestet + conformance-validiert** ist (Kontrakt §1), trägt die Shell eine **dezente, ehrliche Vertrauens-Zeile**: *„Diese Referenz wird aus den Server-Typen generiert und in CI verifiziert — sie kann nicht von der laufenden API abweichen."* **Das ist eine echte Garantie** (Drift-Test + Conformance-Zahn belegen sie) → als Garantie zu formulieren ist **ehrlich**, kein Marketing. **Abgrenzung:** die Zeile behauptet **nur** Schema-Treue (Shapes/Tiers/Envelope) — **nicht**, dass Rate-Limit-Werte/Deprecation-Fristen garantiert sind (die sind advisory, §2-D7/Teil-2-§5).

### D7 — Versioning-Surface

`/api/v1` als **kanonisch** ausgezeichnet; `/api` (unversioniert) als **deprecated-Alias** mit klarer Notiz (funktioniert im Deprecation-Fenster, Ziel ist v1). Ein **Versions-Selektor**, sobald v2 existiert (bis dahin still).

---

## §3 — TEIL 2: Narrative „Frontend-von-Null"-Anleitung — Informations-Architektur (→ 234c)

**Nicht Prosa — die Struktur/der Fluss.** Reihenfolge = wie ein fremder Entwickler die Dinge **braucht**. Jeder Schritt **verlinkt in die generierte Referenz (234a-3)** — die Anleitung dupliziert **nie** ein Schema (kein Drift). Beispiele werden **gegen die generierten Schemas validiert** (drift-fest, Kontrakt §4/234c).

### §3.0 — Orientierung / „was du baust"
Mentales Modell: JSON-über-HTTP + WebSocket mit `type`-Diskriminator; **eine** Contract, accurate-by-construction; wähle dein Tier. Was ein read-only- vs. ein Full-Control-Frontend am Ende kann.

### §3.1 — **Authentifiziere ZUERST** (Auth-Flow first) — konkret (Kontrakt §2.2 ratifiziert)
- **1a** Credential nach Frontend-Typ wählen: **Mensch/Browser → Kratos-Login/Session-Cookie** · **Maschine/Non-Browser → participant-scoped Token-Klasse**.
- **1b** Browser: der native **Kratos-Login-Flow** → Session; der `GET /api/auth/me`-Check.
- **1c** Non-Browser: die **participant-scoped Token-Klasse** — wie man sie erhält (mint), wohin sie gehört (`Authorization: Bearer`; WS-`?token=`-Query-Fallback + **warum** — kein WS-Auth-Header im Browser), Lifecycle (revoke/expiry).
- **1d** Verifizieren: `GET /api/auth/me` → `{authenticated:true, tier}`.
- **1e** Die 3 Tiers — was jedes freischaltet; **Default der Token-Klasse = participant/read**; Operator = **explizit** (eigenes Operator-Credential).
- **1f** CORS für BYO-Web-Frontends: Origin auf die Allow-Liste (§6); Cross-Origin-Web authentifiziert per **Token**, nicht Cookie.
> *Ehrlichkeits-Marker:* hier steht der Tier-Contract, bevor der Dev irgendeinen Call macht — kein „später Auth nachrüsten". Die Token-Klasse gewährt **nie** Control (read-Default).

### §3.2 — Deine ersten REST-Calls (Reads)
- **2a** Base-URL + `/api/v1`-Prefix (kanonisch).
- **2b** Roster lesen (`GET /api/agents`), Channel-Liste, Messages.
- **2c** Response-Shapes → **Link in die generierten Schemas (234a-3)** (nicht hier duplizieren).
- **2d** **Pagination** (`afterSeq` + `limit`) — Message-/Event-Historie paginieren.
- **2e** **Error-Envelope** `{error:{code,message}}` + Status-Codes (401/403/404/409/503) — Fehler ehrlich lesen.

### §3.3 — Real-time (WebSocket)
- **3a** Die WS-Kanäle (`/ws/comm`, `/ws/events`, `/ws/lifecycle`) — read-tier; was jeder streamt.
- **3b** WS verbinden + authentifizieren (`?token=`-Query für Browser; das Tier).
- **3c** Das Frame-Protokoll — `{type:…}`-diskriminierte Frames; wie man auf `type` schaltet (verlinkt in die AsyncAPI-Frame-Unions).
- **3d** **Reconnect & Replay** (CYP-198 history-then-live, CYP-204 Cursor-Resume): der seq-Cursor; bei Reconnect replayt der Server **gapless ab deinem Cursor**; **Reconnect ist normal, kein Fehler** (deckt sich mit der App-Ehrlichkeit aus CYP-262/CYP-204).
- **3e** **Ehrliche Constraints (First-Class):** `/ws/agent` ist token/operator-only (ein read-only-Frontend nutzt die comm-/transcript-Streams, **kann keinen Agenten treiben**); `/ws/hub` ist **kein** Frontend-Transport (Remote-Agent-Wire). **Die Grenze wird benannt, nicht verwischt.**

### §3.4 — Writes & Control (operator-Tier)
- **4a** Was Operator braucht (Message-Senden = participant-write; Lifecycle/ACL/Projects/Config = operator).
- **4b** Message senden (`POST /api/channels/{id}/messages`) — der `canWrite`-ACL-Check ist das nachgelagerte Gate.
- **4c** Operator-Aktionen-Überblick (Link in die Referenz) — klar gegated; **ein read-only-Frontend hört hier auf**.

### §3.5 — Error-Handling & Resilienz (Querschnitt)
- **5a** Error-Envelope in der Tiefe; Bedeutung je Code.
- **5b** **Rate-Limits** (konkret — die participant-scoped Token-Klasse ist ratifiziert → per-Token-Bucket, Kontrakt §2-F7): die 429-Response + Backoff-Empfehlung.
- **5c** WS-Disconnect/Reconnect-Resilienz (Ref §3.3-3d).
- **5d** Versioning & Deprecation: Ziel `/api/v1`; auf Deprecation-Hinweise auf `/api` achten.

### §3.6 — Worked Examples pro Endpunkt
Jeder Referenz-Endpunkt trägt ein **Request/Response-Beispiel, validiert gegen das generierte Schema** (drift-fest). Die Anleitung verlinkt jeden Schritt in die Live-Referenz (234a-3).

### IA-Prinzipien (Querschnitt)
- **Progressiv:** Auth → Read → Real-time → Write → Harden. Ein **read-only-Frontend kann nach §3.3 aufhören**; ein Full-Frontend macht weiter. Der Fluss macht diese Gabelung explizit.
- **Jeder Schritt verlinkt in die generierte Referenz** — die Anleitung dupliziert nie ein Schema (kein Drift, gleiche no-drift-Disziplin wie die Referenz).
- **Ehrlichkeits-Marker durchgehend:** Tier-Badges, die token-only-WS-Constraints, „accurate by construction", „Reconnect ist normal".
- **Beispiele gegen generierte Schemas validiert** — die Anleitung kann über keine Shape lügen.

---

## §4 — Ehrlichkeit / Disclosure (mein Kern) — Garantiert vs. Advisory

| Aussage in der Doku | Status | Behandlung |
|---|---|---|
| Schema-Shapes / Diskriminatoren / Nullability | **garantiert** (generiert + drift + conformance) | „accurate by construction"-Zeile (D6) — darf als Garantie stehen |
| Endpunkt-Tier (public/participant/operator) | **garantiert** (Server-Gate, drift-getestete Paths) | Per-Endpunkt-Badge; nie impliziert Token=Control |
| Error-Envelope `{error:{code,message}}` | **garantiert** (shared response schema) | Referenz + §3.5 |
| `/ws/agent` token/operator-only · `/ws/hub` excluded | **garantierte Constraint** | First-Class-Notiz (D4/§3.3-3e), nie verwischt |
| `/api/v1` kanonisch, `/api` deprecated-alias | **garantiert** (dual-mount, drift-getestet) | Versioning-Notiz (D7/§3.5-5d) |
| Rate-Limit-Werte, Deprecation-Fristen | **advisory** (tuning/policy) | Als advisory markiert, **nie** als harte Garantie |
| Participant-scoped Token-Klasse (BYO-Maschine) · Kratos-Login (Mensch) · CORS-Allow-Liste | **ratifiziert** (Kontrakt §2.2 + §6, Auftraggeber-beschlossen) | **Konkret** dokumentiert (der beschlossene BYO-Credential-Weg) — read-Default, revocable, per-Token-Rate-Limit; **nie** impliziert Control |

**Kernregel:** die Doku überstellt **keine** Garantie, die der Kontrakt nicht deckt; und sie **versteckt keine** Zugangs-Grenze (token-only-WS), die ein naiver Leser sonst überschätzt.

---

## §5 — Umfang & Abgrenzung

- **Im Scope (mein Design):** Teil 1 Präsentations-/Theming-/IA-Design der gehosteten Referenz; Teil 2 die Informations-Architektur der narrativen Anleitung; die maritime Doku-Theme-Tokens (`-tokens.json`).
- **Nicht im Scope (Bau — 234a-3/234c):** Renderer einbetten/hosten, Prosa schreiben, Beispiele generieren/validieren, die OpenAPI/AsyncAPI selbst (234a generiert sie), Auth-Modell-Entscheidungen (Auftraggeber ratifiziert Kontrakt §2).
- **Abhängigkeit:** die Referenz existiert erst, wenn 234a-2 die OpenAPI/AsyncAPI assembliert; das Theming setzt auf den gewählten Renderer (Redoc/Swagger/AsyncAPI) auf.

---

## §6 — Invarianten (= meine UX-QA-Abnahme, 10)

**Teil 1 — Referenz:**
1. **Eine gebrandete Shell:** REST- + WS-Renderer unter **einer** maritimen Chrome (Header/Nav/Footer/Theme) — nicht zwei ungebrandete Default-Tools.
2. **Onboarding-Ordnung, nicht Alpha:** Landing/Nav folgen der D2-Reihenfolge (Auth → Reads → Real-time → Writes → Referenz), deckungsgleich mit Teil 2.
3. **Auth prominent + zuerst:** eigener Auth-Block, 3-Tier-Matrix, beide Credential-Pfade, **per-Endpunkt-Tier-Badge**.
4. **Maritime M3, WCAG-sicher:** blau/weiß-Palette; Tier-/Method-Badges **label-tragend** (nie Farbe allein, WCAG 1.4.1); Operator-Badge ≠ Alarm-Rot.
5. **Vertrauens-Zeile ehrlich:** „accurate by construction" nur für Schema-Treue; Rate-Limits/Fristen **nicht** als Garantie.

**Teil 2 — Anleitung:**
6. **Auth-first-Fluss:** §3.1 steht vor jedem Call; Tiers erklärt, bevor der Dev einen Endpunkt trifft.
7. **Progressive Gabelung:** read-only-Frontend kann nach §3.3 aufhören; Full-Frontend macht weiter — explizit im Fluss.
8. **Reconnect/Replay ehrlich:** §3.3-3d = seq-Cursor + gapless replay + „Reconnect ist normal, kein Fehler".
9. **Token-only-WS-Constraint benannt:** §3.3-3e nennt `/ws/agent`-Grenze + `/ws/hub`-Ausschluss als First-Class, nicht begraben.
10. **Kein Doku-Drift:** jeder narrative Schritt verlinkt in die generierte Referenz; Beispiele gegen generierte Schemas validiert — keine parallel gepflegte Shape.

---

## §7 — Offene Punkte / §-Asks (nicht-blockierend, PO/Auftraggeber-Call)

**Alle 5 §-Asks vom PO entschieden (2026-07-06) — hier als ratifizierter Record festgehalten:**
1. ✅ **Renderer: Redoc** (read-fokussiert, sauber, gebrandet). Swagger-UI entfällt für v1.
2. ✅ **Auth-Credential-Pfad — GELÖST (Kontrakt §2.2 + §6 ratifiziert):** participant-scoped Token-Klasse (Maschine) + Kratos-Login (Mensch) + kontrollierte CORS-Allow-Liste. Konkret dokumentiert.
3. ✅ **„Try it": NEIN für v1** — Redoc **statische Referenz**. Swagger-UI-„Try it" (bräuchte Docs-Origin auf der CORS-Allow-Liste + Auth-in-der-Doku = Flächen-Weitung) ist eine reine Referenz nicht wert; späteres Enhancement, der Spec hält es ready (D4/§3).
4. ✅ **Doku-Sprache: EN** (BYO-Frontend-Entwickler = globales/externes Publikum; die App bleibt DE/EN, die API-Doku ist EN).
5. ✅ **Sub-Key:** 234a-3 (gehostete Doku) + 234c (narrativ) = Slices unter **CYP-234**; kein separates Epic. Design-Anker = Jira-Kommentar **12646**.

---

## §8 — Hand-off

- **Deliverables:** `-spec.md` (Design + IA, dieses Dokument) + `-tokens.json` (maritime Doku-Theme → Renderer-CSS-Variablen). **Kein** `-keys.md`/`-tags.md` (gehostete HTML-Surface, nicht die Compose-App — §1-Konvention-Adaption).
- **Konsumenten:** **234a-3** (gehostete Referenz — Teil 1 + Tokens) · **234c** (narrative Anleitung — Teil 2 IA). Auth-Sektion setzt auf **234b** (Auth-Uniformität) auf.
- **Abhängigkeit:** 234a-2 (OpenAPI/AsyncAPI-Assembly) muss existieren, bevor gerendert wird; Auth-Pfad gated auf Auftraggeber-Ratifikation (Kontrakt §2).
- **Danach:** **Ratifikation durch PO** (Design-first) → dann Bau 234a-3/234c → **UX-QA durch mich** gegen §6 (10 Invarianten).
- **Keine Secrets/Tokens** in der Doku selbst (nur *wie* man authentifiziert, nie ein echtes Credential); keine `/ws/hub`-Interna im Frontend-Contract.
