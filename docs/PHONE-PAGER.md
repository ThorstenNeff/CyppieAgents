# Phone-Pager-Layout — Single-Window-Pager statt Canvas (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-54** (Epic CYP-3, Slice S10) · Speist Impl **CYP-50** · Status: **Entwurf — wartet auf Dev-Gegenlesen** · Stand: 2026-06-28
> **Kanonischer Ort:** `KMPCyppieAgents` unter `docs/PHONE-PAGER.md`. Begleit-Artefakte: `docs/design/phone-pager-tokens.json`, `phone-pager-keys.md`, `phone-pager-tags.md`.
> **Reuse-First:** baut auf dem realen Fenster-Manager (CYP-10/16, `window/WindowManagerState.kt`), der Fenster-Montage (`AgentShell.kt`) und CYP-26 (Responsive-Tiling) auf — keine Neuerfindung. **Brand:** CyppieAgents (Anti-Hype).
> **Architektur-Vorgabe (Auftraggeber, 2026-06-28):** Breakpoint über **Compose Window Size Classes** (nicht `expect`/`actual`). Dieses Spec ist in **Size-Class-Buckets** formuliert, nicht in rohen dp.

Spezifiziert das Smartphone-Layout (Android + iOS): statt Multi-Fenster-Canvas wird **immer nur ein Fensterinhalt** gezeigt; die Inhalte liegen galerie-artig in einem **`HorizontalPager` mit Snap**. Keine Implementierungsvorgabe.

---

## 0. Bezugsrahmen (verifiziert im Code, 2026-06-28, develop `5dca05b`)

