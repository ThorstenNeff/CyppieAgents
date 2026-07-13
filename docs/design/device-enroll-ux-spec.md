# CYP-525 — Device-Enroll-Flow UX (Epic CYP-427 Phase-2 Remote)

> Owner: UIUX-Designer · **RATIFIZIERT (a)+2-iii** (PO `1526244088…`) — Dev+Backend bauen · Stand 2026-07-13 ·
> Companions (frozen Dev-AC): `device-enroll-keys.md` + `device-enroll-tags.md` (Guard-ACs GE1–GE7).
> Dieser Doc = die **Design-Rationale/Begleit-Spec** hinter den Companions.
> **UX-QA am gebauten Stand + PO-Rulings 2026-07-13 (PO `1526254113…`) → §0.1.**
> Gegroundet READ-ONLY gg. develop (Explore, 2026-07-13). Der Enroll-Flow ist der **Dogfood-CONNECTED-Blocker**:
> heute gibt es **keine Self-Enroll-Route**, also erreicht keine neue Operator-Maschine je `CONNECTED`.

---

## 0. Headline — Enroll-*Bausteine* existieren, aber 3 Nähte fehlen

Der Explore zeigt: der **Enroll-Schritt selbst ist schon modelliert**, nur nicht verdrahtet:
- `OperatorAuthStep.Enroll(sessionOnly: Boolean)` (im `OperatorAuthDialog`, CYP-460) rendert bereits
  `remote_pop_enroll_title` „Dieses Gerät einrichten" + `remote_pop_enroll_pin` „App-PIN festlegen" +
  (falls `sessionOnly`) `remote_pop_enroll_session_only` „Gilt nur für diese Sitzung — sicherer Geräte-Schlüsselbund folgt."
- **Beide Schlüssel-Pfade existieren:** `DevicePoP.Raw` (Software-Ed25519 + App-PIN, cross-platform inkl. Linux) ∪
  `DevicePoP.Fido2` (Plattform-Authenticator Touch-ID/Hello — **jvm-only, heute STUB** `AuthenticatorUnavailable`).
- **Backup-Codes-Maschine** (`BackupCodeStore.generate(10)`: Crockford-Base32, ≥80 Bit, einmal sichtbar, gesalzen
  SHA-256, single-use) + die **UI** `RecoveryCodesReveal` (einmal-Reveal + Ack-Gate) + `RecoveryInputContent`
  (Code-Eingabe) — **gebaut, aber ohne Caller**.
- Server-Domäne: `EnrolledOperatorDevice` (nur Public-Key, nie privat), `OperatorDeviceEnrollment.enrollFirstDevice`
  (admittiert **nur wenn noch kein Gerät enrolled**; 2. Gerät → `Rejected("already_enrolled_recovery_is_q6_seam")`),
  `OperatorDeviceRecoveryFlow.enrollWithBackupCode` (neues Gerät via Backup-Code).

## 0.1 UX-QA am gebauten Stand + PO-Rulings (2026-07-13)

UX-QA-Pass gg. die **shipped** Enroll-Fläche (CYP-460/480/471 @ develop `4572a278`; der Dev-Build
`feature/CYP-525-operator-device-enroll @ c788e92c` war noch backend-only). Verdikt: Copy im Kern vollständig +
DE/EN-paritätisch. 6 Befunde, alle vom PO ge-ruled:

- **① [HOCH · BAU]** Der Reveal (`RecoveryCodesReveal.kt` L49-53) sagt „einmalig", aber die load-bearing Wahrheit
  (kein Zentral-Login = diese Codes der **einzige Weg zurück**) steht nur auf der *Verlust*-Fläche
  (`RecoveryInputContent` `NO_CENTRAL`), **nicht** auf dem Reveal, wo „Ich habe sie gespeichert" quittiert wird →
  Blind-Quittung. **Fix (ge-ruled BAU):** net-new Key `remote_recovery_codes_no_central` auf dem Reveal, **VOR** dem
  Ack (Variante von `remote_recovery_no_central`, Konsequenz-Rahmen). → **GE7**, Tag `remote.recovery.codesNoCentral`.
