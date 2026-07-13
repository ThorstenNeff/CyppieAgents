# Hub-Connect-Screens — Copy-Delta (S-J-Dogfood-Politur, CYP-419/CYP-395)

> Owner: UIUX-Designer · **Dev-AC (copy-paste-fertig)** · Stand 2026-07-13 · PO `1526330418…` (Triage der UX-QA
> `1526329797…`). **Rein Doku/Copy, kein Code.** Gegroundet READ-ONLY gg. develop `422e0778`.
> **S-J-Modell (PO-bestätigt): `register` ist VESTIGIAL** — Backend bestätigt, Hubs **self-admitten**; die GUI ist
> **List + Select**, **kein** Operator-Register. Der Register-Flow (Titel/Submit) ist in M1 **dormant** → dessen Copy
> (#2/#4) **deferred**. Diese Delta poliert nur, was S-J live wired: **Zeilen-Format · Empty-State · Step-Indikator ·
> Dead-Copy**. Konvention: Underscore-Realkeys, `%1$s`, DE=Default + EN-Parität.

---

## Δ1 — Hub-Listen-Zeile: Name + Status, KEINE Fake-Adresse (🔴 #1, ENTSCHIEDEN)

**Befund:** `HubConnectSelection.kt:116` rendert `Text("localhost:${hub.defaultPort}")` als Adress-Zeile jeder Zeile —
**hartkodiert, nicht lokalisiert, und für einen relay-gedialten Remote-Hub eine Lüge** (jeder Hub zeigt „localhost:port").

**Ziel-Zeilen-Format (`HubRow`):**
1. **Primär: `hub.name`** (der editierbare Anzeigename) — `titleSmall`/`bodyLarge`, `onSurface`.
2. **Optional-Sekundär (Identität/Disambiguierung): kurze hubId** — `hub.hubId.take(8)`, `bodySmall`, `onSurfaceVariant`,
   monospace. Nur wenn Namens-Kollision möglich; **nie** eine erfundene Adresse. (Dogfood = meist 1 Hub → optional.)
3. **Presence-Dot + Label** (bestehende `PresenceRow`, unverändert: filled=online / hollow=offline + `a11y`).
- **HARTE Regel:** **keine** `localhost:port`, **keine** fabrizierte Host/Port-Adresse. Ein relay-gedialter Remote-Hub
  hat **keine** user-facing Adresse — Identität = **Name (+ optional hubId)**, Erreichbarkeit = **Status-Dot**.

**Copy/Keys:** kein net-new Copy-String für Name/hubId (gerenderte Werte). **Optional-empfohlen a11y** für die hubId:
| Key | DE | EN | Rolle |
|---|---|---|---|
| `a11y_hubconnect_hub_id` | Hub-Kennung %1$s | Hub ID %1$s | a11y der optionalen hubId-Zeile (nur wenn gerendert) |

> Dev foldet das Zeilen-Render in den S-J-Client (echte Daten hinter Name/Status). Zeile `:116`-Hardcode **entfällt**.

---

## Δ2 — Empty-State: ehrlich „noch keine Hubs", KEIN Register-CTA (★, ENTSCHIEDEN)

**Befund:** der Empty-State bietet heute den `hubconnect_hubs_register`-CTA („Hub registrieren") — **nicht-funktional**,
da register vestigial ist (Hubs self-admitten, der Operator registriert nichts).

**Ziel:** ehrliche Wartestand-Copy + eine **Refresh**-Affordance (kein Register-CTA). Der Hub erscheint, sobald er
sich self-admittet (online geht) — die GUI wartet/aktualisiert, sie registriert nicht.
| Key | DE | EN | Rolle |
|---|---|---|---|
| `hubconnect_hubs_empty` **(RETEXT)** | Noch keine Hubs — sobald dein Hub online ist, erscheint er hier. | No hubs yet — your hub appears here once it comes online. | ehrlicher Wartestand (self-admit), **ersetzt** „Noch kein Hub registriert." |
| `hubconnect_hubs_refresh` **(NET-NEW)** | Aktualisieren | Refresh | Empty-State-Affordance: Liste neu abfragen (self-admittete Hubs prüfen) — **ersetzt** den Register-CTA. Tag `HUBS_REFRESH`. |

- **Register-CTA aus dem Empty-State entfernen:** `hubconnect_hubs_register` bleibt als Key (dormanter Register-Submit,
  #4 deferred), wird aber im Empty-State **nicht** mehr verwendet.
- Wenn die Liste ohnehin auto-pollt/-pusht, ist der Refresh-Button ein Nice-to-have (schadet nicht); die **Body-Copy
  verspricht keine Auto-Erscheinung fälschlich** — sie ist wartestand-ehrlich in beiden Fällen.

---

## Δ3 — Step-Indikator: echte Schrittzahl + lokalisiert (🟠 #3)

**Befund:** `HubConnectFlow.kt:305/307` rendert hartkodiert `"$step / $of"` mit `of = 4`, aber es existieren nur
**3** Schritte (Register=1, Credentials=2, Ready=3) → Nutzer sehen „3 / 4" als Ende (impliziert fehlenden Schritt 4);
zudem **nicht lokalisiert** (der Code-Kommentar gibt „Schritt 2 von 4" als Spec-Wunsch selbst zu).
| Key | DE | EN | Rolle |
|---|---|---|---|
| `hubconnect_step_indicator` **(NET-NEW, 2 Args)** | Schritt %1$s von %2$s | Step %1$s of %2$s | lokalisierter Stepper; ersetzt das hartkodierte „n / 4". |

- **Nenner = die ECHTE Zahl der in S-J gezeigten Schritte** (heute 3). Wird der Register-Schritt vestigial entfernt,
  passt Dev den Nenner an die real verbleibenden Schritte an (**nie** eine Zahl, die einen nicht-existenten Schritt
  impliziert). `%1$s`=aktueller Schritt, `%2$s`=Gesamt; DE=EN Argument-Anzahl identisch.

---

## Δ4 — Dead-Copy entfernen: „kommt bald" ist faktisch falsch (🟡 #5)

**Befund:** verwaiste Strings behaupten Remote = „kommt bald", obwohl **Remote seit CYP-471 LIVE** ist —
unreferenziert heute, aber **Regressions-/Ehrlichkeits-Falle**, falls je wieder eingehängt.
**Entfernen (DE+EN):**
- `hubconnect_mode_remote_soon` (DE „kommt bald" / EN „coming soon")
- `a11y_hubconnect_mode_remote_disabled` (DE „Remote-Modus — kommt bald, noch nicht verfügbar." / EN „Remote mode — coming soon, not available yet.")
- Tag `HubConnectTags.MODE_REMOTE_SOON` (definiert, unbenutzt).

> Reine Streichung — keine Ersatz-Copy (Remote-Modus ist regulär live, `HubConnectModeChooser`).

---

## Deferred (PO-Triage `1526330418…`, NICHT in dieser Delta)
- **#2 Register-Titel** („Melde dich an…" irreführend) — Register-Flow **dormant in M1**; retexten wenn/falls register reaktiviert.
- **#4 Submit-Key** (eigener „Registrieren"-Key statt Empty-CTA-Reuse) — dito dormant.
- **#6 Remote-Connect-Wärme** (Jargon-Opener) + **#7** (Ellipsis-Spacing, „Hub entfernen") — LOW, Papercuts.

## Honesty-Anker (für §-QA)
- **HA — keine fabrizierte Adresse:** eine Hub-Zeile zeigt **nie** `localhost:port` oder eine erfundene Erreichbarkeit;
  Identität = Name (+opt. hubId), Erreichbarkeit = Presence-Dot (advisory, H1). Ein Remote-Hub ohne user-facing Adresse
  wird **nicht** mit einer Fake-Adresse „gefüllt" (`null≠fabricated`).
- **HB — Empty-State ehrlich zum Modell:** kein nicht-funktionaler Register-CTA; Wartestand-Copy passt zum self-admit
  (der Hub erscheint, wenn er online geht — die GUI registriert ihn nicht).
- **HC — Stepper lügt keine Schrittzahl:** der Nenner = real gezeigte Schritte, nie ein Phantom-Schritt.
- **HD — kein „kommt bald" für Live-Features:** Dead-Copy, die die Realität überzeichnet/unterzeichnet, wird entfernt.

## Self-Validation
- **Net-new: 2 Realkeys** (`hubconnect_hubs_refresh` [0 Args] + `hubconnect_step_indicator` [2 Args]) **+ 1 a11y**
  (`a11y_hubconnect_hub_id` [1 Arg], optional). **1 Retext:** `hubconnect_hubs_empty`. **Entfernt: 2 Keys** (+DE/EN) +
  1 Tag. Alle net-new/retext DE+EN paritätisch (Argument-Anzahl DE=EN identisch).
- **Net-new Tag: 1** — `HubConnectTags.HUBS_REFRESH = "hubConnect.hubs.refresh"` (neue Const im bestehenden Objekt).
  **Entfernt: 1** — `MODE_REMOTE_SOON`. Charset ✓ (camelCase, `[A-Za-z0-9-]+`).
- **0 Kollision** @ `422e0778` (`hubconnect_hubs_refresh`/`_step_indicator`/`a11y_hubconnect_hub_id`/`hubConnect.hubs.refresh`
  greenfield).
- **Geteilte CYP-7-API:** `HUBS_REFRESH` + der Wegfall von `MODE_REMOTE_SOON` mit Tester/DS über den PO abstimmen.
- **Kein content-tragender/sensibler Klartext.** Register-Copy (#2/#4) bewusst **nicht** angefasst (dormant).
- **Dev foldet in S-J:** #1-Zeilen-Render + Empty-State + #3-Step-Count; Copy hier ist der frozen AC.
