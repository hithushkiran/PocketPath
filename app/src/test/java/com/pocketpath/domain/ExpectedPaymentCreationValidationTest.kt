package com.pocketpath.domain

import com.pocketpath.data.firebase.FoundationCodec
import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.ExpectedPaymentState
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ExpectedPaymentCreationValidationTest {
    private fun payment(
        currency: FiatCurrency = FiatCurrency.LKR,
        minor: Long = 12_345L,
        state: ExpectedPaymentState = ExpectedPaymentState.ACTIVE,
    ) = ExpectedPayment(
        id = "payment-operation",
        incomeSourceId = "income-source",
        projectLabel = "Website",
        amountDue = EntryAmount(Money(currency, minor)),
        dueDate = LocalDate.of(2026, 11, 1),
        state = state,
        cancelledAt = if (state == ExpectedPaymentState.CANCELLED) Instant.EPOCH else null,
    )

    @Test fun codecPreservesExactPositiveLkrCreationAmount() {
        assertEquals(12_345L, FoundationCodec.expectedPaymentData(payment())["amountDueMinor"])
    }

    @Test fun codecRejectsNonCreationMetadataAndUnsupportedFiat() {
        assertThrows(IllegalArgumentException::class.java) {
            FoundationCodec.expectedPaymentData(payment(FiatCurrency("EUR", 2)))
        }
    }
}
