// CYP-420 — the SHARED contract-schema pipeline, extracted verbatim from the CYP-399 type generator so the zod
// runtime schemas and the TypeScript types are built from ONE source (the ticket's "eine Quelle, kein Handpflegen").
// Nothing here changed semantically in the extraction: the type generator's output was byte-compared before/after
// (sha256 identical), which is the tooth that this refactor is behaviour-preserving.
//
// Responsibilities (all from CYP-399/CYP-400/CYP-444, comments kept with their original ticket refs):
//   - choose the REAL :core build-export or the PROVISIONAL fixture (fail-closed under CONTRACT_REQUIRE_REAL)
//   - merge the REST (openapi) schemas that the WS asyncapi does not carry
//   - inject the `type` discriminant literal into each union member (so unions are really DISCRIMINATED)
//   - rewrite $refs into a draft-07 `definitions` root schema
import { readFileSync, existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { selectContractInput, contractRequireReal } from './contractInput.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..')

/** Build the draft-07 root schema (definitions + injected discriminants) shared by BOTH generators. */
export function buildContractRootSchema() {
  const REAL = resolve(root, 'contract/asyncapi.json')
  const PROVISIONAL = resolve(root, 'contract/asyncapi.provisional.json')
  // CYP-444: the REST contract (openapi.json, Backend2 build-export via CYP-426). It carries the REST-only DTOs the
  // WS asyncapi doesn't — Agent (roster + role), AgentDetail, mode/send request bodies, etc. Merged below.
  const REAL_OPENAPI = resolve(root, 'contract/openapi.json')

  // CYP-400: fail-closed flip — with CONTRACT_REQUIRE_REAL set (CI/release), a missing real export throws (exit 1).
  const choice = selectContractInput(existsSync(REAL), contractRequireReal(process.env.CONTRACT_REQUIRE_REAL))
  const inputPath = choice === 'real' ? REAL : PROVISIONAL
  const isProvisional = choice === 'provisional'
  if (isProvisional) {
    console.warn(
      '[CYP-399] ⚠  Using PROVISIONAL fixture contract/asyncapi.provisional.json — the real :core build-export ' +
        '(contract/asyncapi.json) is not present. Types are a stand-in; set CONTRACT_REQUIRE_REAL in CI to forbid this.',
    )
  }

  const doc = JSON.parse(readFileSync(inputPath, 'utf8'))
  const schemas = doc?.components?.schemas
  if (schemas === undefined || schemas === null) {
    throw new Error(`[CYP-399] no components.schemas in ${inputPath} — not a ContractGenerator schema doc?`)
  }

  // CYP-444: merge the REST (openapi) schemas so the roster + other REST DTOs are typed too. We keep the WS asyncapi
  // schemas EXACTLY (values AND key order) and append only the openapi-ONLY names (Agent, AgentDetail, …). Appending
  // only new keys is deliberate: (a) asyncapi keeps its discriminated-union `discriminator`/`mapping` the injection
  // step needs, and (b) json-schema-to-typescript's collision numbering (e.g. Message vs the `message` event wrapper)
  // stays stable — a shared DTO is byte-identical in both docs (:core source), so dropping openapi's copy loses nothing.
  let restOnly = {}
  if (existsSync(REAL_OPENAPI)) {
    const openapiDoc = JSON.parse(readFileSync(REAL_OPENAPI, 'utf8'))
    const restSchemas = openapiDoc?.components?.schemas ?? {}
    restOnly = Object.fromEntries(Object.entries(restSchemas).filter(([name]) => !(name in schemas)))
  } else if (!isProvisional) {
    console.warn('[CYP-444] ⚠  contract/openapi.json not found — REST-only types (Agent roster, …) will be missing.')
  }

  // Deep clone so we never mutate the source doc on disk.
  const defs = structuredClone({ ...schemas, ...restOnly })

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
  return { rootSchema, injectedLiterals, isProvisional, inputPath }
}

/**
 * CYP-737 — the REST response roots, DERIVED from the openapi paths rather than hand-listed.
 *
 * The WS side keeps an explicit FRAME_ROOTS list because there are eight channels and adding one is a conscious
 * act. REST has 67 operations: a hand-maintained list would rot on the first endpoint someone adds, and the rot is
 * SILENT — the new response simply goes unvalidated while everything still looks covered. Deriving it makes
 * coverage structural instead of disciplinary.
 *
 * Returns the distinct named schemas referenced by any 2xx JSON response, unwrapping `array` items so
 * `List<Agent>` contributes `Agent`. Inline (unnamed) response schemas are reported separately: they cannot be
 * emitted as a named root, and silently dropping them would be exactly the invisible gap this function exists to
 * prevent.
 */
export function restResponseRoots() {
  const REAL_OPENAPI = resolve(root, 'contract/openapi.json')
  if (!existsSync(REAL_OPENAPI)) return { roots: [], inlineOps: [] }
  const doc = JSON.parse(readFileSync(REAL_OPENAPI, 'utf8'))
  const roots = new Set()
  const inlineOps = []
  for (const [path, methods] of Object.entries(doc?.paths ?? {})) {
    for (const [verb, op] of Object.entries(methods ?? {})) {
      for (const [code, res] of Object.entries(op?.responses ?? {})) {
        if (!/^2/.test(code)) continue
        const schema = res?.content?.['application/json']?.schema
        if (schema === undefined) continue // no body (204 etc.) — nothing to validate
        const named = schema.$ref ?? (schema.type === 'array' ? schema.items?.$ref : undefined)
        if (named === undefined) inlineOps.push(`${verb.toUpperCase()} ${path}`)
        else roots.add(named.replace('#/components/schemas/', ''))
      }
    }
  }
  return { roots: [...roots].sort(), inlineOps: [...new Set(inlineOps)].sort() }
}
