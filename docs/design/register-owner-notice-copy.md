# Register-Owner-Notice — dedizierte „Jemand-hat-sich-mit-deiner-Mail-registriert"-Copy (Backlog #64)

> Owner: UIUX-Designer · Backlog-UX-Item #64 · Stand 2026-07-14 · Status: **Design-Pass, ratifikations-reif** (ungated
> Interim, PO `1526561425…`). **Design/Copy, kein Code.** Gegroundet READ-ONLY gg. develop `96d4de06`.
>
> **Kern:** die anti-enumeration Register-Kollisions-UX ist **schon gebaut** (CYP-176-Gate + CYP-278-Notice-Affordanz);
> die Mail an den **bestehenden Owner** („du hast bereits ein Konto") ist **heute die REUSED Kratos-Recovery-Flow**
> (`HttpKratosRegisterBackend.notifyExisting → triggerFlow("recovery")`, `:113-121`) — also die **Passwort-Reset-Mail**
> dient als Kollisions-Notice. **#64 ersetzt diese Reuse durch eine dedizierte Notice** mit ehrlicher Semantik.

## 0. Adjazenz-Check (kein Konflikt)
Sibling-Worktrees `CYP-516-register-owner-gate` / `CYP-511-resolve-owner-gate` betreffen das **Control-Plane-Rendezvous**
(register/resolve owner-gating, Device-Pairing) — **nicht** die Auth-Konto-Registrierung. Name-only-Diff berührt kein
`notify`/`recovery`/`kratos`/`mail`-File. **Kein Overlap mit #64** (anderes „register"). Read-only geprüft, nicht angefasst.

## 1. Grounding (as-built @ `96d4de06`)
- **Anti-Enumeration ist gewahrt (Client + Server):** Frisch-Registrierung und E-Mail-Kollision sind **byte-identisch**
  (`RegisterResult.Pending` → `AuthedUnverified(fromRegister=true)`, `AuthViewModel.kt:162-176`); der Server antwortet
  branch-invariant `200 {"status":"verification_pending"}` (`HttpAuthRepository.kt:144-164`). **Der Branch reist in der
  Mail, nie in der HTTP-Antwort.**
- **Owner-Notice = heute Recovery-Reuse:** `notifyExisting(email)` triggert den **Recovery**-Self-Service-Flow → Kratos
  sendet die Recovery/Reset-Mail als „du hast schon ein Konto"-Notice (`HttpKratosRegisterBackend.kt:113-121`, Test
  `HttpKratosRegisterBackendTest.kt:107-115`).
- **Mail ist server/Kratos-owned** (Courier-Template), **nie** client-getriggert für Kollision. Die Copy hier ist der
  **Template-AC** — Backend baut das dedizierte Template.
- **Der Registrierende sieht unverändert** den neutralen Verify-Pending-Screen (owner-facing Notice ≠ was der Tipper sieht).

## 2. Warum die Recovery-Reuse falsch ist (Rationale für die dedizierte Notice)
1. **Falsches mentales Modell:** „Setze dein Passwort zurück / stelle dein Konto wieder her" impliziert, der **Owner** habe
   etwas ausgelöst oder **müsse handeln** — hat er aber nicht; jemand anderes hat seine Adresse getippt. Die Reset-Mail
   erklärt das *reale* Ereignis nicht.
2. **Phishing-Ähnlichkeit:** eine unerwartete „Passwort zurücksetzen"-Mail ist genau das Muster, das Security-Trainings als
   verdächtig markieren — sie **erodiert Vertrauen** und trainiert schlechte Reflexe (oder wird zu Recht ignoriert).
3. **Aktions-Mismatch:** die *richtige* Primär-Aktion ist **Anmelden** (es ist dein Konto), Reset ist **sekundär** (nur
   falls Passwort vergessen). Die Recovery-Mail führt mit Reset — die falsche Priorität.
4. **„Nicht-du"-Fall fehlt:** die häufigste Ursache (jemand vertippt sich bei seiner eigenen Adresse) hat gar keine
   Aktion — die Reset-Mail bietet dafür keinen ehrlichen „du musst nichts tun"-Ausgang.

## 3. Die dedizierte Owner-Notice — Copy (DE = Default + EN)
> Mechanik: Kratos-Courier-Template (server-i18n). `%1$s`-Platzhalter nur falls das Template Personalisierung trägt; die
> Links sind Template-Aktions-URLs (Sign-in / Reset), keine app-i18n-Keys.

### Betreff / Subject
- **DE:** Es gab einen Registrierungsversuch mit deiner E-Mail-Adresse
- **EN:** Someone tried to register an account with your email

### Body — DE
```
Hallo,

jemand hat versucht, mit deiner E-Mail-Adresse ein neues Konto zu erstellen. Du hast
bereits ein Konto bei uns — es wurde KEIN zweites angelegt, und an deinem bestehenden
Konto hat sich nichts geändert.

• Warst du das? Melde dich einfach mit deinem bestehenden Konto an:  [Anmelden]
• Passwort vergessen? Setze es hier zurück:  [Passwort zurücksetzen]
• Warst du das nicht? Dann musst du nichts tun — dein Konto ist sicher. Vermutlich hat
  sich jemand bei seiner eigenen Adresse vertippt.

Wir fragen dich in dieser E-Mail nie nach deinem Passwort oder weiteren Angaben.
```

