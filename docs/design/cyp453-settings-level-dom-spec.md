# CYP-453 (P2-f) — Settings-Level im DOM: der Rahmen um die Projekt-Config und die Trennlinie der zwei Gate-Klassen

> Owner: UIUX-Designer · Ticket **CYP-453** (Epic **CYP-430** Voller-Ersatz-Cutover, P2-f) · Stand 2026-07-11
> Basis `origin/develop` `69a14a3a` · `09-UI-Funktionskatalog` §1 (Repo-Config) + §9 (API-Key) · Docs-only → **Dev5-Referenz**.
> **Port der Compose-Quelle, kein Neuentwurf.** **0 neue Keys/Tags** (alle 20 referenzierten Keys gegen `strings.xml` verifiziert). **Nichts gebaut.**
>
> **Quelle:** `settings/SettingsPanel.kt` (`RepoSection` + `ApiKeySection`) · `SettingsTags.kt` · `SettingsViewModel.kt` ·
> `ui/ThemeModeToggle.kt` · `ui/ComposerHistorySizeStepper.kt` · `ui/ThemePreferences.kt` · `docs/design/project-settings-keys.md` / `-tags.md`.
> **Schwester-Slice:** **CYP-433 (P2-e)** spezifiziert die **API-Key-Section-Interna** (Leak-Modell), ist in Security-Review.
> P2-f **rahmt** diese Section, **dupliziert sie nicht** — siehe §4.

---

## 0. Die tragende Achse zuerst: ZWEI Gate-Klassen, die nie vermischt werden dürfen

Ein „Settings"-Fenster verführt dazu, alles Einstellbare in **einen** operator-geschützten Topf zu werfen. Genau das
wäre hier eine **Regression**. Die Fläche trägt zwei fundamental verschiedene Datenklassen:

| | **Projekt-Config** (operator-gated) | **Persönliche Präferenzen** (ungated) |
|---|---|---|
| Besitzer | das **Projekt/Team** (server-autoritativ) | der **einzelne Nutzer** (client-local) |
| Beispiele | Repo-URL/Branch (§3), API-Key (§4) | Theme-Modus, Composer-History-Größe (§6) |
| Gate | **present-but-disabled** ohne Operator-Token | **kein Gate** — jeder Nutzer ändert seine eigenen |
| Quelle | `SettingsViewModel` (`editable` = Operator) | `ThemePreferences` (per-user, ungegatet) |
| Persistenz | server-seitig, pro Team (CYP-84/85) | client-local, durable per Platform-KV |

**Die Regel:** *Das Gate ist eine Eigenschaft des **Datenbesitzers** (Projekt vs. Person), nicht des Fensters.* Ein
gemeinsames Fenster darf die zwei Klassen zusammen **anzeigen**, aber niemals zusammen **gaten**.

**Beleg aus der Compose-Quelle (kein Neuentwurf, sondern eine schon getroffene Entscheidung):** Theme + Composer-History
liegen in Compose **bewusst NICHT** im operator-gegateten `SettingsPanel`, sondern in der immer sichtbaren App-Bar
(`ProjectSwitcherBar`-Trailing-Slot):
- `ThemePreferences.kt`: „*the personal, **ungated** app preferences … not operator/project config … hence its home
  here, beside the theme mode, rather than in the operator `SettingsPanel`*".
- `ComposerHistorySizeStepper.kt` (CYP-387 §3): „*not operator-gated, not project-scoped*".
- CYP-387-Scoping-Korrektur wörtlich: „**SettingsPanel ist operator-gegatet → falsches Gate.**"

Würde man die persönlichen Präferenzen hinter den Operator-Gate schieben, könnte ein **Member** (Nicht-Operator) sein
**eigenes Theme** oder seine **eigene History-Größe** nicht mehr ändern — Daten, die ihm gehören. Das ist der
Ehrlichkeits-/Korrektheits-Fehler, den diese Spec verhindert. **Diese Trennung ist der Kern von P2-f.**

---

## 1. Umfang: was P2-f besitzt, rahmt, dokumentiert

P2-f ist die **Ebene** (das Fenster + der Panel-Rahmen), nicht jeder einzelne Regler darin.

