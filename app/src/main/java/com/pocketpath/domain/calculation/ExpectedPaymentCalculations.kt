package com.pocketpath.domain.calculation

import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.ExpectedPaymentState
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import java.time.LocalDate

/**
 * Read-only expected-income queries.
 *
 * Expected payments are configuration records, not ledger events: these helpers never affect
 * account balances, earnings, receipt state, or currency conversion. Dates are the caller's
 * explicit profile planning dates; device-local time is deliberately not consulted.
 */
fun activeExpectedPaymentsDueIn(
    payments: Iterable<ExpectedPayment>,
    start: LocalDate,
    endInclusive: LocalDate,
): List<ExpectedPayment> {
    require(endInclusive >= start) { "Expected-payment window ends before it starts." }
    val snapshot = payments.toList()
    require(snapshot.map(ExpectedPayment::id).distinct().size == snapshot.size) {
        "Duplicate expected-payment identity"
    }

    return snapshot.asSequence()
        .filter { it.state == ExpectedPaymentState.ACTIVE }
        .filter { it.dueDate in start..endInclusive }
        .sortedWith(compareBy<ExpectedPayment>({ it.dueDate }, { it.id }))
        .toList()
}

/**
 * Exact native-currency totals for active expected payments.
 *
 * Values are intentionally grouped instead of converted to LKR: a historical valuation or FX
 * policy is outside this increment.
 */
fun activeExpectedPaymentTotalsByCurrency(
    payments: Iterable<ExpectedPayment>,
): Map<FiatCurrency, Money> {
    val snapshot = payments.toList()
    require(snapshot.map(ExpectedPayment::id).distinct().size == snapshot.size) {
        "Duplicate expected-payment identity"
    }

    return snapshot.asSequence()
        .filter { it.state == ExpectedPaymentState.ACTIVE }
        .groupBy { it.amountDue.money.currency }
        .toSortedMap(compareBy(FiatCurrency::code))
        .mapValues { (currency, currencyPayments) ->
            Money(currency, currencyPayments.fold(0L) { total, payment ->
                Math.addExact(total, payment.amountDue.money.minor)
            })
        }
}
