package com.tneff.cyppieagents.net

/**
 * CYP-229 (Kotlin/JS) — one-time idempotent `window.fetch` wrapper. Forces `credentials:'include'` for **same-origin**
 * requests only (so the `ory_kratos_session` cookie rides the `/api` reads + the participant avatar-serve); a
 * cross-origin fetch is passed through UNCHANGED — forcing credentials there would leak the session cookie to a
 * foreign origin (CSRF / cookie-leak), so the same-origin guard is the security boundary.
 */
actual fun installSameOriginCredentials() {
    js(
        "(function(){if(window.__cyppieCredsPatched)return;var o=window.fetch;window.fetch=function(i,n){try{var u=(typeof i==='string')?i:(i&&i.url);if(u&&(new URL(u,window.location.href)).origin===window.location.origin){n=Object.assign({},n||{},{credentials:'include'});}}catch(e){}return o.call(this,i,n);};window.__cyppieCredsPatched=true;})()",
    )
}
