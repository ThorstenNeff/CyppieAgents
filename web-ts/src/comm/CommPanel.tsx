// CYP-407 (W9 part 2) — the Comm panel: channel list + timeline + composer (Spec §W9.1). Port of CommPanel.kt.
// Channels are SERVER-filtered (only ACL-readable arrive) → rendered as-is, never client-filtered (a client
// filter would lie about access). Timeline reuses the W6 autoscroll/retention; composer reuses the W5 input
// history. Disclosure is the three distinct states (commDisclosure); sender identity via the contrast-safe accent
// plus text (colour never alone).
import { formatLocalHhMm } from '../agentview/transcriptTime'
import { useAutoscrollPin } from '../agentview/useAutoscrollPin'
import { Composer } from '../agentview/Composer'
import { senderAccent } from './senderAccent'
import { composerDisclosure } from './commDisclosure'
import { LoadErrorRetry } from '../ui/LoadErrorRetry'
import type { Channel, Message1 } from '../types/generated/contract'

export interface CommPanelProps {
  channels: readonly Channel[]
  selectedChannelId: string | null
  onSelectChannel: (id: string) => void
  messages: readonly Message1[]
  senderRole: (agentId: string) => string | null
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
  // CYP-437(#4): a terminal revoke (WS 1008) closes the write affordance entirely — don't leave a composer that
  // only fails server-side. This overrides the disclosure (a revoked socket can't write, whatever canWrite said).
  const revoked = connection === 'revoked'
  const disclosure = composerDisclosure(props.canWrite, props.sendError)
  const tail = messages.length === 0 ? '' : `${messages.length}|${messages[messages.length - 1].id}`
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
              {messages.map((m) => (
                <li key={m.id} className="comm-message" data-testid={`comm.message.${m.id}`}>
                  <time>{formatLocalHhMm(m.ts)}</time>
                  <span className="comm-from" style={{ color: senderAccent(m.from, senderRole(m.from)) }}>
                    {m.from}
                  </span>
                  <span className="comm-body">{m.body}</span>
                </li>
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
