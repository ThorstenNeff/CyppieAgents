# Remote-Connect-Screen (S-B) — §-QA-Akzeptanz-Checkliste (CYP-482 S-B, Epic CYP-427)

> Owner: UIUX-Designer · **Vorbereitet, ausführbar sobald Dev die §9-Mounts baut** (CYP-486 Composition-Root + Live-Feed +
> die 2 UI-Slots) · Stand 2026-07-12 · Begleit-Spec: `remote-connect-screen-sb-ux-spec.md`.
> Gegen echten Code verifiziert @ develop `1e2682a8` (built-vs-frozen unten). **0 net-new** — reine Reuse-Referenz.
> Zweck: der §-QA-Pass ist **instant**, sobald die Mounts landen — jede Prüfung hat ein konkretes Pass/Fail + testTag/Copy-Anker.

---

## A. Built-vs-Frozen — Tag/Copy-Konsistenz-Verifikation (@ `1e2682a8`)

### A.1 GEBAUT (reuse **as-is**, DE/EN-Parität verifiziert ✓) — der S-B-Screen darf **keine Varianten** erfinden
| Familie | Keys (Anzahl) | Tags | Quelle |
|---|---|---|---|
| Connect-Trust-States | `remote_connect_trust_check/changed/provisional` (3) | `remote.connect.trustCheck` · `trustProvisional` · `error.trustChanged` (`RemoteConnectTags`) | CYP-471/475 |
| DevicePoP | `remote_pop_*` (12: pin_title/body, biometric_title/failed, path_hint, wrong_pin, locked, rejected, enroll_title/pin/session_only, keystore_unavailable) | `OperatorAuthTags` (`remote.authStep.*`) | CYP-460 |
| Revoke | `remote_revoke_*` (6: end_action, confirm_title/body, scope_note, ttl_hint, ended) | `RemoteRevokeTags` (`remote.revoke.end/confirm/scopeNote/ttlHint/ended`, 5) | CYP-479/480 |

### A.2 FROZEN-CONTRACT, der S-B-Bau muss sie **EXAKT anlegen** (CYP-480 §2.1; heute NICHT in `strings.xml`/`RemoteConnectTags`)
Die OOB-Confirm-Screen-Copy/Tags — **§-QA verifiziert: exakt diese Namen + DE/EN, keine Divergenz:**
| Key (DE / EN) | Tag (erweitert `RemoteConnectTags`) |
|---|---|
| `remote_connect_trust_first_title` „Erstverbindung mit %1$s" / „First connection to %1$s" | `remote.connect.trustFirst` |
| `remote_connect_trust_first_body` (TOFU-Body, CYP-480) | — |
| `remote_connect_trust_wordlist_label` „Vergleichs-Wörter" / „Comparison words" | `remote.connect.trustWordlist` |
| `remote_connect_trust_hex_label` „Fingerprint (Hex)" / „Fingerprint (hex)" | `remote.connect.trustHex` |
| `remote_connect_trust_qr_label` „QR scannen" / „Scan QR" | `remote.connect.trustQr` |
| `remote_connect_trust_oob` „Über einen anderen Kanal bestätigen" / „Confirm via another channel" | `remote.connect.trustOob` |
| `remote_connect_trust_oob_console` „Vergleiche mit der Anzeige in der Hub-Konsole." / „Compare with the display in the hub console." | `remote.connect.trustOobConsole` |
| `remote_connect_trust_pinned` „Identität gepinnt" / „Identity pinned" | `remote.connect.trustPinned` |
| `remote_connect_trust_confirm` „Stimmt überein — pinnen" / „Matches — pin it" | `remote.connect.trustConfirm` |
| `remote_connect_trust_reject` „Stimmt nicht — abbrechen" / „Doesn't match — abort" | `remote.connect.trustReject` |
| `remote_connect_trust_aborted` „Nicht verbunden — Identität nicht bestätigt." / „Not connected — identity not confirmed." | `remote.connect.trustAborted` |
| `remote_connect_trust_changed_repin` „Neu pinnen nur nach Out-of-Band-Abgleich des neuen Fingerprints." / „Re-pin only after an out-of-band comparison of the new fingerprint." | `remote.connect.trustChangedRepin` |
| + Container | `remote.connect.trustFingerprint` |