### Body — EN
```
Hi,

someone tried to create a new account with your email address. You already have an
account with us — NO second account was created, and nothing about your existing
account has changed.

• Was this you? Just sign in with your existing account:  [Sign in]
• Forgot your password? Reset it here:  [Reset password]
• Wasn't you? Then there's nothing to do — your account is safe. Someone probably
  mistyped their own address.

We'll never ask for your password or any other details in this email.
```

### Honesty-Merkmale (der Punkt der ganzen Übung)
- **Benennt das reale Ereignis** ehrlich („Registrierungsversuch mit deiner Adresse"), statt es als Reset zu verkleiden.
- **Beruhigt faktisch:** kein zweites Konto, nichts geändert, Konto sicher — **kein Alarm**, keine Kompromittierungs-Suggestion.
- **Richtige Aktions-Hierarchie:** Anmelden (primär) › Reset (sekundär) › nichts-tun (Nicht-du) — jede Zeile ehrlich.
- **Anti-Phishing-Zeile:** „Wir fragen dich nie nach deinem Passwort in dieser E-Mail" — trainiert die richtige Instinkt
  und grenzt die legitime Mail vom Phishing ab.

## 4. Zu wahrende Invarianten (dürfen NICHT brechen — CYP-179)
- **Mail-Symmetrie:** die existierende-Branch **sendet weiterhin eine Mail** (jetzt die dedizierte Notice) — sonst wird
  „Stille = existiert nicht" zum Seitenkanal. Die dedizierte Notice ersetzt die Recovery-Mail **1:1 in der Sende-Position**.
- **Timing-Parität (MUST-2):** die branch-divergente Arbeit läuft **off-response-path** (Latenz branch-invariant) — die
  Template-Umstellung ändert daran nichts (Backend).
- **Branch-blind / best-effort:** ein Fehler beim Notice-Versand darf **nie** branch-abhängig sichtbar werden.
- **Owner-facing only:** diese Notice sieht **nur der Owner** (in seinem Postfach) — der Registrierende sieht unverändert
  den neutralen Verify-Pending-Screen. Deshalb darf die Notice **spezifisch-ehrlich** sein (kein Enumeration-Oracle: wer
  sie liest, kontrolliert bereits das Postfach).

## 5. Verwandter Client-Copy-Honesty-Befund (separat, PO-Ruling)
`auth_register_notice_body` (der Screen des Registrierenden) sagt heute **„Wir haben eine E-Mail an %1$s gesendet. Öffne
den Link darin, um fortzufahren."** Für die **Kollisions**-Branch wird aber **keine** Verify-Link-Mail an die getippte
Adresse gesendet (nur die Owner-Notice). Das etablierte **neutrale** Muster (verify/forgot) ist **„Falls ein Konto zu %1$s
existiert, ist eine Bestätigungs-Mail unterwegs."** (`auth_verify_pending_body` / `auth_forgot_sent`).
**Empfehlung:** `auth_register_notice_body` an das neutrale „Falls ein Konto…"-Muster angleichen — ehrlicher (verspricht
keinen Link, den der Tipper ggf. nicht öffnen kann) **und** byte-identisch für beide Branches wahrbar. **Kein Muss für #64**
(betrifft den Client-Screen, nicht die Notice-Mail) — eigenes kleines Ticket, dein Ruling.

## 6. Nahtstellen / Handover
- **Backend (Mail-Mechanik):** dediziertes Kratos-Courier-Template mit der Copy aus §3; `notifyExisting` swappt
  `triggerFlow("recovery")` → dedizierten Notice-Trigger. Invarianten §4 wahren.
- **UIUX (ich):** Copy-AC §3 + Rationale §2 + der Client-Copy-Befund §5. Über den PO.
- **Nicht in #64:** der Client-Verify-Screen (existiert, CYP-278) — außer der optionalen §5-Angleichung.

## 7. Self-Validation
- **Rein Copy/Design, kein Code**, docs-only auf `backlog/register-owner-notice-copy` (nicht CYP-542, eigener Worktree).
- **Anti-Enumeration gewahrt:** Notice owner-facing-only, Mail-Symmetrie + Timing-Parität explizit als Invarianten notiert.
- **DE+EN paritätisch** (Subject + Body + Aktionen).
- **Kein Overlap** mit CYP-516/511 (read-only geprüft, anderes „register" = CP-Rendezvous).
- **Reuse:** nutzt die bestehenden Sign-in/Reset-Aktionen (`auth_link_to_login`/`auth_link_forgot`-Ziele); erfindet keinen
  neuen Recovery-Flow. Die dedizierte Notice **ersetzt** nur die semantisch falsche Recovery-Reuse.
