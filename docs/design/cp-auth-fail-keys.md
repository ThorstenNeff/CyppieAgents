# CYP-517 — CP-Auth-Fail-Copy (Control-Plane-Auth-Ursachen) · i18n-Keys + Tags

> Owner: UIUX-Designer · **Dev-AC (copy-paste-fertig)** · Stand 2026-07-13 · PO `1526302329…`.
> Baut auf dem CP-Auth-Nachtrag (CYP-482, `remote-connect-screen-sb-cpauth-addendum.md`) + der Enroll-Flow-Schiene
> **retryable-nicht-terminal** (`device-enroll-keys.md` HF/H2). Konvention: Underscore-Realkeys, `%1$s`, DE=Default +
> EN-Parität. Gegroundet READ-ONLY gg. develop `4572a278`.
> **Seam-gated:** der ganze CP-Mint-Pfad ist ungebaut (`HttpCpJwtProvider`/`CpJwtProvider` pre-Kontrakt, a∧b∧c =
> CYP-459, cb = CYP-514). Dev verdrahtet **post-CYP-525-Merge** — die Copy kann jetzt vor (schließt die CYP-517-Lücke
> im Voraus, vermeidet den von Dev geflaggten CYP-494-Rework). Absenz heute = by design, **kein Befund**.

## Layer-Distinktion (der Kern — PO-betont)
Dies sind **CP-LAYER-Ursachen** (Control-Plane-Auth, = das Minten des Hub-Tickets **bevor** der Hub dich sieht),
**distinkt** von den **Remote-Connect-Surface**-Ursachen (`DeviceNotEnrolled`/`AuthRejected`/`TrustChanged`/… am
Gerät/Hub). Sie rendern zwar über **dieselbe** Fläche (`RemoteFailureView` / `remote.connect.error.<cause>`), tragen
aber eine **andere Wahrheit**: die zentrale Session/Autorisierung, nicht Gerät oder Hub-Verdikt.
- **Herkunft (aus dem Build verifiziert):** `CP_SESSION_EXPIRED` / `NOT_AUTHORIZED_FOR_HUB` = **Server**-`HubTicketFailure`
  (typed, `HttpCpJwtProvider`); `cpUnreachable` = **Client**-Herkunft (die CP antwortet nie). 3 getrennte Wahrheiten.
- Server-Test bestätigt die Ton-Achse wörtlich: `CP_SESSION_EXPIRED` = „a dead session is the **non-terminal** truth",
  `NOT_AUTHORIZED_FOR_HUB` = „the **terminal** deny".

