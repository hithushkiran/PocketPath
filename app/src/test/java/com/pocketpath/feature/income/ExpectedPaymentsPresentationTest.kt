package com.pocketpath.feature.income

import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.ExpectedPaymentState
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.repository.DataOrigin
import com.pocketpath.domain.repository.Observation
import com.pocketpath.domain.repository.ObservationMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ExpectedPaymentsPresentationTest {
    private val metadata = ObservationMetadata(DataOrigin.SERVER, observedAt = Instant.EPOCH)
    private val payment = ExpectedPayment(
        id = "payment-1",
        incomeSourceId = "source-1",
        projectLabel = "Website milestone",
        amountDue = EntryAmount(Money(FiatCurrency.USD, 123_456L)),
        dueDate = LocalDate.of(2026, 11, 2),
    )

    @Test fun signedOutStateClearsUserOwnedPresentationData() {
        val state = ExpectedPaymentsSessionState.signedOut()

        assertNull(state.userId)
        assertFalse(state.loading)
        assertTrue(state.payments.isEmpty())
        assertTrue(state.totalsByCurrency.isEmpty())
    }

    @Test fun currentUserObservationUsesTheExistingMapper() {
        val state = ExpectedPaymentsSessionState.mapIfCurrent(
            activeUserId = "user-a",
            expectedUserId = "user-a",
            observation = Observation.Data(listOf(payment), metadata),
            start = LocalDate.MIN,
            endInclusive = LocalDate.MAX,
        )

        requireNotNull(state)
        assertEquals("user-a", state.userId)
        assertEquals(listOf(payment), state.payments)
        assertEquals(Money(FiatCurrency.USD, 123_456L), state.totalsByCurrency[FiatCurrency.USD])
    }

    @Test fun staleUserObservationIsIgnoredToPreventCrossUserExposure() {
        val state = ExpectedPaymentsSessionState.mapIfCurrent(
            activeUserId = "user-b",
            expectedUserId = "user-a",
            observation = Observation.Data(listOf(payment), metadata),
            start = LocalDate.MIN,
            endInclusive = LocalDate.MAX,
        )

        assertNull(state)
    }

    @Test fun presentationFormatsExactNativeCurrencyAndStatusWithoutFxConversion() {
        assertEquals("USD 1234.56", expectedPaymentAmountText(payment))
        assertEquals("Active", expectedPaymentStatusText(payment))
        assertEquals(
            "Cancelled",
            expectedPaymentStatusText(payment.copy(
                state = ExpectedPaymentState.CANCELLED,
                cancelledAt = Instant.EPOCH,
            )),
        )
    }

    @Test fun cancellationPresentationStateIsSessionScopedAndCanSignalRetry() {
        val state = ExpectedPaymentsUiState(
            userId = "user-a",
            payments = listOf(payment),
            hasUnresolvedCancellationRetry = true,
            cancellationError = com.pocketpath.domain.repository.RepositoryFailure(
                com.pocketpath.domain.repository.FailureCode.NETWORK,
                "Connection lost",
                "cancel-operation",
            ),
        )

        assertTrue(state.hasUnresolvedCancellationRetry)
        assertEquals("cancel-operation", state.cancellationError?.operationId)
        assertFalse(ExpectedPaymentsSessionState.signedOut().hasUnresolvedCancellationRetry)
        assertNull(
            ExpectedPaymentsSessionState.mapIfCurrent(
                activeUserId = "user-b",
                expectedUserId = state.userId!!,
                observation = Observation.Data(state.payments, metadata),
                start = LocalDate.MIN,
                endInclusive = LocalDate.MAX,
            ),
        )
    }
}
