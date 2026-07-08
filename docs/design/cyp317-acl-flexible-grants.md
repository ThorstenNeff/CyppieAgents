# Design-Spec — CYP-317: flexible ACL-Matrix (jede Agent×Kanal-Zelle grantbar, auch Nicht-Member)

> **Status:** DESIGN-SPEC (kein Bau) · Owner: UIUX · Story (Med) · docs-only auf `feature/CYP-317-acl-flexible-spec` (off develop `d3f3793`).
> **Ziel:** Der Operator kann **jedem** Agenten für **jeden** Kanal read/write geben — **auch Nicht-Membern**; ein Grant **macht ihn zum Member**. Klarer Affordanz-Unterschied „Member mit Rechten" vs. „Nicht-Member, grantbar". Bedienbar bei „alle Agenten × alle Kanäle".

---

## §0 — Ist-Zustand (gegen Code verifiziert, `d3f3793`) — der Gap

| Frage | Befund (Code) |
|---|---|
| Rendert die Matrix **alle Agenten**? | **Ja.** `WideGrid` iteriert `state.agents.forEach` als Spalten (AclPanel.kt:191) × `state.channels.forEach` als Zeilen (:207). Der Grid ist bereits vollständig. |
| Sind **Nicht-Member-Zellen** inert oder schaltbar? | **INERT.** `AclCellView` (AclPanel.kt:300–311): `if (!cell.isMember) { Text("—") … return@Column }` → nur ein N/A-„—" (`CellQualifier.NON_MEMBER`, `a11y_acl_cell_nonmember`), **keine Switches**. Nur `cell.isMember`-Zellen bekommen die `GrantControl`-Switches. **← das ist der Gap.** |
| Toggle-Pfad membership-abhängig? | **Nein.** `AclViewModel.toggle`/`applyToggle` (:173/:217) upserten schlicht einen `AclEntry(channelId, agentId, canRead, canWrite)` und PUTten ihn (`api.setAcl`); **kein `isMember`-Check**. Nur (a) das Rendering unterdrückt Nicht-Member + (b) `AclMatrix.canRead/canWrite` gaten die **effektive** Berechtigung auf `Channel.members`. |
| Präzedenz für „membership-unabhängig grantbar"? | **Ja — `AclReducer.humanCells` (:103).** Menschen (CYP-189) sind **auf JEDEM Kanal grantbar** (`isMember = true` immer, „no membership suppression"), weil CYP-188 den Send am `canWrite`-Chokepoint gatet, **nicht** an `Channel.members`. **CYP-317 = dieses Muster auf Agenten ausdehnen** — mit dem Unterschied, dass Agenten-Enforcement **doch** an Membership gatet → Grant muss Membership **hinzufügen** (§3). |

