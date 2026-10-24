# P01 Block 4 storage foundation

## Implementation and scope

Block 4 adds FoundationCodec, FirebaseFoundationRepository and FirebaseFoundationFactory, plus the supplied local Firestore ruleset. The adapter supports default-profile provisioning, atomic LKR account/opening creation, ordinary LKR expense persistence, complete confirmed history observations and balances derived using existing domain calculations. Replay comparisons ignore server audit timestamps and use microsecond-normalized financial timestamps. The factory creates repositories for the authenticated session and invalidates old sessions.

Writes currently require online Firestore transactions. Categories and UI integration are deferred to Block 5; offline submissions and advanced financial events remain later work. Existing authentication, UI, domain contracts, sample repositories and dependency/build-tool versions were left unchanged in this verification pass.

## Minimal verification — 24 October 2026

Added exactly three JVM tests in `app/src/test/java/com/pocketpath/data/firebase/FoundationCodecTest.kt`, reusing SampleFixtures and existing ledger calculations:

1. Encode/decode stored account, opening and expense payloads with resolved audit timestamps: LKR 10000 minus LKR 850 produces 915000 minor units (LKR 9150), with zero opening income.
2. Equivalent timestamp-normalized event payloads compare equal despite different server audit times; changed amounts compare unequal. Account replay also ignores createdAt/updatedAt audit differences.
3. Floating-point movement/component amounts and inconsistent account/category/goal/income-source query arrays are rejected by the mapper.

Ran once using the existing wrapper, Android Studio JDK 21 and user Gradle cache:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Result: **BUILD SUCCESSFUL in 27s**, 50 actionable tasks (20 executed, 30 up-to-date). **40 JVM tests passed**, including all three new mapper tests; zero failures, errors or skips. Debug APK assembly passed. No Block 4 compilation errors or test failures required production fixes, and no rerun was needed.

No lint, emulator/device tests, real Firebase calls, repository integration tests or rules deployment/verification were performed. Real Firestore/repository integration and the Block 4 ruleset remain **unverified**. Block 3's user-reported initial rules checks do not verify this replacement ruleset. Mapper payload tests are not evidence of online transaction behavior, listener/session cleanup or server-side rule enforcement.

## Block 5 handoff

- Inject FirebaseFoundationFactory and obtain the current authenticated account/ledger repository through `current()`; do not bind user-owned repository instances as application singletons.
- Connect the existing UI/ViewModels and category handling in Block 5. Consume repository observations and errors through the established contracts; use complete confirmed history for balances, never displayed Activity pages or incomplete cached snapshots.
- Retain command operation IDs, financial times and exact minor-unit amounts for retries. Present online/network failures accurately; Block 4 does not provide offline expense finalization.
- Before claiming live persistence works, verify profile provisioning, atomic account/opening creation, expense posting with a valid same-user category, replay/conflict behavior, history/balance updates and logout/session invalidation against authorized test data.
- Independently verify the new Block 4 rules for valid coupled writes and denied invalid/cross-user/unauthenticated writes before any authorized deployment. No deployment was performed here.

Work remains on `feature/p01-foundation`; pre-existing changes were preserved. No commit or push was performed. There are no blockers to the requested JVM/build checks; live integration and rules verification remain outstanding.
