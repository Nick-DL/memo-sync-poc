// Android project root for the phone POC.
//
// CI builds :shell (SaltUI-only, no vendor SDK) with the pinned toolchain:
//   Kotlin 2.4.20, Compose Multiplatform 1.12.2, AGP 9.1.1, JDK 21.
// :app is the full source skeleton; it additionally needs the Huawei Wear Engine SDK AAR and
// Room KSP, and is only attempted via the manual workflow target.
//
// Verify the versions together against:
//   https://github.com/Moriafly/SaltUI#compatibility
//   https://moriafly.com/program/salt-ui/getting-started/installation.html
plugins {
    id("com.android.application") version "9.1.1" apply false
    // AGP 9.1.1's built-in Kotlin is 2.2.10; SaltUI 3.0.0 requires Kotlin 2.4.20.
    // We opt out via android.builtInKotlin=false in gradle.properties and use this plugin.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    // TODO(Android): Room code generation needs KSP pinned to the same Kotlin version:
    // id("com.google.devtools.ksp") version "<ksp matching 2.4.20>" apply false
}

// The Java 17 POC core lives in ../core and is wired in as :core-jvm by settings.gradle.kts.
