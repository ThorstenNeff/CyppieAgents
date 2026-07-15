# Login-Happy-Path — Visual-UX-QA-Checkliste (Morgen-Readiness) (CYP-576 / CYP-575)

> Owner: UIUX-Designer · Stand 2026-07-15 · **Für den echten Login-Lauf.** Was **SIEHT** der Mensch, wenn es **KLAPPT** —
> je mit **Honesty-Achse** (kein falscher Erfolg · INFO-nicht-Error für Advisory · korrekte Live-Region). **Design/QA, kein
> Code.** Gegroundet READ-ONLY gg. develop `d06001a6` (meine Auth-Legibilität-Spec gebaut+gemergt, Dev `5a721953`).
> Begleit: `auth-oidc-legibility-{ux-spec,keys,tags}.md` + (B1-Enroll) `remote-uv-flow-render-oracle.md`.
>
> **Scope-Split (PO):** die **visuelle Legibilität** deck ich/Tester hier ab; die **Nicht-Render-Teeth** (Verhalten,
> Session-Gate, Loopback) decken die §-QA-/Test-Rubriken. Diese Liste = „sieht der Mensch die Wahrheit, wenn's gut geht".

## Lese-Legende
- **Sieht** = die gerenderte Copy (Key) + Tag @ `d06001a6`. **Ton** = Farb-Rolle/HintTone. **Live** = Live-Region.
- **Status:** ✅ conform (beim Lauf bestätigt) · 🟡 **assert-at-run** (Zeile = der Soll-Anblick) · ⚠ Befund → PO.

---

## Der Happy-Path in 7 Stationen (die Kette KLAPPT)

### ① Login-Screen (Ausgangspunkt)
| Sieht | Ton | Honesty | Live | Status |
|---|---|---|---|---|
| E-Mail/Passwort-Formular (`auth.login.form`) + „oder"-Divider (`auth_or_divider`) + **„Mit GitHub anmelden"** (`auth_github_button`, `auth.login.github`) | neutral | die E-Mail-Alternative ist **immer** sichtbar (OIDC nie die einzige Tür) | — | 🟡 |

### ② Klick „Mit GitHub anmelden" → `Starting` (Busy-Guard, transient)
| Sieht | Ton | Honesty | Live | Status |
|---|---|---|---|---|
| der Button wird **sofort disabled** (kein Text-Flash) — „Starting ist transient; der disabled Button IST das Feedback" | neutral | **kein** vorzeitiges „wird angemeldet" (kein falscher Erfolg); Double-Click kann keine 2 Flows starten | — | 🟡 |

### ③ `BrowserHandoff(url)` — der ehrliche Handoff (★ CYP-575-Kern)
| Sieht | Ton | Honesty | Live | Status |
|---|---|---|---|---|
| Primärzeile **„Weiter im Browser…"** (`remote_login_browser_handoff`, `remote.login.browserHandoff`), `bodySmall` `onSurfaceVariant` | neutral/INFO | sagt **„öffnet sich"**, nie „geöffnet" (H3) | — | 🟡 |
| **★ Fallback:** „Falls sich der Browser nicht öffnet, öffne diesen Link:" (`auth_github_handoff_fallback`) | neutral | der manuelle Ausweg ist **sichtbar**, auch wenn der Browser aufging | — | 🟡 |
| **die URL sichtbar + selektierbar** (`auth.github.handoffUrl`, `SelectionContainer`, monospace, `surfaceVariant`-Container) | neutral | die Authorize-URL steht **in der UI** (nicht nur im Terminal) — kein Blindflug | — | 🟡 |
| **„Link kopieren"** (`auth_github_url_copy`, `auth.github.handoffCopy`, `primary` TextButton) | `primary` Aktion | URL ist kein Secret → keine Egress-Disclosure nötig | — (`→UIUX2` Clipboard/a11y) | 🟡 |

> **Happy-Fall:** der OS-Browser öffnet sich, der Mensch meldet sich bei GitHub an. Die Fallback-URL bleibt sichtbar, ist
> aber nicht nötig. **Kein** Timeout erscheint (Loopback kommt prompt) — ④.

### ④ `Returning(native=true)` — Rücksprung
| Sieht | Ton | Honesty | Live | Status |
|---|---|---|---|---|
| **„Zurück zur App…"** (`remote_login_browser_return`, `remote.login.browserReturn`), `bodySmall` `onSurfaceVariant` | neutral/INFO | „Returning" = **wird abgeschlossen**, noch **nicht** „angemeldet" (kein falscher Erfolg) | — | 🟡 |

### ⑤ `Verified` → das Desktop erscheint (strukturelles Gate)
| Sieht | Ton | Honesty | Live | Status |
|---|---|---|---|---|
| die Auth-Screens verschwinden; `content(UserTier)` mountet = das Desktop/Hub-UI | neutral | das Desktop erscheint **nur** bei echtem `Verified` (AuthGate-Strukturgate) — **nie** Desktop-vor-Auth; GitHub-Erfolg ist **nicht** in „logged in" sondergecased, sondern läuft durchs normale Gate | — | 🟡 |

> **Ehrlichkeits-Verzweigung (nicht-happy, zur Abgrenzung):** landet der Nutzer stattdessen bei `AuthedUnverified` →
> Verify-Pending-Gate (erreicht das Desktop **nicht**). Happy-Path = `Verified`.

