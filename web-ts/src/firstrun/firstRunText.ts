// CYP-735 §3.2 — first-run copy, taken VERBATIM from the CMP shared strings
// (`app/shared/src/commonMain/composeResources/values/strings.xml`, keys `first_run_*` / `workspace_unconfigured_*`).
//
// Copied rather than paraphrased on purpose. web-ts has no i18n layer yet, so these are inline German like the rest
// of the app — but the WORDS are the shared ones, byte-for-byte, so the two strands say the same thing to the same
// user. §3.1 shipped a paraphrase from the spec prose instead of the real string ("Hub nicht eingerichtet …" vs the
// shared "Hub noch nicht eingerichtet … In den Projekt-Einstellungen einrichten.") — corrected here, which is the
// whole point of a shared key: the copy has ONE source, and a near-miss is still a miss.
//
// Key ↔ constant mapping is spelled out so the next person can diff the two strands mechanically.

/** `first_run_title` */
export const FIRST_RUN_TITLE = 'Hub einrichten'
/** `first_run_intro` */
export const FIRST_RUN_INTRO =
  'Dein Hub ist installiert und läuft — aber noch nicht eingerichtet. Richte den API-Key und das Repository ein, dann kann dein Team arbeiten.'
/** `first_run_degraded_note` */
export const FIRST_RUN_DEGRADED_NOTE =
  'Das ist bei einem frischen Hub normal: er läuft bereits, kann aber noch keine Agenten starten, bis Key und Repository gesetzt sind.'
/** `first_run_skip` */
export const FIRST_RUN_SKIP = 'Später einrichten'
/** `first_run_skip_note` */
export const FIRST_RUN_SKIP_NOTE =
  'Du kannst das jederzeit in den Projekt-Einstellungen nachholen. Bis dahin läuft der Hub, kann aber keine Agenten starten.'

/** `first_run_step_apikey` / `_repo` / `_team` */
export const FIRST_RUN_STEP = {
  apikey: 'API-Key',
  repo: 'Repository',
  team: 'Team',
} as const

/** `first_run_apikey_saved` */
export const FIRST_RUN_APIKEY_SAVED = 'Gespeichert. Der Key wird beim ersten Start deiner Agenten verwendet.'
/** `first_run_repo_saved` — note it says the hub is cloning NOW, i.e. saved is explicitly NOT "done". */
export const FIRST_RUN_REPO_SAVED = 'Repository gesetzt. Der Hub klont es jetzt.'

/** `workspace_unconfigured_banner` — the shared wording, including WHERE to fix it. */
export const WORKSPACE_UNCONFIGURED_BANNER =
  'Hub noch nicht eingerichtet — Agenten können nicht starten. In den Projekt-Einstellungen einrichten.'
/** `workspace_unconfigured_chip` */
export const WORKSPACE_UNCONFIGURED_CHIP = 'Nicht eingerichtet'

/** Per-step status labels. `saved` is deliberately worded as a middle state, never as completion. */
export const STEP_STATE_LABEL = {
  open: 'Offen',
  saved: 'Gespeichert',
  done: 'Erledigt',
} as const

// ── CYP-735 §3.3 clone lifecycle (shared CMP keys where they exist; §1/§6 copy from the screen-spec) ──────────
/** `first_run_repo_cloning` */
export const CLONE_CLONING = 'Repository wird geklont …'
/** `first_run_repo_cloning_slow` (~15s) — advisory, still cloning. */
export const CLONE_SLOW_HINT = 'Das dauert länger als üblich — große Repositories brauchen Zeit.'
/** `first_run_repo_cloning_long` (~60s) — shows the measured duration; never a verdict. */
export const cloneLongHint = (elapsedMs: number): string =>
  `Klont noch — dauert schon ${Math.max(1, Math.floor(elapsedMs / 60_000))} min. Große Repositories können lange ` +
  'brauchen; du kannst warten oder später zurückkommen.'
/** `first_run_repo_clone_ok` */
export const CLONE_OK = 'Repository geklont.'
/** Config accepted, clone still outstanding — explicitly NOT "it works". */
export const CLONE_NEVER = 'Gespeichert — Klonen steht aus.'
/** Status not determinable (loading / old server / failed read) — never a quiet OK. */
export const CLONE_UNKNOWN = 'Status unbekannt.'
/** Distinct failure reasons, each naming its own fix; UNKNOWN stays honestly undetermined. */
export const CLONE_FAILED_TEXT = {
  URL_UNREACHABLE: 'Repository nicht erreichbar — URL prüfen.',
  AUTH: 'Zugang abgelehnt — Token/SSH-Schlüssel am Hub-Host prüfen.',
  UNKNOWN: 'Klonen fehlgeschlagen — Grund nicht ermittelbar.',
} as const
export const CLONE_RETRY = 'Erneut versuchen'
