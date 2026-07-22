# IssuerNotTrusted — Cross-Surface-Consistency-Referenz (Desktop-Compose → web-ts)

> Owner: UIUX-Designer · **Referenz/Consultation, kein Bau, kein Auftrag an Team-2** (via PO relayed) ·
> Stand develop `f6c850cd`. Zweck: das **gelandete Desktop-Compose-IssuerNotTrusted-Arm** als 1:1-Vorlage
> dokumentieren, damit uiux2/Team-2 die **web-ts-Mount/Placement-Slice** (die 0-render-sites-Lücke)
> **user-visible konsistent** zum Desktop baut. Grundlage: mein §-QA des gelandeten Arms (CYP-805).
> Verwandt: `CYP-747-issuer-not-trusted-ui-spec.md` (Achse c) · `CYP-747-n3-trust-render-qa-checklist.md`
> (die 9 §-QA-Zähne).

---

## 0. Der eine Satz

Die **Wahrheit** ist beidseitig gleich: *owned Hub, kein vertrauenswürdiger Aussteller etabliert →
terminaler Fail-closed-Block, keine Operator-Berechtigung, Auflösung OOB.* Das **Rendering** muss über
Desktop **und** Browser **dasselbe Muster** tragen — sonst liest derselbe Zustand auf zwei Flächen
verschieden. Diese 6 Achsen sind die Vorlage.

---

## 1. Die 6 Konsistenz-Achsen (Desktop-Fakt → web-ts-Anforderung)

| # | Achse | Desktop-Compose (gelandet, `HubConnectSelection.kt`) | web-ts-Anforderung (1:1) |
|---|---|---|---|
| i | **Ton/Glyph** | **WARN-amber `▲`** + Label in `severityColor(Severity.WARN)` — **NICHT** error-red (`:370`) | amber-WARN `▲` + Label; **nicht** die error/danger-Rolle |
| ii | **OOB = Hinweis, kein Button** | OOB-Zeile ist ein **`Text`** mit Tag `ISSUER_OOB`; **kein** Button, **kein** Retry, **kein** Grant (mirror `TRUST_CHANGED_REPIN`) | OOB = **statischer Text**, **kein** `<button>`/Link-Action; kein Retry/Grant-Control |
| iii | **a11y-Dringlichkeit** | `liveRegion = Assertive` + `contentDescription = a11y_...` am **Container** (`Column`) | `aria-live="assertive"` (oder role=alert) am Container + die a11y-Copy |
| iv | **Placement** | im **Connect-Failure-Flow**: `RemoteConnectingView` → `RemoteConnState.LOST → RemoteFailureView(failure)` → `IssuerNotTrusted`-Zweig (`:326`→`:370`) | im **Connect-Failure-Flow** an der terminalen/LOST-Position — **nicht** ein separates Toast/Modal, **nicht** versteckt/inert |
| v | **Copy-Anker** | die 3 gefrorenen Strings (§2), **Anker-Term** „kein vertrauenswürdiger Aussteller etabliert" — **nie** „Token abgelehnt" | **dieselbe Aussage** (Establishment-Ebene, nicht Token-Reject); DE/EN synchron |
| vi | **Shared testids** | `RemoteConnectTags.error("issuerNotTrusted")` = `remote.connect.error.issuerNotTrusted` · `RemoteConnectTags.ISSUER_OOB` = `remote.connect.issuerOob` | **dieselben** testids — geteilter QA-Contract über beide Flächen |

---

## 2. Copy-Anker (gefroren, beidseitig identisch in der Aussage)

| Key | DE (Desktop, gelandet) | EN |
|---|---|---|
| `remote_connect_issuer_not_trusted` | Verbindung angehalten: Dieser Hub ist registriert, aber es ist kein vertrauenswürdiger Aussteller an ihm etabliert. Ohne etabliertes Aussteller-Vertrauen wird keine Operator-Berechtigung erteilt. | Connection halted: this hub is registered, but no trusted issuer is established for it. Without established issuer trust, no operator authority is granted. |
| `remote_connect_issuer_oob` | Ein vertrauenswürdiger Aussteller wird außerhalb der App am Hub etabliert (durch den Betreiber/PO). Danach erneut verbinden. | A trusted issuer is established for the hub outside the app (by the operator/PO). Reconnect afterwards. |
| `a11y_remote_connect_issuer_not_trusted` | Verbindung angehalten: kein vertrauenswürdiger Aussteller am Hub etabliert. Keine Operator-Berechtigung. Aussteller-Vertrauen wird außerhalb der App etabliert; danach erneut verbinden. | Connection halted: no trusted issuer established for the hub. No operator authority. Issuer trust is established outside the app; reconnect afterwards. |

