// CYP-420 — the untrusted-frame validation boundary. Every server->client WS frame is runtime-validated against
// the zod schema generated from the SAME :core contract as the TS types (src/types/generated/contractSchemas.ts).
//
// WHY: TS types are erased at runtime, so `JSON.parse(data) as T` was an unchecked cast — a malformed or hostile
// frame became a "typed" object and flowed into the store. The server is source-of-truth; this is defense-in-depth.
//
// FAIL-CLOSED, BUT NOT FAIL-BRITTLE — the three deliberate choices:
//   ① an invalid frame is DROPPED, never delivered. It is never rendered, never merged into the store.
//   ② dropping is per-FRAME, not per-connection: one bad frame must not tear down a live channel (that would turn
//      a malformed frame into a denial of service). The transport catches, drops, reports, and keeps reading.
//   ③ the report NEVER includes the frame payload or any field VALUE — only the schema name and the issue
//      paths/codes. WS frames carry tokens, transcript content and other secrets; a "helpful" debug log echoing
//      the frame would defeat the server-side masking (spec §7.2). Paths tell you WHICH field was wrong; they
//      never tell you what was in it.
import type { ZodType } from 'zod'

/** What a dropped frame reports: enough to debug the SHAPE, never enough to leak the CONTENT. */
export interface FrameRejection {
  /** the schema/channel the frame failed against, e.g. 'CommWsServerEvent' */
  schema: string
  /** dot-paths of the failing fields + the zod issue code — NO values, NO messages that could echo values */
  issues: readonly string[]
}

export class FrameValidationError extends Error {
  readonly rejection: FrameRejection
  constructor(rejection: FrameRejection) {
    super(`invalid ${rejection.schema} frame: ${rejection.issues.join(', ')}`)
    this.name = 'FrameValidationError'
    this.rejection = rejection
  }
}

/** Build the `validate` hook for one channel. Throws FrameValidationError on a schema violation; the transport
 *  catches it and drops the frame (②). Returns the parsed value on success. */
export function makeFrameValidator<T>(schema: string, zodSchema: ZodType<T>): (raw: unknown) => T {
  return (raw: unknown): T => {
    const result = zodSchema.safeParse(raw)
    if (result.success) return result.data
    // ③ path + code ONLY. `issue.message` is deliberately not used — for some codes zod embeds the received
    // value in it, which is exactly the leak this boundary must not create.
    const issues = result.error.issues.map((i) => `${i.path.length > 0 ? i.path.join('.') : '<root>'}:${i.code}`)
    throw new FrameValidationError({ schema, issues })
  }
}

/** Where dropped frames are reported. Default warns WITHOUT the payload (③); tests/telemetry can swap it. */
let onFrameRejected: (r: FrameRejection) => void = (r) => {
  console.warn(`[CYP-420] dropped an invalid ${r.schema} frame (fields: ${r.issues.join(', ')})`)
}

export function setOnFrameRejected(handler: ((r: FrameRejection) => void) | null): void {
  onFrameRejected = handler ?? (() => undefined)
}

/** Transport helper: run `deliver` only if validation passed; otherwise drop + report, and keep the channel alive.
 *  Any non-validation error is re-thrown — we only swallow what we deliberately handle. */
export function deliverIfValid<T>(validate: (raw: unknown) => T, raw: unknown, deliver: (value: T) => void): void {
  let value: T
  try {
    value = validate(raw)
  } catch (e) {
    if (e instanceof FrameValidationError) {
      onFrameRejected(e.rejection) // ① dropped: `deliver` is NOT called
      return
    }
    throw e
  }
  deliver(value)
}
