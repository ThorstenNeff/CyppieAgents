# CYP-465 (P2-h) — Repo-Re-Provision-Work-Guard + Discard-Bestätigung: die Safety-Lücke schließen

> Owner: UIUX-Designer · Ticket **CYP-465** (P2-h, Story unter Epic **CYP-430** Voller-Ersatz-Cutover) · Stand 2026-07-12
> Basis `origin/develop` `69a14a3a` · CYP-247 S2 (D4/D5) · Docs-only → **Dev5-Referenz**. **Kein Cutover-Blocker** (keine Parity-Regression — die alte Compose-UI hatte den Flow **auch nicht**), aber billig + schließt eine echte **destruktive** Safety-Lücke.
>
> **Kein Port — neuer Flow.** Es gibt **keine bestehende Compose-UI** dafür (das Feld ist heute rein backend-erzwungen). Design **gegen die bestehenden Backend-Felder** + das etablierte **„irreversibel = Bestätigung + Folgen"-Muster** (CYP-450 Remove, CYP-461 Opt-in).
> **Quelle (nur Modell/Route, keine UI):** `core/model/ConfigModel.kt` (`RepoConfigRequest.discardUnpushed` · `RepoConfigView.reprovisionPending`) · `server/boot/RepoReprovision.kt` · `server/routing/ConfigRoutes.kt`.
> **Host:** die **Repo-Section** in **CYP-453 Settings** (ich erweitere sie **nicht** — CYP-453 ist in der Merge-Kaskade; CYP-465 ist der eigene Flow, der in derselben Section-Fläche landet, wenn beide gebaut sind).

---

## 0. Der Flow, wie das Backend ihn wirklich fährt (zuerst geerdet, damit die UX nicht lügt)

Wichtig, weil naheliegende UX-Annahmen hier falsch wären:

1. **`PUT /api/config/repo` blockt NICHT synchron.** Es setzt die neue url/branch (400 bei `invalid_repo_url`) und
   markiert den Clone **stale** (`RepoReprovision.markStale(pid, discardUnpushed)`) → antwortet mit
   `reprovisionPending = true`. Die Agenten laufen **weiter auf dem alten Clone**.
2. **Der Work-Guard blockt SPÄTER** — beim nächsten (Neu-)Start eines Agenten, wenn die Re-Provision tatsächlich
   ausgeführt wird: hat ein Agent einen **dirty tree oder nicht-gepushte Commits** **und** ist `discardUnpushed=false`
   → **Re-Provision blockiert** (alter Clone bleibt, kein stiller Verlust). `discardUnpushed=true` → alter Clone
   abgerissen, Arbeit **verworfen**, neu geklont.
3. **`discardUnpushed` wird beim Speichern gesetzt** (Feld von `RepoConfigRequest`); `markStale` kann die Opt-in-Flag
   **nachträglich aktualisieren**, um eine blockierte Re-Provision freizugeben.
4. **`reprovisionPending`** kommt aus `GET /api/config/repo` (`= reprovision.pending(pid) != null`).

**Konsequenz für die UX:** Es gibt **drei** ehrliche Zustände, nicht einen — *steht an & sauber*, *steht an & blockiert
(unpushte Arbeit)*, *verwerfen bewusst opt-in*. Die UI darf **nie** „gespeichert" mit „hat gegriffen" verwechseln.

---

## 1. Die Safety-Lücke, die wir schließen

`discardUnpushed` ist heute ein **nacktes Wire-Feld ohne UI**. Würde eine UI es als **schlichte Checkbox** verdrahten,
wäre es ein **stiller Datenverlust-Schalter** — genau das Gegenteil von ehrlicher Offenlegung. CYP-465 macht daraus einen
**bewussten, default-sicheren, unwiderruflich-bestätigten** Akt (Muster CYP-450/CYP-461) **und** legt den
*pending/blocked*-Zustand ehrlich offen.

---

## 2. Drei ehrliche Zustände in der Repo-Section

| Zustand | Bedingung | Anzeige | Ton |
|---|---|---|---|
| **Steht an** | `reprovisionPending`, kein bekannter Block | „Repo-Änderung steht an — wirkt beim nächsten (Neu-)Start der Agenten." | **amber** (EFFECT_DEFERRED, „gespeichert ≠ aktiv") |
| **Steht an & blockiert** | pending **und** Agenten haben unpushte/uncommittete Arbeit, `discardUnpushed=false` | „Re-Provision **blockiert**: Agenten haben nicht gepushte Arbeit. Pushen/committen — oder Verwerfen bestätigen." | **amber Attention** (Gefahr, **nicht** error-rot) |
| **Verwerfen (opt-in)** | Operator hat `discardUnpushed=true` bestätigt | (der Discard-Pfad, §3) | — |

