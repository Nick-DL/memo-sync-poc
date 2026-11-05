// NOT COMPILED IN THIS POC - optional Gradle wiring for the Java 17 core.
//
// The authoritative POC build is javac + run-tests.ps1 (see ../README.md). This file only
// exists so the Android app skeleton shows how the same core sources would be consumed as a
// :core-jvm module; it is not executed here (no Gradle on PATH, offline environment).
plugins {
    `java-library`
}

java {
    // Compile the Java 17 core with whatever JDK runs Gradle (CI uses JDK 21); do not require
    // a separately provisioned JDK 17 toolchain.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    // Vendored jar keeps the POC buildable offline (see ../lib/gson-2.11.0.jar).
    api(files("../lib/gson-2.11.0.jar"))
}
