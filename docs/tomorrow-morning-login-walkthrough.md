# Morgen-Früh-Walkthrough — Live-Dogfood GitHub-Login → B1 → Agent-Turn (Crown-Jewel-Test)

> **Für den Auftraggeber** (fährt den Desktop-Client) **+ die server-seitige QA-Verify-Lane** (S-Tester).
> Build: **develop `d06001a6`** (CYP-575 Browser-Launch-Fallback + CYP-576 native API-flow token-exchange + Follow-on:
> UX-States/Double-Click-Guard/Loopback-Server-Stop/Error-Split). Der Login-Fix ist **komplett** — dies ist der erste
> Ende-zu-Ende-Beweis.
>
> **Format je Schritt:** **Was du siehst · Was du tust · Was erwartet** + 🔎 **Server-Verify** (was QA/Team live
> gegenprüft — meist server-seitig, ohne dass du etwas tun musst). QA-Tool: `test/harness/github-oidc-verify.sh`.
> **Kein Blind-Klicken** — jeder Schritt hat ein konkretes „erwartet" und einen Fail-Watch.

## Vorab (QA, vor dem Run — schon erledigt)
- **Read-only Front-Door-Smoke = 11/11 grün** (`github-oidc-verify.sh`): Kratos bietet `github` als OIDC-Methode + baut
  einen korrekten GitHub-Redirect (client_id `Ov23lioeKkKuWAesQBKT`, redirect_uri = Kratos-oidc-callback, scope
  `user:email`, state). Der Server-seitige OIDC-Chain ist verified-good; der Fix von heute ist die **Client-Seite**
  (native token-exchange + Loopback-Return).
- **OOB-Fingerprint-Vergleichswerte** bereit (deploy hub-seitig + Backend zweite Berechnung). Erwartet: `Geiger…dreadful`.

---

## Schritt 1 — Client-Rebuild (den kompletten Login-Fix holen)

- **Was du tust:** im Repo-Ordner:
  ```
  cd <repo> && FIX_REF=origin/develop ./run-dogfood-client.sh
  ```
  (`FIX_REF` zeigt per Default schon auf `origin/develop` = `d06001a6` mit dem kompletten Fix.)
- **Was du siehst:** Fetch- + Build-Log → das Desktop-App-Fenster startet → **Login-Screen**.
- **Was erwartet:** App startet ohne Build-Fehler; Login-Screen mit E-Mail/Passwort **und** dem Button „Sign in with GitHub".
- 🔎 **Server-Verify:** — (lokaler Build). QA-seitig: `github-oidc-verify.sh` Hops 1–2 grün = die Front-Door lebt.

---

## Schritt 2 — GitHub-Login (der gefixte Pfad)

- **Was du siehst:** Login-Screen → Button **„Sign in with GitHub"**.
- **Was du tust:** **einmal** klicken. (Der Double-Click-Guard ist jetzt drin — aber einmal reicht ohnehin.)
- **Was erwartet:**
  1. Chrome / System-Browser **öffnet** (via `xdg-open`, CYP-575) auf der GitHub-OAuth-Seite.
     → *Falls KEIN Browser öffnet:* die in der App **sichtbare Fallback-URL** kopieren und manuell im Browser öffnen
     (CYP-575 loggt/zeigt die URL immer — kein stiller Dead-End mehr).
  2. GitHub-Login (dein Passwort/2FA) → **„Authorize"**.
  3. Browser zeigt **„Anmeldung abgeschlossen — zurück zur App"** (Loopback-Return, RFC 8252 auf `127.0.0.1:47472`).
  4. Die App wechselt von **„Weiter im Browser…"** direkt zum **Workspace/Hub** — **native token-exchange, KEIN
     Redirect-Fehler mehr** (der alte Bug). Dein Account ist `verified=true` → du landest direkt drin (kein Verify-Gate).
- 🔎 **Server-Verify (QA):** nach dem Return übergibst du/Team QA den `session_token` (oder QA liest ihn server-seitig),
  dann:
  ```
  SESSION_TOKEN=<x-session-token> ./github-oidc-verify.sh
  ```
  → **Hop 3** `authenticated=true` + **Hop 4** `/api/agents`·`/api/channels` = **200** = „Hub erscheint **echt**
  server-seitig", nicht nur Client-Anzeige. **Fail-Watch:** hängt die App auf „Weiter im Browser…" → Loopback nicht
  zurückgekommen (CYP-578-Klasse — Retry, der Guard verhindert jetzt Doppel-Arm); Chrome öffnet nicht → Fallback-URL.

---

## Schritt 3 — Hub → Remote → Verbinden → OOB-Fingerprint

