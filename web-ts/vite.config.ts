import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// CYP-398 (W0). The production SPA is served at the DEPLOY/PROXY, not by Ktor (Spec 14 §6); Ktor stays
// API-only + CORS. These `server`/`preview` ports are the LOCAL dev/preview servers only. Coexistence is
// port-based (Spec 14 §6): the new TS UI takes the former primary port (8080) and the old WASM UI moves to
// :8085. The real proxy/origin wiring (`config.web.allowedOrigins`, token-global injection) is the closing
// step of W0 and lives in the deploy config, not here.
export default defineConfig({
  plugins: [react()],
  server: { port: 8080, strictPort: true },
  preview: { port: 8080, strictPort: true },
  // CYP-455 (CYP-422-prep) — CSP nonce seam. `modulePreload.polyfill=false` removes Vite's one PROD inline
  // `<script>` (the module-preload polyfill), so the cutover CSP can keep `script-src` STRICT (no
  // `unsafe-inline`) — the actual XSS defense. The only remaining inline script is the deploy-injected operator
  // token in index.html, which carries a per-response `nonce` (seam documented there). The CSP header + nonce
  // stamp are DEPLOY-owned and activated at cutover under Auftraggeber-GO — this is only the web-ts-side prep.
  build: { modulePreload: { polyfill: false } },
})
