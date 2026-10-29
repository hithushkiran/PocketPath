package com.pocketpath.domain.model

import com.pocketpath.domain.money.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Server audit times may remain unresolved while a command is pending. */
data class RecordMetadata(
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val revision: Long = 0,
    val archivedAt: Instant? = null,
) { init { require(revision >= 0) } }

data class FinancialTime(val occurredAt: Instant, val occurredLocalDate: LocalDate, val recordedTimeZone: ZoneId) {
    init { require(occurredAt.atZone(recordedTimeZone).toLocalDate() == occurredLocalDate) }
}

data class UserProfile(
    val id: String,
    val displayName: String? = null,
    val baseCurrency: FiatCurrency = FiatCurrency.LKR,
    val timeZone: ZoneId = ZoneId.of("Asia/Colombo"),
    val weekStartsOn: Int = 1,
    val primaryGoalId: String? = null,
    val preferredAccountId: String? = null,
    val onboardingCompleted: Boolean = false,
    val hideBalances: Boolean = false,
    val remindersEnabled: Boolean = true,
    val schemaVersion: Int = 1,
    val metadata: RecordMetadata = RecordMetadata(),
) {
    init {
        require(id.isNotBlank() && (displayName == null || displayName.length <= 80))
        require(baseCurrency == FiatCurrency.LKR && weekStartsOn in 1..7 && schemaVersion == 1)
    }
}

enum class AccountType { CASH, BANK, FIAT_WALLET, CRYPTO_WALLET }
enum class CommitmentClass { COMMITTED, DISCRETIONARY, UNPLANNED_ESSENTIAL }

data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val asset: Asset,
    val includeInEverydayPool: Boolean,
    val openingTransactionId: String,
    val openedOn: LocalDate,
    val institutionLabel: String? = null,
    val metadata: RecordMetadata = RecordMetadata(),
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && openingTransactionId.isNotBlank())
        require((type == AccountType.CRYPTO_WALLET) == (asset is CryptoAsset))
        require(!includeInEverydayPool || asset == FiatCurrency.LKR)
    }
}

data class Category(
    val id: String,
    val name: String,
    val defaultCommitmentClass: CommitmentClass,
    val iconKey: String? = null,
    val sortOrder: Int = 0,
    val metadata: RecordMetadata = RecordMetadata(),
) { init { require(id.isNotBlank() && name.isNotBlank()) } }

enum class IncomeSourceKind { SALARY, FREELANCE, ADSENSE, CRYPTO, OTHER }
data class IncomeSource(
    val id: String, val name: String, val kind: IncomeSourceKind,
    val defaultAccountId: String? = null, val defaultCurrency: FiatCurrency? = null,
    val metadata: RecordMetadata = RecordMetadata(),
) { init { require(id.isNotBlank() && name.isNotBlank()) } }

enum class ExpectedPaymentState { ACTIVE, CANCELLED }
data class ExpectedPayment(
    val id: String, val incomeSourceId: String, val projectLabel: String,
    val amountDue: EntryAmount, val dueDate: LocalDate,
    val milestoneLabel: String? = null, val clientLabel: String? = null,
    val state: ExpectedPaymentState = ExpectedPaymentState.ACTIVE,
    val cancelledAt: Instant? = null, val notes: String? = null,
    val cancellationOperationId: String? = null,
    val receivedAmountMinor: Long = 0L,
    val lastReceiptOperationId: String? = null,
    val metadata: RecordMetadata = RecordMetadata(),
) {
    init {
        require(id.isNotBlank() && incomeSourceId.isNotBlank() && projectLabel.isNotBlank())
        require(notes == null || notes.length <= 1000)
        require((state == ExpectedPaymentState.CANCELLED) == (cancelledAt != null))
        require(receivedAmountMinor in 0..amountDue.money.minor)
        cancellationOperationId?.let { require(it.isNotBlank() && '/' !in it) }
        lastReceiptOperationId?.let { require(it.isNotBlank() && '/' !in it) }
    }
}

enum class ScheduleKind { INCOME_REMINDER, COMMITMENT }
enum class Frequency { WEEKLY, MONTHLY, YEARLY }
enum class AmountMode { FIXED, ESTIMATE }
data class Recurrence(val frequency: Frequency, val anchorDate: LocalDate, val interval: Int = 1) {
    init { require(interval == 1) { "Version 1 supports interval 1; short-month clamping is deferred" } }
}

internal fun compatibleScheduleReferences(kind: ScheduleKind, source: String?, category: String?, classification: CommitmentClass?) =
    when (kind) {
        ScheduleKind.INCOME_REMINDER -> !source.isNullOrBlank() && category == null && classification == null
        ScheduleKind.COMMITMENT -> source == null && !category.isNullOrBlank() && classification != null
    }

data class Schedule(
    val id: String, val kind: ScheduleKind, val title: String,
    val recurrence: Recurrence, val timeZone: ZoneId,
    val expectedAmount: EntryAmount, val amountMode: AmountMode,
    val activeFrom: LocalDate, val endDate: LocalDate? = null,
    val incomeSourceId: String? = null, val categoryId: String? = null,
    val commitmentClass: CommitmentClass? = null, val preferredAccountId: String? = null,
    val remindLocalTime: LocalTime? = null, val reminderLeadDays: Int = 0,
    val lastConfirmedAmount: EntryAmount? = null, val paused: Boolean = false,
    val metadata: RecordMetadata = RecordMetadata(),
) {
    init {
        require(id.isNotBlank() && title.isNotBlank() && reminderLeadDays >= 0)
        require(endDate == null || endDate >= activeFrom)
        require(compatibleScheduleReferences(kind, incomeSourceId, categoryId, commitmentClass))
        require(lastConfirmedAmount == null || (kind == ScheduleKind.INCOME_REMINDER &&
            lastConfirmedAmount.money.currency == expectedAmount.money.currency))
    }
}

