package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.contract.ContractGenerator
import io.ktor.http.ContentType
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * CYP-234a-3 — the hosted, browsable API-docs surface. Renders Redoc (REST) + AsyncAPI (WS) inside UIUX's
 * maritime shell, from the `ContractGenerator` generators DIRECTLY — generated-on-request, no checked-in
 * spec artifact, so the docs cannot drift from the code (the payoff of the 234a fidelity chain).
 *
 * - **Bearer-only** hosted spec ([ContractGenerator.hostedOpenApi], PO-Assistant exposure-audit): the external
 *   surface omits the first-party `ory_kratos_session` cookie scheme (no IdP fingerprinting); the raw `/api`
 *   contract keeps both paths.
 * - **Fail-closed auth:** every `/docs*` route requires a credential ([requireCommReader] = a participant/
 *   operator token OR a verified human session) — the ratified authenticated-default (a later PUBLIC flip is a
 *   deploy config switch, not a rebuild).
 * - **Design boundary:** UIUX owns the maritime chrome + `--maritime-*` vars (the vendored `shell.html`); this
 *   route ADDITIVELY fills the `data-slot` nodes + injects the renderer mounts. It does NOT alter chrome/vars.
 * - **Renderer bundles** live under `/docs/assets` (VENDORED, pinned + LICENSE + integrity — a human deploy
 *   step, like the DiceBear assets in CYP-215; see `resources/docs/assets/README.md`). No CDN (CYP-226).
 */
fun Route.docsRoutes(deps: AuthDeps, registry: TokenRegistry) {
    route("/docs") {
        get { call.requireCommReader(deps, registry); call.respondText(renderShell(), ContentType.Text.Html) }
        // Deep-links to the two references — the same shell; its client-side REST↔WS toggle selects the panel.
        get("/rest") { call.requireCommReader(deps, registry); call.respondText(renderShell(), ContentType.Text.Html) }
        get("/ws") { call.requireCommReader(deps, registry); call.respondText(renderShell(), ContentType.Text.Html) }
        // The GENERATED specs (Bearer-only hosted variant), served directly — single-source, no artifact.
        get("/openapi.json") { call.requireCommReader(deps, registry); call.respondText(docsJson(ContractGenerator.hostedOpenApi()), ContentType.Application.Json) }
        get("/asyncapi.json") { call.requireCommReader(deps, registry); call.respondText(docsJson(ContractGenerator.asyncApi()), ContentType.Application.Json) }
        // Vendored renderer bundles (human deploy-step). Same-origin, no CDN.
        staticResources("/assets", "docs/assets")
    }
}

private val DOCS_JSON = Json { prettyPrint = true }

/** The exact serialization the single-source gate pins (served body == this over the generator output). */
fun docsJson(obj: JsonObject): String = DOCS_JSON.encodeToString(JsonObject.serializer(), obj)

private object DocsShell // resource anchor

/**
 * Read the vendored maritime shell + ADDITIVELY inject the renderer mounts before `</body>` and fill the
 * license/version slots — never touching the chrome or `--maritime-*` vars (UIUX's §6/§8 acceptance surface).
 */
private fun renderShell(): String {
    val tpl = DocsShell::class.java.getResource("/docs/shell.html")?.readText()
        ?: error("docs shell template missing from resources (/docs/shell.html)")
    return tpl
        .replace(
            "© CyppieAgents</span>",
            "© CyppieAgents · Redoc (MIT) · AsyncAPI React (Apache-2.0) · generated from server types</span>",
        )
        .replace("</body>", RENDER_MOUNT + "\n</body>")
}

/**
 * The additive renderer mount: load the VENDORED bundles (same-origin, under `/docs/assets`, no CDN) and render
 * the generated specs into UIUX's `#rest-render` + `#ws-render` slots. Redoc/AsyncAPI replace the placeholders
 * at init. A maritime theme is passed from the shell's `--maritime-*` vars so the render sits under one chrome.
 * Integrity hashes are filled by the human vendor step (see assets/README.md) — omitted here, not a CDN load.
 */
private val RENDER_MOUNT = """
  <!-- CYP-234a-3: additive renderer mount (vendored, same-origin, no CDN) -->
  <link rel="stylesheet" href="/docs/assets/asyncapi.min.css">
  <script src="/docs/assets/redoc.standalone.js"></script>
  <script src="/docs/assets/asyncapi-web-component.js"></script>
  <script>
    (function () {
      var v = getComputedStyle(document.documentElement);
      function tok(n, d) { return (v.getPropertyValue(n) || d).trim(); }
      if (window.Redoc) {
        Redoc.init('/docs/openapi.json', {
          theme: { colors: { primary: { main: tok('--maritime-primary', '#0A5AA0') } },
                   typography: { fontFamily: tok('--font-base', 'system-ui, sans-serif'),
                                 code: { fontFamily: tok('--font-mono', 'monospace') } } },
          hideDownloadButton: false, expandResponses: '200,201',
        }, document.getElementById('rest-render'));
      }
      if (window.AsyncApiStandalone) {
        window.AsyncApiStandalone.render(
          { schema: { url: '/docs/asyncapi.json' }, config: { show: { sidebar: true } } },
          document.getElementById('ws-render'),
        );
      }
    })();
  </script>
""".trimIndent()
