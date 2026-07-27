# CYP-847 — Kanonische Announce-Klasse: geteiltes Context-Lost / Handoff-Event (Cross-Surface)

> Owner: UIUX (Team-1) · **Kanonische Ziel-Definition**, gegen die UIUX2 die web-Seite angleicht (polite → assertive);
> **a2-po/UIUX2 gegenbestätigen**. Stand develop `c799d6f9`. **Kein Bau hier** — die Definition; Compose ist heute schon
> konform (Referenz-Impl), web zieht nach.
> **PL-adjudiziert:** die Ansage-Klasse des Context-Lost/Handoff-**Events** = **Assertive**.
> **Doktrin-Quelle:** `docs/A11Y-ANNOUNCEMENTS.md`. **Ursprung 2-Tier:** `CYP-381 §7.1` (Live-State vs durabler Landmark).
> **Warum Parity hier ≠ Colour-Parity (F-A5-3):** die Ansage-Klasse hat eine **reale Nutzer-Konsequenz** — sie steuert,
> ob ein Screenreader die laufende Ausgabe **unterbrochen** bekommt. Divergenz = ein Nutzer verpasst auf einer Fläche den
> Gedächtnis-Verlust seines Agenten. Deshalb ist hier **eine** kanonische Klasse Pflicht.

---

## 0. Die Regel in einem Satz
Der **live Event-Announce** des Context-Lost/Handoff-Zustands ist **Assertive** auf **beiden** Flächen; der **durable
Transcript-Landmark** ist **statisch (kein liveRegion)** auf beiden — und **wird von diesem Alignment NICHT angefasst**.

---

## 1. Doktrin (A11Y-ANNOUNCEMENTS.md, kurz)
- **Assertive** = **Ergebnis einer abgeschickten/gestarteten Aktion** **oder** **unaufgefordert-kritisch** (Blick kann
  woanders sein). Unterbricht die SR-Ausgabe — gerechtfertigt, wenn sonst etwas **verpasst** würde.
- **Polite** = Anfangszustand einer soeben geöffneten Fläche (der Nutzer schaut schon hin).
- Achse ist **Aufmerksamkeit**, nicht Schwere.

---

## 2. Das 2-Tier-Modell (der load-bearing Scope-Guard)

**Der EINE Fehler, den dieses Alignment vermeiden muss: den durablen Landmark versehentlich mit „hochziehen".** Die zwei
Tiers sind verschiedene Dinge mit verschiedenen Klassen:

| Tier | Was | Announce-Klasse | Warum |
|---|---|---|---|
| **1 — Live Event** | der Zustand **tritt ein** (Kontext verloren; Terminal übergeben/zurück) — der persistente WARN-`▲`-Banner erscheint | **Assertive** (bei Erscheinen/Content-Change) | ein **Ereignis**, das der Operator hören muss, auch bei abgewandtem Blick / während er woanders tippt |
| **2 — Durable Landmark** | die **Transcript-Diskontinuitäts-Zeile**, am `CONTEXT_LOST`-ts verankert, **überlebt die Recovery** | **statisch — KEIN liveRegion** | es ist **Historie**, kein Ereignis; fokus-/navigations-lesbar. Ein liveRegion hier = (a) Doppel-Ansage mit Tier 1, (b) Re-Announce bei jedem Fokus/Scroll = Lärm |

> **Guard:** Das Alignment ändert **NUR Tier 1** (web `role="status"` → `role="alert"`). Tier 2 (der durable Landmark)
> bleibt statisch — **web darf ihm KEIN `aria-live` geben**, weder jetzt (web hat den Landmark noch nicht) noch wenn er
> später gebaut wird. Wird Tier 2 versehentlich live, ist die Ehrlichkeit **verletzt** (der Marker ist eine Aufzeichnung,
> kein Alarm).

---

## 3. Soll-Announce pro Zustand (die kanonische Tabelle)

