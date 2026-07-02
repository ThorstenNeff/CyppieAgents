// CYP-188 — no source maps in the PRODUCTION public bundle.
//
// A `.js.map` served next to the bundle re-exposes readable source (a source leak on the public build). Disable
// webpack's devtool for production only; the development run keeps maps for debuggability. The deploy additionally
// blocks `.map` at the host (defense in depth) and re-verifies the served bundle. Applies to the js and wasmJs
// browser webpack (Kotlin merges every webpack.config.d/*.js into the generated config).
if (config.mode === 'production') {
    config.devtool = false;
}
