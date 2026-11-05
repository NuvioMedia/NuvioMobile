// Standalone checks use the host's Android plugin version, without configuring its players.
pluginManagement {
    val catalog = file("../gradle/libs.versions.toml").readText()
    val hostAgp = Regex("(?m)^agp\\s*=\\s*\"([^\"]+)\"")
        .find(catalog)?.groupValues?.get(1)
        ?: error("Cannot find the host Android Gradle plugin version")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.library") version hostAgp
        id("com.android.application") version hostAgp
    }
}

dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}

rootProject.name = "nuvio-vpn-android"
include(":vpn-core")
project(":vpn-core").projectDir = file("../vpn-core")
include(":device-tests")
