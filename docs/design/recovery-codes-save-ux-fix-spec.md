# Recovery-Codes-Save-Schritt — UX-Fix (Live-Enroll-Hang) — Spec + Keys + Tags

> Owner: UIUX-Designer · **Live-Bug-Design-Pass** (zeitkritisch, Design-first) · Stand 2026-07-15 · **Design/Copy, kein
> Code.** Gegroundet READ-ONLY gg. develop `a1593d19`. **Ticket-Key: vom PO zu vergeben** (Branch `feature/recovery-codes-
> save-ux-fix`, umbenennbar). Impl-Koordination: PO → Dev (Client) + Backend (Hub-Window-Semantik).

## 0. Der Hang (Root-Cause, gegroundet)
Beim Erst-Enroll zeigt `RecoveryCodesReveal` (`RecoveryCodesReveal.kt`) die einmaligen Wiederherstellungs-Codes; der Flow HÄLT
(`EnrollConfirmState.Revealing`, CYP-525 GE2) **client-seitig unbegrenzt** (`confirmSavedCodes` suspendiert auf
`deferred.await()`, **kein** Client-Timeout). Der Mensch klickt „Ich habe gespeichert" → `confirmSaved()` → `SavedAck` →
Hub finalisiert → CONNECTED.
**ABER:** der **Hub verwirft die provisorische Enrollment nach ~30 s** (server-belegt). Kommt der `SavedAck` danach, gibt
es **keinen** Finalize-Grant → `ClientOperatorAuth` wartet auf einen Grant, der nie kommt → der Client sitzt **ewig** auf
`remote_connect_authenticating` = **„Confirming operator…"**. Der Hang.
**Verstärker (Copy-Bug):** der „Codes kopieren"-Button (`RecoveryCodesReveal.kt`, `clipboard.setText(...)`) gibt **KEIN
sichtbares Feedback** → der Mensch weiß nicht, ob's klappte → schreibt **handschriftlich ab** → **>30 s** → Fenster
abgelaufen. (Der Diceware-Reveal zeigt dagegen eine „kopiert"-Notiz — hier fehlt sie.)

**Leitsatz:** das Speichern eines **Sicherheits-Backups** ist **menschen-getaktet**, kein 30-s-Rennen. Und kein Warte-
Zustand ohne Ausweg (dieselbe Anti-Dead-Hang-Doktrin wie der OIDC-„Continuing"-Fix, CYP-576).

---

## 1. Save-Flow-Semantik (→ Backend Hub-Window + Client) — der Kern-Fix
**Frage der PO: darf der „hab gespeichert"-Schritt ein hartes Zeitlimit haben? → NEIN, kein 30-s-Kill.**

**Ratifizierte Semantik:**
- **Menschen-getaktet:** die provisorische Enrollment wird **durch die Tunnel-Liveness + die Nutzer-Aktion (`SavedAck`
  / `abort`) begrenzt — NICHT durch eine 30-s-Uhr.** Der Client `abort()`t bereits bei leave/teardown (fail-closed,
  `HubConnectViewModel.kt:374`) → der Hub kann die Provisorik **halten, solange der Tunnel lebt**; `SavedAck` **oder** der
  explizite Abort lösen sie auf. **Kein stiller 30-s-Kill.**
- **Falls Backend eine Obergrenze braucht** (Ressourcen-Cleanup): **großzügig** (Minuten-/Tunnel-Lebensdauer-Skala,
  **nicht** 30 s) **UND** der Client hält sie aktiv (Keepalive/Heartbeat, solange der Reveal sichtbar ist) — ein aktiv
  lesender Mensch wird **nie** gekillt.
- **★ Anti-Dead-Hang (verbindlich, Honesty):** läuft das Fenster doch ab **oder** wird der `SavedAck` abgelehnt, darf der
  Client **NICHT** auf „Confirming operator…" hängen. Er zeigt einen **ehrlichen, aktionablen Ablauf-Zustand**:
  „Das Zeitfenster ist abgelaufen — verbinde neu, um **frische** Codes zu erhalten." + **Reconnect-CTA**. Das nutzt den
  **bestehenden** CYP-525-Re-TOFU-Pfad (close/reload vor Ack → Hub verwirft Provisorik → **frische** Codes,
  `HubConnectUiState.kt:57`) — der Nutzer weiß dann, **was zu tun ist**, statt zu erfrieren.
- **Kein Zeitdruck kommuniziert:** die Copy sagt aktiv „**nimm dir Zeit**" (s. §3), damit der Mensch nicht hetzt/panikt.

> Damit ist der Hang an der Wurzel weg: entweder das Fenster ist menschen-getaktet (kein Kill), oder — falls es doch endet —
> der Client führt ehrlich zum Reconnect statt zu hängen.

## 2. Copy/Save-Affordanz (→ Dev/UIUX2) — der Zeitfresser weg
Niemand soll handschriftlich abschreiben müssen. Drei Wege, robust gestaffelt:

**2a. „Codes kopieren" MIT sichtbarem Feedback (der fehlende Teil):**
- Der Button bleibt, aber nach Klick erscheint eine **„kopiert"-Bestätigung** (past-tense, **nur nach echtem Copy** —
  reuse das **Diceware-Muster** `DicewareReveal`): `remote_recovery_codes_copied` „In die Zwischenablage kopiert."
  (`onSurfaceVariant`, neutral, **kein** Glyph). So **weiß** der Mensch, dass es klappte — kein Abschreiben aus Unsicherheit.
- **⚠ Dev-Flag:** falls `clipboard.setText` auf dem Compose-Canvas real **nicht** die OS-Zwischenablage erreicht (nicht nur
  fehlendes Feedback), ist das ein **echter Plattform-Bug** → Dev verifiziert; **2b macht's unabhängig davon robust.**

**2b. ★ „In Datei speichern" (der clipboard-unabhängige, robuste Weg):**
- Neuer Button `remote_recovery_codes_save_file` „In Datei speichern" → schreibt die Codes über den **Plattform-File-
  Save-Dialog** (Desktop = Datei-Dialog; **Dev/Plattform-Seam**, `expect/actual`). Nach Erfolg:
  `remote_recovery_codes_saved_file` „Als Datei gespeichert." (neutral; **kein** Pfad-Leak-Zwang, optional `%1$s`=Dateiname).
- Das ist der **eigentliche Anti-Handschrift-Fix** — funktioniert auch, wenn die Zwischenablage klemmt.

**2c. Manuelle Selektion (Fallback bleibt):** der `SelectionContainer` bleibt für manuelles Markieren/Kopieren (v. a. Web).

> **Honesty:** „kopiert"/„gespeichert" nur nach **echtem** Copy/Save (nie vorzeitig); kein „sicher"-Versprechen über die
> Zwischenablage hinaus (der Nutzer trägt die sichere Verwahrung — wie bei der Diceware-Egress-Disclosure).

## 3. Klarer Save-CTA (der SavedAck prompt+zuverlässig)
- Der bestehende Ack-Button `remote_recovery_codes_ack` „Ich habe die Codes sicher gespeichert" bleibt der **einzige
  Vorwärts-Weg** (Leave-Gate, HD) — als **primäre** Aktion (gefüllter `primary`-Button, klar unter den Copy/Save-Affordanzen).
- **★ Zeitdruck raus:** direkt darüber `remote_recovery_codes_no_rush` „Nimm dir Zeit — es gibt kein Zeitlimit. Speichere
  die Codes, dann bestätige." (`onSurfaceVariant`, neutral). Kommuniziert die neue menschen-getaktete Semantik (§1) und
  beruhigt — der Mensch hetzt nicht mehr ins abgelaufene Fenster.
- **Ack NICHT auf Copy gaten:** manche fotografieren den Screen / schreiben bewusst ab — der Ack bleibt **immer aktiv**
  (Nutzer-Wahl); die Copy/Save-Affordanzen sind nur **prominent**, nicht erzwungen. Die „no-central"-Stakes-Zeile
  (`remote_recovery_codes_no_central`, bereits `onSurface`-betont) bleibt **vor** dem Ack.
- Nach Ack: `SavedAck` → (mit der §1-Semantik) prompt finalisiert → CONNECTED. Kein Hang mehr.

---

## 4. Neue Keys (DE = Default + EN)
| Key | DE | EN | Rolle / Honesty |
|---|---|---|---|
| `remote_recovery_codes_copied` | In die Zwischenablage kopiert. | Copied to clipboard. | §2a Copy-Feedback, past-tense, nur-nach-Copy. neutral. |
| `remote_recovery_codes_save_file` | In Datei speichern | Save to file | §2b clipboard-unabhängiger Save (Aktion). |
| `remote_recovery_codes_saved_file` | Als Datei gespeichert. | Saved to file. | §2b Save-Bestätigung, nur-nach-Save. neutral. |
| `remote_recovery_codes_no_rush` | Nimm dir Zeit — es gibt kein Zeitlimit. Speichere die Codes, dann bestätige. | Take your time — there's no time limit. Save the codes, then confirm. | §3 nimmt den Zeitdruck; kommuniziert die menschen-getaktete Semantik. |
| `remote_recovery_codes_window_expired` | Das Zeitfenster ist abgelaufen — verbinde neu, um frische Codes zu erhalten. | The window expired — reconnect to get fresh codes. | §1 Anti-Dead-Hang: ehrlicher Ablauf statt „Confirming operator…". |
| `remote_recovery_reconnect` | Neu verbinden | Reconnect | §1 die Reconnect-CTA des Ablauf-Zustands (→ Re-TOFU, frische Codes). |

### a11y
| Key | DE | EN |
|---|---|---|
| `a11y_remote_recovery_codes_copied` | Wiederherstellungs-Codes in die Zwischenablage kopiert. | Recovery codes copied to clipboard. |

## 5. Neue Tags (Area `remote`, scopeId `recovery` — reuse `RemoteRecoveryTags`)
| Tag | Element | liveRegion |
|---|---|---|
| `remote.recovery.codesCopied` | §2a die „kopiert"-Bestätigung (⇔ echter Copy) | **Polite** |
| `remote.recovery.codesSaveFile` | §2b „In Datei speichern"-Button | — |
| `remote.recovery.codesSavedFile` | §2b die Save-Bestätigung (⇔ echter Save) | **Polite** |
| `remote.recovery.codesNoRush` | §3 die „nimm dir Zeit"-Zeile | — |
| `remote.recovery.codesWindowExpired` | §1 der Ablauf-Zustand | **Assertive** (blockierend, braucht Aktion) |
| `remote.recovery.codesReconnect` | §1 die Reconnect-CTA | — |

## 6. Reuse (kein neuer Flow / keine divergente Copy)
- **Copy-Feedback-Muster** = `DicewareReveal` (past-tense, nur-nach-Copy) — dieselbe Sprache, hier auf die Recovery-Codes.
- **Reconnect/Re-TOFU** = der **bestehende** CYP-525-Pfad (close/reload → frische Codes, `HubConnectUiState.kt:57`) — die Ablauf-CTA triggert genau das, erfindet nichts.
- **Tags** unter `RemoteRecoveryTags` (`remote.recovery.*`), **Object bleibt** (kein neues).
- **Bestehende Zeilen bleiben:** `_title`/`_body`/`_single_use`/`_no_central`/`_ack` unverändert (kein Churn) — nur additiv.

## 7. Nahtstellen (Seams → Impl)
- **Backend (Hub-Window-Semantik, der Kern):** die ~30-s-Provisorik-Frist → **menschen-getaktet** (Tunnel-Liveness + Ack/
  Abort), oder großzügig + Client-Keepalive. **Bei Ablauf: ein signalisiertes Reject** (nicht stiller Drop), das der Client
  als §1-Ablauf-Zustand rendern kann.
- **Client (Dev):** (a) den `remote_connect_authenticating`-Hang beim SavedAck→kein-Grant in den §1-Ablauf-Zustand
  überführen (Timeout→aktionabel, nie ewig); (b) Copy-Feedback (§2a); (c) „In Datei speichern" (§2b, `expect/actual`
  File-Save); (d) Keepalive während des Reveals.