enum class OccurrenceState { OPEN, SKIPPED, CANCELLED, CONFIRMED }
data class Occurrence(
    val scheduleId: String, val nominalDate: LocalDate, val effectiveDueDate: LocalDate,
    val kind: ScheduleKind, val titleSnapshot: String, val expectedAmount: EntryAmount,
    val timeZone: ZoneId, val sourceId: String? = null, val categoryId: String? = null,
    val commitmentClass: CommitmentClass? = null, val state: OccurrenceState = OccurrenceState.OPEN,
    val snoozedUntil: Instant? = null, val confirmedTransactionId: String? = null,
    val metadata: RecordMetadata = RecordMetadata(),
) {
    val id: String get() = StableIds.occurrence(scheduleId, nominalDate)
    init {
        require(scheduleId.isNotBlank() && titleSnapshot.isNotBlank())
        require(compatibleScheduleReferences(kind, sourceId, categoryId, commitmentClass))
        require(state != OccurrenceState.CONFIRMED || kind == ScheduleKind.INCOME_REMINDER)
        require((state == OccurrenceState.CONFIRMED) == (confirmedTransactionId != null))
    }
}

object StableIds {
    private fun checked(id: String): String {
        require(id.isNotBlank() && '/' !in id)
        return id
    }
    fun occurrence(scheduleId: String, nominalDate: LocalDate) = "${checked(scheduleId)}_$nominalDate"
    // SRS worked example: salary_monthly_2026-10-25 -> receipt_salary_monthly_2026-10-25.
    fun scheduledReceipt(occurrenceId: String) = "receipt_${checked(occurrenceId)}"
    fun goalPlan(goalId: String, periodStart: LocalDate) = "${checked(goalId)}_$periodStart"
}

enum class GoalState { ACTIVE, PAUSED, COMPLETED, CANCELLED }
data class Goal(
    val id: String, val name: String, val target: EntryAmount,
    val startDate: LocalDate, val deadline: LocalDate, val state: GoalState = GoalState.ACTIVE,
    val completedAt: Instant? = null, val metadata: RecordMetadata = RecordMetadata(),
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && target.money.currency == FiatCurrency.LKR)
        require(deadline >= startDate)
        require((state == GoalState.COMPLETED) == (completedAt != null))
    }
}

/** Provisional enum: SRS declares state but does not enumerate goal-plan values. */
enum class GoalPlanState { ACTIVE, CANCELLED }
data class GoalPlan(
    val goalId: String, val periodStart: LocalDate, val periodEnd: LocalDate,
    val plannedContribution: Money, val state: GoalPlanState = GoalPlanState.ACTIVE,
    val metadata: RecordMetadata = RecordMetadata(),
) {
    val id: String get() = StableIds.goalPlan(goalId, periodStart)
    init {
        require(goalId.isNotBlank() && periodStart.dayOfMonth == 1)
        require(periodEnd == periodStart.plusMonths(1).minusDays(1))
        require(plannedContribution.currency == FiatCurrency.LKR && plannedContribution.minor >= 0)
    }
}

enum class BudgetScope { OVERALL_DISCRETIONARY, CATEGORY_DISCRETIONARY }
enum class BudgetPeriod { WEEKLY, MONTHLY }
enum class BudgetState { ACTIVE, ARCHIVED }
data class Budget(
    val id: String, val scope: BudgetScope, val periodType: BudgetPeriod,
    val limit: EntryAmount, val effectiveFrom: LocalDate, val effectiveTo: LocalDate? = null,
    val categoryId: String? = null, val rolloverEnabled: Boolean = false,
    val state: BudgetState = BudgetState.ACTIVE, val metadata: RecordMetadata = RecordMetadata(),
) {
    init {
        require(id.isNotBlank() && limit.money.currency == FiatCurrency.LKR && !rolloverEnabled)
        require(effectiveTo == null || effectiveTo >= effectiveFrom)
        require(if (scope == BudgetScope.CATEGORY_DISCRETIONARY) !categoryId.isNullOrBlank() else categoryId == null)
    }
}

enum class ValuationSource { MANUAL_ACTUAL, MANUAL_ESTIMATE, PROVIDER_INDICATIVE }
data class RateSnapshot(
    val id: String, val assetCode: String, val rate: ValuationRate,
    val effectiveAt: Instant, val recordedAt: Instant, val source: ValuationSource,
    val providerLabel: String? = null,
) {
    val quoteCurrency: FiatCurrency get() = FiatCurrency.LKR
    init { require(id.isNotBlank() && assetCode.isNotBlank()) }
}

enum class CostMethod { MOVING_AVERAGE_V1 }
data class CryptoPosition(
    val accountId: String, val quantity: CryptoQuantity, val cost: KnownLkrAmount,
    val lastTransactionId: String, val version: Long, val rebuiltAt: Instant? = null,
    val costMethod: CostMethod = CostMethod.MOVING_AVERAGE_V1,
) {
    init {
        require(accountId.isNotBlank() && lastTransactionId.isNotBlank() && version >= 0)
        require(quantity.atomic.signum() >= 0)
        require(cost !is KnownLkrAmount.Known || cost.amount.minor >= 0)
    }
}