**Verifikation:** 12 Keys + 12 Tags gg. `hub-connection`-frozen (CYP-480) — DE/EN-Parität, snake_case-Keys, camelCase-Tags,
0 Kollision (verifiziert vor CYP-480-Merge). **§-QA-Fail** falls der Bau abweichende Namen/Copy anlegt (Divergenz-Anti-Pattern).

---

## B. Die 6 Wiring-Seam-Akzeptanz-Checks

### Seam 1 — `HubConnectFlow` gemountet + CONNECTED→Workspace-Hand-off
- [ ] `HubConnectFlow` hat einen echten Composition-Root-Caller (nicht mehr 0 Caller / nur Tests).
- [ ] Nach **echtem** `RemoteConnState.CONNECTED` (LIVE) mountet die Operator-Fläche; **non-optimistisch** — Hub flippt
      **erst nach LIVE**, nie vorher. reject/drop davor → zurück zum Connect, ehrlicher Fehler.
- [ ] Bestehende Shell-Loading-Gate deckt „Workspace lädt" (0 net-new Copy).

### Seam 2 — Live `RemoteConnectFeed` (`RemoteHubSessionConnectFeed`) statt Stub
- [ ] Der Connect-Screen wird vom **Live-Feed** getrieben (über `RemoteHubSession`/`TofuHubTrust`), nicht `StubRemoteConnectFeed`.
- [ ] Die Progression rendert echte Zustände (RELAY_DIALING→E2E_HANDSHAKE→TRUST_CHECK→AUTHENTICATING→CONNECTED); `●`+primary
      **nur** bei echtem CONNECTED (nie vorzeitig grün/LIVE).

### Seam 3 — ★ OOB-Confirm-Screen @ TRUST_CHECK (das Kern-Zahn, HA/HB/HC)
- [ ] Rendert `OobConfirmState.Awaiting(hubId, fingerprint)` (nicht mehr bloßer Spinner). Tag-Anker: `remote.connect.trustFirst`.
- [ ] **PFLICHT-blockierend (HA, Docs 17/18):** die Progression **stoppt** bei First-Use; **nur** approve/reject als Ausgänge;
      **kein** Skip/Später/Dismiss-ohne-Entscheidung. Screen schließen ohne Entscheidung = Abbruch (reject-Semantik).
- [ ] **Fingerprint echt-abgeleitet (HB):** PGP-Wortliste aus `HubFingerprintDisplay.words()` — **11 Token, even/odd,
      nummerierte Positionen 1–11**; Hex (`hex()`) + QR (`qrPayload()`, Schema `cyppie-hub-key:`). **Nie Platzhalter** (`null≠Fake`).
- [ ] OOB-Anweisung „gegen die Hub-Konsole vergleichen" (`remote_connect_trust_oob_console`, Tag `trustOobConsole`).
- [ ] **Approve** (`trustConfirm`) → `PendingOobConfirmations.approve()` → TOFU pinnt **persistent** → weiter zu AUTHENTICATING.
- [ ] **Reject** (`trustReject`) → `PendingOobConfirmations.reject()` → `TrustConfirmationRejectedException` **fail-closed** →
      `Rejected` → `remote_connect_trust_aborted` → zur Hub-Liste. **Neutral** (kein Schreck-Rot); nichts gepinnt.
- [ ] `Pinned` (spätere Verbindung): kein Prompt, still verifiziert; neutraler „gepinnt"-Indikator (`trustPinned`), **nie grün**.

### Seam 4 — ★ PoP-Prompt inline @ AUTHENTICATING (HE, Q2=inline)
- [ ] `OperatorAuthDialog` **inline** im Connect-Screen bei AUTHENTICATING (nicht Overlay); Connect **blockt** bis PoP.
- [ ] **Aktive Bestätigung:** Touch-ID/PIN, Pfad ehrlich benannt (`remote_pop_path_hint`, nie Biometrie-Optik ohne Biometrie).
- [ ] **Fehler-Klassen getrennt:** lokal `pinWrong`/`lockedOut` (Zähler, Feld enabled) ≠ hub-terminal `authRejected`
      (`errorContainer`, neu anmelden) — nie vermischt. Biometrie-Fehl → PIN-Fallback sichtbar.
- [ ] ⚠**Seam-gated (NICHT als Befund werten):** die **a∧b∧c-AND-Komposition** (CpJwt ∧ DevicePoP) ist bis **CYP-459**
      nachgelagert — der Prompt ist UI-präsent, die Durchsetzung folgt; **kein** vorgetäuschtes „voll durchgesetzt".

