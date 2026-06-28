# Cross-Projekt — Kanal-Autorisierung & Event-Log-Projekt-Filter (S17-Vorlauf · Epic CYP-79)

> Owner: UIUX-Designer · Epic **CYP-79** „S17 — Cross-Projekt-Kanäle" [MP] · Stand 2026-06-28 ·
> Status: **Vorlauf-Entwurf** — wartet auf PO-Gegenlesen/Routing
> Begleit-Design-Files: `docs/design/cross-project-{tokens.json,keys.md,tags.md}`
> Gegroundet gegen develop **`3705b68`** (S13-Modell gemergt: Switcher/Active-Pointer/Scope-Grenze).
> Stories: **CYP-93** S17.1 Cross-Projekt-Kanal anlegen/freigeben (Single-User-Eigentümer-Zustimmung) ·
> **CYP-94** S17.2 Event-Log-Projekt-Filter.

---

## 0. Auftrag, Vorlauf-Charakter & Abgrenzung

S17 öffnet die fail-closed-Projektgrenze (S12/S13) **kontrolliert**: ein Kanal darf — explizit autorisiert
— über die Projektgrenze reichen, und der Event-Log darf projektübergreifend gelesen werden. Dieser
Vorlauf designt die **zwei am wenigsten gekoppelten** Stücke:

- **CYP-93** — die **Autorisierungs- und Disclosure-Schicht** für cross-projekt-Kanäle: Freigabe als
  **Eigentümer-Zustimmungsakt** (nicht „Mitglied hinzufügen"), Single-User = **ein** Eigentümer (anlegen +
  selbst-freigeben), plus die ehrliche Anzeige des Cross-Projekt-Zustands.
- **CYP-94** — der **Event-Log-Projekt-Filter** als projektübergreifende Lese-Linse (read-only).

**HOLD (PO-Auflage):** Alles, was an der konkreten **Cross-Projekt-Kanal-Mitgliedschafts-Verdrahtung**
hängt (wie ACL/Channel im Backend die Projektgrenze technisch überspannen — das **Endpunkt-Scoping**),
bleibt liegen, bis Backends Scoping-Stage landet. Hier wird die **Autorisierungs-/Disclosure-Schicht**
designt, **nicht** die Transport-Details. Die Mitgliedschaft selbst trägt die **bestehende ACL**
(`canRead`/`canWrite` pro Agent, Spec 02 §5.3) **ohne neuen Mechanismus** — der Vorlauf wickelt nur den
**Autorisierungsakt** darum.

**Außerhalb (bewusst):**
- **Bilaterale Zustimmung** mehrerer Eigentümer (Multi-User) = **S18**, deferred. Hier **nicht** als
  garantiert darstellen (§2.2).
- **Repo-Config** = S15/CYP-84. **Projekt-CRUD/Switcher** = S13/CYP-78 (gemergt, Fundament hier genutzt).

---

## 1. Mentales Modell

Die Projektgrenze ist heute **strukturell fail-closed**: `ProjectScope.permits(entityPid, activePid)` ist
**exakt-gleich**, ohne Wildcard — `Channel`/`AclEntry`/`Message`/`Event` mit fremdem `projectId` werden
aus der `AclMatrix` **gedroppt** (verifiziert `3705b68`). Cross-Projekt ist deshalb **kein** beiläufiges
„Mitglied hinzufügen", sondern ein **bewusst gestochenes, autorisiertes Loch** in dieser Grenze.

Daraus die zwei Leitgedanken des Vorlaufs:

1. **Cross-Projekt entsteht nur durch einen expliziten Autorisierungsakt.** Die Mitgliedschaft (ACL)
   trägt die Reichweite technisch; **legitimiert** wird das Überschreiten durch die **Eigentümer-
   Freigabe**. Ohne Freigabe bleibt die Grenze fail-closed (kein Cross-Projekt-Effekt).
2. **Wer wohin reicht, ist jederzeit ehrlich sichtbar.** Ein Kanal, der über Projekte reicht, sagt das
   unmissverständlich (welche Projekte, von wem/wann freigegeben) — sonst wirkt eine Nachricht
   unbemerkt über die Grenze.

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

### 2.3 Der Autorisierungsakt (Dialog)

Aus dem Kanalkontext (Comm-Kanalliste `comm.channel.<id>` bzw. ACL-Zeilenkopf
`aclMatrix.rowHeader.<channelId>`) öffnet die Aktion **„Projektübergreifend freigeben"**
(`crossProject.authorize`, `crossproject_authorize`) den Autorisierungs-Dialog (`crossProject.dialog`),
**operator/owner-gated/fail-closed**:

