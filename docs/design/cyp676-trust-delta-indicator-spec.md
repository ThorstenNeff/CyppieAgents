# CYP-676 — Trust-Delta-UI-Indikator + Trust-Kommunikations-Paket — Design + Copy Spec

> Owner: UIUX-Designer (Team-2) · Epic CYP-675 (Option-A Remote-Hub) · **S7-unabhängig, standalone** ·
> Stand 2026-07-18 · **Design/Copy, kein Code** (ich spezifiziere/verifiziere, baue nicht — Dev5 baut).
> Gegroundet READ-ONLY gg. develop `025b17ae`.
>
> **Scope (PO-Routing):** ein **kohärentes Trust-Kommunikations-Paket**:
> **(a)** kurze User-Doku-Copy (Docs/Copy-Lane) · **(b)** der Indikator: States + Copy + Form + A11y.
> Dieses Dokument liefert **(b) vollständig** und die **Struktur+Stimme von (a)**; die faktischen
> Sicherheits-Aussagen sind als **PROVISORISCH** markiert, bis die ratifizierte Quelle vorliegt (s. §0).
>
> **Standalone-Komponente:** die Verdrahtung in den Remote-Flow (welcher Enum-Wert die *live* Verbindung
> hat) ist **S7-gated und NICHT Teil davon** — die Komponente nimmt den Tier als Parameter (§6).

---

## 0. Fakten-Quelle & Gating — ✅ §4 FINALISIERT (verbatim gg. cyp638)

Die **faktischen Trust-Boundary-Aussagen** (was das Gateway sieht, was „server-servierte Krypto ohne Pin"
genau bedeutet) sind **Sicherheits-Aussagen** — **nicht aus dem Gedächtnis/paraphrasiert**, sondern **verbatim
gegroundet** auf die ratifizierte Quelle. Dieselbe Disziplin, die ich an gebauten Screens prüfe: **eine
Garantie/Boundary-Aussage nie überstellen, immer gegen eine kontrollierte Wahrheit belegen.**

- **✅ Quelle vorliegend:** `cyp638-remote-hub-trust-boundary-one-pager.md` (PO msg 1527893297, Stand 2026-07-17;
  Grundlage = Backend2 server-truth `cyp638-trust-boundary-artifact.md` §3–§5, code-belegt). **Ratifizierte
  Weiche = Option A** (Browser-Remote nur Self-Hosted; nativ = Strong-Path).
- **✅ §4 gegen diese Fakten FINALISIERT** (nicht mehr provisorisch) — jede Sicherheits-Klausel trägt eine
  **Quellen-Zeile** (§4.1) für den Fakten-Check.
- **Offener Flow (PO-Routing):** §4 final → **Backend2 fakten-checkt gg. server-truth** → **PO reviewt** → PO
  routet die finale Spec an Dev5 (Copy/State-Swap). **Kein Copy-Merge/Bau vor B2-Check + PO-Review.**
- **✅ 3 Design-Entscheidungen PO-RATIFIZIERT** (msg 1527893297): (1) `UNKNOWN` fail-closed Default · (2)
  `BROWSER_GATEWAY` NEUTRAL (nicht rot/amber) · (3) distinkte Domäne `remote.security.tier*`. Alle GO.
- **(a) User-Doku:** eigener Drop **nach** §4-Review, gleiche Stimme wie §4 — gegroundet auf dieselbe cyp638-Quelle.

---

## 1. Abgrenzung — das ist NICHT die Pinning-Zeremonie (kein Duplikat)

Es gibt bereits eine **`remote.connect.trust*`**-Familie (`RemoteConnectTags` / `remote_connect_trust_*`,
CYP-471/480): das ist die **einmalige Pinning-Zeremonie** (OOB-Fingerprint, „Identität pinnen",
provisional-trust). Der CYP-676-Indikator ist **etwas anderes**: eine **persistente Anzeige der
Sicherheits-Stufe der *laufenden* Verbindung**. Damit UI + Tests + Copy die zwei nicht verwechseln:

- **Neue, distinkte Namens-Domäne:** `remote.security.tier*` (Tags) / `remote_security_tier_*` (Keys) —
  **bewusst NICHT** `trust` (das ist die Pinning-Familie). Grep-verifiziert **0-Kollision** @ `025b17ae`.
