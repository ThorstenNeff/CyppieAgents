# Remote-Connect-Screen (S-B) UX — „Zu-Hub-verbinden/-wechseln" (CYP-482 S-B, Epic CYP-427)

> Status: **Spec-Closure — Q1–Q3 geruled 2026-07-12 (CYP-482 bleibt In Arbeit)** · docs-only, **kein Bau** · Owner: UX/UI · Stand 2026-07-12
> Der operator-seitige Remote-Connect-Screen: Hub-Auswahl → Verbindungs-Progression → **OOB-Fingerprint-Verifikation**
> (PGP-Wortliste) → **Device-PoP-Prompt** → verbunden (+ Revocation). Maritim+M3, theme-aware.
> Gegen echten Code gegroundet (develop `c49ce08a`, read-only) + ratifizierte RR3/RR5-Architektur (Docs 17/18).
> **Assembly-Spec** — verdrahtet die **schon gebauten** Sicherheits-Seams in die Connect-UI; die **2 unbuilt UI-Slots**
> (OOB-Confirm-Screen + PoP-Prompt) sind das Design. Reuse-schwer (CYP-480/460/429/395), **0 net-new** (Q1 geruled).
> Reine Reuse-Referenz (wie CYP-449). §9-Seam-Map = **1:1 mit Devs unabhängigem CYP-486-Fund** (`HubConnectFlow` 0 Caller /
> kein Composition-Root) — starke Konvergenz.

---

## 0. Grounding-Headline — die Sicherheits-Engine ist gebaut, die Connect-UI-Verdrahtung ist die Lücke

**Gebaut (EXISTS, `c49ce08a`), aber NICHT in die Connect-UI verdrahtet:**
- **`TofuHubTrust`** (`net/hub/trust/TofuHubTrust.kt:24`) = die reale `HubTrust`-Impl (nicht mehr Test-Stub): First-Use →
  OOB-confirm-then-pin · match → `Pinned` · change → `Changed` harter Block (nie still).
- **Persistenter Pin-Store** (`PinnedHubStore.kt:51` + jvm `java.util.prefs`, SSH-known_hosts-Semantik, überlebt Neustart).
- **OOB-Confirmer** (`OobFingerprintConfirmer.kt:22`, `PendingOobConfirmations:50`, `OobConfirmState{Idle,Awaiting(hubId,
  fingerprint),Rejected(hubId)}`): pinnt **nur** nach `approve()`; `reject()` wirft fail-closed (kein stiller Retry).
  ⭐**Die KDoc sagt explizit „The visual form is UIUX's" → der OOB-Confirm-Screen ist MEIN Render von `OobConfirmState`.**
- **88-Bit-PGP-Fingerprint** (`HubFingerprintDisplay.kt:46` even/odd, `PgpWordList.kt` 2×256 EVEN/ODD checksum-pinned,
  11 Token, Hex + QR): **die ratifizierte CYP-482-Entscheidung IST gebaut.**
- **PoP-Dialog** (`OperatorAuthDialog.kt:50`, Content: PIN/Biometrie/Enroll + Fehler-Taxonomie).
- **Revoke-Control** (`RemoteRevokeControl.kt:46`, CYP-479: Sofort-Teardown + Scope-Note + seam-gated TTL).

**Die Connect-UI ist NICHT verdrahtet:** `HubConnectFlow` (`connect/HubConnectFlow.kt:73`) hat **0 Caller** (kein
Composition-Root; = Devs CYP-486-Fund); die Remote-Sequenz läuft auf **`StubRemoteConnectFeed`** (`RemoteConnectFeed.kt:25`,
scripted, kein Noise/TOFU/OOB/PoP); `OobConfirmState`/`OperatorAuthDialog`/`RemoteRevokeControl` haben **0 UI-Referenzen**
(built-not-wired). Bei `TRUST_CHECK` (`HubConnectSelection.kt:208`) steht heute nur ein Spinner + Provisorisch-Zeile; bei
`AUTHENTICATING` (`:219`) ein **bloßer Spinner**. ⟹ **Die 2 unbuilt UI-Slots (OOB-Confirm @ TRUST_CHECK, PoP @
AUTHENTICATING) sind die S-B-Design-Arbeit.**

