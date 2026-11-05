// Java 17 sync core, consumed by the Android modules.
//
// The authoritative POC test path is javac + run-tests.ps1 (see ../README.md). This module
// exists so the Android app can depend on the same tested core sources as :core-jvm.
plugins {
    `java-library`
}

java {
    // Compile the Java 17 core with whatever JDK runs Gradle (CI uses JDK 21); do not require
    // a separately provisioned JDK 17 toolchain.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Repositories are declared centrally in settings.gradle.kts
// (RepositoriesMode.FAIL_ON_PROJECT_REPOS); do not add project repositories here.

dependencies {
    // Vendored jar keeps the POC buildable offline (see ../lib/gson-2.11.0.jar).
    api(files("../lib/gson-2.11.0.jar"))
}
