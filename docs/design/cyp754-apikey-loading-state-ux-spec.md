# CYP-754 — ApiKeyPanel: distinkter Lade-Zustand (Lade-Kante ≠ `set:false`) · Fix-Spec

**Für:** Dev5 (Render + Zahn) · **Von:** UIUX2 (Team-2) · **Baseline (am Objekt):** develop, `web-ts/src/settings/ApiKeyPanel.tsx`
**Herkunft:** UX-QA-Honesty-Sweep #57, Fund #1 (MODERATE-HIGH) — hand-verifiziert. **Klasse:** distinguishable≠distinguished ([[absence-reads-as-all-clear]], CYP-679/CYP-705/CYP-288-Familie: unknown ≠ autoritativ-negativ). · **human-identity: n** (Operator-Fläche, keine Menschen-Identität).

---

## §0 Der Fund (Objekt-Refs)
- `ApiKeyPanel.tsx:39` — `const masked = view?.set ? 'Hinterlegt: ${view.masked}' : 'Kein Schlüssel hinterlegt'`.
- `ApiKeyPanel.tsx:64` — Status-Region: `view === null && loadError ? <LoadErrorRetry/> : <p masked>`.
- **Kollaps:** der reine **Lade**-Zustand `view === null && loadError === false` (Fetch noch nicht aufgelöst; `apiKeyView` startet `null`, `App.tsx:914`) fällt in den `else`-Zweig → `view?.set` ist `undefined` → **„Kein Schlüssel hinterlegt"** — **byte-gleich** zur autoritativen `set:false`-Aussage, auf der **leak-sensibelsten** Fläche.
- Der Datei-Header (CYP-679, `:23-25`) benennt genau diese falsche Behauptung — schloss aber nur die **Error**-Kante, **nicht** die **Lade**-Kante.

## §1 Der Fix — die Status-Region wird **drei-wertig** (loading / error / resolved)
Die Region (`:62-70`) unterscheidet ab jetzt **drei** Zustände; das autoritative masked (`set`/unset) rendert **nur** bei aufgelöstem `view` (dann ist `view.set` ein echter Boolean, nie `undefined`):

| Bedingung | Render | testid | role | Copy |
|---|---|---|---|---|
| `view === null && loadError` | `<LoadErrorRetry/>` (bestehend) | `settings.apiKey.loadError` | (bestehend) | Error + Retry |
| **`view === null && !loadError`** *(NEU)* | Lade-Node | `settings.apiKey.loading` | `status` | **„API-Schlüssel-Status wird geladen…"** |
| `view !== null` | `<p className="apikey-masked">{masked}</p>` | `settings.apiKey.masked` | — | `view.set` ? „Hinterlegt: …" : „Kein Schlüssel hinterlegt" |

**Skizze (illustrativ):**
```tsx
{view === null && loadError ? (
  <LoadErrorRetry testId="settings.apiKey.loadError" onRetry={onRetryLoad ?? (() => undefined)} />
) : view === null ? (
  <p className="apikey-loading" data-testid="settings.apiKey.loading" role="status">
    API-Schlüssel-Status wird geladen…
  </p>
) : (
  <p className="apikey-masked" data-testid="settings.apiKey.masked">
    {view.set ? `Hinterlegt: ${view.masked}` : 'Kein Schlüssel hinterlegt'}
  </p>
)}
```
- **`masked` (`:39`) wird in den `view !== null`-Zweig verschoben** → `view?.set` (optional-chain) wird zu `view.set` (echter Boolean). Kein `undefined`→falsy→false-Claim mehr möglich **by construction**.
- **Honesty:** die Lade-Node ist **neutral** (kein Alarm — [[over-alarm-is-also-dishonest]]; unknown ist kein Fehler), **keine Behauptung** über set/unset. „Kein Schlüssel hinterlegt" bleibt **nur** als autoritative Aussage bei `view.set === false`.
- **Reuse:** dasselbe resolve-then-assert-Muster wie `AuthGate` (`resolving`-Screen vor dem Render) und `setupStatus.ts` (unknown ≠ error ≠ answer). Kein neues Vokabular.

## §2 Der gestagete Zahn (diskriminierend — nach meiner Sweep-Methode)
Der Test muss die **Lade-Kante von `set:false` trennen** — er rötet auf **genau der Mutation** „Lade-Zustand kollabiert in set:false" ([[test-must-discriminate]]: nicht „rendert er?", sondern „welche falsche Impl ließe er durch?"):

- **① Lade-Kante ≠ false-Claim:** `render(<ApiKeyPanel view={null} loadError={false} …/>)` →
  - **MUSS**: `getByTestId('settings.apiKey.loading')` vorhanden.
  - **MUSS NICHT**: Text „Kein Schlüssel hinterlegt" **nirgends** (`queryByText(/Kein Schlüssel hinterlegt/)` = null). **← der diskriminierende Assert:** die alte (kollabierende) Impl rötet hier.
- **② autoritativ-unset bleibt ehrlich:** `view={{ set:false, masked:'' }} loadError={false}` → „Kein Schlüssel hinterlegt" **vorhanden**, kein `loading`-Node. (Der Fix darf die legitime `set:false`-Aussage nicht verschlucken.)
- **③ autoritativ-set:** `view={{ set:true, masked:'***abcd' }}` → „Hinterlegt: ***abcd".
- **④ Error-Kante unverändert:** `view={null} loadError={true}` → `settings.apiKey.loadError`, **kein** `loading`, **kein** masked.

Vier distinkte Inputs → vier distinkte Renders; ① ist der Zahn, der die Regression fängt.

## §3 Landing-Hinweise
- **String inline** wie die bestehenden Panel-Strings („Kein Schlüssel hinterlegt" etc. sind inline in `ApiKeyPanel.tsx`) → die neue Zeile „API-Schlüssel-Status wird geladen…" landet **inline mit Dev5s Impl**, **kein** separater i18n-Key ([[shared-key-landing]] — hier keine Shared-Key-Drift, weil panel-lokal).
- **CSS:** `.apikey-loading` = neutraler Text-Ton (wie `.apikey-masked`, kein Alarm/Fehler-Rot).
- **Kein Prop-/Signatur-Change:** rein render-intern (`view`/`loadError` existieren bereits als Props). Scope minimal, ready für Dev5/Landing.
