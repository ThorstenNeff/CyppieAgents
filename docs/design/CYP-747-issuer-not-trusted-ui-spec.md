# CYP-747 / Model-2 — `owned-but-issuer-not-trusted`: Trust-State-UI Design-Pass

> Owner: UIUX-Designer · **Design-only, kein Bau** (Bau erst nach Backend-S1-State + PO/Reviewer-Ratifikation) ·
> Stand develop `5e2294e0` · Input: Dev-Seam-Analyse `cyp747-trust-ui-seam.md` (`0b2b76ca`).
> **PO-Grenzen (verbindlich, 2026-07-21):** ① fail-closed (Operator-Autorität NICHT gewährt bis Issuer-Trust
> etabliert) · ② connect-time-only zuerst (per-single-active-hub) · ③ **keine** In-UI-„Issuer-trauen"-Aktion
> (OOB/PO-HALT). PO-Bestätigung: **Hard-Block-Terminal als Slice-1.**
> Verwandt: Cyp443-Trust-Achsen-Firewall · CYP-789 „marked, not hidden" · `RemoteConnectTags.error(cause)` ·
> `A11Y-ANNOUNCEMENTS.md`.

---

## 1. Die dritte Trust-Achse (was hier NICHT passiert)

Der Client modelliert heute **zwei bewusst getrennte** Trust-Achsen, firewalled durch
`Cyp443TrustAxisSeparationGuardTest`:

- **(a) Hub-Key-Trust** — `TrustResolution` (TOFU des Noise-DH-Static-Key), `TofuHubTrust.resolve()`.
- **(b) Operator-Identität** — `OperatorAuthOutcome` / `OperatorAuthError`.

`owned-but-issuer-not-trusted` ist eine **DRITTE** Achse: das **Vertrauen zum Aussteller** (`iss`, CP-JWT /
Relay-Key; `CpJwtProvider`, `HttpCpJwtProvider`). Der Hub ist an der Control-Plane **admittiert/owned**, aber
es ist **kein vertrauenswürdiger Aussteller am Hub etabliert/gepinnt**.