## NET-NEW Keys — Dev legt an (0 Kollision @ `4572a278`)
| Ursache | Key | DE | EN | Ton / Affordance |
|---|---|---|---|---|
| **CP_SESSION_EXPIRED** | `remote_connect_cp_session_expired` | Sitzung abgelaufen — bitte erneut anmelden. | Session expired — please sign in again. | **RETRYABLE / recoverable → WARN-amber `▲`.** Affordance = **Re-Login** (zentraler AuthGate / CYP-176), **nicht** blindes Connect-Retry. Benignes Lebenszyklus-Ereignis — **nie** error-rot, **nie** „abgelehnt". |
| **NOT_AUTHORIZED_FOR_HUB** | `remote_connect_not_authorized_hub` | Nicht berechtigt für diesen Hub. | Not authorized for this hub. | **TERMINAL → `HintTone.ERROR` (errorContainer), kein Retry, kein Re-Auth** (Re-Login ändert die Berechtigung nicht). Policy-Deny auf CP-Ebene. **KEIN Admin-Suffix (PO `1526303594…`):** MVP ist Single-Operator (Operator ownt seinen Hub → kein „Hub-Administrator"-Rollen-Begriff existiert); ein Suffix würde auf eine nicht-vorhandene Rolle zeigen. Zeile bleibt as-is; Suffix revisiten, wenn Multi-User/Rollen landen. (Im Single-Operator-Dogfood ist die Ursache quasi unerreichbar = defensiv/future-proofing.) |
| **cpUnreachable** | `remote_connect_cp_unreachable` | Zentraler Dienst nicht erreichbar — später erneut versuchen. | Central service unreachable — try again later. | **TRANSIENT / retryable → wie `RelayUnreachable`** (Retry-Affordance). Symmetrisch zur Relay-/Hub-offline-Copy. **Plain-Language (PO `1526303594…`): „Zentraler Dienst" / „central service", NICHT „Control Plane"** — internes Architektur-Naming leakt nicht in user-facing Copy (Anti-Hype/Plain-Language-Ethos). |
| a11y (empfohlen, WARN) | `a11y_remote_connect_cp_session_expired` | Warnung: Sitzung abgelaufen — erneut anmelden. | Warning: session expired — sign in again. | a11y-Präfix „Warnung/Warning" wie `a11y_workspace_remote_context` (der `▲`-Node ist dekorativ + Text trägt die Wahrheit, WCAG 1.4.1). |

## Tags — `RemoteConnectTags.error(<cause>)` (bestehende fn, KEIN neuer Const/Objekt)
| Tag | Wert | Herkunft |
|---|---|---|
| `RemoteConnectTags.error("cpSessionExpired")` | `remote.connect.error.cpSessionExpired` | Server `HubTicketFailure.CP_SESSION_EXPIRED` |
| `RemoteConnectTags.error("notAuthorizedForHub")` | `remote.connect.error.notAuthorizedForHub` | Server `HubTicketFailure.NOT_AUTHORIZED_FOR_HUB` |
| `RemoteConnectTags.error("cpUnreachable")` | `remote.connect.error.cpUnreachable` | Client (CP antwortet nie) |

> Charset ✓ (camelCase-Segmente, `[A-Za-z0-9-]+`, kein Underscore/Punkt im Segment-Wert). Nutzt die **bestehende**
> `error(cause)`-fn (heute cause ∈ relayUnreachable/hubOffline/handshakeFailed/trustChanged/authRejected) → **kein
> neuer Const**, 3 neue Ursachen-Strings.

## Honesty-Anker (für §-QA)
- **5+ nie-konflatierte Fail-Wahrheiten am Connect-Surface:** `cpSessionExpired` (CP-Session weg, WARN, Re-Login) ≠
  `notAuthorizedForHub` (CP-Policy-Deny, terminal) ≠ `cpUnreachable` (CP-Infra weg, retry) ≠ `authRejected` (Hub lehnt
  Geräte-PoP ab, terminal) ≠ `deviceNotEnrolled` (Gerät nicht eingerichtet, aktionabel). Jede eigene Copy + Tag + Ton.
- **Ton-Schiene = `RemoteFailureView`-Severity-Pattern:** WARN-amber `▲` (Aufmerksamkeit, nicht kaputt) für
  `cpSessionExpired`; `HintTone.ERROR`/errorContainer (terminal) für `notAuthorizedForHub`; retryable (Retry-Button, §7-
  Ton-Konsistenz mit Relay/Hub) für `cpUnreachable`. **Kein** Tag trägt Erfolgs-Grün.
- **★ Reconciliation mit der CYP-525-③-Ruling:** dort ist `cpSessionExpired` am Connect-Surface heute **absent**
  (AuthGate-Re-Login-Pfad; die „kollabiert-in-authRejected"-Kante war als LOW post-dogfood geloggt). **CYP-517 ist die
  geplante Schließung dieser Kante:** es gibt der CP-Session-Expiry eine **eigene, ehrliche** Ursache (WARN + Re-Login),
  statt sie als Hub-Ablehnung zu maskieren. Die Affordance **ist** das AuthGate-Re-Login aus ③ — **kein** Widerspruch,
  sondern dessen erstklassige Oberflächung. GE4/HB (2-Wege heute) bleiben für den **Nicht-CP** Connect-Surface gültig.
- **Herkunfts-Ehrlichkeit:** `cpUnreachable` ist client-seitig (kein Server-Verdikt) — nie als „Hub/CP hat entschieden"
  gerahmt; es ist „wir erreichen die CP nicht".
- **Plain-Language (PO `1526303594…` ge-ruled):** `cp_unreachable` sagt **„Zentraler Dienst" / „central service"**,
  **nicht** „Control Plane" — internes Architektur-Naming leakt nie in user-facing Copy (Anti-Hype/Plain-Language-Ethos).
  `cp_session_expired` vermeidet den Begriff ohnehin („Sitzung abgelaufen"). Der Key-Name (`_cp_unreachable`) + Tag
  (`cpUnreachable`) bleiben (interne Kennung ≠ user-facing Copy).
- **NOT_AUTHORIZED = terminal, ohne Admin-Verweis (PO `1526303594…` ge-ruled):** MVP Single-Operator → kein
  „Hub-Administrator" existiert; die Terminal-Zeile bleibt suffixlos, nie auf eine nicht-vorhandene Rolle zeigend.

## Self-Validation
- **Net-new: 3 Realkeys** (`remote_connect_cp_session_expired` / `_not_authorized_hub` / `_cp_unreachable`) **+ 1 a11y**
  (empfohlen, WARN) = **4 Keys**. Alle DE+EN, 0 Args (DE=EN identisch).
- **0 Kollision** @ `4572a278` (kein `cp_session_expired`/`not_authorized`/`cp_unreachable`/`control_plane` in beiden
  `strings.xml`; `CP_SESSION_EXPIRED`/`NOT_AUTHORIZED_FOR_HUB` existieren nur als Server-`HubTicketFailure`-Enum, kein
  Client-Key/Tag).
- **Tags: 3 neue Ursachen-Werte** über die bestehende `RemoteConnectTags.error(cause)`-fn — **kein** neuer Const/Objekt.
- **Geteilte API mit QA (CYP-7):** die 3 Ursachen-Werte über den PO mit Tester + DS abstimmen (Frozen-Contract) —
  wie beim CP-Auth-Nachtrag angekündigt.
- **Kein content-tragender/sensibler Klartext**; kein „Seam"/„a∧b∧c"/„HubTicket"-Jargon in der User-Copy.
- **Seam-gated:** absent bis der CP-Mint-Pfad (CYP-496/459/514) landet — Dev verdrahtet post-CYP-525-Merge.
