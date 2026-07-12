# Post-Deploy Cutover Smoke — web-ts (QA/Team2)

> Status: pre-staged (forward-prep, kein Merge-Bezug) · Begleitend: `CUTOVER-PARITY-AND-SECURITY-GATES.md`
> (der Rig-Pass), `QA-WEB-FLOW-ROLLBACK-CYP-352.md` (Rollback).
>
> **Auslöser:** *nachdem* der Auftraggeber den Cutover freigibt **und** PO1 die web-ts-Fläche deployt. Dann ist
> diese Checkliste **instant** durchfahrbar (Dogfood am Staging-/Live-Stand).

**Zweck — die Komplement-Achse zum Rig.** Der Parity-Rig hat die **Client-/Feed-Logik** bewiesen, aber mit
bewussten **Harness-Accommodations** (proxy-injiziertes Operator-Bearer statt Kratos-Cookie auf `/ws/agent`;
kein CSP; `FakeSpawner` statt echtem `claude`; same-origin via Vite-Proxy). Diese Smoke verifiziert **genau die
realen Dinge, die der Rig NICHT konnte** — am echten Deploy, mit echtem Kratos, echten CSP-Headern, echtem Agenten.
Jeder Check ist **diskriminierend** (ein benannter Negativ-/Fehl-Fall), nicht bloß „rendert".

**Voraussetzungen.** Deploy grün · Staging-URL · ein **Operator**-Login (Kratos) · mind. **ein real konfigurierter
Agent** (echter `claude`, API-Key hinterlegt) · Browser-DevTools (Network + Console) offen.

**Ergebnis.** Kurze Pass/Fail-Liste an PO. **Ein Fail auf einem [BLOCK]-Check → Rollback** (`QA-WEB-FLOW-ROLLBACK-CYP-352`).

---

## §1 · Auth & Topologie (Deploy-owned §2-Items — LIVE-only, im Rig nicht testbar)

| # | Check | Diskriminator (Fehl-Fall) | Rig-Accommodation, die das hier ablöst |
|---|---|---|---|
| 1.1 [BLOCK] | **Login-Redirect greift.** Unauthentifiziert die Staging-URL öffnen → Redirect auf die **Kratos-hosted** Login-Seite. | Die App rendert **kein** eigenes Passwort-/Credential-Formular; der Redirect zeigt auf die **Kratos-Origin**. Ein In-App-Login-Feld = FAIL. | Rig war token-basiert, kein Kratos. |
| 1.2 [BLOCK] | **Same-origin (Reverse-Proxy = ein Origin).** Nach Login: SPA + `/api/*` + `/ws/*` alle auf **einer** Origin. | Network-Tab zeigt **keine** cross-origin-Requests, **keine** CORS-Preflights/-Fehler. Ein cross-origin `/api`-Call = FAIL. | Rig: Vite-Proxy simulierte den einen Origin. |
| 1.3 [BLOCK] | **CSP-Header präsent (B-2, im Rig offen).** Response der SPA trägt `Content-Security-Policy`. | Mind. `default-src 'self'`, `script-src 'self'` (**kein** `unsafe-inline`), `connect-src` = API/WS-Origin, `object-src 'none'`, `frame-ancestors 'none'` + `X-Content-Type-Options: nosniff`. **Aktiv-Probe:** eine injizierte Inline-`<script>` wird vom Browser **geblockt** (CSP-Violation in der Console). Kein CSP-Header = FAIL. | Rig hatte **keinen** CSP (B-2-Gap). |
| 1.4 [BLOCK] | **Session-Cookie-Flags.** Der Kratos-Session-Cookie ist `HttpOnly` + `Secure` + `SameSite`. | `document.cookie` gibt den Session-Cookie **nicht** her (HttpOnly). Fehlt ein Flag = FAIL. | Rig: kein echter Cookie. |

## §2 · Operator-Panels rendern (Assembly am echten Stand)

