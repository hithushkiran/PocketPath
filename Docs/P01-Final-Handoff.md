# PocketPath P01 final handoff — combined Blocks 7–8

## Baseline and scope

PocketPath helps Kavindu understand income, spending and savings goals. The implemented baseline includes Compose/Material 3 navigation, Hilt, Email/Password registration/sign-in/password reset/logout, default profile/category provisioning, LKR cash-account creation with one OPENING event, ordinary LKR expenses, complete-history account balances, Home/Activity presentation, device-local drafts and receipts, and Firestore-managed offline expense writes using cached references. Plan and Goals remain placeholders; Settings provides authentication/logout controls, not financial preference editing.

Production persistence supports only LKR OPENING and ordinary EXPENSE. Domain models and extension contracts also represent later work; their presence does not enable production flows. Income/expected payments, schedules/commitments, recurrence processing, budgets, goal funding/calculations, transfers, FX/crypto, refunds/corrections, reminders and account/category editing or archival remain deferred. No new feature flows were added in Blocks 7–8.

## Shared contracts and integration points

Paths below are repository-relative under `app/src/main/java/com/pocketpath/`:

| Surface | Files and responsibilities |
| --- | --- |
| Shared records | `domain/model/Records.kt`, `Transaction.kt`: records, financial dates, immutable canonical envelopes and derived reference IDs. |
| Exact finance | `domain/money/Money.kt`: Money, EntryAmount, CryptoQuantity, valuation primitives and bounds. `domain/validation/TransactionValidator.kt`: structural checks. `domain/calculation/LedgerCalculations.kt`: native balances and basic period totals. |
| Repository contracts | `domain/repository/Repositories.kt`: AccountRepository, LedgerRepository, CategoryRepository and later profile/income/schedule/goal/budget/rate/protected-ledger surfaces. Commands include CreateAccountCommand and RecordExpenseCommand. CompleteLedger and ActivityPage are distinct. `RepositoryState.kt` supplies Observation/CommandResult and runtime origin/completeness/write metadata. `AuthRepository.kt` defines authentication/session contracts. |
| Authentication and scope | `data/repository/FirebaseAuthRepository.kt`, `di/FirebaseModule.kt`, `data/firebase/UserFirestorePaths.kt`: Firebase dependencies, authenticated UID paths and session checks. |
| Financial persistence | `data/firebase/FoundationCodec.kt` maps current storage shapes. `data/repository/FirebaseFoundationFactory.kt` creates per-session repositories. `FirebaseFoundationRepository.kt` implements AccountRepository/LedgerRepository and exposes profile/category bootstrap and category observation helpers. It does **not** implement every extension interface, including CategoryRepository's save operation. |
| Local/recovery | `data/local/FinanceLocalStore.kt`: AtomicFile JSON drafts, frozen commands, receipts and coverage per UID. `data/repository/OfflineExpenseWriter.kt`: expense handoff and read-only recovery. `feature/finance/FinanceDraftController.kt`: restoration, autosave and receipt presentation. |
| Presentation | `feature/finance/FinanceState.kt`, `FinanceViewModel.kt`; `feature/auth/AuthRoot.kt`; Home, Accounts, Add and Activity screens; `ui/components/FinanceComponents.kt`. MainActivity supplies ViewModels and AuthRoot gates the financial shell by UID. `navigation/Destinations.kt` and `PocketPathApp.kt` own typed routes and shared navigation. |
| Development samples | `data/sample/SampleFixtures.kt`, `InMemoryFinanceRepository.kt`: opt-in development implementations with no production binding. |

Retain exact fiat minor units, explicit currencies, checked arithmetic and final historical conversion rounding. Account movements alone determine balances; report components and reservations have separate meanings. Opening funds are not earnings. Unknown values remain explicitly unknown. Negative recorded balances warn rather than prevent a real expense.

Callers retain operation identity and frozen content through uncertain retries; conflicting reuse must fail. Runtime pending/cloud/cache state is not an editable assertion stored on ledger events. Drafts restore before autosave and initialize the stored revision first. Online provisioning remains asynchronous. Session changes cancel observers, clear in-memory references and render only the new UID's state.

Complete-history coverage is required for full balances. Cached data may be stale. Offline expenses require cached references; account creation remains online-only. Drafts are local to this device and excluded from backup. Firestore owns pending-write transport; stored receipts are not a second posting queue. Queries guard at 5,000 documents (requesting 5,001 to detect overflow). Activity fetches the bounded history before sorting/filtering/paginating in memory; displayed pages are never complete balance inputs.

## Ownership and coordination before merging

- **Sugeerthan:** Income and Accounts.
- **Tharukshan:** Spending and Planning.
- **Hithush:** Goals, Overview and integration.
- New transaction kinds require coordinated codec, validation, calculations, rules and fixture updates; domain enum support alone is insufficient.
- Account/category editing and archival require repository operations, revision handling and rules changes. Current rules deny those updates/deletes; profile edits are also denied.
- All consumers must preserve exact money and retained operation identity.
- Activity pages must not become balance inputs.
- Session cleanup, durable drafts and pending/confirmed/rejected states must survive integration.
- Shared models/contracts/navigation changes require coordination across owners before merging.

