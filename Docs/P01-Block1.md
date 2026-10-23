# P01 Block 1 foundation

## Scope and inspected baseline

This block adds only project organization, Hilt and the Material 3 navigation shell. No authentication, Firebase, financial models, data access or calculations are implemented. Kavindu remains the persona; PocketPath is the product name.

The project started on `feature/p01-foundation`, with starter files already staged and `Docs/`, `.idea/` and daemon JVM configuration untracked. Those existing changes were preserved. No prior AGENTS.md was found in the project or its parent paths. Git inspection required a command-scoped safe.directory override because the sandbox account differs from the repository owner; no global Git configuration was changed.

Inspected settings, root/app build scripts, catalog, wrapper, daemon criteria, manifest, MainActivity, Compose theme and resource files. Readable text was extracted from all three references under `Docs/reference/`: the revised v1.1 implementation plan, v0.2 SRS and SE3092 assignment PDF. Extraction succeeded for each; source documents were not modified. Their wider implementation scope remains deferred beyond this block.

| Setting | Retained value |
| --- | --- |
| Namespace / application ID | `com.pocketpath` / `com.pocketpath` |
| Minimum / target SDK | 26 / 36 |
| Compile SDK | 36, minor API 1 (SDK platform 36.1) |
| Android Gradle plugin | 9.3.3, built-in Kotlin enabled |
| Kotlin / Compose compiler plugin catalog | 2.2.10 |
| Gradle wrapper | 9.5.0, existing checksum and JAR preserved |
| Java source / target | 11 / 11, unchanged |
| Gradle daemon criteria | Java 21 in existing `gradle-daemon-jvm.properties` |
| Explicit application Java/Kotlin toolchain | None in template; unchanged |
| Installed Android Studio JBR used for checks | 21.0.10 |
| Compose BOM | 2026.02.01 |

## Package locations and responsibilities

All paths below are relative to `app/src/main/java/com/pocketpath/` in the existing app module.

```text
PocketPathApplication.kt       Hilt application
MainActivity.kt                Hilt activity, edge-to-edge, theme and shell
navigation/                   Serializable destinations, graph, tabs and app chrome
ui/theme/                     Existing light/dark/dynamic Material 3 theme
ui/components/                Shared scrollable placeholder presentation
feature/home/                 Financial overview (Hithush)
feature/activity/             Shared transaction history presentation
feature/plan/                 Spending and planning (Tharukshan)
feature/goals/                Goals (Hithush)
feature/transaction/          Shared Add entry point
feature/accounts/             Accounts (Sugeerthan)
feature/settings/             App settings
```

Reserved future package locations (documented, not filled with empty classes): `di` for Hilt modules; `domain.model` and `domain.repository` for Block 2 shared contracts; `data.repository` for real implementations; `feature.income` and `feature.spending` for owned flows. Feature ViewModels will coordinate repository calls through coroutines and expose immutable StateFlow; Composables consume state and send events. No ViewModel or fake repository is needed for these static placeholders.

## Navigation and UI

Navigation Compose uses Kotlin serialization objects for typed destinations. The bottom bar contains Home, Activity, Plan and Goals. Switching tabs uses `popUpTo(findStartDestination())` with saved state, `launchSingleTop` and `restoreState`. Selection derives from the current back-stack destination, including after recreation.

The labelled Add FAB opens a separate destination. More opens a menu with Accounts and Settings. Secondary screens hide the bottom bar/FAB and offer a labelled accessible Back icon; system Back also pops to the originating tab. Repeated taps use single-top navigation. Top-level system Back follows Navigation Compose's stack behavior back to Home, then exits.

Scaffold padding and consumed insets keep content clear of system/app bars; IME padding and scrollable screen content support reduced space. Manifest `adjustResize` is retained. Labels supply semantics for tab, menu and FAB icons; the standalone Back icon has a description. All screen text is in string resources. No invented balances, records or sync states appear. The existing dynamic/light/dark theme is reused and an app preview replaces the greeting preview.

## Dependency choices

