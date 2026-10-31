# P02B shared contract proposal — recurring income

Status: **design review only**. This document does not authorize implementation or deployment.

## Scope and current code facts

`Schedule`, `Occurrence`, `Recurrence`, `StableIds`, and `FinancialTransaction.occurrenceId` already exist in the domain. `Occurrence.id` is derived by `StableIds.occurrence(scheduleId, nominalDate)`, and `StableIds.scheduledReceipt(occurrenceId)` yields `receipt_{occurrenceId}`. `OccurrenceCalculator` now clamps short months while retaining `Recurrence.anchorDate`.

`ScheduleRepository` currently exposes `observeSchedules`, `observeOccurrences`, `saveSchedule`, `saveOccurrence`, and `confirmIncome`. It has no occurrence-mutation commands. `FoundationCodec` currently rejects `event.occurrenceId != null`; its production event mapping and Rules support only LKR OPENING, EXPENSE, and the restricted P02A INCOME shape. The SRS nevertheless requires scheduled receipts to retain their **native fiat amount** and, when foreign, immutable historical LKR conversion metadata. The recurring contract below therefore supports LKR and USD at the domain/schema level, but production USD confirmation depends on the approved USD-account, foreign-income codec, repository, and Rules extension.

## 1. Kotlin contract diff

Keep existing methods unchanged and add the following to `domain/repository/Repositories.kt`:

```kotlin
suspend fun adjustIncomeOccurrence(
    operationId: String,
    occurrenceId: String,
    expectedRevision: Long,
    amount: EntryAmount,
    effectiveDueDate: LocalDate,
): CommandResult<Occurrence>

suspend fun snoozeIncomeOccurrence(
    operationId: String,
    occurrenceId: String,
    expectedRevision: Long,
    until: Instant,
): CommandResult<Occurrence>

suspend fun skipIncomeOccurrence(
    operationId: String,
    occurrenceId: String,
    expectedRevision: Long,
): CommandResult<Occurrence>
```

No model change is required for the schedule/occurrence contract: `Schedule`, `Occurrence`, `RecordMetadata.revision`, `FinancialTransaction.occurrenceId`, `MovementValuation`, `RateSnapshot`, and `CommandResult` already express the required state. `operationId` is accepted for all mutable commands and is retained by the ViewModel for an uncertain retry; the first version does not add an operation-ID field to `Occurrence`.

Repository behavior:

* New schedule/occurrence creation: same document ID plus semantically identical payload is `Accepted(..., replayed = true)`; a different payload is `CONFLICT`.
* Adjustment, skip, and snooze read the occurrence in an online transaction. Its revision must equal `expectedRevision`. A stale retry may be treated as replay only when the stored resulting state/value exactly equals the command target; otherwise it is `CONFLICT`.
* `confirmIncome(occurrenceId, expectedRevision, receipt)` requires `receipt.id == StableIds.scheduledReceipt(occurrenceId)`. It returns the existing receipt as replay only when event semantics match, and otherwise returns `CONFLICT`.
* `VALIDATION`, `NOT_FOUND`, `REJECTED`, `CONFLICT`, `NETWORK`, `UNAUTHENTICATED`, and `PERMISSION_DENIED` retain the current `RepositoryFailure` conventions.

`saveOccurrence` is idempotent materialization for the common scheduler. It must accept only an occurrence calculated from a same-user active schedule snapshot; it does not post money. The scheduler materializes a bounded UI/reminder window, never an infinite future series. Window selection remains scheduler/UI policy, not repository identity policy.

## 2. Firestore mappings

All paths are below the authenticated user:

* `users/{uid}/schedules/{scheduleId}`
* `users/{uid}/occurrences/{scheduleId}_{nominalDate}`
* `users/{uid}/transactions/receipt_{scheduleId}_{nominalDate}`

### Schedule document

Required: `kind`, `title`, `frequency`, `interval`, `anchorDate`, `timeZone`, `expectedAmountMinor`, `currencyCode`, `amountMode`, `activeFrom`, `paused`, `reminderLeadDays`, `createdAt`, `updatedAt`, `revision`.

Optional: `endDate`, `incomeSourceId`, `categoryId`, `commitmentClass`, `preferredAccountId`, `remindLocalTime`, `lastConfirmedAmountMinor`, `archivedAt`.