## Blocks 7–8 changes

In `OfflineExpenseWriter.reconcile(uid)`, the initial unresolved-receipt snapshot now uses `submitMutex.withLock`, rechecks the UID and reads PREPARED/PENDING receipts while protected from the journal-to-Firestore handoff in `submit()`. The empty-list return, `waitForPendingWrites()` and server reconciliation loop remain **after** the lock. Recovery remains read-only and never submits stored receipts.

Updated FirebaseFoundationRepository's stale comment to distinguish online account transactions from Firestore-managed offline expenses. Added root `firebase.json` and `firestore.indexes.json` for reproducible rules/index paths. Current queries use automatic single-field indexes; no composite indexes or field overrides were invented. Do not deploy the empty index definition over an existing project's indexes without comparing them first. README now documents setup, configuration, limitations and authorized manual rules-only deployment. AGENTS.md describes the current baseline. Earlier block documents were preserved as historical records.

No project aliases, Firebase initialization, tool installation, live connection or deployment was performed. Local Android configuration remains ignored and is not reproduced here.

## Focused codec/rules inspection — source evidence only

Inspected current FoundationCodec mapping and the current rules for these paths, plus the account/opening repository write boundary:

| Path | Source-level agreement |
| --- | --- |
| Profile | `defaultProfileData()` emits the exact default fields accepted by `validDefaultProfile`, with server timestamps. Owner get/create only; list/update/delete denied. Optional profile fields can be read by the codec but have no current client write flow/rules grant. |
| Categories | Bootstrap emits name/classification/sort order, optional icon and revision-zero/server audit fields. Rules permit owner creation; category updates/deletes are denied. Reads are owner-only through the supported-collection match. |
| Atomic account/opening | Repository writes both documents in one transaction, optionally provisioning the profile. `getAfter` checks pair the new account/opening IDs and date; `!exists` rejects reuse of an existing counterpart. OPENING has one LKR movement and no report components. |
| Ordinary expense | One negative LKR movement matches one positive spending component, category/account query IDs match, and the rule checks existing same-user account/category references. Fiat amount bounds agree at 100,000,000,000 minor units. Server timestamp transforms align with `request.time`. Only the local pending decoder allows unresolved audit time; this is not permission to store a null confirmed timestamp. |
| Access/immutability | `isOwner(uid)` gates granted reads/creates; reference paths use the same UID. Ledger update/delete is denied. The supported-collection wildcard grants owner reads only; the recursive fallback grants no access. These are source findings, not executed authorization tests. |

### Validation follow-up: changes and remaining limitation

This follow-up changes only `firestore.rules` and this handoff. It tightens validation without changing fields, amounts, atomic account/opening pairing, owner-only grants or ledger immutability:

- Added `nonBlank`: strings containing only Unicode separator characters or whitespace control characters are rejected. `validId` uses it while retaining the slash, dot and double-dot restrictions; profile creation now also validates its UID document identifier. Account/category names and supplied institution labels use it too. Meaningful strings are checked, not trimmed or rewritten. Blank timezone strings are also rejected, matching the codec's nonblank text check.
- Added `validDate`/`validDateParts` for account `openedOn` and event `occurredLocalDate`. These preserve the existing four-digit `YYYY-MM-DD` format and check month 1–12, day 1–month length, and Gregorian leap years (divisible by 4, excluding centuries unless divisible by 400). No additional year cutoff or historical UTC offset was introduced.
- No changes were made to profile defaults, paired creation checks, integer money bounds, expense movement/component agreement, UID access or update/delete denial. Existing malformed documents are not repaired by create-time validation.

