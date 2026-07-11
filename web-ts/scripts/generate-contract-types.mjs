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

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { compile } from 'json-schema-to-typescript'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..')

const REAL = resolve(root, 'contract/asyncapi.json')
const PROVISIONAL = resolve(root, 'contract/asyncapi.provisional.json')
const OUT = resolve(root, 'src/types/generated/contract.ts')

const inputPath = existsSync(REAL) ? REAL : PROVISIONAL
const isProvisional = inputPath === PROVISIONAL
if (isProvisional) {
  console.warn(
    '[CYP-399] ⚠  Using PROVISIONAL fixture contract/asyncapi.provisional.json — Backend2 build-export ' +
      '(contract/asyncapi.json) not present yet. Types are a stand-in until the real :core export lands.',
  )
}

const doc = JSON.parse(readFileSync(inputPath, 'utf8'))
const schemas = doc?.components?.schemas
if (schemas === undefined || schemas === null) {
  throw new Error(`[CYP-399] no components.schemas in ${inputPath} — not a ContractGenerator schema doc?`)
}

// Deep clone so we never mutate the source doc on disk.
const defs = structuredClone(schemas)

// --- inject the discriminant literal into each union member (the CYP-399 core step) ------------------------
const refName = (ref) => {
  if (typeof ref !== 'string' || !ref.startsWith('#/components/schemas/')) {
    throw new Error(`[CYP-399] unexpected $ref (want #/components/schemas/X): ${ref}`)
  }
  return ref.slice('#/components/schemas/'.length)
}

let injected = 0
const injectedLiterals = []
for (const [unionName, schema] of Object.entries(defs)) {
  const disc = schema?.discriminator
  if (disc === undefined || !Array.isArray(schema.oneOf)) continue
  const prop = disc.propertyName
  const mapping = disc.mapping ?? {}
  if (typeof prop !== 'string' || Object.keys(mapping).length === 0) {
    throw new Error(`[CYP-399] union ${unionName} has a discriminator without propertyName/mapping`)
  }
  for (const [wire, ref] of Object.entries(mapping)) {
    const member = defs[refName(ref)]
    if (member === undefined) {
      throw new Error(`[CYP-399] union ${unionName}: mapping "${wire}" -> ${ref} does not resolve to a schema`)
    }
    member.type = 'object'
    member.properties = member.properties ?? {}
    const existing = member.properties[prop]
    if (existing !== undefined && existing.const !== undefined && existing.const !== wire) {
      throw new Error(
        `[CYP-399] member ${refName(ref)} already has ${prop}=${existing.const} but union ${unionName} maps it to "${wire}"`,
      )
    }
    member.properties[prop] = { type: 'string', const: wire }
    member.required = Array.from(new Set([...(member.required ?? []), prop]))
    injected++
    injectedLiterals.push(wire)
  }
}
if (injected === 0) {
  throw new Error('[CYP-399] no discriminated-union members were injected — schema shape unexpected (fail-closed)')
}

// --- rewrite $refs #/components/schemas/X -> #/definitions/X (json-schema-to-typescript resolves definitions) --
const rewriteRefs = (node) => {
  if (Array.isArray(node)) return node.map(rewriteRefs)
  if (node !== null && typeof node === 'object') {
    const out = {}
    for (const [k, v] of Object.entries(node)) {
      if (k === '$ref' && typeof v === 'string') out[k] = v.replace('#/components/schemas/', '#/definitions/')
      else if (k === 'mapping' && v !== null && typeof v === 'object') {
        out[k] = Object.fromEntries(
          Object.entries(v).map(([mk, mv]) => [mk, String(mv).replace('#/components/schemas/', '#/definitions/')]),
        )
      } else out[k] = rewriteRefs(v)
    }
    return out
  }
  return node
}

const rootSchema = {
  $schema: 'http://json-schema.org/draft-07/schema#',
  title: 'CyppieContract',
  type: 'object',
  additionalProperties: false,
  definitions: rewriteRefs(defs),
}

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
console.log(`[CYP-399] wrote ${OUT} (${injected} discriminant literal(s) injected; all survived codegen)`)
