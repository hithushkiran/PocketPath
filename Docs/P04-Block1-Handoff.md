# P04 Block 1 handoff

## Scope

Implemented on `feature/goals-overview`. This block adds only platform-neutral calculations and their focused JVM tests:

- `app/src/main/java/com/pocketpath/domain/calculation/GoalCalculations.kt`
- `app/src/main/java/com/pocketpath/domain/calculation/FundsPlanningCalculations.kt`
- `app/src/test/java/com/pocketpath/domain/P04PlanningCalculationsTest.kt`

The supplied implementation was applied unchanged. No existing production files, shared contracts, dependencies, navigation, UI or persistence were changed.

## Calculation behavior

- Goal progress uses effective POSTED reservation events from complete, user-scoped ledger history at the supplied instant. Future and voided events are excluded; account-scoped history is rejected. Plan-linked net contributions remain separate from total goal reservations. This projection does not authorize allocation funding or change goal lifecycle state.
- Savings pace uses explicit remaining contribution dates and exact LKR minor-unit arithmetic. Any one-cent remainder is distributed to the earliest dates so installment totals remain exact.
- Funds planning uses `F = L - R - C - P`: eligible balance minus goal reservations backed in eligible accounts, outstanding commitments, and remaining planned contributions. The horizon ends at calendar month end; supplied commitments must include overdue bills.
- Missing inputs remain unknown rather than becoming zero. Known-empty feeds use zero. Negative flexible funds remain visible, and arithmetic overflow fails explicitly.
- Purchase preview is a pure simulation and creates no records. A linked bill payment can release only its explicitly supplied commitment reservation, capped by the payment and outstanding commitments. Preview does not release goal money or change a plan.

## Verification completed in this finalization pass

Using Android Studio's JDK 21, ran the complete JVM suite once:

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

Result: **49 tests across 8 suites; 0 failures, 0 errors, 0 skipped**. Gradle reported `BUILD SUCCESSFUL`.

`git diff --check` passed. Because the implementation files were untracked, all three were also checked directly for trailing whitespace and conflict markers; none were found. No code corrections were required.

## Results from the preceding implementation pass

These results were obtained before this finalization pass:

- All **6 focused P04 planning calculation tests passed**, with no failures, errors or skipped tests.
- Debug assembly succeeded using JDK 21.
- All three implementation files matched the supplied code exactly.

The preceding command was:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.pocketpath.domain.P04PlanningCalculationsTest" :app:assembleDebug --console=plain
```

Debug assembly was not repeated during finalization because no code changed.

## Remaining scope and limitations

No UI, persistence, Firebase rules, emulator or device verification was completed for this block. JVM results and debug assembly do not establish live Firebase behavior or deployed-rule correctness. Goals screens and persistence remain future blocks; the UI remains at the P01 baseline.

No lint, emulator/device tests or live Firebase operations were run in this pass. No branch switch, merge, commit, push or deployment was performed. The three pre-existing untracked implementation files were preserved; this handoff is the only file created during finalization. No unexpected file changes or remaining verification blockers were found.
