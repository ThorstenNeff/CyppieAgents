// CYP-407 (W9 part 2) — the Comm panel: channel list + timeline + composer (Spec §W9.1). Port of CommPanel.kt.
// Channels are SERVER-filtered (only ACL-readable arrive) → rendered as-is, never client-filtered (a client
// filter would lie about access). Timeline reuses the W6 autoscroll/retention; composer reuses the W5 input
// history. Disclosure is the three distinct states (commDisclosure); sender identity via the contrast-safe accent
// plus text (colour never alone).
import { Fragment } from 'react'
import { formatLocalHhMm } from '../agentview/transcriptTime'
import { useAutoscrollPin } from '../agentview/useAutoscrollPin'
import { Composer } from '../agentview/Composer'
import { senderAccent } from './senderAccent'
import { composerDisclosure } from './commDisclosure'
import { LoadErrorRetry } from '../ui/LoadErrorRetry'
import { applyMentionSpans } from './mentionSpans'
import { channelUnread, READ_STATE_UNAVAILABLE, type UnreadView } from './unreadModel'
import { channelHasMention } from './mentionCue'
import type { Channel, DeliveredMessage } from '../types/generated/contract'

export interface CommPanelProps {
  channels: readonly Channel[]
  selectedChannelId: string | null
  onSelectChannel: (id: string) => void
  /** CYP-744: DeliveredMessage envelopes (stored message + server-resolved mention spans). Chips render from the
   *  spans; the client no longer resolves mentions from the body. */
  messages: readonly DeliveredMessage[]
  senderRole: (agentId: string) => string | null
  /** CYP-705: server read state. Default UNAVAILABLE → no badge and NO all-clear (unknown ≠ zero). */
  readState?: UnreadView
  /** CYP-705: index of the first unread message (from firstUnreadIndex). null/absent ⇒ no divider. */
  unreadDividerIndex?: number | null
  /** CYP-740: loaded envelopes per channel, for the channel-level @agent mention cue. Absent ⇒ no cue (silent). */
  messagesByChannel?: ReadonlyMap<string, readonly DeliveredMessage[]>
  connection: 'live' | 'connecting' | 'offline' | 'revoked'
  canWrite: boolean | null
  sendError: string | null
  onSend: (text: string) => void
  historySize: () => number
  // CYP-288: a failed INITIAL channel-list / history load surfaces error+retry, NOT an empty list (failed ≠ empty).
  // Gated on the data still being empty → live/WS data or a successful retry hides the error naturally.
  channelsLoadError?: boolean
  onRetryChannels?: () => void
  messagesLoadError?: boolean
  onRetryMessages?: () => void
}

const CONNECTION_TEXT: Record<CommPanelProps['connection'], string> = {
  live: 'Verbunden',
  connecting: 'Verbinde…',
  offline: 'Offline — Neuverbindung…',
  revoked: 'Zugriff entzogen',
}