### Seam 5 — Revoke-Mount @ CONNECTED (HF)
- [ ] `RemoteRevokeControl` gemountet im Kontext-Row (nicht mehr render-test-only). Tag `remote.revoke.end`.
- [ ] **Garantiert:** „Fern-Sitzung beenden" → `onEndSession` → `backToHubList()`/`RemoteHubSession.close()` (Sofort-Teardown).
- [ ] **Advisory-Scope:** `remote_revoke_scope_note` „nur diese Verbindung — kein globales Revoke".
- [ ] ⚠**Seam-gated (NICHT als Befund):** `remote_revoke_ttl_hint`/`remote.revoke.ttlHint` **absent**, solange Backend keine
      Op-Session-TTL liefert (`ttl=null`). Kein erfundenes Ablaufdatum.

### Seam 6 — Provisorisch-Disclosure-Retirement an Live-Feed gekoppelt (Q3, Honesty-kritisch)
- [ ] **Solange Stub-Feed:** `remote_connect_trust_provisional` (`remote.connect.trustProvisional`) **bleibt** sichtbar.
- [ ] **Wenn Live-Feed (#2) landet:** der OOB-Confirm-Screen (Seam 3) **ersetzt** den Provisorisch-Spinner; echtes Pinnen
      läuft → Provisorisch-Disclosure **retired**. **§-QA-Fail** falls die Disclosure **vor** dem Live-Feed entfernt wird
      (dann behauptet der Trust-Check echte Verifikation ohne echtes Pinnen) **oder** falls sie **nach** dem Live-Feed
      bleibt (dann Untersagen). `null ≠ Fake`.

---

## C. Querschnitt-Zähne (aus Spec §1 HA–HF / §10)

- [ ] **Farbe nie alleiniger Träger (WCAG 1.4.1):** jeder Zustand Form+Label+a11y. **NIE `tertiary`/#40D6A0-Grün** als Status.
- [ ] **WARN-Amber NUR** TrustChanged (`severityColor(WARN)`, `remote.connect.error.trustChanged`, terminal no-retry);
      **errorContainer** nur harte Terminals (`authRejected`); Reject/Relay-Drop **neutral** (kein Alarm-Rot).
- [ ] **Presence ≠ connected:** Registry-Presence (advisory, Pre-Connect-Picker) ≠ meine-Session (Connect-States) ≠
      Agent-Status — nie konflatiert.
- [ ] **Maritim + M3 · theme-aware:** Dark/Light `maritimeColorScheme`; PGP-Wortliste **non-localized** (kanonisch, App==
      Hub-Konsole — nur das Label `remote_connect_trust_wordlist_label` lokalisiert, **nie die Wörter**; CYP-482-Sicherheits-Req).

## D. Bewusst-deferred (Absenz korrekt, **NICHT** als Befund werten)
- a∧b∧c-Composition (Seam 4) → CYP-459.
- Op-Session-TTL / `ttl_hint` (Seam 5) → Backend-Seam; `null` = absent, korrekt.
- Provisorisch-Disclosure (Seam 6) bleibt, **bis** Live-Feed — Absenz-vor-Live wäre Fehler, Präsenz-solange-Stub ist korrekt.
- Live-Feed/Composition-Root selbst = CYP-486; bis dahin läuft alles auf Stub — kein „operating-remotely"-Verhalten erwartbar.

---

## E. Ausführungs-Hinweis
- **Instant-Pass-Bedingung:** sobald CYP-486 (Composition-Root) + `RemoteHubSessionConnectFeed` + die 2 UI-Slots (OOB-Confirm,
  PoP) + Revoke-Mount landen → diese Liste A–D durchgehen. A.2 zuerst (lands der Bau die 12 CYP-480-Keys/Tags exakt?), dann
  B (Verhalten), dann C (Querschnitt).
- **Shift-left:** A.1/A.2 kann der Tester (CYP-7) schon gegen den Bau-PR prüfen; die Verhaltens-Zähne (B) brauchen den
  gemounteten Screen (behaviorale QA, ggf. Maestro auf ≥1 Target).

---

*Vorbereitet, ausführbar sobald Dev die §9-Mounts baut. Gegen echten Code verifiziert @ `1e2682a8` (built A.1 / frozen A.2).
0 net-new. Begleit-Spec `remote-connect-screen-sb-ux-spec.md`. Seam-gated-Absenzen (§D) sind kein Befund.*
