// Buildable SaltUI shell for CI.
//
// The full :app skeleton needs the Huawei Wear Engine SDK AAR and Room KSP; this module keeps
// the same SaltUI toolchain (Kotlin 2.4.20 / CMP 1.12.2 / AGP 9.1.1 / JDK 21) but only uses
// documented, compileable SaltUI APIs. CI builds this first, then can be switched to :app once
// the vendor SDK is available.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.memosync.shell"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.memosync.shell"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
    jvmToolchain(21)
    compilerOptions {
        // Java/Kotlin bytecode stays at 17; JDK 21 is only the build toolchain.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("io.github.moriafly:salt-ui:3.0.0")
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation("androidx.activity:activity-compose:1.9.3")
}
