# Cross-Projekt — Kanal-Autorisierung & Event-Log-Projekt-Filter (S17 · Epic CYP-79)

> Owner: UIUX-Designer · Epic **CYP-79** „S17 — Cross-Projekt-Kanäle" [MP] · Stand 2026-06-28 ·
> Status: **Finalisiert** — §6 PO-entschieden gefaltet, **§2.7-HOLD aufgehoben** (CYP-102 gelandet);
> Membership-Transport mitdesignt, Permit-Mechanismus als Backend-zu-reconcilen markiert.
> Begleit-Design-Files: `docs/design/cross-project-{tokens.json,keys.md,tags.md}`
> Gegroundet gegen develop **`f73a527`** (S13 komplett gemergt; CYP-102 Endpunkt-Scoping gelandet:
> events/comm rescope per Active-Pointer + WS-Scope-Pin).
> Stories: **CYP-93** S17.1 Cross-Projekt-Kanal anlegen/freigeben (Single-User-Eigentümer-Zustimmung) ·
> **CYP-94** S17.2 Event-Log-Projekt-Filter.

---

## 0. Auftrag, Vorlauf-Charakter & Abgrenzung

S17 öffnet die fail-closed-Projektgrenze (S12/S13) **kontrolliert**: ein Kanal darf — explizit autorisiert
— über die Projektgrenze reichen, und der Event-Log darf projektübergreifend gelesen werden. Diese Spec
deckt die **zwei am wenigsten gekoppelten** Stücke:

- **CYP-93** — die **Autorisierungs- und Disclosure-Schicht** für cross-projekt-Kanäle **plus den
  Membership-Transport** (kein HOLD mehr — CYP-102 ist gelandet): Freigabe als **Eigentümer-
  Zustimmungsakt** (nicht „Mitglied hinzufügen"), Single-User = **ein** Eigentümer (anlegen +
  selbst-freigeben), plus die ehrliche Anzeige des Cross-Projekt-Zustands und der konkreten Reichweite.
- **CYP-94** — der **Event-Log-Projekt-Filter** als projektübergreifende Lese-Linse (read-only).

**§2.7-HOLD aufgehoben (CYP-102 gelandet, develop `f73a527`):** Der Membership-Transport wird jetzt
**mitdesignt** (nicht mehr gehalten). Die Mitgliedschaft selbst trägt die **bestehende ACL**
(`canRead`/`canWrite` pro Agent, Spec 02 §5.3) **ohne neuen Mechanismus** — diese Spec wickelt den
**Autorisierungsakt** darum **und** designt die **Reichweiten-/Member-Disclosure** (§2.7). **Einzig der
konkrete Permit-Mechanismus** (wie ein autorisierter Cross-Projekt-Kanal die exact-match-
`ProjectScope.permits`-Grenze überspannt) ist als **Backend-zu-reconcilen** markiert (PO seedet Backend
parallel die Architektur-Frage) — §1/§5.

**Außerhalb (bewusst):**
- **Bilaterale Zustimmung** mehrerer Eigentümer (Multi-User) = **S18**, deferred. Hier **nicht** als
  garantiert darstellen (§2.2) — die **Datenform** der Zustimmung ist aber **1→N-fähig** (§2.2/§5).
- **Repo-Config** = S15/CYP-84. **Projekt-CRUD/Switcher** = S13/CYP-78 (gemergt, Fundament hier genutzt).

---

## 1. Mentales Modell

Die Projektgrenze ist **strukturell fail-closed**: `ProjectScope.permits(entityPid, activePid)` ist
**exakt-gleich**, ohne Wildcard — `Channel`/`AclEntry`/`Message`/`Event` mit fremdem `projectId` werden
aus der `AclMatrix` **gedroppt** (verifiziert `f73a527`). CYP-102 scopt zusätzlich serverseitig per
**Active-Pointer** (events/comm). Cross-Projekt ist deshalb **kein** beiläufiges „Mitglied hinzufügen",
sondern ein **bewusst gestochenes, autorisiertes Loch** in dieser Grenze.

> **Permit-Mechanismus = Backend-zu-reconcilen:** Wie ein autorisierter Cross-Projekt-Kanal die
> exact-match-Grenze technisch überspannt (der konkrete `ProjectScope.permits`-Pfad für freigegebene
> Kanäle), ist die zentrale offene Architektur-Frage — Backend baut/reconciled das parallel. Diese Spec
> liefert die Autorisierungs-/Disclosure-Hülle **vollständig** und markiert nur diesen einen Mechanismus.

