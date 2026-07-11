// CYP-403 (W5) — TS port of :app:shared's ComposerRecall (CYP-387 §2). The ↑/↓ recall cursor over an immutable,
// newest-last history snapshot. Pure & deterministic: the composer owns one instance, passes the current history
// + live draft, renders the returned draft. The history is NEVER mutated here — a recall-edit is a transient
// working copy the caller holds; the next ↑/↓ reads the ORIGINAL neighbour. older/newer return the draft to SHOW,
// or null when the arrow is NOT consumed (empty history on ↑, or already at the live draft on ↓).
export class ComposerRecall {
  /** null = at the live draft; else an index into the history passed to older/newer. */
  private navIndex: number | null = null
  /** the live draft, captured on ENTRY to history so ↓ past the newest restores it. */
  private stash = ''

  /** Exposed for tests: the current cursor (null = at the live draft). */
  index(): number | null {
    return this.navIndex
  }

  /** ↑ (older). Enters history (stashing draft) from the live draft, else steps toward older entries. */
  older(history: readonly string[], draft: string): string | null {
    if (history.length === 0) return null
    const idx = this.navIndex === null ? null : Math.min(this.navIndex, history.length - 1)
    if (idx === null) {
      this.stash = draft
      this.navIndex = history.length - 1
      return history[history.length - 1]
    }
    if (idx > 0) {
      this.navIndex = idx - 1
      return history[idx - 1]
    }
    this.navIndex = 0
    return draft // at the oldest: no move, but consumed
  }

  /** ↓ (newer). Steps toward newer entries, or leaves history at the newest — restoring the stashed draft. */
  newer(history: readonly string[], draft: string): string | null {
    void draft // symmetry with older(); the restored value is the stash, not the current draft
    if (this.navIndex === null) return null
    if (history.length === 0) {
      this.navIndex = null
      return this.stash
    }
    const idx = Math.min(this.navIndex, history.length - 1)
    if (idx < history.length - 1) {
      this.navIndex = idx + 1
      return history[idx + 1]
    }
    this.navIndex = null
    return this.stash
  }

  /** After a send (the draft was captured as a new entry by the store): leave history, clear the stash. */
  reset(): void {
    this.navIndex = null
    this.stash = ''
  }
}
