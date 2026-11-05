// NOT COMPILED IN THIS POC - reference-only Gradle Kotlin DSL skeleton for the SaltUI app.
//
// Why not built here: needs Android SDK + JDK 21 toolchain (AGP 9.1.1 / Kotlin 2.4.20 /
// Compose Multiplatform 1.12.2) and the Wear Engine SDK; the POC environment has only JDK 17.
// The tested POC logic is the Java 17 core (:core-jvm, wired in settings.gradle.kts).
plugins {
    id("com.android.application")
    // Opted out of AGP built-in Kotlin (see gradle.properties) because SaltUI 3.0.0 needs
    // Kotlin 2.4.20, while AGP 9.1.1's built-in Kotlin is 2.2.10.
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.compose")            // Compose Multiplatform (SaltUI 3.x is CMP based)
    id("org.jetbrains.kotlin.plugin.compose")
    // TODO(Android): id("com.google.devtools.ksp") for Room, version must match Kotlin 2.4.20
}

android {
    namespace = "com.example.memosync.android"
    compileSdk = 36 // TODO(Android): align with the installed SDK; POC not validated

    defaultConfig {
        applicationId = "com.example.memosync"
        minSdk = 23 // SaltUI UI module requirement (salt-ui-navigation would need 24)
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-poc"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    // SaltUI 3.x / AGP 9.x target: JDK 21 toolchain (not available in this POC environment).
    jvmToolchain(21)
}

dependencies {
    // Tested Java 17 sync core (HLC, MergeEngine, ProtocolCodec, SyncManager state machine).
    implementation(project(":core-jvm"))

    // SaltUI - Compose Multiplatform design system. Docs: https://moriafly.com/program/salt-ui/
    // Verify the version + compatibility table before upgrading:
    //   https://github.com/Moriafly/SaltUI#compatibility
    implementation("io.github.moriafly:salt-ui:3.0.0")
    // Optional: implementation("io.github.moriafly:salt-ui-navigation:3.0.0") (minSdk 24)

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation("androidx.activity:activity-compose:1.9.3") // TODO(Android): pin
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7") // TODO(Android): pin

    // Room - outline only in this POC (entities/DAOs not compiled).
    implementation("androidx.room:room-runtime:2.7.0") // TODO(Android): pin + KSP compiler
    // ksp("androidx.room:room-compiler:2.7.0")

    // Wear Engine Kit - NOT on Maven Central; vendor the Huawei SDK AAR and verify the
    // coordinate/version from the Huawei developer console:
    //   https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/wearengine
    // implementation("com.huawei.hms:wearengine:<verify-version>")
}
