// CYP-423 — type declaration for the .mjs token source (so TS consumers get a typed, exhaustive role map).
export type MaritimeRole =
  | 'primary'
  | 'onPrimary'
  | 'primaryContainer'
  | 'onPrimaryContainer'
  | 'secondary'
  | 'onSecondary'
  | 'secondaryContainer'
  | 'onSecondaryContainer'
  | 'tertiary'
  | 'onTertiary'
  | 'tertiaryContainer'
  | 'onTertiaryContainer'
  | 'error'
  | 'onError'
  | 'errorContainer'
  | 'onErrorContainer'
  | 'background'
  | 'onBackground'
  | 'surface'
  | 'onSurface'
  | 'surfaceVariant'
  | 'onSurfaceVariant'
  | 'outline'
  | 'outlineVariant'
  | 'warnContainer'
  | 'onWarnContainer'

export type MaritimeScheme = Record<MaritimeRole, string>

export declare const MARITIME_TOKENS: { light: MaritimeScheme; dark: MaritimeScheme }

// CYP-468 (F2) — the event-log severity palette (CYP-274), single-sourced here and emitted as `--event-sev-*`.
export type EventSeverityKey = 'error' | 'warn' | 'info' | 'debug'
export type EventSeverityScheme = Record<EventSeverityKey, string>
export declare const EVENT_SEVERITY: { light: EventSeverityScheme; dark: EventSeverityScheme }
