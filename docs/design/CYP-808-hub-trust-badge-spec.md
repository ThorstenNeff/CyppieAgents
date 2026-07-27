# CYP-808 — Compose/Desktop Hub-Trust-Badge + TrustTone-Helper (Design-Spec)

> Owner: UIUX · **Design-Spec, kein Bau** (Dev baut → mein Gate). Stand develop `18fc52fd`.
> Aktiviert durch **Multi-Hub (Option A, Auftraggeber)**: per-Hub-Trust-Visibility wird zentral — der Multi-Hub-Client
> **CYP-832** braucht diese Sichtbarkeits-Schicht. Auch single-hub nützlich (nicht gated).
> **Contract-Quelle:** `:core/model/HubTrust.kt` (`HubTrustState`, Achse a) — Compose UND web-ts sprechen EIN Vokabular.
> **Sibling (schon geshippt):** web-ts `HubTrustBadge.tsx`/`hubTrustView.ts` (uiux2, CYP-801). Diese Spec ist der
> **Compose-Zwilling** — identischer Contract, gleiche Glyph-FORMEN + Copy + a11y; Farb-Token **wo sicher identisch,
> wo Compose-Palette es erzwingt bewusst adaptiert** (§4).
> **Gate:** `CYP-747-n3-trust-render-qa-checklist.md` (die 9 Ehrlichkeits-Zähne). **Cross-Achse:** Achse-a `HubTrustState`
> ⊥ Achse-c `HubIssuerTrust` (Cyp443-Firewall) — nie konflatieren (Zahn 9, beißt bei 2-TRUSTED = Multi-Hub).

---

## 0. Was gebaut wird (zwei Artefakte)

1. **`HubTrustBadge`** (Composable, `commonMain`) — die per-Hub 5-State-Trust-Pille, prop-getrieben (`HubTrustState?` +
   `HubDescriptorValidity`), rendert EINEN Hub honest + nicht-alarmierend. Zwilling zu web-ts `HubTrustBadge.tsx`.
2. **`hubTrustTone(...)`** — **die EINE zentrale Trust-Ton-Quelle** (Muster `EventVisuals.severityColor`/
   `severityContainer`): `HubTrustState → (Glyph, Farb-Rolle, Emphase, Label-Key, a11y-Key, testId-Suffix)`. Kein Arm
   wählt Trust-Farbe mehr inline. **D4-Fold:** die korrekte „TRUSTED = neutral, never-green (CYP-803)"-Wahrheit lebt
   hier als KDoc; `:core/HubTrust.kt`-KDoc „only 'green' state" wird im Zug korrigiert (§6).

---

## 1. Der Contract (am Code geerdet, `:core/model/HubTrust.kt`)

| Typ | Werte | Achse |
|---|---|---|
| `HubTrustState` | `UNKNOWN` · `PENDING` · `TRUSTED` · `REJECTED` · `STALE` | **a** (TOFU / Hub-Key) |
| `TrustRejectReason` (closed) | `KEY_CHANGED` · `OOB_REJECTED` | **a**, nur bei `REJECTED` |
| `HubDescriptorValidity` | `VALID` · `MALFORMED` | **separates Upstream-Signal**, kein Trust-Zustand |

**Fail-closed:** `UNKNOWN` = Default. Ein absentes/null/nicht-evaluiertes Signal rendert **NIE** vertraut
(„never green-by-default", safe-but-silent). Ein `MALFORMED`-Deskriptor kollabiert die Pille auf `UNKNOWN` (Trust nicht
evaluierbar) **UND** hebt ein **separates** `⚠` (§5) — nie ein 6. Pillen-Zustand.

---

## 2. Die 5 States — Render-Tabelle (Compose)

Glyph-FORM (WCAG 1.4.1: Form trägt, nie Farbe allein) **identisch zu web-ts** (◯◔●⊘◑); Label-WORT immer sichtbar (der
eigentliche a11y-Träger). Farb-Rolle = **Compose-Palette** (§4 begründet Adaption).

