# CYP-847 — Handoff/Context-Lost Banner: a11y-Register-Parität (polite → assertive)

> UX/UI-a11y-Fix (PL-adjudiziert, ich führe). Der Context-Lost/Handoff-**Banner** rendert web `role="status"` (implizit
> **polite**), native (ratifiziert) **assertive**. Gleiche Event-Kritikalität → gleiche Announce-Klasse. **Hier IST die
> Divergenz der Bug** (≠ CYP-803 per-Surface-Token, wo Divergenz legitim war). Passt zur **CYP-825**-Register-Disziplin
> (terminal/unsolicited-critical = assertive). Ich spec, Team-1-UIUX gegenbestätigt, Dev5 implementiert.

## 0. ★ Der 2-Tier-Scope-Guard zuerst (load-bearing, am Objekt bestätigt)
Die polite→assertive-Änderung betrifft **NUR den live Event-Announce**, **NICHT** den durablen Transcript-Landmark
(CYP-381 §7.1, „momentary→durable split"). Am Objekt verifiziert:
- **Tier 1 — LIVE Event-Announce = der HandoffBanner** (`web-ts/src/agentview/HandoffBanner.tsx`, beide Arten). Die „live
  Hälfte" (native `AgentWindow.kt:260`: „HandoffBanners, which are the live half") — **clears** mit dem State
  (CONTEXT_LOST→MEDIATED beim nächsten echten Turn → `handoffBanner(control)===null` → Banner weg). Heute `role="status"`
  = eine **Live-Region** (polite). **← DAS ist das CYP-847-Ziel → assertive.**