Added pinned Hilt runtime/compiler/plugin 2.60.1 and KSP 2.3.10. Hilt's current setup documents the KSP configuration; KSP documents Kotlin 2.2.10 compatibility and AGP 9/Gradle 9 support. Added Navigation Compose 2.9.7, serialization JSON 1.9.0 and Kotlin serialization plugin matching the existing Kotlin 2.2.10 compiler plugin. Material icons core follows the existing Compose BOM. No Kotlin Android plugin is added because AGP supplies built-in Kotlin. No existing toolchain or SDK was upgraded. The template's Core KTX 1.19.1 failed AAR metadata checks because it requires compile SDK 37; it is pinned to 1.18.0, which supports the existing SDK 36.1 ([Core release notes](https://developer.android.com/jetpack/androidx/releases/core#1.18.0)). All other existing versions are retained.

Compatibility references: [Hilt setup](https://dagger.dev/hilt/gradle-setup), [Dagger KSP](https://dagger.dev/dev-guide/ksp.html), [KSP compatibility](https://kotlinlang.org/docs/ksp-overview.html), [typed Navigation Compose routes](https://developer.android.com/guide/navigation/design/type-safety), [Navigation releases](https://developer.android.com/jetpack/androidx/releases/navigation).

## Android Studio manual verification

1. Open this existing project, select JDK 21 for Gradle, install SDK platform 36.1 and sync. Run `app` on API 26 and on a current API 36 device/emulator. Expect Home with no greeting, fake financial data or crash.
2. Tap Activity, Plan and Goals. Confirm the matching title, purposeful placeholder and one selected bottom tab. Tap each selected tab repeatedly; Back must not traverse duplicate copies.
3. From each of the four tabs, open Add. Confirm its entry explanation and absence of extra permanent tabs. Use toolbar Back and repeat with system Back; return to the exact originating tab.
4. From each tab, open More > Accounts, then More > Settings in separate visits. Verify titles and placeholder copy. Verify both toolbar and system Back return to the originating tab. Dismiss More by tapping outside and by Back.
5. Switch Home > Activity > Plan > Goals > Home repeatedly. On a non-Home tab, system Back should return Home and another Back should exit. Relaunch and confirm Home.
6. Rotate on Goals and on each secondary destination. Verify the current destination and selected tab survive; Back from the recreated secondary destination still returns to its origin. Tab save/restore is configured; meaningful feature state will need verification when it exists.
7. Try a small phone, landscape, light/dark modes, dynamic color on Android 12+, 200% font size and TalkBack. Verify tab/menu/Add labels, Back announcement, readable scrollable copy, reachable controls and no overlap with system bars. There are no text inputs in Block 1; recheck IME behavior when entry fields arrive.
8. Inspect the app preview in Android Studio. Record device/API, build and actual outcomes; these manual checks are not claimed as executed here.

## Validation results

Checks run on 23 October 2026 using Android Studio's JBR 21.0.10 and the existing user Gradle cache:

- `:app:assembleDebug --offline --no-configuration-cache --console=plain`: **BUILD SUCCESSFUL**, 41 tasks (4 executed, 37 up-to-date). This includes successful Kotlin/Java compilation, Hilt KSP generation, Hilt component compilation, AAR metadata validation and APK packaging. APK: `app/build/outputs/apk/debug/app-debug.apk`.
- `:app:lintDebug --offline --no-configuration-cache --console=plain`: **blocked**, task `:app:lintAnalyzeDebug` could not resolve `com.android.tools.external.com-intellij:intellij-core:32.3.3` and `com.android.tools.external.com-intellij:kotlin-compiler:32.3.3`. Their JARs are not cached. Online combined build/lint attempts stalled downloading these tools and were interrupted. Lint analysis did not complete; no lint pass is claimed.
- The first wrapper attempt failed because this sandbox defaulted its cache to unwritable `C:\.gradle`; the checks above used the existing `C:\Users\User\.gradle` cache through a session environment override. No machine path was added to project configuration.
- The initial debug build detected the template's Core KTX 1.19.1 / compile SDK 36.1 mismatch. After the documented 1.18.0 pin, AAR metadata validation and the debug build passed. A subsequent offline attempt needed uncached transitive AndroidX AARs; an online retry resolved them before the final successful offline build.
- `git diff --check`: passed. All main manifest/resource XML and the version catalog parsed successfully. Ignore-rule checks confirmed build output, local.properties, sample signing and service-account filenames are excluded; the existing wrapper JAR remains tracked and is not ignored.
- No device/emulator, manual navigation, accessibility, preview or instrumentation checks were run here. No placeholder-text or empty-class tests were added. Existing starter tests are preserved and were not run.

Run from Android Studio's terminal with Gradle offline mode disabled and network access to Google Maven and Maven Central:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug --console=plain
```

If lint tooling resolution also causes configuration-cache serialization errors, retry `.\gradlew.bat :app:lintDebug --no-configuration-cache --console=plain` after restoring dependency access. Do not change AGP, Gradle or SDK versions to work around missing cached downloads.

Remaining placeholders: all seven destinations contain explanatory static content. Shared financial models and repository contracts begin in Block 2; real ViewModels, financial operations, persistence, Firebase and authentication remain deferred. The only outstanding validation blocker is lint tooling download availability. Changes are left unstaged over the original staged template for review; no commit or push was performed.
