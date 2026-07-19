// CYP-420 — zod RUNTIME schemas for the WS boundary, generated from the SAME :core contract as the TS types
// (scripts/contractSchema.mjs). "generate, don't commit": output is gitignored and rebuilt by `npm run zod:gen`.
//
// Why runtime validation at all, when the types already exist: TS types are erased at runtime. Every WS ingress
// did `JSON.parse(data) as T` — an unchecked cast. A malformed or hostile frame was therefore treated as a valid
// typed object and flowed straight into the store. The server is source-of-truth, but that is defense-in-depth:
// the DOM client must not be compromisable by one bad frame.
//
// TWO NON-OBVIOUS THINGS, both fail-closed:
//   ① json-schema-to-zod (v2) does NOT resolve `$ref` — it silently emits `z.any()`, i.e. a validator that
//      accepts everything. That is worse than no validation, because it LOOKS validated. So refs are
//      dereferenced here, and a guard below rejects any bare `z.any()` that survives into the output.
//   ② `additionalProperties` is absent throughout the :core schema, so the emitted objects tolerate UNKNOWN keys
//      (`.catchall(z.any())`). That is deliberate and load-bearing: the client reads only typed fields, and a
//      strict schema would turn a benign server-side field addition into a client-wide outage. Fail-closed must
//      not mean fail-brittle — we validate the shape we depend on, not the shape we happen to know today.
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { jsonSchemaToZod } from 'json-schema-to-zod'
import { buildContractRootSchema, restResponseRoots } from './contractSchema.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..')
const OUT = resolve(root, 'src/types/generated/contractSchemas.ts')

// The complete set of UNTRUSTED server->client frame roots. Every WS ingress in src/net must validate against one
// of these (see the wiring guard in src/net/wsValidation.ts + its test). Adding a channel means adding it here.
const FRAME_ROOTS = [
  'CommWsServerEvent', // /ws/comm
  'EventsWsServerEvent', // /ws/events
  'TerminalServerFrame', // /ws/terminal
  'AgentRunStateEvent', // /ws/lifecycle
  'AgentTokenUsageEvent', // /ws/token-usage
  'AgentBusyStateEvent', // /ws/busy-state
  'AgentTerminalControlEvent', // /ws/terminal-state
  'StoredAgentEvent', // the agent event socket
]

const { rootSchema, isProvisional } = buildContractRootSchema()
const defs = rootSchema.definitions

// --- dereference (①) -----------------------------------------------------------------------------------------
// Inline `$ref: '#/definitions/X'`. A cycle would recurse forever, so it THROWS rather than degrading to z.any():
// a contract we cannot fully express must fail the build, not silently stop validating.
const deref = (node, stack) => {
  if (Array.isArray(node)) return node.map((n) => deref(n, stack))
  if (node === null || typeof node !== 'object') return node
  if (typeof node.$ref === 'string') {
    const name = node.$ref.replace('#/definitions/', '')
    if (stack.includes(name)) {
      throw new Error(`[CYP-420] cyclic $ref through ${name} (${stack.join(' -> ')}) — cannot emit a total schema. Fail-closed.`)
    }
    const target = defs[name]
    if (target === undefined) throw new Error(`[CYP-420] unresolved $ref ${node.$ref} — fail-closed.`)
    return deref(target, [...stack, name])
  }
  return Object.fromEntries(Object.entries(node).map(([k, v]) => [k, deref(v, stack)]))
}

// CYP-737 — the REST response roots, DERIVED from the openapi paths (not hand-listed): 67 operations would rot a
// manual list, and the rot is silent — a new endpoint simply goes unvalidated while coverage still looks complete.
// An inline (unnamed) response schema cannot become a named root; those are REPORTED rather than dropped, so the
// gap stays visible instead of becoming an invisible hole in the coverage story.
const { roots: REST_ROOTS, inlineOps } = restResponseRoots()
if (inlineOps.length > 0) {
  console.warn(
    `[CYP-737] ⚠  ${inlineOps.length} REST operation(s) return an INLINE (unnamed) schema and therefore get no ` +
      `generated validator: ${inlineOps.join(', ')}. Give the response a named :core DTO to bring it under validation.`,
  )
}
// WS roots first (stable output order), then the REST roots not already covered by a WS frame.
const ALL_ROOTS = [...FRAME_ROOTS, ...REST_ROOTS.filter((n) => !FRAME_ROOTS.includes(n))]

const chunks = []
for (const name of ALL_ROOTS) {
  const schema = defs[name]
  if (schema === undefined) throw new Error(`[CYP-420/737] root ${name} missing from the contract — fail-closed.`)
  const body = jsonSchemaToZod(deref(schema, [name]), { name: `${name}Schema`, module: 'esm', type: false })
  // strip the per-schema import line; one shared import goes in the banner
  chunks.push(body.replace(/^import \{ z \} from "zod"\s*/m, '').trim())
}

const generated = chunks.join('\n\n')

