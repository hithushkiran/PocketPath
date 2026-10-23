# P01 Block 2 shared domain contracts

## Scope and inspected baseline

PocketPath remains the product name; Kavindu is the persona. This block provides platform-neutral records, exact financial primitives, repository contracts, deterministic calculations and opt-in in-memory samples. It implements no Firebase, authentication, Room, financial UI, notifications, recurrence-window generation, trading service or full planning/goal engine. Block 1 screens, navigation, theme and Hilt bindings are unchanged. No sample repository is bound to Hilt or used by the app shell.

Read root AGENTS.md, Docs/P01-Block1.md, the current shell/build setup and the references in `Docs/reference/` using that capitalization. The three reference documents were readable through their extracted text: the SRS v0.2, revised implementation plan v1.1 and assignment PDF. SRS sections 6–7 and 9–11 govern money bounds, financial effects, field-level schema, query completeness, conflict rules and offline behavior. The implementation plan's wider P01 scope is deferred to the remaining blocks.

Starting branch: `feature/p01-foundation`; starting working tree was clean. Existing commits/pushes do not resolve Block 1's missing lint tooling or unperformed device/manual checks. No commit or push is made by this block.

## Public types and files

All application paths below start at `app/src/main/java/com/pocketpath/`.

| File/package | Consumer contract |
| --- | --- |
| `domain/money/Money.kt` | `FiatCurrency`, `CryptoAsset`, bounded signed `Money`, positive `EntryAmount`, exact `CryptoQuantity`, disjoint `NativeAmount`, `ValuationRate`, `KnownLkrAmount` and `historicalLkrValue` |
| `domain/model/Records.kt` | `RecordMetadata`, `FinancialTime`, profile, account, category, source, expected payment, schedule/recurrence/occurrence, stable IDs, goal/plan, budget, rate snapshot and crypto position |
| `domain/model/Transaction.kt` | `FinancialTransaction`, all 13 event kinds, POSTED/VOIDED status, movements, report components, reservations, historical valuations, trade fees/details and void metadata |
| `domain/validation/TransactionValidator.kt` | Constructor-enforced bounded-envelope, event-kind and local arithmetic checks; exposes descriptive structural errors |
| `domain/repository/RepositoryState.kt` | Flow observation, loading/data/failure, metadata, command result and failure contracts |
| `domain/repository/Repositories.kt` | Account, ledger, category, profile, income, schedule/commitment, goal, budget, rate and protected-ledger interfaces; command payloads; complete ledger and Activity page types |
| `domain/calculation/LedgerCalculations.kt` | Native balances with negative-balance warning; timezone-explicit period component totals; basic earmark sum |
| `data/sample/InMemoryFinanceRepository.kt` | One opt-in implementation of the basic account/ledger/category interfaces, backed by an atomic StateFlow snapshot and Mutex |
| `data/sample/SampleFixtures.kt` | Explicit synthetic cash/category/opening/expense fixture; fixed time and caller-supplied sample user scope |

Domain code imports no Compose, Android Context or Firebase types. It uses `java.time` (available at minimum API 26), `BigInteger`, `BigDecimal`, and coroutines Flow contracts. Coroutines core 1.10.2 is now an explicit catalog dependency; no existing dependency or build-tool versions changed. No new testing library was required: tests use existing JUnit 4.13.2 and core coroutines `runBlocking`/Flow/Mutex APIs.

## Domain to SRS storage mapping

These are domain records, not Firebase serialization DTOs. A later adapter must flatten grouped fields, reject unexpected storage keys, validate supplied query-reference arrays against the derived arrays, and preserve exact units.

