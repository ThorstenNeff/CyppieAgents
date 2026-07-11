# ERROR-Grund — Offenlegungs-Spec + Backend-Naht-Frage (CYP-351 §4, aus CYP-396 herausgehalten)

> Owner: UIUX-Designer · Stand 2026-07-11 · Basis `origin/develop` `b3d08dae` · Scope: Compose **und** DOM (medien-unabhängig)
> Docs-only. Quelle: `cyp351-unknown-vs-error-spec.md` §4. **Getrennt vom CYP-396-Ring-Fix** (der ist backend-
> unabhängig, QA'd GO) — dieser Teil **hängt an einer Backend-Naht** und ist erst umsetzbar, wenn Backend2 die
> Vertragsfrage (§2) beantwortet hat. **Kein CYP-Key vergeben** — re-anker sobald getickt. **Nichts gebaut — Spec.**
>
> **Zweck dieses Dokuments:** (1) die **präzise Frage an Backend2** formulieren, damit sie gestellt werden kann;
> (2) das **fail-closed-Display** so festlegen, dass die UI heute schon ehrlich ist und bei jeder der möglichen
> Backend-Antworten bereit ist.

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

---

## 3. Das Display — fail-closed und bereit für jede BE-Antwort

Der ERROR-Grund erscheint als **eigener Knoten am ERROR-Status** (nicht auf der `lifecycleError`-Aktionszeile).
**Präsentation wiederverwenden** (error-Ton, `labelSmall`/klein, volle Breite — wie `LifecycleErrorRow`), **Quelle
getrennt** (aus dem Zustands-Grund, nicht `_lifecycleError`). **Fail-closed:** der Knoten existiert **nur** im
`ERROR`-Zustand; verschwindet, sobald der Zustand auflöst.

**Entscheidungsbaum (deckt jede BE-Antwort ab):**

- **(a) Grund vorhanden, Code (BE-2=Code):** lokalisierter, kuratierter Satz über eine `when(code)`-Abbildung
  (**Muster** wie `LifecycleErrorRow`; die konkreten Code→Key-Paare liefere ich, **sobald Backend2 die Code-Menge
  benennt** — ich erfinde keine Codes vorab). Unbekannter Code → `else`-Zweig = fail-closed-Text (b), **nie** der
  rohe Code als „Grund".
- **(a') Grund vorhanden, Freitext (BE-2=Freitext):** **wörtlich** gezeigt, **ohne Ausschmückung**, und
  **herkunfts­markiert** — z. B. präfixiert „Vom Prozess gemeldet: …" (neuer Key `agent_error_reason_raw_prefix`),
  in einem ruhigen Diagnose-Stil (monospace/zitiert). **Nicht lokalisieren** (es ist keine kuratierte Botschaft),
  **nicht kürzen** (Offenlegungssatz — darf umbrechen, `disclosure-vs-layout`).
- **(b) Kein Grund (BE-1=Nein oder Grund fehlt):** **„Fehler — Grund nicht gemeldet"** (neuer Key
  `agent_error_reason_unreported`). **Nie** leer, **nie** erfunden, **nie** ein anderer Zustand vorgetäuscht.

**Unverhandelbare Offenlegungs-Regeln:**
1. **Kein erfundener Grund.** Fehlt der Grund, sagt die UI das (b) — sie rät nicht aus Symptomen.
2. **Grund ≠ Garantie.** Bei BE-3=best-effort ist der Grund ein **Diagnose-Hinweis**, nicht die zugesicherte
   Ursache; die Wortwahl impliziert keine Gewissheit, die der Server nicht gibt.
3. **`ERROR` wird nie aufgelöst** (Bindeglied zu CYP-351 §3): kein `ERROR → STOPPED/RUNNING`, weil „ist ja nicht
   gelaufen". Der Grund erklärt den Zustand, er ersetzt ihn nicht.
4. **Herkunft ehrlich** (a'): rohe Prozessausgabe wird als solche markiert, nie als kuratierter Satz ausgegeben.

---

## 4. Keys (jetzt anlegbar) + was auf die Naht wartet

**Jetzt (fail-closed-Pfad, backend-unabhängig):**

| Real-Key | DE | EN |
|---|---|---|
| `agent_error_reason_unreported` | Fehler — Grund nicht gemeldet | Error — reason not reported |
| a11y `a11y_agent_error_reason` | Fehlergrund: %1$s | Error reason: %1$s |

**Wartet auf BE-2 (erst nach der Contract-Antwort):**

| Real-Key | DE | EN | Bedingung |
|---|---|---|---|
| `agent_error_reason_raw_prefix` | Vom Prozess gemeldet: %1$s | Reported by the process: %1$s | nur wenn BE-2 = Freitext (a') |
| `agent_error_reason_<code>` … | (je Code, wenn Backend2 die Menge benennt) | … | nur wenn BE-2 = Code (a) |

**Reuse (kein neuer Key/Tag):** `agent_status_error` (das Wort „Fehler"), die `error`-Farbrolle, das
`LifecycleErrorRow`-Präsentations- und `when(code)`-Muster. **Shared-Key-Drift** wie gehabt: ich entwerfe, der Dev
landet die Keys **mit** der Impl (nicht vorab isoliert mergen).

**testTag:** eigener Knoten `agent.<id>.errorReason` (analog `AgentViewTags.lifecycleError`), **präsent iff**
`state == ERROR`. Getrennt vom `agent.<id>.lifecycleError` (Aktionsfehler) — QA prüft die Trennung.

---

## 5. Medien-Unabhängigkeit (Compose + DOM)

Das Design ist medien-unabhängig. Compose: eine `Text`-Zeile unter dem Status (error-Ton), Freitext-Fall in einem
`monospace`/zitierten Stil. DOM: ein `<div>`/`<details>` am Status, per `aria-describedby` an den Statusknoten
gebunden; Freitext in `<code>`/`<pre>` mit `white-space: pre-wrap` (umbrechen, nicht `ellipsis`); a11y über
`a11y_agent_error_reason`. Fail-closed-Text und alle Offenlegungsregeln (§3) identisch.

---

## 6. Abnahme (fail-closed, diskriminierend) — für QA, wenn die Impl landet

Jeder Test benennt die falsche Implementierung, die er ablehnt:

1. **Kein Grund → „Grund nicht gemeldet".** Server liefert keinen Grund zum ERROR → die Zeile zeigt
   `agent_error_reason_unreported`. **Mutation:** leere Zeile / weggelassener Knoten bei `reason == null` ⇒ rot.
   **Mutation:** ein aus dem Kontext geratener Grund ⇒ rot.
2. **Code → lokalisierter Satz, nie roher Code.** (Wenn BE-2=Code.) Bekannter Code → sein Key; unbekannter →
   fail-closed-Text. **Mutation:** roher Code als „Grund" gezeigt ⇒ rot.
3. **Freitext → wörtlich + herkunfts­markiert.** (Wenn BE-2=Freitext.) Text unverändert, mit
   `agent_error_reason_raw_prefix`. **Mutation:** Freitext lokalisiert/umformuliert/gekürzt ⇒ rot.
4. **`ERROR` nie aufgelöst.** Ein Server-`ERROR` rendert als `ERROR` (+ Grund/Fail-closed), nie als STOPPED/RUNNING.
   **Mutation:** Mapping `ERROR → STOPPED` ⇒ rot. (Bindeglied CYP-351 §3.)
5. **Trennung von der Aktionszeile.** `agent.<id>.errorReason` (Zustand) ≠ `agent.<id>.lifecycleError` (Aktion);
   ein Aktions-Reject erscheint **nicht** als Zustands-Grund und umgekehrt. **Mutation:** beide auf denselben
   Knoten/dieselbe Quelle ⇒ rot.

**Test 1 ist der wichtige** — er prüft die **Ehrlichkeit** (kein erfundener/leerer Grund), nicht die Anzeige.

---

## 7. Self-Validation

- **An der Naht gemessen, nicht geraten:** der Zustand ist heute ein nacktes Enum ohne Grund-Feld
  (`AgentLifecycleApi.kt:18`) — deshalb ist BE-1 die erste Frage, nicht das Display.
- **Aktions-Fehler ≠ Zustands-Grund** sauber getrennt (`LifecycleErrorRow` ist ersteres) — sonst maskiert das eine
  das andere.
- **Fail-closed ist der Default** (b), bevor Backend2 antwortet — die UI ist schon jetzt ehrlich.
- **Ich erfinde keine Codes vorab** (a) — die Code→Key-Paare kommen erst, wenn Backend2 die Menge benennt.
- **Freitext wird herkunfts­markiert** (a'), nie als kuratierter Satz ausgegeben — Offenlegungs-Ehrlichkeit.
- **Nicht an CYP-396 gekoppelt** (der ist backend-unabhängig, GO). **Docs-only.**
