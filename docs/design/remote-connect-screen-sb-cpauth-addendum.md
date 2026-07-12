# S-B-Connect-Screen — Nachtrag: CP-Auth / CpJwt-Mint (CYP-482 S-B, Epic CYP-427)

> Owner: UIUX-Designer · **Spec-Closure — Freeze-GO 2026-07-12** · docs-only · Stand 2026-07-12 · Nachtrag zu
> `remote-connect-screen-sb-ux-spec.md`. Erdet Backends CYP-501-②-Kontrakt (`CpJwtProvider`/`HubTicketMinter`) UX-seitig.
> Gegen echten Code (develop `eb84fe1b`) + Docs 17/18 + CYP-501-§3 (worktree) gegroundet.
> **DS/CYP-7-Sync:** die 3 neuen error-Ursachen sind geteilte testTag-API → der PO koordiniert die Werte
> (`remote.connect.error.{cpSessionExpired,cpUnreachable,notAuthorizedForHub}`) mit Tester/DS.

---

## 0. Verdikt: der CP-Auth-Schritt ist **abgedeckt — transparent by contract** (kein sichtbarer Step-up-Prompt)

**Der CpJwt (a+b) mintet aus der bestehenden zentralen Kratos/App-Login-Session** — **kein** frischer CP-Login/Device-Code
mitten im Connect-Flow. Belege:
- CYP-501 §3 (worktree): der Mint „bear[s] an operator session/device-code auth to the CP … **The client never chooses
  `sub`** — the CP sets it from the authenticated identity." Der Client liefert nur `{hubId, cb}` (`HubTicketRequest`).
- `HubTransport.sessionToken()` trägt heute den **Kratos**-Token (`HubTransport.kt:27-33`); S-K erweitert ihn zum
  hub-scoped Ticket-JWT (`SessionMaterial.hubTicket` heute auskommentiert = NOT-BUILT).
- Docs 17/18: der Operator macht **ein** zentrales Kratos-Login; der einzige benannte Extra-Schritt beim Remote-Login ist
  **„ein zusätzlicher Touch/PIN"** (= DevicePoP, Faktor c). **Der Device-Code-Flow ist HUB-Provisioning** (Use Case 1),
  **nicht** per-Connect-Operator-Auth.
- Der Mint passiert **nach** dem Noise-Handshake (damit `cb` das Live-`h` bindet) — **innerhalb** dessen, was die UI
  `AUTHENTICATING` nennt.

⟹ **Die S-B-Spec ist auf dem Happy-Path korrekt.** Kein CP-Login-Screen nötig. Der einzige sichtbare Auth-Moment ist der
DevicePoP (S-B §4), bereits spezifiziert.

---

## 1. Klarstellung: `AUTHENTICATING` faltet **zwei** Faktoren