For P02B recurring income: `kind=INCOME_REMINDER`, `currencyCode` is `LKR` or `USD` at scale 2, `amountMode` is `FIXED` or `ESTIMATE`, `incomeSourceId` is required, category/classification are absent, and an optional preferred account is an active same-currency FIAT account. `createdAt` is a server timestamp on creation; mutable updates use server `updatedAt`; revision starts at `0` and increments by exactly one. Current production account persistence is LKR-only, so USD schedule creation/selection is blocked until USD accounts are persisted and owner-validated.

### Occurrence document

Required: `scheduleId`, `nominalDate`, `effectiveDueDate`, `kind`, `titleSnapshot`, `expectedAmountMinor`, `currencyCode`, `timeZone`, `state`, `createdAt`, `updatedAt`, `revision`.

Optional: `sourceId`, `categoryId`, `commitmentClass`, `snoozedUntil`, `confirmedTransactionId`.

Identity fields are immutable: document ID, `scheduleId`, `nominalDate`, and `kind`. New income occurrences are `OPEN`, retain the schedule's native `currencyCode`, start at revision `0`, and have source snapshot populated with category/classification/confirmation absent. `snoozedUntil` is a timestamp; `confirmedTransactionId` is present only for `CONFIRMED` income occurrences. The clamped nominal date is the generated occurrence date; the original monthly/yearly anchor remains only on the schedule, so identity is stable across regeneration.

Existing documents lacking optional fields decode with `null`; existing schedules/occurrences remain readable. New mapping must reject undeclared fields rather than ignoring them.

### Linked receipt event

Use a restricted POSTED `INCOME` shape: exactly one positive FIAT movement in the occurrence's native LKR or USD currency; one `EARNING` component for the same source; no reservation, merchant, crypto, original, goal, or expected-payment links; and optional note. `occurrenceId` is the only added link and is not editable after creation.

For LKR, movement minor units equal the known-LKR EARNING component and no valuation is present. For USD, the movement remains USD minor units; exactly one `MovementValuation` for that movement is required, using an explicit positive `ValuationRate` (`LKR per one USD major unit`), its actual effective timestamp/source, and optional immutable rate-snapshot ID. The component retains the original USD amount and same rate and stores `historicalLkrValue(nativeAmount, rate)`. BR-11 final HALF_UP rounding occurs exactly once in `historicalLkrValue`; the scheduler never creates, guesses, updates, or silently substitutes a rate. If no explicit rate/snapshot is supplied, confirmation is rejected or retained as a draft, never converted at a current rate.

## 3. Confirmation transaction

The repository must use an online Firestore transaction and read before all writes, in this order:

1. `transactions/{receipt.id}` — semantic replay/conflict decision first.
2. `occurrences/{occurrenceId}` — existence, kind, OPEN state, revision, source, currency, and identity.
3. `schedules/{occurrence.scheduleId}` — same-user income schedule, not archived/paused, compatible source/currency.
4. `accounts/{receipt.movement.accountId}` — active FIAT account whose native currency/scale equals the occurrence.
5. `incomeSources/{occurrence.sourceId}` — active source compatible with the occurrence currency.
6. If a rate snapshot ID is present, `rateSnapshots/{rateSnapshotId}` — same-user asset/currency, exact rate/effective-time/source compatibility.

For a new confirmation, validate deterministic event ID/link, source and native-currency equality, amount positivity, `expectedRevision`, and the LKR-or-USD event invariant above. For USD, validate valuation/native amount/rate/component equality using the existing `TransactionValidator` foreign-income rules. Then atomically create the immutable event and update occurrence only: `state=CONFIRMED`, `confirmedTransactionId=receipt.id`, clear `snoozedUntil`, server `updatedAt`, and revision +1. A schedule revision change does not invalidate an already materialized snapshot confirmation unless the schedule is archived/paused; occurrence snapshot/revision is authoritative. Update the schedule’s `lastConfirmedAmountMinor` and revision only if product review confirms this mutable template field is desired; otherwise derive it from confirmed receipts to avoid another coupled write.

The receipt remains a normal immutable ledger event, so corrections continue through the existing protected correction/void workflow. Any later correction integration must atomically maintain occurrence linkage rather than deleting the receipt.

## 4. Rules proposal

