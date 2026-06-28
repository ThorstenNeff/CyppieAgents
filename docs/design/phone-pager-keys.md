# i18n-Keys — Phone-Pager-Layout (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-54** · Status: **Entwurf** · Stand: 2026-06-28
> Begleitend zu `docs/PHONE-PAGER.md`. `compose.resources` → **Underscore-Real-Keys** (gepunktete Form = menschlicher Namespace). Platzhalter positional (`%1$s`).
> **Reuse (nicht dupliziert):** Fenster-**Titel** kommen aus `WindowState.title` (kein eigener Key); Identitäts-/Rollen-Labels aus CYP-14. Hier nur Pager-Chrome.

## 1. Indikator / Navigation

| Namespace (human) | Real-Key | DE (Quelle) | EN |
|---|---|---|---|
| `pager.page.position` | `pager_page_position` | Seite %1$s von %2$s | Page %1$s of %2$s |
| `pager.prev` | `pager_prev` | Vorherige Seite | Previous page |
| `pager.next` | `pager_next` | Nächste Seite | Next page |
| `pager.empty` | `pager_empty` | Keine Fenster | No windows |

## 2. Aktivität / Badge

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `pager.activity.badge` | `pager_activity_badge` | %1$s neu | %1$s new |

> `pager_activity_badge` = **Aufmerksamkeits-Hinweis** („es gibt Neues auf dieser Seite"), **nicht** „zugestellt/erledigt". Löst **keinen** Auto-Sprung aus (§5 Disclosure).

## 3. Accessibility-Keys (Farbe nie alleiniger Träger)

| Namespace (human) | Real-Key | DE | EN |
|---|---|---|---|
| `a11y.pager.page` | `a11y_pager_page` | Seite %1$s von %2$s: %3$s | Page %1$s of %2$s: %3$s |
| `a11y.pager.dot` | `a11y_pager_dot` | Zu Seite %1$s: %2$s | Go to page %1$s: %2$s |
| `a11y.pager.activity` | `a11y_pager_activity` | Aktivität auf Seite %1$s | Activity on page %1$s |

> `%3$s`/`%2$s` = der `WindowState.title` der Zielseite — der Screenreader nennt **Position + Fenstername** („Seite 2 von 5: Frontend"), nicht nur „Seite 2".

### Disclosure-kritische Wortwahl
- `pager_page_position` / `a11y_pager_page` nennen **immer die wahre Seitenzahl** — operator-gated abwesende Fenster werden **nicht** mitgezählt (Omission, keine Phantom-Seite).
- `pager_activity_badge` ist ein **Hinweis**, keine Zustell-/Erledigt-Aussage; Hintergrund-Events stehlen die aktuelle Seite nicht.

> **⚠ Shared-Key-Drift:** landen in `:app:shared`-Resources → konsumierendes Modul (CYP-50-Impl + Test-Modul CYP-7) muss re-syncen. **Lieferung mit der CYP-50-Umsetzung timen.**