export function CommPanel(props: CommPanelProps) {
  const { channels, selectedChannelId, onSelectChannel, messages, senderRole, connection } = props
  const { channelsLoadError = false, onRetryChannels, messagesLoadError = false, onRetryMessages } = props
  const { readState = READ_STATE_UNAVAILABLE, unreadDividerIndex = null, messagesByChannel } = props
  // CYP-437(#4): a terminal revoke (WS 1008) closes the write affordance entirely — don't leave a composer that
  // only fails server-side. This overrides the disclosure (a revoked socket can't write, whatever canWrite said).
  const revoked = connection === 'revoked'
  const disclosure = composerDisclosure(props.canWrite, props.sendError)
  const tail = messages.length === 0 ? '' : `${messages.length}|${messages[messages.length - 1].message.id}`
  const { ref, onScroll } = useAutoscrollPin(tail)

  return (
    <div className="comm-panel" data-testid="comm-panel">
      <nav className="comm-channels" aria-label="Kanäle" data-testid="comm-channels">
        {channels.length === 0
          ? // CYP-288: a failed channel-list load shows error+retry, not a silently-empty list. Genuinely-empty
            // (no error) stays as-is (an empty nav) — non-vacuum contrast.
            channelsLoadError && (
              <LoadErrorRetry testId="comm.channels.loadError" onRetry={onRetryChannels ?? (() => undefined)} />
            )
          : channels.map((ch) => (
              <button
                key={ch.id}
                type="button"
                className="comm-channel"
                aria-current={ch.id === selectedChannelId ? 'true' : undefined}
                data-testid={`comm.channel.${ch.id}`}
                onClick={() => onSelectChannel(ch.id)}
              >
                {ch.name}
                {/* CYP-705 — unread-of-record, THREE distinct states (spec 82e3680b §0/§3). UNKNOWN is rendered
                    VISIBLY as a neutral marker, never as absence: on a channel list silence reads as "all clear",
                    so staying quiet would be the lie rather than the caution. Neutral, not alarming — unknown is
                    undetermined, not an error. Only a server-CONFIRMED zero is legitimately silent. Colour is
                    never the sole carrier: each marker has text/glyph plus an aria-label. */}
                {/* CYP-740 — the @agent mention cue. PRESENT-ONLY and deliberately distinct from the CYP-705
                    unread badge (own testid, own glyph): both are channel-level cues but different facts, and
                    flattening them would make "has mentions" and "has unread" indistinguishable.
                    Absence is SILENT — never "no mentions", because an unopened channel has no loaded messages
                    and we would be reporting a gap as a result. */}
                {channelHasMention(messagesByChannel?.get(ch.id) ?? []) && (
                  <span
                    className="comm-mention-cue"
                    data-testid={`comm.channel.${ch.id}.mentionCue`}
                    aria-label={`Erwähnungen in ${ch.name}`}
                  >
                    @
                  </span>
                )}
                {(() => {
                  const unread = channelUnread(readState, ch.id)
                  if (unread.kind === 'read') return null // authoritative "you have read this" — honestly silent
                  if (unread.kind === 'unknown') {
                    return (
                      <span
                        className="comm-unread-unknown"
                        data-testid={`comm.channel.${ch.id}.unreadUnknown`}
                        aria-label="Ungelesen-Status unbekannt"
                      >
                        •
                      </span>
                    )
                  }
                  return (
                    <span
                      className="comm-unread"
                      data-testid={`comm.channel.${ch.id}.unreadBadge`}
                      aria-label={`${unread.count} ungelesen in ${ch.name}`}
                    >
                      {unread.count}
                    </span>
                  )
                })()}
              </button>
            ))}
      </nav>

      <section className="comm-conversation">
        {/* WARN-amber offline / errorContainer revoke — a terminal revoke must not read as a reconnectable blip. */}
        <div
          className={`comm-status comm-status-${connection}`}
          role="status"
          aria-live="polite"
          data-testid="comm-status"
        >
          {CONNECTION_TEXT[connection]}
        </div>

        <div className="comm-timeline transcript-scroll" ref={ref} onScroll={onScroll} data-testid="comm-timeline">
          {messages.length === 0 ? (
            // CYP-288: a failed history load shows error+retry (failed ≠ empty); a genuinely-empty channel keeps
            // the empty state — error BEATS empty, non-vacuum contrast.
            messagesLoadError ? (
              <LoadErrorRetry testId="comm.timeline.loadError" onRetry={onRetryMessages ?? (() => undefined)} />
            ) : (
              <p className="comm-empty" data-testid="comm-empty">
                Noch keine Nachrichten.
              </p>
            )
          ) : (
            <ol>
              {messages.map((d, i) => (
                <Fragment key={d.message.id}>
                  {/* CYP-705 — the "Neu" divider at the first-unread boundary. Rendered ONLY from a server cursor
                      (the index is computed by firstUnreadIndex); no cursor ⇒ no divider, never a guessed line. */}
                  {i === unreadDividerIndex && (
                    <li className="comm-unread-divider" role="separator" data-testid="comm.unread.divider">
                      Neu
                    </li>
                  )}
                <li className="comm-message" data-testid={`comm.message.${d.message.id}`}>
                  <time>{formatLocalHhMm(d.message.ts)}</time>
                  <span className="comm-from" style={{ color: senderAccent(d.message.from, senderRole(d.message.from)) }}>
                    {d.message.from}
                  </span>
                  <span className="comm-body">
                    {/* CYP-744 — mention chips from SERVER spans. The server resolved the mentions (fail-closed:
                        unknown token / email / code all yield no span, proven == the old client rule by the parity
                        oracle); the client only slices the body at those offsets. Segments are rendered as TEXT
                        NODES (never dangerouslySetInnerHTML — the CYP-456/W9 invariant holds, and applyMentionSpans
                        stays lossless even on a malformed span). The chip carries the sender's accent AND the literal
                        text, so colour is never the sole signal (WCAG 1.4.1); the text is the token VERBATIM as typed
                        (body.slice), never a rewritten id. */}
                    {applyMentionSpans(d.message.body, d.mentions ?? []).map((s, i) =>
                      s.kind === 'mention' ? (
                        <span
                          key={i}
                          className="comm-mention"
                          data-testid={`comm.mention.${i}`}
                          style={{ color: senderAccent(s.id, senderRole(s.id)) }}
                        >
                          {s.text}
                        </span>
                      ) : (
                        <span key={i}>{s.text}</span>
                      ),
                    )}
                  </span>
                </li>
                </Fragment>
              ))}
            </ol>
          )}
        </div>

        {revoked ? (
          <p className="comm-revoked-lock" data-testid="comm-revoked-lock">
            Zugriff entzogen — Senden ist gesperrt.
          </p>
        ) : disclosure === 'readonly' ? (
          <p className="comm-readonly-hint" data-testid="comm-readonly-hint">
            Nur Lesen — du darfst in diesem Kanal nicht antworten.
          </p>
        ) : (
          <div className="comm-composer">
            {disclosure === 'denied' && (
              <p className="comm-send-denied" data-testid="comm-send-denied">
                Senden abgelehnt (keine Schreibrechte in diesem Kanal).
              </p>
            )}
            {disclosure === 'failed' && (
              <p className="comm-send-failed" data-testid="comm-send-failed">
                Senden fehlgeschlagen. Bitte erneut versuchen.
              </p>
            )}
            <Composer onSend={props.onSend} historySize={props.historySize} />
          </div>
        )}
      </section>
    </div>
  )
}
