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
}

const CONNECTION_TEXT: Record<CommPanelProps['connection'], string> = {
  live: 'Verbunden',
  connecting: 'Verbinde…',
  offline: 'Offline — Neuverbindung…',
  revoked: 'Zugriff entzogen',
}

export function CommPanel(props: CommPanelProps) {
  const { channels, selectedChannelId, onSelectChannel, messages, senderRole, connection } = props
  const disclosure = composerDisclosure(props.canWrite, props.sendError)
  const tail = messages.length === 0 ? '' : `${messages.length}|${messages[messages.length - 1].id}`
  const { ref, onScroll } = useAutoscrollPin(tail)

  return (
    <div className="comm-panel" data-testid="comm-panel">
      <nav className="comm-channels" aria-label="Kanäle" data-testid="comm-channels">
        {channels.map((ch) => (
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
            <p className="comm-empty" data-testid="comm-empty">
              Noch keine Nachrichten.
            </p>
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

        {disclosure === 'readonly' ? (
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
