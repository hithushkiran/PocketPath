package com.pocketpath.domain.calculation

import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.toLongChecked
import java.math.BigInteger
import java.time.LocalDate

/** All quantities are LKR minor units. null means unknown, never zero. */
data class FundsPlanningInput(
    val asOfDate: LocalDate,
    val eligibleBalanceMinor: Long?,
    val reservedInEligibleAccountsMinor: Long?,
    val outstandingCommitmentsMinor: Long?,
    val remainingPlannedContributionsMinor: Long?,
    val horizonEnd: LocalDate = asOfDate.withDayOfMonth(asOfDate.lengthOfMonth()),
) {
    init {
        require(horizonEnd == asOfDate.withDayOfMonth(asOfDate.lengthOfMonth())) {
            "The baseline planning horizon ends at the current calendar month end."
        }
        require(reservedInEligibleAccountsMinor == null || reservedInEligibleAccountsMinor >= 0L)
        require(outstandingCommitmentsMinor == null || outstandingCommitmentsMinor >= 0L)
        require(remainingPlannedContributionsMinor == null || remainingPlannedContributionsMinor >= 0L)
    }
}

enum class MissingFundsInput { ELIGIBLE_BALANCE, GOAL_RESERVATIONS, COMMITMENTS, GOAL_PLANS }

sealed interface FundsPlanningResult {
    data class Unavailable(val missing: Set<MissingFundsInput>) : FundsPlanningResult
    data class Ready(
        val input: FundsPlanningInput,
        val unreservedLiquidityMinor: Long,
        val availableAfterConfirmedReservationsMinor: Long,
        val flexibleFundsMinor: Long,
    ) : FundsPlanningResult
}

/**
 * Inputs must cover the same user, as-of date and horizon, including overdue bills.
 * R includes only reservations backed INSIDE L. P is the unmade remainder.
 * Known-empty bills/plans are zero; unimplemented/incomplete feeds remain null.
 */
fun calculateFundsPlan(input: FundsPlanningInput): FundsPlanningResult {
    val missing = mutableSetOf<MissingFundsInput>()
    if (input.eligibleBalanceMinor == null) missing += MissingFundsInput.ELIGIBLE_BALANCE
    if (input.reservedInEligibleAccountsMinor == null) missing += MissingFundsInput.GOAL_RESERVATIONS
    if (input.outstandingCommitmentsMinor == null) missing += MissingFundsInput.COMMITMENTS
    if (input.remainingPlannedContributionsMinor == null) missing += MissingFundsInput.GOAL_PLANS
    if (missing.isNotEmpty()) return FundsPlanningResult.Unavailable(missing.toSet())

    // Aggregates can exceed Money's per-record bound; keep checked Long totals.
    val unreserved = Math.subtractExact(
        requireNotNull(input.eligibleBalanceMinor),
        requireNotNull(input.reservedInEligibleAccountsMinor),
    )
    val afterConfirmed = Math.subtractExact(unreserved, requireNotNull(input.outstandingCommitmentsMinor))
    val flexible = Math.subtractExact(afterConfirmed, requireNotNull(input.remainingPlannedContributionsMinor))
    return FundsPlanningResult.Ready(input, unreserved, afterConfirmed, flexible)
}

data class PurchaseImpactPreview(
    val before: FundsPlanningResult.Ready,
    val after: FundsPlanningResult.Ready,
    val purchaseLkrMinor: Long,
    val commitmentReservationReleasedMinor: Long,
    val incrementalPlanningGapMinor: Long,
)

/**
 * Pure simulation: creates no financial event and changes no plan.
 * Ordinary purchase: release = 0.
 * Existing reserved bill: release is the outstanding reservation covered by this
 * payment, capped at both the purchase and C. A linked bill flow supplies it;
 * do not infer a release from an expense category alone.
 */
fun previewPurchaseImpact(
    input: FundsPlanningInput,
    purchase: EntryAmount,
    commitmentReservationReleasedMinor: Long = 0L,
): PurchaseImpactPreview {
    require(purchase.money.currency == FiatCurrency.LKR)
    val before = calculateFundsPlan(input) as? FundsPlanningResult.Ready
        ?: throw IllegalArgumentException("Complete planning inputs are required for a preview.")
    val amount = purchase.money.minor
    val outstanding = requireNotNull(input.outstandingCommitmentsMinor)
    require(commitmentReservationReleasedMinor in 0L..minOf(amount, outstanding)) {
        "A bill reservation release cannot exceed the payment or outstanding commitments."
    }

    val afterInput = input.copy(
        eligibleBalanceMinor = Math.subtractExact(requireNotNull(input.eligibleBalanceMinor), amount),
        outstandingCommitmentsMinor = Math.subtractExact(outstanding, commitmentReservationReleasedMinor),
    )
    val after = calculateFundsPlan(afterInput) as FundsPlanningResult.Ready
    fun gap(value: Long): BigInteger = BigInteger.valueOf(value).negate().max(BigInteger.ZERO)
    val incrementalGap = (gap(after.flexibleFundsMinor) - gap(before.flexibleFundsMinor)).toLongChecked()

    return PurchaseImpactPreview(before, after, amount, commitmentReservationReleasedMinor, incrementalGap)
}
