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

export type MaritimeScheme = Record<MaritimeRole, string>

export declare const MARITIME_TOKENS: { light: MaritimeScheme; dark: MaritimeScheme }