**Kern-Ehrlichkeit:** `reprovisionPending=true` heißt **„steht an", nicht „hat gegriffen"** — der Agent läuft noch auf
dem alten Clone. Und ist es **blockiert**, muss die UI das **sagen** — sonst glaubt der Operator, seine Repo-Änderung
sei aktiv, während sie still nicht griff. Das ist dieselbe „gespeichert ≠ aktiv"-Wurzel wie mein P2-f/CYP-453
Effect-Hint, hier mit einem **zweiten** Grund (blockiert), der nie verschluckt werden darf.

---

## 3. Der Discard-Opt-in — default-sicher, unwiderruflich-bestätigt (Muster CYP-450/CYP-461)

`discardUnpushed` **niemals** als schlichte Checkbox. Der Pfad:

- **Default = KEEP (`discardUnpushed=false`).** Der Work-Guard **blockt** bei dirty/unpushter Arbeit — **kein stiller
  Verlust**. Das ist die sichere, voreingestellte Wahrheit.
- **DISCARD = bewusster, benannter, bestätigter Akt.** Turning it on öffnet einen **Bestätigungs-Dialog**
  (`role="alertdialog"`, Muster CYP-450 Remove): benennt die **unwiderrufliche Folge** — „nicht committete/gepushte
  Arbeit in Agenten-Worktrees geht **verloren**, wenn die Repo-Änderung greift" — **cancel-erstfokussiert**,
  Confirm ist die benannte, destruktive Aktion.
