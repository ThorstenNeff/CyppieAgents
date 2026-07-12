# CYP-470 (P2-i) — Auth im DOM: der Redirect-Session-Gate, der NIE Credentials anfasst

> Owner: UIUX-Designer · Ticket **CYP-470** (P2-i, **die letzte ○-Cutover-Lücke**, Story unter Epic **CYP-430**) · Stand 2026-07-12
> Basis `origin/develop` `abf861e0` · erweitert die bestehende **Kratos-Config CYP-176/230/413** (am etablierten Flow ausgerichtet, nicht neu erfunden) · Docs-only → **Dev5-Referenz**.
> **PO1-Shape:** Kratos-hosted Login via Redirect (web-ts fasst **NIE** Credentials an), `whoami`→Operator/Member, Kratos-Logout-Flow, 401→Re-Auth-Redirect.
> **Quelle (Contract + REFERENZ, kein 1:1-Port):** `core/model/AuthMe.kt` (whoami) · `auth/AuthRepository.kt` (`SessionState`) · `auth/AuthGate.kt` (Compose-Referenz — **branded, wird NICHT portiert**, §0).

---

## 0. Der Trade-off zuerst (Objekt-Befund — bitte einzeln bestätigen/eskalieren)

Der PO bat, einen Produkt-/Risk-Trade-off **einzeln** zu flaggen. Hier ist einer, und er prägt die ganze Spec:

- **Der etablierte Compose-Auth-Flow ist BRANDED in-app (Credential-Touch):** `AuthGate.kt` rendert `LoginScreen`
  (Email/Passwort-Formular), Register, Reset, `VerifyPendingScreen` — der Compose-**Client** fasst Credentials an und
  trägt dafür den vollen Ehrlichkeits-Vertrag (Enumeration-Safety, Rate-Limit, neutrale Wortlaute).
- **PO1s web-ts-Shape ist REDIRECT-only (kein Credential-Touch):** web-ts bounced zum Kratos-hosted Login, hält nur das
  Session-Cookie.