// --- guards (fail-closed; a vacuous validator must never ship) ------------------------------------------------
// ① no `$ref` may survive into the emitted output. THIS is the degradation signal: json-schema-to-zod turns an
//    unresolved ref into `z.any()`, i.e. a validator that accepts anything while looking validated. `deref` above
//    already throws on unresolved/cyclic refs; this is the belt-and-braces check on the actual emitted text.
//
//    NOTE — measured, and deliberately NOT flagged: the output legitimately contains bare `z.any()` for the
//    free-form fields the contract itself leaves untyped (`content`, `input`, `usage`, `tools`,
//    `rate_limit_info` — kotlinx JsonElement carrying Claude-Code stream-json payloads). An earlier version of
//    this guard rejected ALL bare `z.any()` and fired on exactly those. That would have been a false positive:
//    the contract has no shape to enforce there.
//    SCOPE BOUNDARY (important, do not overclaim): zod validates the ENVELOPE — frame type, required fields,
//    field types, discriminants. It does NOT and cannot constrain those free-form payloads. Safety for the
//    RENDERED content of tool output is a separate defense (sanitizer allowlist / no innerHTML / CSP, Spec §7),
//    not this ticket. Runtime validation here must not be mistaken for output-encoding safety.
if (/\$ref/.test(generated)) {
  throw new Error('[CYP-420] a `$ref` survived into the emitted schema — it did not dereference and would validate nothing. Fail-closed.')
}
// ①b COUNT-PIN (Assist2 F3): the contract's genuinely untyped free-form fields are a FIXED, known set —
// content, input, usage, tools, rate_limit_info (and their duplicates across union members). Pinning the exact
// count catches BOTH a $ref that silently degraded to `z.any()` AND the free-form set quietly GROWING (a new
// untyped field is a real reduction in validation coverage and must be a conscious, reviewed change — not a
// silent one). Raising this number is allowed; doing it without noticing is not.
// CYP-737: raised 8 → 9, deliberately and after identifying the source (the guard's whole purpose is to force
// that identification rather than a reflexive bump). The 9th is `EventPage`, whose embedded `EventSurrogate.detail`
// is the SAME contract-untyped free-form field already accepted on the WS side via `EventsWsServerEvent` — now
// also reachable through the REST paging response. No NEW untyped field entered the contract, and no `$ref`
// stopped dereferencing; the identical field simply became reachable by a second route.
const BARE_ANY_EXPECTED = 9
const bareAnyCount = (generated.replace(/\.catchall\(z\.any\(\)\)/g, '').match(/z\.any\(\)/g) ?? []).length
if (bareAnyCount !== BARE_ANY_EXPECTED) {
  throw new Error(
    `[CYP-420] bare z.any() count is ${bareAnyCount}, expected ${BARE_ANY_EXPECTED}. Either a $ref stopped ` +
      'dereferencing (validation silently lost) or the contract gained/lost an untyped free-form field. Review the ' +
      'diff, then update BARE_ANY_EXPECTED deliberately. Fail-closed.',
  )
}
// ② every frame root actually emitted an export
const missing = ALL_ROOTS.filter((n) => !generated.includes(`export const ${n}Schema`))
if (missing.length > 0) throw new Error(`[CYP-420] no schema emitted for: ${missing.join(', ')} — fail-closed.`)
// ③ the discriminated unions kept their `type` literals — same tooth as the TS generator (CYP-399). Without the
//    literal the union would accept any member shape for any `type`.
for (const [unionName, wires] of Object.entries({
  CommWsServerEvent: (defs.CommWsServerEvent?.oneOf ?? []).length,
  EventsWsServerEvent: (defs.EventsWsServerEvent?.oneOf ?? []).length,
  TerminalServerFrame: (defs.TerminalServerFrame?.oneOf ?? []).length,
})) {
  if (wires === 0) throw new Error(`[CYP-420] ${unionName} is not a union in the contract — unexpected shape, fail-closed.`)
}
const literalCount = (generated.match(/z\.literal\(/g) ?? []).length
if (literalCount === 0) throw new Error('[CYP-420] no z.literal() discriminants survived codegen — unions are not discriminated. Fail-closed.')

const banner = `/**
 * AUTO-GENERATED (CYP-420) — DO NOT EDIT. Run \`npm run zod:gen\`.
 * Source: ${isProvisional ? 'contract/asyncapi.provisional.json (PROVISIONAL)' : 'contract/asyncapi.json (:core build-export)'}
 * Runtime validators for the UNTRUSTED server->client WS frames, from the same contract as the TS types.
 * Objects tolerate unknown keys by design (forward-compatible); required fields + types are enforced.
 */
import { z } from 'zod'
`

mkdirSync(dirname(OUT), { recursive: true })
writeFileSync(OUT, `${banner}\n${generated}\n`, 'utf8')
console.log(`[CYP-420] wrote ${OUT} (${FRAME_ROOTS.length} frame roots, ${literalCount} discriminant literal(s))`)