`AUTHENTICATING` (= `ClientOperatorAuth.authenticate()`, `ClientOperatorAuth.kt:33/36`) deckt **beide**:
- **(a+b) CpJwt-Mint — TRANSPARENT** (kein Prompt): Client holt/mintet den hub-scoped CpJwt aus der Kratos-Session +
  `{hubId, cb}`. **Fail-closed:** kein Ticket ⇒ kein Connect (nie erfunden, KDoc „absent ⇒ fail-closed").
- **(c) DevicePoP — SICHTBAR** (Touch-ID/PIN, S-B §4, `OperatorAuthDialog` inline).

**UX-Konsequenz:** der `AUTHENTICATING`-Slot rendert den **PoP-Prompt** (sichtbar); der CpJwt-Mint ist die **transparente**
a+b-Hälfte davor (kein eigener Screen). **Ergänzung zu S-B §4:** die Copy/Chrome zeigt den PoP als den Nutzer-Schritt;
der CpJwt-Mint bleibt still (er ist kein Nutzer-Moment) — **aber sein Fehlschlag ist einer** (§2).

---

## 2. ★ CP-Auth-Fail-Pfad (die eigentliche Lücke) — NEU

Der CpJwt-Mint kann scheitern; heute ist das **nicht** repräsentiert (`RemoteFailure` hat nur relay/hub/handshake/trust/
auth-Ursachen). Drei operator-facing Fälle, alle **fail-closed** (kein Ticket ⇒ kein Connect):

| Fall | Ursache | UX | Copy (DE / EN) |
|---|---|---|---|
| **CP-Sitzung abgelaufen/fehlt** | Kratos-Session tot ⇒ Mint kann Operator nicht authentifizieren | **sichtbare Re-Auth** (der EINE Ort, wo ein CP-Login erscheint — Recovery, nicht Happy-Path): route auf den zentralen Login (**AuthGate/CYP-176**), danach Connect fortsetzen | `remote_connect_cp_session_expired` „CP-Sitzung abgelaufen — erneut anmelden." / „CP session expired — sign in again." |
| **CP nicht erreichbar** | CP-Round-trip für den Mint scheitert | Fehler + Retry (neutral/errorContainer, wie relay-Klasse) | `remote_connect_cp_unreachable` „Control Plane nicht erreichbar — Ticket kann nicht ausgestellt werden." / „Control Plane unreachable — can't issue the ticket." |
| **Nicht berechtigt für Hub** | CP verweigert den Mint für Hub Y (kein Mitglied) | **terminaler** ehrlicher Fehler — **distinkt** von hub-seitigem `authRejected` (das ist der Hub, der die PoP ablehnt; dies ist die **CP**, die den Mint verweigert) | `remote_connect_not_authorized_hub` „Für diesen Hub nicht berechtigt." / „Not authorized for this hub." |

**Platzierung:** die Fehler entstehen **innerhalb `AUTHENTICATING`** (der Mint sitzt dort). 3 neue Ursachen an der
**bestehenden `RemoteConnectTags.error(<cause>)`-Taxonomie** (reuse-Muster, camelCase): `cpSessionExpired` · `cpUnreachable` ·
`notAuthorizedForHub`. `cpSessionExpired` ist **kein** blanker Fehler, sondern der **Re-Auth-Recovery-Pfad** (→ AuthGate → resume).

**Honesty:** `cpSessionExpired` ≠ `notAuthorizedForHub` ≠ `authRejected` — drei getrennte Wahrheiten (Session-Ablauf /
CP-Autorisierung / Hub-PoP-Ablehnung), nie vermischt. Alle fail-closed (nie ein Phantom-Ticket).

---

## 3. CP-Mediations-Sichtbarkeit (Disclosure-Notiz, keine neue UI)

Der CP **mintet** das Ticket ⟹ der CP **weiß**, welcher Operator sich mit welchem Hub verbindet (Metadaten) — auch wenn der
Tunnel E2E ist (CP sieht nur Ciphertext der **Nutzlast**). Das **verstärkt** die bestehende CYP-429-H5-Ehrlichkeit
(„E2E via Relay = nur Nutzlast, nicht Metadaten") — der E2E-Indikator darf **nicht** „vollständig unsichtbar" implizieren.
**Keine neue UI**, nur: die E2E-Indikator-Copy bleibt bei „Nutzlast E2E-verschlüsselt" (nie „anonym/unsichtbar"), konsistent
mit CYP-429 §8.2.

---

## 4. Net-New + Seam-Status

- **Net-New (3 Copy-Keys + 3 error-Ursachen):** `remote_connect_cp_session_expired` · `remote_connect_cp_unreachable`
  · `remote_connect_not_authorized_hub`; Tags `remote.connect.error.{cpSessionExpired,cpUnreachable,notAuthorizedForHub}`
  (erweitern das bestehende `error(<cause>)`-Muster — **keine** neue Area/Objekt). Re-Auth reused **AuthGate** (`auth_*`,
  CYP-176) — kein neuer Login-Screen.
- **DS/CYP-7-Sync (Freeze-GO):** die 3 error-Ursachen = geteilte testTag-API → der **PO koordiniert die Werte** mit
  Tester/DS beim Einfrieren.
- **⚠ Seam-gated:** der **gesamte** Client-Mint-Pfad ist **ungebaut** (`CpJwtProvider` = pre-Kontrakt-Stub falscher Signatur
  ohne `hubId`/`cb`; `SessionMaterial.hubTicket` auskommentiert; `HubTicketMinter`/`HubTicketRequest` **nur im
  CYP-501-Worktree, INERT**; a∧b∧c ungewired = CYP-459). ⟹ Die Fail-Pfad-Copy **landet mit der Mint-Verdrahtung**
  (CYP-501-Merge + CYP-459); bis dahin **absent** (kein Phantom-Fehler). **Design-Contract jetzt, Build später.**

---

## 5. Zusammenfassung

- **Abgedeckt (Happy-Path):** CP-Auth = **transparent** via Kratos-App-Login; **kein** sichtbarer CP-Step-up mid-Connect;
  Device-Code = Hub-Provisioning nicht per-Connect. S-B-Spec korrekt.
- **Lücke (gefüllt) = der CP-Auth-**Fail-**Pfad** (3 Fälle: Session-abgelaufen→Re-Auth · CP-unerreichbar · nicht-berechtigt) +
  die Klarstellung `AUTHENTICATING` = transparenter-Mint + sichtbarer-PoP + die CP-Mediations-Disclosure (§3).
- **3 net-new Copy/Ursachen, seam-gated** (landen mit CYP-501-Mint + CYP-459; DS/CYP-7-Sync über den PO).

---

## 6. Acceptance-Teeth (§-QA, wenn der Mint-Pfad landet — Ergänzung zur S-B-QA-Checkliste)

1. **CP-Auth transparent:** kein CP-Login-Screen auf dem Happy-Path; CpJwt mintet still aus der Kratos-Session; einziger
   sichtbarer Auth-Schritt = DevicePoP @ AUTHENTICATING.
2. **Fail-Pfad 3-getrennt:** `cpSessionExpired` (→ Re-Auth via AuthGate, resume) ≠ `cpUnreachable` (→ Retry) ≠
   `notAuthorizedForHub` (→ terminal) ≠ hub-seitig `authRejected` — nie vermischt; alle fail-closed (kein Phantom-Ticket).
3. **Seam-gated korrekt:** solange der Mint-Pfad INERT/ungebaut ist, sind die 3 Fail-Zustände **absent** (kein erfundener
   Fehler) — Absenz ist **kein** Befund.
4. **CP-Mediation ehrlich:** E2E-Indikator sagt „Nutzlast verschlüsselt", **nie** „anonym/unsichtbar" (CP sieht Metadaten).

---

*Spec-Closure (Freeze-GO 2026-07-12). Verdikt: Happy-Path abgedeckt (transparent), Fail-Pfad gefüllt. Gegen `eb84fe1b` +
Docs 17/18 + CYP-501-§3 (worktree) gegroundet, read-only. Nichts gebaut; Mint-Pfad INERT/CYP-501-Worktree, Fail-Copy
seam-gated (CYP-501+CYP-459). DS/CYP-7-Sync für die 3 error-Ursachen über den PO. Alle Seams über den PO.*