- **Tier 2 — DURABLE Transcript-Landmark** = das Transcript-Discontinuity-Band (native `a11y_transcript_context_lost`,
  `AgentWindow.kt:1165`: „one **static** a11y landmark … **NO liveRegion** — the live announcement is elsewhere"). **In
  web-ts DEFERRED** (nicht gebaut — HandoffBanner.tsx-Kommentar: „CONTEXT_LOST transcript chrome … deferred"). **→ bleibt
  UNBERÜHRT.** Wenn web-ts es später baut: **statisch, KEINE liveRegion** (Tier 2 nie assertiv).

**Warum die Grenze zählt:** ein assertiver **Landmark** würde bei jeder Transcript-Interaktion/Recovery re-announcen —
falsch. Der **Event** (Banner erscheint) ist der unsolicited-critical Moment, der interrupten soll; der **Marker** (History
war hier verloren) ist ein statischer Ort. Nur der Event wird assertiv. Es gibt in web-ts heute **nur** Tier 1 (Banner) →
kein Risiko, den Landmark versehentlich zu erwischen; die Spec fixiert die Grenze für den künftigen Tier-2-Bau.

## 1. Die Änderung (exakt)
**Beide** HandoffBanner-Arten: `role="status"` → **`role="alert"` + explizit `aria-live="assertive"`**.
| Banner | Ort | heute | CYP-847 |
|---|---|---|---|
| **contextLost** | `HandoffBanner.tsx:16` | `role="status"` | `role="alert" aria-live="assertive"` |
| **handoff** | `HandoffBanner.tsx:31` (+`aria-label`) | `role="status"` | `role="alert" aria-live="assertive"` |

**Unverändert (nur das Register ändert sich):** ▲-Glyph · WARN-amber-Ton · Copy („Kontext verloren — ohne vorherige
Historie zurückgekehrt." / „Terminal übergeben an {holder} · seit {since}") · die handoff-`aria-label`
(„Terminal an {holder} übergeben, seit {since} — interaktive Sitzung aktiv"). **Kein** visueller/Copy-Change.

## 2. Warum BEIDE Banner (nicht nur contextLost)
Beide sind **Tier-1-Live-Event-Announces** derselben Klasse — **unsolicited** (passiert dem Operator, nicht von ihm
ausgelöst) + **handlungsrelevant** (contextLost: der Agent erinnert die Historie nicht → weiter-reden unter falschem
Glauben; handoff: ein anderer Holder treibt das Terminal → die mediierte Sicht/Composer ist verändert). Native behandelt
beide **uniform** (dieselbe `HandoffBanners`/`FrameBanner`-Mechanik). → beide assertiv.
- **Kein Announce-Storm:** der Inhalt ist **statisch** (`since` = lokales **HH:MM**, kein tickender Timer → kein
  Re-Announce). Der Banner **clears** mit dem State (momentary-live). Also feuert die assertive Ansage **einmal** beim
  Erscheinen — genau der Event —, nicht wiederholt. Konsistent mit [[over-alarm-is-also-dishonest]] (assertiv bleibt für
  das seltene unsolicited-critical Event reserviert, wird nicht inflationär).

## 3. Attribut-Form: `role="alert"` + explizit `aria-live="assertive"`
`role="alert"` **impliziert** `aria-live="assertive"` + `aria-atomic`, aber die **explizite** `aria-live="assertive"` ist
das etablierte **web-ts-Haus-Muster** für SR-Zuverlässigkeit (`auth/LoginScreen.tsx:119`:
`role="alert" aria-live="assertive"`) — manche AT mappen `role="alert"` nicht zuverlässig auf assertiv ohne das explizite
Attribut. **Beide** setzen = SR-robust + Haus-Konsistenz (Dev5s Lean, bestätigt). *(Gleiche SR-Reliabilitäts-Logik wie
CYP-825: dediziertes/explizites assertives Signal statt implizitem Mapping.)*

## 4. Cross-Surface / Cross-Confirm
- **Parität:** native `HandoffBanners`/`FrameBanner` = die **live Hälfte** des 2-Tier-Modells; die assertive Announce-Klasse
  für dies **unsolicited-critical** Event ist die **ratifizierte** Registrierung (CYP-381 §7.1). web richtet sich daran aus
  — **hier ist Match korrekt** (Divergenz = Bug), anders als CYP-803 (per-Surface-Token legitim).
- **Team-1-UIUX-Gegenbestätigung (via Koordinator):** dass **assertive** der ratifizierte Register für dies Event ist
  **und** dass die **Tier-1(Banner)/Tier-2(Landmark)**-Grenze (nur Banner assertiv, Landmark statisch) korrekt gespiegelt
  ist. *(Ich DM Team-1 nicht — PO/PL routet; meine Seite ist am Objekt bestätigt: native FrameBanner = live half,
  Transcript-Band = static/no-liveRegion.)*

## 5. Caveat (guided-live, konsistent mit CYP-825 / CYP-803-L1)
**alert-on-mount:** ist der Banner **schon präsent**, wenn das Agent-Fenster mountet (Fenster geöffnet, während bereits
handed-off/context-lost), sagen **manche SR** ein bei-Mount-präsentes `role="alert"` **nicht** an. Der **Normalfall**
(Banner erscheint auf einen Live-Control-State-Wechsel) sagt an. → **already-present-on-mount in der guided-live-AT-Runde
mitverifizieren** (bündeln mit CYP-825-Revoke + CYP-803-L1 — dieselbe alert-on-mount-Familie).

## 6. Teeth (Tester2)
1. **Beide Banner assertiv** — contextLost + handoff rendern `role="alert"` **und** `aria-live="assertive"`.
   *(Mutation: `role="status"`/polite an einem der beiden → RED = das Divergenz-Bug zurück.)*
2. **Landmark unberührt** — der (künftige) durable Transcript-Context-Lost-Landmark bleibt **statisch, keine liveRegion**
   (nicht `alert`/`aria-live`). *(Mutation: Landmark bekommt eine liveRegion → RED = Tier-2 in eine Live-Region gezogen.)*
   *(Heute deferred → dieser Zahn ist die Leitplanke für den künftigen Bau.)*
3. **Nur Register geändert** — Glyph ▲, WARN-amber, Copy, handoff-`aria-label` **unverändert**. *(Mutation: Copy/Glyph/Ton
   mit-geändert → RED = Scope-Creep.)*
4. **Kein Announce-Storm** — `since` bleibt statisch HH:MM (kein tickender Timer, der die assertive Region re-feuert).

## 7. Reuse-Ledger
- **Attribut-Form:** `auth/LoginScreen.tsx:119` (`role="alert" aria-live="assertive"`) — Haus-Präzedenz.
- **2-Tier-Modell:** CYP-381 §7.1 (native `AgentWindow.kt:260`/`:1165`) — live half vs durable static landmark.
- **Register-Disziplin:** CYP-825 (Revoke=assertive, nur terminal) · CYP-755 §1 (aktiv-Hub-Wechsel=assertive) · CYP-805
  (terminal=assertive) — Progression/transient=polite. CYP-847 ordnet den Banner-Event in dieselbe assertive-Klasse ein.
- **Kein neuer Leaf/Copy/Glyph** — reiner a11y-Register-Fix.
