import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { AuthGate } from './auth/AuthGate'
import { logoutUrl } from './auth/authConfig'
import { createLogin } from './auth/loginFlow'
import { RestHubRepo } from './state/restRepo'
import { bootstrapLocalHub } from './state/hubConfig'
import { isOperatorServe } from './platform/operatorToken'
import './ui/tokens.generated.css' // CYP-423 maritime token layer (generated) — must load before app styles
import './index.css'

const root = document.getElementById('root')
if (root === null) throw new Error('CYP-398: #root not found in index.html')

// CYP-515 (a): the in-app auth session-gate wraps the app. whoami resolves BEFORE any app content mounts (anti-flash);
// None → the in-app LoginScreen (loginFlow drives the Kratos same-origin API-flow; the server sets the httpOnly session
// cookie). The AuthGate installs the global 401 → in-app re-auth handler itself. break-glass (injected operator token)
// bypasses the whoami gate (Bearer authenticates).
const cfg = bootstrapLocalHub()
const repo = new RestHubRepo(cfg.hubId, cfg.apiBase)
const login = createLogin({
  fetchImpl: (input, init) => fetch(input, init), // raw fetch — NOT the RestClient (a login 401 must not fire re-auth)
  fetchAuthMe: () => repo.fetchAuthMe(),
})

createRoot(root).render(
  <StrictMode>
    <AuthGate
      activeHubId={cfg.hubId}
      fetchAuthMe={() => repo.fetchAuthMe()}
      login={login}
      redirectToLogout={() => window.location.assign(logoutUrl())}
      breakGlass={isOperatorServe()}
    >
      {(operator) => <App operatorOverride={operator} />}
    </AuthGate>
  </StrictMode>,
)