### 0.1 ✅ Fingerprint-Korrektur (kein Drift)
Ein früherer Zwischenstand (`a69257da`) hatte eine 32-Wort-Single-List-Placeholder-Ableitung (30 Bit). **Der ist seit
`c49ce08a` ersetzt** durch die ratifizierte PGP-even/odd-Version (2×256, 11 Token, **88 Bit ≥ 80**, Emoji gestrichen).
**Kein Drift** — CYP-482 ist korrekt gebaut. (Grounding-Beleg; der HEAD bewegte sich während der Reads.)

---

## 1. Kern-Ehrlichkeit (tragende Entscheidungen)

- **HA — OOB-Fingerprint-Verifikation ist PFLICHT, nicht überspringbar (Docs 17/18, Reviewer-Hard-Req).** Doc 18 §Entsch. 3:
  „der OOB-Schritt **nicht optional** — sonst degradiert Option X auf ‚CP-Erstauslieferung blind vertrauen'." ⟹ Der
  OOB-Confirm-Screen **blockt** die Connect-Progression bei First-Use; die **einzigen** Ausgänge sind **approve** (→ pin +
  weiter) oder **reject** (→ fail-closed Abbruch). **Kein** „später"/„überspringen"/Dismiss-ohne-Entscheidung.
- **HB — Fingerprint nur echt-abgeleitet, nie Platzhalter (HA/CYP-480).** Der Screen rendert `HubFingerprintDisplay` aus dem
  **echten** `dhPubKey`. Solange die **Live-Feed** (statt Stub) nicht läuft, rendert der Screen **keinen** Fake-Fingerprint —
  die **Provisorisch-Disclosure bleibt** (§5).
- **HC — Reject = fail-closed, sichere Wahl (CYP-480 HB).** Fingerprint-Nichtübereinstimmung → `reject()` → nicht pinnen,
  Connect abbrechen, `OobConfirmState.Rejected`, zur Hub-Liste. Neutral (kein Schreck-Rot); Abbruch ist der richtige Zug.
- **HD — `TrustChanged` = terminal, nie still, Re-Pin nur OOB (Docs 17/18 + CYP-480 HC).** Gepinnter ≠ vorgelegter Key →
  harter Block (WARN-Amber, no-retry, gebaut `HubConnectSelection.kt:242-255`), **kein** Ein-Klick-Weiter.
- **HE — PoP = aktive Nutzer-Bestätigung, fail-closed (RR2-B, Docs 17/18).** DevicePoP verlangt Touch-ID/PIN beim
  Verbinden/Wechseln; Auth = **a∧b∧c AND-never-OR** (CpJwt ∧ DevicePoP). ⚠**Die a∧b∧c-Komposition ist NOCH NICHT gewired
  (CYP-459-Gate, Doc 18)** — der Screen präsentiert den Prompt, die Durchsetzung landet mit CYP-459.
- **HF — Revoke = garantiert-lokal + advisory-Scope/TTL (Entsch. 4).** Sofort-Teardown dieser Session (garantiert) + Scope-
  Note „nur diese Verbindung" + TTL-Hinweis **nur** wenn Backend liefert (nie erfunden). `RemoteRevokeControl` encodiert das.

---

## 2. Der S-B-Screen-Flow (Assembly)

Operator-seitige Sequenz (Remote-Modus), Chrome maritim+M3, theme-aware. Rendert `RemoteConnState` (`RemoteConnectingView`,
`HubConnectSelection.kt:201`) + die 2 neuen Slots:

```
Hub-Auswahl (Remote)              ← HubListView + Presence (advisory) + ModeChooser.connectRemote (CYP-395/429, EXISTS)
  → RELAY_DIALING                 ← neutral „Relay wird gewählt …" (EXISTS)
  → E2E_HANDSHAKE                 ← neutral „E2E-Handshake …" (EXISTS)
  → TRUST_CHECK  ┌─ FirstUse  → ★ OOB-CONFIRM-SCREEN (§3, NEU — render OobConfirmState.Awaiting)
                 ├─ Pinned    → still verifiziert (kein Prompt); Provisorisch-Disclosure solange Stub (§5)
                 └─ Changed   → ★ terminaler WARN-Amber-Block (§HD, gebaut, kein Re-Pin MVP)
  → AUTHENTICATING              → ★ PoP-PROMPT (§4, NEU — mount OperatorAuthDialog inline)
  → CONNECTED (LIVE ●)          ← + Revoke-Control-Mount (§6) im Kontext-Row
  → LOST                        ← RemoteFailureView (terminal: TrustChanged/AuthRejected no-retry)
```

