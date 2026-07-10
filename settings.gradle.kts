rootProject.name = "KMPCyppieAgents"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // CYP-334: JediTerm (Desktop terminal widget) is distributed via JetBrains' intellij-dependencies repo,
        // not Maven Central. Scoped to the jediterm group so it never intercepts other artifacts' resolution.
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies") {
            mavenContent { includeGroup("org.jetbrains.jediterm") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":app:androidApp")
include(":app:androidAppDemo")
include(":app:desktopApp")
include(":app:shared")
include(":app:webApp")
include(":app:webAppDemo")
include(":app:iosAppDemo")
include(":core")
include(":connector-core")
include(":remote-runtime")
include(":server")
// CYP-106: hermetic embedded-server E2E harness — needs :server + the client repos (:app:shared) on ONE
// test classpath, which neither has alone. Additive, own gate (`:e2e:test`), NOT in the default `check`.
include(":e2e")