### ⑥ Hub erscheint (hubConnect-Flow)
| Sieht | Ton | Honesty | Live | Status |
|---|---|---|---|---|
| Erst-Start: `Preparing` → `LoadingHubs` → `Register`/`Credentials`/`Ready`; Wiederkehr: **`HubList`** (Presence advisory je `HubDescriptor`, H1) → Hub wählen → `ChoosingMode` | neutral | Presence ist **advisory** (nie „garantiert online"); `connected` erst bei realem LIVE (kein Vorgriff) | — | 🟡 |

### ⑦ B1-Enroll-States (die CYP-542-Fläche — Werte via `remote-uv-flow-render-oracle.md`)
| Sieht | Ton | Honesty | Live | Status |
|---|---|---|---|---|
| **Erst-Enroll** (`SetPassphrase`): Diceware-Default **lesbar angezeigt** + „Diese Passphrase verwenden" (`primary`) · type-your-own + **Strength-Meter** (OK `●`/`primary` · zu-schwach `▲`/WARN-amber · blocklisted error-Ton, Fill `outline`-gedämpft) | primary/WARN/error je Verdict | Diceware **muss** gezeigt werden (HG2, abschreibbar); Meter lügt nie „stark"; fail-closed | Meter **Polite**, Fehler **Assertive** | 🟡 |
| **Clipboard-Notice** (nach echtem Copy): „In die Zwischenablage kopiert…" (`onSurfaceVariant`, kein Glyph) | neutral | **past-tense + nur-nach-Copy** — nie vorzeitig „gespeichert/gelöscht" | — | 🟡 |
| **Wiederkehr** (`PassphrasePrompt`): App-Passphrase-Eingabe (maskiert, **kein** Reveal-Toggle §1b) → Entsperrung | neutral | maskiert; kein Klartext on-screen | — | 🟡 |
| **AUTHENTICATING** → `CircularProgressIndicator` `primary` + „Operator wird bestätigt…" (`remote_connect_authenticating`) | neutral/INFO | **verifying, nicht granted** — **kein** Erfolgs-Grün, kein Fake-Success | Polite | 🟡 |
| **connected** → Workspace erscheint | neutral | erst bei realem `CONNECTED`, **ohne** Erfolgs-Grün | — | 🟡 |

---

## Negativ-Raum: was auf dem Happy-Path NICHT erscheinen darf
- **Kein** `HintTone.ERROR`/error-rot irgendwo auf dem glatten Lauf (Handoff/Returning/AUTHENTICATING sind **neutral/INFO**).
- **Kein** `TimedOut` (`auth.github.timedOut`), wenn der Loopback prompt zurückkommt — der Timeout ist die **Ausnahme**, nicht der Anblick.
- **Kein** Erfolgs-Grün/`tertiary` als Status — nirgends (nicht bei Returning, nicht bei granted, nicht bei OK-Meter).
- **Kein** totes „Continuing…" ohne Fallback-URL darunter.
- **Kein** vorzeitiges „angemeldet"/„Browser geöffnet" vor dem echten `Verified`/`CONNECTED`.

## Honesty-Achse (die durchgängige Prüfung)
1. **Kein falscher Erfolg:** jede Progress-Zeile (Handoff/Returning/AUTHENTICATING) sagt *versucht/läuft*, nie *fertig*, bis das echte Gate greift.
2. **INFO-nicht-Error für Advisory:** Handoff/Returning/AUTHENTICATING = neutral/INFO; error-Ton bleibt echten Fehlern vorbehalten (auf dem Happy-Path abwesend).
3. **Korrekte Live-Region:** Advisory/Progress = **Polite** (Timeout, Meter, AUTHENTICATING); echte Fehler = **Assertive** (auf dem Happy-Path nicht ausgelöst).
4. **Advisory-Töne über die zwei Flächen konsistent** — der OIDC-Progress-`INFO` ist die Referenz (PO-bestätigt, CYP-574-Disziplin); B1-Enroll-Advisory alignt darauf.

## Ausführung (morgen, schnell)
Beim Lauf jede 🟡 → ✅/⚠. ⚠ = **priorisierte Liste** (Severity + konkreter Fix) an den PO, nicht selbst gefixt. Werte-
Abweichungen der B1-Fläche → `remote-uv-flow-render-oracle.md`; Verhaltens-Teeth (Session-Gate/Loopback) → Tester.

## Self-Validation
- **Gegroundet** gg. dem gebauten `GithubUiState`/`AuthGate`/`AuthTags` @ `d06001a6` (file:symbol) — die 3 OIDC-Legibilität-States (Handoff-URL/Timeout/Error≠Cancel) sind **gebaut wie spezifiziert** (INFO/Polite/Assertive geprüft).
- **Reuse:** B1-Enroll-Werte aus `remote-uv-flow-render-oracle.md` (nicht dupliziert); OIDC-Copy/Tags aus `auth-oidc-legibility-{keys,tags}.md`.
- **Happy-Path vollständig** ①→⑦ + Negativ-Raum + Honesty-Achse. Kein Bau, keine develop-Berührung; docs-only auf `feature/CYP-576-auth-oidc-legibility-spec`.
