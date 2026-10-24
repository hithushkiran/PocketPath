# P01 Block 3 Firebase and authentication verification

## Scope and preserved work

Verification on 24 October 2026 started on `feature/p01-foundation` with the applied Block 3 changes still uncommitted. The pre-existing `.idea/gradle.xml` change and all other user changes were preserved. No commit, push, Firebase Console access or rules deployment was performed.

The implementation adds email/password sign-in, registration, password reset, session gating, logout and user-scoped Firestore path access. Financial persistence and later-block features remain unimplemented. No real Firebase accounts are used by the JVM tests.

## Applied implementation checks

- The supplied catalog entries, Google Services plugin 4.5.0, main Auth/Firestore modules, coroutines Play Services and lifecycle aliases were applied consistently. Verification found that the supplied Firebase BoM 35.0.2 selects Auth 25.0.0, compiled with Kotlin metadata 2.4.0, which fails compilation with this project's Kotlin 2.2.10 (metadata 2.2.0). Firebase BoM was therefore pinned to 34.14.1, selecting Auth 24.1.0 and Firestore 26.3.0 according to the [official release mapping](https://firebase.google.com/support/release-notes/android). Existing Android, Kotlin, Compose, Hilt, SDK and Google Services plugin versions were retained. No compiler metadata checks were bypassed. The subsequent compilation, tests, APK assembly and lint passed with this pin.
- Manifest declares INTERNET. The application remains a Hilt application and MainActivity remains a Hilt entry point. Activity obtains AuthViewModel through ViewModelProvider and hosts AuthRoot in the existing theme.
- AuthRepository contains platform-neutral session/result/error contracts. FirebaseAuthRepository uses coroutine Task.await, an AuthStateListener removed on flow closure, and cancellation propagation. Email is trimmed; passwords are passed unchanged and not persisted by the implementation. Reset UI uses a generic success message.
- FirebaseModule supplies singleton FirebaseAuth, FirebaseFirestore and AuthRepository. No sample financial repository is bound into production.
- AuthRoot collects state with lifecycle awareness, gates the shell by session, and keys the signed-in navigation tree by UID. Auth forms support login/registration/reset, busy controls and transient password state. Settings receives identity, busy/error state and logout callback through the two supplied PocketPathApp edits; unrelated navigation and its preview remain intact.
- UserFirestorePaths derives UID from the current authenticated session. UserFirestoreScope checks that its captured UID is still active before returning paths. Later repositories must still detach listeners and clear state on session changes; client checks do not replace server rules.
- Local `firestore.rules` exactly matches the supplied second, corrected block. It permits owner reads for the profile and supported direct child collections, denies writes and denies other access. The unnecessary literal collection match is absent. This is a static source comparison, not an emulator/security-rule test or proof of deployed rules.
- Catalog TOML and manifest XML parse. Auth string references resolve uniquely. `git diff --check` passed.

## Configuration requirements

`app/google-services.json` exists and contains an Android client package matching `com.pocketpath`. No other configuration values were printed, copied into documentation or modified. The file is ignored by Git. This package check does not prove that the remote project/provider is configured correctly.

Android Studio must use the existing compatible Gradle JDK (JDK 21), SDK platform 36.1 and access to the configured dependency repositories. The actual Firebase project must have Email/Password authentication enabled and permit the intended password policy. The Google Services Gradle plugin consumes the local configuration during the build; do not commit that file or private credentials.

Firestore remains read-only under the initial rules. No user-profile or financial-document creation is implemented here; Firebase Authentication registration is separate from Firestore writes. The user reports that the initial rules were published; this task did not deploy them or inspect the remote project's rules independently.

## JVM tests added

`app/src/test/java/com/pocketpath/feature/auth/AuthViewModelTest.kt` uses a fake AuthRepository, controlled StateFlow emissions, StandardTestDispatcher and deferred responses. Main dispatcher is replaced/reset per test and ViewModelStore is cleared for scope cleanup.

Seven tests cover:

