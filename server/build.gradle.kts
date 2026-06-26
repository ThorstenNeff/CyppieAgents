plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

group = "com.tneff.cyppieagents"
version = "1.0.0"
application {
    mainClass = "com.tneff.cyppieagents.ApplicationKt"
}

dependencies {
    api(projects.core)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.serverWebsockets)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.ktor.clientWebsockets)
    testImplementation(libs.kotlin.testJunit)
}