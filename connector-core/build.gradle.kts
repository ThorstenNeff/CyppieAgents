plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

group = "com.tneff.cyppieagents"
version = "1.0.0"

// CYP-142 / E2.6 (S4.0) — the provider-agnostic Claude-Code stream-json session core, shared by
// :server (the local hub connector) and :remote-runtime (the BYOA bridge). The CYP-170 lazy-init
// dependency-inversion (ResumingSession) and Gate #3 masking + Gate #6 mediation discipline live HERE,
// once, so the bridge REUSES them by construction rather than re-deriving (the resume-deadlock risk).
dependencies {
    api(projects.core)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.logback) // brings slf4j-api (the connector uses org.slf4j); consistent with :server
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.kotlin.testJunit)
}