**Kern:** CYP-317 ist kein neuer Grid — es ist **Nicht-Member-Zellen von inert („—") zu schaltbar** machen, ehrlich gekoppelt an „Grant ⇒ Membership".

---

## §1 — Ziel: jede Zelle schaltbar

- Die Nicht-Member-Agent-Zelle rendert **dieselben** zwei unabhängigen `GrantControl`-Switches (Lesen/Antworten) wie eine Member-Zelle (**Reuse**, konsistent mit `humanCells`) — **kein** inertes „—" mehr für grantbare Zellen.
- Reducer: `AclCell.isMember` **bleibt** — aber nur noch als **Affordanz-Diskriminator** (§2), nicht mehr als Toggle-Sperre. Der `return@Column`-Zweig entfällt für grantbare Nicht-Member.
- Member-Zellen **unverändert** (keine Regression): Switches + bestehende Marker (enforced/pending/conflict/po-critical).

---

## §2 — Affordanz: „Member mit Rechten" vs. „Nicht-Member, grantbar" (nicht-farblich)

Der Unterschied MUSS **non-color** getragen werden (WCAG 1.4.1) — Form + Text, nie nur Farbe:

| | Member-Zelle | Nicht-Member-Zelle (grantbar) |
|---|---|---|
| Rahmen | keiner (bündig) | **gestrichelter 1px-Outline** (Form-Marker) |
| Marker-Text | — | **„kein Mitglied"** (Reuse `acl_non_member`, `onSurfaceVariant`, `labelSmall`) |
| Switches | Lesen/Antworten | Lesen/Antworten (Default **aus**) |
| a11y | Toggle-Labels | Toggle-Labels **+** „Gewähren fügt als Mitglied hinzu" (§6) |

- **Primärer non-color-Marker = gestrichelter Outline + „kein Mitglied"-Text.** Eine optionale dezente `surfaceVariant`-Tönung ist **sekundär** (Farbe allein nie ausreichend).
- Beim ersten Grant → Zelle wird **pending** → nach Server-Echo **Member** (Outline + Marker verschwinden, normale Member-Zelle). Der Übergang ist die ehrliche Bestätigung (§3).
- Switches immer sichtbar (Direktheit + Konsistenz mit Member/Human-Zellen). Dichte trägt die Skalierung (§5), nicht eine Progressive-Disclosure-Sonderform (erwogen, **nicht empfohlen** — Inkonsistenz + Extra-Klick).

---

## §3 — Honesty-Kern: Grant ⇒ Membership, Zelle spiegelt Durchsetzung

**Der scharfe Punkt.** Für einen **Agenten** gatet die effektive `canRead/canWrite` (`AclMatrix`) auf `Channel.members` (Hub-Routing, 02 §6.3: „A muss member sein UND canWrite"). Ein Grant-Eintrag **ohne** Membership würde „gewährt" zeigen, während Enforcement weiter **verweigert** (kein Member) → **die Zelle löge**.

- **Daher:** ein Grant auf einen Nicht-Member-Agenten fügt **serverseitig atomar Membership hinzu** (= die PO-Vorgabe „Grant macht ihn zum Member") **zusammen** mit dem Eintrag. Erst dann ist der Grant durchsetzbar und die Zelle darf „gewährt/durchgesetzt" zeigen.
- **Disclosure-Fluss (ehrlich):**
  1. Nicht-Member, Switches aus → Marker „kein Mitglied" + a11y „Gewähren fügt als Mitglied hinzu".
  2. Operator schaltet Lesen (oder Antworten) → **optimistisch pending** (`acl_pending` „wird übernommen…") — **keine** vorgetäuschte Membership/Durchsetzung.
  3. Server-Echo (Membership + Grant) → Zelle wird **Member**, Marker `acl_enforced` „durchgesetzt".
  4. Fehler → **revert** (bestehender `revert`-Pfad, AclViewModel:239) → zurück zu Nicht-Member; `acl_change_failed`.
- **§9-KERN-INVARIANTE:** die Zelle behauptet **nie** eine Berechtigung, die Enforcement (Membership-Gate) nicht trägt. „gewährt/durchgesetzt" ERST nach Echo; davor `pending`; nie „Member" vorspiegeln.
- **Revoke-Semantik (Backend-Vertrag, §8):** entfernt das Widerrufen des **letzten** Grants die Membership wieder (Zelle → Nicht-Member-grantbar) oder bleibt ein Member-ohne-Grant? Die **UI spiegelt** nur, was der Server durchsetzt — beides ist ehrlich, solange die Zelle den durchgesetzten Zustand zeigt. Zu fixieren im Backend.

---

## §4 — PO-Leitplanke sichtbar, aber Enforcement = Backend (nicht UI-Optimismus)

Die bestehende Leitplanke **bleibt unverändert** und wird durch die neue Grantbarkeit **nicht** umgangen:
- **Advisory in der UI:** `AclReducer.wouldLockoutPo` (HUB-Kanal, PO-Member verliert Grant) → `LockoutDialog` (`acl_po_lockout_warning`); `poCritical`-Flag → `⚑`-Marker (`a11y_acl_po_critical`); Self-Blind-Warnung.
- **Enforcement = Server:** ein wirklich aussperrender Toggle → **409** → `acl_po_protected` („Geschützt: würde den PO aussperren – Änderung abgelehnt"). Die UI ist **Spiegel**, nicht Quelle.
- **CYP-317-spezifisch:** die Leitplanke betrifft das **Entziehen** von PO-Grants (Lockout), nicht das **Gewähren** an Nicht-Member — ein Grant löst nie einen Lockout aus. Die neue Grantbarkeit **schwächt die Leitplanke nicht**: alle Revoke-Pfade bleiben advisory-in-UI / enforced-am-Server. (PO-Vorgabe explizit: „PO nicht aussperrbar — Durchsetzung ist Backend/Guard, nicht UI-Optimismus.")

---

## §5 — Skalierung (alle Agenten × alle Kanäle bedienbar)

Mit jeder Zelle nun interaktiv wächst die Dichte. Bestehend: `WideGrid` (h+v-Scroll) + `NarrowCards` (Per-Kanal-Auswahl < `PANE_COLLAPSE_WIDTH`). Ergänzungen:

1. **Sticky Header (primär):** die **Kanal-Spalte** (Row-Header, `CHANNEL_COL_WIDTH`) und die **Agent-Header-Zeile** bleiben beim Scrollen **fixiert** — die zentrale Usability-Hilfe für einen wachsenden Grid (heute scrollen die Header weg, Orientierung geht verloren).
2. **Kanal-Filter (empfohlen):** ein leichter Filter/Auswahl im Wide-Modus, um auf eine Teilmenge Kanäle zu fokussieren (weniger Zeilen). Die `NarrowCards`-Per-Kanal-Auswahl ist die schmale Variante davon — **erhalten**.
3. **Agent-Filter (optional):** analog für Spalten, falls viele Agenten.
4. **Reuse:** bestehende h/v-Scroll-Container + Narrow-Card-Fokus bleiben. Kein neuer Layout-Grundtyp; Sticky-Header + Kanal-Filter sind additive Naht.

> Schlank halten: Kern = schaltbare Zellen; Skalierung = **Sticky-Header + Kanal-Filter** (Agent-Filter optional). Nicht über-bauen.

---

## §6 — Copy-Keys (DE + EN)

**NEU (je DE+EN):**
| Key | DE | EN |
|---|---|---|
| `acl_grant_adds_member` | Gewähren fügt als Mitglied hinzu. | Granting adds as a member. |

Getragen als **a11y-Zusatz** (und optional dezente Inline-Mikrozeile) auf der Nicht-Member-Zelle → macht die Konsequenz „Grant ⇒ Membership" (§3) explizit, bevor der Operator schaltet.

**REUSE (0 neu):** `acl_read`/`acl_write` (Switch-Labels) · `acl_granted`/`acl_denied` (read-only Chips) · `acl_non_member` „kein Mitglied" (jetzt der **Affordanz-Marker** der grantbaren Zelle, nicht mehr inert) · `acl_pending`/`acl_enforced` (Disclosure) · `a11y_acl_toggle_read`/`a11y_acl_toggle_write` (tragen schon Subjekt+Kanal) · `a11y_acl_cell_nonmember` „%1$s ist kein Mitglied von %2$s" (Marker-a11y, weiter korrekt) · `a11y_acl_pending` · PO-Leitplanke `acl_po_lockout_warning`/`acl_po_protected`/`a11y_acl_po_critical` · `acl_change_failed`.

> **⚠️ Shared-Key-Drift:** der neue Key landet in `values/strings.xml` (DE) **und** `values-en/strings.xml` (EN) **synchron** mit dem Bau. Timing abstimmen.

---

## §7 — testTags (`aclMatrix.*`, shared QA/CYP-7)

- **Reuse:** `aclMatrix.cell.<ch>.<agent>` (+`.read`/`.write`/`.readonly`), `cellQualifier(…, PENDING/ENFORCED/CONFLICT/PO_CRITICAL)`.
- **`CellQualifier.NON_MEMBER("nonMember")` bleibt** — sitzt jetzt auf dem **Affordanz-Marker** der grantbaren Zelle (der „kein Mitglied"-Text/Outline), **nicht** mehr auf einem inerten „—". QA-Vertrag: eine Nicht-Member-Zelle trägt **sowohl** `NON_MEMBER`-Qualifier **als auch** die `.read`/`.write`-Switch-Knoten (= schaltbar, war vorher gegenseitig ausschließend).
- **Skalierung:** falls Sticky-Header/Filter eigene Knoten brauchen → neue Tags im `aclMatrix.*`-Namensraum (z. B. `aclMatrix.channelFilter`), vor Landung gegen `AclMatrixTags.kt` verifizieren ([[verify-reuse-testtags-against-code]]) + mit QA/CYP-7 syncen.

---

## §8 — Backend-/Contract-Deps

1. **Grant ⇒ Membership (atomar, §3-Kern):** `PUT /api/acl` auf einen **Nicht-Member-Agenten** fügt server-seitig **Membership** hinzu (in `Channel.members`) **zusammen** mit dem Eintrag → der Grant wird durchsetzbar und die Zelle spiegelt echte Membership+Grant. (Spiegelbild zu `humanCells`, wo Enforcement membership-unabhängig ist; für Agenten trägt die Membership das Hub-Routing.)
2. **Revoke-Semantik:** definiert das Backend, ob das Widerrufen **aller** Grants die Membership wieder entfernt (Zelle → Nicht-Member-grantbar) oder Member-ohne-Grant bleibt. UI = Spiegel.
3. **PO-Leitplanke** bleibt server-enforced (409 → `acl_po_protected`), unverändert (§4).
4. **`AclMatrix`** braucht keine UI-seitige Änderung, sobald das Backend Membership mit dem Grant setzt — die bestehende effektive-Grant-Logik greift dann normal.

---

## §9 — Invarianten (= UX-QA-Abnahme)

1. **Jede Agent×Kanal-Zelle schaltbar** — Member **und** Nicht-Member; kein inertes „—" mehr für grantbare Nicht-Member; Member-Zellen regressions-frei.
2. **Klare Affordanz-Differenz, non-color** — Member vs. Nicht-Member-grantbar via **Border-Stil + „kein Mitglied"-Text (+a11y)**, nie nur Farbe (WCAG 1.4.1).
3. **Honesty: Grant erst nach Echo durchgesetzt** — Nicht-Member-Grant zeigt `pending` bis zum Server-Echo, dann `enforced`; nie vorgetäuschte Membership/Berechtigung; Fehler → revert.
4. **Grant ⇒ Membership** — die Zelle behauptet nie eine Berechtigung ohne die tragende Membership; Membership wird server-seitig mit dem Grant hinzugefügt.
5. **PO-Leitplanke unverändert + sichtbar** — advisory `LockoutDialog`/`⚑`/`acl_po_protected`; Enforcement Backend (409), **nie** UI-Optimismus; neue Grantbarkeit umgeht die Leitplanke nicht.
6. **Kein grüner SUCCESS** — Grant-/Zustandsmarker neutral über M3-Rollen (Switch-Glyph ✓/✕, enforced-Punkt, pending-Text), kein grün.
7. **Skalierung bedienbar** — Sticky-Header (Kanal-Spalte + Agent-Zeile) + Kanal-Filter; Narrow-Per-Kanal-Fokus erhalten; h/v-Scroll bleibt.
8. **i18n + a11y** — DE+EN-Parität; Zustände als Text/Form nicht nur Farbe; a11y-Labels tragen Subjekt+Kanal **+** „Gewähren fügt hinzu".
9. **Fail-closed / partial-view unverändert** — Nicht-Operator = read-only Chips (kein Switch); terminaler Revoke (CYP-296) = read-only, keine schaltbaren Nicht-Member-Zellen.

---

## §10 — Hand-off

- **Kein Bau, kein Merge.** Docs-only auf `feature/CYP-317-acl-flexible-spec` (distinkter Worktree `CYP-317-spec`).
- **Reuse-Anker:** `AclCellView`/`GrantControl` (Switches) · `humanCells`-Muster (membership-unabhängige Grantbarkeit) · `AclReducer`/`AclMatrix` · PO-Leitplanke (`wouldLockoutPo`/`LockoutDialog`/`acl_po_protected`) · Narrow-Card-Per-Kanal-Fokus.
- **Keys/Tags** synchron mit dem konsumierenden Modul landen (shared QA/CYP-7); Tags gegen `AclMatrixTags.kt` verifizieren.
- **UX-QA nach Bau:** die 9 §9-Invarianten, gerendert — Nicht-Member-Grant-Fluss (aus → pending → Member), Affordanz-Differenz, PO-Leitplanke, Skalierung (Sticky+Filter), DE+EN, Operator + Non-Operator (partial-view).
