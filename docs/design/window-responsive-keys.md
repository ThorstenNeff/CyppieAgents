# i18n-Keys — Responsive Window-Manager (Desktop) (v0.2)

> Owner: UIUX-Designer · Ticket: **CYP-26** · Status: **Entwurf** · Stand: 2026-06-28
> Begleitend zu `docs/design/WINDOW-RESPONSIVE.md`. `compose.resources` → **Underscore-Real-Keys** (gepunktete Form = menschlicher Namespace). Platzhalter positional (`%1$s`).
> **Reuse-First:** Der Großteil von CYP-26 ist **verhaltensbasiert** (Layout/Clamp/Snap) und braucht **keine** neuen Strings. Fenster-**Titel** kommen aus `WindowState.title` (kein Key). Hier nur die wenigen Affordanzen.

## 1. Fenster einpassen (Pflicht — die einzige nutzer-getriggerte Re-Tile-Aktion)

| Namespace (human) | Real-Key | DE (Quelle) | EN |
|---|---|---|---|
| `window.fit.action` | `window_fit_action` | Fenster einpassen | Fit windows |

> `window_fit_action` löst ein **einmaliges** Re-Tile aus (heilt off-host/überlappte Zustände auf Nutzerwunsch). **Kein** automatisches Re-Tile bei Resize — die Hand-Anordnung des Nutzers bleibt sonst unangetastet (`WINDOW-RESPONSIVE.md` §2.3).

## 2. Snap — ⏸ BACKLOG (entfällt für CYP-26)

> **PO-Entscheid 2026-06-28: Snap → Backlog.** `window_snap_toggle` (DE „Einrasten" / EN „Snap to edges") wird in CYP-26 **nicht** angelegt — nur Referenz für ein späteres Snap-Ticket. **Für CYP-26 sind nur §1 + §3 zu verdrahten.**

## 3. Accessibility-Keys (Farbe nie alleiniger Träger)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `a11y.window.fit` | `a11y_window_fit` | Alle Fenster neu einpassen | Re-fit all windows |

> Fenster-/Titelleisten-a11y (`contentDescription` „Agentenfenster …", „Titelleiste …, mit Pfeiltasten verschieben", „Größe ändern, …") ist **bereits** in `FloatingWindow` umgesetzt (verifiziert develop `ebdf442`) — **nicht** duplizieren. CYP-26 ergänzt nur die Fit-Aktion.

---

### Disclosure-kritische Wortwahl
- `window_fit_action` ist eine **explizite** Aktion — kein „Auto-Layout". Wortlaut „einpassen", nicht „aufräumen/optimieren" (verspricht keine Magie).
- Kein String suggeriert ein erzwungenes Tiling; der Free-Floating-Charakter bleibt sprachlich erhalten.

> **⚠ Shared-Key-Drift:** Keys landen in `:app:shared`-Resources → konsumierendes Modul (CYP-26-Impl + Test-Modul CYP-7) muss re-syncen. **Lieferung mit der Impl timen.** DE+EN-Parität ist Pflicht.