- **④ [MITTEL · BAU]** session-only-Disclosure ist gebaut neutral-grau (`OperatorAuthDialog.kt` L96-104,
  `onSurfaceVariant`) = Understatement einer Security-Downgrade-Wahrheit. **Fix (ge-ruled BAU):** **WARN-amber**
  (`severityColor(Severity.WARN)` + `▲` separater Node, WCAG 1.4.1), doktrin-konsistent mit `workspace.remoteContext`.
  Da (a)+Persist der ratifizierte Default ist, ist Session-only die **Ausnahme** → amber-flaggen ist richtig, nicht
  laut. Ton-Swap am bestehenden Node — kein neuer Key/Tag. → **GE6**.
- **③ [MITTEL · ENTSCHEIDUNG]** `cpSessionExpired` ist **bewusst KEINE** distinkte gerenderte Connect-Ursache: der
  CpJwt = zentrale Operator-Session, ihr Ablauf = zentrales **Re-Login über AuthGate** (CYP-176), getrennt von der
  Remote-Connect-Failure-Taxonomie. → **GE4 korrigiert auf 2-Wege: `DeviceNotEnrolled ≠ AuthRejected`** + separater
  AuthGate-Pfad-Vermerk. Keine fehlende Ursache. Die „kollabiert-in-authRejected-Copy"-Kante = **LOW Post-Dogfood**
  (Attributions-Unschärfe; Remedy Re-Login bleibt ~richtig), non-blocking geloggt. ⟹ „CYP-517 final am
  Connect-Surface" ist mit dem 2-Wege-Wortlaut **wahr**.
- **② [Info → Dev-AC, PO relayt]** Kein Copy-Defekt (Copy vollständig). GE2-**Enforcement** ist ungebaut: der Ack ist
  ein Tap-through-`Button` (`RecoveryCodesReveal.kt` L72-75, kein `enabled`-Gate), `RecoveryCodesReveal` hat 0
  Prod-Caller. Dev-Verhaltens-AC (Inc 3): Reveal als **einzige Vorwärts-Tür** mounten + CONNECTED an den Ack via
  `enabled`-Gate koppeln (nicht Tap-through) + Testers Axis-4.
- **⑤ [NIEDRIG · POSITIV]** „Enroll = Schritt, nicht Fehler" ist am Dialog-Layer schon ehrlich: `NeedsEnroll`
  nicht-terminal (`OperatorAuthTaxonomy.kt` L28-29/L41), distinkter Tag `error("needsEnroll")`, aktionabler Titel.
- **⑥ [NIEDRIG · Copy]** `remote_connect_auth_rejected` ≡ `remote_pop_rejected` wortidentisch — moot durch ③ (keine
  neue cpSessionExpired-Ursache, also nichts zu reusen); Merker: **keinen** der beiden für eine spätere distinkte
  cp-Copy reusen.

**Dev-Verdrahtung in Inc 3:** ① Text auf dem Reveal, ④ Farbe am session-only-Node. Docs-only-Delta faltet in den
CYP-525-Merge (revert-guard).

---

**Die 3 fehlenden Nähte (= CYP-525-Scope, mein Spec zeichnet die UX):**
1. **Keine Enroll-Route/-Wire** — Enroll + Recovery + Backup-Code-Gen sind unverdrahtete Domänen-Klassen; nur der
   **Verify**-Pfad ist live (`OperatorAssertionVerifier` @ `Rr3TunnelGate`).
2. **`RemoteFailure` hat KEINE `DeviceNotEnrolled`-Ursache** → „nicht enrolled" **kollabiert in `AuthRejected`**
   (`ClientOperatorAuth` macht not-enrolled/UV-denied/no-authenticator alle zu `false` → `RemoteFailure.AuthRejected`).
   **Das ist der Silent-/Misleading-Fail** (Q3-Kern): ein nicht-enrolltes Gerät sähe aus wie „Vom Hub abgelehnt.
   Bitte neu anmelden." — falsch (kein Reject; Neu-Login hilft nicht).
