@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.tneff.cyppieagents.net

/** CYP-229/231 (Kotlin/Wasm) — see the JS actual. Same-origin: force `credentials:'include'` + echo the `cyppie_csrf`
 *  double-submit cookie in `X-CSRF-Token` on unsafe methods (POST/PUT/DELETE/PATCH); cross-origin/safe untouched. */
private fun patchFetch(): Unit = js(
    "(function(){if(window.__cyppieCredsPatched)return;var o=window.fetch;var U={POST:1,PUT:1,DELETE:1,PATCH:1};window.fetch=function(i,n){try{var u=(typeof i==='string')?i:(i&&i.url);if(u&&(new URL(u,window.location.href)).origin===window.location.origin){n=Object.assign({},n||{},{credentials:'include'});var m=((n.method||(typeof i==='object'&&i&&i.method)||'GET')+'').toUpperCase();if(U[m]){var c=document.cookie.match(/(?:^|;\\s*)cyppie_csrf=([^;]*)/);if(c){var h=new Headers(n.headers||undefined);if(!h.has('X-CSRF-Token'))h.set('X-CSRF-Token',c[1]);n.headers=h;}}}}catch(e){}return o.call(this,i,n);};window.__cyppieCredsPatched=true;})()",
)

actual fun installSameOriginCredentials() {
    patchFetch()
}
