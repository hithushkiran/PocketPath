package com.pocketpath.feature.income

import com.pocketpath.domain.model.ExpectedPayment
import com.pocketpath.domain.model.IncomeSource
import com.pocketpath.domain.money.EntryAmount
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.repository.WriteState
import java.time.LocalDate

data class ExpectedPaymentCreationUiState(
    val userId: String? = null,
    val loading: Boolean = true,
    val sources: List<IncomeSource> = emptyList(),
    val sourceId: String? = null,
    val projectLabel: String = "",
    val milestoneLabel: String = "",
    val amount: String = "",
    val dueDate: String = "",
    val busy: Boolean = false,
    val locked: Boolean = false,
    val saved: Boolean = false,
    val replayed: Boolean = false,
    val error: String? = null,
    val writeState: WriteState? = null,
) {
    val eligibleSources: List<IncomeSource>
        get() = sources.filter { it.metadata.archivedAt == null &&
            (it.defaultCurrency == null || it.defaultCurrency == FiatCurrency.LKR) }
    internal fun canStartSave() = !loading && !busy && !saved
}

sealed interface ExpectedPaymentCreationEvent {
    data class SourceChanged(val id: String) : ExpectedPaymentCreationEvent
    data class ProjectChanged(val value: String) : ExpectedPaymentCreationEvent
    data class MilestoneChanged(val value: String) : ExpectedPaymentCreationEvent
    data class AmountChanged(val value: String) : ExpectedPaymentCreationEvent
    data class DueDateChanged(val value: String) : ExpectedPaymentCreationEvent
    data object Save : ExpectedPaymentCreationEvent
    data object CreateAnother : ExpectedPaymentCreationEvent
}

internal object ExpectedPaymentCreationFactory {
    fun create(
        operationId: String,
        sourceId: String?,
        projectLabel: String,
        milestoneLabel: String,
        amount: String,
        dueDate: String,
        sources: List<IncomeSource>,
    ): ExpectedPayment {
        val selectedSource = requireNotNull(sourceId) { "Select an income source." }
        require(sources.any { it.id == selectedSource && it.metadata.archivedAt == null &&
            (it.defaultCurrency == null || it.defaultCurrency == FiatCurrency.LKR) }) {
            "Select an active LKR income source."
        }
        val project = projectLabel.trim()
        require(project.isNotBlank()) { "Enter a project label." }
        val milestone = milestoneLabel.trim().takeIf { it.isNotEmpty() }
        val money = Money.parse(amount.trim(), FiatCurrency.LKR)
        require(money.minor > 0) { "Enter a positive LKR amount." }
        return ExpectedPayment(
            id = operationId,
            incomeSourceId = selectedSource,
            projectLabel = project,
            milestoneLabel = milestone,
            amountDue = EntryAmount(money),
            dueDate = LocalDate.parse(dueDate.trim()),
        )
    }
}
