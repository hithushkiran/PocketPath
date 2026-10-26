package com.pocketpath.feature.income

import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.ExpectedPaymentState
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.repository.DataOrigin
import com.pocketpath.domain.repository.FailureCode
import com.pocketpath.domain.repository.Observation
import com.pocketpath.domain.repository.ObservationMetadata
import com.pocketpath.domain.repository.RepositoryFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ExpectedPaymentsStateMapperTest {
    private val start = LocalDate.of(2026, 10, 10)
    private val end = LocalDate.of(2026, 10, 20)
    private val metadata = ObservationMetadata(
        origin = DataOrigin.SERVER,
        observedAt = Instant.parse("2026-10-01T00:00:00Z"),
    )

    private fun payment(
        id: String,
        dueDate: LocalDate,
        currency: FiatCurrency = FiatCurrency.LKR,
        minor: Long = 100L,
        state: ExpectedPaymentState = ExpectedPaymentState.ACTIVE,
    ) = ExpectedPayment(
        id = id,
        incomeSourceId = "freelance",
        projectLabel = "Project $id",
        amountDue = EntryAmount(Money(currency, minor)),
        dueDate = dueDate,
        state = state,
        cancelledAt = if (state == ExpectedPaymentState.CANCELLED) Instant.EPOCH else null,
    )

    @Test fun loadingAndEmptyObservationsMapToDistinctStates() {
        val loading = ExpectedPaymentsStateMapper.map(Observation.Loading, start, end)
        val empty = ExpectedPaymentsStateMapper.map(Observation.Data(emptyList(), metadata), start, end)

        assertTrue(loading.loading)
        assertFalse(loading.isEmpty)
        assertFalse(empty.loading)
        assertTrue(empty.isEmpty)
        assertFalse(empty.isPopulated)
        assertEquals(metadata, empty.metadata)
    }

    @Test fun populatedStateUsesWindowFilteringOrderingAndNativeCurrencyTotals() {
        val state = ExpectedPaymentsStateMapper.map(
            Observation.Data(
                listOf(
                    payment("late", LocalDate.of(2026, 10, 20), minor = 99L),
                    payment("same-b", LocalDate.of(2026, 10, 15), FiatCurrency.USD, 500L),
                    payment("outside", LocalDate.of(2026, 10, 21), minor = 1000L),
                    payment("same-a", LocalDate.of(2026, 10, 15), minor = 1_001L),
                    payment("cancelled", LocalDate.of(2026, 10, 12), minor = 999L, state = ExpectedPaymentState.CANCELLED),
                ),
                metadata,
            ),
            start,
            end,
        )

        assertEquals(listOf("same-a", "same-b", "late"), state.payments.map(ExpectedPayment::id))
        assertEquals(Money.lkr(1_100L), state.totalsByCurrency.getValue(FiatCurrency.LKR))
        assertEquals(Money(FiatCurrency.USD, 500L), state.totalsByCurrency.getValue(FiatCurrency.USD))
        assertTrue(state.isPopulated)
        assertNull(state.error)
    }

    @Test fun repositoryFailureSurfacesTypedErrorAndRetainsFilteredLastAvailableData() {
        val last = Observation.Data(
            listOf(payment("in-window", LocalDate.of(2026, 10, 12)), payment("outside", LocalDate.of(2026, 10, 21))),
            metadata,
        )
        val failure = RepositoryFailure(FailureCode.NETWORK, "Network unavailable")

        val state = ExpectedPaymentsStateMapper.map(Observation.Failed(failure, last), start, end)

        assertEquals(failure, state.error)
        assertEquals(listOf("in-window"), state.payments.map(ExpectedPayment::id))
        assertTrue(state.isPopulated)
    }

    @Test fun malformedSnapshotBecomesValidationErrorWithoutInventingData() {
        val duplicate = payment("same", LocalDate.of(2026, 10, 12))

        val state = ExpectedPaymentsStateMapper.map(
            Observation.Data(listOf(duplicate, duplicate), metadata),
            start,
            end,
        )

        assertEquals(FailureCode.VALIDATION, state.error?.code)
        assertTrue(state.payments.isEmpty())
        assertTrue(state.totalsByCurrency.isEmpty())
    }
}
