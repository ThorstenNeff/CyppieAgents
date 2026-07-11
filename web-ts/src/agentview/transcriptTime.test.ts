import { describe, it, expect } from 'vitest'
import { formatHhMm } from './transcriptTime'

describe('formatHhMm', () => {
  it('formats epoch-ms + east offset as zero-padded HH:mm', () => {
    expect(formatHhMm(0, 0)).toBe('00:00')
    expect(formatHhMm(0, 7_200_000)).toBe('02:00') // Berlin summer, +2h
    expect(formatHhMm(3_661_000, 0)).toBe('01:01') // 1h 1m 1s
    expect(formatHhMm(5 * 60_000, 0)).toBe('00:05')
  })

  it('floor-mods across midnight for a negative offset instead of a negative hour', () => {
    expect(formatHhMm(0, -60_000)).toBe('23:59')
    expect(formatHhMm(0, -3_600_000)).toBe('23:00')
  })
})