| Zustand / Ereignis | Soll-Announce | Tier | Anmerkung |
|---|---|---|---|
| **Context-Lost tritt ein** (Agent ohne vorherige Historie zurück) | **Assertive** | 1 | **unaufgefordert-kritisch** — der Agent hat sein Gedächtnis verloren; MUSS gehört werden (CYP-381: nie vorgetäuschte Kontinuität) |
| **Handoff-Übergabe** (Terminal an interaktive Sitzung übergeben) | **Assertive** | 1 | Erscheinen des Handoff-Banners = die **bestätigte** (nicht-optimistische) Transition, die der Operator abschickte + auf die er wartet |
| **Handoff zurück / bestätigter Mode-Flip** (→ Orchestrierung) | **Assertive** | 1 | Ergebnis der abgeschickten Umschaltung |
| **Handoff in-flight** (switching / HANDING_OVER) | **kein Announce** — `stateDescription` / `aria-busy` | — | noch kein Ergebnis; darf **nicht pulsen** |
| **Recovery** (Kontext zurück → MEDIATED) | **kein Assertive-Re-Announce** — der Live-Banner **verschwindet** (sichtbar); Tier-2-Landmark **bleibt** | 1→klar / 2 bleibt | gute Nachricht braucht keine Unterbrechung; die Aufzeichnung bleibt als Landmark |
| **Durabler Transcript-Landmark** (Diskontinuitäts-Zeile) | **kein liveRegion** (statisch) | 2 | fokus-lesbare Historie; **nie** Tier-1-hochziehen |

---

## 4. Was NICHT anzufassen ist (explizit, für UIUX2)
- **Der durable Transcript-Landmark / die Diskontinuitäts-Zeile** bekommt **kein `aria-live`/`role="alert"`**. Er ist ein
  persistenter, am `CONTEXT_LOST`-ts verankerter **Aufzeichnungs**-Marker (überlebt die Recovery). Fokus-/Navigations-
  lesbar, nie ein Live-Interrupt.
- **Der in-flight `switching`-Zustand** bekommt **keinen** Announce-Puls (bleibt `aria-busy`/`stateDescription`).
- **Recovery** löst **kein** Assertive aus (der Banner klärt sich sichtbar; keine Interrupt-Ansage für die gute Nachricht).

---

## 5. Per-Surface: Ist → Ziel

| Fläche | Ist (heute) | Ziel |
|---|---|---|
| **Compose (Referenz, schon konform)** | Live-`contextLostBanner` `liveRegion=Assertive` (`AgentWindow.kt:545`); Mode-Confirm Assertive (`:384`); `switching` `stateDescription` kein Puls (`:418`); durabler `contextLostDivider` statisch `a11y_transcript_context_lost` **kein liveRegion** (`:1165`, CYP-381 §7.1) | **unverändert** — dies ist die Referenz-Impl der kanonischen Klasse |
| **web (zieht nach)** | `HandoffBanner.tsx`: **beide** Banner (`contextLost` + `handoff`) `role="status"` (= polite); durabler Landmark **noch nicht gebaut** (CYP-644 deferred) | Tier-1-Banner `role="status"` → **`role="alert"`** (assertive-bei-Erscheinen). **Wenn** der durable Landmark später gebaut wird: **statisch, kein `aria-live`** (Tier-2-Guard) |

**Wortlaut bleibt** wie geshippt (web „Kontext verloren — ohne vorherige Historie zurückgekehrt." / „Terminal übergeben
an X · seit Y"; Compose `a11y_terminal_handoff`/context-lost-a11y) — dieses Alignment ändert die **Announce-Klasse**,
nicht die Copy. Glyph `▲` WARN-amber bleibt (Handoff/Context-Loss = Vorsicht, nie grün/nie error-rot).

---

## 6. Self-Validation
- **Am Code beider Flächen geerdet** (develop `c799d6f9`): web `HandoffBanner.tsx` `role="status"` (beide Banner) +
  Compose `AgentWindow.kt:384/418/545` + `AgentViewTags.contextLostBanner/Divider` real gelesen — file:line-belegt.
- **PL-Adjudikation umgesetzt** (Assertive), **doktrin-begründet** (Context-Lost=unaufgefordert-kritisch, Handoff=
  Ergebnis-abgeschickt) — nicht „lauter = besser", sondern die richtige **Klasse** je Aufmerksamkeits-Achse.
- **2-Tier-Guard explizit** (§2/§4): das load-bearing Risiko ist, den **durablen Landmark** mit hochzuziehen — er bleibt
  statisch. Ohne diesen Guard „reinigt" das Alignment versehentlich die Historie in einen Alarm.
- **Recovery ehrlich gescoped:** kein Assertive für die gute Nachricht; Banner klärt, Landmark bleibt.
- **Scope: nur die Announce-Klasse**, nicht Copy/Glyph/Ton (die sind schon geteilt/konform). Compose unverändert
  (Referenz), web ändert genau 1 Attribut je Banner (`role`).
- **Kein Bau** — Ziel-Definition für UIUX2/a2-po zum Gegenbestätigen; der web-Change ist deren Lane.
