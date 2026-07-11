// CYP-401 (W3) — the transcript time seam, ported from :app:shared (agentview/TranscriptTime.kt, CYP-335/336).
// The pure formatter (epoch-ms + offset → HH:mm) is unit-tested directly; the local-offset lookup is the one
// platform bit. Offset is PER INSTANT (DST-correct): `new Date(tsMs).getTimezoneOffset()` gives minutes BEHIND
// UTC, so east-of-UTC (Berlin summer) is negated to a positive offset — matching the Kotlin contract.
const DAY_MS = 86_400_000

/**
 * Pure: the 24-hour zero-padded HH:mm of [tsMs] as seen from a zone [offsetMs] east of UTC. Floor-mod on the
 * day, so a negative offset (or pre-1970 tsMs) wrapping past midnight shows the previous day's clock, never a
 * negative hour.
 */
export function formatHhMm(tsMs: number, offsetMs: number): string {
  const dayMs = (((tsMs + offsetMs) % DAY_MS) + DAY_MS) % DAY_MS
  const hours = Math.floor(dayMs / 3_600_000)
  const minutes = Math.floor(dayMs / 60_000) % 60
  return `${pad2(hours)}:${pad2(minutes)}`
}

/** [formatHhMm] in the browser's local zone, DST-correct for the given instant. */
export function formatLocalHhMm(tsMs: number): string {
  const offsetMs = -new Date(tsMs).getTimezoneOffset() * 60_000
  return formatHhMm(tsMs, offsetMs)
}

function pad2(value: number): string {
  return value.toString().padStart(2, '0')
}
