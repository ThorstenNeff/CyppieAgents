package com.tneff.cyppieagents.net

/**
 * CYP-229/231 (Kotlin/JS) — one-time idempotent `window.fetch` wrapper for **same-origin** requests only (a
 * cross-origin fetch is passed through UNCHANGED — the same-origin guard is the security boundary):
 *  - **CYP-229:** forces `credentials:'include'` so the `ory_kratos_session` cookie rides the `/api` reads
 *    (forcing it cross-origin would leak the session cookie to a foreign origin).
 *  - **CYP-231:** on an **unsafe method** (POST/PUT/DELETE/PATCH) it reads the JS-readable double-submit CSRF
 *    cookie (`cyppie_csrf`, base64url) and echoes it in the `X-CSRF-Token` header the server compares — else a
 *    cookie-authed write is a 403 `csrf_failed`. Safe methods + cross-origin never get the header (no token leak).
 */
actual fun installSameOriginCredentials() {
    js(
        "(function(){if(window.__cyppieCredsPatched)return;var o=window.fetch;var U={POST:1,PUT:1,DELETE:1,PATCH:1};window.fetch=function(i,n){try{var u=(typeof i==='string')?i:(i&&i.url);if(u&&(new URL(u,window.location.href)).origin===window.location.origin){n=Object.assign({},n||{},{credentials:'include'});var m=((n.method||(typeof i==='object'&&i&&i.method)||'GET')+'').toUpperCase();if(U[m]){var c=document.cookie.match(/(?:^|;\\s*)cyppie_csrf=([^;]*)/);if(c){var h=new Headers(n.headers||undefined);if(!h.has('X-CSRF-Token'))h.set('X-CSRF-Token',c[1]);n.headers=h;}}}}catch(e){}return o.call(this,i,n);};window.__cyppieCredsPatched=true;})()",
    )
}
