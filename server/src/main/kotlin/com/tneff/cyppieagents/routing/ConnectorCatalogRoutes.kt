package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.boot.ConnectorRouter
import com.tneff.cyppieagents.model.ConnectorDescriptor
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ConnectorsView
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * CYP-462 — `GET /api/connectors`: the connector catalog + each kind's DECLARED fidelity, so the picker can
 * preview a connector's capabilities BEFORE the operator commits (UIUX-confirmed).
 *
 * **Read-tier via [requireCommReader]** — token OR a verified human OPERATOR/MEMBER session, the SAME resolver as
 * `GET /api/agents`. This is load-bearing: the connector picker is browser-facing (the tokenless SPA authenticates
 * by its Kratos session cookie, CYP-230), so a token-only gate ([requireParticipant]) would 401 the browser and
 * kill the picker — the CYP-320 bug class. The payload is 100% static, secret-free, tenant-free connector
 * vocabulary, so it needs no per-subject ACL. The *mutation* (choosing/opting-in a connector) stays operator-only
 * in [connectorRoutes]; this route is read-only.
 *
 * **Single-sourced** from [ConnectorRouter.capabilitiesForKind] — the SAME mapping the spawn/opt-in path uses to
 * set an agent's caps — so the preview can NEVER drift from what a new (local) agent actually gets. Pure static:
 * no runtime / project / session state is read. The value is the declared (= LOCAL-effective) profile; a
 * REMOTE agent's caps clamp further (design note in [ConnectorsView]).
 */
fun Route.connectorCatalogRoutes(
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
    apiBase: String = "/api",
) {
    route("$apiBase/connectors") {
        get {
            call.requireCommReader(deps, registry) // token OR verified human session; 401 if unauthenticated
            call.respond(
                ConnectorsView(
                    connectors = ConnectorKind.entries.map { kind ->
                        ConnectorDescriptor(kind, ConnectorRouter.capabilitiesForKind(kind))
                    },
                ),
            )
        }
    }
}