- Kein neues Affordance-Muster erfunden: die Komponente **reuse't** bestehende Primitive (§3).

---

## 2. State-Modell

PO nennt **2** Stufen (`native`, `browser-gateway`). Ich ergänze **eine dritte, fail-closed** Stufe mit
Begründung (PO-ratifizierbar):

| Tier | Register | Bedeutung | Warum |
|---|---|---|---|
| **`NATIVE`** | **Garantie** | starke E2E: direkt verschlüsselt (Noise-E2E), Hub-Identität gepinnt | die starke Stufe |
| **`BROWSER_GATEWAY`** | **Advisory / informativ** | dokumentiert **schwächer**: Gateway sieht Klartext; server-servierte Krypto ohne unabhängigen Pin | die schwächere, dokumentierte Stufe |
| **`UNKNOWN`** *(ergänzt)* | **fail-closed neutral** | Stufe noch nicht ermittelt (Verbindungsaufbau / unbestimmt) | **Honesty:** die Komponente darf **nie** auf `NATIVE` defaulten, bevor die Stufe feststeht (kein optimistisches Grün). Da das Wiring S7-gated ist, kann die Komponente vor der Auflösung gemountet werden → sie braucht einen ehrlichen Ruhezustand. |

> **✅ PO-RATIFIZIERT (msg 1527893297):** `UNKNOWN` als fail-closed Default = Pflicht (nie optimistisch-NATIVE
> vor Tier-Klarheit). `UNKNOWN` ist der Default-Param (kein Default-Argument auf `NATIVE`); die sichtbare
> `UNKNOWN`-Stufe bleibt (ehrlicher als leer).

---

## 3. Form, Reuse & Platzierung

**Form = kompakter Pill.** Reuse-Disziplin: **spiegele** `WindowBadge.kt:133 Pill(text, container, content,
tag, description)` (clip `RoundedCornerShape(50)`, `background(container)`, `testTag`,
`.semantics(mergeDescendants=true){ contentDescription }`, `labelSmall`) — **nicht divergent neu erfinden**.
Ist `Pill` heute `private`, ist der saubere Move: **in ein geteiltes Primitive heben** (oder struktur-gleich
spiegeln) — kein 2. Pill-Dialekt.

**Non-Farbe-Signal (WCAG 1.4.1) = führender Tone-Glyph**, exakt wie `TonedHint`/`hintGlyph` es etabliert
(Plain-Text-Glyph, **kein Emoji** — CYP-54, Desktop-JVM-sicher). Pro Tier ein **distinkter Glyph-Shape**, der
die Stufe **ohne Farbe** trägt:

| Tier | Glyph | Warum dieser Shape |
|---|---|---|
| `NATIVE` | `●` (gefüllt) | „voll/etabliert" — spiegelt das bestehende **LIVE `●`+primary**-Idiom (`HubConnectTags.kt:73`) |
| `BROWSER_GATEWAY` | `◐` (halb) | „partieller Schutz" — Shape trägt die Halbheit **ohne** Alarm-Farbe. **Bewusst NICHT `▲`** (das ist im Code WARN → würde „etwas ist falsch" implizieren) |
| `UNKNOWN` | `·` (Punkt) | der etablierte GATED/pending-Glyph (`hintGlyph(GATED)`) |

> Die Glyphen `●`/`◐`/`·` sind geometrisch (U+25CF/U+25D0/·), Plain-Text, aus derselben Familie wie das
> bereits ausgelieferte `●`. **Render-Test muss die Glyph-Präsenz + Distinktheit bestätigen** (§7, T5).

**Disclosure-Zeile für `BROWSER_GATEWAY`:** **immer sichtbar** (nicht tap-to-reveal). Einen Downgrade hinter
einen Tap zu verstecken wäre **soft-downplaying** — die schwächere Stufe trägt ihre Caveat-Zeile offen.
Reuse: `TonedHint(text, HintTone.INFO, tag)` — Glyph `i`, `secondary`-Ton. **`INFO`, NICHT `ERROR`/WARN**
(s. §5). Optionales „Mehr erfahren" → verlinkt die User-Doku (a).

