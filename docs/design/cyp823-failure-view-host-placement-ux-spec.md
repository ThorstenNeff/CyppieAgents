# CYP-823 (A4) — Failure-View-Host Layout: Multi-Hub Connect-Flow Placement

> UX/UI-Placement-Deliverable. **CYP-807-Weiche = Option A (Multi-Hub) entschieden** → web-ts bekommt einen Connect-Flow-Host.
> Dieses Doc legt fest, **wo/wie** die Connect-Failure-Region + die drei geparkten Trust-UI-Leaf-Renders im Multi-Hub-
> Connect-Flow rendern. Synthese aus **CYP-755 §2/§3** (Trust-Badge-Placement) + **CYP-807** (host-agnostisches Slot-Spec).
> Feeds Dev5s A2/A3-Mount. Ich designe/spec/verifiziere — Dev5 baut die Region/Wiring.

## 0. Der Kern-Constraint zuerst — ZWEI ZONEN, nie vermischt
Der PO-Constraint (**Achse-c-Issuer-Block ≠ Achse-a-Trust-Badge**) ist **kein Detail, sondern die Layout-Achse**. Drei
trust-artige Flächen sind **drei distinkte Konzepte** mit **drei Lebenszyklen** → **zwei Zonen**:

- **Zone 1 — Always-visible STATUS-Chrome** (persistent, at-a-glance, fail-closed): *„Wie steht es um diesen Hub / diese
  laufende Verbindung?"* Trägt den **HubTrustBadge** (Achse a, per-Hub TOFU-Key-Status) und den **RemoteSecurityTierBadge**
  (laufende-Verbindung-Tier). Ändert sich **nicht** mit dem Connect-Versuch — Status, kein Ereignis.
- **Zone 2 — Die Connect-Failure-REGION** (transient je Connect-Versuch, terminal): *„Dieser Verbindungsversuch endete
  wie?"* Trägt die **Failure-Arme**, u. a. den **IssuerNotTrustedBlock** (Achse c, terminaler WARN-Block). Erscheint **nur**
  am terminal-negativen Verbindungs-Ausgang (`failed(cause)`/terminal-`lost`), eine Ursache zur Zeit.

**Warum strikt getrennt:** ein Hub kann **gleichzeitig** `trusted` sein (Achse-a-Badge, Zone 1 — „ich habe seinen Key
gepinnt") **UND** einen IssuerNotTrusted-Block haben (Achse c, Zone 2 — „der Hub vouched meinen Aussteller nicht"). Das
sind **verschiedene Trust-Fragen** — sie im selben Element/Ort zu rendern würde sie konflatieren ([[reconcile-not-collapse-distinct-states]]
/ `tier*`≠`trust*`≠issuer-Disziplin). **Kein** Element trägt zwei Zonen; **kein** Ort mischt Status-Badge und Failure-Block.

## 1. Die drei Leaf-Renders — Rolle, Zone, Kardinalität
| Leaf | Achse | Rolle | Zone | Kardinalität | Ton |
|---|---|---|---|---|---|
| **HubTrustBadge** (CYP-801) | a (Hub-Key TOFU) | always-visible **Status** | 1 | **N** (per Hub im Registry) | neutral (CYP-803) |
| **RemoteSecurityTierBadge** (CYP-676) | tier (laufende Conn) | always-visible **Status** | 1 | **1** (aktive Verbindung) | neutral/INFO (nie Alarm) |
| **IssuerNotTrustedBlock** (CYP-805) | c (Issuer-Vouch) | terminaler **Block-Arm** | 2 | **1** (aktiver Connect) | WARN-amber terminal |

**Kardinalitäts-Pointe:** Trust-**Status** ist **multi** (alle Hubs im Switcher tragen ihr eigenes Badge); die
Failure-**Region** + Tier sind **singulär** (ein aktiver Connect / eine laufende Verbindung zur Zeit — CYP-805-Tags sind
bewusst **nicht** per-`hubId`).

## 2. Zone 1 — Always-visible Status-Chrome
### 2a. Hub-Switcher-Bar (per-Hub Trust-Badge) — CYP-755 §2/§3 realisiert
- **Form-Reuse mit Anpassung:** die **`ProjectSwitcher`-Muster** (`App.tsx:982` / CYP-651 — always-visible, aktiv-Pointer,
  **non-optimistisch/server-confirmed**, operator-gated) ist der richtige *Interaktions*-Anker. **ABER** ProjectSwitcher
  ist ein nativer `<select>` → kann **kein** per-Option-Glyph-Badge + a11y tragen. Der Hub-Switcher braucht die **Listen-
  Form** (Menü-Items, nicht `<select>`-Options), damit **jeder Hub sein eigenes HubTrustBadge at-a-glance** trägt. → Reuse
  das *Switch-Verhalten* (non-optimistisch, aktiv folgt server-confirmed `activeHubId`, switch-to-active = no-op), **nicht**
  das `<select>`-Rendering.
