// CYP-405 (W7) — standard Base64 <-> raw bytes for the /ws/terminal PTY frames (TerminalModel.kt: dataBase64 is
// standard Base64 of the raw byte run; the payload is VT/ANSI, not text, so it must round-trip as BYTES). xterm's
// term.write accepts a Uint8Array, and term.onData gives a (UTF-8) keystroke string.
export function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i])
  return btoa(binary)
}

export function base64ToBytes(b64: string): Uint8Array {
  const binary = atob(b64)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i)
  return out
}

/** A keystroke string → Base64 of its UTF-8 bytes (for TerminalInput.dataBase64). */
export function stringToBase64(s: string): string {
  return bytesToBase64(new TextEncoder().encode(s))
}
