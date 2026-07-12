#!/usr/bin/env bash
# CYP-422 Post-Deploy unauth §2 header smoke — the gate-critical, no-login checks (run the moment the staging URL
# lands; no operator creds needed). Header-based; the browser active-probe is deploy-smoke/csp-probe.spec.ts.
# Usage: deploy-smoke-headers.sh https://<staging-web-ts-url>
set -uo pipefail
URL="${1:?usage: deploy-smoke-headers.sh <staging-url>}"
pass=0; fail=0
ok(){ echo "PASS  $1"; pass=$((pass+1)); }
no(){ echo "FAIL  $1"; fail=$((fail+1)); }

# fetch response headers twice (for the per-response-nonce-differs check)
H1="$(curl -sS -D - -o /dev/null "$URL" 2>/dev/null | tr -d '\r')"
H2="$(curl -sS -D - -o /dev/null "$URL" 2>/dev/null | tr -d '\r')"
csp="$(printf '%s\n' "$H1" | grep -i '^content-security-policy:')"

# §1.3 CSP present + nonce-based + hardened, no unsafe-inline
[ -n "$csp" ] && ok "1.3 CSP header present" || no "1.3 CSP header present"
printf '%s' "$csp" | grep -qi 'nonce-'                    && ok "1.3 script-src nonce-based"   || no "1.3 script-src nonce-based"
ss="$(printf '%s' "$csp" | grep -io "script-src[^;]*")"
printf '%s' "$ss" | grep -qi 'unsafe-inline'              && no "1.3 script-src unsafe-inline PRESENT" || ok "1.3 script-src no unsafe-inline"
printf '%s' "$csp" | grep -qi "object-src[^;]*none"       && ok "1.3 object-src 'none'"        || no "1.3 object-src 'none'"
printf '%s' "$csp" | grep -qi "frame-ancestors[^;]*none"  && ok "1.3 frame-ancestors 'none'"   || no "1.3 frame-ancestors 'none'"

# §1.3b per-response nonce differs (two requests → two nonces)
n1="$(printf '%s' "$H1" | grep -io "nonce-[A-Za-z0-9+/=_-]\+" | head -1)"
n2="$(printf '%s' "$H2" | grep -io "nonce-[A-Za-z0-9+/=_-]\+" | head -1)"
{ [ -n "$n1" ] && [ "$n1" != "$n2" ]; } && ok "1.3b per-response nonce differs" || no "1.3b per-response nonce differs ($n1 vs $n2)"

# §1.3 nosniff
printf '%s\n' "$H1" | grep -qi '^x-content-type-options: *nosniff' && ok "1.3 X-Content-Type-Options nosniff" || no "1.3 nosniff"

# §1.4 Kratos session Set-Cookie flags (only if the landing sets one; else verify at the login/callback step)
sc="$(printf '%s\n' "$H1" | grep -i '^set-cookie:')"
if [ -n "$sc" ]; then
  printf '%s' "$sc" | grep -qi 'httponly' && ok "1.4 Set-Cookie HttpOnly" || no "1.4 Set-Cookie HttpOnly"
  printf '%s' "$sc" | grep -qi 'secure'   && ok "1.4 Set-Cookie Secure"   || no "1.4 Set-Cookie Secure"
  printf '%s' "$sc" | grep -qi 'samesite' && ok "1.4 Set-Cookie SameSite" || no "1.4 Set-Cookie SameSite"
else
  echo "NOTE  1.4 no Set-Cookie on the landing response — verify at the Kratos login/callback step (guided)"
fi

# §4.2 same-origin /api/health reachable (2xx)
code="$(curl -sS -o /dev/null -w '%{http_code}' "${URL%/}/api/health" 2>/dev/null)"
[ "$code" = "200" ] && ok "4.2 /api/health 200 (same-origin)" || no "4.2 /api/health ($code)"

echo "--------"
echo "SUMMARY  PASS=$pass  FAIL=$fail"
[ "$fail" -eq 0 ]