- **Was du siehst:** Hub-Liste (**zeigt `hub_c1d6f5ffd892a03d`**); Remote-Connect-Option.
- **Was du tust:** Hub wählen → **Verbinden/Remote**. Am **OOB-Fingerprint-Dialog PAUSIEREN**.
- **Was erwartet:** eine **Wortliste** wird angezeigt. **Paste sie an den PO (a-po).** Erwartet: **`Geiger…dreadful`**.
- 🔎 **Server-Verify:** QA/deploy/Backend vergleichen die Wortliste aus **3 Quellen** (dein Client-Display +
  deploy-hub-seitig + Backend-Berechnung). **Pass NUR wenn alle drei identisch sind — Wort-für-Wort UND in Reihenfolge.**
  Ein Mismatch in einer → **fail-closed** (SF-3 identity-changed, Zeile C-F2) → **NICHT fortfahren**, an PO melden.
  S1-Server-Checks: genau **1** `TunnelAuthRequest`, CpJwt∧PoP **PASS vor** CONNECTED, ● nur bei echt-CONNECTED.

---

> **Precondition — `~/.cyppie`:** Für einen frischen First-Enroll sollte `~/.cyppie` **leer/absent** sein. Existiert
> bereits ein `operator-vault` (früherer Versuch), erwartest du den **PIN/Unlock**-Screen, **nicht** „Passphrase setzen"
> — das ist korrekt. Ein **korruptes** Vault → **OOB-Recovery** (kein Re-Enroll): das ist **by-design fail-closed**
> (E-F1/SF-3), **kein** Bug. Für einen sauberen Demo-Run optional vorher `mv ~/.cyppie ~/.cyppie.bak`.

## Schritt 4 — B1-Enroll (Passphrase → Recovery-Codes → Connected)

- **Was du siehst:** „App-Passphrase setzen"-Screen mit **Stärke-Meter** + **Diceware-Vorschlag** (Ein-Klick).
- **Was du tust:** eine **starke** Passphrase eingeben (oder den Diceware-Vorschlag übernehmen) → bestätigen. Dann die
  **Recovery-Codes** sichern und den „Ich hab sie gesichert"-Ack setzen.
- **Was erwartet:** Meter zeigt bei starker Phrase **`●` / „Strong"**; bei zu schwach **`▲`** + „too weak"; bei einer
  bekannten Phrase „too common" (**bleibt auf Enroll**, kein Reject-Reloop). Nach Confirm: Vault versiegelt →
  Recovery-Codes angezeigt → **„Connected"**.
- 🔎 **Server-Verify (B1 silent-fails, §8 / Path 1):**
  - **TOFU-Enroll:** CpJwt-Gate **vor** jedem Enroll-Write (kein Land-grab); `firstEnroll=true` → `EnrollResponse` E2E
    über den Tunnel → **Finalize erst auf Client-`SavedAck`** (Code-Hashes + Anchor **atomar**); kein Ack → discard →
    re-mint (**kein Lockout**). Store **0600** owner-only.
  - **Crown-jewel-fail-closed** (mensch-getriebene File-Injections nach QA-Schritten — im Happy-Path optional; die LOGIK
    ist CYP-542T-bewiesen): **corrupt-Vault → kein Re-Enroll** (E-F1, SF-3) · **Tamper → no-oracle** (E-F3) ·
    **Migration → kein Key-Loss** (E-F5).

---

## Schritt 5 — Agent-Turn (Connected + echter Turn)

- **Was du siehst:** Workspace/Hub mit Agenten-Roster; ein Eingabefeld **„Nachricht an den Agenten"** (kein Shell-Prompt).
- **Was du tust:** einen Agenten öffnen → eine Aufgabe/Nachricht schicken.
- **Was erwartet:** der Agent antwortet (Turn läuft sichtbar).
- 🔎 **Server-Verify:** Workspace-Request über den Tunnel = **200 + echter Roster**; ein **statischer Operator-Token über
  den Tunnel → 401** (god-token-per-tunnel, QA prüft); der Agent-Turn erscheint **server-seitig** (nicht nur
  Client-Echo). **V6/CYP-573-Watch:** der Dot-Status über einen Reconnect — bleibt er stale-`RUNNING`, ist das die
  dokumentierte CYP-573-Manifestation (erwartet, wird server-seitig festgehalten).

---

## ★ CYP-588 — Transcript-Vollständigkeit unter Slow-Consumer (optional, non-vakuos)

> Best-effort **Live-Bestätigung**, dass der Gap-Detect (CYP-588, gemergt `c5cbae6e`) eine echte `/ws/agent`-Buffer-Lücke
> heilt. **Der Mechanismus ist bereits bewiesen** — in-process `Cyp588LiveGapDetectTest` (echter `SqliteAgentEventStore`/
> DROP_OLDEST, echter slow-Subscriber, echter >256-Burst, **mutation-RED**). Dieser Schritt fügt nur „über die echte WS"
> hinzu — **mit hartem Non-Vakuitäts-Guard: ohne echten >256-Drop → INCONCLUSIVE, KEIN fake-pass.**