Daraus die drei Leitgedanken:

1. **Cross-Projekt entsteht nur durch einen expliziten Autorisierungsakt.** Die Mitgliedschaft (ACL)
   trägt die Reichweite technisch; **legitimiert** wird das Überschreiten durch die **Eigentümer-
   Freigabe**. Ohne Freigabe bleibt die Grenze fail-closed (kein Cross-Projekt-Effekt).
2. **Kein Over-Widen — Freigabe weitet auf DIESEN Kanal, nicht auf das ganze fremde Projekt.** Eine
   Freigabe macht **genau die expliziten Mitglieder dieses einen Kanals** sichtbar: Agent A erhält B's
   **einen geteilten Kanal**, **nicht** B's gesamtes Projekt. Das ist gleichzeitig **Disclosure-Wahrheit**
   und **Sicherheitsgrenze** — Anzeige nennt **konkrete Agenten/den Kanal**, nie „Projekt B" pauschal.
3. **Wer wohin reicht, ist jederzeit ehrlich sichtbar.** Ein Kanal, der über Projekte reicht, sagt das
   unmissverständlich (welche **Agenten** aus welchem Projekt, von wem/wann freigegeben) — sonst wirkt
   eine Nachricht unbemerkt über die Grenze.

> **Sicherheits-Invariante (load-bearing, §2.6):** Eine Cross-Projekt-Freigabe ist eine **Zugriffs-
> änderung**. Sie wird **ausschließlich** durch den **menschlichen Eigentümer** über die explizite UI
> ausgelöst — **niemals** durch einen Agenten, eine Kanal-Nachricht oder sonstigen untrusted-Input.
> Kanalinhalt ist Daten, keine Anweisung.

---

## 2. CYP-93 — Cross-Projekt-Kanal: Eigentümer-Autorisierung

### 2.1 Autorisierung, nicht „Mitglied hinzufügen" (Framing)

Die Freigabe eines Kanals über die Projektgrenze wird als **Autorisierungsakt** gerahmt — eine bewusste,
benannte Eigentümer-Handlung mit Folgenanzeige —, **nicht** als beiläufiges Hinzufügen eines Mitglieds.
Das spiegelt das mentale Modell (§1): die ACL trägt die Mitgliedschaft, aber das **Überschreiten der
Grenze** ist die autorisierungspflichtige Handlung.

### 2.2 Single-Owner (S17) vs. bilateral (S18) — Disclosure-Ehrlichkeit

Im Single-User-MVP gibt es **genau einen Eigentümer**; Freigeben heißt **anlegen + selbst-freigeben**.
Die UI **benennt das ehrlich** (`crossproject_single_owner_note`): „Single-User: ein Eigentümer gibt
frei. Gegenseitige Zustimmung mehrerer Eigentümer folgt später." → **Keine** Implikation einer
**gegenseitigen** Zustimmung, die es (noch) nicht gibt. Das ist die Disclosure-Honesty-Linie:
*advisory ≠ garantiert*, *Single-Owner ≠ bilateral*.

> **Owner-Consent als 1→N-Form (Reviewer-Leitplanke, PO):** Die **Datenform** der Zustimmung wird als
> **Sammlung** von Eigentümer-Zustimmungen modelliert (wer hat zugestimmt), **nicht** hartcodiert auf
> einen — trivial **N=1** heute, **S18** füllt die bilaterale/mehrseitige Zustimmung **ohne Umbau**. Das
> ist die „Eins-auf-N"-Naht. Die **Disclosure bleibt ehrlich Single-Owner** (oben); nur das Modell ist
> N-fähig. (Backend-Vertrag §5: Zustimmung = Set, nicht Bool.)

### 2.3 Der Autorisierungsakt (Dialog)

Die Freigabe ist **gerichtet** (PO-§6-Addendum b): der Eigentümer teilt **seinen** Kanal **hinaus** —
keine gegenseitige Freigabe (deckt sich mit dem Single-Owner-Modell §2.2). Aus dem Kanalkontext
(Comm-Kanalliste `comm.channel.<id>` bzw. ACL-Zeilenkopf `aclMatrix.rowHeader.<channelId>`) öffnet die
Aktion **„Projektübergreifend freigeben"** (`crossProject.authorize`, `crossproject_authorize`) den
Autorisierungs-Dialog (`crossProject.dialog`), **operator/owner-gated/fail-closed**:

