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

- **Was du siehst:** Hub-Liste; Remote-Connect-Option.
- **Was du tust:** Hub wählen → **Verbinden/Remote**. Am **OOB-Fingerprint-Dialog PAUSIEREN**.
- **Was erwartet:** eine **Wortliste** wird angezeigt. **Paste sie an den PO (a-po).** Erwartet: **`Geiger…dreadful`**.
- 🔎 **Server-Verify:** QA/deploy/Backend vergleichen die Wortliste aus **3 Quellen** (dein Client-Display +
  deploy-hub-seitig + Backend-Berechnung). **Pass NUR wenn alle drei identisch sind — Wort-für-Wort UND in Reihenfolge.**
  Ein Mismatch in einer → **fail-closed** (SF-3 identity-changed, Zeile C-F2) → **NICHT fortfahren**, an PO melden.
  S1-Server-Checks: genau **1** `TunnelAuthRequest`, CpJwt∧PoP **PASS vor** CONNECTED, ● nur bei echt-CONNECTED.

---

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
| Kein Browser öffnet | Fallback-URL aus der App kopieren + manuell öffnen (CYP-575). |
| Verify-Pending-Gate (unerwartet — dein Account ist verified=true) | Re-Probe-Button „Weiter/Aktualisieren" (CYP-576-Follow-on / CYP-582 fix a); sonst Logout → Re-Login. |
| OOB-Fingerprint ≠ `Geiger…dreadful` | **NICHT fortfahren** — an PO melden (fail-closed, C-F2). |
| Agent-Turn erscheint client-seitig, aber nicht server-seitig | an QA/PO melden (Turn nicht echt ausgeführt). |

> **Referenzen:** QA-Katalog `test/M1-M2-dogfood-qa-scenario-catalog.md` (Path 0 Login, Path 1 Enroll, §8 Server-Verify-
> Checkliste) · Harness `test/harness/github-oidc-verify.sh` + `github-oidc-maestro.yaml` · CYP-576-Deploy→Verify-Runbook
> (docs) · verwandte offene Tickets: **CYP-578** (Loopback-Bind-Swallow, im Follow-on adressiert), **CYP-582**
> (Verify-Gate/Deep-Link-Gap, non-blocking bei verified=true).
