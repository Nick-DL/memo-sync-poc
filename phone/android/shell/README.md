# `:shell` — buildable SaltUI shell (CI)

This module exists so GitHub Actions can prove the phone toolchain works without the Huawei
Wear Engine SDK AAR or Room KSP:

- JDK 21 toolchain
- AGP 9.1.1 with `android.builtInKotlin=false` (SaltUI needs Kotlin 2.4.20; AGP 9.1.1 built-in is 2.2.10)
- Kotlin 2.4.20 (external `org.jetbrains.kotlin.android` plugin)
- Compose Multiplatform 1.12.2
- SaltUI 3.0.0

It uses only APIs verified against SaltUI 3.0.0 sources (`SaltTheme`, `SaltConfigs`,
`BasicScreen`, `ScreenCard`, `Item`, `Button`, `SaltTheme.dimens`).

Build (from `memo-sync-poc/phone/android`, JDK 21 on PATH):

```bash
gradle :shell:assembleDebug
```

CI: `../../.github/workflows/phone-build.yml` (repo root = `memo-sync-poc`).

The full `:app` skeleton adds the real SaltUI screens, Room and Wear Engine transport; it is
intentionally not part of the default CI target until the vendor SDK and Room KSP are wired in.
