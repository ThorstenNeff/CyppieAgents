# CYP-525 / M1 — Dogfood-Walkthrough-Copy + Inc-3-Mount-UX-QA-Checkliste

> Owner: UIUX-Designer · **Dogfood-Übergabe-Material** (PO `1526259263…`) · Stand 2026-07-13 · rein Doku/Copy, kein Code.
> Begleit: `device-enroll-{keys,tags,ux-spec}.md` (Guard-ACs GE1–GE7) · `workspace-remote-context-{keys,tags}.md` (CYP-527).
> Zweck: (A) die user-facing Schritt-für-Schritt-Anleitung, die der PO dem Auftraggeber gibt · (B) die Mount-§-QA-Liste,
> die ich am gebauten Enroll-Flow (Dev Inc 3) abarbeite. Copy = **1:1 aus dem Build** (bestehende CYP-460/480-Keys) +
> meine 2 net-new Zeilen (GE6-Ton, GE7-Text). Verifiziert gg. develop `4572a278`.

---

## A. M1-Dogfood-Walkthrough-Copy (für den Auftraggeber)

**Kontext, den der Auftraggeber vorab kennen muss:** Dies ist der **erste** Verbindungsaufbau eines neuen Geräts zu
einem Remote-Hub. Das Gerät wird dabei **eingerichtet** (enrolled). Auf Linux/Desktop-Dogfood ist der Geräteschlüssel
ein **Software-Schlüssel** — das ist erwartet, kein Mangel. Einmalig gezeigte **Wiederherstellungs-Codes** sind die
**einzige** Rückkehr, falls das Gerät verloren geht — es gibt **keinen** zentralen Login-Reset. Bitte die Codes
**wirklich** speichern, bevor du weiterklickst.

Jeder Schritt unten: **Was du siehst · Was du tust · Was du erwarten solltest.**

### Schritt 1 — Verbinden + Identität bestätigen (OOB)
- **Siehst:** Der Verbindungsaufbau läuft durch neutrale Zwischenzustände (Relay wählen → verschlüsselter Handschlag).
  Dann erscheint ein **Fingerprint-Vergleich**: eine nummerierte Wortliste (1–11) plus Hex/QR, mit dem Hinweis
  **„Verifiziere die Identität über einen anderen Kanal, bevor du sie pinnst."** Zwei Ausgänge:
  **„Stimmt überein — pinnen"** und **„Stimmt nicht — abbrechen"**.
- **Tust:** Vergleiche die Wörter mit dem, was die **Hub-Konsole** zeigt. Stimmen sie überein → **„Stimmt überein — pinnen"**.
- **Erwartest:** Der Schritt **blockiert**, bis du bestätigst — es gibt kein Überspringen. „Abbrechen" ist die sichere
  Wahl und bricht sauber ab (nichts wird gepinnt). Kein grünes „Verbunden" an dieser Stelle — das kommt erst am Ende.

### Schritt 2 — Gerät einrichten (Enroll-Step)
- **Siehst:** Titel **„Dieses Gerät einrichten"**, darunter **„App-PIN festlegen"**. Weil dies ein Software-Schlüssel
  ist, erscheint ein **bernsteinfarbener Hinweis mit ▲: „Gilt nur für diese Sitzung — sicherer Geräte-Schlüsselbund
  folgt."**
- **Tust:** Lege eine App-PIN fest.
- **Erwartest:** Der **amber ▲-Hinweis ist erwartetes Verhalten**, kein Fehler (nicht rot). Er sagt ehrlich: dieser
  Schlüssel ist an **diese Sitzung** gebunden, ein hardware-gestützter Schlüsselbund kommt später. (Auf einem Gerät mit
  Hardware-Passkey/Touch-ID/Hello erscheint dieser amber Hinweis **nicht** — dort ist der Schlüssel hardware-gestützt.)

### Schritt 3 — Wiederherstellungs-Codes + Bestätigung (der load-bearing Schritt)
- **Siehst:** Titel **„Wiederherstellungs-Codes"**, die Code-Liste (nummeriert, lesbar, nicht maskiert), und drei
  Wahrheiten in Klartext:
  - **„Bewahre diese Codes offline und sicher auf. Du siehst sie nur dieses eine Mal — sie können nicht erneut angezeigt werden."**
  - **„Jeder Code funktioniert nur einmal."**
  - **„Ohne diese Codes gibt es keinen Weg zurück — ein zentraler Login stellt den Zugriff nicht wieder her."**
  - Button **„Codes kopieren"**, und darunter der Bestätigungs-Button **„Ich habe die Codes sicher gespeichert"**.