3. **Backup-Codes-Reveal ist NICHT an den Enroll-Schritt gekoppelt** — kein First-Enroll→Codes-Hand-off. Die zwei
   Flächen existieren unabhängig.

Zusätzlich: **DEVICE_SECURE (dauerhafter Geräte-Schlüsselbund) = named-not-built** → Enroll ist heute ehrlich
„nur diese Sitzung". Fido2/Passkey-Pfad = Stub. Nur der **Raw-Software-Pfad** funktioniert heute (jvm/Linux =
Dogfood-Basis).

---

## 1. First-Use-Enroll-UX — der AUTHENTICATING-Zweig (konsistent zu S-B OOB/PoP)

**Leitidee — Enroll ist die zweite Hälfte derselben TOFU-Münze:**
- **OOB-Confirm (S-B, TRUST_CHECK):** „*Ich* vertraue diesem **Hub**" (Gerät→Hub, out-of-band).
- **Enroll (AUTHENTICATING):** „dieser **Hub/CP** vertraut diesem **Gerät**" (Hub→Gerät).
Beide sind **First-Use-only** und degradieren bei Folgeverbindungen zu einem schnellen Check (gepinnter Hub /
enrolltes Gerät). Diese Symmetrie macht den Flow legibel und begründet, warum Enroll **inline** im Connect-Flow
sitzt (wie OOB + PoP), nicht als separate Route.

**Sequenz — erste Verbindung eines NEUEN Geräts** (reuse die S-B-`RemoteConnectingView`-Chrome):
```
RELAY_DIALING → E2E_HANDSHAKE → TRUST_CHECK (OOB-Confirm: Hub bestätigen)
   → AUTHENTICATING:
        isEnrolled()==false  →  ┌─ ENROLL-Schritt (statt Pin/Biometric-PoP) ─────────────┐
                                 │ ① „Dieses Gerät einrichten" (remote_pop_enroll_title)  │
                                 │ ② Schlüssel anlegen: App-PIN festlegen (Raw) ODER      │
                                 │    OS-Passkey-Prompt (Fido2) — je nach Gerät (Q2)      │
                                 │ ③ ★ Backup-Codes EINMAL zeigen + Ack-Gate               │
                                 │    (RecoveryCodesReveal — bisher unverdrahtet)          │
                                 │ ④ PoP über den frischen Schlüssel                       │
                                 └────────────────────────────────────────────────────────┘
   → CONNECTED
```
**Folgeverbindung (enrolltes Gerät):** TRUST_CHECK entfällt (Hub gepinnt) → AUTHENTICATING = schneller
`Pin`/`Biometric`-PoP → CONNECTED. Kein Enroll, keine Codes.

**★ Der load-bearing Join (③): First-Enroll MUSS die Backup-Codes einmal hand-offen, Ack-gated.**
Der Geräte-Schlüssel lebt **nur auf diesem Gerät** (die CP hält ihn nie) und es gibt **bewusst kein Zentral-Login-
Recovery** (HE, CYP-480). ⟹ Die **einzige** Wiederherstellung bei Geräteverlust sind die Backup-Codes. Darum darf
der Flow **nicht** `CONNECTED` erreichen, bevor der Operator quittiert hat, dass er die Codes gespeichert hat
(`remote.recovery.codesAck`). Sonst = ein Gerät ohne Rückweg (Lockout-Falle). Reuse `RecoveryCodesReveal` 1:1,
gemountet als Enroll-Schritt ③.

**Zwei Enroll-Türen (Server-getrieben, beide UI existiert):**
- **Erstes Gerät überhaupt** (`enrollFirstDevice` admittiert) → Schlüssel anlegen → **Codes zeigen** (③).
- **Zusätzliches/Ersatz-Gerät** (Server `already_enrolled` → `OperatorDeviceRecoveryFlow.enrollWithBackupCode`) →
  **Code eingeben** (`RecoveryInputContent`, `remote_recovery_start_*`) → Schlüssel anlegen. (Das ist zugleich der
  Multi-Device- und der Lost-Device-Pfad, Q6/②a — Seam-gated bis Server-Multi-Device.)

