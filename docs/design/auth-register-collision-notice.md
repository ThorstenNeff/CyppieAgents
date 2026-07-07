# Design-Pass — Register-Kollisions-Notice (enumeration-sicher)

> **Status:** DESIGN-PASS (kein Bau) · Owner: UIUX-Designer · PO-getriggert 2026-07-06 · pending UIUX-Follow-up aus dem Auth-Zug (Epic **CYP-176**; Ticket-Key vom PO bei Ratifikation).
> **Grounding (Reuse, real verifiziert):** `docs/design/auth-spec.md` (§-Ask 1 Enumeration-Sicherheit, State-Machine Z.111 Register→„neutrale Bestätigung"), `values/strings.xml` (bestehende Enum-safe-Copy `auth_verify_pending_body`/`auth_forgot_sent`/`auth_verify_resend_done`; Affordance-Keys `auth_link_to_login`/`auth_link_forgot`), `auth/AuthTags.kt` (Area `auth`, scopeId `verify`), `KratosDto.kt`. Verifiziert gegen develop `0369fb0`.
> **Security-bewusst ist der KERN:** die Notice darf **kein Orakel** werden. Die Ableitung „garantiert vs. advisory" (Disclosure) ist §4.

---

## §0 — Zwei Sätze

Registriert sich jemand mit einer **bereits existierenden** E-Mail, sendet das System heute eine **Recovery-Mail**, aber der Bildschirm zeigt die generische „Bestätigungs-Mail unterwegs"-Copy → **subtil falsch** für den echten Bestandsnutzer (er bekam eine *Sign-in/Reset*-Mail, keine Bestätigung) und **hilft ihm nicht**. Dieser Pass definiert eine **dedizierte, enumeration-sichere** Notice: **identischer Bildschirm/Copy/Ton egal ob neu oder existierend** (kein Leak), die dem echten Nutzer trotzdem hilft (`Anmelden`/`Zurücksetzen`-Affordance + honest „falls du bereits ein Konto hast"-Zeile).

---

## §1 — Was existiert (Reuse, nicht neu erfunden)

Die Enumeration-Sicherheit ist **architektonisch schon da** (auth-spec §-Ask 1 + State-Machine): Register-Erfolg landet auf dem **Verify-Pending-Screen** (`auth.verify.pending`); der Kollisionsfall landet **auf demselben** Screen (Kratos-Enumeration-Mitigation: gleicher `verification_pending`-Ausgang, CYP-187-Wrapper `200 {"status":"verification_pending"}` branch-invariant). **Der Screen ist also schon nicht-leakend.** Bestehende Enum-safe-Voice (konsistent): `auth_verify_pending_body`, `auth_forgot_sent`, `auth_verify_resend_done`.

**Die Lücke ist NUR die Copy** (nicht die Architektur): `auth_verify_pending_body` = „…ist eine **Bestätigungs-Mail** unterwegs" — für den Kollisionsfall wurde aber eine **Recovery/Sign-in-Mail** gesendet. Die Copy ist damit für den Bestandsnutzer **irreführend + hilflos**.

---

## §2 — Die Entscheidung: dedizierte Register-Notice-Copy (NICHT die geteilte Verify-Copy umschreiben)

**Warum nicht `auth_verify_pending_body` umschreiben:** dieser Screen wird **auch** vom `AuthedUnverified`-Gate erreicht (ein eingeloggt-aber-unverifizierter Nutzer, Resend/Gate). Dort wäre eine „falls du bereits ein Konto hast → anmelden/zurücksetzen"-Zeile **falsch** (er ist schon angemeldet, nur unverifiziert) → das geteilte Key umzuschreiben würde die falsche Hilfe in den Gate-Kontext leaken. **Anti-Divergenz-Prinzip:** kein Duplikat-Screen, aber **kontext-passende Copy** auf dem geteilten Screen.

→ **Eine dedizierte Notice-Copy, gezeigt, wenn der Pending-Screen VOM REGISTER-Flow erreicht wird**; `auth_verify_pending_body` bleibt für den reinen Verify/Gate-Kontext akkurat.

---

## §3 — Copy (DE + EN) — enumeration-sicher, honest, dual-purpose

> **✅ RATIFIZIERTER SPLIT (Dev-Vorschlag, PO 2026-07-07):** Der Body ist **rein neutral** (2 Sätze, 0 konditionale Sprache) = maximales Nicht-Orakel; die Dual-Purpose-**Hilfe wandert vollständig in die Affordance-Zeile** (Anmelden/Zurücksetzen, allen gezeigt). Stärker als die ursprüngliche 3-Satz-Body-Klausel: keine „falls du bereits ein Konto hast"-Aussage mehr im Body → die Affordance-Links sind unbedingte Navigation (keine Behauptung über die Konto-Existenz). **Bedingung:** die Affordance-Zeile ist jetzt der **alleinige Träger** der Bestandsnutzer-Hilfe — sie MUSS präsent + allen sichtbar sein.

