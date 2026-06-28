# testTag-Schema — Phone-Pager-Layout (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-54** (Epic CYP-3, S10) · Speist CYP-50 · Status: **Entwurf** · Stand: 2026-06-28
> Begleitend zu `docs/PHONE-PAGER.md`, `docs/design/phone-pager-tokens.json`, `phone-pager-keys.md`.
> **Schema (Test-Contract v0.5 §2):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**. Segmentwerte `[A-Za-z0-9-]+` (**keine Punkte**).
> **Vertrag Dev↔QA (CYP-7):** API, nicht still umbenennen. Vorschlag `:app:shared`-Objekt **`PhonePagerTags`** (Area `phonePager`).

Macht den Phone-Pager für Compose-UI-Tests + Maestro stabil adressierbar — insbesondere die Disclosure-Anker (Omission keine Phantom-Seite, Badge ≠ Auto-Sprung, ein-Fenster = kein Chrome).

---

## 0. Konventionen

- **Eine Area, Single-Instance:** `phonePager`.
- **Seiten-Selektor = `windowId`** (dieselbe id wie `window.<id>` aus `WindowTestTags`, eine Quelle). Fenster-ids sind punktfrei.
- Erscheint nur im **Compact-Modus** (width **oder** height Compact, PHONE-PAGER §1); im Canvas-Modus existiert **kein** `phonePager.*`-Knoten (QA prüft den Modus über An-/Abwesenheit von `phonePager.pager` vs. `window.host`).

---

## 1. Pager & Seiten — Area `phonePager`

| Element | testTag | Zweck |
|---|---|---|
| Pager (Container) | `phonePager.pager` | `HorizontalPager` mit Snap |
| Seite (je Fenster) | `phonePager.page.<windowId>` | eine Seite; hostet `window.<windowId>.content` |
| Kopfzeile | `phonePager.header` | Titel-Leiste der aktuellen Seite |
| Kopf-Titel | `phonePager.header.title` | `WindowState.title` der aktiven Seite |
| Empty-State | `phonePager.empty` | 0 Fenster |

## 2. Indikator & Navigation

| Element | testTag | Zweck |
|---|---|---|
| Indikator (Container) | `phonePager.indicator` | Dots **oder** Zähler |
| Dot (je Seite) | `phonePager.indicator.dot.<windowId>` | tappbarer Dot (≤ ~6 Seiten) |
| Aktiver Dot (Qualifier) | `phonePager.indicator.dot.<windowId>.active` | aktive Seite (Form/Größe, nicht nur Farbe) |
| Positions-Zähler | `phonePager.indicator.position` | „N / M" (> ~6 Seiten) |
| Vorherige Seite | `phonePager.prev` | Vor-Affordanz |
| Nächste Seite | `phonePager.next` | Zurück-Affordanz |

## 3. Aktivität (Deep-Link / Badge)

| Element | testTag | Zweck |
|---|---|---|
| Aktivitäts-Badge (je Seite) | `phonePager.page.<windowId>.badge` | „Aktivität auf dieser Seite" — passiv |

---

## 4. Test-relevante Disclosure-Anker (für QA/CYP-7)

- **Omission keine Phantom-Seite:** ohne Operator-Token existiert **keine** `phonePager.page.eventbrowse`/`…eventtail` und **kein** zugehöriger Dot; `phonePager.indicator.position` zählt nur echte Seiten.
- **Ein Fenster = kein Chrome:** bei genau 1 Seite ist `phonePager.indicator` **abwesend** (kein vorgegaukeltes Mehr).
- **Badge ≠ Auto-Sprung:** ein Hintergrund-Event erzeugt `phonePager.page.<id>.badge`, **ohne** dass der Pager die aktuelle Seite wechselt (Kontext bleibt). Sprung nur nach explizitem Tap auf Dot/Badge.
- **Aktiver Zustand nicht nur Farbe:** `…dot.<id>.active` ist über Form/Größe gesetzt (a11y-Selektor), nicht nur farblich.
- **Modus-Trennung:** im Canvas (beide ≥ Medium) existiert `window.host`, **kein** `phonePager.pager` — und umgekehrt.

---

## 5. Hand-off-Hinweis (Dev + QA)

- Tags gehören als `PhonePagerTags` in `:app:shared` (wie `WindowTestTags`/`CommTags`) — **mit Tester (CYP-7) teilen**, Änderungen über den PO.
- Seiten referenzieren die **bestehende** `window.<id>`-id (eine Quelle, kein zweites id-Schema).
- **Shared-Key/Tag-Drift:** mit CYP-50-Impl + Test-Modul zeitgleich nachziehen.