- **Tust:** Codes kopieren / offline speichern (Passwort-Manager, Ausdruck). **Erst dann** „Ich habe die Codes sicher
  gespeichert" drücken.
- **Erwartest:** Dies ist ein **hartes Tor** — ohne diese Bestätigung wirst du **nicht** verbunden. Es gibt **kein**
  „später erinnern"/„überspringen", und die Codes werden **nie wieder** gezeigt. Nimm dir hier Zeit: verlierst du die
  Codes, ist das Gerät nicht mehr wiederherstellbar (es gibt keinen zentralen Reset).

### Schritt 4 — Verbunden
- **Siehst:** Ein **● Verbunden**-Zustand (primär hervorgehoben) und **„Loslegen"** zum Betreten des Workspace. Nach
  „Loslegen" steht während des Dogfood oben eine **bernsteinfarbene Zeile mit ▲:** „Remote verbunden mit &lt;Hub&gt; —
  Workspace-Daten laufen noch nicht über den Tunnel." (Sekundär daneben: „Fern-Sitzung beenden".)
- **Tust:** „Loslegen".
- **Erwartest:** Das **● Verbunden** erscheint **nur**, wenn die Verbindung wirklich steht. Die **amber ▲-Zeile im
  Workspace ist im Dogfood erwartet und ehrlich**: die Sitzung ist remote aufgebaut, aber die Workspace-Daten laufen im
  aktuellen Stand noch nicht über den Tunnel — kein Fehler, sondern die wahrheitsgemäße Zwischenstufe (sie verschwindet
  automatisch, sobald die Daten tatsächlich über den Tunnel gehen).

