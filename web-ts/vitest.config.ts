import { defineConfig } from 'vitest/config'

// CYP-400 (W2) — net-layer unit tests run in node (sockets/timers are injected, no DOM needed).
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
