# Desktop-Native Remote-Operator-UX — Design-Spec (CYP-460, Epic CYP-427)

> Status: **Spec-Closure — Q-Rulings gefolded 2026-07-12** (Q4/Q5 geruled; Q2/Q3 **Threat-Model-parametrisiert**, exakte
> Werte folgen; **Q6 offen/eskaliert an Auftraggeber** — Recovery, §5.2) · docs-only, **kein Bau** · Owner: UX/UI
> **Ergänzung** zu `remote-operator-ux-spec.md` (CYP-429, web-zentriert) für den **Desktop-Native-Fall** (Team-1 zuerst,
> RR6=ii). Baut zusätzlich auf CYP-449 (Desktop-Shell). Gegen echten Code gegroundet (CYP-443-Seams, read-only).
> Eingefrorene Companion-Files (Haus-Konvention): `desktop-remote-operator-keys.md` · `-tags.md` · `-tokens.json`.
> Naht-Konsistenz zu CYP-429/CYP-443/CYP-449 über den PO (Passkey → Backend `OperatorAssertionVerifier`).

⭐**Grounding-Kern-Befund:** CYP-443 (Worktree `.worktrees/CYP-443-operator-auth`) hat **nur Slice 1 gebaut** (Noise-Tunnel
+ Session-State-Machine). Die **operator-facing PoP-Taxonomie, Keystore-Enroll/Unlock, PIN/Passphrase-Eingabe und das
Fingerprint-Format sind in Code UNDEFINIERT** (nur Seams: `OperatorAuthenticator.authenticate→Boolean`,
`HubTrust.resolve→TrustResolution`). **Diese Spec gibt genau diesen Seams UX-Gestalt.**

---

## 1. Kern-Ehrlichkeit (tragende Entscheidungen)

- **H1 — DevicePoP-Union ehrlich über den aktiven Pfad.** Zwei Pfade: **Raw** (OS-Keystore-Key + expliziter
  **App-PIN/Passphrase**, cross-platform inkl. **Linux**) und **Fido2** (Touch-ID/Windows-Hello, macOS/Win, **progressive
  enhancement**). Die UI sagt **ehrlich, welcher Pfad aktiv ist und warum** — nie eine Biometrie vortäuschen, wo nur PIN
  existiert (Linux = immer PIN). Fido2 bevorzugt wo verfügbar; Raw/PIN ist der cross-platform **Boden**.
- **H2 — PoP fail-closed & Hub-Reject terminal.** `OperatorAuthenticator` ist fail-closed (jeder Fehler ⇒ nicht gewährt);
  ein **Hub-Reject** = `RemoteFailure.AuthRejected` **terminal, kein stiller Retry** (`RemoteHubSession.kt:135-146`). Die
  UX rendert das ehrlich: **lokale** Wrong-PIN-Retries (vor der Assertion) mit Versuchszähler + Lockout ≠ ein
  **hub-seitiger** Reject (terminal, neu anmelden). Die zwei Fehlerklassen nie vermischen.
- **H3 — Native Login ehrlich: Browser-Handoff für OIDC, kein Fake-Webview.** Email/Passwort ist nativ-in-app
  (`AuthPasswordField`); **GitHub-OIDC** übergibt an den **System-Browser** (RFC-8252-Muster, heute `Desktop.browse()`) —
  das ist das **sichere** native Muster, **kein** eingebetteter Webview (Anti-Pattern). Die UX sagt ehrlich „weiter im
  Browser … zurück zur App", täuscht keine In-App-Federation vor.
