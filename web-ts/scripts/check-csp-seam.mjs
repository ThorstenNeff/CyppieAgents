#!/usr/bin/env node
// CYP-455 (CYP-422-prep) — the CSP-nonce-seam GUARD. Runs on the BUILT artifact, after `vite build`.
//
// Why a guard and not just a one-off measurement: the strict cutover CSP (`script-src 'self' 'nonce-…'`, no
// `unsafe-inline`) is the actual XSS defense, and it only holds if the emitted index.html has NO un-nonced inline
// script. That property is produced by a single config line (`build.modulePreload.polyfill=false`) and can be
// silently undone by flipping it back, by a Vite upgrade that emits a new inline snippet, or by a plugin. All three
// would leave the gate GREEN while quietly making the cutover CSP impossible — the deploy would only find out by
// either breaking the app or weakening `script-src` to `unsafe-inline`. So it is measured on every build.
//
// NOTE on the parsing: HTML COMMENTS ARE STRIPPED FIRST. index.html documents the deploy's injection form inside a
// comment (`<script nonce="__CSP_NONCE__">…`), so a naive scan reports false positives on its own documentation.
// Measured 2026-07-18: naive scan = 2 hits, both from the comment; comment-stripped = 0.
import { readFileSync, existsSync } from 'node:fs'

const DIST = 'dist/index.html'
const SRC = 'index.html'
const NONCE_PLACEHOLDER = '__CSP_NONCE__'

const fail = (msg) => {
  console.error(`CYP-455 CSP-seam check FAILED:\n${msg}`)
  process.exit(1)
}

// Fail closed: a missing artifact must never read as "clean" (that would make the check vacuous).
if (!existsSync(DIST)) fail(`${DIST} not found — run \`vite build\` first. Refusing to pass vacuously.`)

const stripComments = (html) => html.replace(/<!--[\s\S]*?-->/g, '')
const dist = stripComments(readFileSync(DIST, 'utf8'))

// ① the load-bearing property: every emitted <script> must be an external `src` one. An inline script would need a
//    per-response nonce the build cannot know — i.e. it forces `unsafe-inline` at cutover.
const inline = [...dist.matchAll(/<script[^>]*>/g)].map((m) => m[0]).filter((t) => !/\ssrc\s*=/.test(t))
if (inline.length > 0) {
  fail(
    `${DIST} emits ${inline.length} INLINE <script> tag(s) — this breaks the strict cutover CSP (script-src without\n` +
      `'unsafe-inline'). Check that build.modulePreload.polyfill is still false in vite.config.ts, and that no plugin\n` +
      `injects an inline snippet.\n  ${inline.join('\n  ')}`,
  )
}

// ② tokenless-by-construction (CYP-398/152/188): the operator token is deploy-injected at SERVE time and must never
//    be baked into the built artifact. A committed/compiled token would leak operator authority to every visitor.
if (/CYPPIE_OPERATOR_TOKEN\s*=/.test(dist)) {
  fail(`${DIST} contains a baked-in CYPPIE_OPERATOR_TOKEN assignment — the artifact must stay TOKENLESS (deploy-injected only).`)
}

// ③ the seam itself is a CONTRACT with the deploy: the placeholder the deploy stamps per response must stay
//    documented in source. Losing it silently strands the deploy's nonce step.
if (!readFileSync(SRC, 'utf8').includes(NONCE_PLACEHOLDER)) {
  fail(`${SRC} no longer documents the \`${NONCE_PLACEHOLDER}\` nonce seam — the deploy's per-response nonce contract must stay recorded.`)
}

console.log(`CYP-455 CSP-seam OK: 0 inline <script> in ${DIST}, artifact tokenless, nonce seam documented.`)