| Domain representation | SRS field(s) / mapping rule |
| --- | --- |
| `id`, `operationId` | Child document ID; financial transaction `id` is always `operationId`. New ordinary command IDs are caller-retained UUIDs; samples use visibly synthetic IDs. |
| `RecordMetadata` | Flatten `createdAt`, `updatedAt`, `revision`, `archivedAt`; unresolved server timestamps are null in domain and must be handled as pending server values, not invented audit times. |
| `FinancialTime` | Flatten `occurredAt` (Instant), `occurredLocalDate` (ISO LocalDate), `recordedTimeZone` (ZoneId ID). Local date must agree with the instant in that recorded zone. `clientCreatedAt` is separate; server `createdAt` may be unresolved. |
| `UserProfile` | Flatten preferences alongside identity. `baseCurrency` maps to `LKR`; `timeZone` to its ID; `weekStartsOn` is ISO 1–7. `primaryGoalId` lives only here. |
| `Account.asset` | `currencyCode`, `assetClass` (`FIAT` or `CRYPTO` from sealed subtype), `scale`. No balance field. `openingTransactionId`, `openedOn`, `includeInEverydayPool`, type/institution and metadata retain their SRS meanings. |
| `Category` | `name`, `defaultCommitmentClass`, `iconKey`, `sortOrder` and flattened metadata. Archived categories retain historical references. |
| `IncomeSource` | `name`, `kind`, `defaultAccountId`, `defaultCurrency.code` and metadata. A source is not an account. |
| `ExpectedPayment.amountDue` | `currencyCode` and `amountDueMinor`; project/milestone/client labels, due date, ACTIVE/CANCELLED state and cancellation/notes retained. No editable paid counter. |
| `Schedule.recurrence` | Flatten `frequency`, `interval`, `anchorDate`; retain `timeZone`, active/end dates, pause/reminder preferences and compatible source/category links. Baseline interval is 1. |
| `Schedule.expectedAmount`, `lastConfirmedAmount` | `expectedAmountMinor`, `currencyCode`, optional `lastConfirmedAmountMinor`. Only income schedules have a last confirmed amount. |
| `Occurrence` | ID is `scheduleId + '_' + nominalDate`; retains effective due date, snapshot kind/title/amount, `sourceId` (SRS occurrence spelling), category/classification, explicit decision state, snooze/confirmation and metadata. Domain also retains explicit timezone from the schedule snapshot. |
| `AccountMovement.amount` | Fiat: `currencyCode`, `assetClass=FIAT`, `amountMinor:Long`; crypto: code, `assetClass=CRYPTO`, `quantityAtomic` exact base-10 integer string. Scale comes from the asset/account. Never populate both amount representations. |
| `ReportComponent.lkrAmount` | Known maps to `amountLkrMinor`; unknown analytical result maps to an absent amount plus explicit unknown reason. `originalAmount` flattens to `originalCurrency` and original fiat minor or crypto atomic units; retain rate decimal string and snapshot ID. |
| `ReservationEffect.amountLkr` | `amountLkrMinor`, signed; `goalId`, `backingAccountId`. Backing must resolve to same-user LKR account in the repository. |
| `MovementValuation` | `accountId`, exact native units, `lkrMinor`, `rateLkrPerMajor` as decimal string, `effectiveAt`, source and optional snapshot ID. Signed valuation corresponds to the signed movement; report magnitudes remain separately positive where required. |
| `FinancialTransaction` query lists | `accountIds` derives from movement accounts plus reservation backing accounts; `categoryIds`, `goalIds`, `incomeSourceIds` are deduplicated/sorted from envelope effects/components. Caller cannot independently supply them. |
| Transaction links/description | SRS names `occurrenceId`, `expectedPaymentId`, `originalTransactionId`, `goalPlanId`, `note`, `merchantLabel`, `replacesTransactionId`; retained independently. |
| `VoidMetadata` | Flatten `voidedAt`, `voidReason`. VOIDED requires metadata; POSTED excludes it. Core posted fields are immutable. |
| `CryptoTrade` | Asset/quote account IDs, exact positive quantity, gross quote native amount, fee details, proceeds/acquisition LKR valuation, cost method and known/unknown allocated cost/profit. Quote can be fiat or crypto (including USDT), with its own scale; no implicit USD parity. |
| `Goal.target` | `targetLkrMinor`; dates/state/completion/archival remain explicit. No editable progress or duplicate primary flag. |
| `GoalPlan.plannedContribution` | `plannedContributionLkrMinor`; monthly inclusive window and `goalId + '_' + periodStart` identity; no contribution postings or editable remaining counter. |
| `Budget.limit` | `limitLkrMinor`; scope/category, period type, effective dates, state and metadata; rollover false. No editable spent value. |
| `RateSnapshot.rate` | `rateLkrPerMajor` decimal string; `quoteCurrency=LKR`, asset code, source/effective/recorded times and provider label. |
| `CryptoPosition.quantity`, `cost` | `quantityAtomic` plus retained asset scale; `costKnown`, nullable `knownCostLkrMinor`, explicit reason when unknown. `MOVING_AVERAGE_V1`, last transaction/version/rebuilt time are projection metadata, not an independent financial source. |

