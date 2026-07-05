package com.tneff.cyppieagents.net

/** CYP-229 — native (jvm) has no browser fetch; the session rides via X-Session-Token / the engine's own cookie jar. */
actual fun installSameOriginCredentials() { /* no-op */ }
