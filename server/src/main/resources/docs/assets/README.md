# CYP-234a-3 — vendored renderer bundles (human deploy step)

The hosted docs (`/docs`) render Redoc (REST) + AsyncAPI (WS) from **same-origin, vendored** bundles — **no
CDN** (CYP-226: no external host on the runtime surface; strict-CSP-friendly). This directory holds those
bundles. They are a **human-reviewed deploy step** (like the DiceBear PNG bundling in CYP-215): the backend
route + generators + slot integration are built and gated; committing the actual third-party JS/CSS + their
licenses is the operator's action.

## Files to vendor here (pinned versions)

| file | source | pin | license |
|---|---|---|---|
| `redoc.standalone.js` | `redoc` (Redocly community) | pin an exact version, e.g. `2.1.x` | MIT |
| `asyncapi-web-component.js` | `@asyncapi/web-component` | pin an exact version | Apache-2.0 |
| `asyncapi.min.css` | `@asyncapi/react-component` styles | match the web-component version | Apache-2.0 |

Also commit the upstream `LICENSE` files alongside (e.g. `redoc.LICENSE`, `asyncapi.LICENSE`).

## Integrity

When vendoring, compute each bundle's SRI hash and add `integrity="sha384-…" crossorigin="anonymous"` to the
corresponding `<script>`/`<link>` in `DocsRoutes.RENDER_MOUNT`. (Omitted in-repo because the hash is of the
vendored bytes, which land in this deploy step — not a CDN fetch.)

## Why not a CDN

CYP-226 lesson: an external host on the runtime surface is a live-availability + CSP + supply-chain risk. The
docs are served entirely same-origin; a strict `script-src 'self'` CSP then covers them with no allowances.