- **H4 — TOFU wie CYP-429, gegen echte `TrustResolution`-Zustände.** `FirstUse(fingerprint)` = OOB-Bestätigung + Pin (die
  Confirm-UX ist laut Code „Slice-3's concern" = **meine Fläche**); `Changed` = **harter Block, terminal**
  (`RemoteFailure.TrustChanged`, OOB-Re-Pin, nie still); `Pinned` = still gegen den Pin verifiziert. Fingerprint-Format ist
  **in Code undefiniert** → ich spezifiziere die **mehrschichtige** Darstellung (Wort-/Emoji-Sequenz primär, Hex + QR
  sekundär — konsistent zu CYP-429 §8/Q4).
- **H5 — „E2E via Relay" NICHT über-versprechen.** Noise (`Noise_NK_25519_ChaChaPoly_BLAKE2s`, eine gepinnte Suite, **kein**
  Plaintext-Fallback, RR8) macht die **Nutzlast** vertraulich — aber der Relay **sieht Größen/Timing** (Metadaten nicht
  gepolstert, CYP-443-Framing §6 „do not over-promise"). Der Indikator sagt **nur** „Nutzlast E2E-verschlüsselt", **nicht**
  „vollständig anonym/unsichtbar".
- **H6 — Outbound-only Relay-Drop ⇒ in-flight ehrlich ungewiss.** Der Tunnel ist outbound-only; ein Transport-Reset ⇒
  `reportDropped()` ⇒ Reconnect ⇒ **in-flight-Aktionen ungewiss** (Framing §4, „did my action complete?"), nie still als
  erledigt.
- **H7 — Keystore-Sicherheit ehrlich, plattformbewusst.** `SecureSessionStore.Persistence.DEVICE_SECURE` ist
  **named-not-built** (Phase-1 in-memory; macOS-Keychain/Linux-libsecret/Windows als Kommentar). Die UX **verspricht keine
  Hardware-gestützte Sicherheit, die die Plattform noch nicht liefert** — zurückhaltende, plattformbewusste Copy.

---

## 2. Scope

- **Desktop-Native (Team-1 zuerst; Web-UI/Team-2 teilt `commonMain`).** Ergänzt CYP-429 (web-zentriert) um: native
  Login-Shell, **DevicePoP-Union-Prompt-Flow** (der Kern), native TOFU-Bestätigung, native Verbindungs-/Reconnect-Zustände.
- **Enthalten:** §4 native Login-Shell · §5 DevicePoP-Union (Enroll/Unlock/PIN/Biometrie/Fehler/Fallback) · §6 TOFU nativ ·
  §7 Verbindungszustand/Reconnect · §8 Operator-Surface-Reuse.
- **Nicht enthalten:** die Krypto/Slice-1 (CYP-443 gebaut), der Shell-Rahmen (CYP-449), der web-zentrische Flow (CYP-429).

---

## 3. Reuse/Grounding-Karte (gegen echten Code @ CYP-443 read-only + develop)

- **Session-State-Machine (real, Slice 1):** `net/hub/remote/RemoteSessionState.kt` — `RemoteConnState{… AUTHENTICATING,
  TRUST_CHECK, LOST}` (:14), `RemoteFailure{AuthRejected(:25), TrustChanged(expectedFingerprint)(:23)}`,
  `OperatorAuthenticator.authenticate(tunnel,hubId): Boolean` (:70, PoP = `H(h‖hubId‖nonce‖"operator-auth")`, UV required,
  fail-closed), `HubTrust.resolve(hubId): TrustResolution` (:52) mit `Pinned(hubStatic)`/`FirstUse(hubStatic,fingerprint)`/
  `Changed(expectedFingerprint)` (:56-63). Flow `RemoteHubSession.kt:100-146` (trust→handshake→auth, terminal fail-closed).
- **UNDEFINIERT in Code (= meine UX-Fläche):** PoP-Ergebnis-Taxonomie (nur `Boolean`), Raw-vs-Fido2-Modellierung,
  Keystore-Enroll/Unlock, PIN/Passphrase-Eingabe, Fingerprint-String-Format.
- **Keystore-Abstraktion:** `auth/SecureSessionStore.kt` (`expect class`, `Persistence{SESSION_ONLY, DEVICE_SECURE}` :22,
  DEVICE_SECURE named-not-built; per-Plattform actuals in-memory). **Kein** PIN/Enroll/Unlock modelliert = neu.
- **Maskiertes-Eingabe-Vorbild:** `auth/AuthComponents.kt:132` `AuthPasswordField` (`PasswordVisualTransformation`,
  Text-Label-Reveal, nie a11y-Leak) = **PIN/Passphrase-Basis**. Auch `ApiKeySection`/`HubConnectFlow`-Credential.
- **Native Login:** `desktopApp/.../main.kt:8-20` `Window("KMPCyppieAgents")` + `onOpenExternalUrl → Desktop.browse()`
  (System-Browser); `auth/AuthGate.kt` LoginScreen; GitHub-OIDC `GithubUiState{Idle,Redirecting,Returning,Error}` shellt
  zum Browser (`:209`). **Kein** In-App-Webview/native Federation existiert.
- **Framing:** `docs/design/CYP-443-tunnel-framing-spec.md` — Noise NK outbound-only, kein Plaintext-Fallback (RR8, §6);
  Close-Semantik §4 (Drop ⇒ in-flight ungewiss); Metadaten nicht gepolstert §6 (nicht über-versprechen).
- **CYP-429-Reuse:** Fern-Betrieb-Banner, TOFU-Muster (Wordlist/Hex/QR), Relay-Drop, `remote_*`/Area `remote`,
  `remote.authStep.*`-Tags (popPrompt/enroll/error) — **diese Spec vertieft sie nativ**.

---

## 4. Native Login-Shell (kein Browser-Chrome)

Der zentrale Login (`mail@thorsten-neff.de`) läuft in der **nativen Desktop-Shell** (CYP-449), kein Browser-Rahmen.

- **Email/Passwort:** nativ-in-app (`AuthPasswordField`) — unverändert, kein Browser.
- **GitHub-OIDC (ehrlicher Handoff, H3; Q4 ratifiziert = Loopback RFC 8252 §7.3):** die App initiiert, **öffnet den
  System-Browser** (Authorization-Code + PKCE + **Loopback/localhost-Redirect**). UI-Zustand: „**Weiter im Browser …**"
  (Kontrollen deaktiviert) → „**Zurück zur App …**" (Loopback empfangen) → Login fertig. **Kein** eingebetteter Webview;
  ehrlich, dass der Schritt die App verlässt.
- **Fehler:** Browser-Rückkehr scheitert/Timeout → ehrlicher Retry („Anmeldung im Browser nicht abgeschlossen"); kein
  stiller Hänger.
- Danach → **DevicePoP-Schritt (§5)** → Hub-Liste (CYP-429/CYP-449).

---

## 5. DevicePoP-Union UX (Kern — RR2=B)

Der verpflichtende PoP-Schritt (CYP-429 §5, jetzt **nativ konkretisiert**). **Union** aus zwei Pfaden; die UX wählt den
stärksten verfügbaren, sagt ehrlich welcher.

### 5.1 Pfad-Wahl (H1, progressive)
- **Fido2-Pfad** (macOS Touch-ID / Windows Hello): bevorzugt wo verfügbar. Native OS-Biometrie-Aufforderung.
- **Raw-Pfad** (OS-Keystore-Key + **App-PIN/Passphrase**): der **cross-platform Boden** (Linux = immer, sonst Fallback).
- **Ehrlich:** die UI benennt den aktiven Pfad („Mit Touch-ID bestätigen" vs „App-PIN eingeben") — nie Biometrie-Optik
  ohne Biometrie. Ein kleiner „Wie wird bestätigt?"-Hinweis erklärt den Pfad (progressive, kein Alarm).

### 5.2 Enroll (Erst-Setup)
Kein Device-Key vorhanden ⇒ **Enroll-Schritt** zuerst:
- **Fido2:** Biometrie/Platform-Authenticator registrieren (OS-Flow).
- **Raw:** Device-Keypair im OS-Keystore erzeugen + **App-PIN/Passphrase setzen** (zweifache Eingabe, Stärke-Hinweis
  ehrlich/kein-Zwang-Overkill). **H7 / Q5 ratifiziert:** solange `DEVICE_SECURE` **named-not-built** ist (Phase-1
  in-memory), sagt die Copy **ehrlich „nur für diese Sitzung"** — **keine** Zusage von geräte-persistentem Schlüsselbund/
  Hardware-Sicherung. Erst **wenn `DEVICE_SECURE` gebaut ist**, wechselt die Copy auf die plattformbewusste
  „im Schlüsselbund dieses Geräts gesichert"-Aussage. Nicht über-versprechen.
- **Recovery** (verlorener Device-Key) — **Q6 OFFEN/ESKALIERT (Reviewer-Verdikt, RR7/RR2-B):** Re-Enroll **=** Re-Pin;
  **KEIN „Zentral-Login-allein"-Flow** — Zentral-Login-allein würde einen CP-/Account-Kompromiss zur **Operator-Seizure**
  machen. Empfehlung: **hub-lokaler OOB-Schritt** beim Re-Enroll (Recovery-Friction-Trade-off = **Auftraggeber-Entscheidung**).
  Der Pfad ist **markiert** (keine Aussperr-Sackgasse), aber die UX **zeichnet ihn NICHT** aus, bis der Auftraggeber den
  Re-Enroll-Mechanismus ruled — kein vorgetäuschter Ein-Klick-Recovery.

### 5.3 Unlock (Folge-Nutzung) + Ergebnis-Taxonomie (NEU — ich definiere)
`OperatorAuthenticator` gibt heute nur `Boolean`. Operator-facing brauchen wir **mehr Stufen** (Design-Gap → Naht S-1):

| Zustand | Bedeutung | UX |
|---|---|---|
| `idle` | vor Aufforderung | neutral |
| `prompting` | PIN-Feld **oder** Biometrie-Prompt aktiv | neutral, Pfad benannt (5.1) |
| `verifying` | Assertion signiert, Hub prüft (`AUTHENTICATING`) | neutral, non-blocking Spinner |
| `granted` | Hub gewährt | weiter zur Hub-Liste (kein Erfolgs-Grün) |
| `pinWrong` | **lokal** falsche PIN (vor Assertion) | Fehler + **Versuchszähler**; nach N → `lockedOut` |
| `lockedOut` | zu viele PIN-Fehler | temporäre Sperre + ehrliche Wartezeit; **≠** Hub-Reject |
| `biometricFailed` | Biometrie fehlgeschlagen/abgebrochen | **Fallback auf PIN** (Raw), ehrlich |
| `cancelled` | Nutzer bricht ab | zurück, kein Zustand geändert |
| `needsEnroll` | kein Key | → Enroll (5.2) |
| `keystoreUnavailable` | Keystore nicht erreichbar | ehrlicher Fehler, kein Fake-Erfolg |
| `authRejected` | **Hub** lehnt ab (`RemoteFailure.AuthRejected`, terminal) | **terminal**, neu anmelden — **kein** stiller Retry (H2) |

**Ehrlichkeits-Trennung (H2):** `pinWrong`/`lockedOut` sind **lokal** (Versuch vor der Assertion); `authRejected` ist
**hub-seitig terminal**. Nie vermischen (ein lokaler Tippfehler ist kein „vom Hub abgelehnt").

### 5.4 PIN/Passphrase-Feld
Reuse `AuthPasswordField` (maskiert, Text-Label-Reveal, nie a11y-Leak). Kein Klartext-Echo; Reveal nur der aktuellen Eingabe.

**Q2/Q3 = Threat-Model-parametrisiert (Rulings, exakte Werte folgen):** **PIN** (numerisch, Richtwert ≥6) als schneller
Default, **Passphrase** als Höher-Sicherheit-Opt-up; **Lockout** nach N lokalen Fehlversuchen (Richtwert N=5) mit
exponentiellem Backoff. Länge/Stärke/N/Backoff sind **Threat-Model-Parameter** — die UX zeichnet die *Zustände*
(Versuchszähler, Lockout-Wartezeit), die *Zahlen* kommen vom Threat-Model (nicht hartkodiert in der Spec).

---

## 6. TOFU (nativ, gegen `TrustResolution`)

Rendert die echten drei Zustände; Fingerprint-Format definiere ich (Code undefiniert).
- **`FirstUse(fingerprint)`:** Trust-Prompt + **OOB-Bestätigung** — mehrschichtig (CYP-429 Q4): **primär Wort-/Emoji-Sequenz**
  (fehlerarmer OOB-Abgleich), **sekundär Hex** (kopierbar) **+ QR**. „Über einen anderen Kanal bestätigen", dann pinnen.
- **`Pinned`:** still gegen Pin verifiziert; kleiner neutraler „Identität gepinnt"-Indikator (Kontext-Banner).
- **`Changed(expectedFingerprint)`:** **harter Block, terminal** (`RemoteFailure.TrustChanged`) — „Identität von Hub X
  geändert (mögliches MITM)", **nie still**; Fortfahren nur nach explizitem **OOB-Re-Pin**. WARN-Amber, nie `tertiary`-Grün.
- Konsistent zu CYP-429 §8; Töne/Copy **identisch**, keine Varianten.

---

## 7. Verbindungszustand / Reconnect (Noise outbound-only)

- **Connect-Progression** rendert `RemoteConnState`: … → `TRUST_CHECK` (§6) → `AUTHENTICATING` (§5) → LIVE (`●`+primary nur
  bei echtem LIVE) → `LOST`. Typisierte Terminals: `AuthRejected` (§5), `TrustChanged` (§6).
- **Relay-Drop (H6):** outbound-only Reset ⇒ `reportDropped()` ⇒ **ein globales** Reconnect-Surface (CYP-449 §7), **in-flight
  ungewiss** (Framing §4), nie still als erledigt. Backoff + Cursor-Resume (gapless).
- **E2E-Indikator (H5):** „Nutzlast E2E-verschlüsselt via Relay" — **nicht** über-versprechen (Relay sieht Größen/Timing);
  neutral, nie grün, nie als Hub-Vertrauen (§6-getrennt).
- **Kein Plaintext-Fallback (RR8):** Remote ist immer Noise; scheitert der Tunnel, gibt es **keinen** unsicheren Ersatz —
  ehrlicher Fehler.

## 8. Voller Operator-Surface remote

Reuse CYP-429 §10 (Surfaces über Relay, Optimistic-vs-confirmed-Ehrlichkeit) + CYP-449 (Shell rahmt aktiven-Hub-Workspace).
Agenten/Projekte konfigurieren + chatten laufen über den Noise-Tunnel; keine neue Optimistik. Der Desktop-Pass ändert das
**nicht** — er liefert nur den nativen Weg **hinein** (Login+PoP+TOFU).

## 9. Maritim + M3

- Native OS-Dialoge (Biometrie) folgen dem OS; App-eigene Schritte (PIN, Enroll, TOFU) maritim+M3. PIN maskiert; Biometrie-
  Aufforderung neutral. **WARN-Amber nur** Trust-Änderung (§6); **errorContainer** harte Terminals (AuthRejected/Reconnect-
  fail); **nie** `tertiary`-Grün als Status. Farbe nie allein (1.4.1); Dark/Light `maritimeColorScheme`.

## 10. testTag-Kontrakt (Übersicht — maßgeblich: `desktop-remote-operator-tags.md`)

Konsistent zu `hubconnect_*`/`remote_*`, Distanz zu `connector_*`. **Vertieft** die CYP-429-`remote.authStep.*`-Tags nativ;
spiegelt den **eingefrorenen** `desktop-remote-operator-tags.md`:
```
remote.authStep.pathHint            (5.1, welcher Pfad)
remote.authStep.pinField            (5.4)
remote.authStep.pinReveal           (5.4)
remote.authStep.biometricPrompt     (5.1 Fido2)
remote.authStep.enroll              (5.2, bestehend CYP-429)
remote.authStep.enrollPinSet        (5.2 Raw)
remote.authStep.attempts            (5.3 Versuchszähler)
remote.authStep.lockedOut           (5.3)
remote.authStep.error.<cause>       (5.3: pinWrong/biometricFailed/keystoreUnavailable/authRejected/needsEnroll/cancelled)
remote.login.browserHandoff         (4, „weiter im Browser")
remote.login.browserReturn          (4, „zurück zur App")
```
**Reuse:** `remote.trust.{fingerprint,wordlist,hex,qr,pinPrompt,changedAlarm}`, `remote.connect.*`, `remote.relayDrop`,
`remote.context.*` (CYP-429). **Fail-closed-Anker:** `authRejected` terminal (kein Retry-Tag); `changedAlarm` hard-block;
Biometrie-Fehl → PIN-Fallback sichtbar.

## 11. Copy (Übersicht — maßgeblich: `desktop-remote-operator-keys.md`)

Key-Familie `remote_pop_*` / `remote_login_*` (greenfield, 0 Kollision verifiziert). Reuse CYP-429 `remote_trust_*`. Diese
Tabelle spiegelt den **eingefrorenen** `desktop-remote-operator-keys.md`:
| Key | DE | EN |
|---|---|---|
| `remote_pop_pin_title` | App-PIN eingeben | Enter your app PIN |
| `remote_pop_pin_body` | Bestätige mit deiner App-PIN, um remote auf deine Hubs zuzugreifen. | Confirm with your app PIN to access your hubs remotely. |
| `remote_pop_biometric_title` | Mit %1$s bestätigen | Confirm with %1$s |
| `remote_pop_path_hint` | Bestätigung über %1$s | Confirming via %1$s |
| `remote_pop_wrong_pin` | Falsche PIN. Noch %1$s Versuche. | Wrong PIN. %1$s attempts left. |
| `remote_pop_locked` | Zu viele Fehlversuche. Erneut in %1$s. | Too many attempts. Try again in %1$s. |
| `remote_pop_biometric_failed` | Biometrie fehlgeschlagen — nutze deine App-PIN. | Biometrics failed — use your app PIN. |
| `remote_pop_rejected` | Vom Hub abgelehnt. Bitte neu anmelden. | Rejected by the hub. Please sign in again. |
| `remote_pop_enroll_title` | Dieses Gerät einrichten | Set up this device |
| `remote_pop_enroll_pin` | App-PIN festlegen | Set an app PIN |
| `remote_pop_enroll_session_only` | Gilt nur für diese Sitzung — sicherer Geräte-Schlüsselbund folgt. | Applies to this session only — secure device keychain to come. |
| `remote_pop_keystore_unavailable` | Schlüsselbund nicht verfügbar. | Keychain not available. |
| `remote_login_browser_handoff` | Weiter im Browser … | Continuing in your browser… |
| `remote_login_browser_return` | Zurück zur App … | Returning to the app… |

*(Reuse CYP-429: `remote_trust_*`, `remote_e2e_indicator`, `remote_relay_dropped`, `remote_context_operating`. `%1$s` =
Pfad-Name „Touch-ID"/„Windows Hello", Versuchszahl, Wartezeit — kein Secret/Key-Rohwert.)*

## 12. Entscheidungen (Q-Rulings PO 2026-07-12)

1. **Q1 — Ticket:** CYP-460 (Story unter Epic CYP-427). ✅
2. **Q2 — PIN/Passphrase = Threat-Model-parametrisiert:** PIN (numerisch, Richtwert ≥6) Default + Passphrase-Opt-up; exakte
   Länge/Stärke folgen. UX zeichnet die Zustände, Zahlen kommen vom Threat-Model (§5.4).
3. **Q3 — Lockout = Threat-Model-parametrisiert:** N lokale Fehlversuche (Richtwert 5) + exp. Backoff; exakte Werte folgen (§5.4).
4. **Q4 — Native-OIDC-Rückkehr = Loopback (RFC 8252 §7.3).** ✅ (§4).
5. **Q5 — Keystore-Disclosure = ehrliche „nur für diese Sitzung"-Copy** bis `DEVICE_SECURE` gebaut; dann plattformbewusste
   Schlüsselbund-Copy. ✅ (§5.2/H7).
6. **Q6 — Recovery: 🔴 OFFEN/ESKALIERT (Auftraggeber).** Reviewer-Verdikt: **Re-Enroll = Re-Pin (RR7/RR2-B), KEIN
   Zentral-Login-allein** (das würde CP-/Account-Kompromiss zur Operator-Seizure machen); Empfehlung **hub-lokaler
   OOB-Schritt**. Recovery-Friction-Trade-off = Auftraggeber-Entscheidung; die UX zeichnet den Re-Enroll-Flow **NICHT** aus,
   bis geruled (§5.2).

## 13. Nahtstellen (über den PO)

- **S-1 — `OperatorAuthenticator`-Ergebnis erweitern:** heute `Boolean` → die operator-facing Taxonomie (§5.3:
  granted/pinWrong/lockedOut/biometricFailed/cancelled/needsEnroll/keystoreUnavailable/authRejected) braucht ein reicheres
  Ergebnis, damit die UX **nicht rät**. Raw-vs-Fido2-Pfad muss erkennbar sein.
- **S-2 — `OperatorDeviceKeyStore` (Slice 2, ungebaut):** Enroll (Keypair erzeugen + PIN setzen) vs. Unlock (PIN/Biometrie)
  Operationen; cross-platform actuals inkl. Linux (libsecret) + PIN-Unlock. Persistence `DEVICE_SECURE` bauen.
- **S-3 — `HubTrust`/`TrustResolution`-Fingerprint-Format:** Backend/Krypto liefert Bytes; Client leitet Wort-/Emoji-Sequenz
  + Hex + QR ab (Format definieren, konsistent CYP-429 Q4).
- **S-4 — Native-OIDC-Handoff:** System-Browser + Loopback/Deep-Link-Rückkehr (RFC 8252); Dev-Transport-Naht.
- **S-5 — Backend `OperatorAssertionVerifier`** (aus CYP-429 S-4): prüft die PoP-Assertion (channel-bound an Noise-`h`).
- **Metadaten-Ehrlichkeit (Framing §6):** E2E-Copy nicht über Metadaten hinaus versprechen.

## 14. Acceptance-Teeth (für spätere §-QA)

1. **Union ehrlich (H1):** aktiver Pfad benannt (PIN vs Biometrie); Linux nie Biometrie-Optik; Fido2-Fehl → PIN-Fallback sichtbar.
2. **Fehler-Klassen getrennt (H2):** lokal `pinWrong`/`lockedOut` (Zähler) ≠ hub-seitig `authRejected` (terminal, kein Retry).
3. **Native Login ehrlich (H3):** OIDC-Handoff „weiter im Browser/zurück zur App", kein Fake-Webview; Email/Passwort nativ.
4. **TOFU (H4):** FirstUse OOB-Confirm (Wordlist/Hex/QR) + Pin; Changed harter Block + OOB-Re-Pin, nie still; identisch CYP-429.
5. **E2E nicht über-versprochen (H5):** Indikator = nur Nutzlast; Metadaten-Grenze ehrlich; nie grün/nie Hub-Vertrauen.
6. **Relay-Drop (H6):** ein globales Reconnect-Surface, in-flight ehrlich ungewiss; kein Plaintext-Fallback (RR8).
7. **Keystore-Disclosure ehrlich (H7):** keine Hardware-Zusage die die Plattform nicht hält (DEVICE_SECURE named-not-built).
8. **Reuse:** PIN=`AuthPasswordField`; TOFU/Relay/Kontext=CYP-429; Shell=CYP-449; Zustände=echte CYP-443-Seams — keine Divergenz.
9. **Farbe nie allein (1.4.1) & Maritim:** WARN nur Trust-Änderung, error nur harte Terminals, nie `tertiary`-Grün; Dark/Light.

---

*Spec-Closure (Q-Rulings gefolded 2026-07-12; Q2/Q3 Threat-Model-parametrisiert, **Q6 Recovery offen/eskaliert an
Auftraggeber**). Die eingefrorenen Companion-Files (`desktop-remote-operator-keys.md` / `-tags.md` / `-tokens.json`) sind
die UI-Vorlage; §10/§11 sind Übersicht. Gegroundet gegen CYP-443-Seams (read-only, nichts angefasst). Nichts gebaut.
Naht-Konsistenz CYP-429/443/449 über den PO. **Offen bleibt bewusst: Q2/Q3-Zahlen (Threat-Model), Q6-Recovery-Mechanismus
(Auftraggeber) — die UX zeichnet diese nicht vorweg.***
