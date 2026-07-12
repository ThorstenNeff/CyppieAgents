# Remote-Operator Trust-Confirm · Recovery · Revocation — Design-Spec (CYP-480, Epic CYP-427)

> Status: **Spec-Closure — Q-Rulings gefolded 2026-07-12** · docs-only, **kein Bau** · Owner: UX/UI · Stand 2026-07-12
> **Ergänzung** zu `desktop-remote-operator-ux-spec.md` (CYP-460) und `remote-operator-ux-spec.md` (CYP-429).
> Deckt die drei neu-ratifizierten Flächen ab (PO 2026-07-12, RR5-Wand gefallen): **①** Trust-Confirm/OOB-Fingerprint
> (+`TrustChanged`, Entscheidung 3 = Option X) · **②** Q6-Recovery (Backup-Codes + Recovery-Eingabe, Entscheidung 2) ·
> **③** Revocation-UI (Fern-Sitzung beenden + ≤TTL, Entscheidung 4).
> Gegen echten Code gegroundet (develop `efc8eaf7`, read-only). Companion-Files: `-keys.md` · `-tags.md` · `-tokens.json`.
> Seam→Ticket (PO): **S-A/S-B → CYP-478** · **S-C → CYP-479 (Client) + CYP-459/469 (Server)** · **S-D → CYP-459**.

---

## 0. Grounding-Kern-Befund (entscheidet die Ehrlichkeits-Schicht)