- **UIUX2 (Interaktion/a11y):** Fokus/Reihenfolge (Copy → Save → Ack), die Live-Region-Verdrahtung (Polite Copy/Save,
  Assertive Expiry), File-Dialog-Interaktion. (Flächen-Split wie CYP-542/CYP-576, über PO.)

## 8. Acceptance-Teeth (spätere §-QA gg. Bau)
1. Der Save-Schritt hat **kein** 30-s-Kill; ein aktiv lesender Mensch wird nicht rausgeworfen.
2. „Codes kopieren" gibt **sichtbares** Feedback; „In Datei speichern" funktioniert clipboard-unabhängig.
3. Läuft ein Fenster doch ab → **sichtbarer, aktionabler** Ablauf-Zustand + Reconnect → **frische** Codes; **kein** ewiges „Confirming operator…".
4. Copy/Save-Bestätigungen **nur nach echtem** Copy/Save (kein vorzeitiger Erfolg).
5. „Nimm dir Zeit"-Zeile präsent; Ack ist die klare primäre Vorwärts-Aktion, nicht auf Copy gegatet.
6. „no-central"-Stakes bleiben **vor** dem Ack; Töne neutral/`primary`, Expiry = error-Ton (blockierend), **kein** Erfolgs-Grün.
7. DE=Default + EN-Parität.

