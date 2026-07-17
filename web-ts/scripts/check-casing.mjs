// CYP-674 — FS-INDEPENDENT file-casing-collision guard for the web-ts source tree.
//
// Two modules in the SAME directory whose basenames differ ONLY in case (e.g. `WindowBadge.tsx` vs `windowBadge.ts`)
// compile fine on a case-SENSITIVE FS (Linux / the merge-gate host / CI) but break module resolution on a
// case-INSENSITIVE FS (macOS / Windows) → TS1149 "File name differs only in casing" + a cascade, and a potentially
// mis-resolved app bundle. A Linux gate is structurally BLIND to this — the FS itself hides the second file — so we
// detect it from GIT's index (which records the exact committed case regardless of the working FS) rather than by
// walking the filesystem. Exits non-zero and prints each colliding pair when any same-dir basename collides
// case-insensitively; wire it into the web-ts gate so this class can never reach a case-insensitive target again.
import { execSync } from 'node:child_process'
import path from 'node:path'

// Git records the committed case; listing from the index (not the FS) is what makes this check FS-independent.
const files = execSync('git ls-files -- src', { encoding: 'utf8' })
  .split('\n')
  .filter((f) => /\.(ts|tsx)$/.test(f))

// Group by (directory, case-folded basename-without-extension). A group with >1 distinct real name is a collision.
const groups = new Map()
for (const f of files) {
  const dir = path.posix.dirname(f)
  const base = path.posix.basename(f).replace(/\.(ts|tsx)$/, '')
  const key = `${dir}\0${base.toLowerCase()}`
  if (!groups.has(key)) groups.set(key, new Set())
  groups.get(key).add(`${dir}/${base}`)
}

const collisions = [...groups.values()].filter((s) => s.size > 1).map((s) => [...s].sort())
if (collisions.length > 0) {
  console.error(
    `CYP-674 casing-collision check FAILED — ${collisions.length} same-dir basename collision(s), unsafe on a case-insensitive FS (macOS/Windows):`,
  )
  for (const pair of collisions) console.error(`  ${pair.join('  <->  ')}`)
  console.error('Fix: rename one module per pair so no two basenames differ only in case (e.g. append a `Model` suffix to the logic module).')
  process.exit(1)
}
console.log('CYP-674 casing-collision check OK — no case-only basename collisions in web-ts/src.')