- **Jeder Hub-Eintrag:** Name + **`<HubTrustBadge trust={…} validity={…}>`** (per-`hubId`). Der aktive Hub ist markiert.
- **Honesty (CYP-755 §2/§3):** inaktive/nicht-frische Hubs rendern `unknown`, **nicht** das letzte gecachte `trusted`
  ([[forecast-vs-observed-disclosure]]). Fail-closed sichtbar, nie Stille ([[absence-reads-as-all-clear]]).

### 2b. Aktiv-Hub Connection-Header (Tier-Badge)
- Im Header des **aktiven** Hubs (die verbundene Fläche): **`<RemoteSecurityTierBadge tier={…}>`** — der Tier der
  **laufenden** Verbindung (native/gateway/unknown). Fail-closed `unknown`. Die BROWSER_GATEWAY-INFO-Disclosure bleibt
  **always-visible** (CYP-676 §5 — Downgrade nie tap-to-reveal).
- **Nicht** im Switcher-Eintrag (das ist per-Hub-Trust, nicht laufende-Conn-Tier) — zwei verschiedene Status-Achsen,
  nebeneinander im Header lesbar, aber distinkte Badges ([[token-contrast-is-role-dependent]]-Nachbar: distinkte Namespaces
  `hub.trust.*` vs `remote.security.tier.*`).

## 3. Zone 2 — Connect-Flow + Failure-Region
Der Connect-Flow des **aktiven** Hubs (Parität zu Compose `RemoteConnectingView`, ein `HubCard`), schaltet auf den
Conn-State:
`dialing → handshake → trust-check → authenticating → (reconnecting) → CONNECTED | failed(cause) | lost`.