**Das ist eine bewusste Divergenz, kein Port.** Aber sie ist **am etablierten Flow ausgerichtet**, nicht neu erfunden:
der **Server unterstützt die Browser-Session-Cookie-Bahn schon** (`AuthRepository`-KDoc: „a browser session, whose
credential is the same-origin `ory_kratos_session` cookie the engine sends automatically"). web-ts nutzt also die
**Browser-Variante** des etablierten Flows, nicht die Compose-Client-Branded-Variante.

**Konsequenz:** CYP-470 ist **KEIN** Port von `AuthGate`/`LoginScreen`/Register/Reset — es ist ein **dünner
Session-Gate**. Der **Verlust**: die branded in-app Login/Register/Reset-UX (der Nutzer sieht die Kratos-hosted Seite,
ein Kontext-/Brand-Wechsel). Der **Gewinn**: web-ts fasst **nie** Credentials an — kleinere Angriffsfläche, keine
Enumeration-Safety-/Rate-Limit-Last im DOM.

> **ENTSCHEIDUNG FINAL (PO 2026-07-12): redirect-only STEHT.** Die Auftraggeber-Antwort war unklar → der PO fährt auf
> der **einstimmigen** Empfehlung (me + PO1 + PO). Diese Spec ist damit der **verbindliche** Auth-Scope. Sollte später
> doch **branded Web-Login** gefordert werden, ist das ein **anderer Scope** (Credential-Touch) — den liefere ich dann
> **additiv** nach; die redirect-only-Fläche hier bleibt korrekt (der Session-Gate/whoami/Logout/401-Teil trägt in
> beiden Welten).
> **Sub-Frage GELÖST (PO 2026-07-12):** der Verify-Email-Zustand = **dünner web-ts-Verify-Gate** (meine Empfehlung —
> ehrlicher als ein stiller Redirect-Loop), **nicht** Kratos-Redirect. Siehe §2.

---

## 1. Das Credential-Grenze-Prinzip (mein Revier, hart)

web-ts rendert **NIE** eine Credential-Fläche — **kein** Passwort-/Email-Input, **kein** Login/Register/Reset-Formular.
Login, Register, Reset, Verify leben **Kratos-hosted** (Redirect). Der Browser hält **nur** das **same-origin
`ory_kratos_session`-Cookie** — von der Engine automatisch gesendet, **nie** als Header, **nie** in DOM/JS/localStorage
gelesen oder gespiegelt. Die Enumeration-Safety/Rate-Limit/Neutral-Wortlaut-Ehrlichkeit lebt **bei Kratos** — web-ts
**kann sie nicht verletzen**, weil es die Credential-Fläche **gar nicht hat**. Das ist die **stärkste** Leak-Grenze:
die Fläche existiert nicht (vgl. CYP-433 API-Key present-but-disabled ≥ CYP-432 Omission — hier noch eine Stufe: die
Credential-Fläche ist **nicht im Produkt**).

---

## 2. Die vier Zustände (aus `AuthMe`/`SessionState`) + ihre web-ts-UX

`GET /api/auth/me` → `AuthMe(authenticated, role, verified)` (content-free — keine id/email/secrets außer der
Unverified-Email fürs Gate). `SessionState`: `None` / `Unverified(email)` / `Active`.

| Zustand | Bedingung | web-ts-UX |
|---|---|---|
| **None** (unauth) | kein gültiges Cookie / `authenticated=false` | **Redirect zum Kratos-Login-Flow** (self-service Login-URL, CYP-176-Config). Zwischenschirm „Weiterleitung zur Anmeldung…" (**kein Formular**), dann bounce. |
| **Unverified(email)** | `authenticated=true, verified=false`, `role=null` | **dünner web-ts-Verify-Gate** (PO-entschieden 2026-07-12): „Bitte bestätige deine E-Mail: `<email>`" + Resend (Kratos-Flow). **Kein App-Zugang** (guarded routes 401 weiterhin). Ein ehrlicher Zustand-Screen, **kein** stiller Redirect-Loop. |
| **Active + role=OPERATOR** | authenticated+verified, `role="OPERATOR"` | voller Zugang; Operator-Gates **offen** (aus whoami, §3). |
| **Active + role=MEMBER** | authenticated+verified, `role="MEMBER"` | Zugang; Operator-Flächen present-but-disabled/omitted (unverändert, aber jetzt aus whoami). |

**Fail-closed:** whoami-Netzwerkfehler → `None` → unauth-Redirect. **Nie** optimistisch „authenticated/operator"
rendern, **nie** App-Inhalt vor der whoami-Auflösung flashen (erst Session klären, dann rendern).

---

## 3. `whoami` treibt Operator/Member (ersetzt den injizierten Token als Wahrheit)

Heute leitet `App.tsx` `cfg.operator` aus dem **injizierten** Operator-Token ab (`operatorToken()`/`isOperatorServe`).
CYP-470 macht die **authentifizierte Session** zur Wahrheit: `cfg.operator = (AuthMe.role === "OPERATOR")`. Der
**injizierte Operator-Token bleibt der break-glass-Pfad** (jedes Data-Repo sendet weiter seinen eigenen
`Authorization: Bearer`; die Session-only-Nutzer authentifizieren via Cookie). **Fail-closed:** kein/unklares whoami →
Member/unauth, nie „operator" geraten. Die schon gebauten Operator-Gates (present-but-disabled / Omission je
Datenbesitzer, Konsistenz-Pass ✓) hängen damit an der **echten** Session, nicht nur an einem injizierten Token.

---

## 4. Logout = Kratos-Flow, kein Fake-Clear

Der Logout-Control → **Kratos-Logout-Flow** (Redirect; invalidiert die Session **Kratos-/server-seitig**). **KEIN**
client-seitiges „Cookie löschen und so tun als ob" — die Session-Invalidierung ist **server-autoritativ** (dieselbe
Wurzel wie „aria-checked=enforced, nicht Klick-Echo": der Client behauptet keinen Zustand, den der Server nicht
bestätigt hat). Nach Logout → `None` → Login-Redirect.

---

## 5. 401 → Re-Auth-Redirect (Session-Ablauf ehrlich)

