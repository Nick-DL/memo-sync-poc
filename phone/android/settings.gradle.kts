// Android project settings for the phone POC.
// :shell is buildable in CI; :app is the full source skeleton (needs Wear Engine SDK + Room KSP).
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
        mavenCentral() // SaltUI is published here: io.github.moriafly:salt-ui:<TAG>
    }
}

rootProject.name = "memo-sync-phone"

include(":app")

// Smaller, dependency-complete SaltUI shell that CI can build without the Huawei Wear Engine
// SDK / Room KSP. The full :app skeleton stays source-only until those are available.
include(":shell")

// Reuse the tested Java 17 core sources instead of duplicating HLC/merge/protocol code.
include(":core-jvm")
project(":core-jvm").projectDir = file("../core")