| # | Check | Diskriminator |
|---|---|---|
| 2.1 [BLOCK] | **Alle Operator-Flächen assemblieren:** Lifecycle-Header · Event-Log · Comm · ACL-Matrix · Agent-Mgmt · Settings (Repo+API-Key) · Product-Lead. | Jede Fläche zeigt **echte** Daten (nicht empty/error/„connecting" hängend). Eine fehlende/gebrochene Fläche = FAIL. |
| 2.2 | **API-Key maskiert (kein Klartext).** Settings zeigt `***last4`, nie den vollen Key — auch nicht in einem Response-/Debug-Feld (Network-Tab prüfen). | Ein Klartext-Key irgendwo im DOM/Netzwerk = **[BLOCK]-FAIL** (Secret-Leak). |
| 2.3 | **Member-Posture (falls ein Member-Login vorhanden):** Event-Log-Fenster **fehlt**, Settings present-but-disabled. | Ein Member, der operator-only Bodies sieht = **[BLOCK]-FAIL**. (Server-seitig bereits E2E-bewiesen; hier Live-Bestätigung.) |

## §3 · Reale Feeds (was der Rig gefaked hat — der Kern dieser Smoke)

| # | Check | Diskriminator | Rig-Accommodation, die das hier ablöst |
|---|---|---|---|
| 3.1 [BLOCK] | **`/ws/agent` per echtem Kratos-Cookie.** Ein Agent-Transkript streamt Events. | Das Transkript füllt sich **ohne** proxy-Bearer — der reale Cookie-Handshake (CYP-454) trägt. Handshake-Reject/leeres Transkript = FAIL. | Rig: proxy-injiziertes Operator-Bearer (Deviation ①). |
| 3.2 [BLOCK] | **Composer → `UserTurn` erreicht einen ECHTEN Agenten.** Nachricht tippen + Enter → der reale `claude` empfängt sie und **antwortet** (Assistant-Turn erscheint im Transkript). | Eine echte Agenten-Antwort (nicht nur der gesendete Turn) erscheint. Kein Response = FAIL (Mediation/Spawn-Pfad kaputt). | Rig: `FakeSpawner`/`OpenFakeProcess` (kein echter claude). |
| 3.3 | **Event-Log-Live-Tail flippt real auf „Live".** Event-Log öffnen → Status `Verlauf lädt…` → **`Live`** (CYP-499 am echten `EventSocket`). | Bleibt „Verlauf lädt…" = FAIL (CaughtUp-Emit fehlt am realen Server). | Rig verifizierte den Client-Flip; hier der reale Server-Emit. |
| 3.4 | **ACL-Änderung wirkt real.** Eine `canWrite`-Zelle entziehen → der betroffene Agent kann **nicht** mehr in den Kanal senden (Hub verweigert, 403). | Der Entzug greift **server-seitig** (nicht nur optimistisches UI). Send trotz Entzug erfolgreich = FAIL. | Rig: FakeSpawner-Agent; hier realer Hub-Enforcement-Pfad. |
| 3.5 | **Comm-Timeline: Historie + Live.** Ein Kanal zeigt seine Historie; eine neue (real gesendete) Nachricht erscheint **live**, **einmal** (Dedup by `Message.id`). | Eine Dublette bei Live/Reconnect = FAIL. |

## §4 · Hygiene

| # | Check | Diskriminator |
|---|---|---|
| 4.1 [BLOCK] | **Keine Console-Errors / keine CSP-Violations** über den ganzen Durchlauf. | Eine uncaught Exception oder CSP-Violation = FAIL (§1.3-gekoppelt). |
| 4.2 | **Alle `/api`-Requests 2xx, alle `/ws`-Upgrades 101.** | Ein 4xx/5xx/failed-Request auf dem Happy-Path = FAIL. |
| 4.3 | **Reconnect-Toleranz.** Netz kurz kappen/wieder verbinden → Terminal + Comm verbinden automatisch neu, **ohne** Dublette sichtbarer Nachrichten. | Verlorene/duplizierte Nachrichten = FAIL. |

## §5 · Cross-Ref: die 5 Deploy-owned §2-Items (Live-verifiziert, nicht Rig)

1. **CSP-Header** gesetzt → §1.3.
2. **Same-origin** Serving (Reverse-Proxy) → §1.2.
3. **Session-Cookie-Flags** (HttpOnly/Secure/SameSite) → §1.4.
4. **`/ws/agent` Cookie-Auth** (CYP-454, real statt proxy-Bearer) → §3.1.
5. **API-Key server-seitig, maskiert, nie geloggt** → §2.2.

> **Warum genau diese Live-only sind:** der Rig lief same-origin-per-Proxy, ohne CSP, mit token-Auth + FakeSpawner —
> also sind **Kratos-Cookie-Auth, echte CSP-Header, echter Agent-Spawn, der reale „Live"-Flip und die realen
> Cookie-Flags** die Achsen, die **nur am Deploy** fallen. Diese Smoke ist die letzte Meile: „läuft die Fläche real,
> nicht nur im Rig".

## §6 · Bekannte, nicht-blockierende Deferrals (aus dem Rig-Pass, hier nur zur Kenntnis)

- **CYP-502** (A6-Pause-Revoke kein `/ws/events`-Mid-Stream-1008 — kein Requirement, defer).
- **[MP]-Tier** (Projekt-CRUD/Switcher · Cross-Projekt-Kanal · Projekt-Filter) — bei Portierung.
- **B-4 Negativ-Operator-Schutz** ist server-E2E + Member-Coverage-Split abgedeckt.