**Platzierung (Intent, nicht Wiring):** die **CONNECTED Remote-Kontext-Zeile** (Nachbarschaft von
`RemoteRevokeControl`). Das konkrete Mount/Wiring ist **S7-gated und NICHT mein Teil** — hier nur der
vorgesehene Slot. Die Komponente ist **standalone** (nimmt `tier` als Param, §6).

---

## 4. Copy — DE (Default) + EN · ✅ FINALISIERT (verbatim gg. cyp638, §4.1)

> DE → `app/shared/src/commonMain/composeResources/values/strings.xml` · EN → `…/values-en/strings.xml`.
> **0 Args**, DE==EN Arg-Parität. Apostroph: dieselbe Konvention wie die umgebende recovery/connect-Sektion.
> **Badge-Labels stabil** (kurz, faktisch). Die Detail/Disclosure/a11y-Zeilen sind **verbatim gegen die
> ratifizierten cyp638-Fakten gegroundet** — Quellen-Mapping in **§4.1** für den Backend2-Fakten-Check.

**DE:**
```xml
<!-- CYP-676 Trust-Delta-Indikator. remote.security.tier* — NICHT die Pinning-Familie remote.connect.trust*. -->
<string name="remote_security_tier_native">Ende-zu-Ende</string>
<string name="remote_security_tier_gateway">Browser-Gateway</string>
<string name="remote_security_tier_unknown">Wird geprüft</string>
<string name="remote_security_tier_native_detail">Direkt Ende-zu-Ende verschlüsselt (Noise-E2E), Hub-Identität gepinnt.</string>
<string name="remote_security_tier_gateway_disclosure">Über ein Browser-Gateway verbunden. Anders als bei der nativen Ende-zu-Ende-Verbindung endet die Verschlüsselung am Gateway: es sieht den Datenverkehr mit dem Hub im Klartext, und die Browser-App wird vom Server ausgeliefert — ohne unabhängigen Pin. Dokumentiert schwächer; vorgesehen nur für selbst gehostete Deployments, die dir gehören (Hub und Gateway).</string>
<string name="a11y_remote_security_tier_gateway">Verbindungssicherheit: Browser-Gateway — dokumentiert schwächer als die native Ende-zu-Ende-Verbindung.</string>
<string name="a11y_remote_security_tier_native">Verbindungssicherheit: Ende-zu-Ende, direkt verschlüsselt und Hub-Identität gepinnt.</string>
<string name="a11y_remote_security_tier_unknown">Verbindungssicherheit wird ermittelt.</string>
```

**EN:**
```xml
<string name="remote_security_tier_native">End-to-end</string>
<string name="remote_security_tier_gateway">Browser gateway</string>
<string name="remote_security_tier_unknown">Checking…</string>
<string name="remote_security_tier_native_detail">Directly end-to-end encrypted (Noise-E2E), hub identity pinned.</string>
<string name="remote_security_tier_gateway_disclosure">Connected via a browser gateway. Unlike the native end-to-end connection, the encryption ends at the gateway: it sees the traffic with the hub in cleartext, and the browser app is served by the server — without an independent pin. Documented as weaker; intended only for self-hosted deployments that you own (hub and gateway).</string>
<string name="a11y_remote_security_tier_gateway">Connection security: browser gateway — documented as weaker than the native end-to-end connection.</string>
<string name="a11y_remote_security_tier_native">Connection security: end-to-end, directly encrypted and hub identity pinned.</string>
<string name="a11y_remote_security_tier_unknown">Determining connection security…</string>
```

