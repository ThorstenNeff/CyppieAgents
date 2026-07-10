#!/usr/bin/env bash
# CYP-352 — Boot-Smoke für das AUSGELIEFERTE Web-Artefakt.
#
# Warum es das gibt: `runComposeUiTest` (das Gate) komponiert die App IM TESTPROZESS. Es lädt nie das
# ausgelieferte Artefakt über HTTP. Ein fehlendes Asset, ein kaputtes index.html oder ein Wasm-Ladefehler
# bleiben dort unsichtbar. Das ist Zeile 1 der Verlustliste in docs/QA-WEB-TESTING-OPTIONS-CYP-352.md.
#
# Was es prüft — und mehr nicht:
#   1. `/` liefert 200 und referenziert ein `.js`-Bundle.
#   2. Jedes vom Dokument referenzierte Skript-Asset liefert 200.
#   3. Die Seite lädt in echtem Chrome ohne JS-Exception und ohne fehlendes Asset der EIGENEN Herkunft.
#
# Bewusst NICHT rot: fehlgeschlagene Aufrufe an das Backend (`ERR_CONNECTION_REFUSED` gegen den Hub-Port).
# Die Demo ist NICHT backendlos — gemessen: sie ruft /api/agents, /api/channels, /api/acl und drei WebSockets
# auf :8787 und degradiert sichtbar zu Fehlerzuständen. Das ist gewolltes Verhalten und darf den Boot-Smoke
# nicht rot machen; es wird gemeldet, nicht bestraft. (`favicon.ico` ebenso: kein Boot-Defekt.)
#
# Was es NICHT prüft: irgendetwas über den Inhalt der UI. Dafür ist `:app:shared:wasmJsBrowserTest` zuständig.
# Dieses Skript kennt Compose nicht und soll es nicht kennen.
#
# Aufruf:  scripts/web-boot-smoke.sh [URL]        (Default: http://localhost:8080)
# Exit:    0 = Boot sauber · 1 = Boot kaputt (mit Grund auf stderr)

set -uo pipefail
URL="${1:-http://localhost:8080}"
CHROME="$(command -v google-chrome || command -v chromium || command -v chromium-browser)"
[ -n "$CHROME" ] || { echo "FEHLER: kein Chrome/Chromium im PATH" >&2; exit 1; }

fail() { echo "BOOT-SMOKE ROT: $*" >&2; exit 1; }

# 1. Dokument erreichbar?
html="$(curl -fsS "$URL/" 2>/dev/null)" || fail "GET $URL/ liefert kein 200"

# 2. Referenzierte Skripte erreichbar?
scripts="$(printf '%s' "$html" | grep -oE 'src="[^"]+\.js"' | sed 's/src="//;s/"$//')"
[ -n "$scripts" ] || fail "index.html referenziert kein .js-Bundle"
for s in $scripts; do
  case "$s" in http*) u="$s";; /*) u="$URL$s";; *) u="$URL/$s";; esac
  curl -fsS -o /dev/null "$u" || fail "Asset nicht ladbar: $u"
done

# 3. Bootet die Seite ohne JS-Fehler? Chrome über das DevTools-Protokoll beobachten.
PORT=9422
"$CHROME" --headless=new --disable-gpu --no-sandbox --remote-debugging-port=$PORT \
          --window-size=1000,700 "$URL/" >/dev/null 2>&1 &
CH=$!
trap 'kill $CH 2>/dev/null' EXIT
sleep 2

python3 - "$PORT" "$URL" <<'PY' || exit 1
import json,urllib.request,socket,base64,struct,sys,time
port=int(sys.argv[1])
for _ in range(30):
    try:
        tabs=json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json")); break
    except Exception: time.sleep(1)
else:
    print("BOOT-SMOKE ROT: Chrome-DevTools nicht erreichbar", file=sys.stderr); sys.exit(1)
page=[t for t in tabs if t["type"]=="page"][0]
path=page["webSocketDebuggerUrl"].split(str(port),1)[1]
s=socket.create_connection(("127.0.0.1",port)); s.settimeout(30)
k=base64.b64encode(b"0123456789abcdef").decode()
s.send(f"GET {path} HTTP/1.1\r\nHost:127.0.0.1:{port}\r\nUpgrade:websocket\r\nConnection:Upgrade\r\nSec-WebSocket-Key:{k}\r\nSec-WebSocket-Version:13\r\n\r\n".encode()); s.recv(8192)
def send(m):
    d=json.dumps(m).encode(); h=bytearray([0x81]); n=len(d)
    if n<126: h.append(0x80|n)
    else: h.append(0x80|126); h+=struct.pack(">H",n)
    s.send(bytes(h)+b"\x00\x00\x00\x00"+d)
def recv():
    b1=s.recv(2)
    if len(b1)<2: return None
    ln=b1[1]&0x7f
    if ln==126: ln=struct.unpack(">H",s.recv(2))[0]
    elif ln==127: ln=struct.unpack(">Q",s.recv(8))[0]
    d=b""
    while len(d)<ln: d+=s.recv(ln-len(d))
    return json.loads(d)
send({"id":1,"method":"Runtime.enable"})
send({"id":2,"method":"Log.enable"})
send({"id":3,"method":"Page.enable"})
send({"id":4,"method":"Page.reload"})
errors=[]; backend=[]
origin=sys.argv[2] if len(sys.argv)>2 else ""
deadline=time.time()+25
while time.time()<deadline:
    s.settimeout(max(0.5, deadline-time.time()))
    try: m=recv()
    except Exception: break
    if not m: break
    meth=m.get("method")
    if meth=="Runtime.exceptionThrown":
        d=m["params"]["exceptionDetails"]
        errors.append("exception: "+ (d.get("exception",{}).get("description") or d.get("text","?"))[:160])
    elif meth=="Log.entryAdded" and m["params"]["entry"].get("level")=="error":
        e=m["params"]["entry"]; url=e.get("url",""); text=e.get("text","?")
        if "favicon" in url:                      # kein Boot-Defekt
            continue
        if "ERR_CONNECTION_REFUSED" in text or "WebSocket connection" in text:
            backend.append(url[-60:]); continue   # Backend fehlt: gemeldet, nicht bestraft
        if url.startswith(origin):                # fehlendes Asset der eigenen Herkunft
            errors.append(f"asset: {url[-70:]} :: {text[:80]}")
if backend:
    print(f"HINWEIS: {len(backend)} fehlgeschlagene Backend-Aufrufe (erwartet ohne Hub) — kein Boot-Defekt.")
if errors:
    print("BOOT-SMOKE ROT: JS-Fehler beim Boot", file=sys.stderr)
    for e in errors[:5]: print("   "+e, file=sys.stderr)
    sys.exit(1)
print("BOOT-SMOKE GRUEN: Dokument, Assets und Boot ohne JS-Fehler")
PY
