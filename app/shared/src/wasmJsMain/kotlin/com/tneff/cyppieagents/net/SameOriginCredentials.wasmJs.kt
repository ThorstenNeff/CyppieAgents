package com.tneff.cyppieagents.net

/** CYP-229 (Kotlin/Wasm) — see the JS actual. Same-origin fetch → `credentials:'include'`; cross-origin untouched. */
private fun patchFetch(): Unit = js(
    "(function(){if(window.__cyppieCredsPatched)return;var o=window.fetch;window.fetch=function(i,n){try{var u=(typeof i==='string')?i:(i&&i.url);if(u&&(new URL(u,window.location.href)).origin===window.location.origin){n=Object.assign({},n||{},{credentials:'include'});}}catch(e){}return o.call(this,i,n);};window.__cyppieCredsPatched=true;})()",
)

actual fun installSameOriginCredentials() {
    patchFetch()
}
