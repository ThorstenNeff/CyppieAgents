// CYP-399 (W1) — Contract type generator. Consumes the :core contract schema (AsyncAPI 2.6 with JSON-Schema
// components, produced by the server's ContractGenerator/SchemaWalker) and emits TypeScript types. "generate,
// don't commit": the output under src/types/generated/ is gitignored and produced in CI/build, never hand-edited.
//
// THE KEY STEP (CYP-399 "the `type` discriminator drives the union"): SchemaWalker emits each sealed union as
// `oneOf` + `discriminator{propertyName:"type", mapping}`, but the SUBTYPE component schemas carry NO `type`
// literal (kotlinx's subtype descriptor has only the subtype's own fields; `type` is synthetic on the wire). A
// naive codegen would produce a plain union, not a DISCRIMINATED one. So we read `discriminator.mapping` and
// inject the literal `type: "<wire>"` into each member before generating — the wire already carries it, and
// :core/the server stay unchanged. Fail-closed: a mapping target that doesn't resolve throws.
//
// Input: the real export `contract/asyncapi.json` (Backend2's Gradle build-export) if present; otherwise the
// PROVISIONAL fixture `contract/asyncapi.provisional.json` (with a loud warning). `/docs/*` is auth-gated, so we
// never fetch a live server — the schema is a deterministic build artifact.

import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { compile } from 'json-schema-to-typescript'
import { buildContractRootSchema } from './contractSchema.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..')

const OUT = resolve(root, 'src/types/generated/contract.ts')

// CYP-420: the load/merge/inject/rewrite pipeline moved to scripts/contractSchema.mjs so the zod runtime schemas
// are generated from the SAME source (one contract, no hand-maintenance). Extraction was verified byte-identical.
const { rootSchema, injectedLiterals, isProvisional } = buildContractRootSchema()

const banner = `/**
 * AUTO-GENERATED (CYP-399) — DO NOT EDIT. Run \`npm run contract:gen\`.
 * Source: ${isProvisional ? 'contract/asyncapi.provisional.json (PROVISIONAL — pending Backend2 build-export)' : 'contract/asyncapi.json (:core build-export)'}
 * The \`type\` discriminator is projected into each union member so these are real TS discriminated unions.
 */`

const ts = await compile(rootSchema, 'CyppieContract', {
  bannerComment: banner,
  additionalProperties: false,
  declareExternallyReferenced: true,
  unreachableDefinitions: true,
  format: false,
})

// Fail-closed guard: every injected discriminant literal must survive into the emitted TS, i.e. the union is
// really DISCRIMINATED (a plain union would drop the `type: "…"` literal). This is the consumer-side tooth for
// CYP-399's "the `type` discriminator drives the union".
const missing = injectedLiterals.filter((wire) => !ts.includes(`"${wire}"`))
if (missing.length > 0) {
  throw new Error(
    `[CYP-399] discriminant literal(s) did not survive codegen: ${missing.join(', ')} — the generated union is ` +
      'not discriminated. Fail-closed.',
  )
}

mkdirSync(dirname(OUT), { recursive: true })
writeFileSync(OUT, ts, 'utf8')
console.log(`[CYP-399] wrote ${OUT} (${injectedLiterals.length} discriminant literal(s) injected; all survived codegen)`)
