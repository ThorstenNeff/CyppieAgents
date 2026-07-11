# CYP-433 (P2-e) — API-Key-Screen im DOM: die leak-sensibelste Fläche

> Owner: UIUX-Designer · Ticket **CYP-433** (Epic **CYP-430** Voller-Ersatz-Cutover, P2-e) · Stand 2026-07-11
> Basis `origin/develop` `3213a1b3` · `09-UI-Funktionskatalog` §9 · Docs-only → **Dev5-Referenz**.
> **Port der Compose-Quelle, kein Neuentwurf.** **0 neue Keys/Tags.** **Nichts gebaut.**
>
> **Quelle:** `settings/SettingsPanel.kt` (`ApiKeySection`) · `SettingsViewModel.kt` · `SettingsTags.kt` ·
> `settings_apikey_*`-Keys. Der Settings-Panel hat **zwei** Abschnitte (Repo-Config §1 + API-Key §9); **CYP-433 =
> der API-Key-Abschnitt** (der Repo-Abschnitt ist der Geschwister-Slice, gleiches Panel, gleiches Gating).

---

## 0. Das Leak-Modell zuerst (mein Revier) — drei harte Regeln über sensible Daten

Der Klartext-Schlüssel ist das Geheimnis; die UI berührt ihn nur **schreibend, transient**. Die Regeln, aus denen
alles folgt (09 §9 Querschnitt: „nie im Klartext zurückrendern, maskiert letzte 4, nie geloggt, operator-geschützt"):

1. **Der Klartext-Schlüssel gelangt NIE dauerhaft in den DOM.** Der **hinterlegte** Schlüssel wird **nur** als die
   **server-maskierte** Form `***<last4>` gezeigt (`settings_apikey_masked` „Hinterlegt: %1$s") — **nie** clientseitig
   aus einem Klartext maskiert: **der Client hat den Klartext gar nicht** (der Server liefert nur `apiKeyMasked`).
   Das Eingabefeld ist **write-only** und **startet leer** — der hinterlegte Schlüssel wird **nie** ins Feld geladen.
2. **Der einzige Klartext im DOM ist der NEUE Schlüssel, den der Operator gerade tippt** — transient im
   `value` des Eingabefelds. **Regel:** `type="password"` (default), `autocomplete="new-password"`, **kein**
   persistierender `name`, **kein** Reflektieren des Werts in `data-*`/`aria-*`/andere Attribute, und **nach
   erfolgreichem Speichern das Feld leeren** (der getippte Schlüssel bleibt nicht im DOM stehen).
3. **Nie geloggt.** Kein `console.*`, keine Telemetrie, **keine Fehlermeldung** enthält je den Wert — der Fehler ist
   der generische `settings_apikey_save_failed` „Speichern fehlgeschlagen". Auch kein Paste-Handler, der den Wert
   irgendwohin spiegelt.

> Das ist die **schärfste** Ausprägung der Leak-Grenzen-Kette (CYP-421 content-free → CYP-432 gated-egress-Bodies →
> **CYP-433 Klartext-Secret**). Hier ist die Grenze nicht der Mount und nicht ein Feld — es ist der **Wert selbst**.

---

## 1. Gating-Kontrast zu CYP-432 — hier **present-but-disabled**, nicht Omission

Wichtig, weil es zu CYP-432 gegenläufig ist und leicht falsch übernommen wird:

| | CYP-432 Event-Log | **CYP-433 API-Key** |
|---|---|---|
| Trägt die **Fläche** ein Geheimnis? | **ja** (`detail`-Bodies) | **nein** (maskierter Status ≠ Schlüssel; Feld leer) |
| Gating ohne Operator | **Omission** — Route nicht gemountet | **present-but-disabled** — Panel sichtbar, Eingaben `disabled` + Gate-Hinweis |

**Warum hier present-but-disabled richtig ist:** der Screen selbst leakt nichts — der maskierte Status ist `***last4`
(kein Schlüssel), das Eingabefeld ist leer. Also bleibt der Gate **beobachtbar** (Muster CYP-73/ACL): der
Nicht-Operator *sieht*, dass es einen Schlüssel-Screen gibt und dass er operator-geschützt ist, kann ihn aber nicht
ändern. Die Leak-Grenze ist der **Klartext-Wert** (§0), nicht der Screen.

> **Scope-Frage (nicht erfunden):** soll für einen Nicht-Operator auch der **maskierte** Status (`***last4`) verborgen
> werden? Compose zeigt ihn (present-but-disabled). Ihn zu verbergen wäre **strenger als die Compose-Parität** — als
> PO-Entscheidung markiert, nicht selbst gesetzt. Default = Parität (maskiert sichtbar, Eingaben gated).

---

## 2. Anatomie (Mirror `ApiKeySection`) — Area `settings.apiKey`

```
┌ settings.section.apiKey ───────────────────────────────────────────────┐
│  «API-Schlüssel»  (heading)                                             │
│  settings.apiKey.masked : „Hinterlegt: ***last4"  |  „Kein Schlüssel hinterlegt" │
│  [ settings.apiKey.input  (write-only, password) ]  [ settings.apiKey.reveal ]   │
│  settings.apiKey.gateHint   (Nicht-Operator: „Nur mit Operator-Token änderbar")  │
│  [ settings.apiKey.save ]                                               │
│  settings.apiKey.error      (generisch, nie der Wert)                   │
│  settings.apiKey.effectHint (AMBER: „Gespeichert. Wirkt beim nächsten Start – neu starten…") │
└─────────────────────────────────────────────────────────────────────────┘
```

**Status (read-only):** `settings.apiKey.masked` = `settings_apikey_masked "Hinterlegt: %1$s"` (server-`***last4`)
**oder** `settings_apikey_unset "Kein Schlüssel hinterlegt"`. Ein einfacher Text-Knoten, nie interaktiv, nie der Wert.

**Eingabe (write-only) + Reveal:**
- `settings.apiKey.input`: `<input type="password">`, **leer** (Placeholder `settings_apikey_placeholder` „Neuen
  Schlüssel eingeben…"), `enabled` = Operator. a11y `a11y_settings_apikey_input`.
- `settings.apiKey.reveal`: Toggle, der **nur die aktuelle Eingabe** un-maskiert (`type` password↔text am **Input**,
  nie am maskierten Status). **Text-Label, kein Emoji** (CYP-99/CYP-54: 👁/🙈 war tofu-anfällig und bricht die
  no-emoji-Konvention) — das Label ist zugleich die a11y-Beschreibung (`a11y_settings_apikey_reveal`/`_hide`).
- **Nach Speichern: Feld leeren** (§0.2).

**Save:** `settings.apiKey.save` (`settings_save`), `enabled` = Operator ∧ nicht-leer.

**Fehler:** `settings.apiKey.error` = generisch (`settings_apikey_save_failed`), **nie** der Wert.

---

## 3. Die zwei Hinweise & ihre Töne — Ehrlichkeit vor Optik

- **Effect-Hint (`settings.apiKey.effectHint`) = AMBER** (`EFFECT_DEFERRED`-Ton), **nie** Erfolgs-Grün:
  `settings_apikey_effect_hint` „Gespeichert. Wirkt erst beim nächsten Start des Agenten – jetzt neu starten, damit
  der neue Schlüssel zieht." **„Gespeichert ≠ aktiv"** — dieselbe Ehrlichkeit wie „saved ≠ active" und dieselbe
  WARN-amber-nicht-grün-Regel (W6/ACL/Event-Log).
- **Restart-Folge — kein Restart-Control hier.** Der Hinweis **zeigt auf** den bestehenden CYP-73 per-Agent-Restart
  (`agent.<id>.restartBtn`, **P2-a/CYP-431**) — Reuse, **kein** neuer Control, **kein** neuer Key. Der Screen
  aktiviert nicht selbst; er sagt ehrlich, dass der nächste Start/Neustart den Schlüssel zieht.
- **Gate-Hint (`settings.apiKey.gateHint`) = GATED-Ton:** `workspace_operator_only` bzw. `settings_operator_required`
  „Nur mit Operator-Token änderbar". Sichtbar, wenn nicht editierbar.

Alle drei sind **Text + Ton + a11y**, Farbe nie allein (WCAG 1.4.1). **Kein `ellipsis`** auf diesen Offenlegungssätzen.

---

## 4. Operator-Gate (fail-closed, present-but-disabled)

`editable` = Operator-Token. Ohne: **Eingabe + Reveal + Save `disabled`** (`aria-disabled="true"`, present, **kein
Fake-Control** CYP-317) + der Gate-Hint (§3). Der maskierte Status bleibt sichtbar (§1). Der Gate ist **beobachtbar**,
nie ein still totes Feld, das Editierbarkeit vorspiegelt.

---

## 5. Keys & Tags — alles bestehend (0 neu)

**Keys (Reuse):** `settings_apikey_section`/`_masked`/`_unset`/`_placeholder`/`_effect_hint`/`_save_failed`,
`settings_operator_required`, `workspace_operator_only`, `settings_save`, `a11y_settings_apikey_input`/`_reveal`/`_hide`.
**Tags (Reuse):** `settings.section.apiKey` · `settings.apiKey.masked`/`.input`/`.reveal`/`.save`/`.gateHint`/
`.effectHint`/`.error` → im DOM als `data-testid`, punktfrei-Schema unverändert (Test-Contract v0.5, QA CYP-7).
Kein neuer Key aus dem Medienwechsel; taucht ein DOM-Element auf, das Compose nicht hat, liefere ich den Key impl-nah.

---

## 6. Abnahme-Zähne (diskriminierend, leak-fokussiert) — je mit der falschen Impl, die er ablehnt

1. **Klartext nie dauerhaft im DOM.** Der hinterlegte Schlüssel erscheint **nur** als `***last4`; das Eingabefeld
   ist beim Laden **leer**. **Mutation:** hinterlegter Schlüssel im Klartext (oder im `value` des Inputs vorgeladen)
   ⇒ rot.
2. **Server-maskiert, nicht client-maskiert.** Der maskierte Text kommt aus `apiKeyMasked` (Server), nicht aus einem
   Klartext, den der Client mascht. **Mutation:** Client hält/masht den Klartext ⇒ rot.
3. **Reveal un-maskiert nur die aktuelle Eingabe.** Toggle wirkt auf den Input, **nie** auf den Status. **Mutation:**
   Reveal zeigt den hinterlegten Schlüssel ⇒ rot.
4. **Nach Speichern Feld geleert.** **Mutation:** getippter Schlüssel bleibt im `value` nach Save ⇒ rot.
5. **Nie geloggt / nie im Fehler.** **Mutation:** ein `console.*`/Telemetrie/`error`-String enthält den Wert ⇒ rot.
6. **Effect-Hint AMBER, nie grün; „gespeichert ≠ aktiv".** **Mutation:** grüner „Erfolg"-Ton / „aktiv" ⇒ rot.
7. **Kein Restart-Control hier;** Hinweis zeigt auf `restartBtn` (P2-a). **Mutation:** ein eigener Restart-Button im
   Key-Screen ⇒ rot (falsche Aktivierungsquelle).
8. **Gate present-but-disabled, kein Fake.** Nicht-Operator: Eingaben `disabled` + Gate-Hint, Panel **präsent**.
   **Mutation:** aktives Fake-Feld **oder** ganze Fläche omitted (das ist CYP-432, hier falsch) ⇒ rot.

---

## 7. DOM/A11y-Spezifika

- Eingabe: `type="password"`, `autocomplete="new-password"`, kein persistierender `name`; Reveal toggelt `type`.
- Section-Heading als `role="heading"` (`aria-level`); Reveal-Button mit `aria-label` = das Text-Label; Hints als
  `role="status"` (Effect/Gate) bzw. `role="alert"` (Error).
- Zielgröße ≥ 24px (Reveal-Toggle, Save). Farbe nie alleiniger Träger (§3). Kein `ellipsis` auf Hinweistext.
- Der `detail`/Wert nie in `title`/`aria-label`/`data-*`; Clipboard-Paste erlaubt, aber nicht spiegeln/loggen.

**Nichts gebaut — Spec + Dev5-Referenz. Der Klartext-Schlüssel ist das Geheimnis; die UI berührt ihn nur schreibend,
transient, und leert danach. Gemeinsame Voraussetzung: Token-Ebene (Ton-Rollen als CSS-Variablen).**
