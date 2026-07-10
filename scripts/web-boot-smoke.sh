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
#   4. Auf dem Bildschirm wurde etwas GEMALT (mehr als eine Farbe). Compose malt in ein Canvas -- der DOM
#      verrät darüber nichts, das Bild schon. Fängt den Fall "bootet sauber, rendert nichts".
#
# Bewusst NICHT rot: fehlgeschlagene Aufrufe an das Backend (`ERR_CONNECTION_REFUSED` gegen den Hub-Port).
# Die Demo ist NICHT backendlos — gemessen: sie ruft /api/agents, /api/channels, /api/acl und drei WebSockets
# auf :8787 und degradiert sichtbar zu Fehlerzuständen. Das ist gewolltes Verhalten und darf den Boot-Smoke
# nicht rot machen; es wird gemeldet, nicht bestraft. (`favicon.ico` ebenso: kein Boot-Defekt.)
#
# Was es NICHT prüft: irgendetwas über den Inhalt der UI. Dafür ist `:app:shared:wasmJsBrowserTest` zuständig.
# Dieses Skript kennt Compose nicht und soll es nicht kennen.
#
# Aufruf:  scripts/web-boot-smoke.sh <URL> <erwartetes-Bundle>
#          z. B.  scripts/web-boot-smoke.sh http://localhost:8080 webApp.js      (PROD)
#                 scripts/web-boot-smoke.sh http://localhost:8080 webAppDemo.js  (DEMO)
# Exit:    0 = Boot sauber · 1 = Boot kaputt (mit Grund auf stderr)
#
# Das erwartete Bundle ist PFLICHT, fail-closed. Grund: prod und demo binden denselben Port 8080, und der
# zweite Serve-Task bindet ihn nicht -- er scheitert NICHT laut. Wer dann misst, misst den falschen Build und
# liest ein Ergebnis, das nichts bedeutet. (Genau so passiert, CYP-340 §3.1.) Ein Handlauf, der auf Disziplin
# baut, ist der schwaechste Teil eines Waechters; also erzwingt das Skript, was der Handlauf nur verlangte.

set -uo pipefail
URL="${1:-}"
EXPECT="${2:-}"
CHROME="$(command -v google-chrome || command -v chromium || command -v chromium-browser)"
[ -n "$CHROME" ] || { echo "FEHLER: kein Chrome/Chromium im PATH" >&2; exit 1; }

fail() { echo "BOOT-SMOKE ROT: $*" >&2; exit 1; }

[ -n "$URL" ]    || fail "erstes Argument fehlt: URL"
[ -n "$EXPECT" ] || fail "zweites Argument fehlt: erwartetes Bundle (z. B. webApp.js oder webAppDemo.js). Fail-closed — ohne diese Angabe sagt ein gruener Lauf nicht, WAS geprueft wurde."

# 1. Dokument erreichbar?
html="$(curl -fsS "$URL/" 2>/dev/null)" || fail "GET $URL/ liefert kein 200"

# 2. Referenzierte Skripte erreichbar?
scripts="$(printf '%s' "$html" | grep -oE 'src="[^"]+\.js"' | sed 's/src="//;s/"$//')"
[ -n "$scripts" ] || fail "index.html referenziert kein .js-Bundle"

# 2a. Wird das ERWARTETE Bundle serviert? Sonst misst der Rest den falschen Build.
printf '%s\n' $scripts | grep -qx ".*/\?$EXPECT" || printf '%s\n' $scripts | grep -q "^$EXPECT$" \
  || fail "falscher Build serviert: erwartet '$EXPECT', gefunden: $(printf '%s ' $scripts)"
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
# 4. Wurde ueberhaupt etwas gemalt? Ein Bildschirm mit EINER Farbe ist kein gerenderter Bildschirm.
#    (Compose malt in ein Canvas; der DOM verraet nichts darueber. Das Bild schon.)
send({"id":9,"method":"Page.captureScreenshot","params":{}})
png=None
end2=time.time()+20
while time.time()<end2:
    s.settimeout(max(0.5,end2-time.time()))
    try: m=recv()
    except Exception: break
    if m and m.get("id")==9:
        png=base64.b64decode(m["result"]["data"]); break
if png is None:
    print("BOOT-SMOKE ROT: kein Screenshot erhalten", file=sys.stderr); sys.exit(1)

import zlib
def png_colors(data):
    pos=8; w=h=None; idat=b""; bpp=4
    while pos < len(data):
        ln=struct.unpack(">I",data[pos:pos+4])[0]; typ=data[pos+4:pos+8]
        chunk=data[pos+8:pos+8+ln]; pos+=12+ln
        if typ==b"IHDR":
            w,h,depth,ctype=struct.unpack(">IIBB",chunk[:10])
            bpp={0:1,2:3,4:2,6:4}[ctype]
        elif typ==b"IDAT": idat+=chunk
        elif typ==b"IEND": break
    raw=zlib.decompress(idat); stride=w*bpp; out=bytearray(); prev=bytearray(stride); i=0
    for _ in range(h):
        f=raw[i]; i+=1; line=bytearray(raw[i:i+stride]); i+=stride
        for x in range(stride):
            a=line[x-bpp] if x>=bpp else 0
            b=prev[x]; c=prev[x-bpp] if x>=bpp else 0
            if f==1: line[x]=(line[x]+a)&255
            elif f==2: line[x]=(line[x]+b)&255
            elif f==3: line[x]=(line[x]+((a+b)>>1))&255
            elif f==4:
                pp=a+b-c; pa=abs(pp-a); pb=abs(pp-b); pc=abs(pp-c)
                pr=a if (pa<=pb and pa<=pc) else (b if pb<=pc else c)
                line[x]=(line[x]+pr)&255
        out+=line; prev=line
    cols=set()
    for px in range(0,len(out),bpp*37):   # Stichprobe, nicht jedes Pixel
        cols.add(bytes(out[px:px+3]))
        if len(cols)>8: break
    return len(cols)

n=png_colors(png)
if n < 3:
    print(f"BOOT-SMOKE ROT: Bildschirm zeigt nur {n} Farbe(n) — es wurde nichts gerendert", file=sys.stderr)
    sys.exit(1)

print(f"BOOT-SMOKE GRUEN: Dokument, Assets, Boot ohne JS-Fehler, und es wurde gerendert ({n}+ Farben)")
PY
