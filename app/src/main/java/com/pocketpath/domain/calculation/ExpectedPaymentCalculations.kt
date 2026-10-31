package com.pocketpath.domain.calculation

import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.ExpectedPaymentState
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.repository.CompleteLedger
import com.pocketpath.domain.repository.LedgerScope
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

/**
 * Outstanding active expected income, derived from posted linked receipts in complete user history.
 * The stored payment counter is checked against the ledger projection, never trusted as the source.
 */
fun activeExpectedPaymentOutstandingTotalsByCurrency(
    payments: Iterable<ExpectedPayment>,
    ledger: CompleteLedger,
): Map<FiatCurrency, Money> {
    require(ledger.scope == LedgerScope.AllEvents) {
        "Expected-payment totals require complete user event scope."
    }
    val snapshot = payments.toList()
    require(snapshot.map(ExpectedPayment::id).distinct().size == snapshot.size) {
        "Duplicate expected-payment identity"
    }
    val byId = snapshot.associateBy(ExpectedPayment::id)
    val receivedByPayment = mutableMapOf<String, Long>()
    val receiptsByPayment = mutableMapOf<String, MutableSet<String>>()

    ledger.events.filter { it.expectedPaymentId != null && it.status == TransactionStatus.POSTED }.forEach { receipt ->
        require(receipt.kind == TransactionKind.INCOME) { "Expected-payment receipts must be income events." }
        val paymentId = requireNotNull(receipt.expectedPaymentId)
        val payment = requireNotNull(byId[paymentId]) { "Receipt references a missing expected payment." }
        val amount = (receipt.movements.single().amount as? com.pocketpath.domain.money.NativeAmount.Fiat)?.money
            ?: throw IllegalArgumentException("Expected-payment receipts require fiat movements.")
        require(amount.minor > 0) { "Expected-payment receipt amount must be positive." }
        require(amount.currency == payment.amountDue.money.currency) {
            "Expected-payment receipt currency does not match."
        }
        require(receipt.components.single().incomeSourceId == payment.incomeSourceId) {
            "Expected-payment receipt source does not match."
        }
        receivedByPayment[paymentId] = Math.addExact(receivedByPayment[paymentId] ?: 0L, amount.minor)
        receiptsByPayment.getOrPut(paymentId) { mutableSetOf() }.add(receipt.id)
    }

    snapshot.forEach { payment ->
        val ledgerReceived = receivedByPayment[payment.id] ?: 0L
        require(payment.receivedAmountMinor == ledgerReceived) {
            "Expected-payment receipt counter does not match the ledger."
        }
        require(payment.lastReceiptOperationId == null ||
            payment.lastReceiptOperationId in receiptsByPayment[payment.id].orEmpty()
        ) { "Expected-payment last receipt is missing from the ledger." }
        require(ledgerReceived <= payment.amountDue.money.minor) {
            "Expected-payment receipts exceed the expected amount."
        }
    }

    return snapshot.asSequence()
        .filter { it.state == ExpectedPaymentState.ACTIVE }
        .groupBy { it.amountDue.money.currency }
        .toSortedMap(compareBy(FiatCurrency::code))
        .mapValues { (currency, currencyPayments) ->
            Money(currency, currencyPayments.fold(0L) { total, payment ->
                val received = receivedByPayment[payment.id] ?: 0L
                Math.addExact(total, payment.amountDue.money.minor - received)
            })
        }
}
