plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    // Required so @Serializable classes defined in :server (e.g. PlatformConfig) get generated
    // serializers — without it PlatformConfig.load() throws at runtime and the boot crashes (CYP-33).
    alias(libs.plugins.kotlinSerialization)
}

group = "com.tneff.cyppieagents"
version = "1.0.0"
application {
    mainClass = "com.tneff.cyppieagents.ApplicationKt"
}

dependencies {
    api(projects.core)
    implementation(libs.logback)
    implementation(libs.kotlinx.coroutinesCore)
    // Event-Log persistence (CYP-35): xerial sqlite-jdbc, pinned. Full WAL/batch control behind the
    // EventSink seam at the smallest dependency; SQLDelight stays the documented upgrade path (02 §15).
    implementation(libs.sqlite.jdbc)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.serverWebsockets)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.ktor.clientWebsockets)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.kotlin.testJunit)
}