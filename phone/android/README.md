# Phone Android app — SaltUI skeleton + buildable `:shell` (CI)

Two modules:

- `:shell` — **buildable in GitHub Actions**. A small SaltUI app shell (theme + list/settings
  cards) that uses only APIs verified against SaltUI 3.0.0 sources. CI builds
  `:shell:assembleDebug` by default. This is the "does the Android/SaltUI toolchain work?"
  artifact, not the final product.
- `:app` — the full source skeleton (SaltUI screens + Room + Wear Engine transport). It is still
  **source-only** until the Huawei Wear Engine SDK AAR and Room KSP are added; CI can try it via
  `workflow_dispatch` target `:app:assembleDebug`.

The tested implementation remains the Java 17 core in `../core/` (see `../README.md`); the shell
does not duplicate sync logic.

**Local blockers** (why this machine builds neither module directly)

- The Android SDK **does** exist (`D:\Android\Sdk`: platforms android-33/34/36/36.1,
  build-tools 30.0.3/36.0.0/36.1.0/37.0.0), but that alone is not enough.
- SaltUI 3.x targets **JDK 21** (AGP 9.1.1 / Kotlin 2.4.20 / Compose Multiplatform 1.12.2), while
  this POC environment only has **JDK 17** (`javac`/`java` 17.0.14); there is no `kotlinc` on PATH.
- `:app` additionally needs the Wear Engine SDK dependency and real signing fingerprints
  (`peerFingerPrint`) for debug/release flavors, which cannot be produced offline in the POC.
- CI is the intended build path: `.github/workflows/phone-build.yml` installs JDK 21, Android
  SDK 36, Gradle 9.3.1 and builds the shell APK.

## UI stack: SaltUI (not plain Material)