Configuration dates without their own timezone (expected payments, goals, plans and budgets) are interpreted in the user's explicit profile planning timezone. Occurrences retain the schedule timezone. Storage adapters must keep this distinction rather than replacing financial dates with upload dates.

## Exact units and invariants

- Fiat uses signed Long minor units. LKR/USD scale is exactly 2: LKR 850 = 85000; USD 100 = 10000. `EntryAmount` requires positive money; signed movements may be negative or zero for an opening. Money and targets are capped at 1,000,000,000 major units per record. Additional fiat currencies require an explicit scale; the final Long conversion remains checked.
- Crypto uses BigInteger atomic units, asset scale 0–18, and at most 78 unsigned digits. Addition requires identical asset and scale. Fiat addition similarly rejects different currencies/scales.
- Input is plain decimal text without grouping or exponent. Excessive fractional digits, even extra trailing zeros, are rejected. BigDecimal/BigInteger conversions and aggregate Long arithmetic are exact/checked; there is no Float/Double arithmetic or truncation.
- Historical LKR valuation follows BR-11: native major units × full-precision rate × 100, then HALF_UP once at the final minor-unit boundary. Historical component/valuation values remain stored snapshots. Current rates never rewrite old receipts.
- Only effective POSTED events contribute. Native account balances sum movements only. Reports sum components only. Goal earmarks sum reservation effects only. Per-record limits do not incorrectly clamp aggregate account balances; aggregate Long overflow is rejected.
- INCOME requires receipt/earnings; EXPENSE requires paying movements/spending. Opening and adjustment are not ordinary earnings/consumption. Same-asset transfer principal must cancel, with fee native units/report magnitudes represented once. FX keeps unlike actual quantities. Trade native principal and fees must match its movement envelope; trading result/cost engines remain deferred.
- Foreign ordinary receipts/payments require retained historical movement valuation and matching report totals. USD receipts remain USD native units even when their report component is LKR-valued. Only designated LKR accounts may enter the everyday pool.
- Known/Unknown is explicit for analytical cost/profit. An unknown opening cost cannot be paired with fabricated known zero profit. `AnalyticalTotal.knownLkrMinor` is only a known subtotal; consumers must inspect `isComplete`/`unknownReasons` before presenting any total as complete.
- Negative recorded balances are permitted and return `NEGATIVE_RECORDED_BALANCE`; they do not block truthful expense entry. This does not represent lending or assurance of sufficient real-world funds.
- Movements/components/reservations/valuations are bounded to four items; event lists are defensively copied and unmodifiable. Query lists are generated from those lists. Fees/unknown-profit details remain part of the one envelope.
- An event constructor checks local structure. It cannot prove same-user reference ownership, correct current account scale, available goal funding, cumulative refund caps, uniqueness of scheduled confirmation, active-budget overlap or crypto position version. Production repositories/rules must perform those checks atomically where required. It is not an audited banking ledger.

## Repository and query semantics

Repository instances are user-scoped. Production implementations must derive UID from authentication, reject cross-user references and unauthenticated access, detach listeners and clear in-memory state on sign-out. No test UID or sample instance is a production authentication mechanism. Configuration commands preserve revision/archival semantics; financial events are create-only except the controlled online void/replacement workflow. Invalid command content is rejected, not silently corrected. Coroutine cancellation must propagate.