> **Anker-Ebene, nicht Token-Ebene (Backend-Route-#1, code-belegt 2026-07-21):** §5-C2 = ZWEI unabhängige
> Kanten — `owns-hub` **∧** `hub-trusts-issuer`. Der Zustand sitzt auf der **Trust-ESTABLISHMENT-Ebene**
> („kein vertrauenswürdiger Aussteller/Relay-Key am Hub gepinnt"), **UPSTREAM** des per-Token-CpJwt-Verify —
> **KEIN** „Token abgelehnt"-Sub-Case (die Krypto bleibt atomar: `iss`+`sub` in EINEM Token). Issuer-Trust ⊥
> Operator-Id ⊥ Hub-Pin (ein *anderer* Operator kann vom *selben* Aussteller bezeugt sein; ein *rotierter*
> Aussteller-Key bezeugt *denselben* Operator). ⟹ Die Copy formuliert den **Anker-Zustand** („kein Aussteller
> etabliert"), **nie** eine Token-Ablehnung — das trägt zugleich die WARN-amber-Klassifikation (§3).

> **Diese Achse wird NICHT in (a) oder (b) gefaltet.** Das neue `RemoteFailure.IssuerNotTrusted` referenziert
> **weder** `TrustResolution` **noch** `OperatorAuth*`-Typen — es trägt seine Issuer-Daten als **eigenes** Feld
> (§4). Der Cyp443-Guard bleibt grün. **Das ist keine Kosmetik: die Achsen-Trennung ist die
> Sicherheits-Invariante** — Hub-Key-Trust sichert den *Kanal*, der Aussteller vouched das
> *Operator-Credential*; sie dürfen sich nicht gegenseitig „heilen".

**Kanal vs. Autorität — warum das die Surface-Entscheidung trägt:** die **Kanal**-Authentizität ruht auf
Achse (a) (Noise-DH-TOFU, an einen gepinnten Key). Achse (c) (Aussteller) betrifft, ob das
**Operator-Berechtigungs-Credential** von einem vertrauten Aussteller stammt. Ein untrusted Aussteller
bedeutet: **wir können die Operator-Autorität nicht vouchen** → sie wird fail-closed **nicht erteilt**.

---

## 2. Surface-Entscheidung: **Hard-Block-Terminal** (Slice-1) — Begründung

**Empfehlung (PO-bestätigt): ein neuer terminaler `RemoteFailure.IssuerNotTrusted`, gerendert in
`RemoteFailureView` — fail-closed, kein Session-Aufbau.**

| Kriterium | Hard-Block-Terminal (gewählt) | Degraded-but-connected-Observer (spätere Slice) |
|---|---|---|
| **Fail-closed (①)** | **by construction** — kein CONNECTED, keine Session ⟹ Autorität kann **nicht** lecken | erfordert **per-Affordance-Withholding** jeder operator-gated Aktion; **eine vergessene = Leak** |
| **Flächenkosten (②)** | **eine** Failure-Variante + **ein** `RemoteFailureView`-Zweig | großes Refactor: jede operator-Autorität sichtbar-entzogen + Banner + Read-Only-Modus |
| **③ OOB, kein Grant** | trivial (kein Button, terminal) | ebenfalls möglich, aber mehr Oberfläche, mehr Grant-Verwechslungsrisiko |
| **Ehrlichkeit** | eine klare Wand + Grund + OOB-Weg | Read-Access erhalten, aber Operator könnte „withheld" mit „kaputt" verwechseln |

> **Der entscheidende Punkt für ①:** Hard-Block ist nicht nur billiger — er ist **sicherer auf genau der
> Sache, die der PO zu flaggen bat.** Weil **keine Session** entsteht, gibt es **nichts**, das Autorität
> granten könnte. Beim Degraded-Modus lebt das Leak-Risiko in jeder einzeln zu entziehenden
> Affordance. Deshalb Hard-Block zuerst; Degraded ist die reichere, teurere, **später** zu härtende Slice
> (§7), falls „Beobachten während OOB-Auflösung" je Produktziel wird.

**Kein `IssuerNotTrusted`-Grant, nirgends.** Es gibt keine `viewModel::trustIssuer`-Methode und darf keine
geben. Der einzige Ausgang ist **OOB-Auflösung** (PO/Mensch etabliert Aussteller-Trust) **und dann ein
frischer Connect** — nicht ein In-UI-Klick. *(Falls je ein Grant-Pfad auftaucht: HALT + Eskalation ans PL.)*

---

## 3. Tonalität, Glyph, Copy — konsistent mit der Trust-Verify-OOB-Familie

`RemoteFailureView` kennt zwei terminale Grammatiken (am Code verifiziert):
- **Verify-OOB, WARN-amber `▲`** — `TrustChanged` / `TrustRejected`: „nicht kaputt, außerhalb der App
  verifizieren, terminal, **kein** Retry."
- **error-red `HintTone.ERROR`** — `AuthRejected`: „der Hub hat dich abgelehnt."

`IssuerNotTrusted` gehört in die **erste** Grammatik (**WARN-amber `▲`**): der Hub ist **owned/erreichbar**,
er hat **nicht** abgelehnt, und **wir** können den Aussteller nicht vouchen — eine **Verify-OOB-Trust-Entscheidung**,
kein Systemfehler und kein „denied". `error-red` wäre eine **Fehl-Klassifikation** (impliziert kaputt/abgelehnt).

> **Kein Pre-Read-Konflikt mit `TrustChanged`/`TrustRejected`,** obwohl alle drei WARN-amber `▲` tragen: sie
> sind **dieselbe Klasse** (terminaler Trust-Block, „verifiziere OOB, handle nicht in der App") mit
> **derselben Handlung**. Die spezifische Ursache trägt die **Copy** + der **Tag** — exakt wie
> `TrustChanged` vs `TrustRejected` heute schon dieselbe Grammatik teilen und sich nur in Copy/Tag
> unterscheiden. Der Operator muss sie nicht auf einen Blick trennen; alle drei sagen „stopp, verifiziere OOB".

**Kein Retry-Button** (wie `TrustChanged`/`TrustRejected`, **anders** als `DeviceNotEnrolled`): ein Retry
**vor** OOB-Auflösung träfe denselben untrusted Aussteller → futil. Ein Button würde eine klickbare Auflösung
**vortäuschen**, die es nicht gibt. Ausgang = normale „zurück zur Hub-Liste"-Navigation (kein Grant, kein
Retry); nach OOB-Auflösung durchläuft der frische Connect die Aussteller-Prüfung erneut.

### 3.1 Keys (Vorschlag — Wortlaut mit Backend-§5-C2-Begriff final abgleichen)

| Key | DE | EN |
|---|---|---|
| `remote_connect_issuer_not_trusted` | Verbindung angehalten: Dieser Hub ist registriert, aber es ist kein vertrauenswürdiger Aussteller an ihm etabliert. Ohne etabliertes Aussteller-Vertrauen wird keine Operator-Berechtigung erteilt. | Connection halted: this hub is registered, but no trusted issuer is established for it. Without established issuer trust, no operator authority is granted. |
| `remote_connect_issuer_oob` | Ein vertrauenswürdiger Aussteller wird **außerhalb der App** am Hub etabliert (durch den Betreiber/PO). Danach erneut verbinden. | A trusted issuer is established for the hub **outside the app** (by the operator/PO). Reconnect afterwards. |
| `a11y_remote_connect_issuer_not_trusted` | Verbindung angehalten: kein vertrauenswürdiger Aussteller am Hub etabliert. Keine Operator-Berechtigung. Aussteller-Vertrauen wird außerhalb der App etabliert; danach erneut verbinden. | Connection halted: no trusted issuer established for the hub. No operator authority. Issuer trust is established outside the app; reconnect afterwards. |

**Wortlaut-Begründung:**
- **Nennt den Anker-Zustand ehrlich:** „registriert, aber kein vertrauenswürdiger Aussteller etabliert" —
  nicht „kaputt", nicht „abgelehnt", **nicht „Token abgelehnt"** (Backend-Route-#1: Establishment-Ebene,
  nicht Token-Ebene, §1). Der Hub ist legitim; die Lücke ist ein **nicht etabliertes Aussteller-Vertrauen**.
- **Nennt die Fail-closed-Folge explizit:** „keine Operator-Berechtigung" — der Operator sieht, dass
  Autorität **entzogen** ist (nicht still fehlt) = „marked, not hidden" (CYP-789).
- **`remote_connect_issuer_oob` ist ein HINWEIS, kein Button** — mirror von `trustChangedRepin`
  („purely informational OOB hint, NOT an action button"). Keine Handlungsverben Richtung App.
- **„Aussteller" ankerbar (§3.1-offener-Punkt ZU, Backend-Route-#1):** der operator-lesbare Begriff für die
  **Aussteller-Anker/Pin-Kante** — „etabliert/gepinnt", **nie** „Token abgelehnt". Backend hat „3. Achse
  firewalled" code-belegt bestätigt; der Wortlaut ist damit final verankert (kein „CA"/„Token"-Framing).
- **`%`-Platzhalter optional** für den Aussteller-Bezeichner, falls Backend ihn liefert (§4): dann Vollform
  „…es ist kein vertrauenswürdiger Aussteller %1$s etabliert…"; fehlt er → obige generische Form
  (**`null ≠ fabriziert`**).

### 3.2 Ansage-Dringlichkeit

`IssuerNotTrusted` ist das **Ergebnis eines abgeschickten Connect-Versuchs** → nach `A11Y-ANNOUNCEMENTS.md §1`
**`Assertive`** (der Operator hat den Connect ausgelöst und wartet auf sein Ergebnis) — konsistent mit den
übrigen `RemoteFailureView`-Endzuständen (`RemoteOperatorAuthSteps`/`RemoteFailure` sind Assertive-Ergebnisse).

---

## 4. Einhängung — Typ, Tag, Reducer (am Code verifiziert)

**(1) Neue `RemoteFailure`-Variante** (`net/hub/remote/RemoteSessionState.kt`), firewall-sauber:

```kotlin
/** CYP-747 — the hub is CP-owned, but its CP-JWT/cert ISSUER is not trusted (a THIRD trust axis, Cyp443).
 *  Terminal, fail-closed: no session, no operator authority. Resolution is OOB (PO/human), then a fresh connect.
 *  Carries ONLY its own issuer identifier — references NEITHER TrustResolution NOR OperatorAuth types. */
data class IssuerNotTrusted(val issuer: String? = null) : RemoteFailure
```

- `issuer: String?` = optionaler Anzeige-Bezeichner (Backend liefert ihn, falls verfügbar; sonst `null` →
  generische Copy, **keine erfundene Nummer**). Eigenes Feld, **kein** geliehener Typ aus (a)/(b).

**(2) `RemoteFailureView`-Zweig** (`connect/HubConnectSelection.kt`), mirror der `TrustRejected`-Grammatik:

```kotlin
is RemoteFailure.IssuerNotTrusted -> Column(
    modifier = Modifier.fillMaxWidth().testTag(RemoteConnectTags.error("issuerNotTrusted"))
        .semantics { contentDescription = a11y },   // Assertive live-region am Container
    verticalArrangement = Arrangement.spacedBy(6.dp),
) {
    Row(...) { Text("▲ ", color = severityColor(Severity.WARN))
               Text(stringResource(Res.string.remote_connect_issuer_not_trusted),
                    color = severityColor(Severity.WARN), style = bodySmall) }
    // OOB-HINWEIS, KEIN Button (mirror trustChangedRepin):
    Text(stringResource(Res.string.remote_connect_issuer_oob),
         style = bodySmall, color = onSurfaceVariant,
         modifier = Modifier.testTag("remote.connect.issuerOob"))
}
```
- **Kein Retry-, kein Grant-Button** (terminal, OOB).

**(3) `surfaceRemote()` — KEIN neuer Prioritäts-Zweig nötig.** `IssuerNotTrusted` ist ein **schlichter
terminaler Failure** wie `TrustChanged`/`AuthRejected`: er reitet den bestehenden
`else -> RemoteConnecting(hub, rs)`-Pfad (`rs.failure` gesetzt) → `RemoteFailureView` schaltet darauf.
**Nur** `DeviceNotEnrolled` brauchte einen Sonderzweig (Routing zum Enroll) — hier nicht. *(Macht die Slice
billiger als Dev's Q2 als Obergrenze skizzierte.)*

**(4) Tag:** `RemoteConnectTags.error("issuerNotTrusted")` → `"remote.connect.error.issuerNotTrusted"`, plus
`"remote.connect.issuerOob"` für den OOB-Hinweis. Frozen `error(cause)`-Taxonomie ist das etablierte Zuhause;
Segment-Charset konform. **Shared API mit QA (CYP-7) — über den PO koordinieren** (wie die CYP-460/471-Tags).

**(5) Detektions-Ebene (Backend-S1, nicht meine Lane):** Backend-Route-#1 verortet den Verdikt auf der
**Establishment-Ebene** — „kein vertrauenswürdiger Aussteller am Hub etabliert/gepinnt", **UPSTREAM** des
per-Token-CpJwt-Verify (kein Token-Reject-Sub-Case). Das UI ist **ebenen-/phasen-agnostisch** — es rendert,
sobald `rs.failure = IssuerNotTrusted` gesetzt ist. WO/WIE Backend die **Anker-Bestimmung** baut = §5-C2 (S1
im Bau).

---

## 5. Fail-closed-Beweis (die Zähne für die spätere §-QA)

1. **Kein CONNECTED, keine Session** — `IssuerNotTrusted` ist terminal; die State-Machine erreicht nie
   `RemoteConnState.CONNECTED` ⟹ es existiert **kein** operierbarer Kontext ⟹ Operator-Autorität ist
   **strukturell** nicht erteilbar (①, by construction).
2. **Kein Grant-Pfad** — keine `trustIssuer`-Methode, kein Grant-Button; der einzige Ausgang ist OOB +
   frischer Connect (③).
3. **Achsen-Firewall — und der Bau-Zahn dahinter** — `IssuerNotTrusted` referenziert keine
   `TrustResolution`/`OperatorAuth*`-Typen. **⚠ Reviewer-bestätigt (2026-07-21): der `Cyp443`-Guard ist heute
   2-von-3 (blind für die Issuer-Achse).** „Dritte Achse firewalled" ist **Prosa, bis der Guard die
   Issuer-Anker/Pin-Kante real rötet** — beim Bau ist der Tripwire auf genau diese **Aussteller-Anker/Pin-Heimat**
   (⊥ a/b) zu erweitern. **Das ist ein Ehrlichkeits-Zahn** (PO gatet, ich verifiziere beim §-QA, dass die
   „firewalled"-Aussage tatsächlich stimmt), nicht bloße Bau-Hygiene.
4. **Marked, not hidden** (CYP-789) — der Zustand wird **explizit** gerendert (eigener Tag, eigene Copy,
   Grund + OOB-Weg), nie als stiller/generischer Failure und nie im vertrauten Look.
5. **`null ≠ fabriziert`** — fehlt der Aussteller-Bezeichner, generische Copy, keine Platzhalter-Nummer.

> **Was ich flagge (PO bat darum):** dieses Design **grantet nichts.** Sollte im Bau **irgendetwas** Autorität
> trotz untrusted Aussteller erteilen (ein Grant-Button, ein Degraded-Modus ohne Withholding, ein
> CONNECTED-trotz-IssuerNotTrusted), ist das die HALT-Grenze → an dich, du eskalierst ans PL.

---

## 6. ⟂ Backend-Naht (S1) — was ich konsumiere, was ich nicht entscheide

| Ich konsumiere | Backend (§5-C2 / S1) entscheidet |
|---|---|
| `RemoteFailure.IssuerNotTrusted(issuer?)` als terminalen Verdikt | **WANN** der Aussteller geprüft wird (Phase) + **WIE** „trusted" definiert ist |
| den optionalen `issuer`-Anzeige-String | ob/welcher Aussteller-Bezeichner geliefert wird |
| die Garantie, dass der Verdikt **terminal** ist (kein CONNECTED) | die State-Machine-Verdrahtung, die den Verdikt setzt |

**Offene Backend-Sanity (Reviewer/PL, vom PO angestoßen):** falls Backends §5-C2-Semantik bedeutet, dass ein
untrusted Aussteller **auch die Kanal-/Read-Trust** kompromittiert (nicht nur das Operator-Credential), dann
ist Hard-Block **zwingend** (nicht nur bevorzugt) und der Degraded-Observer (§7) **entfällt** als Option.
Bestätigt Backend hingegen „Kanal via (a) sicher, nur Aussteller-Vouching fehlt", bleibt Degraded eine
legitime spätere Slice. **Diese Kanal-vs-Autorität-Frage bitte im Reviewer-Sanity mitklären.**

---

## 7. Bewusst spätere Slices (nicht Slice-1, geflaggt — keine stille Kürzung)

- **Degraded-but-connected-Observer** (Read-Access während OOB-Auflösung): erfordert per-Affordance-
  Autorität-Withholding (jede operator-gated Fläche sichtbar-entzogen, „marked not hidden") + einen
  persistenten Banner in `RemoteOperatingChrome`. **Größer + Leak-Risiko** ⟹ eigene Slice mit eigener §-QA,
  **nur** falls „Beobachten während OOB" Produktziel wird **und** Backend Kanal-Trust bestätigt (§6).
- **List-time per-Row-Badge** (② deferred): „owned but issuer untrusted" auf `HubRow` **vor** Connect braucht
  ein **neues `HubDescriptor`-Feld** ⟹ **CP-DTO-Vertragsänderung** (`ControlPlaneClient.hubs()`) + neuen
  Per-Row-Tag neben `HubConnectTags.hubPresence(hubId)`. Heute existiert **keine** Per-Row-Trust-Affordanz;
  Trust ist per-single-active-hub. Net-new, spätere Slice.

---

## 8. Self-Validation

- **Am echten Code geerdet:** `RemoteFailure`-Taxonomie (`RemoteSessionState.kt`), `RemoteFailureView`-Zweige
  (`HubConnectSelection.kt:329+`), `surfaceRemote()`-Reducer (`HubConnectViewModel.kt:320+`),
  `RemoteConnectTags.error(cause)`, der `trustChangedRepin`-Hinweis-nicht-Button-Präzedenzfall — alle gelesen,
  nicht angenommen.
- **PO-Grenzen 1:1 umgesetzt:** ① fail-closed **by construction** (terminal, keine Session); ② connect-time-only
  (per-single-active-hub, List-Badge deferred §7); ③ **kein** Grant — nur OOB-Hinweis (mirror `trustChangedRepin`).
- **Achsen-Firewall respektiert (Cyp443):** eigene Variante, eigenes `issuer`-Feld, **keine** Referenz auf
  (a)/(b)-Typen — die Trennung als Sicherheits-Invariante benannt, nicht nur als Konvention.
- **Ton aus dem Bestand abgeleitet:** WARN-amber `▲` (Verify-OOB-Familie), begründet **warum nicht** error-red;
  Pre-Read-Nicht-Konflikt mit `TrustChanged`/`TrustRejected` ausdrücklich adressiert (gleiche Klasse, Copy/Tag
  trägt die Ursache).
- **Fail-closed-Zähne + Grant-HALT explizit** (§5) — genau der „flag anything that grants authority"-Auftrag.
- **Kanal-vs-Autorität-Sicherheitsfrage an Reviewer/PL geflaggt** (§6) statt selbst über die Grenze zu
  entscheiden — die Surface-Wahl hängt an einer Backend-Semantik, die nicht meine ist.
- **Spätere Slices geflaggt, nicht still gekürzt** (§7): Degraded-Observer + List-Badge mit ihren echten Kosten
  (per-Affordance-Withholding · CP-DTO-Change).
- **`null ≠ fabriziert`, `marked not hidden`** durchgezogen (§3.1/§5).
- **Keine Zeile Bau:** `strings.xml`/Tags/Kotlin (Dev-Lane) nicht angefasst — dieses Dokument ist die Referenz;
  die Snippets sind illustrativ (Skizzen), nicht committed. Bau erst nach S1-State + Ratifikation.