> **Establishment-Ebene, nicht Token-Ebene** (CYP-803/Backend-Route-#1): der Zustand ist „**kein Aussteller
> etabliert/gepinnt**", **upstream** des per-Token-Verify — die Copy formuliert den **Anker-Zustand**, nie eine
> Token-Ablehnung. Das trägt zugleich die **amber-WARN**-Klassifikation (Achse i): es ist kein „kaputt/denied".

---

## 3. Die Honesty-Kerne, die über beide Flächen halten müssen

- **Fail-closed by construction** — der Block ist **terminal**: es entsteht **kein** CONNECTED, keine Session ⟹
  Operator-Autorität kann **nicht** lecken. web-ts darf **nicht** eine „connected-but-warned"-Variante bauen,
  die trotzdem Aktionen zulässt. *(Falls web-ts irgendetwas Autorität-trotz-untrusted grantet → HALT → PO/PL.)*
- **Kein In-UI-Issuer-Grant** — es gibt **keinen** „diesem Aussteller trauen"-Button, auf **keiner** Fläche.
  Aussteller-Trust ist die **OOB/PO-Grenze**. Der `ISSUER_OOB`-Text ist ein **Hinweis**, keine Aktion.
- **Kein Retry** — Reconnect vor OOB-Auflösung träfe denselben untrusted Aussteller → futil; ein Retry-Button
  täuschte klickbare Auflösung vor. (Wie Desktop `TrustChanged`: kein Retry.)
- **Marked, not hidden** (CYP-789) — der Zustand wird **sichtbar** platziert (eigener Node/testid, Grund + OOB-
  Weg), **nie** ein stiller/generischer Failure und **nie** inert/versteckt.
- **Achse-c ≠ Achse-a am Render (Cyp443 / PL-0107)** — dieser Issuer-Block (c) ist **nicht** das Hub-Key-
  `HubTrustState` (a). Kein geteilter „vertraut/trust"-Look, der die Achsen konflatiert. *(Auf Compose heute
  unkritisch — es rendert nur axis-c; **auf web-ts relevant, sobald das positive `HubTrustState.TRUSTED`-Badge
  koexistiert** — dann die Zwei-`TRUSTED`-Distinktheit wahren, s. `…-n3-trust-render-qa-checklist.md §5-Zahn 9`.)*

---

## 4. Anti-Patterns (was die web-ts-Mount NICHT tun darf)

- ❌ error-red/danger-Ton (impliziert „kaputt/denied" — falsch; es ist verify-OOB).
- ❌ ein „Erneut verbinden"/Retry- **oder** „Aussteller vertrauen"-Button.
- ❌ die OOB-Zeile als klickbare Aktion statt als Text-Hinweis.
- ❌ „Token abgelehnt"/„rejected"-Wortlaut statt des Establishment-Anker-Terms.
- ❌ Toast/kurzlebiges Banner, das verschwindet (der Zustand ist **terminal** — er bleibt bis OOB+Reconnect).
- ❌ still/inert (kein Node, kein a11y) — die 0-render-sites-Lücke ist genau der zu schließende Fehler.
- ❌ abweichende testids (bräche den geteilten QA-Contract).

---

## 5. Verifikations-Referenz (Desktop, für Team-2 zum Abgleich)

- Render-Arm: `HubConnectSelection.kt:370` (`is RemoteFailure.IssuerNotTrusted`).
- Mount/Placement: `HubConnectSelection.kt:326` (`RemoteConnState.LOST → RemoteFailureView`).
- Tags: `RemoteConnectTags.error("issuerNotTrusted")`, `RemoteConnectTags.ISSUER_OOB`.
- Beweis der User-Visibility: `Cyp747IssuerNotTrustedRenderTest` (LOST + IssuerNotTrusted → Block sichtbar,
  kein CONNECTED, kein Retry, a11y Assertive).
- Meine §-QA des Arms: 🟢 GO (alle anwendbaren Zähne) — diese Referenz destilliert genau dieses geprüfte Muster.

---

## 6. Self-Validation

- **Am gelandeten Code geerdet** (nicht am Stub): Render-Arm + Mount + Tags + Strings auf develop `f6c850cd`
  real gelesen; die 6 Achsen sind Desktop-Fakten mit file:line, keine Wunschliste.
- **Consultation, kein Auftrag** — Referenz für uiux2/Team-2, via PO relayed; ich schneide/gate ihre Slice nicht.
- **Honesty-Kerne über Flächen konstant** (§3): fail-closed, kein Grant, kein Retry, marked-not-hidden, Achse-c≠a —
  dieselben, die meine §-QA am Desktop bestätigt hat.
- **Achse-c≠a-Nuance ehrlich gescoped** (§3): auf Compose heute unkritisch (nur axis-c rendert), auf web-ts
  relevant sobald das TRUSTED-Badge koexistiert (Cross-Ref auf Zahn 9 / CYP-808) — kein Overclaim, dass es
  „schon jetzt überall beißt".
- **Copy-Anker = Establishment-Ebene** verbatim aus dem gelandeten Desktop; kein „Token abgelehnt".
- **Keine Zeile Bau:** nichts an `strings.xml`/web-ts angefasst — reine Referenz.
