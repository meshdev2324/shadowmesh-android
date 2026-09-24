pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ShadowMesh"
include(":app")
include(":core:vpn")
project(":core:vpn").projectDir = file("core/vpn")
include(":core:ui")
project(":core:ui").projectDir = file("core/ui")
include(":macrobenchmark")