- **„Kann keine Folge autorisieren, die man nicht sieht" (CYP-461-Prinzip):** wenn das Backend die **betroffene
  Arbeit** kennt (welche Agenten, dirty vs. unpushed), zeigt der Dialog sie **konkret** — nicht nur generisch. Kennt es
  sie zum Bestätigungs-Zeitpunkt **nicht** (der Guard läuft erst bei der Re-Provision), ist die Warnung **advisory**
  formuliert („**falls** Agenten dann unpushte Arbeit haben, geht sie verloren") — nie als sichere Aussage getarnt.

> **Die eine Backend-Scope-Frage (⟂Backend2, entscheidet den ehrlichsten Ort der Bestätigung):** kann der
> *pending/blocked*-Zustand die **betroffenen Agenten** tragen (z. B. `reprovisionPending` angereichert um
> `blockedAgents: [id…]` bzw. ein `GET …/reprovision-status`)? **Wenn ja** — der ehrlichste Flow ist: Speichern setzt
> `discardUnpushed=false` (sicher), und die **Discard-Bestätigung erscheint erst, wenn die Re-Provision real blockiert**,
> mit der **konkreten** betroffenen Arbeit (mein Empfehlung — man bestätigt den Verlust, den man **sieht**). **Wenn nein**
> — Fallback: Discard-Opt-in beim Speichern mit **advisory** Warnung (§3), default-sicher. Ich spezifiziere **beide**
> Enden; der finale Ort hängt an deiner/Backend2s Antwort.

---

## 4. Keys — **neu** (nicht portierbar; Landung mit der Impl)

Anders als meine bisherigen P2-Specs ist dies **kein reiner Port** → es braucht **neue Keys**. Vorschlag (DE Default /
EN), alle als Text + Ton + a11y, **Farbe nie allein**, **kein `ellipsis`** (Offenlegung bricht um):

| Key | DE | EN |
|---|---|---|
| `settings_repo_reprovision_pending` | Repo-Änderung steht an – wirkt beim nächsten (Neu-)Start der Agenten. | Repo change pending — applies on the agents' next (re)start. |
| `settings_repo_reprovision_blocked` | Re-Provision blockiert: Agenten haben nicht gepushte Arbeit. Pushen/committen – oder Verwerfen bestätigen. | Re-provision blocked: agents have unpushed work. Push/commit — or confirm discard. |
| `settings_repo_discard_label` | Nicht gepushte Agenten-Arbeit beim Re-Provisionieren verwerfen | Discard unpushed agent work on re-provision |
| `settings_repo_discard_warning` | Unwiderruflich: nicht committete/gepushte Arbeit in den Agenten-Worktrees geht verloren, wenn die Repo-Änderung greift. | Irreversible: uncommitted/unpushed work in the agent worktrees is lost when the repo change applies. |
| `settings_repo_discard_confirm` | Arbeit verwerfen & re-provisionieren | Discard work & re-provision |

**Reuse:** `settings_repo_effect_hint` bleibt der statische „wirkt auf neue Worktrees / nächster Boot"-Hint; die neuen
Keys sind der **Live/destruktive** Zusatz. Cancel = bestehender generischer Cancel (`comm_back`/`connector_optin_cancel`-
Analog — final beim Dev-Gegenlesen). **Shared-Key-Sync mit der Impl timen** (`:app:shared` compose.resources) — der
Developer landet sie **im selben Commit** wie die Impl, sonst bricht ein Shared-Check ([[shared-key-landing]]). Tags:
additive unter `settings.repo.*` (`settings.repo.reprovisionPending`/`.reprovisionBlocked`/`.discardToggle`/
`.discardDialog`/`.discardConfirm`) — final gg. `SettingsTags` beim Landen.

---

## 5. Ehrlichkeits-Invarianten (Basis der Abnahme)

1. **`pending ≠ applied`:** `reprovisionPending` sagt „steht an", nie „hat gegriffen" (Agent läuft noch auf altem Clone).
2. **Blocked wird offengelegt:** ist pending **blockiert** (unpushte Arbeit), sagt die UI **warum** — nie stilles „ok".
3. **Discard default-sicher:** default = keep (Guard blockt, kein stiller Verlust); Discard nur als bewusster,
   bestätigter, benannter Akt.
4. **Irreversibel = Bestätigung + Folgen** (CYP-450): der Discard-Dialog benennt die unwiderrufliche Folge,
   cancel-erstfokussiert.
5. **Folge sichtbar vor der Autorisierung** (CYP-461): betroffene Arbeit konkret zeigen, wenn bekannt; sonst **advisory**
   formulieren, nie als sichere Aussage.
6. **Gefahr ≠ Fehler:** die blockiert-/Warn-Zeilen sind **amber Attention**, nicht error-rot (a0/CYP-385); ein echter
   Request-Fehler bleibt error-rot.
7. **Operator-gated fail-closed** (geerbt von der Repo-Section, CYP-453 §0): kein zweiter Gate.
8. **Farbe nie allein; kein `ellipsis`** auf Warn-/Blocked-/Pending-Text.

---

## 6. Abnahme-Zähne (diskriminierend — je mit der falschen Impl, die er ablehnt)

1. **`pending ≠ applied`.** **Mutation:** `reprovisionPending` als „Repo aktiv/umgestellt" gerendert (statt „steht an,
   nächster Neustart") ⇒ rot.
2. **Blocked offengelegt.** **Mutation:** eine blockierte Re-Provision wird als „steht an"/„ok" gezeigt, ohne den
   unpushte-Arbeit-Grund ⇒ rot.
3. **Discard default-sicher.** **Mutation:** `discardUnpushed` startet `true` / ist vorausgewählt ⇒ rot.
4. **Discard = bestätigter, benannter, irreversibler Akt.** **Mutation:** Discard als schlichte Checkbox ohne
   Bestätigungs-Dialog / ohne Nennung des Verlusts ⇒ rot.
5. **Folge sichtbar/advisory.** **Mutation:** der Dialog behauptet konkret verlorene Arbeit, die er nicht kennt (statt
   advisory), **oder** verschweigt die betroffene Arbeit, die das Backend liefert ⇒ rot.
6. **Gefahr amber, nicht error-rot.** **Mutation:** blockiert/Warn im Error-Ton (als App-Fehler) ⇒ rot.

---

## 7. DOM-/A11y-Spezifika

- **Discard-Dialog** = `role="alertdialog"` (Focus-Trap, **Cancel erstfokussiert**, Confirm = destruktiv benannt). Die
  drei Zustände (§2) als `role="status"` (pending) bzw. `role="alert"`/amber (blocked). **Discard-Toggle** =
  `role="checkbox"`, `aria-checked` = der **gesetzte** Opt-in-Zustand (nie Klick-Echo).
- Amber = `--md-sys-color-warn-container`/`-on-warn-container` (die WARN-Rolle, keine Grün-Rolle); Error = error-Rolle.
- Zielgröße ≥ 24px (Toggle, Confirm/Cancel). **Farbe nie alleiniger Träger.** **Kein `ellipsis`** auf Warn-/Blocked-/
  Pending-/Dialog-Text (Offenlegung bricht um). **RTL** gespiegelt.

**Nichts gebaut — Spec + Dev5-Referenz.** Ein Repo-Wechsel kann **Agenten-Arbeit zerstören**; heute erzwingt das nur der
Server, ohne dass der Operator die Folge sieht. CYP-465 macht die Folge **sichtbar und bestätigungspflichtig** (default =
kein Verlust), legt *steht-an* und *blockiert* ehrlich auseinander — und der Verlust wird nur verworfen, wenn eine Hand
ihn benannt und bestätigt hat. **Die eine offene Frage** (§3) ist der ehrlichste **Ort** der Bestätigung — sie hängt an
Backend2s Fähigkeit, die betroffene Arbeit zu benennen.