| State | Glyph | Farb-Rolle (Compose) | Emphase | Label DE / EN | a11y-Ton |
|---|---|---|---|---|---|
| `UNKNOWN` | `◯` | `onSurfaceVariant` | gedämpft-neutral | „Vertrauen nicht geprüft" / „Trust not checked" | ruhig, kein Alarm |
| `PENDING` | `◔` (+ Spinner-Affordanz) | `onSurfaceVariant` | gedämpft-neutral | „wird geprüft…" / „checking…" | ruhig, in-progress |
| `TRUSTED` | `●` | **`onSurface`** (voll) | **voll-emphase-neutral** | „vertraut" / „trusted" | „vouched, widerrufbar" |
| `REJECTED` | `⊘` | **WARN-amber** `severityColor(Severity.WARN)` | markiert | „abgelehnt" / „rejected" | protektiv (kein „kaputt") |
| `STALE` | `◑` | **WARN-amber** (distinkt von REJECTED via Glyph+Copy) | markiert | „Zuletzt vertraut — Frische nicht bestätigt" / „Last trusted — currency not confirmed" | Handlung nötig |

**Load-bearing Zähne (aus der Gate-Liste):**
- **TRUSTED = `onSurface` (voll), NICHT `onSurfaceVariant`** — voll-emphase hält TRUSTED **distinkt von unknown/pending**
  (die sind gedämpft), aber **kein** Affirm-Akzent, **kein** `primary`/Blau, **kein** literal-Grün (Zahn 1/7/8, CYP-803).
  *(Nicht Desktops `TrustResolution.Pinned`-`onSurfaceVariant` blind kopieren — hier existieren die unknown/pending-
  Nachbarn, also braucht TRUSTED die volle Emphase, sonst kollabiert es in den Absence-Ton = Über-Neutralisierung Zahn 8.)*
- **UNKNOWN/PENDING** nie im vertrauten Look; **PENDING ≠ UNKNOWN** (eigener Zustand, PL-freeze §5b-P2).
- **STALE nie im TRUSTED-Look** (keine vorgetäuschte Frische; „marked, not hidden" §4a); **STALE ≠ UNKNOWN** (STALE
  *hatte* Trust, markiert; UNKNOWN *nie* bewertet, leer-neutral) — Pre-Read via Amber-Markierung vs neutraler ◯.
- **REJECTED ≠ STALE** — gleicher Amber-Hue, distinkt via **Glyph** (`⊘` terminal-verweigert vs `◑` frische-Lücke) +
  **Copy**; nie simultan (ein Hub = ein Trust-Zustand). WCAG 1.4.1: das WORT trägt, Glyph verstärkt.

---

## 3. `TrustRejectReason` — Copy-Distinktheit bei REJECTED (Reuse)

Bei `REJECTED` präzisiert der Grund die Copy (distinkte Strings + Tags, N4-distinkt):

| Reason | Key (Reuse) | Aussage |
|---|---|---|
| `KEY_CHANGED` | `remote_connect_trust_changed` | „Hub-Schlüssel geändert — Out-of-Band neu bestätigen." |
| `OOB_REJECTED` | `remote_connect_trust_rejected` | „Fingerprint abgelehnt — nicht verbunden." |

Ausgeschlossene Ursachen (N4, nie in REJECTED falten): Netzfehler → `UNKNOWN`; Revocation/non-LIVE → `STALE`; malformed
→ `MALFORMED`.

---

## 4. Warum die Farb-Adaption Compose ≠ web-ts (bewusst, begründet — Day+Night)

Der **Contract** (States, Glyph-Formen, Copy, a11y, neutral-TRUSTED) ist **identisch**. Zwei Farb-Token **müssen** auf
Compose abweichen, weil Compose's maritime-M3-Palette andere Rollen-Semantik hat als web-ts's fixe Token:

| State | web-ts | Compose (diese Spec) | Grund |
|---|---|---|---|
| `STALE` | ~~`tertiary`~~ → **`--event-sev-warn` (Amber)** seit **CYP-838** | **WARN-amber** | ✅ **KONVERGIERT** — beide Amber. web-ts's alter `tertiary` war **#40D6A0 GRÜN in Dark** (genau die Inversion, die ich flaggte); CYP-838 swappte ihn auf Amber (action-neutral, beide Themes, mutations-getestet). Compose brauchte `tertiary` nie (Nacht-Grün-Falle CYP-300). |
| `REJECTED` | `--md-sys-color-error` (rot) | **WARN-amber** | Compose's bestehende Trust-Verweiger-Arme (`TrustChanged`/`TrustRejected`, `HubConnectSelection.kt`) sind **bewusst WARN-amber** („protektiv, nicht kaputt/error-red"). Das Badge muss der **eigenen Surface-Doktrin** folgen, sonst amber-Arm + rot-Badge für dieselbe Verweigerung = intern inkonsistent. |

**identisch** bleiben: UNKNOWN/PENDING `onSurfaceVariant`, TRUSTED `onSurface`. **STALE ist nach CYP-838 auf beiden Amber
(konvergiert).** Verbleibende Colour-Differenz = **nur `REJECTED`** (web-ts error-rot vs Compose WARN-amber) — bewusst
pro-Surface (Compose-Verweiger-Doktrin), abgedeckt von der F-A5-3-Resolution unten. Cross-Surface-tertiary-Check
**erledigt** (CYP-838).

> **★ F-A5-3 RESOLUTION (Tester A5-Pass, 2026-07-27, UIUX-Entscheid): DELIBERATELY DIVERGENT → dokumentiert + geschlossen.
> Cross-Platform-Colour-Parität ist KEIN Ziel.** Der Tester bestätigte: REJECTED+STALE teilen sich auf Compose Amber
> (`Severity.WARN`), web-ts nutzt distinkte Tones (`REJECTED='warn'` vs `STALE='action'`) — **kein Kohärenz-Bug**
> (Glyph `⊘`/`◑` + Wort bleiben distinkt, colour-never-sole hält). Warum bewusst so:
> 1. **Contract-Parität ist das Ziel, nicht Token-Parität** (§0/§4) — Plattformen haben verschiedene Paletten, normal.
> 2. **web-ts's STALE war `tertiary`** (= #40D6A0 GRÜN in Dark, genau die von mir geflaggte Inversion) → **CYP-838
>    swappte ihn auf Amber** (`--event-sev-warn`, mutations-getestet); **STALE ist jetzt auf beiden Amber = KONVERGIERT**.
>    Der verify-don't-trust-Flag war korrekt UND ist bereits actioned. Verbleibende Colour-Differenz = nur `REJECTED`.
> 3. **REJECTED + STALE sind dieselbe Severitäts-FAMILIE** (WARN: beide „nicht-aktuell-vertraut, Aufmerksamkeit", weder
>    System-Fehler noch benigne-neutral) — gleiches Amber ist ehrlich; der Unterschied terminal-Verweiger vs Frische-
>    Lücke trägt Glyph+Copy.
> 4. Ein 2. Hue erzwingen bräuchte `tertiary` (Nacht-Falle) **oder** REJECTED auf error-rot heben (über-alarmiert
>    `OOB_REJECTED` = das eigene „Nein" des Operators; bricht die amber-„protektiv"-Verweiger-Doktrin) — beide schlechter.
>
> **Optionaler Zukunfts-Hebel (NICHT jetzt), falls je At-a-glance-Scanning-Distinktion gewünscht:** distinguieren via
> **Emphase innerhalb der Amber-Familie** (STALE = recede/markiert-amber, passend zu „residual trust markiert"; REJECTED
> = voll-amber), **nie** ein 2. Hue. Kein Requirement — reine spätere Politur.

**Day+Night + WCAG (beide Schemata):** Amber kommt aus der **einen** `severityColor(Severity.WARN)`-Quelle (CYP-274/300,
schon Day+Night-getunt, ≥ Kontrast). `onSurface`/`onSurfaceVariant` sind Scheme-Rollen (M3 garantiert Text-Kontrast).
Das **Label-WORT ist immer präsent** → WCAG 1.4.1 erfüllt (Farbe nie Alleinträger); der Glyph ist Verstärkung.

---

## 5. `MALFORMED` — das separate `⚠`-Upstream-Signal (eigener Namespace)

Wie web-ts: **zwei unabhängige Outputs** bei `MALFORMED` — (1) die Pille fällt auf `UNKNOWN` (fail-closed, nie
rejected/trusted), (2) ein **separater, mandatorischer `⚠`-Marker** in **eigenem** testTag-Namespace (NICHT `hub.trust.*`).
- Glyph `⚠` + Text (`hub.trust.<hubId>.upstreamError`-analog → Compose-Tag `hubTrust.<hubId>.upstreamError`), Ton
  **ERROR** (`severityColor(Severity.ERROR)`) — Korruption/MITM/Bug möglich, aber **distinkt von REJECTED** (REJECTED =
  Entscheidung; MALFORMED = konnte nicht evaluieren). Immer gezeigt (mandatory), nie versteckt.
- Copy (Reuse-Muster web-ts): „Ungültiger Hub-Descriptor — Status nicht interpretierbar." / „Invalid hub descriptor —
  status not interpretable."

---

## 6. `hubTrustTone` — die zentrale Ton-Quelle (+ D4-Fold)

Muster **1:1 `EventVisuals`**: reine `*For(state, scheme, dark)`-Kerne (unit-testbar) + `@Composable`-Wrapper.

```
// app/shared/.../net/hub/trust/HubTrustTone.kt  (Skizze, illustrativ)
data class HubTrustToneSpec(val glyph: String, val color: Color, val labelKey: StringResource,
                            val a11yKey: StringResource, val stateToken: String)  // stateToken = state.name.lowercase()

fun hubTrustToneFor(state: HubTrustState, scheme: ColorScheme, dark: Boolean): HubTrustToneSpec = when (state) {
    UNKNOWN  -> spec("◯", scheme.onSurfaceVariant, ...)
    PENDING  -> spec("◔", scheme.onSurfaceVariant, ...)
    TRUSTED  -> spec("●", scheme.onSurface,        ...)  // voll-emphase-NEUTRAL, never-green (CYP-803)
    REJECTED -> spec("⊘", severityColorFor(WARN, scheme, dark), ...)
    STALE    -> spec("◑", severityColorFor(WARN, scheme, dark), ...)  // night-safe amber, NICHT tertiary
}
@Composable fun hubTrustTone(state): HubTrustToneSpec = hubTrustToneFor(state, MaterialTheme.colorScheme, isDark())
```

- **Einzige Trust-Farb-Quelle:** `HubTrustBadge` UND jeder künftige Consumer (List-Row, Connect-Strip) ziehen NUR
  hierüber; **kein inline `colorScheme.x` für Trust** mehr. Die verstreuten `onSurfaceVariant`-Griffe in
  `OobFingerprintConfirmScreen`/`HubConnectSelection` bleiben (das ist Connect-FLOW-Chrome, nicht der Badge-Ton) — der
  Helper deckt den **Badge/State-Ton**, nicht jeden Text im Trust-Flow. *(Scope ehrlich: „Trust-Farb-Töne" = die
  State-Farbe je `HubTrustState`, nicht die neutralen Label-Farben im OOB-Screen.)*
- **D4-Fold (aus dem Cross-Surface-QA):** die maßgebliche Ton-Wahrheit lebt im `hubTrustTone`-KDoc
  („TRUSTED = neutral, never-green, issuer-vouched + widerrufbar, CYP-803"). **Im selben Zug** `:core/model/HubTrust.kt`
  KDoc korrigieren: „the only 'green' state" (2×) → „neutral (never-green, CYP-803)". Ein Doc-Sweep, gehört zur zentralen
  Ton-Wahrheit.
- **Never-green-Guard-Zahn:** ein Regressions-Test (Muster web-ts `hubTrustTrustedTone.honesty.test.ts` Tooth 10):
  `hubTrustToneFor(TRUSTED,…).color == scheme.onSurface` (NICHT `primary`/`onSurfaceVariant`) in **beiden** Schemata;
  Mutation → rot. Fängt Overclaim (→primary/grün) UND Über-Neutralisierung (→onSurfaceVariant).

---

## 7. Placement (Multi-Hub, CYP-832) + Cross-Achse

- **Per-Hub-Row (die Multi-Hub-Sichtbarkeits-Schicht):** jede föderierte Hub-Zeile im Multi-Hub-Client trägt ihr
  `HubTrustBadge` (per-Hub-`HubTrustState`). Das ist der Kern-Nutzen: mehrere Hubs, jeder eigener sichtbarer Trust.
  testTag `hubTrust.<hubId>.<state>` (present-iff-state, Parität web-ts `hub.trust.{hubId}.{state}`).
- **Connect-Strip (aktiver Hub):** optional derselbe Badge; NICHT verwechseln mit dem bestehenden
  `TrustResolution.Pinned`-Kontext-Indikator (`RemoteContextBanner`) — der bleibt (binärer „gepinnt"-Hinweis,
  `onSurfaceVariant`); das Badge ist die 5-State-Schicht. (Doppelung im MVP vermeiden — PO entscheidet, ob Connect-Strip
  auf das Badge migriert oder der Pinned-Hinweis bleibt.)
- **Cross-Achse (Zahn 9, Multi-Hub-relevant):** dieses Badge ist **Achse a** (`HubTrustState.TRUSTED` = neutral `●`).
  Achse c (`HubIssuerTrust.TRUSTED`) rendert heute auf Compose **nichts** (proceed) — falls je ein Issuer-„trusted"-
  Indikator koexistiert, MUSS er **distinkt** vom Badge-`●` sein (nie geteilter „trust"-Look; Cyp443 auf Label/Fläche).
  In Multi-Hub, wo mehrere TRUSTED-Zustände sichtbar werden, ist das der scharfe Zahn.

---

## 8. i18n-Keys (Reuse + Neu)

**Reuse:** `remote_connect_trust_check` (PENDING-Nähe) · `remote_connect_trust_changed` (KEY_CHANGED) ·
`remote_connect_trust_rejected` (OOB_REJECTED). **Neu (net-new, DE+EN zusammen landen — i18n-Parität-Check, Dev-Lane):**

| Key | DE | EN |
|---|---|---|
| `hub_trust_unknown` | Vertrauen nicht geprüft | Trust not checked |
| `hub_trust_pending` | wird geprüft… | checking… |
| `hub_trust_trusted` | vertraut | trusted |
| `hub_trust_rejected` | abgelehnt | rejected |
| `hub_trust_stale` | Zuletzt vertraut — Frische nicht bestätigt | Last trusted — currency not confirmed |
| `hub_descriptor_invalid` | Ungültiger Hub-Descriptor — Status nicht interpretierbar | Invalid hub descriptor — status not interpretable |
| `a11y_hub_trust_trusted` | Diesem Hub wird vertraut (Aussteller-vouched, widerrufbar). | This hub is trusted (issuer-vouched, revocable). |
| `a11y_hub_trust_stale` | Zuletzt vertraut — Frische nicht bestätigt. Zum Fortfahren Out-of-Band erneut bestätigen. | Last trusted — currency not confirmed. Re-confirm out-of-band to continue. |
| `a11y_hub_trust_*` (übrige States) | *(analog, ruhig; UNKNOWN/PENDING nie „sicher")* | |

> **★ STALE-Copy KANONISCH (Cross-Surface, 2026-07-27):** die **deskriptive** Form oben ist die kanonische Trust-STALE-
> Copy für **beide** Surfaces — **nicht** web-ts's imperativ-„abgelaufen — erneut bestätigen". Grund: STALE = `:core`
> „can no longer be confirmed **current**" (Revocation **oder** non-LIVE-Feed); „abgelaufen/expired" behauptet eine
> definite Terminierung, die wir nicht wissen (Overclaim, `null≠fabriziert`), und liest terminaler als STALE ist
> (≠ REJECTED). **Label deskriptiv** (das Badge zeigt einen Zustand); die **Aktion** („OOB erneut bestätigen") lebt in
> der **a11y-Zeile**, nie im Pill-Label und nie via „abgelaufen". web-ts alignt hierauf (PO-Follow-up).

**Wortlaut-Disziplin:** kein „sicher/verifiziert" bei TRUSTED (Overclaim); TRUSTED-Label **„vertraut" NICHT „gepinnt"**
(kollidiert mit `pinnedOperatorId`, CYP-803). Wortgleich zu web-ts `HUB_TRUST_TEXT` wo möglich.

---

## 9. a11y (announce-Dringlichkeit)

- Das Badge ist **persistenter Zustand**, kein just-happened-Event → `liveRegion = Polite` (Muster web-ts `role="status"
  aria-live="polite"`). Die **Aktiv-Hub**-Assertive-Eskalation (falls der aktive Hub gerade in REJECTED/STALE kippt) ist
  eine **Placement**-Sache (wo das Badge in der Chrome sitzt), nicht in das Leaf-Badge gebacken — analog web-ts §2.
- Pille = `role="img"`-Analog: der a11y-Text buchstabiert das **Wort** (Bedeutung), nie Glyph/Farbe allein.
- MALFORMED-`⚠` = `role="alert"`-Analog (mandatory, unaufgefordert).

---

## 10. Self-Validation
- **Contract am Code geerdet** (`:core/HubTrust.kt` develop `18fc52fd`), Sibling web-ts `hubTrustView.ts`/`index.css`
  real gelesen — Glyphe/Token/Copy sind belegte Fakten, keine Wunschliste.
- **Cross-Surface ehrlich:** Contract identisch; **zwei Farb-Divergenzen (STALE, REJECTED) mit Grund benannt** (Compose
  `tertiary`=Nacht-Grün-Falle; Compose-Trust-Doktrin=amber) + als Reconciliation-Notiz an PO/Team-2 geflaggt — kein
  stiller Drift, kein Überschreiben von web-ts.
- **9 Zähne adressiert:** never-green/neutral-`onSurface` (1/7), Über-Neutralisierung-Gegenfalle (8), Zwei-TRUSTED-
  Distinktheit für Multi-Hub (9), N4-Nicht-Falten (UNKNOWN≠REJECTED≠STALE≠MALFORMED), safe-but-silent (STALE markiert),
  fail-closed-Default (UNKNOWN).
- **Zentralisierung + Scope ehrlich:** `hubTrustTone` = die eine State-Ton-Quelle; **explizit gescoped** auf den
  Badge/State-Ton, NICHT jede neutrale Label-Farbe im OOB-Flow (kein Overclaim „zentralisiert alles").
- **D4 gefoldet:** der `:core`-KDoc-Sweep gehört zur zentralen Ton-Wahrheit (never-green), hier verortet.
- **Never-green-Guard als Test-Zahn** spezifiziert (beide Schemata) — die Ehrlichkeit ist maschinell gepinnt, nicht nur
  Prosa. **Kein Bau** — reine Vorlage; Keys/Tags final am Bau, Dev landet DE+EN zusammen.