Requirement: phone frontend uses **SaltUI** (https://github.com/Moriafly/SaltUI), a Compose
Multiplatform design system. Sources: the official docs at
<https://moriafly.com/program/salt-ui/> and <https://github.com/Moriafly/SaltUI>.

Verified against the official docs (fetched while writing this skeleton) and SaltUI 3.0.0 sources:

- `SaltTheme`, `SaltConfigs`, `Text`, `Icon`, `Item`, `Button` → `com.moriafly.salt.ui`
- `BasicScreen`, `ScreenCard`, `TitleBarButton` → `com.moriafly.salt.ui.screen`
- `SaltIcons` and its icon extensions (`Check`, `ChevronRight`, ...) → `com.moriafly.salt.ui.icons`
- `TitleBarButton` takes a named `icon = { ... }` lambda (not a trailing content lambda).

| API | Source page | Usage here |
|---|---|---|
| `SaltTheme(configs = SaltConfigs.default(isDarkTheme = ...))` | [安装与接入](https://moriafly.com/program/salt-ui/getting-started/installation.html), [主题](https://moriafly.com/program/salt-ui/foundations/theme.html) | app root in `MainActivity.kt` / `ui/MemoScreens.kt` |
| `Text`, `Icon`, `SaltIcons` (`com.moriafly.salt.ui.*`) | [基础组件](https://moriafly.com/program/salt-ui/components/primitives.html) | all body text/icons |
| `BasicScreen(onBack, title, toolButtons) { contentPadding -> ... }` (+ `@OptIn(UnstableSaltUiApi::class)`) | [构建页面](https://moriafly.com/program/salt-ui/screen.html) | every screen |
| `ScreenCard(header = ..., footer = ...) { ... }` | [构建页面](https://moriafly.com/program/salt-ui/screen.html), [Item 元项](https://moriafly.com/program/salt-ui/components/items.html) | list/edit/conflict/settings sections |
| `Item(text = ..., onClick = ...)`, `ItemSwitcher`, `ItemValue`, `ItemEdit`, `ItemTip` | [Item 元项](https://moriafly.com/program/salt-ui/components/items.html) | list rows, settings rows, title editing |
| `Button(onClick = ..., text = ...)`, `Card`, `RoundedColumn` | [基础组件](https://moriafly.com/program/salt-ui/components/primitives.html) | primary actions |
| `TitleBarButton`, `BasicScreenDefaults.BackButton` | [构建页面](https://moriafly.com/program/salt-ui/screen.html) | title-bar actions |
| `Dialog` / `AdaptiveDialog` (overlays module) | [弹层与对话框](https://moriafly.com/program/salt-ui/components/overlays.html) | delete/conflict confirmation placeholder |

Uncertain APIs are **not invented**: any component whose exact parameter list could not be
confirmed from the docs (e.g. `Dialog` content lambda, `innerPadding()` import path,
`SaltDynamicColors` import package, `SaltIcons` member names beyond the documented `Check`) is
marked `TODO(SaltUi)` in the source and must be checked against the pinned SaltUI 3.0.0 sources
when the build environment exists.

### Where SaltUI is used

- `MainActivity.kt`: `SaltTheme(configs = SaltConfigs.default(isDarkTheme = isSystemInDarkTheme()))`
  wraps the whole app (single theme root, documented setup).
- `ui/MemoScreens.kt`:
  - **list** — `BasicScreen(actionButton = null, title, toolButtons = { TitleBarButton { ... } })`,
    `ScreenCard(header/footer)`, `Item(text, onClick)` rows, `Button(text = "新建备忘录")`;
  - **edit** — `BasicScreen(onBack, title, toolButtons = { TitleBarButton(save) })`,
    `ScreenCard` for title/body, salted `BasicTextField` using `SaltTheme.textStyles.main` /
    `SaltTheme.colors.*`, `Button(text = "保存")`;
  - **conflict** — two `ScreenCard`s (phone version / watch version) built from `Item`s plus
    `Button`s for "keep phone / keep watch"; confirmation dialog is a `TODO(SaltUi)` pointing at
    the SaltUI overlays docs;
  - **settings** — `ScreenCard`s with `Item` rows for watch connection / sync / merge summary /
    protocol version.
- Navigation is a small `rememberSaveable` state machine; SaltUI's optional
  `salt-ui-navigation` module is referenced but not required by the skeleton.

## Gradle dependencies (reference only)

`io.github.moriafly:salt-ui:3.0.0` is resolved from Maven Central (see `../build.gradle.kts` /
`app/build.gradle.kts`). SaltUI docs require `mavenCentral()` and say to check the version
compatibility table before pinning: <https://github.com/Moriafly/SaltUI#compatibility>.

- UI module: `io.github.moriafly:salt-ui:3.0.0` — Android `minSdk 23`.
- Optional navigation module (not used; we use simple state navigation to keep the skeleton
  small): `io.github.moriafly:salt-ui-navigation:<same version>` — `minSdk 24`.
- Compose Multiplatform / Kotlin / AGP versions: pinned per the project requirement
  (Kotlin 2.4.20, Compose Multiplatform 1.12.2, AGP 9.1.1). These must be validated together;
  the official docs say not to guess compatibility.

## File map (source-only)

```
android/
  AndroidManifest.xml                 # <queries> for com.huawei.health, HMS appid, NO Bluetooth permission
  build.gradle.kts                    # plugin versions (AGP/Kotlin/CMP)
  settings.gradle.kts                 # includes :app, :shell and :core-jvm (mapped to ../core)
  app/build.gradle.kts                # SaltUI + Wear Engine + Room/Compose deps (reference)
  app/src/main/java/com/example/memosync/
    MainActivity.kt                   # SaltTheme root, activity lifecycle -> sync triggers
    ui/MemoScreens.kt                 # SaltUI list/edit/conflict/settings screens
    sync/Transport.kt                 # Kotlin transport interface (mirrors core Transport)
    sync/SyncManager.kt               # Android wrapper: Java core SyncManager + coroutines + Room
    sync/WearEngineTransport.kt       # Wear Engine P2P stub (real SDK calls, not compiled here)
    data/MemoEntity.kt                # Room entities (memos / peer base / sync state)
    data/MemoDao.kt                   # Room DAOs + RoomMemoStore implementing the Java MemoStore
    data/AppDatabase.kt               # Room database outline
  shell/                              # buildable SaltUI-only APK for CI
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/java/com/example/memosync/shell/MainActivity.kt
```

## What is real vs. mocked here

| Piece | Status |
|---|---|
| HLC, merge engine, protocol codec, chunking, sync state machine | **Real and tested** in `../core/` (Java 17) |
| SaltUI screens | `:shell` is **buildable** (verified APIs). Full `:app` screens are source-only; dialog/overlay details remain TODO(SaltUi) |
| Room persistence | Outline only (entities/DAOs/Store adapter); no migration/config tested |
| Wear Engine P2P transport | Stub mirroring the decompiled reference (`HiWearHelper.java` / `HuaweiController.java`, read-only reference); not compiled, needs SDK + fingerprints |
| Manifest `queries` / HMS appid / no Bluetooth permission | Written against the design doc §7.3; not validated by `aapt` in this environment |

## How to build

CI: `.github/workflows/phone-build.yml` (JDK 21 + Android SDK 36 + Gradle 9.3.1).

- Default target: `:shell:assembleDebug` → buildable SaltUI shell APK.
- Full app: run the workflow manually with target `:app:assembleDebug` after adding:
  1. Wear Engine SDK (HMS) dependency and HMS appid;
  2. Room KSP plugin matching Kotlin 2.4.20;
  3. `peerPkgName` / `peerFingerPrint` for debug/release (design doc §7.1).

## TODOs before this can ship

- [ ] Confirm remaining SaltUI 3.0.0 API details (`Dialog`, `innerPadding`, `SaltDynamicColors`,
      optional `Item` params) against the pinned sources; `BasicScreen`/`ScreenCard`/`Item`/
      `Button`/`SaltTheme` used by `:shell` are verified.
- [ ] Wear Engine SDK: real `P2pClient`/`DeviceClient`/`MonitorClient` callbacks (task callback
      type differs between SDK versions; reference uses a wrapper with success/failure order
      `OooO0O0`/`OooO00o`).
- [ ] Register the P2P receiver at app start (not at session start) so watch `sync.request`
      frames are not missed (design doc §5.6).
- [ ] Foreground service for background sync (optional, see design doc §5.4).
- [ ] Room migrations + conflict table (`conflicts` in design doc §3.3).
- [ ] Certificate pinning / signing flavors; HMS appid for release.