**Hub-Wechsel** = derselbe Flow mit vollem Teardown vorab (CYP-449-Shell §6 / CYP-429 Q5, ein aktiver Hub).

---

## 3. ★ OOB-Confirm-Screen (TRUST_CHECK / `OobConfirmState.Awaiting`) — NEU, das Kern-Design

**Rendert `OobConfirmState.Awaiting(hubId, fingerprint)`** (die Logik/State ist gebaut, CYP-478; der Visual ist meiner).
Reuse `HubCard`-Scaffold. **Blockt die Progression (HA, Pflicht).**

**Aufbau (oben→unten) — reuse CYP-480 §2.1-Copy (frozen `remote_connect_trust_*`):**
1. **Titel** `remote_connect_trust_first_title` „Erstverbindung mit %1$s".
2. **Body** `remote_connect_trust_first_body` — TOFU-ehrlich. **(Q1 geruled: trägt die Pflicht — kein net-new „verify_required".)**
3. **Fingerprint-Block** (`trustFingerprint`, aus `HubFingerprintDisplay`, **echt-abgeleitet** HB):
   - **Primär**: PGP-Wortliste (`HubFingerprintDisplay.words()` — 11 Token, even/odd, `trustWordlist`,
     `remote_connect_trust_wordlist_label`) — **nummerierte Positionen 1–11** (Positions-Vergleich; even/odd fängt
     Transposition). **Vorlesbar** für den OOB-Abgleich.
   - **Sekundär**: Hex (`HubFingerprintDisplay.hex()`, `trustHex`, kopierbar) + QR (`HubFingerprintDisplay.qrPayload()`,
     `trustQr`, Schema `cyppie-hub-key:`).
4. **OOB-Anweisung**: `remote_connect_trust_oob` + `remote_connect_trust_oob_console` „Vergleiche mit der Anzeige in der
   Hub-Konsole." (Option X, Docs 17/18).
5. **Provisorisch-Disclosure** `remote_connect_trust_provisional` — **solange Live-Feed nicht läuft** (§5).
6. **Zwei Aktionen (Pflicht-Ausgänge, HA/HC):**
   - **Bestätigen** `remote_connect_trust_confirm` „Stimmt überein — pinnen" · `trustConfirm` → `PendingOobConfirmations.approve()`
     → TOFU pinnt (persistent) → weiter zu AUTHENTICATING.
   - **Ablehnen** `remote_connect_trust_reject` „Stimmt nicht — abbrechen" · `trustReject` → `PendingOobConfirmations.reject()`
     → `TrustConfirmationRejectedException` (fail-closed) → `OobConfirmState.Rejected` → `remote_connect_trust_aborted`
     „Nicht verbunden — Identität nicht bestätigt." → zur Hub-Liste.
   - **Kein dritter Ausgang** (kein Skip/Später/Dismiss) — HA. Schließen des Screens ohne Entscheidung = Abbruch (=reject-Semantik).

**`Rejected`-State**: neutral, ehrlicher Nicht-verbunden-Zustand; Retry = erneuter Connect-Versuch (frischer OOB-Confirm).

---

## 4. ★ PoP-Prompt (AUTHENTICATING) — NEU, mount `OperatorAuthDialog` **inline** (Q2 geruled)

Bei `RemoteConnState.AUTHENTICATING` (heute bloßer Spinner) → **mount `OperatorAuthDialog` als inline-Slot** (Q2: inline,
kohärent mit der Progression — **kein** Overlay-Dialog):
- **Aktive Bestätigung (HE, Docs 17/18):** Touch-ID/PIN (Pfad-ehrlich, CYP-460 §5.1); der Connect **blockt** bis PoP.
- **Fehler-Taxonomie** (CYP-460): lokal `pinWrong`/`lockedOut` (Zähler, Feld enabled) ≠ hub-terminal `authRejected`
  (`errorContainer`, neu anmelden) — nie vermischt. Biometrie-Fehl → PIN-Fallback.
- **⚠ Seam (nicht Q):** die **a∧b∧c-AND-Komposition** (CpJwt ∧ DevicePoP) ist **nicht gewired** (CYP-459-Gate, Doc 18:88) —
  der Screen präsentiert den Prompt, die Durchsetzung („nie OR") landet mit CYP-459. Bis dahin ist der PoP-Prompt UI-präsent,
  aber die Gate-Semantik nachgelagert. Ehrlich: **kein** vorgetäuschtes „vollständig durchgesetzt".
- **Copy:** reuse `remote_pop_*` (CYP-460, frozen). Kein net-new.

---

## 5. Provisorisch-Disclosure — Retirement-Plan (Honesty-kritisch, Q3 geruled)

Die Engine (TOFU + persistenter Pin + OOB + 88-Bit-Fingerprint) **existiert jetzt** — aber der Connect-Screen läuft noch auf
`StubRemoteConnectFeed` (ruft nie `TofuHubTrust`, `HubConnectSelection.kt:213` zeigt `remote_connect_trust_provisional`).
**Ehrlichkeits-Regel (Q3 bestätigt: an den Live-Feed gekoppelt):**
- **Solange Stub-Feed:** Provisorisch-Disclosure **bleibt** (der Trust-Check macht kein echtes Pinnen).
- **Wenn Live-Feed landet** (Devs `RemoteHubSessionConnectFeed` über `RemoteHubSession`/`TofuHubTrust`): der
  **OOB-Confirm-Screen (§3) ERSETZT** den Provisorisch-Spinner am TRUST_CHECK — echtes Pinnen läuft, echter Fingerprint wird
  gerendert → **Provisorisch-Disclosure RETIRED** (nicht mehr nötig, wäre dann Untersagen). Der Übergang ist **an den
  Live-Feed gekoppelt**, nicht früher. `null ≠ Fake` bleibt: kein echter Fingerprint ohne echte Bytes.

---

## 6. Revoke-Mount (CONNECTED / Kontext-Row) — mount `RemoteRevokeControl`

Bei `CONNECTED` → **mount `RemoteRevokeControl`** (gebaut, CYP-479, render-tested, nicht live) im Kontext-Row (CYP-449 §4).
- **Garantiert:** „Fern-Sitzung beenden" → `onEndSession` → `HubConnectViewModel.backToHubList()`/`RemoteHubSession.close()`
  (Sofort-Teardown, `remote_revoke_confirm_body`).
- **Advisory:** Scope-Note „nur diese Verbindung" (`remote_revoke_scope_note`, kein globales Revoke); TTL-Hinweis
  (`remote_revoke_ttl_hint`) **nur** wenn Backend eine Op-Session-TTL liefert (Entsch. 4, `ttl` default null → **absent**).
- **Copy/Tags:** reuse `remote_revoke_*`/`RemoteRevokeTags` (CYP-480, frozen). Kein net-new.

---

## 7. Reuse-Map + Net-New = 0 (Q1 geruled)

**Alles Reuse — keine neuen Keys/Tags:**
- OOB-Confirm-Screen: `remote_connect_trust_*` (CYP-480 §2.1, frozen) + `HubFingerprintDisplay`/`PgpWordList` (CYP-482, gebaut)
  + `OobConfirmState`/`PendingOobConfirmations` (CYP-478, gebaut).
- PoP: `remote_pop_*`/`OperatorAuthDialog`/`OperatorAuthTags` (CYP-460, gebaut).
- Connect-States: `remote_connect_*`/`RemoteConnectTags` (CYP-471/475, gebaut).
- Revoke: `remote_revoke_*`/`RemoteRevokeControl`/`RemoteRevokeTags` (CYP-479/480, gebaut).
- Hub-Auswahl/Presence: `hubconnect_*`/`HubConnectSelection` (CYP-395/419, gebaut).

**Verworfener Kandidat (Q1):** `remote_connect_trust_verify_required` — **nicht** angelegt; die blockierende Struktur +
`remote_connect_trust_first_body` tragen die Pflicht. Falls es im Bau unklar liest → 1 Zeile später nachziehen (PO), nicht jetzt.

---

## 8. Entscheidungen (PO 2026-07-12) — geruled

- **Q1 — Pflicht-Verifikations-Copy = 0 net-new** (blockierende Struktur + `first_body` trägt die Pflicht; ggf. 1 Zeile später).
- **Q2 — PoP-Prompt = inline-Slot** @ AUTHENTICATING (kohärent mit der Progression; kein Overlay).
- **Q3 — Provisorisch-Retirement = an den Live-Feed gekoppelt** (bleibt solange Stub; retired erst wenn `RemoteHubSessionConnectFeed`
  den echten Fingerprint rendert). `null ≠ Fake`.
- **Ticket:** CYP-482 S-B-Design-Arbeit (CYP-482 bleibt In Arbeit); Freeze docs-only, reine Reuse-Referenz.

---

## 9. Seam-Map (Wiring — an Dev über PO, ride mit RR5/CYP-459+; = Devs CYP-486-Fund)

Die Engine ist gebaut; die **Verdrahtung** ist die S-B-Bau-Arbeit:
1. **Mount `HubConnectFlow`** in `App`/AuthGate (heute 0 Caller / kein Composition-Root) + CONNECTED→Workspace-Hand-off (CYP-449).
2. **Live `RemoteConnectFeed`** (`RemoteHubSessionConnectFeed` über `RemoteHubSession`/`TofuHubTrust`) statt `StubRemoteConnectFeed`.
3. **Wire `OobConfirmState`/`PendingOobConfirmations.state`** in `RemoteConnectingView` @ TRUST_CHECK → §3-Render.
4. **Mount `OperatorAuthDialog` inline** @ AUTHENTICATING → §4; + a∧b∧c-Komposition (CYP-459-Gate).
5. **Mount `RemoteRevokeControl`** @ CONNECTED → §6.
6. **Retire Provisorisch-Disclosure** gekoppelt an #2 (§5).

---

## 10. Acceptance-Teeth (für spätere §-QA, wenn die Seams landen)

1. **OOB Pflicht-blockierend (HA):** First-Use blockt bei TRUST_CHECK; nur approve/reject; kein Skip/Später/Dismiss-ohne-Entscheidung.
2. **Fingerprint echt (HB):** PGP-Wortliste 11-Token even/odd aus `HubFingerprintDisplay` (echter `dhPubKey`); nie Platzhalter.
3. **Reject fail-closed (HC):** `reject()` → nicht pinnen, Abbruch, `Rejected`, neutral (kein Schreck-Rot).
4. **TrustChanged terminal (HD):** WARN-Amber, no-retry, kein Ein-Klick-Weiter (gebaut).
5. **PoP aktiv + inline (HE/Q2):** Touch-ID/PIN inline @ AUTHENTICATING; lokal ≠ hub-terminal; a∧b∧c-Gate ehrlich nachgelagert.
6. **Provisorisch honest (Q3):** bleibt solange Stub; retired NUR mit Live-Feed; `null ≠ Fake`.
7. **Revoke (HF):** garantierter Teardown + Scope-Note + TTL nur wenn Backend liefert (absent sonst).
8. **Reuse (0 net-new):** OOB=CYP-480, PoP=CYP-460, Revoke=CYP-480, Connect=CYP-471/475 — keine divergenten Teile.

---

*Spec-Closure (Q1–Q3 geruled 2026-07-12; CYP-482 bleibt In Arbeit). Assembly/Wiring-Screen-Spec der gebauten
Sicherheits-Seams. Gegen echten Code (`c49ce08a`) + Docs 17/18 gegroundet, read-only. Nichts gebaut. Fingerprint-Drift war
stale (PGP-even/odd 88-Bit ist gebaut, §0.1). 0 net-new (reine Reuse-Referenz). Die 2 unbuilt UI-Slots (OOB-Confirm §3, PoP
§4) + die 6 Mounts (§9) sind die S-B-Arbeit (= Devs CYP-486-Fund). Alle Seams über den PO.*