Der reale Remote-Pfad läuft **weiterhin gegen `StubRemoteConnectFeed` („until RR5", `RemoteConnectFeed.kt:14`)**; die
echten Orchestratoren (`RemoteHubSession`, `HubTrust`, `OperatorAuthenticator`) sind **Seams, noch nicht verdrahtet**. Für
alle drei Flächen gilt: **State-Modell + Copy existieren und sind honest-by-design, das *Backing-Verhalten* (Pin-Store,
Fingerprint-Rendering, Backup-Codes, Session-TTL/-Registry) ist NICHT gebaut.** Diese Spec zeichnet die *Ziel-UX* +
Ehrlichkeits-Disclosures und **flaggt die Seams** — sie zeichnet **kein** funktionierendes Feature vor, das es nicht gibt.

**Drei load-bearing Code-Befunde:**
- **T1** — `FirstUse.fingerprint` / `TrustChanged.expectedFingerprint` sind **definiert, aber nie in lesbare Form berechnet
  und nie gerendert** (`RemoteHubSession.kt:111` liest nur `.hubStatic`; `.fingerprint` hat 0 UI-Konsumenten). **Kein
  Pin-Store, keine Trust-Persistenz** (nur ein Test-Stub). ⟹ **CYP-475-Provisorisch-Disclosure bleibt (①a ✅)**, der
  Confirm/Reject-Screen rendert **nur einen echt abgeleiteten** Fingerprint (nie Platzhalter — fail-closed).
- **T2** — **Keine Reject/Abort-Affordance** existiert im Connect-Flow (auto-getrieben; `TrustChanged`/`AuthRejected`
  rendern terminal **ohne Button** — `HubConnectSelection.kt:242-255`). ⟹ Confirm/Reject für `FirstUse` = **net-new** (S-B).
- **T3** — **Kein Backup-Code-Primitiv**; `OperatorDeviceRecovery` ist **absichtlich leer** (Q6-Seam,
  `OperatorDeviceEnroll.kt:54-56`). Server-Enroll ist **first-device-only** — ein 2. Gerät wird **abgelehnt**
  (`Rejected("already_enrolled_recovery_is_q6_seam")`). **Keine Operator-Session-TTL, keine Session-Registry, kein
  Server-Revoke** — TTLs existieren nur auf *Participant-Tokens*/*WS-Tickets*/*Nonce-Ledger*, **nicht** auf der Operator-Session.

### 0.1 ⚠ Namens-Drift-Befund (Design-System-Abstimmung, PO-geroutet)
Die **CYP-429 §8 `remote_trust_*`-Familie** (Fingerprint-Affordances, First-Connect, `changed_title/body`, `pinned`) wurde
**nie gebaut**. CYP-471/475 haben die Trust-**Zustände** stattdessen unter **`remote_connect_trust_*`** ausgeliefert
(`remote_connect_trust_check` / `_changed` [**ein** terser String „Hub-Schlüssel geändert — Verbindung blockiert. Neu-Pinnen
nötig."] / `_provisional`) + Tags `remote.connect.trustCheck` / `trustProvisional` / `error.trustChanged` (`RemoteConnectTags`).
**Folge:** der FirstUse-Trust-Confirm-Screen (Fläche ①) rendert als **neuer Branch von `RemoteConnectingView`** → er **muss**
in die ausgelieferte **`remote.connect.trust*`-Familie** konsolidieren (die gebauten Keys lassen sich nicht billig umbenennen;
ein separates `remote.trust.*`-Areal würde **einen Screen über zwei Familien splitten**). **Diese Spec revidiert daher das im
CYP-480-Ticket genannte `remote.trust.*` → `remote.connect.trust*`** und konsolidiert die CYP-429-§8-Affordance-Copy dort
hinein (superseded CYP-429 §8). **PO/DS-Bestätigung ausstehend** (§6, Q-Namen).

---

## 1. Kern-Ehrlichkeit (tragende Entscheidungen)

- **HA — Fingerprint nie erfunden (T1/fail-closed).** Der Confirm/Reject-Screen zeigt **nur** die aus dem echten
  Hub-`dhPubKey` abgeleitete Wort-/Hex-/QR-Sequenz. Solange die Bytes verworfen werden (T1), rendert der Screen **keinen
  Platzhalter-Fingerprint** — die **Provisorisch-Disclosure** (`remote_connect_trust_provisional`, CYP-475) sagt ehrlich
  „vorläufig" (①a ✅), und der volle Screen ist auf die Fingerprint-Ableitungs-Naht (S-A/CYP-478) gegated. `null ≠ Fake`.
- **HB — Reject ist eine erstklassige, sichere Wahl (nicht versteckt).** Bei Fingerprint-Nichtübereinstimmung ist Abbruch
  der **richtige** Zug (mögliches MITM). Die UX rahmt „Stimmt nicht — abbrechen" als **legitime, erwartete** Option (neutral,
  **kein** destruktiver Schreck-Button), und der Abbruch ist **fail-closed**: **nicht pinnen**, Verbindung abreißen, zur
  Hub-Liste mit ehrlichem „nicht verbunden — Identität nicht bestätigt".
- **HC — `TrustChanged` = harter, terminaler Block; KEIN Re-Pin im MVP (①b ✅).** Gepinnter ≠ vorgelegter Key = mögliches
  MITM. WARN-Amber, kein Retry, **nie still** (bereits gebaut, `remote_connect_trust_changed`). **Im MVP: reiner harter
  Block** — **kein** Re-Pin-Affordance. `remote_connect_trust_changed_repin` ist ein **rein informativer** Hinweis („Neu
  pinnen nur nach OOB-Abgleich"), **kein** Aktions-Button. Re-Pin kommt erst mit dem Pin-Store und dann **nur OOB** (Entsch. 3).
- **HD — Backup-Codes: einmal sichtbar, nie erneut (T3).** Bei Enroll einmalig im Klartext gezeigt („offline sicher
  aufbewahren", **jeder Code einmal gültig**), danach **nie wieder anzeigbar** — kein „Codes erneut ansehen". Verlassen
  nur nach explizitem „Ich habe sie gespeichert" (ehrliche Reibung gegen Verlust). **Nicht maskiert** (sie sollen gelesen+
  gespeichert werden) — **nicht** das `AuthPasswordField`-Muster (es gibt **kein** `ApiKeySection`/`Secrets.mask`), sondern
  lesbarer Text + Kopieren.
- **HE — Kein „Zentral-Login-allein"-Recovery (Q6/RR7).** Ein zentraler Login stellt den Operator-Zugriff **nicht** wieder
  her — das ist **bewusst nicht möglich** (Zentral-Login-allein würde CP-/Konto-Kompromiss zur Operator-Seizure machen).
  Die UX **sagt das ehrlich**, damit der Operator keinen „Passwort-vergessen"-Reset sucht. Recovery = Backup-Code (OOB) →
  Re-Enroll **=** Re-Pin. Multi-Device = **empfohlene Prävention** (Entsch. 2) — **frame-only + Seam-gated** (②a ✅): heute
  lehnt der Server ein 2. Gerät ab (T3) ⟹ der **befolgbare** Rat wird erst gerendert, wenn Multi-Device-Enroll server-seitig
  gebaut ist (CYP-459/469; `OperatorPinStore` modelliert bereits ein **Set** an Credentials — die Naht ist da).
- **HF — „Sitzung beenden" ≠ „überall widerrufen" (T3, guaranteed-vs-advisory).** „Fern-Sitzung beenden" reißt **diese**
  Verbindung auf **diesem** Gerät **sofort** ab (garantiert, `close()` gebaut). Es gibt **keine** Server-Session-Registry
  und **kein** globales Revoke — die UX **verspricht kein** globales Kill-Switch (③b ✅ MVP = lokaler Teardown +
  Scope-Note; Cross-Device-Multi-Session-Revoke = **Follow-up**). Die ≤TTL-Semantik ist **advisory** und an eine **neu zu
  bauende Operator-Session-TTL** gegated (③a ✅, CYP-459) — bis dahin **kein erfundenes Ablaufdatum** (`null ≠ 0`, absent).

---

## 2. Fläche ① — Trust-Confirm / OOB-Fingerprint (Entscheidung 3 = Option X)

Rendert als **neuer Branch von `RemoteConnectingView`** (`FirstUse`-Sub-Zustand von `TRUST_CHECK`). Familie
**`remote.connect.trust*`** (§0.1). Reuse `HubCard`-Scaffold, maritim+M3.

### 2.1 First-Connect Trust-Confirm-Screen (`TrustResolution.FirstUse`)
**Aufbau (oben → unten):**
1. **Titel** `remote_connect_trust_first_title` „Erstverbindung mit %1$s" *(Copy von CYP-429 §8 übernommen, re-homed)*.
2. **Body** `remote_connect_trust_first_body` — TOFU-ehrlich „über einen anderen Kanal bestätigen … pinnen".
3. **Fingerprint-Block** (`trustFingerprint`-Container; **nur echt abgeleitet**, HA):
   - **Primär**: Wort-/Emoji-Sequenz (`trustWordlist`, `remote_connect_trust_wordlist_label`) — fehlerarmer OOB-Abgleich.
   - **Sekundär**: Hex (`trustHex`, `remote_connect_trust_hex_label`, kopierbar) + QR (`trustQr`, `remote_connect_trust_qr_label`).
4. **OOB-Anweisung**: `remote_connect_trust_oob` **+ NET-NEW** `remote_connect_trust_oob_console` „Vergleiche mit der Anzeige
   in der Hub-Konsole." *(Option X = Abgleich gegen die Hub-Konsole).*
5. **Provisorisch-Disclosure** `remote_connect_trust_provisional` *(reuse SHIPPED CYP-475)* — **bleibt** (T1/①a), neutral
   `onSurfaceVariant`/`labelSmall`.
6. **Zwei explizite Aktionen (NET-NEW, HB):**
   - **Bestätigen → pinnen**: `remote_connect_trust_confirm` „Stimmt überein — pinnen" · Tag `trustConfirm`. → pinnt, weiter
     zu `AUTHENTICATING`.
   - **Ablehnen → abbrechen (fail-closed)**: `remote_connect_trust_reject` „Stimmt nicht — abbrechen" · Tag `trustReject`. →
     **nicht** pinnen, Verbindung abreißen (S-B `rejectTrust()`), zur Hub-Liste; Ergebnis `remote_connect_trust_aborted`
     „Nicht verbunden — Identität nicht bestätigt." (neutral, kein Alarm-Rot — Abbruch ist die *sichere* Wahl).

### 2.2 `TrustResolution.Pinned`
Still gegen den Pin verifiziert; kleiner neutraler „Identität gepinnt"-Indikator (`remote_connect_trust_pinned`,
`trustPinned`) im Kontext-Strip (§9 CYP-429). Kein Prompt. **NIE grün** (`onSurfaceVariant`).

### 2.3 `TrustResolution.Changed` (`RemoteFailure.TrustChanged`) — **reuse SHIPPED + informativer Zusatz (HC)**
Gebaut & korrekt (WARN-Amber `▲`, terminal, kein Retry — `HubConnectSelection.kt:242-255`, Tag `remote.connect.error.trustChanged`).
**Reuse SHIPPED** `remote_connect_trust_changed` (ein terser String).
- **NET-NEW `remote_connect_trust_changed_repin`** „Neu pinnen nur nach Out-of-Band-Abgleich des neuen Fingerprints." —
  **rein informativer** Hinweis (Decision-3-„Re-Pin-nur-OOB"), **kein** Aktions-Button, WARN-Amber-Kontext. **Kein Re-Pin im
  MVP (①b)** — reiner harter Block; Re-Pin-Flow erst mit Pin-Store (dann durch §2.1-Confirm/Reject mit dem neuen Fingerprint).

---

## 3. Fläche ② — Q6-Recovery (Entscheidung 2)

### 3.1 Backup-Codes-Anzeige beim Provisioning (im Enroll, CYP-460 §5.2)
Einmalige Klartext-Anzeige von N Wiederherstellungs-Codes **nach** erfolgreichem Device-Enroll. Reuse
`OperatorAuthDialog`-Enroll-Scaffold; **lesbarer Text-Block** (nicht maskiert, HD).
- **Titel** `remote_recovery_codes_title` „Wiederherstellungs-Codes".
- **Body (einmal-sichtbar)** `remote_recovery_codes_body` „Bewahre diese Codes offline und sicher auf. Du siehst sie nur
  dieses eine Mal — sie können nicht erneut angezeigt werden."
- **Einmal-Nutzung** `remote_recovery_codes_single_use` „Jeder Code funktioniert nur einmal."
- **Code-Liste** (`recoveryCodesList`) + **Kopieren** (`recoveryCodesCopy`, `remote_recovery_codes_copy`).
- **Verlassen-Gate** `remote_recovery_codes_ack` „Ich habe die Codes sicher gespeichert" (Checkbox/Confirm; ohne Ack **kein**
  Weiter). Tag `recoveryCodesAck`.
- **a11y** `a11y_remote_recovery_codes` (Container-Hinweis; Codes werden **nicht** einzeln vorgelesen).
- **Anzahl N / Format = Reviewer-Threat-Model (②b ✅)** — analog CYP-460 Q2/Q3: die UX rahmt den *Flow*, die *Zahlen*
  (Anzahl, Entropie, single-use-Konsum) liefert der Reviewer als AC. **Nicht** in der Spec hartkodiert.

### 3.2 Recovery-Eingabe (verlorenes Gerät → Re-Enroll = Re-Pin)
- **Titel** `remote_recovery_start_title` „Gerät wiederherstellen".
- **Body** `remote_recovery_start_body` „Gib einen deiner Wiederherstellungs-Codes ein, um dieses Gerät neu einzurichten."
- **Code-Feld** (`recoveryCodeField`, `remote_recovery_code_label`).
- **Kein-Zentral-Login (HE, kritisch)** `remote_recovery_no_central` „Ein zentraler Login allein stellt den Zugriff nicht
  wieder her — das schützt dich, falls dein Konto kompromittiert wird." Tag `recoveryNoCentral`.
- **Multi-Device-Empfehlung (Prävention, ②a — frame-only + Seam-gated)** `remote_recovery_multidevice_hint` „Empfehlung:
  Richte ein zweites Gerät ein, damit du bei Verlust nicht ausgesperrt bist." Tag `recoveryMultidevice`. **⚠ Erst als
  befolgbare Affordance rendern, wenn Multi-Device-Enroll gebaut ist** (T3, CYP-459/469); bis dahin nur als Modell/Absicht.
- **Fehler** `remote_recovery_invalid_code` „Code ungültig oder bereits benutzt." (`recoveryError`) — Einmal-Nutzung ehrlich.
- **Erschöpft (fail-closed)** `remote_recovery_exhausted` „Keine Wiederherstellungs-Codes mehr — wende dich für die
  Wiederherstellung an die Hub-Konsole." — **kein** Phantom-Weg, ehrlicher OOB-am-Hub-Verweis.

---

## 4. Fläche ③ — Revocation-UI (Entscheidung 4)

### 4.1 „Fern-Sitzung beenden"-Kontrolle
Platzierung im Fern-Betrieb-Kontext-Banner (§9 CYP-429). Reuse client-teardown `RemoteHubSession.close()` /
`HubConnectViewModel.backToHubList()` (gebaut) + Destructive-Confirm-`AlertDialog`-Scaffold (wie `LockoutDialog`/`RemoveDialog`).
- **Aktion** `remote_revoke_end_action` „Fern-Sitzung beenden" · Tag `revokeEnd`.
- **Confirm-Dialog** `remote_revoke_confirm_title` „Fern-Sitzung beenden?" · Tag `revokeConfirm`.
- **Body (garantiert/sofort, HF)** `remote_revoke_confirm_body` „Diese Verbindung wird sofort getrennt."
- **Scope-Note (advisory/ehrlich, HF — kein globales Revoke)** `remote_revoke_scope_note` „Das beendet nur diese
  Verbindung auf diesem Gerät — es widerruft keine anderen Sitzungen." · Tag `revokeScopeNote`, neutral `onSurfaceVariant`.
  *(③b: Cross-Device-Multi-Session-Revoke = Follow-up, nicht MVP.)*
- **≤TTL-Hinweis (advisory, ③a — neu zu bauende Operator-Session-TTL, CYP-459)** `remote_revoke_ttl_hint` „Deine
  Berechtigung läuft ohnehin innerhalb von %1$s ab." · Tag `revokeTtlHint`. **⚠ Nur rendern, wenn das Backend eine
  Operator-Session-TTL liefert** — heute existiert **keine** (T3); bis dahin **absent** (`null ≠ 0`).
- **Ergebnis** `remote_revoke_ended` „Fern-Sitzung beendet." (`revokeEnded`) → `LOST` → Hub-Liste; `a11y_remote_revoke_ended`.

### 4.2 Ehrlichkeits-Grenze (warum „beenden", nicht „widerrufen")
Der reale Effekt ist **lokaler Teardown** (diese Verbindung). Echtes Credential-Revoke (überall killen) bräuchte die
Server-Session-Registry, die **nicht existiert** (T3). Darum **Aktion = „beenden"** und der ≤TTL-Hinweis ist die **advisory**
Obergrenze — **kein** Kill-Switch-Overstatement.

---

## 5. Maritim + M3 · Farb-/a11y-Ehrlichkeit

- **WARN-Amber NUR** Trust-Änderung (§2.3, `severityColor(Severity.WARN)` — dark `#FFC857` / light `#9A6400`). Reuse SHIPPED.
- **errorContainer** nur harte Terminals (AuthRejected via `TonedHint(ERROR)`); Revoke-Confirm folgt dem **Haus-Destructive-
  Confirm**-Muster, Kontrolle selbst neutral.
- **Trust-Reject** = **neutral** (sichere Wahl, kein Schreck-Rot). **Backup-Codes** = neutral lesbar; „einmal sichtbar"-Warnung
  = INFO/GATED-Ton (kein Alarm). **Recovery-erschöpft** = neutral. **Scope-/TTL-Note** = neutral `onSurfaceVariant`.
- **NIE `tertiary`/#40D6A0-Grün** als Status. **Farbe nie alleiniger Träger** (WCAG 1.4.1) — Form + Label + a11y. Dark/Light
  `maritimeColorScheme`.

---

## 6. Entscheidungen (Q-Rulings PO 2026-07-12) + verbleibende Abstimmung

1. **①a — Provisorisch-Disclosure bleibt** bis Fingerprint-Ableitung + Pin-Store gebaut (S-A/CYP-478). ✅
2. **①b — Kein Re-Pin im MVP** (reiner harter Block; Re-Pin erst mit Pin-Store, nur OOB). ✅ `_changed_repin` = nur Hinweis.
3. **②a — Multi-Device frame-only + Seam-gated** (befolgbarer Rat erst mit Server-Enroll CYP-459/469). ✅
4. **②b — Backup-Code-Anzahl/Format = Reviewer-Threat-Model** (AC, analog CYP-460 Q2/Q3). ✅ UX rahmt Flow, Reviewer liefert Zahlen.
5. **③a — ≤TTL = neu zu bauende Operator-Session-TTL** (CYP-459), **nicht** Participant-Token-TTL; bis gebaut absent. ✅
6. **③b — MVP = lokaler Sofort-Teardown + Scope-Note;** Cross-Device-Multi-Session-Revoke (Registry) = Follow-up. ✅
7. **⏳ Q-Namen (§0.1, PO/DS):** diese Spec revidiert `remote.trust.*` → **`remote.connect.trust*`** (shipped-aligned, da die
   Trust-Zustände dort gebaut sind und der Screen ein Branch von `RemoteConnectingView` ist). **PO/DS-Bestätigung ausstehend**
   — bei Gegenentscheid = billiger Rename-Re-Sync (wie CYP-395). Recovery/Revoke = eigene Areas `remote.recovery.*`/`remote.revoke.*`.

---

## 7. Nahtstellen (über den PO an Dev/Backend) — Seam→Ticket

- **S-A — Fingerprint-Ableitung → CYP-478:** Krypto liefert `dhPubKey`-Bytes; Client leitet Wort-/Emoji-Sequenz + Hex + QR
  ab; `FirstUse.fingerprint` in die UI durchreichen (heute verworfen, `RemoteHubSession.kt:111`). **Gate für §2.1** + Pin-Store.
- **S-B — Reject/Abort-Pfad → CYP-478:** `HubConnectViewModel.rejectTrust()` (nicht pinnen, `close()`, zur Hub-Liste,
  `remote_connect_trust_aborted`) — heute **keine** Reject-Affordance (T2).
- **S-C — Backup-Codes + Recovery → CYP-479 (Client) + CYP-459/469 (Server):** Generierung (N Codes gehasht, single-use-
  Konsum), Einmal-Reveal-Kanal, Recovery-Verifikation (Code → Re-Enroll); **+ Multi-Device-Enroll** (Server hebt
  first-device-only auf, `OperatorDeviceEnroll.kt:32`; `OperatorPinStore` modelliert schon ein Set).
- **S-D — Operator-Session-TTL/-Registry → CYP-459:** TTL-Feld auf der Operator-Session (für den ≤TTL-Hinweis); Cross-Device-
  Registry/Revoke = Follow-up.

---

*Spec-Closure (Q-Rulings gefolded 2026-07-12). Companion-Files (`-keys.md`/`-tags.md`/`-tokens.json`) sind die UI-Vorlage.
Gegen echten Code gegroundet (develop `efc8eaf7`, read-only, nichts angefasst). Nichts gebaut. **Offen: §0.1/§6-Q7
Namens-Bestätigung (PO/DS).** Alle Seams über den PO.*
