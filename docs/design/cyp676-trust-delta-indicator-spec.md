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

## 0. Fakten-Quelle & Gating (WICHTIG — vor Copy-Finalisierung)

Die **faktischen Trust-Boundary-Aussagen** (was das Gateway sieht, was „server-servierte Krypto ohne Pin"
genau bedeutet) sind **Sicherheits-Aussagen** — die schreibe ich **nicht aus dem Gedächtnis/paraphrasiert**,
sondern **verbatim gegroundet** auf die ratifizierte Quelle. Das ist dieselbe Disziplin, die ich an gebauten
Screens prüfe: **eine Garantie/Boundary-Aussage nie überstellen, immer gegen eine kontrollierte Wahrheit
belegen.**

- **Quelle:** `cyp638-remote-hub-trust-boundary-one-pager.md` (PO liefert an) — die ratifizierten Fakten.
- **Fakten-Check:** Backend2 gegen die Server-Truth · **Review:** PO.
- **Konsequenz für dieses Dokument:** die **Struktur, States, Form, A11y, Tags, Seam, Verify-Teeth** stehen
  fest (aus Code-Truth gegroundet). Die **Copy-Strings mit Sicherheits-Fakten** (§4, native/gateway-Detail +
  Gateway-Disclosure) sind **PROVISORISCH** — Platzhalter in der richtigen Stimme, die **1:1 gegen das
  One-Pager finalisiert** werden, sobald es vorliegt. **Kein Merge/Bau der Copy vor B2-Fakten-Check + PO-Review.**
- **(a) User-Doku:** schreibe ich in einem **eigenen Drop**, sobald das One-Pager da ist (gleiche Stimme wie §4).
  → **Ich bitte um das One-Pager** (PO-Angebot „sag, wenn du's brauchst"): **ja, bitte anhängen.**

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

> **`UNKNOWN` ist der Default** (kein Default-Argument auf `NATIVE`). Wenn PO die 3. Stufe nicht will, ist der
> **fail-closed-Default trotzdem Pflicht** — dann rendert die Komponente bei unbekanntem Tier schlicht nichts
> Bestätigendes (nie `NATIVE`). Empfehlung: die sichtbare `UNKNOWN`-Stufe **behalten** (ehrlicher als leer).

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

## 4. Copy — DE (Default) + EN · **§4-Sicherheits-Fakten = PROVISORISCH (s. §0)**

> DE → `app/shared/src/commonMain/composeResources/values/strings.xml` · EN → `…/values-en/strings.xml`.
> **0 Args**, DE==EN Arg-Parität. Apostroph: dieselbe Konvention wie die umgebende recovery/connect-Sektion.
> **Badge-Labels sind stabil** (kurz, faktisch). **Die mit ⚠FAKT markierten Detail/Disclosure-Zeilen sind
> Platzhalter in der richtigen Stimme — 1:1 gegen `cyp638`-One-Pager finalisieren + B2-Fakten-Check.**

**DE:**
```xml
<!-- CYP-676 Trust-Delta-Indikator. remote.security.tier* — NICHT die Pinning-Familie remote.connect.trust*. -->
<string name="remote_security_tier_native">Ende-zu-Ende</string>
<string name="remote_security_tier_gateway">Browser-Gateway</string>
<string name="remote_security_tier_unknown">Wird geprüft</string>
<!-- ⚠FAKT (provisorisch, gg. cyp638 finalisieren): -->
<string name="remote_security_tier_native_detail">Direkt Ende-zu-Ende verschlüsselt (Noise-E2E), Hub-Identität gepinnt.</string>
<string name="remote_security_tier_gateway_disclosure">Über ein Browser-Gateway verbunden. Das Gateway sieht den Datenverkehr im Klartext; die Verschlüsselung wird vom Server bereitgestellt, ohne unabhängigen Pin. Dokumentiert schwächer als die direkte Ende-zu-Ende-Verbindung — vorgesehen für selbst gehostete Hubs.</string>
<string name="a11y_remote_security_tier_gateway">Verbindungssicherheit: Browser-Gateway — dokumentiert schwächer als Ende-zu-Ende.</string>
<string name="a11y_remote_security_tier_native">Verbindungssicherheit: Ende-zu-Ende, direkt verschlüsselt und Hub-Identität gepinnt.</string>
<string name="a11y_remote_security_tier_unknown">Verbindungssicherheit wird ermittelt.</string>
```

**EN:**
```xml
<string name="remote_security_tier_native">End-to-end</string>
<string name="remote_security_tier_gateway">Browser gateway</string>
<string name="remote_security_tier_unknown">Checking…</string>
<!-- ⚠FAKT (provisional, finalize against cyp638): -->
<string name="remote_security_tier_native_detail">Directly end-to-end encrypted (Noise-E2E), hub identity pinned.</string>
<string name="remote_security_tier_gateway_disclosure">Connected via a browser gateway. The gateway sees the traffic in cleartext; the encryption is served by the server, without an independent pin. Documented as weaker than the direct end-to-end connection — intended for self-hosted hubs.</string>
<string name="a11y_remote_security_tier_gateway">Connection security: browser gateway — documented as weaker than end-to-end.</string>
<string name="a11y_remote_security_tier_native">Connection security: end-to-end, directly encrypted and hub identity pinned.</string>
<string name="a11y_remote_security_tier_unknown">Determining connection security…</string>
```

**Stimme-Regeln (Anti-Hype / Anti-Downplay) — an jeder Zeile geprüft:**
- **Kein „sicher"/„safe" unqualifiziert**, **kein „unsicher/gefährlich/danger"** — Gateway-Copy ist ein
  **faktischer Komparativ** („dokumentiert schwächer"), kein Alarm, kein Beschwichtigen.
- **Kein Hype auf `NATIVE`** (kein „100 % / vollkommen sicher") — nennt den **Mechanismus** (Noise-E2E, Pin),
  keinen Superlativ.
- Gateway nennt **exakt was schwächer ist** (Gateway sieht Klartext · server-servierte Krypto · kein
  unabhängiger Pin) **und** den vorgesehenen Kontext (self-hosted) — **das sind die ⚠FAKT-Zeilen** (§0).

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
- **PROVISORISCH & korrekt gegated:** die §4-Sicherheits-Fakten-Zeilen finalisiere ich gegen
  `cyp638`-One-Pager (B2-Fakten-Check + PO-Review); **kein Copy-Merge davor** (§0). **User-Doku (a) folgt als
  eigener Drop**, sobald das One-Pager vorliegt — gleiche Stimme wie §4.
- **Reuse-first, kein Duplikat:** neue Namens-Domäne `remote.security.tier*` bewusst getrennt von der
  Pinning-`trust`-Familie; Pill+TonedHint gespiegelt, kein divergenter Dialekt.
- Kein Bau; docs-only auf `feature/CYP-676-trust-delta-indicator-spec` (Basis `025b17ae`).
