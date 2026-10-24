# P01 Block 6: durable drafts and offline expense receipts

## Implemented behavior

`data/local/FinanceLocalStore.kt` stores per-user drafts, frozen commands, expense receipts and query-coverage IDs as JSON using Android `AtomicFile` in `noBackupFilesDir`. Filenames are derived from the UID; reads validate the stored UID and format version. These records are not a second ledger or an application-managed posting queue.

`data/repository/OfflineExpenseWriter.kt` supports ordinary LKR expenses using cached account/category references. A durable receipt is prepared before handing the write to Firestore. Firestore owns transport of pending writes. Retrying an uncertain operation retains its original identity and content; conflicting content is rejected. Restart reconciliation waits for pending writes and reads server documents without submitting receipts again. Account creation still requires an online transaction.

`feature/finance/FinanceDraftController.kt` restores forms, frozen commands and receipt states, then autosaves changes. FinanceViewModel initializes `draftRevision` from the stored revision before starting autosave. Profile/category provisioning runs asynchronously after restoration, so an online bootstrap cannot block cached references or drafts.

Repository metadata distinguishes cache/server origin, completeness and pending operation IDs. Pending events may have an unresolved server audit timestamp; confirmed decoding still requires a timestamp. Complete-history coverage gates balance calculations; Activity pages remain separate and are not used to calculate balances.

The Accounts/Add/Activity screens and shared components expose draft, cache, pending, confirmed and rejected states. Accepted expenses retain their input and frozen command until “Record another expense.” Rejected entries retain input for review; editing a rejected entry clears its old command so the replacement receives a new identity. AuthRoot warns about unresolved local work and flushes drafts before signing out.

## Focused integration inspection and fix

Inspection was limited to restoration/autosave order, persistence before submission, read-only restart recovery, session isolation and save/input status handling:

- Restoration completes and the stored revision is assigned before `startWatching()`.
- Save handlers flush the controller before calling the repository; the writer also persists a receipt before `set()`.
- Startup calls reconciliation, which performs no expense submission. Uncertain PREPARED/PENDING receipts are checked rather than automatically resubmitted.
- `collectLatest` cancels old session children; session changes clear controller/command references and replace financial state. Controllers check their scope and UID; asynchronous ViewModel results check their generation. Repository session closure detaches listeners. AuthRoot only renders financial state matching the authenticated UID and keys navigation by UID.
- PREPARED retains a locked uncertain request; PENDING and CONFIRMED retain saved input with distinct status; REJECTED retains input and exposes review/editing. This is source inspection, not device verification.

The initial compile found an extra comma after the sign-in error string inside an `if` expression in `OfflineExpenseWriter.kt`. Removed that comma. No other application changes were made during verification. Existing Block 6 changes were preserved on `feature/p01-foundation`; dependencies and Firestore rules were unchanged.

## Verification — 24 October 2026

Added exactly three JVM tests to the existing `FoundationCodecTest`:

1. Pending expense decoding accepts a null server timestamp while preserving identity and movement amount.
2. Confirmed decoding rejects null/missing server timestamps and accepts a resolved timestamp.
3. A serialized local finance file written to a temporary file and read back preserves UID, operation ID and the exact 85,001-minor-unit expense amount.

The third test exercises the persisted JSON representation and command conversion. It does not exercise Android Context, AtomicFile crash recovery or actual cross-user filesystem access.

Requested command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

The first shell invocation stopped before Gradle tasks because the default `C:\.gradle` location could not create its wrapper lock directory. Retried with session-local environment settings:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = 'C:\Users\User\.gradle'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

That run failed at `:app:compileDebugKotlin` with the syntax error above (BUILD FAILED in 9s). After the fix, reran the two affected tasks with the same command: **BUILD SUCCESSFUL in 26s**, 50 actionable tasks (16 executed, 34 up-to-date), configuration cache reused. **43 tests passed across 7 suites, with 0 failures, 0 errors and 0 skips**, as recorded in the JVM XML reports. Debug assembly and Hilt/Kotlin compilation passed. No dependencies or test infrastructure were added.

No lint, emulator/device checks, live Firebase operations, rules deployment, commit or push was performed. There are no remaining blockers to these JVM/build checks.

## Outstanding device checks and limitations

- Prepare account/expense drafts, restart/force-stop the app, and confirm restoration without an initial autosave overwriting them. Check local save errors and logout flush behavior.
- Load references online, go offline, save an expense, and verify pending status and exact input survive restart. Reconnect and confirm one cloud event, one balance effect and confirmed status. Repeated uncertain retries must retain the operation ID.
- Exercise an actual server rejection; confirm retained input, review/clear/edit behavior and a deliberate retry. Confirm no stored receipt is submitted automatically at startup.
- Switch between two real UIDs with drafts and pending work; confirm observers stop, no previous-user data appears, and each user's drafts return only for that user. Check sign-out warnings and back navigation.
- Verify cached/incomplete-history labels, withheld incomplete balances and cached Activity independently. Verify account creation remains online-only.

Real Firestore integration, deployed permissions, network/process-death timing and AtomicFile recovery remain unverified. Drafts are device-local, excluded from backup, and are not cross-device drafts; clearing app data/uninstalling removes them. Cached references must first have been loaded. No automatic receipt posting queue or later financial features were introduced.

## Combined Blocks 7–8 handoff

Consume the existing domain contracts, UID-scoped repository factory, complete-ledger metadata, retained operation IDs and shared financial UI state. Coordinate shared-contract/navigation changes across the three ownership areas. Keep Activity pagination separate from complete calculations and preserve exact money, replay safety, draft restoration and session isolation when integrating the remaining assigned work. Carry the device checklist above into the combined Blocks 7–8 integration and final manual verification; this build/test pass does not establish live Firebase correctness or deployed-rule behavior.