- **BESITZT (voll spezifiziert hier):**
  1. das **Settings-Level** — Fenster `settings`, Panel-Root, Scroll, Titel (§2);
  2. die **Repo-Section** (voller Port von `RepoSection`, operator-gated) (§3);
  3. das **gemeinsame Operator-Gate-Muster** der Projekt-Config (§3/§4, present-but-disabled);
  4. das **gemeinsame Deferred-Effect-Disclosure-Muster** („gespeichert ≠ aktiv", amber) — **der „maßgebliche Ort"** für
     den Effect-Hint, den P2-a/P2-e nur *referenzieren* (§5).
- **RAHMT (nicht dupliziert → CYP-433/P2-e, Security-Review):** die **API-Key-Section**. P2-f rendert nur Heading +
  Platzierung im Panel + teilt Gate (§4) und Effect-Hint-Muster (§5). Das **Leak-Modell** (Klartext nie im DOM,
  write-only-Input, Reveal, Masking) ist **CYP-433** und wird hier **nicht** neu geschrieben (§4).
- **DOKUMENTIERT / VERLINKT (kein Re-Home ohne PO-Entscheid):** die **persönlichen Präferenzen** (Theme,
  Composer-History). Default = sie bleiben in der App-Bar (Compose-Parität, richtiges Gate); Placement als
  Scope-Frage an den PO (§6).

---

## 2. Anatomie des Levels (Mirror `SettingsPanel`) — Area `settings`

```
window.settings                              (Fenster im Manager; id = "settings")
└─ window.settings.content                   (Fensterinhalt; Reuse WindowTestTags.content)
   └─ settings.panel                         (scroll-Container, verticalScroll → nativer overflow-y)
      ├─ settings.section.repo               ← §3  PROJEKT-CONFIG (operator-gated)
      │    «Repository» (heading)
      │    settings.repo.status              („Kein Repository konfiguriert – Agenten können nicht starten")
      │    [ settings.repo.url.input ]
      │    [ settings.repo.branch.input ]
      │    settings.repo.gateHint            (Nicht-Operator)
      │    [ settings.repo.save ]
      │    settings.repo.error
      │    settings.repo.effectHint          (AMBER, §5)
      └─ settings.section.apiKey             ← §4  gerahmt → CYP-433 (Leak-Modell dort)
           «API-Schlüssel» (heading)
           …Interna = CYP-433/P2-e (masked/input/reveal/save/error) …
           settings.apiKey.effectHint        (AMBER, §5 — Instanz desselben Musters)
```

Fenster-Titel = `settings_title` „Projekt-Einstellungen / Project settings" — der Titel selbst sagt schon **Projekt**,
was die Grenze zu den persönlichen Präferenzen (§6) zusätzlich markiert. Der Panel-Root ist `settings.panel` mit
nativem `overflow-y` (der skiko-`verticalScroll`-Seam entfällt im DOM — vgl. [[migration-ports-tokens-not-optics]]).

---

## 3. Repo-Section (voller Port, operator-gated) — Mirror `RepoSection`

Genuiner P2-f-Inhalt (kein eigener Slice). Die Reihenfolge und Töne 1:1 aus `RepoSection`:

- **Status-Zeile `settings.repo.status`** — nur wenn **nicht** konfiguriert: `settings_repo_status_unset` „Kein
  Repository konfiguriert – Agenten können nicht starten." Ton = **INFO/WARN**, **nicht** Error-Rot: es ist ein
  ehrlicher Zustand, kein Fehler. **Ehrlichkeit:** der Satz nennt die **Folge** (Agenten können nicht starten), nicht
  nur „leer" — er darf nie stumm weggelassen werden.
- **`settings.repo.url.input` / `settings.repo.branch.input`** — `enabled = editable` (Operator). a11y
  `a11y_settings_repo_url` / `_branch`. Labels `settings_repo_url_label` / `_branch_label`.
- **Gate-Hint `settings.repo.gateHint`** — nur wenn nicht editierbar: `workspace_operator_only` /
  `settings_operator_required` „Nur mit Operator-Token änderbar". **Sichtbar** (present-but-disabled, §0), nie ein
  stumm totes Feld (CYP-317 „no fake control").
- **`settings.repo.save`** — `enabled = canSaveRepo` (Operator ∧ gültig/dirty).
- **`settings.repo.error`** — `settings_repo_url_invalid` „Ungültige Repository-URL" (role=alert), nie generisch
  verschluckt.
- **`settings.repo.effectHint` (AMBER)** — `settings_repo_effect_hint` „Änderung wirkt auf neu angelegte Worktrees /
  beim nächsten Hochfahren – bestehende Worktrees bleiben unverändert." = **Instanz** des §5-Musters.

---

## 4. API-Key-Section — **nur gerahmt** (→ CYP-433/P2-e)

**Wichtig:** P2-f **dupliziert die API-Key-Interna nicht.** CYP-433 (in Security-Review) ist die **einzige Quelle** für:
- das Leak-Modell (Klartext **nie** dauerhaft im DOM; hinterlegter Schlüssel nur server-`***last4`; Feld write-only,
  `type=password`, nach Save geleert; nie geloggt, nie im Fehler),
- `settings.apiKey.masked` / `.input` / `.reveal` / `.save` / `.error`,
- der Reveal-Kontrast zu §0: die API-Key-Section ist **present-but-disabled** (nicht Omission), **weil sie nichts
  leakt** — die Leak-Grenze ist der Klartext-**Wert**, nicht der Screen (im Gegensatz zu CYP-432-Event-Log, das am
  Mount omittet, weil es Bodies trägt).

**Was P2-f (die Ebene) zur Section beiträgt — und nur das:**
1. **Heading + Platzierung** — `settings.section.apiKey` als zweite Section unter der Repo-Section im selben Panel.
2. **Gemeinsames Gate** — dieselbe `editable`-Logik wie Repo (§0/§3): Operator ⇒ editierbar, sonst present-but-disabled
   + Gate-Hint.
3. **Effect-Hint als Instanz des §5-Musters** — `settings.apiKey.effectHint` / `settings_apikey_effect_hint` ist **das**
   Beispiel, für das P2-f der „maßgebliche Ort" ist (§5).

> **Nahtstellen-Notiz an Dev5:** P2-e und P2-f landen dieselbe Fläche. Wenn CYP-433 aus der Security-Review kommt, ist
> die api-key Section-Komponente **die** Implementierung; P2-f montiert sie in `settings.panel` und liefert Repo-Section
> + Frame drumherum. **Keine zweite API-Key-Implementierung.**

---

## 5. Das gemeinsame Deferred-Effect-Disclosure-Muster — der „maßgebliche Ort"

Der PO-Auftrag benennt P2-f als **den maßgeblichen Ort für den API-Key-Effect-Hint** (`settings_apikey_effect_hint`, den
P2-a/P2-e nur *referenzieren*). Das löst sich sauber, wenn man erkennt: **beide** Effect-Hints (Repo §3, API-Key §4)
sind **eine** Disclosure-Regel, hier definiert:

**Regel „gespeichert ≠ aktiv":** Ein gespeicherter Wert der Projekt-Config wirkt **nicht sofort**. Die UI muss das
**ehrlich offenlegen** — sonst liest sich „Speichern OK" wie „ist jetzt live", was falsch ist.

1. **Ton = AMBER**, niemals Erfolgs-Grün. Im DOM realisiert über die **WARN-Rolle** `--md-sys-color-warn-container` /
   `--md-sys-color-on-warn-container` (die einzige Amber-Rolle der Token-Ebene; **eine Success-/Grün-Rolle existiert gar
   nicht** — bewusst). Dieselbe „amber-nicht-grün / WARN≠tertiary"-Lehre wie W6/ACL/Event-Log ([[disclosure-vs-layout]]).
   `role="status"`.
2. **Zeigt auf den bestehenden Restart, rendert KEINEN eigenen.** Der API-Key-Hint sagt „…jetzt neu starten, damit der
   neue Schlüssel zieht" und meint den **CYP-73/P2-a per-Agent-Restart** (`agent.<id>.restartBtn`, Lifecycle-Header).
   **Genau deshalb referenzieren P2-a und P2-e den Hint nur:** die **Aktivierungsquelle** ist der Lifecycle-Restart,
   nicht der Settings-Screen. Ein eigener Restart-Button hier wäre die **falsche Aktivierungsquelle** (Abnahme §9.4).
3. **Zwei Instanzen, konsistent:** Repo → „neu angelegte Worktrees / nächstes Hochfahren – bestehende unverändert";
   API-Key → „nächster Start des Agenten – jetzt neu starten". Gleicher Ton, gleiche Struktur, gleiche
   Nie-grün-/Nie-ellipsis-Regel. Divergieren die beiden (Ton oder Wortlaut-Muster) ⇒ Abnahme §9.7 rot.
4. **Text + Ton + a11y, Farbe nie allein** (WCAG 1.4.1). **Kein `text-overflow: ellipsis`** auf dem Hinweis — ein
   Offenlegungssatz darf umbrechen, nie gekürzt werden ([[disclosure-vs-layout]], §9.8).

---

## 6. Persönliche Präferenzen (Theme, Composer-History) — ungated, Compose-Parität = App-Bar

Aus §0: diese gehören dem **Nutzer**, nicht dem Projekt, und dürfen **nie** hinter den Operator-Gate.

- **Theme-Modus** (`ThemeModeToggle`, CYP-268 R3): System / Light / Dark, client-local. Der aktive Modus ist mit einem
  **Formmarker** ausgezeichnet (◐ System / ○ Light / ● Dark), **nie Farbe allein** (WCAG 1.4.1). Bindet an die
  CYP-423-Token-Ebene (`[data-theme]` schaltet die `--md-sys-color-*`-Werte). Keys `theme_mode_system/light/dark`,
  `theme_menu_label`, a11y `a11y_theme_mode/_menu`.
- **Composer-History-Größe N** (`ComposerHistorySizeStepper`, CYP-387): **eine** globale, persönliche Zahl,
  Bereich 0..200, **0 = aus** (ehrlich screenreader-angesagt, kein totes Control). Keys
  `composer_history_size_label/_help`.

**Default-Placement = App-Bar (Compose-Parität), NICHT das Settings-Fenster.** In Compose reiten beide auf dem
immer-sichtbaren `ProjectSwitcherBar`-Trailing-Slot; die DOM-Parität montiert sie an derselben ungegateten,
projekt-unabhängigen Stelle. P2-f **dokumentiert** das und stellt sicher, dass sie **nicht** in den operator-gegateten
Topf rutschen.

> **Scope-Frage an den PO (nicht selbst gesetzt):** sollen Theme + History **zusätzlich** im Settings-Fenster
> auftauchen (manche Nutzer suchen „Einstellungen" dort)? **Wenn ja**, dann **nur** als **eigene, sichtbar getrennte,
> ungegatete „Präferenzen"-Zone** — eigener Region-Header, eigenes a11y-`role="group"`, **niemals** den Operator-Gate der
> Projekt-Config teilend (§0). Das kostet **einen** neuen Heading-Key (z. B. `settings_prefs_section`) — der **einzige**
> denkbare neue Key dieser Spec, und nur in diesem Fall. **Default = App-Bar, 0 neue Keys.**

---

## 7. Keys & Tags — alles bestehend (0 neu)

**Keys (Reuse, gg. `strings.xml` develop `69a14a3a` verifiziert — 20/20 vorhanden):**
`settings_title`, `settings_save`, `settings_operator_required`, `workspace_operator_only`,
`settings_repo_section/_url_label/_branch_label/_status_unset/_effect_hint/_url_invalid`,
`a11y_settings_repo_url/_branch`,
`settings_apikey_section/_effect_hint` (übrige API-Key-Keys → CYP-433),
`theme_mode_system/_light/_dark`, `theme_menu_label`, `a11y_theme_mode/_menu`,
`composer_history_size_label/_help`.

**Tags (Reuse, gg. `SettingsTags.kt` verifiziert):** `settings.panel`, `settings.section.repo`, `settings.section.apiKey`,
`settings.repo.url.input/.branch.input/.save/.status/.gateHint/.effectHint/.error`,
`settings.apiKey.*` (→ CYP-433), `themeToggle.menu/.item.*`, `composerHistory.stepper/.dec/.value/.inc`.
**Fenster-Reuse:** `window.settings` / `window.settings.content` / `window.settings.titlebar` (`WindowTestTags`).
Im DOM als `data-testid`, punktfreies Schema unverändert (Test-Contract v0.5, QA CYP-7).

**Kein neuer Key aus dem Medienwechsel.** Einziger möglicher neuer Key = `settings_prefs_section` — **nur** falls der PO
die persönlichen Präferenzen ins Fenster holt (§6). Taucht sonst ein DOM-Element auf, das Compose nicht hat, liefere ich
den Key impl-nah (Landung mit der Impl, [[shared-key-landing]]).

---

## 8. Ehrlichkeits-Invarianten (Basis der Abnahme)

1. **Gate = Datenbesitzer, nicht Fenster.** Projekt-Config operator-gated present-but-disabled; persönliche Präferenzen
   nie gegatet (§0).
2. **Effect-Hints AMBER, nie grün; „gespeichert ≠ aktiv"** (§5). Kein Success-Ton, keine „aktiv"-Behauptung.
3. **Kein eigener Restart-Control;** der Hint zeigt auf den Lifecycle-Restart (P2-a) (§5.2).
4. **Repo-unset ehrlich** — nennt die Folge („Agenten können nicht starten"), nie stumm/„OK" (§3).
5. **API-Key nur gerahmt**, Leak-Modell = CYP-433 (eine Quelle), nicht hier neu erfunden (§4).
6. **Farbe nie alleiniger Träger** (WCAG 1.4.1); Theme-Marker ist Form (◐/○/●), Töne sind Text+Ton+a11y.
7. **Kein `ellipsis`** auf Offenlegungs-/Fehler-/Gate-Sätzen (sie brechen um), Aktionslabels dürfen kürzen
   ([[migration-ports-tokens-not-optics]]).
8. **present-but-disabled, kein Fake** (CYP-317): gegatete Eingaben `disabled` + sichtbarer Gate-Hint, nie ein aktives
   Feld, das Editierbarkeit vorspiegelt.

---

## 9. Abnahme-Zähne (diskriminierend — je mit der falschen Impl, die er ablehnt)

1. **Persönliche Präferenzen NICHT operator-gated.** Theme + Composer-History sind für einen **Member** (kein
   Operator-Token) voll bedienbar. **Mutation:** Theme/History `disabled` ohne Operator ⇒ rot.
2. **Zwei Gate-Klassen nicht vermischt.** Repo/API-Key ohne Operator = `disabled` + Gate-Hint; persönliche Präferenzen
   nie hinter demselben Gate. **Mutation:** Repo/API-Key für Member editierbar **oder** Theme/History hinter dem
   Operator-Gate ⇒ rot.
3. **Effect-Hints AMBER, nie grün; „gespeichert ≠ aktiv".** **Mutation:** grüner/„Erfolg"-Ton oder „ist jetzt aktiv" ⇒
   rot.
4. **Kein Restart-Control im Settings-Level;** Hint zeigt auf `agent.<id>.restartBtn` (P2-a). **Mutation:** ein
   Restart-Button in Settings ⇒ rot (falsche Aktivierungsquelle).
5. **Repo-unset ehrlich (nennt die Folge).** **Mutation:** stumm leer / neutrales „OK" statt „Agenten können nicht
   starten" ⇒ rot.
6. **API-Key-Section gerahmt, nicht dupliziert.** Die Section-Interna kommen aus **einer** Quelle (CYP-433).
   **Mutation:** P2-f re-implementiert das Leak-Modell / weicht von CYP-433 ab (zweite Masking-/Reveal-Logik) ⇒ rot.
7. **Effect-Hint = ein Muster, konsistent Repo↔API-Key.** **Mutation:** die beiden Hints divergieren in Ton oder
   Struktur (einer amber, einer grün; einer „saved ≠ active", einer „gespeichert") ⇒ rot.
8. **Kein `ellipsis` auf Offenlegung/Fehler/Gate.** **Mutation:** `text-overflow: ellipsis` (oder Zeilen-Clamp) auf
   Effect-/Gate-/Error-/Status-Text ⇒ rot.

---

## 10. DOM-/A11y-Spezifika

- **Struktur:** `window.settings` › `window.settings.content` › `settings.panel` (nativer `overflow-y`). Jede Section =
  `role="group"` mit vorangestelltem `role="heading"` (`aria-level`), Reuse der Compose-`heading()`-Semantik.
- **Gate:** `aria-disabled` aus `operatorToken()` (`web-ts/src/platform/operatorToken.ts`; absent ⇒ fail-closed Member).
  Present, nie Fake (CYP-317). Gleiches Muster wie ACL/Lifecycle (`aria-disabled={!operator}`).
- **Töne:** Hints `role="status"`, Fehler `role="alert"`. Amber = `--md-sys-color-warn-container` /
  `--md-sys-color-on-warn-container`; Error = `--md-sys-color-error-container` / `-on-error-container`. **Keine
  Grün-Rolle** (existiert nicht). Zielgröße interaktiver Elemente ≥ 24px (Save, Reveal→CYP-433, Theme-Menü, Stepper
  −/+). Farbe nie alleiniger Träger (§8.6).
- **Persönliche Präferenzen (falls im Fenster, §6):** eigene, sichtbar getrennte Region, eigener Header, **ungegatet** —
  niemals unter `settings.section.repo/apiKey`-Gating.
- **Kein `ellipsis`** auf Offenlegungs-/Fehler-/Gate-/Status-Text (§8.7).

**Nichts gebaut — Spec + Dev5-Referenz.** P2-f ist der **Rahmen**: das Settings-Level, die Repo-Section, das gemeinsame
Gate- und Effect-Hint-Muster. Die API-Key-Section kommt aus **CYP-433** (eine Quelle), die persönlichen Präferenzen
bleiben **ungegatet** (Compose-Parität). Gemeinsame Voraussetzung: die Token-Ebene (Ton-Rollen als CSS-Variablen,
CYP-423).
