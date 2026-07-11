// CYP-405 (W7) — the real PTY shell in the browser: xterm.js bound to the existing /ws/terminal transport
// (terminalSocket, W2). Output frames → term.write(Uint8Array); term.onData → TerminalInput; FitAddon + resize →
// TerminalResize. Everything disposes on UNMOUNT (= window/shell close); a hidden-but-mounted view keeps the
// socket + PTY alive (hidden != closed, §4.2) — the W8 toggle relies on that, so teardown lives here at unmount.
//
// Not unit-tested: xterm needs real DOM measurement/canvas (jsdom can't); the transport wiring is exercised in QA.
// The pure parts (base64) and the gate/warning flow (ShellGate) are unit-tested.
import { useEffect, useRef } from 'react'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { terminalSocket } from '../net/channels'
import { base64ToBytes, stringToBase64 } from './base64'

export function XtermView({ baseUrl, agentId, token }: { baseUrl: string; agentId: string; token: string }) {
  const hostRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const host = hostRef.current
    if (host === null) return

    const term = new Terminal({ convertEol: false, fontFamily: 'ui-monospace, monospace', scrollback: 5000 })
    const fit = new FitAddon()
    term.loadAddon(fit)
    term.open(host)
    fit.fit()

    const socket = terminalSocket({
      baseUrl,
      agentId,
      token,
      onEvent: (frame) => {
        if (frame.type === 'output') term.write(base64ToBytes(frame.dataBase64))
        else if (frame.type === 'exit') term.write(`\r\n\x1b[2m[Sitzung beendet: ${frame.code}]\x1b[0m\r\n`)
      },
    })
    socket.start()

    const dataSub = term.onData((d) => socket.send({ type: 'input', dataBase64: stringToBase64(d) }))
    const sendResize = () => socket.send({ type: 'resize', cols: term.cols, rows: term.rows })
    const resizeSub = term.onResize(sendResize)
    sendResize() // announce the initial size

    const onWindowResize = () => fit.fit()
    window.addEventListener('resize', onWindowResize)

    return () => {
      window.removeEventListener('resize', onWindowResize)
      dataSub.dispose()
      resizeSub.dispose()
      socket.close()
      term.dispose()
    }
  }, [baseUrl, agentId, token])

  return <div className="xterm-host" ref={hostRef} data-testid="xterm-host" />
}
