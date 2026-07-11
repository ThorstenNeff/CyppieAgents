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
})