**Operator-only:** Enroll bindet **diesen** Geräteschlüssel an die **Operator-Identität** (CpJwt a+b etabliert die
Identität, Enroll die Geräte-Bindung c). Ein MEMBER erreicht das nie.

---

## 2. (a) Software-Key vs (b) WebAuthn/Passkey — UX-Tradeoff

| | (a) Software-Key + App-PIN (`DevicePoP.Raw`) | (b) WebAuthn/Passkey (`DevicePoP.Fido2`) |
|---|---|---|
| Enroll-Geste | eigener Flow: **App-PIN festlegen** (`remote_pop_enroll_pin`) | **OS-nativer Prompt** (Touch-ID/Hello) — „Passkey für diesen Hub anlegen" |
| Folge-PoP | App-PIN eingeben (`Pin`-Schritt) | Biometrie-Prompt (`Biometric`-Schritt) |
| Natürlichkeit | Nutzer erfindet/merkt eine PIN | vertraute OS-Geste, nichts Neues zu merken |
| Schlüssel-Custody | Software-Ed25519 im App-Keystore; **App-PIN keyloggable** (bewusster No-Hardware-Tradeoff) | Hardware-backed (Secure Enclave/TPM) = **echtes DEVICE_SECURE** |
| „nur diese Sitzung"? | **Ja** heute (`session_only`-Disclosure), bis dauerhafter Keychain (DEVICE_SECURE) | **Nein** — hardware-persistent, keine session-only-Disclosure |
| Plattform | cross-platform **inkl. Linux** (Dogfood-Basis) | macOS/Windows; **NICHT Linux/CI** — Fido2-Store = heute Stub |

**Empfehlung: progressiv, gerät-getrieben — kein Hart-Entweder-Oder.** Bevorzuge den **OS-Passkey wo die Plattform
ihn liefert** (natürlichste Geste, echtes DEVICE_SECURE), **falle auf Software-Key + App-PIN zurück** wo nicht
(Linux). Das **ist schon die Code-Form** (`OperatorDeviceKeyStore`-OS-Selektion: Fido2 wo verfügbar, sonst Raw). Die
Enroll-Copy **adaptiert**: Passkey → OS-Prompt; Software → „App-PIN festlegen".

**⚠ Honesty-Kopplung an die Disclosure:** die `remote_pop_enroll_session_only`-Zeile erscheint **nur** auf dem
**Raw-Pfad** (`sessionOnly==true`) — ein echter Passkey ist hardware-persistent, darf **nicht** „nur diese Sitzung"
sagen. Der bestehende Code gated das schon (`if sessionOnly`). Für den **Dogfood heute** (Linux, Fido2=Stub)
enrollt der Operator über **Software-Key + App-PIN**, mit der ehrlichen „nur-diese-Sitzung"-Disclosure.

---

## 3. Ehrlichkeit — „nicht enrolled → kein CONNECTED" nie still/irreführend