> **Ersatzgerät (Nebenpfad, nicht der M1-Happy-Path):** Ein **zweites** Gerät zeigt in Schritt 3 **keine** neuen Codes,
> sondern verlangt die **Eingabe** eines vorhandenen Wiederherstellungs-Codes („Gerät wiederherstellen"). Das ist
> beabsichtigt — Codes werden nur **einmal** vergeben und **einmal** verbraucht.

### Interne Verankerung (für den PO, nicht für den Auftraggeber-Text)
| Schritt | User-Copy-Keys | Guard/Anchor |
|---|---|---|
| 1 Connect/OOB | `remote_connect_trust_first_*` (CYP-480, gepinnt) · Pflicht-blockierend | OOB-Pflicht (Docs 17/18); ● nur echt-CONNECTED |
| 2 Enroll | `remote_pop_enroll_{title,pin,session_only}` (CYP-460) · session_only = **amber ▲** | **GE1** (Enroll iff `isEnrolled()==false`) · **GE3** (session_only nur Raw) · **GE6** (amber-Ton) |
| 3 Codes+Ack | `remote_recovery_codes_{title,body,single_use,copy,ack}` + **`remote_recovery_codes_no_central`** (net-new) | **GE2** (Ack-Gate vor CONNECTED) · **GE5** (Codes einmal) · **GE7** (no-central vor Ack) |
| 4 Connected | `hubconnect_ready_enter` „Loslegen" + `workspace_remote_context_partial` (CYP-527, amber ▲) | ● nur echt-CONNECTED · CYP-527 present-iff (Remote-CONNECTED) |
| Ersatzgerät | `remote_recovery_start_*` / `remote_recovery_code_label` (CYP-480) | 2. Tür (`enrollWithBackupCode`) |

---

## B. Inc-3-Mount-UX-QA-Checkliste (mein §-QA am gebauten Enroll-Flow)

Abzuarbeiten, sobald Dev den Enroll-Flow gemountet hat (`feature/CYP-525-operator-device-enroll`, Inc 3). **Behavioral**
(present-iff / Gate / Ton), gegen den echten Build gelesen — nicht nur Render. Jede Zeile = eine assertbare Prüfung.

| # | Guard | Was ich verifiziere (am gebauten Stand) | Assertion / Tag |
|---|---|---|---|
| **GE1** | Enroll iff nicht-enrolled | Bei AUTHENTICATING rendert der **Enroll-Step** **nur** wenn `OperatorDeviceKeyStore.isEnrolled()==false`; ein enrolltes Gerät geht direkt zu `Pin`/`Biometric`-PoP, **nie** zum Enroll-Step. Beide Richtungen (non-vacuous). | `remote.authStep.enroll` present ⇔ `isEnrolled()==false` |
| **GE2** | Ack-Gate vor CONNECTED | First-Enroll erreicht `RemoteConnState.CONNECTED` **erst nach** erfülltem Ack — **kein Skip, kein Tap-through**. Der Ack muss CONNECTED via `enabled`-Gate koppeln (Dev-Verhaltens-AC): ohne Quittung kein Weiter. | CONNECTED unerreichbar solange `remote.recovery.codesAck` nicht ausgelöst |
| **GE3** | session-only nur Raw | `remote_pop_enroll_session_only` rendert **iff** Raw-Software-Pfad (`sessionOnly==true`); auf Hardware-Passkey (Fido2) **absent** (keine DEVICE_SECURE-Überzeichnung, kein -Understatement). | `remote.authStep.enroll`-Disclosure present ⇔ `sessionOnly==true` |
| **GE4** | not-enrolled ≠ AuthRejected (2-Wege) | not-enrolled routet **in-flow** zum Enroll-Step (nie `false`→`AuthRejected`); als Connect-Failure ist es die **distinkte** `remote.connect.error.deviceNotEnrolled` (aktionabel), **nie** in `authRejected` kollabiert. `cpSessionExpired` = **absent by design** (AuthGate-Re-Login) — **kein** Befund. | `deviceNotEnrolled`-Tag ≠ `authRejected`-Tag; DeviceNotEnrolled aktionabel |
| **GE5** | Codes einmal, nie erneut | Codes beim First-Enroll **einmal** sichtbar (`codesList`), danach **keine** „erneut ansehen"-Affordance/Tag. | keine Re-View-Affordance; `RecoveryCodesReveal`-Einmal-Semantik |
| **GE6** | session-only = WARN-amber | Der session-only-Node rendert `severityColor(Severity.WARN)` (amber) + **`▲` separater Node** (WCAG 1.4.1) — **nicht** `onSurfaceVariant`-grau, **nie** error-rot, **nie** tertiary/grün. Farbe **nie** alleiniger Träger (Glyph separat). | Ton == WARN-amber; `▲` eigener Node; kein error/tertiary |
| **GE7** | no-central vor Ack | `remote.recovery.codesNoCentral` rendert **auf dem Reveal, oberhalb/vor** `remote.recovery.codesAck` — nicht nur auf der Verlust-Fläche. Der informierte Ack setzt die Konsequenz-Zeile **davor** voraus. | `codesNoCentral` present **und** in Reihenfolge vor `codesAck` |

**Querschnitt (zusätzlich zu GE1–GE7):**
- **Copy-Vollständigkeit + DE/EN-Parität:** alle Reveal/Enroll-Keys beidseitig, Wortlaut 1:1 aus `strings.xml`
  (net-new `remote_recovery_codes_no_central` + `remote_connect_device_not_enrolled` müssen exakt so gegossen sein).
- **Tag-Contract:** `remote.recovery.codesNoCentral` + `remote.connect.error.deviceNotEnrolled` müssen exakt den
  eingefrorenen CYP-7-Werten entsprechen (Divergenz = §-QA-Fail).
- **Ton-Konsistenz:** kein Enroll/Recovery-Tag trägt Erfolgs-Grün; error-rot **nur** für echte terminale Fehler
  (`authRejected`), advisory/Downgrade = WARN-amber, neutral-Zwischenzustände = neutral.
- **Fail-closed:** `deviceNotEnrolled` aktionabel (nicht terminal-„abgelehnt"); Ack-Gate hart; Ersatzgerät ohne
  gültigen Code kommt **nicht** durch.

**Nicht als Befund werten (seam-/build-gated):** solange Dev die Mounts/Enforcement nicht landet, ist GE2-Enforcement
(Tap-through→`enabled`-Gate) ein **Dev-Verhaltens-AC**, kein Copy-Defekt; `cpSessionExpired`-Abwesenheit = by design;
Hardware-Passkey-Pfad ist jvm-Stub (Dogfood = Software-PIN).