1. Repository session changes propagate to exposed session state.
2. Repeated submissions while busy issue only one request, including cross-action submissions.
3. Failure results release busy state and allow a successful retry.
4. Unexpected exceptions also release busy state and permit retry.
5. Password mismatch never calls repository registration.
6. Sign-out calls the repository and its SignedOut emission reaches the state consumed by AuthRoot.
7. Password reset exposes generic success; sign-in trims email and preserves password whitespace.

Added `kotlinx-coroutines-test` through the existing version catalog, reusing coroutines 1.10.2, as a JVM test dependency. No production dependency versions were upgraded. These tests exercise ViewModel behavior, not Firebase network calls or Compose rendering.

## Commands and actual results

Requested command (using the existing wrapper, session-only Android Studio JDK/user cache selection):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

Actual results:

- Initial run with supplied BoM 35.0.2: failed at `:app:compileDebugKotlin` due to Auth 25.0.0 Kotlin metadata incompatibility. Dependency resolution took several minutes but completed; this was a compiler compatibility failure, not an unresolved download blocker. JVM tests and lint did not complete in that initial run.
- After the scoped BoM 34.14.1 fix, the same requested command completed **BUILD SUCCESSFUL in 2m 3s**, 59 actionable tasks (32 executed, 27 up-to-date), with configuration cache stored.
- `:app:testDebugUnitTest`: **37 tests passed, zero failures/errors/skips**. This includes **all seven new AuthViewModel tests** and 30 existing tests. Report: `app/build/reports/tests/testDebugUnitTest/index.html`.
- `:app:assembleDebug`: **passed**, including Google Services configuration processing, Kotlin/Java compilation and Hilt code generation/compilation. APK: `app/build/outputs/apk/debug/app-debug.apk`.
- `:app:lintDebug`: **passed with zero errors and 26 warnings**. Warning categories are retained target/compile SDK and dependency versions, redundant manifest label and unused resources. No errors were suppressed, rules weakened, lint baseline added or unrelated dependency versions upgraded. Report: `app/build/reports/lint-results-debug.html`.
- `git diff --check`: passed. Local corrected rules comparison, auth resource uniqueness, configuration package match and configuration ignore check passed. These are source/configuration checks, not deployed Firebase or device verification.

Changes made during verification were limited to the compatible Firebase BoM pin, the coroutines-test catalog/test dependency, seven AuthViewModel JVM tests and this document. The supplied production Kotlin implementation and Firestore rules required no source changes. There are no remaining JVM/build/lint/download blockers in this environment. Subsequent user-reported manual results are recorded below, separately from agent-run checks.

## User-reported manual results

The user reports:

- Authentication/device checks **passed**.
- Initial Firestore rules **were published**.
- Rules Playground results:
  - Owner read: **allowed**.
  - Cross-user read: **denied**.
  - Unauthenticated read: **denied**.
  - Owner write: **denied**.

These are user-reported results, not checks independently performed by the agent. This documentation-only update did not rerun verification or deploy rules. Device/API details and individual authentication scenarios were not specified in the report.

## Detailed manual checklist

Retained for coverage tracking; the broad user-reported pass above does not establish which individual scenarios or device configurations were exercised:

- Launch on API 26 and a current supported Android device/emulator. Check session loading and signed-out gate; confirm no financial/sample records appear.
- With authorized test accounts, exercise registration, sign-in, invalid credentials, network failure, rate limiting and password reset (including an unknown email). Verify actual Firebase behavior and project password policy without logging credentials.
- Check repeated taps, IME submission, password show/hide, registration confirmation, mode switching, system Back, keyboard insets, landscape/small screen, 200% text, dark mode and TalkBack.
- Check session restoration after process restart, rotation behavior and transient passwords. Verify Settings logout returns to auth UI and Back cannot expose the signed-out navigation tree. Repeat with different UIDs.
- The four reported Rules Playground cases are recorded above. Additional cases, such as unknown collection paths, were not specifically reported.
- Carry forward the remaining Block 1 tab/Add/Accounts/Settings navigation and accessibility checks. JVM/build/lint results do not replace these checks.