These edits use documented [Rules String matching/ranges](https://firebase.google.com/docs/reference/rules/rules.String) and [integer conversion/arithmetic](https://firebase.google.com/docs/reference/rules/rules.Integer). The regex uses [RE2 Unicode categories and hexadecimal escapes](https://github.com/google/re2/blob/main/doc/syntax.txt) to cover whitespace beyond ordinary ASCII spaces. Documentation/source inspection is not a rules compiler or execution result; the revised rules have **not** been syntax-checked by Firebase or executed in this follow-up.

**Precise remaining timezone/date-consistency limitation:** consider an otherwise valid owner-created expense with existing valid account/category references and these fields:

```text
occurredAt:         Timestamp for 2026-10-24T00:00:00Z
occurredLocalDate:  "2026-10-23"
recordedTimeZone:   "Asia/Colombo"
```

The original rules accept these field conditions, and the calendar/nonblank changes still do: the timestamp has the right type, the date exists and the timezone is nonblank. FoundationCodec constructs `FinancialTime`; Colombo's local date at this instant is **2026-10-24**, so its date-consistency invariant rejects the record. This is a source-level counterexample, not an executed Playground request. An otherwise valid record using `recordedTimeZone: "Not/AZone"` likewise passes the nonblank rule but fails `ZoneId.of` during decoding.

Rules [Timestamp operations are UTC-based](https://firebase.google.com/docs/reference/rules/rules.Timestamp) and expose no IANA ZoneId lookup or historical timezone transition database. The app reads a valid ZoneId from the profile and uses it for financial dates; its default is Asia/Colombo, but the domain/codec can consume other valid zones. Requiring one zone would narrow those supported inputs. Even Colombo has historical offset changes, so adding a fixed UTC+05:30 conversion would incorrectly reject some valid historical app entries. No fixed offset, arbitrary year boundary, hardcoded timezone whitelist, schema change or new backend was introduced.

Consequently, timezone-name validity and full timestamp/zone/local-date consistency remain **unenforced by these rules** and are a remaining schema/security limitation. Client validation is not protection against an owner sending malformed records directly. Calendar validity is tightened in source, but this does not solve consistency. Security verification remains incomplete.

## Verification evidence

- Historical Block 6 verification passed **43 JVM tests** and debug assembly; see `P01-Block6.md` for that run and its limitations.
- **User-reported device results:** the four suggested phone checks worked: draft recovery, offline expense recovery, reconnect/retry without duplicates, and basic user switching. These were reported by the user, not executed by this agent.
- **User-reported Rules Playground results:** five access/immutability checks passed. Their individual request payloads and exact tested rules revision were not supplied in this follow-up. This records the user's reported result, not execution by this agent, coverage of the new validation edits, or confirmation of deployed-rule parity.
- Current combined Blocks 7–8 run, 24 October 2026: executed once with Android Studio JDK 21 and the existing user Gradle cache:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = 'C:\Users\User\.gradle'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Result: **BUILD SUCCESSFUL in 12s**, 50 actionable tasks (17 executed, 33 up-to-date), configuration cache reused. XML reports record **43 tests across 7 suites, zero failures, errors or skips**. Debug assembly and Kotlin/Hilt compilation passed. No failures required another run. No tests, dependencies or test frameworks were added. These JVM tests do not exercise the new mutex race against a real Firestore client.

For the combined Blocks 7–8 agent run, no lint, emulator runs, live Firebase operations, security-rule execution, deployment, dependency upgrades, commits or pushes were performed. No build/test blockers remained. For this rules-only follow-up, no Gradle tests/build/lint, Firebase syntax compilation or rule execution was run; no tools/test infrastructure were installed and nothing was deployed, committed or pushed. Previous Android results remain historical evidence, not verification of the new rules. Deployed-rule parity remains **unverified**.

## Minimum manual checks before publishing this rules revision

1. Compile/validate the **exact edited rules** in an authorized Firebase rules editor/test environment without publishing first. Verify the new regex escaping, string slices, integer conversions and helper calls are accepted. No Firebase syntax/execution check was performed here.
2. Exercise successful current profile/category/expense payloads and nonblank labels, then negative cases: empty/space/tab/newline/Unicode-space-only IDs, account/category names and institution labels; wrong field types; forbidden fields/kinds; money floats, bounds and mismatched movement/component/query IDs. Include nonblank text with surrounding whitespace to avoid accidental over-restriction.
3. Check both account and event dates: accept `2024-02-29`, `2000-02-29`, `2026-04-30`; deny `2026-02-29`, `1900-02-29`, `2100-02-29`, `2026-04-31`, `2026-09-31`, month 00/13 and day 00. Check supported date-format boundaries rather than assuming a new year limit.
4. Repeat access/immutability checks against this exact revision: owner read allowed, cross-user read denied, unauthenticated read denied, ledger update denied and ledger delete denied. Also confirm valid owner creates succeed and cross-user creates fail. The five user-reported passes do not substitute for testing this edited revision.
5. In an authorized test setup capable of atomic multi-document writes, verify a valid account/opening pair succeeds and lone or mismatched counterparts fail. Confirm account/category editing and archival remain denied. Single-document Playground checks alone do not establish atomic-pair behavior.

Before any authorized publication, retain the exact reviewed/tested rules and resolve or explicitly accept the documented timezone-consistency limitation; do not claim it has been fixed. After separately authorized publication, compare the connected project's deployed rules with that exact file to establish parity. This request did not authorize or perform publication.

## Remaining device/integration checks

Force a real server rejection and verify retained inputs, rejection/review state and deliberate retry. Test switching users **while writes are pending**, then returning without leaks or duplicates. These remain unverified despite the user-reported basic phone checks. Reproduce recovery concurrent with submission and through process death/reconnection; exercise cached/incomplete-history states and local-storage failures.

P01's requested code/configuration/documentation changes and historical JVM/build verification are recorded above. **P01 security verification remains incomplete**: revised rules compilation/execution, deployed parity and full timezone/date consistency are not established.