Jeder API-/WS-**401** (Session abgelaufen/entzogen) → **Re-Auth-Redirect** zum Kratos-Login-Flow. **KEIN** stilles
Weiterlaufen mit **stale Operator-UI**, **keine** Endlos-Retry-Schleife. Der Nutzer sieht „Sitzung abgelaufen — neue
Anmeldung…" + bounce. Analog zum `accessRevoked`-fail-closed von Event-Log/Comm: ein entzogener Zugriff räumt die
Fläche, statt sie stale weiterzuzeigen.

---

## 6. Session-Status sichtbar (ehrlich, content-free)

Ein kleiner **Session-Indikator**: „Angemeldet als **Operator**/**Member**" (aus `AuthMe.role`, **Text + Label**, nie
nur Farbe) + **Logout** (`auth_logout`). **Keine** Identität/Email/Secrets im Klartext gerendert (AuthMe ist
content-free; die Unverified-Email erscheint **nur** im Verify-Gate §2, nirgends sonst). Der Indikator ist die ehrliche
Antwort auf „wer bin ich / wie komme ich raus", nicht mehr.

> **Scope-Entscheidung GESETZT (PO-Frage 2026-07-12) — Identitäts-Anzeige = (a) nur Rolle+verified.** Der Kern-Flow
> braucht funktional **nur** `role` (Operator-Gates) + `verified` (Verify-Gate) + Logout. Das hält den CYP-182-`AuthMe`
> **content-free intakt** → **NULL Server-Arbeit**. **Deferred-additiv (nicht jetzt):** (b) eigene `identityId`
> (Self-Attribution „du bist X") = sauberer Späterschritt, falls je eine Multi-Account-Verwechslungs-Not entsteht
> (geteilte Maschine); im redirect-only-Single-Operator-MVP marginal (die Rolle ist das handlungsleitende Signal,
> Logout die Escape). (c) Display-Name (Kratos-Traits) erst recht später. Kein Assist-Sign-off nötig, weil content-free
> unangetastet bleibt.

---

## 7. Reuse vs. NICHT-Port

- **REUSE:** der `AuthMe`-Contract (`core`) · die Kratos self-service Flow-URLs (CYP-176/230/413-Config) · das
  etablierte Operator-Gate-Muster (present-but-disabled / Omission) · `auth_logout`/`auth_loading`/das
  `auth_github_redirect`-Redirect-Message-Muster.
- **NICHT portiert (bewusst, §0):** `AuthGate.LoginScreen` / Register / Reset / `VerifyPendingScreen`-**Formulare** und
  die zugehörigen branded `auth_*`-Keys (Email/Passwort/Login/Register/Reset) — sie leben **Kratos-hosted**. Der
  Enumeration-Safety-/Rate-Limit-Vertrag reist **nicht** in web-ts, weil die Fläche nicht in web-ts ist.

---

## 8. Keys — teils neu (redirect-only-Flow, kein Port)

Der Redirect-Flow braucht **wenige neue** Keys (die branded `auth_*`-Keys passen nicht). Vorschlag (DE Default / EN):

| Key | DE | EN |
|---|---|---|
| `auth_redirecting_signin` | Weiterleitung zur Anmeldung… | Redirecting to sign-in… |
| `auth_session_expired` | Sitzung abgelaufen – neue Anmeldung… | Session expired — signing in again… |
| `auth_verify_pending_title` | E-Mail bestätigen | Verify your email |
| `auth_verify_pending_body` | Bitte bestätige deine E-Mail: %1$s | Please verify your email: %1$s |
| `auth_signed_in_as` | Angemeldet als %1$s | Signed in as %1$s |
| `auth_role_operator` | Operator | Operator |
| `auth_role_member` | Member | Member |

**Reuse:** `auth_logout` („Abmelden"/„Sign out"), `auth_loading`, `auth_rate_limited` (falls Kratos rate-limitet
zurückkommt), `auth_verify_resend`-Analog falls vorhanden. **Shared-Key-Sync mit der Impl timen** (`:app:shared`
compose.resources — der Developer landet die Keys **im selben Commit** wie die Impl, [[shared-key-landing]]). Tags:
Area `auth` — `auth.redirect`/`auth.verifyGate`/`auth.sessionStatus`/`auth.logout` (final gg. `AuthTags` beim Landen;
die branded `AuthTags` sind hier größtenteils n/a).

---

## 9. Ehrlichkeits-Invarianten (Basis der Abnahme)

1. **Credential-Grenze:** web-ts rendert **nie** Credential-Felder; Login/Register/Reset/Verify = Kratos-hosted-Redirect;
   das Session-Cookie **nie** in DOM/JS/localStorage/Log (§1).
2. **whoami = Wahrheit** für Operator/Member (nicht der injizierte Token allein); fail-closed → Member/unauth (§3).
3. **Logout = Kratos-Flow** (server-autoritativ), kein client-Fake-Clear (§4).
4. **401 → Re-Auth-Redirect;** kein stilles stale-Operator-Weiterlaufen, keine Retry-Schleife (§5).
5. **`verified=false` ≠ Zugang** (Verify-Gate; guarded routes 401) (§2).
6. **AuthMe content-free** — keine id/email/secrets gerendert (außer der Unverified-Email im Gate) (§6).
7. **role = Text + Label**, Farbe nie allein; **kein unauth-Inhalt-Flash** vor der whoami-Auflösung.

---

## 10. Abnahme-Zähne (diskriminierend — je mit der falschen Impl, die er ablehnt)

1. **Keine Credential-Fläche.** **Mutation:** web-ts rendert ein Passwort-/Email-/Login-Formular (statt Redirect) ⇒ rot.
2. **whoami treibt Operator.** **Mutation:** Operator-UI aus dem injizierten Token trotz `role=MEMBER` **oder**
   optimistisch „operator" bei whoami-Fehler ⇒ rot.
3. **Logout = Kratos-Flow.** **Mutation:** client-seitiges Cookie-Clear ohne Kratos-Logout (Session bleibt server-gültig) ⇒ rot.
4. **401 → Redirect.** **Mutation:** ein 401 wird still geschluckt / stale Operator-UI bleibt / Endlos-Retry ⇒ rot.
5. **`verified=false` gegatet.** **Mutation:** eine unverifizierte Session bekommt App-Zugang ⇒ rot.
6. **Cookie/Session nie im DOM.** **Mutation:** das Session-Token/Cookie erscheint in DOM/JS/localStorage/Log/`data-*` ⇒ rot.
7. **Kein unauth-Flash.** **Mutation:** App-Inhalt (Fenster/Operator-UI) rendert kurz **vor** der whoami-Auflösung ⇒ rot.

---

## 11. DOM-/A11y-Spezifika

- **Redirect-Zwischenschirme** (`auth.redirect`, `auth_redirecting_signin`/`auth_session_expired`) = `role="status"`
  (kurz sichtbar, dann `window.location`-Redirect auf die same-origin Kratos-Flow-URL). **Verify-Gate**
  (`auth.verifyGate`) = `role="region"` mit Heading. **Session-Indikator** (`auth.sessionStatus`) = `role="status"`.
- **Logout** ≥ 24px, `role="button"`. `role` = **Text + Label** (Operator/Member), Farbe nie allein.
- **Fail-closed-Render-Reihenfolge:** erst whoami auflösen → dann rendern; **nie** Operator-Fenster/-Controls vor der
  Session-Auflösung mounten (analog zum Event-Log-Mount-Gating). **Kein `ellipsis`** auf Offenlegungs-/Status-Text.

**Nichts gebaut — Spec + Dev5-Referenz.** CYP-470 schließt die **letzte** Cutover-○-Lücke als **dünner Redirect-
Session-Gate**: web-ts fasst **nie** Credentials an, `whoami` treibt Operator/Member, Logout und Re-Auth sind
Kratos-Flows, `verified=false` gatet — und die branded Login-UX bleibt **bewusst** Kratos-hosted (§0, Eskalations-Frage
offen, falls branded Web-Login ein Produkt-Muss ist).