- **Reichweite/Folgen** (`crossProject.dialog.scope`, `crossproject_dialog_scope` = „Dieser Kanal erreicht
  dann: %1$s") — benennt **explizit, welche Projekte und welche Agenten** der Kanal nach der Freigabe
  erreicht. Die garantierte „was passiert"-Aussage (HintTone.INFO).
- **Eigentümer-Zustimmung** (`crossProject.dialog.ownerConsent`, `crossproject_owner_consent` = „Ich gebe
  diesen Kanal als Eigentümer projektübergreifend frei.") — eine **bewusste** Bestätigungs-Affordanz
  (Checkbox/expliziter Schritt), nicht ein stiller Default.
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
  - freigegeben → `crossproject_status_shared` = „Projektübergreifend: %1$s · freigegeben am %2$s"
    (%1$s=Projektliste, %2$s=Zeitpunkt) — **wer/wann** ist Teil der ehrlichen Anzeige.
  - **nicht** freigegeben → `crossproject_status_not_shared` = „Nur in diesem Projekt – nicht
    projektübergreifend freigegeben." → der **fail-closed-Default** ist explizit, kein Graubereich.
- Ohne Operator/Eigentümer-Token: `crossProject.gateHint` (`crossproject_operator_required`) — read-only,
  Freigabe-Aktion deaktiviert.

### 2.5 Freigabe zurücknehmen (Revoke)

`crossProject.revoke` (`crossproject_revoke` = „Freigabe zurücknehmen") schließt das Loch wieder: der
Kanal fällt auf **projekt-lokal/fail-closed** zurück. Operator/owner-gated. (Revoke entfernt die
Cross-Projekt-Sichtbarkeit; die ACL-Einträge selbst sind Sache der Membership-Verdrahtung — §2.7/HOLD.)

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

### 2.7 HELD — Membership-Verdrahtung / Transport (Backend-Naht)

**Nicht** in diesem Vorlauf: **wie** ACL/Channel die Projektgrenze technisch überspannen (Endpunkt-
Scoping, projektübergreifende `members`/`AclEntry`-Persistenz, der Cross-Projekt-`ProjectScope`-Pfad).
Die bestehende ACL **trägt** die Mitgliedschaft (Epic-Vorgabe); dieser Vorlauf liefert nur die
**Autorisierungs-/Disclosure-Hülle**. Vertrag/Flag-Namen werden mit Backends Scoping-Stage reconciled
(§5). **Wenn die UI-Schicht an eine harte Transport-Abhängigkeit stößt → an PO melden** (dann ggf.
S13-UX-QA vorziehen).

---

## 3. CYP-94 — Event-Log-Projekt-Filter (projektübergreifende Lese-Linse)

Der Event-Log ist heute (mit S13-Active-Pointer) serverseitig auf das **aktive** Projekt gescoped; `Event`
trägt `projectId` (server-gestempelt, verifiziert `3705b68`). CYP-94 gibt dem **Operator/Eigentümer** eine
**read-only** Linse, Ereignisse **eines anderen** oder **aller** Projekte zu sehen.

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
Projekt-Read**. Die Cross-Projekt-Abfrage **umgeht bewusst** das Active-Pointer-Scoping — das ist eine
**explizite Operator-Fähigkeit** und muss serverseitig als solche durchgesetzt werden (fail-closed; der
Operator/Eigentümer sieht nur **eigene** Projekte). Siehe §5 (Lese-Override-Naht; **nicht** die gehaltene
Membership-Verdrahtung).

---

## 4. Disclosure-Disziplin (Zusammenfassung)

| Prinzip | Umsetzung hier |
|---|---|
| **Zugriffsänderung nur durch Menschen** | Cross-Projekt-Freigabe ist owner/operator-gated; keine „aus Nachricht/Agent freigeben"-Affordanz; Server autoritativ (§2.6). |
| **Garantiert ≠ advisory** | Single-Owner-Freigabe ehrlich benannt; **keine** Implikation bilateraler Zustimmung (S18) (§2.2). |
| **fail-closed-Default sichtbar** | „Nur in diesem Projekt – nicht freigegeben" ist explizit (§2.4); Event-Linse default = aktives Projekt (§3.1). |
| **Reichweite nie unbemerkt** | Cross-Projekt-Badge + Status (welche Projekte, von wem/wann) (§2.4); Event-Sicht-Indikator + Pro-Zeile-Projekt (§3.3). |
| **Identität ≠ Recht ≠ Severity** | Cross-Projekt-Badge sagt „reicht", nicht „darf"; Projekt-Spalte = Identität, getrennt von Severity/Recht (CYP-14). |
| **Farbe nie alleiniger Träger (WCAG 1.4.1)** | Badge/Marker = Form/Glyph + Label; Projekt pro Zeile = Text; alle Hinweise = TonedHint. |
| **operator-gated / fail-closed** | Freigabe + Cross-Projekt-Read beide ohne Token unmöglich; Omission/Gate ehrlich. |

---

## 5. Datenpfad & Backend-Naht (greenfield / teils HELD — reconcilen)

Verifiziert gegen `3705b68`: Fundament steht (`Event.projectId` server-gestempelt; `Channel`/`AclEntry`
projectId-scoped; `Project`/`ProjectsView`/`SwitchActiveRequest` aus S13). **Neu/greenfield:**

1. **Cross-Projekt-Autorisierung (Vertrag, designt):** ein owner/operator-gated Endpunkt, der die
   Cross-Projekt-Freigabe eines Kanals **setzt/zurücknimmt** und **wer/wann** festhält (für die Status-
   Anzeige §2.4). Reuse `requireOperator()` (403 `operator_required`/401). Server **autoritativ**
   (fail-closed; nie aus untrusted-Input). **Flag-/Endpunkt-Namen mit Backend reconcilen.**
2. **Membership-Verdrahtung (HELD, §2.7):** wie `members`/`AclEntry` die Grenze technisch überspannen
   (Endpunkt-Scoping, Cross-Projekt-`ProjectScope`-Pfad) — Backend baut die Scoping-Stage; UI-Schicht
   wartet darauf. Bestehende ACL trägt die Mitgliedschaft (kein neuer Mechanismus).
3. **Event-Log-Lese-Override (Naht, designt):** `EventFilter` (heute `agentId/type/severity/since/until/
   correlationId/sessionId`, **kein `projectId`** — verifiziert `EventContract.kt`/`EventSink.kt`) +
   `projectId`-Achse erweitern; REST-Query-Param `projectId` (bzw. „all") ergänzen (`EventsApiClient`
   baut Query-Params). Der Override **umgeht das Active-Pointer-Scoping bewusst** und ist **operator-only**
   (fail-closed; nur eigene Projekte). **Kein** Leak content-freier Severity/Type-Regel verletzt (Events
   sind ohnehin content-frei, PRD §3.5).

---

## 6. Offene Punkte (PO/Backend)

1. **Freigabe-Granularität:** pro Kanal (designt) — bestätigen. Reicht „ein Eigentümer gibt seinen Kanal
   für Projekt Y frei" als Single-User-Modell, oder braucht es schon eine Projekt-Paar-Notation?
2. **Status-Provenienz:** liefert das Backend „freigegeben von <wer> am <ts>" zur Anzeige (§2.4), oder
   nur ein boolesches „cross-project: ja/nein" (dann Status ohne wer/wann)?
3. **Event-Lese-Override-Form:** `projectId`-Param mit Spezialwert „all" vs. separater Cross-Projekt-
   Endpunkt — Backend-Präferenz (§5.3).
4. **Revoke-Semantik:** entfernt Revoke nur die Sichtbarkeit/Autorisierung, oder auch die
   cross-projekt-`AclEntry`s? (hängt an der gehaltenen Membership-Verdrahtung, §2.7).
5. **Timing:** Wann landet Backends Endpunkt-Scoping-Stage (entscheidet, wann CYP-93-Membership baubar
   wird; bis dahin ist nur die Autorisierungs-/Disclosure-Schicht implementierbar)?

---

## 7. Self-Validation

- **Artefakte:** diese Spec + `cross-project-{tokens.json,keys.md,tags.md}` (Muster CYP-17).
- **Vorlauf-Disziplin:** Autorisierungs-/Disclosure-Schicht designt; **Membership-Transport explizit
  GEHALTEN** (§2.7/§5.2) wie vom PO verlangt; bilaterale Zustimmung als S18 abgegrenzt (kein
  Überversprechen).
- **Reuse gegen Code (develop `3705b68`) verifiziert:** `Event.projectId`, `EventFilter` **ohne**
  `projectId` (EventContract.kt/EventSink.kt → Erweiterung nötig), `EventsApiClient` Query-Param-Bau,
  `CommTags.channel(id)`=`comm.channel.<id>`, `aclMatrix.rowHeader.<channelId>`, `EventLogTags`
  (eventBrowse/eventTail), `ProjectScope.permits` (exakt-gleich/fail-closed), `requireOperator`,
  `ui/TonedHint.kt`+`HintTone`, CYP-14 `ColorSlot` (Identität≠Recht), `event_filter_*`-Key-Familie.
- **Keys:** `crossproject_*` greenfield (0 Kollision); `event_filter_project`/`_all`/`event_view_*`/
  `event_row_project` reihen sich in die **bestehende** `event_*`-Surface-Familie (kein Cross-Surface-
  Drift). DE+EN-Parität Pflicht.
- **Tags:** neue Area `crossProject` (CYP-93) + **Additionen** zu den bestehenden `eventBrowse`/`eventTail`
  -Areas (CYP-94); Test-Contract v0.5 §2-konform.
- **Security:** Anti-Injection-Invariante (§2.6) load-bearing dokumentiert; Freigabe = owner/operator-
  gated, Server autoritativ, fail-closed; keine „aus Nachricht freigeben"-Affordanz.
