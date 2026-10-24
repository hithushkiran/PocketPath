# P01 Block 5 financial journey

## Implemented journey and files

Block 5 connects the signed-in user to profile/category provisioning, LKR cash-account creation with an opening event, ordinary expense entry, recorded balances and paginated Activity. Home provides account setup and expense entry actions. Accounts shows the creation form and balances; Add supports account/category/classification selection and expense saving; Activity distinguishes openings from expenses. Plan, Goals, Settings, bottom tabs and app-bar navigation remain in place.

Application files added or changed by the supplied implementation, relative to `app/src/main/java/com/pocketpath/`:

- `data/firebase/FoundationCodec.kt`: category encoding/decoding.
- `data/repository/FirebaseFoundationRepository.kt`: initial-category provisioning and observation.
- `feature/finance/FinanceState.kt` and `FinanceViewModel.kt`: shared forms, observations, commands and session state.
- `ui/components/FinanceComponents.kt`: shared financial presentation and selection components.
- `feature/home/HomeScreen.kt`, `feature/accounts/AccountsScreen.kt`, `feature/transaction/AddScreen.kt` and `feature/activity/ActivityScreen.kt`: connected financial screens.
- `navigation/PocketPathApp.kt`, `feature/auth/AuthRoot.kt` and `MainActivity.kt`: financial state and ViewModel wiring.

Root `firestore.rules` adds category creation validation and owner creation permission. No rules were deployed during verification.

## Minimal verification — 24 October 2026

Started on `feature/p01-foundation` with the supplied Block 5 changes present; existing changes were preserved. Ran the requested command once using the existing wrapper, Android Studio JDK 21 and user Gradle cache:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Result: **BUILD SUCCESSFUL in 25s**, 50 actionable tasks (17 executed, 33 up-to-date), configuration cache reused. **40 existing JVM tests passed**, with zero failures, errors or skips. Debug APK assembly, Kotlin/Java compilation and Hilt generation/compilation passed. No compilation/test failures required fixes, no rerun was needed, and no new test suite was added. Dependencies and build-tool versions were unchanged. Only this document was added during verification.

Brief source checks, not live interaction tests:

- Account/expense save handlers reject busy or saved forms and set `busy=true` synchronously before launching a request, preventing repeated UI taps from starting concurrent submissions.
- Pending command objects are reused before generating new IDs. Network/rejected/uncertain failures retain the command and lock input; retry therefore preserves the operation ID, amounts and financial timestamps.
- Successful forms set `saved=true`; both handlers reject another save until the explicit NewAccount/NewExpense action resets the corresponding form. The UI exposes “Create another account” / “Record another expense”.
- FinanceViewModel resets state and pending commands on sign-out or a different UID, scopes work to the current collection session and guards asynchronous results by generation. AuthRoot renders only financial state matching its authenticated UID, substitutes empty/loading state on mismatch and keys the navigation tree by UID. Signed-out UI does not render the financial shell.

## Outstanding live checks and Block 6

Live Firebase checks remain **outstanding**: profile/category creation, atomic account/opening persistence, expense saves and balance refresh, Activity pagination, retry/replay behavior, repeated taps and logout/user switching on actual devices. The existing JVM tests and successful build do not verify the new financial ViewModel's runtime journey or deployed Firestore permissions. The current category rules also require independent verification and any separately authorized deployment; earlier rules results do not verify this revision.

Saves remain **online-only Firestore transactions**. Forms and pending commands are **in-memory only**, not durable across process death. Offline posting, durable user-scoped drafts and recovery belong to Block 6; this block does not promise those capabilities.

No lint, emulator/device tests, broad review, live Firebase operations, rules deployment, commit or push was performed. There are no blockers to the requested JVM/build checks; live integration remains unverified.
