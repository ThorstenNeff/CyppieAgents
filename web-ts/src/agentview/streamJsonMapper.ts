// CYP-401 (W3) — maps the generated wire events (StreamJsonEvent, W1) to UI rows (AgentEvent). A faithful TS port
// of :app:shared's StreamJsonMapper (agentview/StreamJsonMapper.kt). Stateful BY DESIGN — it remembers each
// tool_use by id so the later tool_result (which carries only tool_use_id, not the tool name) can resolve the
// ToolCall row RUNNING → OK/ERROR. One instance per stream/collection. The behavioral golden corpus
// (streamJsonMapper.test.ts) pins this against the Kotlin mapper's semantics.
//
// Masking: events are expected ALREADY masked by the server mediator; the summaries here are presentation-only
// and additionally truncate.
// CYP-412 (A): the generated contract names union members by @SerialName (System/Assistant/User/Result), not by
// the Kotlin class name — import the real names.
import type { StreamJsonEvent, ContentBlock, Assistant, User, Result, System } from '../types/generated/contract'
import type { AgentEvent, ToolCallRow } from './agentEvent'

const SUMMARY_MAX = 80
const RESULT_MAX = 120
const PREFERRED_KEYS = ['command', 'file_path', 'path', 'pattern', 'query', 'url', 'description']

export class StreamJsonMapper {
  private readonly toolCalls = new Map<string, ToolCallRow>()
  private readonly readySessions = new Set<string>()
  private seq = 0

  /** @param readyNoticeText localized "agent ready" label; the mapper holds no user-facing literal (CYP-383). */
  constructor(private readonly readyNoticeText: string) {}

  /** [tsMs] is the server stamp (StoredAgentEvent.tsMs) — every row this event produces is dated by it (CYP-335). */
  map(event: StreamJsonEvent, tsMs: number): AgentEvent[] {
    switch (event.type) {
      case 'system':
        return this.mapSystem(event, tsMs)
      case 'rate_limit_event':
        return [] // observability/spend signal — never part of the transcript
      case 'assistant':
        return (event.message.content ?? []).flatMap((b) => this.mapAssistantBlock(event, b, tsMs))
      case 'user':
        return (event.message.content ?? []).flatMap((b) => this.mapUserBlock(event, b, tsMs))
      case 'result':
        return this.mapResult(event, tsMs)
    }
  }

  private mapSystem(event: System, tsMs: number): AgentEvent[] {
    // CYP-383: fire the "ready" Notice on the FIRST SystemEvent of a session with a non-blank session_id, exactly
    // once per session — a later same-session event (e.g. a subtype:"status" compaction) never re-fires.
    const sid = event.session_id
    if (sid !== null && sid !== undefined && sid.trim() !== '' && !this.readySessions.has(sid)) {
      this.readySessions.add(sid)
      return [{ kind: 'notice', id: this.idOf(event.uuid), text: this.systemNotice(event), tsMs }]
    }
    return []
  }

  private mapAssistantBlock(event: Assistant, block: ContentBlock, tsMs: number): AgentEvent[] {
    switch (block.type) {
      case 'text':
        return [
          {
            kind: 'assistantText',
            id: this.idOf(event.message.id ?? event.uuid),
            text: block.text,
            // Partial-messages OFF (MVP): a turn arrives complete, so stop_reason is set.
            complete: event.message.stop_reason !== null && event.message.stop_reason !== undefined,
            tsMs,
          },
        ]
      case 'thinking':
        return [] // no `thinking` row in the MVP vocabulary
      case 'tool_use': {
        const call: ToolCallRow = {
          kind: 'toolCall',
          id: block.id,
          tool: block.name,
          summary: summarizeToolInput(block.input),
          status: 'running',
          tsMs,
        }
        this.toolCalls.set(block.id, call)
        return [call]
      }
      case 'tool_result':
        return [] // tool_result normally arrives in a user event
    }
  }

  private mapUserBlock(event: User, block: ContentBlock, tsMs: number): AgentEvent[] {
    switch (block.type) {
      case 'tool_result': {
        const out: AgentEvent[] = []
        const toolId = block.tool_use_id ?? undefined
        const prior = toolId !== undefined ? this.toolCalls.get(toolId) : undefined
        if (prior !== undefined && toolId !== undefined) {
          // Spread from `prior` carries the tool call's START time forward (CYP-335).
          const resolved: ToolCallRow = { ...prior, status: block.is_error ? 'error' : 'ok' }
          this.toolCalls.set(toolId, resolved)
          out.push(resolved) // same id → foldEvent updates the tool-call row in place
        }
        out.push({
          kind: 'result',
          id: 'result-' + this.idOf(toolId ?? event.uuid),
          label: summarizeResult(block.content),
          isError: block.is_error ?? false, // wire omits it → Kotlin default false
          tsMs, // the result row is dated by its own arrival
        })
        return out
      }
      case 'text':
        // CYP-326 #1: a PLATFORM-injected incoming message → an IncomingSystem row so the operator sees the
        // trigger. A plain replayed user echo (injected_source == null) stays dropped (no composer double-echo).
        return event.injected_source !== null && event.injected_source !== undefined
          ? [{ kind: 'incomingSystem', id: this.idOf(event.uuid), text: block.text, tsMs }]
          : []
      default:
        return []
    }
  }

  private mapResult(event: Result, tsMs: number): AgentEvent[] {
    const isSuccess = !event.is_error && (event.subtype === null || event.subtype === undefined || event.subtype === 'success')
    if (isSuccess) return [] // the assistant text already showed the turn; result.result duplicates it
    const suffix = event.subtype !== null && event.subtype !== undefined ? `: ${event.subtype}` : ''
    return [{ kind: 'notice', id: this.idOf(event.uuid), text: `Turn-Fehler${suffix}`, tsMs }]
  }

  // CYP-383: the ready label is injected (localized by the caller); only the model suffix is composed here.
  private systemNotice(event: System): string {
    return this.readyNoticeText + (event.model !== null && event.model !== undefined ? ` · ${event.model}` : '')
  }

  /** Stable id, falling back to a deterministic per-mapper sequence when the wire id is absent. */
  private idOf(id: string | null | undefined): string {
    return id ?? `ev-${this.seq++}`
  }
}

// --- summary helpers (module-private; mirror the Kotlin companion) ------------------------------------------
function summarizeToolInput(input: unknown): string {
  const obj = typeof input === 'object' && input !== null && !Array.isArray(input) ? (input as Record<string, unknown>) : {}
  const key = PREFERRED_KEYS.find((k) => k in obj) ?? Object.keys(obj)[0]
  return truncate(scalar(key !== undefined ? obj[key] : undefined), SUMMARY_MAX)
}

function summarizeResult(content: unknown): string {
  return truncate(scalar(content), RESULT_MAX)
}

function scalar(element: unknown): string {
  if (element === null || element === undefined) return ''
  if (typeof element === 'string') return element
  if (typeof element === 'number' || typeof element === 'boolean') return String(element)
  return JSON.stringify(element)
}

function truncate(s: string, max: number): string {
  const flat = s.replace(/\n/g, ' ').trim()
  return flat.length <= max ? flat : flat.slice(0, max - 1) + '…'
}
