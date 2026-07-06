# i18n-Keys — Restart-Hinweis am Home-Agentenfenster (CYP-239)

> Owner: UIUX-Designer · Story **CYP-239** · Stand: 2026-07-06 · Status: Vorschlag
> Konvention (verifiziert gg. `values/strings.xml` @ `e6f0882`): Underscore-Realkeys, **DE = Default** (`values/`),
> **EN** (`values-en/`), Parität Pflicht.

---

## 1. Neue Keys: **KEINE (0)** — Reuse-verbatim (PO: „keinen zweiten Hint-Chip")

Der Home-Restart-Hinweis nutzt **exakt** den bestehenden String, den beide Panels (Settings + Management) schon zeigen:

| Reused Key (@ `e6f0882`) | DE | EN |
|---|---|---|
| `agent_edit_effect_hint` | Gespeichert. Wirkt erst beim nächsten Start des Agenten – jetzt neu starten, damit die neue Konfiguration zieht. | Saved. Takes effect on the agent's next start — restart now so the new configuration applies. |

> **Warum verbatim:** die Copy nennt bereits die Aktion („jetzt neu starten") — die passt, weil der CYP-73-Restart-Button
> im selben Fensterkopf sitzt. **Ehrlichkeits-Anker (code-dokumentiert, `strings.xml` Z. 174): „‚Gespeichert' ≠ ‚Aktiv'"**
> — impliziert nie, die Config sei schon aktiv. **0 Divergenz** = ein Wording über Settings-Panel, Mgmt-Panel und Home.

---

## 2. Optional (Forward §10, nur auf PO-Wunsch, NICHT blockierend): Home-Phrasing ohne Recency-Vorspann

Falls „Gespeichert." auf einem länger stehenden **Home**-Banner (Minuten nach dem Save gesehen) zu „gerade eben" klingt,
ein Sibling **ohne** den Recency-Vorspann — gleiche Aussage, gleiche Ehrlichkeit:

| Key (Vorschlag) | DE | EN |
|---|---|---|
| `agent_restart_pending_hint` | Neustart nötig, damit die neue Konfiguration greift. | Restart needed for the new configuration to take effect. |

> **Default bleibt Reuse-verbatim (§1)** — Anti-Divergenz. Dieser Sibling **nur**, wenn der PO die Recency-Nuance ändern
> will; dann DE+EN paritätisch. Beide Varianten wahren „Gespeichert/Neustart ≠ Aktiv".

---

## Zähl-/Validierungs-Block (Selbst-Validierung)

- **Neue Keys Pflicht: 0.** Reuse `agent_edit_effect_hint` (existiert DE+EN @ `e6f0882`, verifiziert).
- **Neue Keys optional (Forward): 1** — `agent_restart_pending_hint` (DE+EN 1/1), nur auf PO-Wunsch.
- **Argument-Keys (`%…$s`): 0.**
- **0 Kollision:** der optionale Key `agent_restart_pending_hint` ist neu gg. `strings.xml`/`values-en` @ `e6f0882`
  (im Push `grep`-gegengeprüft, falls gezogen).
- **Kein Secret in Keys:** keine E-Mail/Token/Endpoints; neutrale Zustands-Copy.
- **Ehrlichkeit:** „Gespeichert/Neustart ≠ Aktiv" in beiden Varianten; nie „ist aktiv" vor dem Restart.
- **⚠ Shared-Key-Drift:** bei §1 (Reuse) **keiner** — der Key existiert schon. Nur falls der optionale Sibling gezogen
  wird, landet er in `:app:shared` → mit CYP-7/Impl timen.