**★ Terminologie (reconciled mit Dev5s CYP-822-A1-State-Machine):** meine frühere „`LOST`"-Bezeichnung stammte aus
Compose' EINEM terminal-negativen State (`RemoteConnState.LOST`). Dev5s Machine ist präziser und splittet ihn:
**`failed(cause)`** = *nie verbunden* (z. B. IssuerNotTrusted-Refusal am trust-check) vs **`lost`** = *war verbunden,
dann gedroppt*. „Failure-Region am terminal-negativen Ausgang" meint daher **beide terminal-negativen Ausgänge**:
`failed(cause)` **∪** terminal-`lost` — der Arm-Switch dispatcht auf die konkrete Ursache/Art. **Ein reconnectbares/
in-flight `lost`** (falls die Machine das modelliert) ist ein **Transient** (polite, retrybar), **kein** Failure-Arm —
wie Compose `RECONNECTING`. **`IssuerNotTrusted` ist spezifisch ein `failed(IssuerNotTrusted)`** (Refusal am trust-check,
nie verbunden) — **nie** `lost`.
- **In-Progress-States:** neutrale Spinner/Copy (nie Alarm; „reconnecting" ist honestly-uncertain, nicht rot).
- **CONNECTED:** verbundene Fläche + der **Tier-Badge** (2b) + Enter-Workspace. Kein Failure.
- **terminal-negativ (`failed(cause)` ∪ terminal-`lost`) → Failure-Region:** **EINE Region, N Arme, Arm-Switch je Ursache** (Parität zu Compose `RemoteFailureView`):
  | Ursache | Achse | Arm | Ton | Retry? |
  |---|---|---|---|---|
  | `NOT_TRUSTED` (Issuer) | c | **`IssuerNotTrustedBlock`** (CYP-805) | ▲ WARN-amber terminal, OOB | **nein** |
  | AuthRejected | b | Auth-Arm *(später)* | error-rot | nein |
  | TrustChanged (Hub-Key) | a | Re-Pin-Arm *(später)* | re-pin-Register | nein |
  | Transport/Netz | — | Transport-Arm *(später)* | **neutral** | **ja** |
  - **Genau ein Arm** sichtbar; **Ursache-distinkt** (keine Kollaps-Copy „Verbindung fehlgeschlagen"). Terminale Arme
    (Issuer/Auth/TrustChanged) = **kein Retry**; nur Transport = Retry ([[terminal-block-tone-is-structural]]).
  - **Bau-Auflage (CYP-807-Empfehlung, bestätigt):** die Region als **Arm-Switch** bauen — auch wenn heute nur der
    Issuer-Arm existiert — damit Auth/TrustChanged/Transport **additiv** als Arme landen.

## 4. Mount-Slots (explizit für Dev5s A2/A3)
| # | Slot | Komponente | Props / State-Quelle | Bedingung |
|---|---|---|---|---|
| M1 | Hub-Switcher-Eintrag (je Hub) | `HubTrustBadge` | `trust`, `validity` per `hubId` (axis-a Verdikt-Feed) | immer (alle Registry-Hubs) |
| M2 | Aktiv-Hub Connection-Header | `RemoteSecurityTierBadge` | `tier` der laufenden Verbindung (S7-Transport-Seam) | wenn aktiv/verbunden |
| M3 | Failure-Region-Arm | `IssuerNotTrustedBlock` | `issuerTrust`-Enum (NUR das Enum, **nie** die issuer-ID — CYP-805-Security) | **`failed(cause=IssuerNotTrusted)`** (≡ `issuerConnectDecision===block` am gescheiterten trust-check) — **NICHT** `lost` |

- **M1 ist Zone 1, M3 ist Zone 2 — verschiedene Orte, verschiedene Lebenszyklen.** M1 persistiert; M3 erscheint nur am
  terminal-negativen Ausgang. Sie können **gleichzeitig** sichtbar sein (Hub im Switcher = `trusted`, Connect endet `IssuerNotTrusted`)
  ohne Widerspruch — genau der Beweis, dass die Zonen getrennt sind.

## 5. Übergänge
- **proceed** (`absent`/`TRUSTED`/`REMOTE_NOT_CONFIGURED`): **kein** Issuer-Arm; ohne anderen Failure keine Failure-Region;
  Flow läuft bis `CONNECTED`. *(Absent→proceed sicher, weil **Server** das Gate ist — CYP-805 / [[client-gate-is-not-the-boundary]].)*
- **block** (`NOT_TRUSTED`): Failure-Region mountet den Issuer-Arm bei `failed(IssuerNotTrusted)`, **assertive**, kein Retry/Proceed, `CONNECTED`
  wird **nicht** erreicht.
- **Hub-Switch (CYP-755 §3):** Teardown→Setup, **resolve-then-render**; Rolle/Tier/Trust des neuen aktiven Hubs
  **fail-closed neu aufgelöst** (least-privilege bis aufgelöst) — **nichts** vom alten Hub reist. Die Zone-1-Badges der
  anderen Hubs bleiben ihr eigener Status; Zone-2-Region/Tier folgen dem **neuen** aktiven Hub.

## 6. Cross-Surface-Parität (Compose)
- **Struktur:** `RemoteConnectingView` (conn-switch) → `LOST` → `RemoteFailureView(when failure)` — 1-Region/N-Arme,
  terminal=no-retry. web-ts spiegelt das **strukturell** (Option A).
- **Copy/Tags:** Issuer-Arm = CYP-805 (wortgleich Team-1 CYP-747 §5-C2, Tags `remote.connect.error.issuerNotTrusted`).
  Trust-Badge = CYP-801/803 (neutral). Tier = CYP-676. **Keine** neue Copy/Ton/Glyph hier — reines Placement.

## 7. Teeth (Tester2, sobald gemountet)
1. **Zonen-Trennung (load-bearing)** — der `HubTrustBadge` (M1) rendert **nie** in der Failure-Region, der
   `IssuerNotTrustedBlock` (M3) **nie** im Switcher/Status-Chrome. Namespaces bleiben getrennt (`hub.trust.*` ≠
   `remote.connect.error.*`). *(Mutation: Issuer-Block im Status-Strip / Trust-Badge als Failure-Arm → RED = Achsen-Konflation.)*
2. **Ko-Existenz ohne Widerspruch** — ein Hub `trusted` im Switcher (M1) UND `IssuerNotTrusted` in der Failure-Region (M3)
   **gleichzeitig** rendern beide korrekt, kein Element überschreibt das andere. *(Mutation: der Block „übernimmt" das
   Trust-Badge oder umgekehrt → RED.)*
3. **Ein-Arm** — genau ein Failure-Arm am terminal-negativen Ausgang; nie zwei Failure-Aussagen. Terminal=kein Retry.
4. **proceed→kein Block; block→Block terminal** — s. §5.
5. **Switcher: inaktiv/nicht-frisch = `unknown`** — kein gecachtes `trusted` für nicht-frische Hubs (Zone-1-Honesty).
6. **Fail-closed Re-Resolve bei Switch** — nichts vom alten Hub reist; neuer Tier/Trust/Rolle neu aufgelöst.
7. **Tier-Badge Zone 1, nicht Failure** — der `RemoteSecurityTierBadge` (M2) ist Status im Header, nie ein Failure-Arm;
   BROWSER_GATEWAY-Disclosure always-visible.

## 8. Reuse-Ledger
- **Switch-Verhalten:** `ProjectSwitcher`/CYP-651 (non-optimistisch, server-confirmed, aktiv-Pointer) — Verhalten reusen,
  Render als **Liste** (per-Hub-Badge) statt `<select>`.
- **Connect-Flow-Struktur:** Compose `RemoteConnectingView`/`RemoteFailureView` (`HubConnectSelection.kt`).
- **Multi-Hub-State:** `hubRegistry` (CYP-800, `hubIds()`/`endpointFor`), `AuthGate.activeHubId`.
- **Leaf-Renders unverändert:** CYP-801 (HubTrustBadge), CYP-676 (TierBadge), CYP-805 (IssuerNotTrustedBlock) — dies ist
  **Placement/Layout**, kein neuer Leaf, keine neue Copy.
- **Prior-Specs:** CYP-755 §2/§3 (Trust-Badge-Strip/Switcher + Switch-Flow), CYP-807 (Failure-Region-Slot).