Basic interfaces support accounts/find/create-with-opening, categories, ordinary expense commands, complete effective ledger/account history, native balances and paginated Activity. Extension interfaces expose income sources/expected payments/receipts, schedules/occurrences/commitment payments, goals/plans/reservations, budgets, rates, corrections/refunds and serialized crypto commands without implementing those features.

`CompleteLedger` and `ActivityPage` are deliberately different types. Complete ledgers carry `AllEvents` or `AccountEvents(accountId)` scope. Balance helpers reject a different account scope; period reports/earmarks require the complete user scope. A page never implicitly converts to a complete ledger. Production adapters must not manufacture `CompleteLedger` from incomplete cache results: emit an incomplete-history failure/retain prior complete data, and expose partial records through paginated presentation with incomplete metadata. Complete balances need all relevant prior effective movements or a later validated checkpoint design; a single period/page cannot substitute.

Observation represents Loading, Data (including loaded empty) or Failed with optional last available data. Runtime metadata records memory/cache/server origin, complete/incomplete/not-applicable scope, observation time and local-saved/pending/cloud-confirmed/rejected state. Command results similarly separate acceptance from rejection and replay. These are runtime facts, never editable financial-document fields proving server confirmation.

Activity pages are newest financial occurrence first with ID tie-breaking, capped at 50. The sample cursor binds revision and account filter; any intervening sample write requires restarting pagination. A displayed final page is still marked incomplete as a summary source. Future Firebase cursors must preserve equivalent stable ordering and user scope.

## Samples and retries

Construct `InMemoryFinanceRepository(userId, clock, initialCategories)` explicitly, or call `SampleFixtures.create(explicitSampleUserId)`. The fixture seeds synthetic cash LKR 10000 and an expense LKR 850, yielding 9150. Production UI remains unchanged and displays no records.

Create-account publishes account plus one OPENING event in a single StateFlow snapshot. Ordinary expenses validate local account/category references, archival status, currency, historical rate when needed and structural transaction rules. A Mutex serializes commands and stores the original command/result under its operation ID. Exact retries return the existing result with `replayed=true`; changed content or cross-command reuse returns CONFLICT. Rejected commands do not reserve an operation ID or partially write data. The caller must retain identical draft content/time/ID when retrying.

Samples report MEMORY_SAMPLE/LOCAL_SAVED, never CLOUD_CONFIRMED. They lose all records and operation identities on disposal/process death, have no real authentication/security, network/cache synchronization or durable drafts, and must not be used as proof of NFR-08 restart persistence. Categories support optimistic revisions and archival. The sample deliberately rejects commitment-linked expense commands; later schedule repositories must validate those links. No advanced service is implemented merely to populate tests.

## Online-dependent operations deferred

Final schedule confirmation must read occurrence/revision and post its deterministic receipt atomically. Corrections atomically void/replace and update dependent links. Refunds check the effective original and cumulative refunded amount. Goal allocations/releases check existing backing and reservations. Crypto actions serialize quantities/cost-basis through the position version. Active budget changes check duplicate/overlapping scopes. These operations require authoritative online reads in the baseline; offline forms remain drafts.

Ordinary additions may later be locally queued with stable IDs, but durable rejection recovery and cache/draft policy remain unimplemented. Room/outbox selection is deferred; do not introduce a second posting queue that duplicates Firestore writes. Account creation must eventually atomically enforce its single opening/reference under the authenticated user.

## Team handoff