## 10. Ticket-Mapping (PO-Koordination, zwei Geschwindigkeiten)
> Der PO teilt diese Spec auf zwei Speeds. Damit Dev/Backend die **richtige Teilmenge** je Ticket ziehen:

**Immediate (live-unblock):**
- **CYP-596 (Backend, baut):** Fenster **großzügig + env-config** — der schnelle Server-Fix (= §1 „falls Backend eine Obergrenze braucht: großzügig", die Brücke, **nicht** die Ziel-Semantik).
- **CYP-595 (Dev, baut) + mein Anti-Dead-Hang-Expiry-Zustand gefoldet:** Client-Timeout → **statt nacktem Rejected** der **§1-Ablauf-Zustand**: „Fenster abgelaufen — neu verbinden für frische Codes" + Reconnect-CTA (reuse CYP-525-Re-TOFU).
  - **Keys für CYP-595:** `remote_recovery_codes_window_expired` · `remote_recovery_reconnect`.
  - **Tags für CYP-595:** `remote.recovery.codesWindowExpired` (**Assertive**) · `remote.recovery.codesReconnect`.

**Robust Follow-on (CYP-596-Teil-2 + Copy/Save, mit voller Spec):**
- **① Tunnel-Liveness-Semantik (Backend, der Kern > extend-clock):** Provisorik durch Tunnel-Liveness + Ack/Abort, nicht Uhr. (Keine neuen Keys — Verhaltens-Semantik.)
- **② Copy-Feedback + „In Datei speichern" (Dev/UIUX2, der Anti-Handschrift-Wurzel-Fix):**
  - **Keys:** `remote_recovery_codes_copied` · `remote_recovery_codes_save_file` · `remote_recovery_codes_saved_file` · `a11y_remote_recovery_codes_copied`.
  - **Tags:** `remote.recovery.codesCopied` (Polite) · `remote.recovery.codesSaveFile` · `remote.recovery.codesSavedFile` (Polite).
- **③ „Nimm dir Zeit"-CTA (Dev):**
  - **Key:** `remote_recovery_codes_no_rush`. **Tag:** `remote.recovery.codesNoRush`.

> Live-Brücke (kein Bau nötig): Screenshot-Workaround + großzügiges Fenster; die Wurzel-Fixe (①-Semantik + ②-Copy/Save) sind die dauerhafte Lösung.

## 9. Self-Validation
- **Gegroundet** gg. `RecoveryCodesReveal.kt` / `EnrollConfirmCoordinator.kt` / `ClientOperatorAuth.kt` / `HubConnectUiState.kt` @ `a1593d19` (file:symbol) — Hang-Mechanik code-belegt, nicht vermutet.
- **0 Kollision @ `a1593d19`:** die 7 neuen Keys grep-verifiziert **nicht** vorhanden; die 6 Tags nicht in `RemoteRecoveryTags`.
- **Reuse-first:** Copy-Feedback = Diceware-Muster · Reconnect = CYP-525-Re-TOFU · Tags = `remote.recovery.*` · bestehende Copy unverändert.
- **Honesty:** Sicherheits-Backup ist menschen-getaktet (kein Kill) · kein Dead-Hang (Anti-Dead-Hang wie CYP-576) · Feedback nur nach echter Aktion · kein Zeitdruck-Overstatement. Kein Bau, docs-only auf `feature/recovery-codes-save-ux-fix`.