**Stimme-Regeln (Anti-Hype / Anti-Downplay) — an jeder Zeile geprüft:**
- **Kein „sicher"/„safe" unqualifiziert**, **kein „unsicher/gefährlich/danger"** — Gateway-Copy ist ein
  **faktischer Komparativ** („dokumentiert schwächer"), kein Alarm, kein Beschwichtigen.
- **Kein Hype auf `NATIVE`** (kein „100 % / vollkommen sicher") — nennt den **Mechanismus** (Noise-E2E, Pin),
  keinen Superlativ.
- Gateway nennt **exakt die zwei ratifizierten Residuals** (A2-Hop-Klartext · RR6 server-servierte App ohne Pin)
  **und** die ratifizierte Bedingung (nur operator-owned self-hosted **Deployment — Hub und Gateway**, cyp638
  Präz. i; „Deployment (Hub und Gateway)" **nicht** bloß „Hubs" — sonst läse self-hosted-Hub + Cyppie-Gateway
  [Fall b] fälschlich grün).

### 4.1 Quellen-Mapping (verbatim gg. cyp638 — für den Backend2-Fakten-Check)
| Copy-Klausel | cyp638-Fakt (verbatim-Grundlage) |
|---|---|
| `native_detail`: „Direkt Ende-zu-Ende verschlüsselt (Noise-E2E)" | „Nativ ist Device⇄Hub durchgehend **Noise-verschlüsselt** (E2E)" (§Was strukturell passiert) |
| `native_detail`: „Hub-Identität gepinnt" | „Malicious-CP wird durch client-seitiges **TOFU-Pinning** abgefangen"; Anchor = „shipped Binary + Operator-TOFU-Pin" (§E2E-vs-NOT) |
| `gateway_disclosure`: „die Verschlüsselung endet am Gateway" | „die native E2E-Spanne wird **am Gateway zerschnitten**: `Browser —TLS— Gateway` + `Gateway —(Klartext)— Hub`" |
| `gateway_disclosure`: „sieht den Datenverkehr **mit dem Hub** im Klartext" | **(A2-Hop)** „der Gateway sieht operator↔hub **im Klartext**" (bidirektional → „mit dem Hub", B2-geschärft) |
| `gateway_disclosure`: „die Browser-App wird vom Server ausgeliefert — ohne unabhängigen Pin" | **(RR6)** „die Browser-Krypto/der Trust-Anchor ist **server-serviert** (SPA/JS vom Gateway) ohne native-Pin-Äquivalent" |
| `gateway_disclosure`: „dokumentiert schwächer" | „Browser = **dokumentiert schwächer**, ‚vertraut TLS + der server-servierten SPA'" (Option A) |
| `gateway_disclosure`: „nur für selbst gehostete **Deployments**, die dir gehören **(Hub und Gateway)**" | Präz. i: „gilt NUR wenn **gateway+CP+hub** wirklich **operator-owned** sind" — B2-geschärft: „(Hub und Gateway)" statt bloß „Hubs", damit self-hosted-Hub **+ Cyppie-Gateway** (= Fall b) die Copy NICHT grün liest. (CP als Infra in „Deployment" gefaltet; user-facing Kurzform = B2-ratifiziert.) |

> **B2-Prüf-Fokus (✅ 2026-07-18: alle 🟢, a/b/c 🟢):** stimmt jede rechte Spalte mit der server-truth
> `cyp638-trust-boundary-artifact.md` §3–§5? (a) „Klartext **mit** Hub"=A2-Hop akkurat ✅ · (b) „App vom Server,
> kein Pin"=RR6 akkurat ✅ · (c) Copy impliziert **nirgends** die native Garantie für den Gateway-Fall ✅.
> **B2-Schärfung eingezogen:** Klausel 7 „(Hub und Gateway)" (nicht „Hubs") = verbindliche Freigabe-Bedingung
> Präz. i, kein Fall-b-Grün-Leck; Klausel 4 „mit dem Hub" (bidirektional).

---

## 5. Warum `BROWSER_GATEWAY` NEUTRAL ist, nicht Alarm — die zentrale Honesty-Entscheidung

Der Reflex wäre Rot/Amber. **Falsch — in die *alarmierende* Richtung unehrlich:** die Browser-Gateway-Stufe
ist eine **dokumentierte, self-hosted-only, vom Nutzer bewusst gewählte** schwächere Stufe — **kein Fehler,
keine Gefahr**. Sie **error-rot** (`colorScheme.error`) oder **WARN-`▲`** zu codieren würde „etwas ist kaputt"
suggerieren und **über-alarmieren**. Umgekehrt wäre sie wie `NATIVE` zu behandeln **Downplay**.

**Die ehrliche Mitte:** **neutraler** Ton (`onSurfaceVariant`/`surfaceVariant`), **distinkter Glyph `◐`**
(Shape trägt „partiell"), **immer-sichtbare INFO-Disclosure**. Die **Schwäche wird von Copy + persistenter
Zeile getragen, nicht von einer Alarm-Farbe**. Die Distinktheit zu `NATIVE` ist **real** (anderer Glyph,
anderer Container, offene Caveat-Zeile) — nur **nicht angst-codiert**.

**Register-Trennung (Garantie vs. Advisory) — mein Kern-Invariant:**
- `NATIVE` = **Garantie**-Register (nennt, was garantiert ist).
- `BROWSER_GATEWAY` = **Advisory/informativ**-Register (nennt eine schwächere Realität) — **impliziert nie die
  `NATIVE`-Garantie**. Die zwei sind **visuell + copy-distinkt**, nie vermischt.
- `UNKNOWN` = **fail-closed** (nie optimistisch als `NATIVE` gerendert).

---

## 6. Komponenten-Signatur (standalone) & Seam

```kotlin
// Der Tier-Typ. Die AUTORITATIVE Auflösung (welchen Wert die live Verbindung hat) liegt
// beim Backend/Transport und ist S7-gated — NICHT Teil dieser Story.
enum class RemoteSecurityTier { NATIVE, BROWSER_GATEWAY, UNKNOWN }

@Composable
fun RemoteSecurityTierBadge(
    tier: RemoteSecurityTier = RemoteSecurityTier.UNKNOWN,  // fail-closed Default — NIE NATIVE
    modifier: Modifier = Modifier,
)
```

- **Standalone:** nimmt `tier` rein; **kein** Netz/Flow-Coupling in dieser Komponente. Das Mapping
  „live-Verbindung → Tier" ist der **S7-Seam** (Backend/Transport-Wahrheit), analog wie `TTL_HINT` in
  `RemoteRevokeControl` seam-gated war. **Nicht mein Teil.**
- **Fail-closed Default:** unaufgelöst ⇒ `UNKNOWN`, **nie** `NATIVE`.

---

## 7. Tags — neues `RemoteSecurityTierTags` (Area `remote.security.tier*`)

```kotlin
object RemoteSecurityTierTags {
    const val BADGE = "remote.security.tierBadge"           // der Pill (immer präsent)
    const val DISCLOSURE = "remote.security.tierDisclosure" // die BROWSER_GATEWAY-INFO-Zeile (present-iff gateway)
    fun tier(id: String) = "remote.security.tier.$id"       // id ∈ {native, browserGateway, unknown}
}
```
**3 Tag-Formen, `remote.security.tier*`, 0-Kollision @ `025b17ae` (grep-belegt).** `tier(id)` gibt den
**diskriminierenden** present-iff-Tier-Anker (genau **ein** Tier-Tag präsent) für die Verify-Teeth.

---

## 8. A11y

- **Nicht-nur-Farbe (WCAG 1.4.1):** Glyph-Shape **+** Label-Text tragen beide die Stufe → Farbe nie alleiniger
  Träger. Explizit.
- **Kontrast (rollen-abhängig):** das **Badge-Label** ist **Text-Rolle** ⇒ **≥ 4.5:1** Content-auf-Container;
  der **Glyph** ist grafisches Tone-Signal ⇒ **≥ 3:1**. **⚠ Prüfen:** `onSurfaceVariant`-auf-`surfaceVariant`
  erreicht für **Text** evtl. **nicht 4.5:1** — dann **`onSurface`-auf-`surfaceVariant`** fürs Label (Glyph darf
  `onSurfaceVariant` bleiben). Im Render/Contrast-Check je Element messen (headless für Token-Auflösung; exakter
  Pixel = guided-human).
- **Screen-Reader:** `.semantics(mergeDescendants=true){ contentDescription = <a11y-Key> }` (spiegelt `Pill`) —
  die a11y-Strings **buchstabieren die Stufe aus** (nicht nur den Glyph): „…Browser-Gateway — dokumentiert
  schwächer als Ende-zu-Ende."

---

## 9. Acceptance-Teeth (meine §-QA, headless jvmTest, sobald gebaut)

Diskriminierend formuliert (jeder Test schließt eine *falsche* Impl aus, nicht nur „wird er rot"):

1. **`tier = NATIVE`** → `BADGE` + `tier("native")` präsent; `tier("browserGateway")` **und** `tier("unknown")`
   **absent**; Label == `remote_security_tier_native`; Glyph `●` präsent.
2. **`tier = BROWSER_GATEWAY`** → `tier("browserGateway")` präsent; `DISCLOSURE` präsent **und
   immer-sichtbar** (nicht hinter Interaktion); Disclosure-Text == `remote_security_tier_gateway_disclosure`
   (beweist: die **ehrliche** Copy ist die gezeigte, kein generischer String); `tier("native")` **absent**.
3. **`tier = BROWSER_GATEWAY`** → Ton **neutral** (`onSurfaceVariant`/`surfaceVariant`), **kein**
   `colorScheme.error`, **kein** `▲`-WARN-Glyph. (schließt Über-Alarmierung aus)
4. **Default/unaufgelöst** (`RemoteSecurityTierBadge()` ohne Tier) → resolves zu **`UNKNOWN`**, **nie**
   `NATIVE`; `tier("native")` **absent**. (schließt optimistisches-Grün-Default aus — die schärfste Zahn)
5. **Glyph-Distinktheit:** die drei Tier-Glyphen sind **verschieden** und **präsent** (Non-Farbe-Signal, WCAG
   1.4.1); a11y-`contentDescription` buchstabiert die Stufe (nicht nur der Glyph).
6. **Kontrast:** Label-Content-auf-Container **≥ 4.5:1**, Glyph **≥ 3:1** (headless, soweit Token auflösbar;
   exakter Pixel = guided-human, dann als solches deklariert — keine Pixel-Behauptung ohne Bestätigung).
7. **EN-Parität:** alle 3 Tiers DE+EN, 0-Arg-Parität; kein fehlender Key.

**Tool-Grenze (ehrlich):** T1–T5, T7 sind **headless-messbar**. T6-exakt-Kontrast/Pixel ist **guided-human**
(ich messe Token-Auflösung headless; die endgültige Pixel-/Farb-Bestätigung braucht einen beobachteten Lauf).

---

## 10. Shared-Key-Sync-Flag (Pflicht-Hinweis)

Die 8 Keys (`remote_security_tier_*` + `a11y_*`) und `RemoteSecurityTierTags` sind **geteilte Ressourcen** →
sie **landen MIT Dev5s Impl** (nicht vorab von mir), sonst driftet der Shared-Check. **Timing:** die Keys mit
dem konsumierenden Bau (Dev5) einspielen. Ich liefere sie paste-ready; **das Landen ist Dev-Seite** — das ist
meine stehende Disziplin (Keys entwerfe ich, landen tut sie die Impl).

---

## 11. Self-Validation

- **Struktur bau-fertig:** States (§2) · Form/Reuse (§3, `Pill`+`TonedHint`) · Signatur/Seam (§6) ·
  Tags 0-Kollision (§7) · A11y rollen-abhängiger Kontrast (§8) · diskriminierende Verify-Teeth (§9).
- **Gegroundet** @ `025b17ae`: `remote.connect.trust*` (Pinning ≠ dies, §1) · `Pill`/`WindowBadge.kt:133` ·
  `TonedHint`/`HintTone.INFO`/`hintGlyph` · LIVE-`●`-Idiom · `remote_connect_mode_sub` (Noise-E2E-Sprache) ·
  `remote.security.tier*` grep-frei (0-Kollision).
- **Honesty-Kern (§5):** Garantie- vs. Advisory-Register distinkt · `BROWSER_GATEWAY` neutral (nicht
  alarm-rot, nicht downplay) · `UNKNOWN` fail-closed (nie optimistisch `NATIVE`) · Anti-Hype/Anti-Downplay
  an jeder Copy-Zeile.
- **✅ §4 FINALISIERT verbatim gg. cyp638** (Quellen-Mapping §4.1 je Klausel → A2-Hop/RR6/Präz.i). Offener
  Flow: **B2-Fakten-Check → PO-Review → Dev5**; **kein Copy-Merge davor** (§0). **User-Doku (a) folgt als
  eigener Drop nach dem §4-Review** — gleiche Stimme, dieselbe cyp638-Quelle.
- **✅ 3 Design-Entscheidungen PO-ratifiziert** (UNKNOWN-fail-closed · BROWSER_GATEWAY-neutral · Domäne
  `remote.security.tier*`).
- **Reuse-first, kein Duplikat:** neue Namens-Domäne `remote.security.tier*` bewusst getrennt von der
  Pinning-`trust`-Familie; Pill+TonedHint gespiegelt, kein divergenter Dialekt.
- Kein Bau; docs-only auf `feature/CYP-676-trust-delta-indicator-spec` (Basis `025b17ae`).
