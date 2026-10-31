package com.pocketpath.feature.income

import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.Account
import com.pocketpath.domain.model.AccountType
import com.pocketpath.domain.model.StableIds
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ExpectedPaymentReceiptFactoryTest {
    private val payment = ExpectedPayment("payment", "source", "Project", EntryAmount(Money.lkr(10_000)), LocalDate.of(2026, 11, 1), receivedAmountMinor = 2_500)
    private val account = Account("account", "Cash", AccountType.CASH, FiatCurrency.LKR, true, "opening", LocalDate.of(2026, 1, 1))
    @Test fun rejectsMissingAccountBeforeBuildingReceipt() {
        assertThrows(IllegalArgumentException::class.java) {
            ExpectedPaymentReceiptFactory.create("op", payment, null, "75.01", emptyList(), Instant.EPOCH, ZoneId.of("Asia/Colombo"))
        }
    }
    @Test fun buildsExactLinkedIncomeEventWithinRemainingAmount() {
        val opId = StableIds.expectedPaymentReceipt(payment.id, payment.metadata.revision)
        val event = ExpectedPaymentReceiptFactory.create(opId, payment, account.id, "75.00", listOf(account), Instant.EPOCH, ZoneId.of("Asia/Colombo"))
        assertEquals("payment", event.expectedPaymentId)
        assertEquals(7_500L, (event.movements.single().amount as com.pocketpath.domain.money.NativeAmount.Fiat).money.minor)
        assertEquals("source", event.components.single().incomeSourceId)
    }
    @Test fun rejectsZeroNegativeAndOverpaymentAndInactiveAccount() {
        listOf("0", "-1", "75.01").forEach { value -> assertThrows(IllegalArgumentException::class.java) { ExpectedPaymentReceiptFactory.create("op", payment, account.id, value, listOf(account), Instant.EPOCH, ZoneId.of("Asia/Colombo")) } }
        assertThrows(IllegalArgumentException::class.java) { ExpectedPaymentReceiptFactory.create("op", payment, "other", "1", listOf(account), Instant.EPOCH, ZoneId.of("Asia/Colombo")) }
    }
}