- Sugeerthan can implement authenticated account/ledger/category integration, income/expected-payment/schedule/rate/position adapters using the shared records, command IDs and exact native units. The sample supports basic development against the same account/ledger contracts.
- Tharukshan can consume RecordExpenseCommand, category/classification, occurrence links, component reports and budget/schedule interfaces. Complete reports must use the complete user event scope. Commitment outstanding amounts are derived, not mutable counters.
- Hithush can consume balance warnings, complete component totals, Known/Unknown results, profile primary-goal reference, goal/plan interfaces and basic earmark sums. Goal-impact, affordability and planning engines remain later work.
- All three coordinate domain/schema/navigation changes before changing contracts; Hithush coordinates integration. No feature needs to duplicate money parsing, historical rounding or envelope definitions.

## Reference ambiguities and explicit provisional choices

1. SRS 9.7 declares a goal-plan state enum without values. This block uses ACTIVE/CANCELLED as a documented provisional choice requiring team agreement before storage mapping.
2. SRS 9.6 calls valuations bounded without specifying a number. This block caps them at four, matching movement capacity, one retained valuation per identical movement/account pair. The number must be coordinated if the schema changes.
3. The trade subdocument names gross quote amount/fees semantically but does not provide every nested scalar storage key. Domain uses typed NativeAmount for fiat or crypto quotes/fees. A future Firebase adapter must explicitly agree those nested keys and unknown-reason fields; this block claims no deployed storage schema.

## Checks and remaining validation

Final verification on 23 October 2026 used the existing Windows wrapper, Android Studio JBR 21.0.10, local SDK and user Gradle cache:

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain`: all requested tasks passed with configuration caching enabled. JVM results: **30 tests, zero failures/errors** (29 new domain/sample tests plus one preserved starter test). The debug APK was packaged successfully.
- Lint: **zero errors, 19 warnings**. Warnings concern retained SDK/dependency versions, the existing redundant Activity label and unused starter XML colors; the added coroutines version also receives an update suggestion. No blanket suppression, baseline, disabling or unrelated upgrade was added. Report: `app/build/reports/lint-results-debug.html`.
- The Block 1 lint download blocker is resolved in this environment: online resolution fetched missing coroutines instrumentation-test metadata/artifacts and AGP's `intellij-core:32.3.3` / `kotlin-compiler:32.3.3` tools. An initial offline resolution attempt had failed with Gradle's `path is null` reporting error; checks resumed after online resolution. There is no remaining build/lint tooling blocker.
- The first completed lint analysis caught BigInteger.longValueExact being API 31 on Android. Replaced it with an explicit BigInteger Long-range check plus conversion available at API 26. JVM boundary/overflow tests and final lint passed. No SDK increase or NewApi suppression was used.
- Tests cover exact fiat/atomic parsing, precision/bounds/overflow and mismatches; opening 10000 minus expense 850; opening exclusion; transfer principal and fee; VOIDED exclusion; exact/concurrent retries and conflicts; reservation separation; USD native/report distinction; explicit USDT quote scale; unknown profit; stable occurrence/receipt/goal-plan IDs; negative balances; temporal refund reporting; validation and defensive copies; user/sample isolation; archival and pagination/completeness.
- `git diff --check` passed. Navigation, feature placeholders, Activity/Application, manifest, theme and Hilt bindings have no source changes from the starting commit. Domain/sample code contains no Android/Compose/Firebase imports or Float/Double financial arithmetic. All changes remain available for review without commit/push.
- No emulator/device navigation, accessibility, instrumentation or Android Studio preview checks were run. Those Block 1 manual checks remain outstanding; passing JVM/build/lint checks do not replace them.

Re-run from Android Studio's terminal (offline mode is optional when all artifacts are cached):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
.\gradlew.bat :app:lintDebug --console=plain
```

The sandbox required session-only JAVA_HOME and GRADLE_USER_HOME overrides to use installed JBR and the existing cache. No SDK path, credential or user-specific machine setting was committed to project configuration.

The Block 1 manual checklist in Docs/P01-Block1.md remains outstanding: device launch/API 26/current API, selected tabs and repeated taps, Add/Accounts/Settings toolbar/system Back, rotation/state restoration, small screens, font scaling, dark mode, TalkBack and preview. This block does not claim those passed. Run them in Android Studio; no financial/sample screens should appear.
