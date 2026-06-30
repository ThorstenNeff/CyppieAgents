plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

group = "com.tneff.cyppieagents"
version = "1.0.0"

// CYP-142 / E2.6 (S4) — the Remote Bridge/Runtime: the deployable that runs in USER infra, wraps the
// user's Claude-Code stream-json session, and relays it to the hub over the Hub-Wire-Protocol (/ws/hub).
// INSIDE = provider-specific (CC stdio, REUSED from :connector-core incl. the CYP-170 inversion); OUTSIDE
// = uniform wire (:core DTOs). It carries NO server secrets — only the agent's S3 token + HUB_URL.
dependencies {
    api(projects.core)
    api(projects.connectorCore) // ClaudeCodeSession (seamed), ResumingSession (CYP-170), MediationGate, ProcessBuilderSpawner
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.logback)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientWebsockets)
    implementation(libs.ktor.serializationKotlinxJson)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.kotlin.testJunit)
}