**Titel** (neutral — „bestätigen" ist verify-spezifisch, für die Kollision gibt es nichts zu bestätigen):

| Key (neu) | DE | EN |
|---|---|---|
| `auth_register_notice_title` | **Prüfe deine E-Mail** | **Check your email** |

**Body** (%1$s = eingegebene E-Mail) — **neutral, identisch neu vs. Kollision:**

| Key (neu) | Text |
|---|---|
| `auth_register_notice_body` (DE) | **Wir haben eine E-Mail an %1$s gesendet. Öffne den Link darin, um fortzufahren.** |
| `auth_register_notice_body` (EN) | **We've sent an email to %1$s. Open the link inside to continue.** |

**Affordance-Zeile — JETZT ALLEINIGER TRÄGER der Dual-Purpose-Hilfe** (immer sichtbar → keine Existenz verraten; hilft dem echten Bestandsnutzer sofort zu handeln — **MUSS präsent sein**). PO-ratifiziert 2026-07-07 mit **Lead-in** (macht die Hilfe explizit, allen gezeigt = enumeration-safe):

| Element | Key | DE | EN / Ziel |
|---|---|---|---|
| **Lead-in** (neu) | `auth_register_notice_have_account` | **Bereits registriert?** | **Already registered?** |
| Anmelden | `auth_link_to_login` (reuse) | „Zurück zur Anmeldung" | → Login-Sub-Screen |
| Passwort zurücksetzen | `auth_link_forgot` (reuse) | „Passwort vergessen?" | → Forgot-Sub-Screen |

> **Honesty-Kern (§4):** „Wir haben eine E-Mail gesendet" ist bei gültigem Register-Submit **in BEIDEN Fällen wahr** (neu → Verify-Mail; existierend → Recovery-Mail; CYP-193 = echtes SMTP-Relay jetzt live, Zustellung real). Daher ist die neutrale Aussage **ehrlicher** als die geteilte „falls ein Konto existiert"-Hedge (die anderswo korrekt ist, weil dort ein Send *nicht* garantiert ist — Forgot/Verify bei unbekannter Adresse). **Abhängigkeit:** die Wahrheit der Copy setzt voraus, dass der Backend bei Kollision *irgendeine* Mail sendet (Recovery/Sign-in) — heute so (PO bestätigt). Fällt das je weg → Copy MUSS auf die „falls ein Konto existiert"-Hedge zurück (§7-Dep).

---

## §4 — Disclosure / Ehrlichkeit (garantiert vs. advisory) — mein Kern

| Aussage | Status | Regel |
|---|---|---|
| „Wir haben eine E-Mail gesendet" | **garantiert** (bei valid submit sendet der Backend immer) | ehrlich als Fakt formulierbar |
| Anmelden/Zurücksetzen-Hilfe | **unbedingte Navigation** (Affordance-Links, allen gezeigt) | nach dem Split KEINE Copy-Aussage mehr — reine UI-Navigation, behauptet nichts über die Konto-Existenz (noch ehrlicher/sicherer als die frühere gerahmte Body-Klausel) |
| „Dein Konto wurde erstellt" | **NIE behaupten** | wäre bei Kollision falsch → Copy sagt „E-Mail gesendet", nicht „Konto erstellt" |
| „Diese E-Mail existiert (nicht)" | **NIE** — weder Text noch Fehler noch Ton noch Timing | das ist das Orakel-Verbot (§5) |

Die Notice ist ein **neutraler Info/Erfolgs-Ton**, **nie ein Fehler** — ein Fehlerzustand, der nur bei Kollision feuert, **wäre** selbst ein Orakel.

---

## §5 — Security-Invarianten (= UX-QA-Abnahme, 8) — die Notice ist KEIN Orakel

1. **Identischer Bildschirm-Ausgang** — Screen, Titel, Body, Affordances, Ton **identisch** für neu vs. existierend. **Ein** Code-Pfad, **kein** kollisions-spezifischer Branch, **nirgends** eine „E-Mail existiert bereits"-Meldung im Register.
2. **Neutraler Ton, nie Fehler** — Info/Erfolg; kein `error`-Tag/-Farbe/-Icon (Fehler-nur-bei-Kollision wäre Orakel). `auth.register.error`/`auth_register_error_generic` bleiben **generisch** (Netzwerk/Server), nie existenz-abhängig.
3. **Differenzierung NUR in der Mail** (Backend) — neu → Verify-Link; existierend → Sign-in/Reset-Link. Der Bildschirm sagt **nie**, welche.
4. **Kein Timing-Orakel** — Register-Submit dauert ununterscheidbar lang, egal ob die Adresse existiert (Backend-Querschnitt; die Notice rendert am selben Transitions-Punkt). *(Flag an Backend/Test, §7.)*
5. **liveRegion-Parität** — die Notice wird über die bestehende CYP-176-`liveRegion` **identisch** angesagt (Screen-Reader bekommt dieselbe neutrale Meldung; kein abweichender a11y-Text).
6. **Affordances existenz-UNabhängig** — `Anmelden`/`Zurücksetzen` werden **allen** gezeigt (nicht nur bei Kollision) → kein Orakel; sie helfen dem echten Nutzer, ohne etwas zu verraten.
7. **Feld-Validierung getrennt** — invalid-email / pw-mismatch / pw-rule bleiben **inline, pre-submit** (`auth_register_email_invalid` etc.), nie mit der Enum-safe-Notice vermengt.
8. **Kein Token/keine Adresse im Klartext-Leak** — %1$s = die vom Nutzer selbst eingegebene Adresse (kein Server-Wissen); kein Verify/Reset-Token je in Tag/Key/Log (auth-spec §2.2, unverändert).

---

## §6 — Reuse, Counts & Drift-Flags

- **Neue Copy-Keys (3, DE+EN):** `auth_register_notice_title`, `auth_register_notice_body` (neutral, gesplittet), `auth_register_notice_have_account` (Lead-in „Bereits registriert?", PO-ratifiziert 2026-07-07).
- **Reuse Copy (0 neu):** `auth_link_to_login`, `auth_link_forgot` (Affordances), `auth.verify.email` (Adress-Echo).
- **Neue testTags (2):** `auth.verify.signIn`, `auth.verify.reset` (scopeId `verify`, nur bei Register-Ankunft gerendert; camelCase, Test-Contract v0.5 §2). **Reuse:** `auth.verify.pending` (Body-Node), `auth.verify.email`.
- **⚠️ Shared-Key-/Tag-Drift (mein Mandat):** Copy-Keys (Area `auth`) **und** die 2 neuen Tags sind **mit QA/CYP-7 geteilt** (`AuthTags` = shared API). Landing dieser Keys/Tags erfordert **Re-Sync des Auth-Moduls + QA** — Timing mit dem konsumierenden Auth-Screen abstimmen (nicht isoliert landen). Kein Rename der bestehenden `auth.*`-Tags.
- **0 neuer Surface/Screen** — es ist der bestehende Pending-Screen-Chrome; nur kontext-abhängige Copy + 2 Affordance-Nodes. **0 App-Theme-Änderung** (maritim folgt automatisch über `colorScheme`-Rollen; nach CYP-268 R1/R3 rekoloriert).
- **Anti-Divergenz:** keine neue Notice-Komponente — Reuse der `AuthComponents`-Notice-Shell; Affordances = bestehende Link-Composables.

---

## §7 — Offene Punkte / §-Asks (nicht-blockierend)

1. **§-Ask (PO):** Ticket-Key für diesen Follow-up (Branch liegt unter Epic `CYP-176`) — vergibst du bei Ratifikation.
2. **Dep (Backend/Test):** **Timing-Parität** des Register-Submits (kein messbarer Latenz-Unterschied neu vs. existierend) — Security-Querschnitt, gehört ins Auth-Security-Test-Plan (CYP-176), nicht in die UI. Ich flagge, verifiziere aber nicht selbst.
3. **Dep (Backend-Kontrakt):** die Copy „wir haben eine E-Mail gesendet" setzt voraus, dass **Kollision → Recovery/Sign-in-Mail** (heute so, CYP-193-SMTP live). Ändert sich das Backend-Verhalten (keine Mail bei Kollision) → Copy auf die „falls ein Konto existiert"-Hedge zurückstellen. **Kontrakt-Notiz an Backend.**
4. **§-Ask (PO/Auftraggeber):** Titel-Wortlaut „Prüfe deine E-Mail" vs. Beibehalt „E-Mail bestätigen" — ich empfehle den neutralen Titel (honest für beide Fälle); dein Call.

---

## §8 — Hand-off

- **Kein Bau** — Design-Pass an den PO → Ratifikation → Ticket-Key. Danach: 2 Keys (DE+EN) + 2 Tags landen **mit** dem Auth-Screen-Change (Drift-Sync §6), Dev verdrahtet die dedizierte Copy + Affordance-Zeile beim Register→Pending-Übergang.
- **UX-QA später:** die 8 §5-Invarianten sind meine Abnahme-Checkliste (Kern = Orakel-Freiheit: identischer Ausgang neu vs. existierend, inkl. liveRegion + kein Fehler-Ton). Timing-Parität (§7-2) verifiziert Test, nicht ich.
- **Reuse-Selbstvalidierung:** `auth_link_to_login`/`auth_link_forgot` gegen `strings.xml` verifiziert (existieren, Z.386–388); `auth.verify.*`-Tags gegen `AuthTags.kt` verifiziert (Area/scopeId real). [[verify-reuse-testtags-against-code]]
