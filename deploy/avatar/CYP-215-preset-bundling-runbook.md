# CYP-215 — DiceBear Preset Bundling Runbook (deploy asset step)

**Turnkey, one-shot deploy step** to populate the self-hosted DiceBear avatar presets. Egress-free at
runtime (no `api.dicebear.com`, no Node/rasterizer sidecar) — generation happens **offline at deploy**, the
server only ever reads static PNGs. Authored + **locally verified against `dicebear@10.3.0`** on 2026-07-05.

> Human gate: run this only on an explicit deploy-GO (bundled CYP-208+212 deploy). The CC-BY styles need a
> ship-license sign-off (see §5) before the generated PNGs are shipped.

---

## 1. Prerequisites

- **Node.js ≥ 22** (DiceBear 10.x requires it). Verified with `node v22.22.1`.
- **Pinned CLI: `dicebear@10.3.0`** (do NOT float — the CLI is version-sensitive). Invoke via `npx --yes
  dicebear@10.3.0 …` (no global install needed) or `npm install --global dicebear@10.3.0`.
- Target host must reach npm ONCE at deploy to fetch the CLI; the generated assets are then static + committed
  to the deploy artifact / placed out-of-repo. **Runtime needs no network.**

## 2. The 5 approved styles + target layout

Only the UIUX/CYP-214 license-cleared styles (matches `AvatarPresetResolver.ALLOWED_STYLES` — an unknown
style is rejected fail-closed server-side):

| Style        | License                         | Ship note |
|--------------|---------------------------------|-----------|
| `bottts`     | Free (Pablo Stanley, pers.+comm.) | attribution-free |
| `avataaars`  | Free (Pablo Stanley, pers.+comm.) | attribution-free |
| `adventurer` | **CC BY 4.0**                   | attribution carried **in-app** (UIUX/Dev) |
| `big-smile`  | **CC BY 4.0**                   | attribution carried **in-app** (UIUX/Dev) |
| `fun-emoji`  | **CC BY 4.0**                   | attribution carried **in-app** (UIUX/Dev) |

**Target root (out-of-repo, gitignored, mirrors the other `.cyppie/` stores):**
```
<PLATFORM_GIT_ROOT>/.cyppie/avatar-presets/<style>/<style>-<n>.png
```
This is the exact path `AvatarPresetResolver(avatarPresetsDir)` reads (wired in `PlatformWiring` as
`gitRoot/.cyppie/avatar-presets`). The resolver lists `<style>/*.png` (sorted) and picks one deterministically
by `hash(seed) % count`, so **any stable set of PNGs per style works** — filenames are irrelevant beyond the
`.png` suffix.

## 3. Generation commands (verified)

**Static gallery set — recommended default** (`--count N` → N avatars per style; the resolver maps each agent
deterministically by seed-hash, so a gallery is all that's needed):

```bash
ROOT="${PLATFORM_GIT_ROOT:-.}/.cyppie/avatar-presets"
for style in bottts avataaars adventurer big-smile fun-emoji; do
  npx --yes dicebear@10.3.0 "$style" "$ROOT/$style" --count 8 --format png --size 256
done
```

Produces `$ROOT/<style>/<style>-0.png … <style>-7.png`, each **256×256 PNG** (verified: 8/style, 40 total,
`PNG image data, 256 x 256, 8-bit/color RGBA`, ~8 KB each).

Notes:
- `--size 256` matches the app's avatar edge (same as the upload re-encode target). Presets are served as-is
  (DiceBear PNGs carry no EXIF; no re-encode needed — re-encode is only for untrusted *uploads*).
- `--count` ignores `--seed` (the N are randomized). For **reproducible** deploys (same bytes each run), either
  commit the generated set to the artifact, or generate per explicit seed (below).
- `--exif` is OFF by default — leave it off (no metadata in shipped assets).

**Deterministic per-seed variant** (if reproducible-by-seed bytes are wanted instead of a random gallery):

```bash
npx --yes dicebear@10.3.0 bottts "$ROOT/bottts" --seed "v1-0" --format png --size 256   # one file, stable
# …repeat with --seed v1-1, v1-2, … for as many stable variants as desired
```

## 4. Wiring (already shipped in CYP-215 — no code change at deploy)

- `PlatformWiring`: `avatarPresetsDir = gitRoot/.cyppie/avatar-presets`.
- `AvatarPresetResolver.resolve(style, seed)`: `<style>` dir missing/empty → returns `null`.
- `GET /api/agents/{id}/avatar` (participant-gated): Preset → resolved PNG; **no asset → 404 → the client
  falls back to its CYP-210 colour-slot default.** So a missing/partial set **degrades gracefully** — nothing
  hard-fails, and the app is fully functional before the assets land.

## 5. License sign-off (human gate, before ship)

- `bottts`, `avataaars`: attribution-free → no action.
- `adventurer`, `big-smile`, `fun-emoji`: **CC BY 4.0** → the app must carry attribution (UIUX/Dev own the
  in-app credit). **Confirm the attribution is present before shipping these three styles' PNGs.**

## 6. Local verification (done 2026-07-05)

1. **Recipe** — ran the §3 static-set command with `dicebear@10.3.0`: 40 PNGs, 8/style, all
   `PNG 256×256 RGBA`, magic `89 50 4E 47 0D 0A 1A 0A`. ✅
2. **Serve path** — `AvatarPresetServeTest` (`:server:test`, green):
   - preset + bundled asset → `GET /api/agents/{id}/avatar` serves the **exact** PNG bytes (participant-gated, 256²).
   - preset + no asset → **404** → client default (graceful degradation).
   - deterministic serve for a given `(style, seed)`.
   - RUN-gated `CYP215_REAL_PRESETS` method served the **real DiceBear output** for all 5 styles through the
     HTTP endpoint (256² each). ✅ (self-skips in CI, which has no Node).

**One-shot at deploy:** run §3 → drop under `.cyppie/avatar-presets/` → presets light up; nothing else to do.
```