- **Was du tust (du bist OPERATOR):** auf einem Agenten einen **verbosen Turn** treiben (Aufgabe mit viel Ausgabe, z.B.
  „liste 400 nummerierte Zeilen mit je einem kurzen Satz") → **während er streamt, das Agenten-Fenster ~5–10 s
  backgrounden/minimieren** (drosselt den WS-Consumer → DROP_OLDEST droppt mid-stream) → wieder in den Vordergrund →
  **reconnect** (die App re-subscribed mit `?since=<lastSeq>`).
- **Was erwartet (falls die Lücke real war):** das Transcript ist nach dem Reconnect **vollständig** — **kein Loch**,
  seq-kontinuierlich; der gedroppte Bereich wurde aus dem durable Store nachgefüllt.

🔎 **Server-Verify (QA/Team-2, creds-seitig; der Backend arbitriert die maskierten Marker, Path B):**
**Schritt 1 — ZUERST beweisen, dass der Drop REAL war (sonst vakuos):**
1. der `durable`-Event-Count des Agenten stieg im Fenster um **> 256** (`GET /ws/agent?agentId=<A>&since=0` drainen +
   zählen, vorher/nachher).
2. das Live-Fenster empfing **weniger** als durable hat (pre-backfill) → **ein Drop ist beweisbar passiert**.
3. der WARN feuerte: `CYP-588: live-buffer gap seq <from>..<to> (slow consumer + DROP_OLDEST) — backfilled from the
   durable store: agent=<A>` (`SqliteAgentEventStore.kt:87`).
→ Halten (1)–(3) **nicht** alle (der verbose Turn emittierte ≤ 256 Events → kein Drop): **als INCONCLUSIVE markieren** —
   der Mechanismus-Beweis bleibt der in-process-Test. **Kein fake-pass.**

**Schritt 2 — erst DANN: beweisen, dass geheilt wurde:**
4. das im Fenster empfangene Transcript == durable `since=0` (seq-kontinuierlich, **kein Loch**, Count-Match) →
   **GRÜN = Backfill live bestätigt.**

> Reihenfolge ist load-bearing: Schritt 1 (Drop real) **VOR** Schritt 2 (geheilt). Schritt-2-grün ohne Schritt-1 =
> vakuos, kein Pass. Voller Plan: `deploy/CYP-588-post-deploy-live-verify-plan.md`; Gate-Referenz:
> `scratchpad/cyp588_live_verify.py --dry-check`.

---

## ★ Persistenz (optional, nach einem Hub-Restart)

- **Was du tust:** (falls getestet) Hub neustarten → App reconnecten.
- **Was erwartet:** das bereits-enrollte Gerät geht **direkt zu PIN** — **kein** Re-Enroll, **keine** neuen Codes.
- 🔎 **Server-Verify:** **0** neue `EnrollResponse`/Code-Mint-Events nach dem Reboot; **Anchor unverändert** (SF-3
  Crown-Jewel — ein neuer Anchor = silent-Re-Enroll = Key-Substitution).

---

## Quick-Triage (falls etwas hängt)

| Symptom | Ursache / Aktion |
|---|---|
| App hängt auf „Weiter im Browser…" | Loopback nicht zurück (CYP-578-Klasse) → Retry (Double-Click-Guard verhindert jetzt Doppel-Arm); sonst App neu starten. |
| Login-Return sofort **Error** / „Bind"-Fehler beim Start | Ein alter Client-Prozess hält noch Port **47472** → freigeben: `lsof -ti:47472 \| xargs -r kill` (hartnäckig: `kill -9`; oder `pkill -f desktopApp`) → App neu starten + retry. |
| App kehrt aus dem Browser zurück, zeigt aber **Error/Login statt Workspace** (≠ „hängt im Browser auf Weiter") | Der `state`-Nonce hat den Callback abgelehnt (state fehlt/mismatch). **Erwartet ist das NICHT** — Kratos v1.3.0 erhält den `state`. Callback-URL prüfen: enthält sie `?state=…&code=…`? **Fehlt `state`** → unerwartetes Kratos-Append-Verhalten → an **PO/Backend eskalieren** (nicht selbst weiterklicken). |
| Kein Browser öffnet | Fallback-URL aus der App kopieren + manuell öffnen (CYP-575). |
| Verify-Pending-Gate (unerwartet — dein Account ist verified=true) | Re-Probe-Button „Weiter/Aktualisieren" (CYP-576-Follow-on / CYP-582 fix a); sonst Logout → Re-Login. |
| OOB-Fingerprint ≠ `Geiger…dreadful` | **NICHT fortfahren** — an PO melden (fail-closed, C-F2). |
| Hub-Liste **leer** bei Schritt 3 | Wahrscheinlich als **falscher GitHub-Account** autorisiert (erwartet: Operator `ab7c54e3`, Hub `hub_c1d6f5ffd892a03d`). In Chrome bei GitHub **ausloggen** (oder Inkognito-Fenster) → erneut „Sign in with GitHub". |
| Agent-Turn erscheint client-seitig, aber nicht server-seitig | an QA/PO melden (Turn nicht echt ausgeführt). |

> **Referenzen:** QA-Katalog `test/M1-M2-dogfood-qa-scenario-catalog.md` (Path 0 Login, Path 1 Enroll, §8 Server-Verify-
> Checkliste) · Harness `test/harness/github-oidc-verify.sh` + `github-oidc-maestro.yaml` · CYP-576-Deploy→Verify-Runbook
> (docs) · verwandte offene Tickets: **CYP-578** (Loopback-Bind-Swallow, im Follow-on adressiert), **CYP-582**
> (Verify-Gate/Deep-Link-Gap, non-blocking bei verified=true).
