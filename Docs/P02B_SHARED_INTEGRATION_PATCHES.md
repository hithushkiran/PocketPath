# P02B shared Firebase integration patches

Status: review artifact only. None of the changes below are applied to production sources, Rules, navigation, or Firebase.

## A. Recurring-income integration patch

### Files affected

* `domain/repository/Repositories.kt` — additive occurrence mutation signatures from `P02B_SHARED_CONTRACT_PROPOSAL.md`.
* `data/firebase/FoundationCodec.kt` — schedule/occurrence mapping and restricted scheduled-INCOME mapping.
* `data/repository/FirebaseScheduleRepository.kt` — new authenticated session-scoped adapter.
* `data/repository/FirebaseScheduleRepositoryFactory.kt` or an additive factory in the repository package — closes listeners on Firebase auth UID change.
* `firestore.rules` — schedule/occurrence path helpers, strict schemas, transitions, and reciprocal receipt checks.
* `feature/income/RecurringIncomeViewModel.kt`, state/screen files — after repository integration is approved.
* `feature/accounts/AccountsScreen.kt`, `navigation/Destinations.kt`, `navigation/PocketPathApp.kt`, `feature/auth/AuthRoot.kt` — typed Accounts entry and UID-bound callbacks.

### Repository implementation outline

`FirebaseScheduleRepository` binds `UserFirestoreScope` at construction, exposes `userId`, tracks snapshot listener removals, and rejects any operation after session replacement. It observes each collection with `limit(5001)`, emits `INCOMPLETE_HISTORY` beyond 5,000 records, decodes strictly, and sorts schedules by ID / occurrences by effective due date then ID.

`saveSchedule` requires operation ID = schedule ID, active LKR or USD source compatibility, valid optional preferred account, and semantic replay comparison excluding server audit timestamps. `saveOccurrence` reads its schedule first and permits only the deterministic occurrence ID/snapshot calculated from that schedule. It creates no ledger event.

`adjustIncomeOccurrence`, `snoozeIncomeOccurrence`, and `skipIncomeOccurrence` use an online transaction: read occurrence, validate INCOME_REMINDER/OPEN/revision, apply the pure `IncomeOccurrenceTransitions` decision, and write only the allowed changed fields plus server `updatedAt` and revision. They never access or write `transactions`.

`confirmIncome` reads in order: deterministic receipt document, occurrence, schedule, receiving account, income source, then optional rate snapshot. A matching existing receipt returns replay before state checks. A new receipt must have ID `StableIds.scheduledReceipt(occurrenceId)`, one native positive FIAT movement, the occurrence link, matching source/currency, and no expected-payment link. It writes the immutable receipt plus the occurrence OPEN→CONFIRMED/revision update in one transaction.

### Codec field mapping

`scheduleData/schedule`: the exact fields in `P02B_SHARED_CONTRACT_PROPOSAL.md`, strict required/optional key checking, LKR/USD FIAT scale 2, immutable ID outside fields, server timestamps on creation, and nullable legacy optional fields.

`occurrenceData/occurrence`: strict schedule ID, nominal/effective ISO dates, snapshot title/amount/currency/time zone, compatible source/category/classification, state, optional snooze/confirmation, audit fields and revision. New occurrence state is OPEN/revision 0.

`eventData/event`: add optional `occurrenceId`; permit it only for INCOME. LKR scheduled income has no valuation. USD scheduled income has one valuation matching movement, `historicalLkrValue`, component original amount/rate/snapshot, and no inferred rate.

### Rules patch

Add `schedulePath`, `occurrencePath`, `validSchedule`, `validOccurrenceCreate`, and income-occurrence transition helpers. Owner-only reads; deny deletes. Create rules enforce strict exact field sets and revision zero. Updates preserve immutable occurrence identity/snapshot links and permit only OPEN→OPEN adjustment/snooze, OPEN→SKIPPED, or reciprocal OPEN→CONFIRMED with revision +1.

The transaction-create INCOME branch adds a mutually exclusive scheduled-link branch. `getAfter(occurrencePath(...))` must be CONFIRMED, point to `receipt_{occurrenceId}`, and match source/native currency/amount. The occurrence update must `getAfter(eventPath(...))` and validate the same link. LKR forbids valuation. USD requires one bounded valuation and same-user account/source/snapshot references; exact BigDecimal valuation arithmetic remains application-side and is emulator-tested.

### Required tests

Codec unit tests, repository transaction tests, UID switch/listener cleanup, replay/conflict/stale revision, duplicate materialization, concurrent confirmation, Rules owner/cross-user/malformed/reciprocal-link tests, and existing P01/P02A transaction regressions.

## B. USD/FX integration patch

### Files affected

* `FoundationCodec.kt`, `FirebaseFoundationRepository.kt`, and its factory.
* `Repositories.kt` only if an approved production `RateRepository` factory is required.
* `firestore.rules`, tests, and Accounts/income feature UI after persistence works.

### Contract and mappings

Extend account encoding/decoding from LKR-only to FIAT LKR/USD at scale 2. USD must have `includeInEverydayPool=false`; its opening event keeps native USD movement. Foreign INCOME stores native USD movement, one matching valuation, known LKR EARNING component with original USD/rate metadata, and optional immutable `rateSnapshotId`. `rateSnapshots/{id}` stores existing `RateSnapshot` fields, decimal rate as string, LKR quote currency, immutable effective/recorded times/source/provider metadata.

FX conversion requires actual source/debit and destination/credit movements plus fees; no EARNING component for converted principal and no invented effective rate. Rates supplied by the user or snapshot must remain immutable on the event.

### Rules/tests/decisions

Rules must validate USD account shape, non-everyday eligibility, native movement alternatives, bounded valuation shape, same-user snapshot references, and preserve current LKR branches. Tests cover USD precision, historical HALF_UP, rate mismatch, missing valuation, and no conversion of USD into everyday LKR funds. Hithush must approve account-schema widening and the manual-rate-without-snapshot policy.

## C. Crypto integration patch

### Files affected

* `FoundationCodec.kt` for crypto account/event/trade/position mapping.
* New `FirebaseProtectedLedgerRepository.kt` and authenticated factory.
* `firestore.rules` for immutable trade events and serialized position update.
* feature-owned crypto state/screen files only after the repository exists.

### Contract and transaction

`recordCryptoTrade(event, expectedPositionVersion)` reads immutable operation event, asset/quote accounts, and `cryptoPositions/{assetAccountId}` before all writes. It validates exact `CryptoTrade`, `CryptoCostBasisCalculations`, quantity availability, expected version, and event replay semantics. It atomically creates the event and writes the next position/version. A full sale clears rounding residual cost; unknown cost remains unknown and produces unknown realized P/L.

Rules enforce owner-only paths, immutable trade envelopes, exact account asset/scale references, version +1 position updates, and event/position linkage. Rules cannot reproduce arbitrary precision cost arithmetic, so repository validation plus emulator tests are required. No automatic USD parity or market rate is permitted.

### Required tests and decisions

Tests: buys, partial/full sells, fees, oversell, version conflict, replay/conflict, unknown opening cost, cross-user denial, and concurrent trade attempts. Hithush must approve crypto field storage keys and the protected position projection schema before implementation.

## Integration order

1. Approve and apply recurring contract patch; compile/JVM test; then Rules emulator test.
2. Apply USD account/foreign-income/rate patch; test before enabling USD recurring UI.
3. Apply protected crypto patch; test before adding buy/sell UI.

No notification code is proposed. Tharukshan receives materialized occurrence IDs only; notifications cannot confirm or post financial events.
