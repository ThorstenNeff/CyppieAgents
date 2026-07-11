// Shared seed constants — MUST match WebE2eSeed in e2e/.../WebE2eServerMain.kt (one source of truth for the
// harness contract). If the boot main changes the seed, update both.
export const OPERATOR_TOKEN = 'e2e-operator-token';
export const agentToken = (id: string) => `e2e-agent-${id}`;

export const SEED_AGENT = 'backend';
export const SEED_CHANNEL = 'po-backend';
export const SEED_EVENT_COUNT = 5; // agent-events seq 1..5 pre-seeded for `backend`

// The reference client surface exposed on `window.cyppie` by fixture/index.html.
export interface CyppieClient {
  connectAgent(agentId: string, since: number, token: string): Promise<boolean>;
  reconnectAgent(): Promise<boolean>;
  connectComm(token: string): Promise<boolean>;
  loadComm(channel: string, token: string): Promise<number>;
  readonly agentSeqs: number[];
  readonly agentFrames: number;
  readonly commIds: string[];
}

declare global {
  interface Window { cyppie: CyppieClient }
}