Add path helpers for schedule and occurrence documents. Owner-only `get,list` applies to both collections; unknown collections remain denied.

Transition matrix:

| Document | Create | Update | Delete |
| --- | --- | --- | --- |
| Schedule | owner; strict valid schedule; revision 0 | deferred unless a separate approved schedule-edit transition is defined | deny |
| Occurrence | owner; strict valid calculated snapshot; OPEN/revision 0 | only OPEN→OPEN adjust/snooze, OPEN→SKIPPED, or OPEN→CONFIRMED | deny |
| Receipt event | owner; existing valid P02A INCOME plus scheduled-link checks | deny | deny |

Occurrence update checks: immutable identity/snapshot references; revision exactly +1; `updatedAt == request.time`; adjustment changes only expected amount/effective date/snooze/revision/audit fields; snooze changes only `snoozedUntil`/revision/audit; skip changes state to SKIPPED and clears snooze; confirmation changes state, confirmation ID, snooze, revision, and audit fields only.

For confirmation, reciprocal `getAfter()` checks are mandatory:

* The new transaction’s `occurrenceId` identifies this occurrence and its document ID is `receipt_{occurrenceId}`.
* `getAfter(occurrence).confirmedTransactionId` equals transaction ID and state is CONFIRMED.
* The post-write occurrence source/native currency/amount are compatible with the receipt’s EARNING/movement.
* LKR receipts have no valuation; USD receipts have exactly one valuation and a matching known-LKR component/original amount/rate/snapshot relationship. Rules validate bounded shape and same-user references; exact decimal-rate arithmetic remains repository/domain validation because Rules cannot safely reproduce BigDecimal calculation.
* The occurrence update reads the post-write event and validates the same reciprocal link.

The existing OPENING, EXPENSE, P02A unlinked INCOME, and expected-payment linked-INCOME rules remain unchanged. The scheduled branch is additive and mutually exclusive with `expectedPaymentId`; no event may carry both links. Rule document-access counts must be emulator-tested before deployment.

## 5. Navigation proposal

Existing shared files are `navigation/Destinations.kt` and `navigation/PocketPathApp.kt`; Accounts currently supplies `onRecordIncome`, `onManageIncomeSources`, and `onExpectedPayments` in `feature/accounts/AccountsScreen.kt`.

Add typed `RecurringIncome` and `CreateRecurringIncomeSchedule` destinations. Add one Accounts entry, “Recurring income”, leading to a list of schedules/upcoming occurrences. It reuses the authenticated account/source observations already used by `ManualIncomeViewModel`; it does not create a second app shell or direct Firestore calls from a composable.

## 6. Tharukshan integration

`ScheduleRepository`, `ScheduleKind`, `Occurrence`, `OccurrenceCalculator`, and `StableIds` are the shared scheduler surface. Sugeerthan owns `INCOME_REMINDER`; Tharukshan owns `COMMITMENT`. Identity remains distinct because the schedule ID namespaces differ. The scheduler may materialize due records and hand immutable occurrence metadata to notifications, but neither scheduler nor notification code posts income automatically.

Questions for Tharukshan:

1. Confirm commitment materialization can use `saveOccurrence` with the identical snapshot/identity rules.
2. Confirm commitment-specific state transitions/payments will use separate Rules branches and do not rely on income-only mutation fields.
3. Define a notification payload boundary that carries occurrence ID only, never financial amount mutation/confirmation authority.

## 7. Test plan

JVM: codec strict keys/legacy optional fields; LKR and USD scheduled receipt validation; exact historical HALF_UP valuation and rate/original-amount mismatch rejection; occurrence transition/revision/replay/conflict; deterministic IDs; short-month/leap-day recurrence; current P01/P02A transaction validation regression.

Repository integration: identical confirmation retry; changed receipt conflict; stale revision; two simultaneous confirmations; inactive/missing account/source/schedule; unauthenticated/session-switch failure; no event on adjustment/skip/snooze.

Rules emulator: owner positive flow; cross-user/unauthenticated denial; unexpected fields; immutable identity edits; invalid revision; independent receipt or independent CONFIRMED update denial; reciprocal `getAfter()` confirmation acceptance; expected-payment/P01/P02A regression cases. Emulator results must be reported separately from JVM/build results.