- **Fenster-Modell** `window/WindowManagerState.kt`: `WindowState(id, title, x, y, width, height)`; Liste = **z-Order** (letztes Element = fokussiert); `focusedId = windows.lastOrNull()?.id`; `tile()` legt das Canvas-Grid (`sqrt(count)`-Spalten).
- **Fenster-Montage** `AgentShell.kt`: pro Agent ein **`AgentWindow`** (CYP-6, `/ws/agent`), **`CommPanel`** (CYP-21), **`AclPanel`** (CYP-48, **immer präsent** — editierbar für Operator, sonst read-only-Teilansicht), **Event-Log Browse + Tail** (CYP-41/42, **operator-gated**: Fenster existieren nur, wenn `operatorToken != null` — Omission, kein „kein Zugriff"-Fenster).
- **testTags** `window/WindowTestTags.kt`: `window.<id>`, `window.<id>.titlebar/content` (instanz-scoped je Fenster-id).
- **CYP-26** `docs/design/WINDOW-RESPONSIVE.md`: M3-Window-Size-Class-Caps für die Canvas-Kachelung + Composer-Robustheit. **S10 grenzt sich davon ab (§7).**

---

## 1. Breakpoint = Compose Window Size Classes (Breite **und** Höhe)

Der Moduswechsel hängt **allein an den Compose Window Size Classes** des App-Fensters (nicht an physischer Bildschirmgröße, nicht an `expect`/`actual`).

> ### Kanonische Regel (Auftraggeber, 2026-06-28)
> **Phone-Pager**, sobald **eine** der beiden Dimensionen `Compact` ist:
> `pager  ⟺  widthSizeClass == Compact  ODER  heightSizeClass == Compact`
> **Canvas** nur, wenn **beide** ≥ `Medium`:
> `canvas ⟺  widthSizeClass ≥ Medium  UND  heightSizeClass ≥ Medium`

| Fall (Width × Height Size Class) | Beispiel | Layout-Modus |
|---|---|---|
| width **Compact** (egal welche Höhe) | Phone-Portrait | **Phone-Pager** |
| height **Compact** (egal welche Breite) | **Phone-Landscape** (breit, aber niedrig) | **Phone-Pager** |
| width ≥ Medium **UND** height ≥ Medium | Tablet, Desktop, großes Foldable | **Canvas** |

- **Maßgeblich ist der Bucket** (`Compact`/`Medium`/`Expanded` je Dimension), nicht rohe dp — Design ↔ Impl teilen dasselbe Primitive. M3-Referenz nur informativ: Width Compact < 600 dp; Height Compact < 480 dp.
- **Landscape-Phone explizit = Pager:** ein breites, aber niedriges Phone (height `Compact`) bekommt den **Pager**, nicht Canvas — eine kurze, gequetschte Canvas-Kachelung wäre schlechter als eine voll-höhe Pager-Seite. (Das löst die früher offene Orientierungs-Kante.)
- **Empfehlung für „beide ≥ Medium" (kleine Tablets):** **Canvas mit reduziertem Tiling, KEIN Pager.** Ab Medium ist Platz für ≥2 Fenster und Tablet-Nutzer erwarten Multi-Fenster. Die Spalten-Zahl im Canvas folgt der **Width**-Size-Class (CYP-26: Medium-Breite ≤ 2 Spalten, Expanded-Breite `sqrt(count)`).

---

## 2. Welche Fenster werden Pager-Seiten (Reihenfolge)

Die Seiten sind dieselben Fenster-Entitäten wie im Canvas, in **stabiler** Reihenfolge:

1. **Agent-Renderer** je Agent (Konfig-Reihenfolge: `po`, `frontend`, `backend`, …)
2. **Comm-Panel**
3. **ACL-Matrix** (immer präsent)
4. **Event-Log Browse** — *operator-gated*
5. **Event-Log Live-Tail** — *operator-gated*

- **Operator-Gating = Omission:** operator-gated Fenster werden Seiten **nur wenn sie existieren** (kein `operatorToken` → keine Event-Log-Seiten, **kein** „kein Zugriff"-Platzhalter). Der Seiten-Indikator (§4) zählt nur **tatsächliche** Seiten. Gleiches Prinzip wie CYP-41/42/CYP-48.

> ### ⚠ Architektur-Hinweis (Dev, wichtig): Seiten-Order ≠ z-Order
> Die Canvas-`windows`-Liste ist nach **z-Order** sortiert (Fokus wandert ans Ende). Würde der Pager seine Seiten daraus ableiten, würden die Seiten **bei jedem Fokuswechsel umsortieren** — desorientierend. Der Pager braucht eine **stabile Seiten-Order** (Registrierungs-/Konfig-Reihenfolge oben), **entkoppelt** von der z-Order. Vorschlag: eine deklarierte `windowOrder`-Liste (stabil) als Seiten-Schlüssel; `focusedId` wählt nur die **aktuelle Seite**, ohne die Order zu ändern.

---

## 3. Modus-Übergang & Zustands-Erhalt

- **Geteilter Anker `focusedId`:** Im Canvas = oberstes Fenster; im Pager = **aktuelle Seite**. Eine Quelle für „welches Fenster ist aktiv".
- **Beim Bucket-Wechsel** (Rotation, Split-Screen, Fold/Unfold, Fenster-Resize am Desktop) bleibt **dasselbe aktive Fenster** erhalten: Canvas-Fokus → Pager-Seite und zurück über `focusedId`. Der Nutzer verliert seinen Kontext nicht.
- Kein Spezial-Hysterese nötig (Bucket-Wechsel ist diskret); der bestehende Reflow (`updateHostSize`, CYP-16 F6) bleibt für den Canvas-Zweig zuständig.

---

## 4. Seiten-Indikator + Navigation

- **`HorizontalPager` mit Snap:** eine Seite ist immer **voll** sichtbar, Snap auf die nächste; **kein** Peek-Teaser im MVP (eine Sache zur Zeit = ehrliche „ein Fenster"-Aussage).
- **Aktueller Titel prominent:** da nur ein Fenster sichtbar ist, trägt eine schlanke Kopfzeile den **`WindowState.title`** der aktuellen Seite (+ ggf. Identitäts-Punkt `colorSlot` aus CYP-14 bei Agent-Seiten — Identität, nie Berechtigung).
- **Indikator:**
  - **≤ ~6 Seiten:** Dot-Indikator. Aktiver Dot = **gefüllt + größer** (Form), nicht nur Farbe (WCAG 1.4.1).
  - **> ~6 Seiten:** kompakter **„%1$s / %2$s"**-Zähler statt gequetschter Dots.
- **Navigation:** primär **Swipe-Snap**; zusätzlich **Vor/Zurück**-Affordanzen und **tappbare Dots** (Entdeckbarkeit + a11y). RTL: Pager-Richtung **und** Indikator-Order spiegeln automatisch mit der Leserichtung.
- **a11y:** Pager ist per Tastatur/Screenreader navigierbar; jede Seite annonciert **Titel + Position** („Seite 2 von 5: Frontend"). Vor/Zurück + Dots haben contentDescriptions.

---

## 5. Fokus / Deep-Link → Pager-Seite

- **Explizite „Fenster X nach vorn"-Aktion** (Nutzer-getrieben) → `pager.animateScrollToPage(indexOf(id))` über die **stabile** Order (§2). Auf Phone ist „Fokus" = „Seite anscrollen".
- **Deep-Link / Benachrichtigung** (z. B. neue Comm-Nachricht, Agent braucht Eingabe) → mappt Fenster-id → Seitenindex.
  - **Disclosure-/UX-Ehrlichkeit:** ein **Hintergrund-Event reißt den Nutzer nicht** aus der aktuellen Seite. Stattdessen **passiver Badge** am Ziel-Seiten-Indikator (§6) + Sprung **nur auf explizites Tippen**. Auto-Sprung **nur** bei einer expliziten Nutzer-„nach vorn"-Aktion.
- **Operator-gated & abwesend:** Deep-Link auf eine nicht existierende Seite (z. B. Event-Log ohne Operator-Token) ist ein **No-op** mit ehrlichem Verschlucken — **nie** auf eine Phantom-Seite navigieren.

---

## 6. Zustände & Disclosure-Honesty (verbindlich)

| Zustand | Darstellung | Disclosure-Regel |
|---|---|---|
| **0 Fenster** | Empty-State | kein leeres Pager-Chrome |
| **1 Fenster** | eine statische Seite, **kein** Indikator/Swipe-Chrome | nicht mehr Seiten suggerieren als existieren |
| **N Fenster** | Pager + Indikator (N echte Seiten) | Indikator zählt **nur tatsächliche** Seiten |
| **Operator-gated abwesend** | keine Event-Log-Seite | Omission, **kein** „kein Zugriff"-Platzhalter (CYP-41/42) |
| **Aktivität auf anderer Seite** | **Badge** am Ziel-Dot (z. B. „%1$s neu") | Badge = „es gibt Aktivität", **nicht** „erledigt/zugestellt"; kein Kontext-Klau |
| **Inhalt lädt/offline** | im jeweiligen Panel | trägt das Panel selbst (Comm/ACL/Event-Log-Banner) — der Pager überschreibt keine Ehrlichkeit |

**Kern-Disclosure:**
1. **„Ein Fenster zur Zeit" ist wörtlich:** voll-snap, kein Peek; der Indikator nennt die **wahre** Seitenzahl.
2. **Operator-Gating bleibt Omission** — keine Phantom-/Dead-Seiten.
3. **Identitätsfarbe ≠ Berechtigung** (CYP-14); Indikator-Aktiv-Zustand nie nur über Farbe.
4. **Events stehlen den Kontext nicht** — Badge + expliziter Sprung statt Auto-Yank; ein Badge ist ein Hinweis, keine Zustell-/Erledigt-Garantie.

---

## 7. Abgrenzung zu CYP-26 (Doppelarbeit vermeiden) — **verbindlich**

S10 ist die **phone-spezifische** Layout-Antwort. Klare Teilung:

| Bereich | Zuständig |
|---|---|
| **Compact (Phone)** — wie sieht „schmal" aus | **S10 (dieses Spec):** **Pager**, eine Seite je Fenster. **Löst CYP-26 §3-Offenpunkt #2** („Stapeln-mit-Scroll" vs. „ein Fenster + Switcher") → Entscheid = **Pager**. **Ersetzt** damit CYP-26 §1.1 Compact „full-width gestapelt". |
| **Medium / Expanded (Tablet/Desktop)** — Canvas-Kachelung | **CYP-26 bleibt:** Spalten-Caps (Medium ≤2, Expanded `sqrt`). |
| **Komponenten-Robustheit** (Composer `maxLines=1`/Ellipsis/`COMPOSER_MIN_WIDTH`) | **CYP-26 bleibt, komplementär:** gilt **innerhalb** einer Pager-Seite genauso (eine Phone-Seite ist full-width, also unkritisch, aber die Regel bleibt). |

→ `WINDOW-RESPONSIVE.md` ist um einen Verweis ergänzt (Compact-Fall siehe S10). **Keine divergierenden Specs.**

---

## 8. Tokens & i18n & testTags

- **Tokens:** Reuse Identitäts-/Statusfarben (CYP-14/CYP-12); wenige Neue (aktiver/inaktiver Indikator-Dot — Form+Größe, nicht nur Farbe; Aktivitäts-Badge; Pager-Kopfzeile) in `docs/design/phone-pager-tokens.json`.
- **i18n:** `compose.resources`/Underscore — Keys in `docs/design/phone-pager-keys.md`. **Reuse** `comm_back` wo passend.
- **testTags:** Area `phonePager`, in `docs/design/phone-pager-tags.md` (Test-Contract v0.5 §2; Dev/QA-Vertrag CYP-7). Seiten-Knoten referenzieren die bestehende `window.<id>`-id (eine Quelle).

> **⚠ Shared-Key/Tag-Drift:** Keys + `PhonePagerTags` landen in `:app:shared` → CYP-50-Impl + Test-Modul (CYP-7) müssen re-syncen. **Lieferung mit der CYP-50-Umsetzung timen.**

---

## 9. Offene Punkte / Dev-Asks (über PO)

1. **Stabile `windowOrder`** (entkoppelt von z-Order, §2) — bestätigen, dass die Impl eine deklarierte Seiten-Order einführt (nicht die `windows`-z-Order-Liste als Seitenquelle nutzt).
2. **Medium = Canvas** (meine Empfehlung, §1) bestätigen — oder soll Medium auch Pager sein? (Empfehlung: Canvas.)
3. **Badge-Quelle:** woher kommt „Aktivität auf Seite X" (Comm-Unread, Agent-`WAITING_FOR_INPUT`, Event-Severity)? Pro Fenstertyp definieren — vorerst Comm-Unread + Agent-Bedarf; Event-Log-Badge optional.
4. **iOS-Swipe-Back-Konflikt:** der System-Edge-Swipe (zurück) vs. Pager-Swipe an der Start-Kante — beim Impl auf iOS prüfen (Gesten-Priorität an der Kante).