**Das Kern-Problem (Gap #2): heute kollabiert not-enrolled in `AuthRejected`** → der Nutzer sähe „Vom Hub
abgelehnt. Bitte neu anmelden." Das ist ein **Misleading-Fail**: es ist kein Reject, das Gerät braucht nur Enroll;
Neu-Login hilft nicht. Das verletzt das Never-Silent-/Distinct-Truth-Prinzip.

**Fix — Enroll ist ein SCHRITT, kein Fehler.** Bei AUTHENTICATING prüft der Client `isEnrolled()` **zuerst**;
`false` → **Enroll-Schritt** (positive, aktionable Fläche „Dieses Gerät einrichten"), **nie** der Weg zu
`false`→`AuthRejected`. Der Baustein existiert: `OperatorAuthError.NeedsEnroll` ist schon als **lokaler,
NICHT-terminaler** Fehler klassifiziert („not an auth failure", Tag `remote.authStep.error.needsEnroll`, Copy
`remote_pop_enroll_title`). CYP-525 muss nur die **Brücke** bauen: not-enrolled → Enroll-Schritt, **vor** dem
terminalen `AuthRejected`.

**Distinct-Cause-Brücke (mein CYP-517-typed-cause-Muster):** falls Enroll **nicht** in-flow abschließt (Enroll-Route
unerreichbar; oder `already_enrolled`-Ersatzgerät braucht Recovery statt First-Enroll), ist das ein **distinkter**
Zustand, der **nie** in `AuthRejected` kollabieren darf. CYP-525 ergänzt eine distinkte
`RemoteFailure.DeviceNotEnrolled` (bzw. `EnrollRequired`) + Copy → „Dieses Gerät einrichten / mit Code
wiederherstellen", **nie** „vom Hub abgelehnt". 3 getrennte Wahrheiten, nie konflatiert:
`DeviceNotEnrolled`(→Enroll, aktionabel) ≠ `AuthRejected`(Hub sagt nein, terminal, Neu-Login) ≠
`cpSessionExpired`(CP-Auth, CYP-517, Re-Auth).

**Anknüpfung an das `workspace.remoteContext`-WARN-Muster (der PO-Punkt):** dasselbe Disclosure-Doktrin —
- **„nur diese Sitzung — dauerhafter Keychain folgt"** (`remote_pop_enroll_session_only`) ist **exakt** das
  „provisorisch/noch-nicht-voll"-WARN-Muster wie „verbunden, aber Daten noch nicht über den Tunnel": guaranteed
  (Schlüssel funktioniert jetzt) vs advisory (nicht dauerhaft), **WARN-amber, nie error-rot** (nichts kaputt), nie still.
- **not-enrolled** wird **nie stumm** ver-nicht-connected: der Operator sieht **immer** die aktionable Enroll-Fläche
  (warum nicht verbunden = Gerät braucht Setup; wie = jetzt einrichten), nie einen mysteriösen Non-CONNECTED oder ein
  falsches „abgelehnt". `NeedsEnroll` = WARN/aktionabel, **nicht** errorContainer-rot.

---

## 4. Reuse-Map (existiert) vs CYP-525-Nähte (zu bauen)

| Baustein | Status | Rolle |
|---|---|---|
| `OperatorAuthStep.Enroll(sessionOnly)` + `remote_pop_enroll_{title,pin,session_only}` | **gebaut** | der Enroll-Schritt-Kern (①②) |
| `remote.authStep.{enroll, enrollPinSet}`-Tags | **gebaut** | Enroll-Mount-Anker |
| `DevicePoP.Raw` + `KeystoreOperatorDeviceKeyStore` (Ed25519, `generateDeviceKey`) | **gebaut** (jvm/Linux) | Software-Schlüssel-Pfad (Dogfood) |
| `DevicePoP.Fido2` + `Fido2OperatorDeviceKeyStore` | **Stub** (jvm) | Passkey-Pfad (mac/Win später) |
| `RecoveryCodesReveal` + `remote.recovery.codes*` + Ack-Gate | **gebaut, ohne Caller** | ③ Codes-einmal-Reveal beim First-Enroll |
| `RecoveryInputContent` + `remote_recovery_start_*` | **gebaut, ohne Caller** | Zusatz-/Ersatz-Gerät via Code |
| `BackupCodeStore` / `EnrolledOperatorDevice` / `enrollFirstDevice` / `enrollWithBackupCode` | **Domäne gebaut, unverdrahtet** | Server-Enroll-Modell (Backend baut Kontrakt parallel) |
| **Enroll-Route/Wire** | **fehlt** | CYP-525: Client-Enroll-Call + Server-Route |
| **`RemoteFailure.DeviceNotEnrolled` + not-enrolled→Enroll-Brücke** | **fehlt** | CYP-525: die Honesty-Naht (Q3) |
| **First-Enroll→Codes-Join** (③ Mount) | **fehlt** | CYP-525: `RecoveryCodesReveal` am Enroll-Schritt |
| **DEVICE_SECURE / dauerhafter Keychain** | **named-not-built** | session-only-Disclosure bleibt bis dahin ehrlich |

**Net-new Copy/Tags voraussichtlich gering:** `remote_pop_enroll_*` + `remote_recovery_*` existieren; net-new
wären v. a. die **`DeviceNotEnrolled`-Connect-Fehler-Copy/-Tag** (`remote.connect.error.deviceNotEnrolled`) und ggf.
eine Enroll-Kontext-Zeile. Wortlaut liefere ich bei Ratifikation.

---

## 5. Honesty-Anker (HA–HF)
- **HA — Codes-Ack-Gate ist Pflicht:** kein `CONNECTED` beim First-Enroll ohne quittiertes Backup-Code-Speichern
  (einzige Recovery, kein Zentral-Login → sonst Lockout-Falle).
- **HB — not-enrolled ≠ rejected:** Enroll ist ein aktionabler Schritt, nie ein `AuthRejected`-Fail; distinkte
  `DeviceNotEnrolled`-Ursache, nie konflatiert (3 getrennte Wahrheiten).
- **HC — session-only ehrlich:** die „nur-diese-Sitzung"-Disclosure erscheint **nur** auf dem Raw-Pfad (keine
  DEVICE_SECURE-Überzeichnung); Passkey (hardware) sagt sie **nicht**. WARN-amber, nie error-rot.
- **HD — Schlüssel-Custody ehrlich:** der private Geräteschlüssel verlässt das Gerät nie (CP/Hub hält nur den
  Public-Key-Anker) — die Copy verspricht keine Server-seitige Wiederherstellung.
- **HE — kein Zentral-Login-Recovery** (reuse CYP-480): Verlust → Backup-Code am Hub (OOB), nie „einfach neu einloggen".
- **HF — never-silent:** jeder Nicht-CONNECTED-Grund ist sichtbar + aktionabel (Enroll / Code / Reject-mit-eigener-Copy),
  nie ein Hang oder ein falsches Etikett — dasselbe Doktrin wie `workspace.remoteContext`.

---

## 6. Offene Auftraggeber-Ratifikations-Fragen
1. **Enroll inline im Connect-Flow (AUTHENTICATING-Zweig, Empfehlung) vs. separater „Gerät einrichten"-Screen
   vor Connect?** (Empf. inline — konsistent zu S-B OOB/PoP.)
2. **Backup-Codes-Ack als HARTES Gate vor CONNECTED bestätigen** (HA) — oder darf man mit Warnung überspringen?
   (Empf. hartes Gate — kein Rückweg sonst.)
3. **Passkey-First-Progressive** bestätigen (Passkey wo verfügbar, sonst Software-PIN) — und für Dogfood **Software-
   PIN + session-only-Disclosure** akzeptieren (Linux, Fido2=Stub)?
4. **`DeviceNotEnrolled` als distinkte `RemoteFailure`-Ursache** (Q3-Brücke) — Backend-Kontrakt muss die Ursache
   typisiert durchreichen (wie CYP-517 typed-cause), nicht als generisches `AuthRejected`.
5. **Zusatz-/Ersatz-Gerät-Enroll (via Backup-Code)** — im MVP schon zeigen (RecoveryInputContent existiert) oder
   Seam-gated bis Server-Multi-Device (CYP-459/469, ②a)?
6. **DEVICE_SECURE-Zeitpunkt** — wann wird die „session-only"-Disclosure abgelegt (dauerhafter OS-Keychain)?
7. **Recovery-Codes-Format/-Anzahl** = Reviewer-Threat-Model (10× Crockford-Base32 ≥80 Bit existiert) — bestätigen.

---

## 7. Scope-Klarstellung
**Kein Bau.** Backend baut parallel Enroll-Modell/Kontrakt. Dieser Pass = die **UX-Skizze** für das
Ratifikations-Paket (surface den existierenden Kontrakt + zeichne die 3 fehlenden Nähte ehrlich). Nach Auftraggeber-
GO friere ich Companion-Keys/Tags/ux-spec ein (die meisten Keys/Tags existieren schon → Delta klein). Enroll ist der
**CONNECTED-Blocker** → damit fällt die letzte Dogfood-Wand (nach Transport-Beweis).