- **Reichweite/Folgen — zwei-phasig, kein Over-Widen** (`crossProject.dialog.scope`,
  `crossproject_dialog_scope`): **Pre-Share** nennt die **Ziel-Projekte** + den ehrlichen Hinweis „Konkrete
  Agenten je nach deren Leserechten – erscheinen nach der Freigabe" (Kontrakt: `reachableScope` ist leer
  bis `shared`, also gibt es **vor** der Freigabe keine konkreten Agenten zu nennen). **Post-Share** nennt
  die **konkreten Member-Agenten** in der Status-Zeile (§2.4) — **nie** „Projekt B" pauschal **mit
  Auto-Access**. Jeder Cross-Projekt-Member wird über `crossproject_member_access` gerendert („%1$s
  (Projekt %2$s) – %3$s", %3$s = Zugriff). **Default eines neuen Cross-Projekt-Members = lesend**
  (`crossproject_access_read`); **schreiben nur per expliziter ACL** (`crossproject_access_write`) — **kein
  Auto-Read für alle Agenten des fremden Projekts**. Garantierte „was passiert"-Aussage (HintTone.INFO).
- **Eigentümer-Zustimmung** (`crossProject.dialog.ownerConsent`, `crossproject_owner_consent` = „Ich gebe
  diesen Kanal als Eigentümer projektübergreifend frei.") — eine **bewusste** Bestätigungs-Affordanz
  (Checkbox/expliziter Schritt), nicht ein stiller Default. **Datenform 1→N-fähig** (§2.2): die Zustimmung
  ist ein Eintrag in der Eigentümer-Zustimmungs-Sammlung (heute genau einer).
- **Human-only-Hinweis** (`crossProject.dialog.humanOnlyNote`, `crossproject_human_only` = „Nur du als
  Eigentümer gibst frei – niemals ein Agent oder eine Nachricht.") — macht die Sicherheits-Invariante
  (§2.6) **im UI sichtbar** (HintTone.INFO).
- **Single-Owner-Hinweis** (`crossProject.dialog.singleOwnerNote`, §2.2).
- **Bestätigen** (`crossProject.dialog.confirm`, `crossproject_confirm` = „Freigeben") / **Abbrechen**
  (`crossProject.dialog.cancel`). Fehler → `crossProject.dialog.error` (`crossproject_error`).

### 2.4 Disclosure: Cross-Projekt-Zustand + Badge

- **Badge** am Kanal (`crossProject.badge.<channelId>`, `crossproject_badge` = „Projektübergreifend";
  a11y `a11y_crossproject_badge`): markiert eine cross-projekt-Reichweite **mit Form/Glyph + Label**,
  nicht über Farbe allein (WCAG 1.4.1) — und **Identität ≠ Recht** (CYP-14): der Badge sagt „reicht über
  Projekte", nicht „du darfst".
- **Status-Zeile** (`crossProject.status`):
  - freigegeben → `crossproject_status_shared` = „Projektübergreifend freigegeben am %1$s · erreicht: %2$s"
    (%1$s=Zeitpunkt aus `sharedAt`, %2$s=konkrete erreichte Agenten) — **wann + konkrete Reichweite**, nie
    „Projekt B" pauschal. **`sharedBy` (wer) ist S18-deferred** (PO-§6.2): im Single-User ist es trivial
    der eine Operator → wird **nicht** angezeigt/gefordert.
  - **nicht** freigegeben → `crossproject_status_not_shared` = „Nur in diesem Projekt – nicht
    projektübergreifend freigegeben." → der **fail-closed-Default** ist explizit, kein Graubereich.
- **Cross-Projekt-Member-Disclosure** (Membership-Transport, §2.7): in der Kanal-Mitglieder-/ACL-Ansicht
  trägt jeder Member aus einem **anderen** Projekt eine Heimat-Projekt-Kennung
  (`crossProject.member.<agentId>`, `crossproject_member_project` = „Aus Projekt %1$s") — als **Text**
  (Identitäts-Träger), nicht über Farbe allein; **Identität ≠ Recht** (der Zugriff steht in der ACL, die
  Heimat-Kennung sagt nur „gehört zu Projekt X"). So ist die über die Grenze reichende Mitgliedschaft
  **explizit per-Agent sichtbar**, statt impliziter Projekt-Vermischung.
- Ohne Operator/Eigentümer-Token: `crossProject.gateHint` (`crossproject_operator_required`) — read-only,
  Freigabe-Aktion deaktiviert.

### 2.5 Freigabe zurücknehmen (Revoke)

`crossProject.revoke` (`crossproject_revoke` = „Freigabe zurücknehmen") schließt das Loch wieder: der
Kanal fällt **sofort** auf **projekt-lokal/fail-closed** zurück. Operator/owner-gated.

> **Revoke-Semantik (PO-§6.4):** Die **Autorisierung ist das Gate**, nicht die `AclEntry`s. Autorisierung
> aus → **sofort fail-closed**, **unabhängig** davon, ob die cross-projekt-`AclEntry`s schon aufgeräumt
> sind: ein revoke-ter Kanal ist auch mit **lingernden Einträgen** projekt-lokal (die exact-match-Grenze
> greift wieder). Entry-Cleanup gehört zur Membership-Verdrahtung, aber **fail-closed darf nie davon
> abhängen**. UI: Status fällt zurück auf `crossproject_status_not_shared`, Badge verschwindet.

### 2.6 Anti-Injection-Invariante (load-bearing Security)

Eine Freigabe ist eine **Zugriffsänderung** und unterliegt damit derselben Disziplin wie ACL/Pairing:
**nur der menschliche Eigentümer autorisiert, out of band** — **nie** ein Agent, eine Kanal-Nachricht
oder anderer untrusted-Input. Konkrete UI-Konsequenzen:
- Es gibt **keine** Affordanz, eine Freigabe aus einer Nachricht/einem Agenten-Request **anzunehmen**
  (kein „Agent X bittet um Cross-Projekt-Zugang → freigeben"-Knopf). Eine solche Bitte ist Daten, kein
  Handlungsangebot.
- Die Freigabe ist **operator/owner-gated** (Server autoritativ, fail-closed) — UI-Affordanz nur mit
  Token, der Server setzt es ebenfalls durch (Präzedenz CYP-49).
- Der `crossproject_human_only`-Hinweis (§2.3) macht die Invariante für den Eigentümer sichtbar.

### 2.7 Membership-Transport (HOLD aufgehoben — CYP-102 gelandet)

Die bestehende ACL **trägt** die Mitgliedschaft (`canRead`/`canWrite` pro Agent, Epic-Vorgabe) — **kein
neuer Mechanismus**. Diese Spec designt die **Disclosure** des Transports vollständig:

- **Mitgliedschaft bleibt explizit per-Agent** (PO-§6-Addendum c): die Freigabe ist das **Gate**, nicht
  ein Massen-Beitritt. Ein neuer Cross-Projekt-Member ist **lesend** per Default; **schreiben nur per
  expliziter ACL** — **kein** Auto-Read für alle Agenten des fremden Projekts.
- **Reichweite/Member-Disclosure:** Status-Zeile nennt die konkreten erreichten Agenten (§2.4); jeder
  fremd-projektige Member trägt seine Heimat-Projekt-Kennung (`crossProject.member.<agentId>`).
- **Datenform 1→N-fähig** (§2.2): Eigentümer-Zustimmung als Sammlung; Backend-Vertrag §5.

> **Einziger offener Punkt = der Permit-Mechanismus (Backend-zu-reconcilen):** **wie** ein autorisierter
> Cross-Projekt-Kanal die exact-match-`ProjectScope.permits`-Grenze technisch überspannt (heute exact-match
> → ein Kanal ist in genau **einem** Projekt-Scope sichtbar). Das ist das „bewusst gestochene autorisierte
> Loch" auf Code-Ebene — **Backend baut/reconciled es** (PO seedet die Architektur-Frage parallel). Die
> Autorisierungs-/Disclosure-Hülle hier ist **vollständig** und unabhängig davon korrekt (fail-closed:
> ohne Permit-Pfad bleibt alles projekt-lokal — kein Leak). **Stößt der Bau an eine harte
> Permit-Abhängigkeit → an PO melden.**

---

## 3. CYP-94 — Event-Log-Projekt-Filter (projektübergreifende Lese-Linse)

**CYP-102 (gelandet, `f73a527`) scopt den Event-Log serverseitig forced-active:** die Routes setzen
`EventFilter.projectId` aus dem `ProjectRegistry`-Active-Pointer — **nie ein Client-Param** (verifiziert
`EventSink.kt`: „a caller can't widen to another project"); der Client-`EventFilter` (EventContract.kt)
hat **kein** `projectId`. `Event` trägt `projectId` (server-gestempelt). CYP-94 gibt dem
**Operator/Eigentümer** eine **read-only** Linse, Ereignisse **eines anderen** oder **aller** Projekte zu
sehen — als **bewusste, operator-only Relaxierung** dieses forced-active-Defaults (§3.4).

### 3.1 Default = aktives Projekt (ehrlich), Cross-Projekt explizit

Der **Default** ist das aktive Projekt — die Standard-Sicht bleibt scoped und unzweideutig. Ein Wechsel
auf ein anderes Projekt oder „alle" ist eine **explizite** Wahl, klar als **projektübergreifende Sicht**
gekennzeichnet — damit fremde Ereignisse nie für die des aktiven Projekts gehalten werden.

### 3.2 Filter-Control (Projekt-Achse) — Reuse der `event_filter_*`-Familie

Neue Filter-Achse in beiden Surfaces (Browse + Live-Tail), konsistent zur bestehenden Filterleiste
(CYP-41/42): `eventBrowse.filter.project` + `eventTail.filter.project`, Label `event_filter_project`
(„Projekt"), Option „alle" = `event_filter_project_all` („Alle Projekte"). Reiht sich neben
`event_filter_agent/type/severity/timeWindow/correlation` — **gleiche Surface/Familie**, daher
Namens-Reuse (kein Cross-Surface-Drift).

**Vertrag (PO-§6.3):** die Achse ist `projectId` mit **`all`-Sentinel**, getragen über die **bereits in
CYP-102 serverseitig eingeführte `EventFilter.projectId`-Achse** — **kein** separater Endpunkt. Der
Client-`EventFilter` erhält dafür ein **`projectId`-Feld**, das **nur im operator-gated Pfad** als
Query-Param/WS-Subscribe-Feld gesendet wird (§3.4). **`all`** = projektübergreifend (alle eigenen
Projekte); ein konkreter `projectId` = ein anderes Projekt.

### 3.3 Cross-Projekt-Sicht-Indikator + Pro-Zeile-Projekt

- **Sicht-Indikator** (`eventBrowse.crossProjectView` / `eventTail.crossProjectView`): wenn die Linse
  **nicht** das aktive Projekt zeigt → `event_view_project` („Sicht: Projekt %1$s") bzw.
  `event_view_all_projects` („Sicht: alle Projekte (projektübergreifend)"). Analog zum bestehenden
  `event_filter_active`-„Teilmenge"-Indikator, aber für die **Scope**-Dimension. HintTone.INFO.
- **Pro-Zeile-Projekt** (`eventBrowse.row.<index>.project` / `eventTail.row.<index>.project`,
  `event_row_project` = „Projekt: %1$s"): **nur** in der projektübergreifenden Sicht eingeblendet, damit
  Ereignisse verschiedener Projekte **unterscheidbar** sind — als **Text** (Identitäts-Träger), nicht
  über Farbe allein. (Optionaler Identitäts-Tint via CYP-14-`ColorSlot` ist zulässig, **nie** alleiniger
  Träger; Identität ≠ Severity ≠ Recht — drei getrennte Achsen wie EVENT-LOG-UI.)

### 3.4 Operator-gated / fail-closed + Backend-Lese-Naht

Der Event-Log ist bereits **operator-only** (ohne Token wird das Fenster gar nicht angeboten — Omission,
EVENT-LOG-UI §5.6). Die projektübergreifende Linse erbt das: **kein Token → keine Linse, kein Cross-
Projekt-Read**. **CYP-102 nicht schwächen (PO-§6.3, kritisch):** der **Default-Pfad bleibt forced-active**
(serverseitig per Active-Pointer, kein Client-Param); **nur** der **operator-gated Pfad** akzeptiert den
`projectId`/`all`-Param und umgeht damit bewusst das Scoping. Serverseitig durchgesetzt (fail-closed; der
Operator/Eigentümer sieht nur **eigene** Projekte). Siehe §5.3 (Lese-Override-Naht).

---

## 4. Disclosure-Disziplin (Zusammenfassung)

| Prinzip | Umsetzung hier |
|---|---|
| **Zugriffsänderung nur durch Menschen** | Cross-Projekt-Freigabe ist owner/operator-gated; keine „aus Nachricht/Agent freigeben"-Affordanz; Server autoritativ (§2.6). |
| **Garantiert ≠ advisory** | Single-Owner-Freigabe ehrlich benannt; **keine** Implikation bilateraler Zustimmung (S18), Datenform aber 1→N-fähig (§2.2). |
| **Kein Over-Widen** | Freigabe erreicht **nur die expliziten Member-Agenten dieses Kanals** (lesend Default, schreiben per ACL), **nie** „Projekt B" pauschal; Mitgliedschaft explizit per-Agent (§1/§2.3/§2.7). |
| **fail-closed-Default sichtbar** | „Nur in diesem Projekt – nicht freigegeben" ist explizit (§2.4); Revoke → sofort fail-closed unabhängig vom Entry-Cleanup (§2.5); Event-Linse default = forced-active (§3.1/§3.4). |
| **Reichweite nie unbemerkt** | Cross-Projekt-Badge + Status (konkrete erreichte Agenten + wann; `sharedBy`=S18) + Member-Heimat-Kennung (§2.4); Event-Sicht-Indikator + Pro-Zeile-Projekt (§3.3). |
| **Identität ≠ Recht ≠ Severity** | Cross-Projekt-Badge sagt „reicht", nicht „darf"; Projekt-Spalte = Identität, getrennt von Severity/Recht (CYP-14). |
| **Farbe nie alleiniger Träger (WCAG 1.4.1)** | Badge/Marker = Form/Glyph + Label; Projekt pro Zeile = Text; alle Hinweise = TonedHint. |
| **operator-gated / fail-closed** | Freigabe + Cross-Projekt-Read beide ohne Token unmöglich; Omission/Gate ehrlich. |

---

## 5. Datenpfad & Backend-Naht (§6 PO-entschieden — Vertrag)

Verifiziert gegen `f73a527`: Fundament steht (`Event.projectId` server-gestempelt; `Channel`/`AclEntry`
projectId-scoped; `Project`/`ProjectsView`/`SwitchActiveRequest` aus S13; CYP-102 Active-Pointer-Scoping
für events/comm + WS-Scope-Pin). **Vertrag:**

1. **Cross-Projekt-Autorisierung (PO-entschieden):** ein owner/operator-gated Endpunkt, der die **gerichtete**
   Freigabe eines Kanals **setzt/zurücknimmt**. **Granularität = pro Kanal** (§6.1; keine Projekt-Paar-
   Notation). **Status-Provenienz:** Backend liefert `{shared: Bool, sharedAt: ts, reachableScope (Projekte/
   Agenten)}`; Status-String nutzt **`sharedAt` + konkrete erreichte Agenten** — **`sharedBy` deferred S18**
   (§6.2). **Zustimmung = Sammlung (1→N-fähig)**, nicht Bool (§2.2). Reuse `requireOperator()` (403
   `operator_required`/401), Server **autoritativ**, fail-closed.
2. **Membership-Verdrahtung (un-held, CYP-102 gelandet):** ACL trägt die Mitgliedschaft **explizit
   per-Agent** (neuer Cross-Projekt-Member **lesend** Default; schreiben nur per expliziter ACL — **kein**
   Auto-Read aller fremden Agenten, §2.3/§2.7). **Einziger offener Mechanismus = der Permit-Pfad** (wie ein
   autorisierter Kanal die exact-match-`ProjectScope.permits`-Grenze überspannt) → **Backend-zu-reconcilen**
   (PO seedet parallel). **Revoke (§6.4):** Autorisierung ist das Gate → Revoke ⇒ **sofort fail-closed**,
   **unabhängig** vom Entry-Cleanup (lingernde Einträge bleiben wirkungslos, exact-match greift wieder).
3. **Event-Log-Lese-Override (PO-§6.3):** `projectId`-Param **+ `all`-Sentinel** auf der **in CYP-102
   serverseitig eingeführten `EventFilter.projectId`-Achse** — **kein** separater Endpunkt. Client-
   `EventFilter` (EventContract.kt, heute ohne `projectId`) erhält das Feld; es wird **nur im operator-gated
   Pfad** als Query-Param/WS-Subscribe gesendet. **Default-Pfad bleibt forced-active (CYP-102 nicht
   schwächen!)**; Override operator-only, fail-closed, **nur eigene Projekte**. Events sind content-frei
   (PRD §3.5) → kein Leak.

---

## 6. Entscheidungen (PO-entschieden — §6.1–§6.5 geschlossen)

1. **Granularität:** **pro Kanal**; keine Projekt-Paar-Notation für Single-User. (→ §5.1)
2. **Status-Provenienz:** Backend liefert `{shared, sharedAt, reachableScope}`; Status = Projekt-/Agent-
   Reichweite + `sharedAt`; **`sharedBy` deferred S18** (Single-Owner = trivial der eine Operator). (→ §2.4/§5.1)
3. **Event-Override-Form:** `projectId`-Param + `all`-Sentinel auf der bestehenden `EventFilter.projectId`-
   Achse, **kein** separater Endpunkt; Override **nur** im operator-gated Pfad, **Default forced-active
   (CYP-102 nicht schwächen)**, fail-closed, nur eigene Projekte. (→ §3.2/§3.4/§5.3)
4. **Revoke:** Autorisierung-off → **sofort fail-closed**; die Autorisierung ist das Gate, **nicht** die
   `AclEntry`s; Entry-Cleanup ist Membership-Sache, aber fail-closed hängt **nicht** davon ab. (→ §2.5)
5. **Timing:** CYP-102 gelandet → **Membership-Verdrahtung jetzt baubar, Teil von CYP-93**; einzig der
   Permit-Mechanismus bleibt Backend-zu-reconcilen. (→ §2.7)

**Reviewer-Leitplanken gefaltet:** (1) **Kein Over-Widen** — Freigabe erreicht nur die expliziten
Member-Agenten dieses Kanals, nicht das ganze fremde Projekt (§1/§2.3); (2) **Owner-Consent als 1→N-Form**
— Zustimmung als Sammlung modelliert, Disclosure bleibt ehrlich Single-Owner (§2.2).

---

## 7. Self-Validation

- **Artefakte:** diese Spec + `cross-project-{tokens.json,keys.md,tags.md}` (Muster CYP-17).
- **Finalisierungs-Disziplin:** §6.1–§6.5 als **PO-entschieden** gefaltet; **HOLD aufgehoben** —
  Membership-Transport mitdesignt (explizit per-Agent, Disclosure vollständig), **nur** der Permit-
  Mechanismus als **Backend-zu-reconcilen** markiert; beide Reviewer-Leitplanken (Kein Over-Widen;
  Owner-Consent 1→N-Form) gefaltet; bilaterale Zustimmung als S18 abgegrenzt (kein Überversprechen).
- **Reuse gegen Code (develop `f73a527`) verifiziert:** `Event.projectId`, `EventFilter` **ohne** Client-
  `projectId` (EventContract.kt) / server-forced-active `projectId` (EventSink.kt, CYP-102 „never a client
  param") → operator-Override ergänzt das Client-Feld, `EventsApiClient` Query-Param-Bau,
  `CommTags.channel(id)`=`comm.channel.<id>`, `aclMatrix.rowHeader.<channelId>`, `EventLogTags`
  (eventBrowse/eventTail), `ProjectScope.permits` (exakt-gleich/fail-closed — Permit-Pfad offen),
  `requireOperator`, `ui/TonedHint.kt`+`HintTone`, CYP-14 `ColorSlot` (Identität≠Recht),
  `event_filter_*`-Key-Familie.
- **Keys:** `crossproject_*` greenfield (0 Kollision); `event_filter_project`/`_all`/`event_view_*`/
  `event_row_project` reihen sich in die **bestehende** `event_*`-Surface-Familie (kein Cross-Surface-
  Drift). DE+EN-Parität Pflicht.
- **Tags:** neue Area `crossProject` (CYP-93, inkl. `member.<agentId>`) + **Additionen** zu den bestehenden
  `eventBrowse`/`eventTail`-Areas (CYP-94); Test-Contract v0.5 §2-konform.
- **Security:** Anti-Injection-Invariante (§2.6) load-bearing; **Kein Over-Widen** (Member-explizit,
  lesend-Default, kein Auto-Read) als Disclosure-Wahrheit **und** Sicherheitsgrenze; Revoke fail-closed
  unabhängig vom Cleanup; Override operator-only ohne CYP-102 zu schwächen; Server autoritativ.
