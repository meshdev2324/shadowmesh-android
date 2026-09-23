pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
