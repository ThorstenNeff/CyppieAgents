# testTag-Schema — Per-Fenster-Badges (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-55** · Speist CYP-55-Impl · Status: **Entwurf** · Stand: 2026-06-28
> Begleitend zu `docs/WINDOW-BADGES.md`, `window-badges-tokens.json`, `window-badges-keys.md`.
> **Schema (Test-Contract v0.5 §2):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**. Segmentwerte `[A-Za-z0-9-]+` (**keine Punkte**).
> **Vertrag Dev↔QA (CYP-7):** API, nicht still umbenennen. Vorschlag `:app:shared`-Objekt **`WindowBadgeTags`** (Area `windowBadge`). Gate: Reviewer + Desktop-`runComposeUiTest`.

Macht den Badge stabil adressierbar — **besonders die Fail-closed-Abwesenheits-Anker** (kein Badge ohne Quelle, kein Leak, kein Phantom).

---

## 0. Konventionen

- **Area `windowBadge`**, **scoped je `windowId`** (dieselbe id wie `window.<id>` / `WindowTestTags`, **eine Quelle**). Fenster-ids sind punktfrei.
- **Ein Badge je Fenster** (der relevanteste) → ein Container je `windowId`.
- **Modus-übergreifend:** im **Canvas** sitzt der Badge in der `FloatingWindow`-Titelleiste; im **Pager** wird der **bestehende** `phonePager.page.<id>.badge` (CYP-54) genutzt — CYP-55 legt **kein** zweites Pager-Badge-Tag an.

---

## 1. Badge — Area `windowBadge` (Canvas-Titelleiste)

| Element | testTag | Zweck |
|---|---|---|
| Badge-Container (je Fenster) | `windowBadge.<id>` | vorhanden ⇔ es gibt einen ehrlichen/erlaubten Hinweis |
| Count-Variante | `windowBadge.<id>.count` | Comm-Unread (B1) |
| Severity-Variante | `windowBadge.<id>.severity` | Event-Log max Severity (C1/C2) |
| Attention-Variante | `windowBadge.<id>.attention` | Agent ERROR (A1) / Stall (A2) |

> Genau **eine** Varianten-Knoten je `windowBadge.<id>` ist gleichzeitig vorhanden (der relevanteste Typ je Fenster).

## 2. Pager-Reuse (kein neues Tag)

| Element | testTag | Quelle |
|---|---|---|
| Pager-Aktivitäts-Badge (je Seite) | `phonePager.page.<id>.badge` | **CYP-54** `phone-pager-tags.md` — CYP-55 füllt ihn |

---

## 3. Test-relevante Disclosure-/Fail-closed-Anker (für QA/CYP-7)

- **Fail-closed = Abwesenheit:** ohne ehrliche/erlaubte Quelle existiert **kein** `windowBadge.<id>` (kein „0"-Count, kein leerer Container). QA prüft die **Abwesenheit** explizit.
- **Kein Leak:** der immer-sichtbare Titelleisten-Badge (`windowBadge.<id>.severity`) erscheint **nur**, wenn (C1) das Fenster selbst gated ist **oder** (C2) ein content-freies Aggregat vorliegt — **nie** trägt der Knoten Event-Body/Meta-Text. QA prüft: Severity-Badge-Text ist **nur** Severity-Enum/Icon, kein Inhalt.
- **Operator-Omission:** ohne Operator-Token existiert **kein** Event-Log-Fenster → **kein** `windowBadge.eventbrowse.*`/`…eventtail.*` und kein Pager-Dot dafür.
- **Reset bei Fokus (B1):** nach Fokus/Öffnen des Comm-Fensters (Canvas) bzw. aktiver Comm-Seite (Pager) **verschwindet** `windowBadge.<commId>.count` bzw. der Pager-Badge.
- **Kein Auto-Sprung:** ein Hintergrund-Ereignis erzeugt `windowBadge.<id>.*` bzw. `phonePager.page.<id>.badge`, **ohne** dass Fokus/aktive Seite wechselt (S10 §5).
- **Kein Fake-WAITING:** es gibt **keinen** Badge-Knoten, der `WAITING_FOR_INPUT` aus geratenem State darstellt; Attention nur aus ERROR (A1) oder einem realen Stall-Signal (A2).
- **Farbe nicht alleiniger Träger:** die Varianten-Knoten (`.count`/`.severity`/`.attention`) sind über **Form/Icon/Text** unterscheidbar (a11y-Selektor), nicht nur farblich.

---

## 4. Hand-off-Hinweis (Dev + QA)

- Tags gehören als `WindowBadgeTags` in `:app:shared` (wie `WindowTestTags`/`CommTags`/`PhonePagerTags`) — **mit Tester (CYP-7) teilen**, Änderungen über den PO.
- Badge-Knoten referenzieren die **bestehende** `window.<id>`-id (eine Quelle, kein zweites id-Schema); Pager nutzt das bestehende `phonePager.page.<id>.badge`.
- **Shared-Key/Tag-Drift:** mit CYP-55-Impl + Test-Modul zeitgleich nachziehen.
