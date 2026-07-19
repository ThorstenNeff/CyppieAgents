# Gateway BASIS-Connect (web-ts) · UX-Spec — Browser → Gateway → EIN Hub (Team-2)

**Für:** Dev5 · **Von:** UIUX2 (Team-2) · **Baseline:** develop `c72a05e1` (am Objekt gemessen 2026-07-19) · **Epic:** Phase-2 Remote-Parität (CYP-676 Option-A)
**Scope-Grenze (PO):** **BASIS** = Browser→Gateway→**EIN** Hub (same-origin `/api`+`/ws`), voll Team-2-baubar. Der volle Remote-Operate (→Relay→NAT'd Hub) berührt **Team-1s Relay-Leg** → §5, Team-1-Koordination, **kein web-ts-Screen**.
**Tooling-Grenze:** Zustände headless render-test-messbar; Runtime/Pixel = guided-human.

---

## §0 Architektur-Grounding (am Objekt — CYP-638 `GatewayServer.kt` + web-ts `appConfig.ts` + CYP-515 auth)
- **Der Gateway ist der same-origin Front-Door:** *„the browser speaks plain HTTP(S)/WS to the gateway, and the gateway forwards to the hub"* (`GatewayServer.kt:54`). Default-DENY-Allowlist (REST aus `RestContract.REST_OPS`, 8 WS-Sockets); **`/api/cp/` + `/ws/hub` + `/mcp/hub` am Edge ausgeschlossen** (die Control-Plane/Relay-Leg → Team-1).
- **web-ts base-URL** (`appConfig.ts`): `CYPPIE_API_BASE`/`CYPPIE_WS_BASE` **deploy-injizierte Globals** (cross-origin coexistence) **oder same-origin-Fallback** (`location.origin`, http→ws). **Kein runtime-user-entered URL heute.**
- **„Connect" = die Kratos-Auth-Session** (CYP-515: whoami-Gate → `LoginScreen` → httpOnly-Session), same-origin. Es gibt heute **keinen** separaten „dial a remote hub"-Schritt im Browser — die vom Gateway ausgelieferte SPA spricht same-origin `/api`+`/ws` mit dem Hub dahinter.

## §1 Scope GEKLÄRT (PO/Backend2, am Objekt): SAME-ORIGIN — kein in-app URL-Feld, kein Cross-Origin-Step
Ich hatte gefragt, ob „Gateway-URL eingeben" ein **in-app** Feld meint (die base ist heute deploy-Global/same-origin, kein runtime-Feld). **Geklärt: der Gateway ist SAME-ORIGIN — die SPA wird aus dem Gateway selbst ausgeliefert.** Damit:
- **„Gateway-URL eingeben" = der Nutzer navigiert seinen BROWSER** zur Gateway-URL (Adressleiste) → bekommt die SPA same-origin. **Kein in-app URL-Feld, kein CORS, kein Cross-Origin-Consent-Step** zu spezifizieren.
- **„verbinden" = der Kratos-Login same-origin** (`/.ory/kratos/public/*`, httpOnly-Cookie, CYP-515). Das **IST** der Connect.
- Endpoints (`/api/*` + 8 `/ws/*`) sind **serverseitig auto-forwarded** → **nichts pro-Endpoint** zu spezifizieren, nur **Connect + Session + Revoke**.
- **BYO-Gateway in-app-runtime-URL-Override = OUT** (nicht was same-origin bedeutet) — nur relevant, falls je ein separater „SPA anderswo gehostet, zeigt auf fremden Gateway"-Deploy-Modus gewollt wird (eigene Entscheidung, nicht dieser Spec).
> Die Eskalation hat sich gelohnt: sie verhinderte **beides** — ein falsches in-app URL-Feld **und** einen falschen Cross-Origin-Consent-Step ([[prestage-against-the-tickets-own-spec]]: Scope-Konflikt eskalieren zahlt sich aus).

## §2 BASIS-Gateway-Connect UX (Lesart A — object-true, build-ready)
Der „Connect" ist **kein neuer Flow**, sondern die ehrliche **Zusammenführung von drei bestehenden Bausteinen** + der load-bearing Tier-Disclosure:
- **Auth = Connect** (reuse CYP-515): der whoami-Gate + `LoginScreen` (Kratos same-origin) **IST** der Verbindungs-Schritt. Kein zweiter „Verbinden"-Button für (A).
- **Verbindungs-Status** (reuse comm `connection`: `live`/`connecting`/`offline`/`revoked`): die WS-Verbindung zeigt schon live/offline/revoked. Erweitern um den **Kontext**: „verbunden über Browser-Gateway".
- **★ Tier-Disclosure wiren (CYP-676, der Ehrlichkeits-Kern):** `RemoteSecurityTierBadge` existiert, ist aber **un-wired** (`RemoteSecurityTierBadge.tsx:10`). Für den Gateway-Pfad: Tier = **BROWSER_GATEWAY** → **immer-sichtbare INFO-Disclosure** („Über ein Browser-Gateway verbunden. Anders als bei nativer E2E endet die Verschlüsselung am Gateway…", der ratifizierte §4-Text), **nie tap-to-reveal**, **fail-closed default UNKNOWN** (nie optimistic NATIVE-green). **Das ist der load-bearing Teil** — der Nutzer sieht ehrlich, dass diese Verbindung dokumentiert schwächer ist.
- **Self-Hosted-Gating sichtbar (Option-A):** die Disclosure macht klar: vorgesehen für Deployments, die dir gehören (Gateway+Hub).

**Zustände (headless-messbar):**
| Zustand | Render |
|---|---|
| nicht eingeloggt | `LoginScreen` (CYP-515) — „connect" = login |
| verbindet (WS connecting) | connection-Status „Verbinde…" |
| verbunden (WS live) | connection „Verbunden" **+ BROWSER_GATEWAY-Tier-Badge + immer-sichtbare INFO-Disclosure** |
| Tier unbestimmt | Badge **UNKNOWN** (fail-closed, nie NATIVE) |
| offline / revoked | reuse comm offline/revoked-Banner (terminal ≠ reconnectable) |

## §3 Session-State + Revoke (nächster Screen — kurz, reuse)
- **Session-State:** verbunden/getrennt = whoami (CYP-515) + connection-Status. Kein neuer Store.
- **Revoke/Trennen = Logout** (Kratos-Session-Kill) → zurück zum `LoginScreen`. **Non-optimistisch** (Zustand flippt auf Server-Bestätigung, wie überall). Bei terminalem WS-Revoke (1008): der bestehende „Zugriff entzogen"-Pfad (comm `revoked`) gilt.

## §4 Discovery (kurz — reconcile mit Backend2)
Advisory `online`/`lastSeen` (HubDiscovery-Register „advisory H1") **falls im Gateway-Modell relevant** — beobachtet, nie Garantie; grob (Privacy). Reconcile: liefert der Gateway ein Discovery-Signal, oder ist der same-origin-Hub implizit „der eine"? (Bei EINEM Hub via same-origin ist Discovery evtl. n/a.)

## §5 ★ Team-1-Grenze (PO-Vorgabe, am Objekt bestätigt)
- **BASIS (dieser Spec):** Browser→Gateway→**EIN** Hub, same-origin `/api`+`/ws` (die 8 Data-Plane-Sockets). **Team-2-baubar.**
- **`/ws/hub` + `/api/cp/` sind am Edge ausgeschlossen** (`GatewayServer.kt` default-DENY) = die **Relay/Control-Plane-Leg**. Der **volle Remote-Operate** (Browser→Gateway→**Relay**→NAT'd Hub) berührt **Team-1s Relay-Leg** → **Team-1-Koordination, KEIN web-ts-Screen.** Ich spezifiziere das **nicht** als Team-2-Fläche; es ist die Team-1-Naht.

## §6 Honesty-Leitplanken (CYP-676 — tragen 1:1)
native = starke E2E mit Pin · browser-gateway = **dokumentiert schwächer, immer-sichtbar** (nie versteckt) · fail-closed **UNKNOWN** default (nie optimistic-green) · **Self-Hosted-only** (Option-A; cyppie-hosted-Browser-Gateway = separater späterer §4b, NICHT hier) · **nie eine native-Optik/Pin faken**, die der Browser strukturell nicht hat ([[migration-ports-tokens-not-optics]]).

## §7 Übergabe-Flags
- **(A)-Basis build-ready jetzt (Team-2):** reuse CYP-515-auth + comm-connection-state + **CYP-676-Tier-Badge wiren** (der load-bearing neue Teil). Kein neuer Connect-Store nötig.
- **(B) BYO-Gateway in-app-runtime-URL = OUT** (same-origin geklärt, §1) — „Gateway-URL eingeben" = Browser-Navigation, kein in-app Feld; nur relevant bei einem separaten Deploy-Modus (SPA anderswo gehostet).
- **Team-1-Grenze geflaggt:** Relay-Leg (`/ws/hub`, `/api/cp`) = Team-1, kein web-ts-Screen.
- **Nächste Gateway-Screen-Specs:** Session-State+Revoke (§3, klein), Discovery (§4, reconcile-abhängig). Tier-Disclosure-Copy = der ratifizierte CYP-676 §4-Text (reuse, nicht neu erfinden).
