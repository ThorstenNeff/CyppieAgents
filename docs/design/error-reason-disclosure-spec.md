# ERROR-Grund — Offenlegungs-Spec + Backend-Naht-Frage (CYP-351 §4, aus CYP-396 herausgehalten)

> Owner: UIUX-Designer · Stand 2026-07-11 · Basis `origin/develop` `b3d08dae` · Scope: Compose **und** DOM (medien-unabhängig)
> Docs-only. Quelle: `cyp351-unknown-vs-error-spec.md` §4. **Getrennt vom CYP-396-Ring-Fix** (der ist backend-
> unabhängig, QA'd GO) — dieser Teil **hängt an einer Backend-Naht** und ist erst umsetzbar, wenn Backend2 die
> Vertragsfrage (§2) beantwortet hat. **Kein CYP-Key vergeben** — re-anker sobald getickt. **Nichts gebaut — Spec.**
>
> **Zweck dieses Dokuments:** (1) die **präzise Frage an Backend2** formulieren, damit sie gestellt werden kann;
> (2) das **fail-closed-Display** so festlegen, dass die UI heute schon ehrlich ist und bei jeder der möglichen
> Backend-Antworten bereit ist.
>
> ---
> **UPDATE 2026-07-11 — Backend2-Contract-Read beantwortet die BE-Fragen (Ticket `CYP-421`, gebündelt mit
> serverNowMs/Delegation):**
> - **BE-1 = Nein (heute):** `AgentRunStateEvent` ist **content-free by construction** (`{agentId, runState}`) — der
>   **immer-sichtbare Header-Feed** darf die **operator-gated** `/ws/events`-Egress **nicht** wiederverwenden. Kein
>   Grund-Feld heute; hinzufügen = Contract-Change (CYP-421).
> - **BE-2 = Code, kein Freitext (harte Grenze):** weil der Header-Feed **nicht operator-gated** ist, bräche ein
>   Freitext-Grund (z. B. `stderr`) die **Leak-Grenze**. Der Grund **muss** ein Enum sein:
>   **`CRASHED` / `SIGNALLED` / `SPAWN_FAILED` / `UNKNOWN`**.
> - **BE-3 = gemischt:** **autoritativ** beim beobachteten Exit-Code (CYP-351 `waitFor`; `null` = unbekannt,
>   fail-closed), **best-effort** beim Spawn-Fehler.
>
> **Konsequenz für diese Spec:** der **Freitext-Branch (§3 a') entfällt** (Leak-Grenze). Es bleiben **(a) Code →
> lokalisierter Satz** und **(b) fail-closed**. Die Code→Key-Paare sind jetzt konkret (§4) — die Code-Menge ist
> benannt. Der Rest der Spec trägt 1:1. **§2–§4 unten sind entsprechend aktualisiert; die ursprüngliche Frage bleibt
> als Beleg stehen, markiert.**

---

## 1. Der Mangel — und die aktuelle Wahrheit (an `b3d08dae` verifiziert)

`ERROR` sagt **„Fehler"**, nennt aber **keine Ursache**. Zwei Fakten aus dem Code, die die Grenze scharf ziehen:

- **Der Zustand kann heute gar keinen Grund tragen.** `AgentLifecycleState { RUNNING, STOPPED, ERROR, UNKNOWN }`
  ist ein **nacktes Enum** (`AgentLifecycleApi.kt:18`), gespiegelt aus `:core` `AgentRunState { RUNNING, STOPPED,
  ERROR }` (`AgentLifecycleClient.kt`, `toLifecycleState()`). Es gibt **kein Grund-Feld**. Ein Grund kann also
  erst angezeigt werden, wenn der Server ihn liefert — das ist die Naht.
- **`LifecycleErrorRow` ist NICHT der ERROR-Grund.** Diese Zeile (`AgentWindow.kt:382`) zeigt den Fehler **einer
  Aktion** (`already_running`/`spawn_failed`/`operator_required`/`mode_swap_failed`/generic), gespeist aus dem
  **transienten** `_lifecycleError` (letzter Steuer-Reject, beim nächsten Lifecycle-Event gelöscht). Das ist ein
  **anderer Lebenszyklus und eine andere Bedeutung** als der Grund eines **durablen** ERROR-**Zustands**.

> **Regel: Aktions-Fehler und Zustands-Grund nie zusammenlegen.** Ein Aktions-Reject („Start abgelehnt, läuft
> schon") auf derselben Zeile wie ein Zustands-Grund („Prozess mit Code 137 gestorben") würde beide verfälschen.
> Getrennte Quelle, getrennter Knoten (§3).

---

## 2. Die Frage an Backend2 (⟂BE-ERROR-Grund) — präzise, damit sie beantwortbar ist

**Liefert der Server zum `ERROR`-Zustand einen Grund?** Und wenn ja, in welcher Gestalt? Drei Unterfragen, deren
Antworten das Display bestimmen:

| # | Frage | Warum es das Display ändert |
|---|---|---|
| **BE-1** | Trägt `:core` `AgentRunState.ERROR` (bzw. der Lifecycle-Snapshot/`/ws/lifecycle`) **überhaupt** ein Grund-Feld? | Nein → nur der fail-closed-Pfad (§3b). Ja → §3a/a'. |
| **BE-2** | Ist der Grund ein **Code** (endliche, dokumentierte Menge wie die `ApiError`-Codes) **oder** ein **Freitext** (z. B. Prozess-`stderr`/Exit-Signal)? | **Code** → lokalisierter, kuratierter Satz (Reuse `LifecycleErrorRow`-Muster). **Freitext** → **wörtlich, herkunfts­markiert** gezeigt (nicht lokalisierbar, §3a'). |
| **BE-3** | Ist der Grund **autoritativ** oder **best-effort** (kann fehlen/ungenau sein)? | Best-effort → die UI stellt ihn als **Diagnose-Hinweis** dar, nicht als garantierte Ursache (Offenlegungs-Ehrlichkeit). |

> **Empfehlung an den Server (falls BE-1 = neu zu bauen):** ein **strukturierter Code** (BE-2 = Code) ist für die
> UI die ehrlichste und lokalisierbare Form — analog zu den `ApiError`-Codes. Ein optionales `detail`-Freitextfeld
> **zusätzlich** zum Code ist ok, solange die UI es als **rohe Prozessausgabe** kennzeichnet, nicht als kuratierten
> Satz. Reiner Freitext ohne Code zwingt die UI in §3a' (wörtlich + Herkunftsmarke), was tragbar, aber ärmer ist.

> **⇒ BEANTWORTET (CYP-421, 2026-07-11):** BE-1 = **Nein** (heute kein Grund-Feld; `AgentRunStateEvent` ist
> content-free by construction). BE-2 = **Code** (Enum `CRASHED`/`SIGNALLED`/`SPAWN_FAILED`/`UNKNOWN`), **Freitext
> ausgeschlossen** durch die Leak-Grenze (der Header-Feed ist nicht operator-gated). BE-3 = **gemischt** (autoritativ
> beim Exit-Code, `null`=fail-closed; best-effort beim Spawn-Fehler). → **§3a' entfällt; §3a/b + §4 sind konkret.**
> Meine Empfehlung „strukturierter Code" ist bestätigt; das **optionale `detail`-Freitextfeld ist gestrichen** —
> genau die Leak-Grenze, die ich als Risiko markiert hatte, macht es unzulässig.

---

## 3. Das Display — fail-closed und bereit für jede BE-Antwort

Der ERROR-Grund erscheint als **eigener Knoten am ERROR-Status** (nicht auf der `lifecycleError`-Aktionszeile).
**Präsentation wiederverwenden** (error-Ton, `labelSmall`/klein, volle Breite — wie `LifecycleErrorRow`), **Quelle
getrennt** (aus dem Zustands-Grund, nicht `_lifecycleError`). **Fail-closed:** der Knoten existiert **nur** im
`ERROR`-Zustand; verschwindet, sobald der Zustand auflöst.

**Entscheidungsbaum (final nach CYP-421 — zwei Pfade, der Freitext-Pfad entfällt):**

- **(a) Grund vorhanden, Code:** lokalisierter, kuratierter Satz über eine `when(code)`-Abbildung (**Muster** wie
  `LifecycleErrorRow`). Code-Menge = **`CRASHED`/`SIGNALLED`/`SPAWN_FAILED`/`UNKNOWN`** → Keys in §4. **Unbekannter/
  künftiger Code → `else`-Zweig = fail-closed-Text (b)**, **nie** der rohe Enum-Name als „Grund".
- **(b) Kein Grund-Feld / `null`:** **„Fehler — Grund nicht gemeldet"** (`agent_error_reason_unreported`). Deckt den
  Heute-Zustand (BE-1=Nein, kein Feld) **und** den `null`-Exit-Code (BE-3, unbekannt, fail-closed) ab. **Nie** leer,
  **nie** erfunden, **nie** ein anderer Zustand vorgetäuscht.
- **(a') Freitext — GESTRICHEN (CYP-421):** die **Leak-Grenze** (nicht-operator-gated Header-Feed) schließt rohen
  `stderr`/Freitext aus. Kein `agent_error_reason_raw_prefix`, kein monospace-Rohtext. *(Der Branch stand im
  Entwurf; er ist hier als bewusst entfernt vermerkt, nicht kommentarlos getilgt.)*

> **`UNKNOWN` (Enum-Wert) ≠ „nicht gemeldet" (kein Feld) — ehrliche Trennung:** `UNKNOWN` ist ein **gelieferter**
> Grund („der Server hat beobachtet, konnte aber nicht klassifizieren") → eigener Key `agent_error_reason_unknown`
> („Grund unbekannt"). `agent_error_reason_unreported` gilt, wenn **gar kein Feld** kommt (Übergangszeit vor
> CYP-421 / älterer Server) **oder** der Exit-Code `null` ist. Beide sind fail-closed-ehrlich, aber sie sagen
> Verschiedenes: „gemeldet, unbekannt" vs. „nicht gemeldet". Falls das Backend `null`-Exit **als** `UNKNOWN`
> kodiert, kollabieren sie zu einem Fall — **das ist eine ⟂BE-Rückfrage** (§4-Fußnote), keine UI-Erfindung.

**Unverhandelbare Offenlegungs-Regeln:**
1. **Kein erfundener Grund.** Fehlt der Grund, sagt die UI das (b) — sie rät nicht aus Symptomen.
2. **Grund ≠ Garantie.** BE-3=best-effort betrifft v. a. **`SPAWN_FAILED`**: der Grund ist ein **Diagnose-Hinweis**,
   nicht die zugesicherte Ursache; die Wortwahl impliziert keine Gewissheit, die der Server nicht gibt. Die
   Exit-Code-Gründe (`CRASHED`/`SIGNALLED`) sind autoritativ.
3. **`ERROR` wird nie aufgelöst** (Bindeglied zu CYP-351 §3): kein `ERROR → STOPPED/RUNNING`, weil „ist ja nicht
   gelaufen". Der Grund erklärt den Zustand, er ersetzt ihn nicht.
4. **Leak-Grenze wahren:** kein Freitext/`stderr` im Header-Feed (CYP-421) — nur der Enum-Code, lokalisiert.

---

## 4. Keys — jetzt konkret (Code-Menge steht, CYP-421)

**Fail-closed (backend-unabhängig, sofort anlegbar):**

| Real-Key | DE | EN |
|---|---|---|
| `agent_error_reason_unreported` | Fehler — Grund nicht gemeldet | Error — reason not reported |
| a11y `a11y_agent_error_reason` | Fehlergrund: %1$s | Error reason: %1$s |

**Code → lokalisierter Satz (landen mit dem CYP-421-Contract-Consumer):**

| Real-Key | Enum-Code | DE | EN | Autorität (BE-3) |
|---|---|---|---|---|
| `agent_error_reason_crashed` | `CRASHED` | Abgestürzt (Exit-Code %1$s) | Crashed (exit code %1$s) | autoritativ |
| `agent_error_reason_signalled` | `SIGNALLED` | Durch Signal beendet (%1$s) | Terminated by signal (%1$s) | autoritativ |
| `agent_error_reason_spawn_failed` | `SPAWN_FAILED` | Start fehlgeschlagen | Failed to start | **best-effort** (Diagnose-Hinweis) |
| `agent_error_reason_unknown` | `UNKNOWN` | Grund unbekannt | Reason unknown | gemeldet-aber-unklassifiziert |

- Der **`%1$s`-Platzhalter** (Exit-Code/Signal) wird nur gesetzt, **wenn** der Contract die Zahl mitliefert; sonst
  die argumentlose Kurzform (kein leeres `%1$s`). ⟂BE-Rückfrage: liefert `CRASHED`/`SIGNALLED` die Zahl mit?
- **`SPAWN_FAILED` (Zustands-Grund) ≠ `spawn_failed` (Aktions-Fehler, `agent_ctl_err_spawn_failed`):** getrennte
  Keys, getrennte Surfaces (§1-Regel). Ähnlicher Wortlaut, andere Bedeutung/Quelle — nicht zusammenführen.
- **`else`-Zweig = `agent_error_reason_unreported`:** jeder unbekannte/künftige Enum-Wert fällt fail-closed, **nie**
  als roher Name. ⟂BE-Rückfrage: kodiert das Backend `null`-Exit als `UNKNOWN` oder als fehlendes Feld? (§3-Kasten.)

**Reuse (kein neuer Key/Tag):** `agent_status_error` (das Wort „Fehler"), die `error`-Farbrolle, das
`LifecycleErrorRow`-Präsentations- und `when(code)`-Muster. **Shared-Key-Drift** wie gehabt: ich entwerfe, der Dev
landet die Keys **mit** dem CYP-421-Contract-Consumer (nicht vorab isoliert mergen).

**testTag:** eigener Knoten `agent.<id>.errorReason` (analog `AgentViewTags.lifecycleError`), **präsent iff**
`state == ERROR`. Getrennt vom `agent.<id>.lifecycleError` (Aktionsfehler) — QA prüft die Trennung.

---

## 5. Medien-Unabhängigkeit (Compose + DOM)

Das Design ist medien-unabhängig. Compose: eine `Text`-Zeile unter dem Status (error-Ton, `labelSmall`), Text =
der lokalisierte Satz zum Enum-Code. DOM: ein `<div>` am Status, per `aria-describedby` an den Statusknoten
gebunden; a11y über `a11y_agent_error_reason`. **Kein Freitext-/`<pre>`-Fall mehr** (CYP-421-Leak-Grenze).
Fail-closed-Text und alle Offenlegungsregeln (§3) identisch.

---

## 6. Abnahme (fail-closed, diskriminierend) — für QA, wenn die Impl landet

Jeder Test benennt die falsche Implementierung, die er ablehnt:

1. **Kein Grund → „Grund nicht gemeldet".** Server liefert keinen Grund zum ERROR → die Zeile zeigt
   `agent_error_reason_unreported`. **Mutation:** leere Zeile / weggelassener Knoten bei `reason == null` ⇒ rot.
   **Mutation:** ein aus dem Kontext geratener Grund ⇒ rot.
2. **Code → lokalisierter Satz, nie roher Code.** (Wenn BE-2=Code.) Bekannter Code → sein Key; unbekannter →
   fail-closed-Text. **Mutation:** roher Code als „Grund" gezeigt ⇒ rot.
3. **`UNKNOWN` (Enum) → „Grund unbekannt", nicht dieselbe Zeile wie „nicht gemeldet".** Ein geliefertes `UNKNOWN`
   → `agent_error_reason_unknown`; ein **fehlendes** Feld → `agent_error_reason_unreported`. **Mutation:** beide auf
   denselben Text ⇒ rot (verwischt „gemeldet-unklar" mit „nicht gemeldet"). *(Freitext-Test des Entwurfs entfällt —
   CYP-421 schließt Freitext aus.)*
4. **`ERROR` nie aufgelöst.** Ein Server-`ERROR` rendert als `ERROR` (+ Grund/Fail-closed), nie als STOPPED/RUNNING.
   **Mutation:** Mapping `ERROR → STOPPED` ⇒ rot. (Bindeglied CYP-351 §3.)
5. **Trennung von der Aktionszeile.** `agent.<id>.errorReason` (Zustand) ≠ `agent.<id>.lifecycleError` (Aktion);
   ein Aktions-Reject erscheint **nicht** als Zustands-Grund und umgekehrt. **Mutation:** beide auf denselben
   Knoten/dieselbe Quelle ⇒ rot. Besonders `SPAWN_FAILED` (Zustand) ≠ `spawn_failed` (Aktion).
6. **Leak-Grenze.** Kein Freitext/`stderr` erreicht den Header-Feed; nur der Enum-Code wird gerendert. **Mutation:**
   ein roher `detail`-String im ERROR-Grund ⇒ rot (CYP-421-Grenze verletzt).

**Test 1 ist der wichtige** — er prüft die **Ehrlichkeit** (kein erfundener/leerer Grund), nicht die Anzeige.

---

## 7. Self-Validation

- **An der Naht gemessen, nicht geraten:** der Zustand ist heute ein nacktes Enum ohne Grund-Feld
  (`AgentLifecycleApi.kt:18`) — deshalb ist BE-1 die erste Frage, nicht das Display.
- **Aktions-Fehler ≠ Zustands-Grund** sauber getrennt (`LifecycleErrorRow` ist ersteres) — sonst maskiert das eine
  das andere.
- **Fail-closed ist der Default** (b) — die UI war schon vor der Contract-Antwort ehrlich, und bleibt es für jeden
  unbekannten/künftigen Enum-Wert (`else` → `unreported`).
- **Codes nicht vorab erfunden — jetzt vom Contract bestätigt:** die vier Keys (§4) folgen der von CYP-421
  benannten Enum-Menge, nicht meiner Vermutung.
- **Der Freitext-Branch ist bewusst entfernt, nicht getilgt** (§3 a'): die Leak-Grenze, die ich als BE-2-Risiko
  markiert hatte, macht ihn unzulässig — meine „strukturierter Code"-Empfehlung war die richtige.
- **`UNKNOWN` ≠ „nicht gemeldet"** ehrlich getrennt; die eine offene ⟂BE-Rückfrage (kodiert `null`-Exit als
  `UNKNOWN`?) benannt, nicht geraten.
- **Nicht an CYP-396 gekoppelt** (der ist backend-unabhängig, GO). ERROR-Grund = CYP-421-Contract-Consumer. **Docs-only.**
