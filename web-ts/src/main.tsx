import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { AuthGate } from './auth/AuthGate'
import { loginUrl, logoutUrl, hasLoginFlowReturn } from './auth/authConfig'
import { RestHubRepo } from './state/restRepo'
import { readHubConfig } from './state/hubConfig'
import { isOperatorServe } from './platform/operatorToken'
import { setOnUnauthorized } from './net/rest'
import './ui/tokens.generated.css' // CYP-423 maritime token layer (generated) — must load before app styles
import './index.css'

const root = document.getElementById('root')
if (root === null) throw new Error('CYP-398: #root not found in index.html')

// CYP-470: the auth session-gate wraps the app. whoami resolves BEFORE any app content mounts (anti-flash); a global
// 401 re-auth-redirects. break-glass (injected operator token) bypasses the whoami gate (Bearer authenticates).
const cfg = readHubConfig()
const repo = new RestHubRepo(cfg.apiBase)
const redirectToLogin = () => window.location.assign(loginUrl())
setOnUnauthorized(redirectToLogin) // every /api 401 → re-auth redirect (§5)

createRoot(root).render(
  <StrictMode>
    <AuthGate
      fetchAuthMe={() => repo.fetchAuthMe()}
      redirectToLogin={redirectToLogin}
      redirectToLogout={() => window.location.assign(logoutUrl())}
      breakGlass={isOperatorServe()}
      flowReturnPresent={hasLoginFlowReturn(window.location.search)} // CYP-515: don't re-init a Kratos flow return → loop
    >
      {(operator) => <App operatorOverride={operator} />}
    </AuthGate>
  </StrictMode>,
)
