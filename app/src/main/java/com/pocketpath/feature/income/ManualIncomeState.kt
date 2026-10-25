package com.pocketpath.feature.income

import com.pocketpath.domain.model.Account
import com.pocketpath.domain.model.AccountMovement
import com.pocketpath.domain.model.ComponentNature
import com.pocketpath.domain.model.FinancialTime
import com.pocketpath.domain.model.FinancialTransaction
import com.pocketpath.domain.model.IncomeSource
import com.pocketpath.domain.model.ReportComponent
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.model.TransactionStatus
import com.pocketpath.domain.money.FiatCurrency
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.domain.money.Money
import com.pocketpath.domain.money.NativeAmount
import com.pocketpath.domain.repository.WriteState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ManualIncomeUiState(
    val userId: String? = null,
    val loading: Boolean = true,
    val referencesReady: Boolean = false,
    val error: String? = null,
    val accounts: List<Account> = emptyList(),
    val sources: List<IncomeSource> = emptyList(),
    val sourceId: String? = null,
    val accountId: String? = null,
    val amount: String = "",
    val date: String = "",
    val note: String = "",
    val busy: Boolean = false,
    val locked: Boolean = false,
    val saved: Boolean = false,
    val replayed: Boolean = false,
    val writeState: WriteState? = null,
) {
    internal fun canStartSave(): Boolean = referencesReady && !busy && !saved

    /** A frozen online income request exists only in this ViewModel's memory. */
    val hasUnresolvedIncomeWork: Boolean
        get() = locked && !saved

    val selectableAccounts: List<Account>
        get() = accounts.filter { it.asset == FiatCurrency.LKR && it.metadata.archivedAt == null }

    val selectableSources: List<IncomeSource>
        get() = sources.filter {
            it.metadata.archivedAt == null &&
                (it.defaultCurrency == null || it.defaultCurrency == FiatCurrency.LKR)
        }
}

sealed interface ManualIncomeEvent {
    data class SourceChanged(val id: String) : ManualIncomeEvent
    data class AccountChanged(val id: String) : ManualIncomeEvent
    data class AmountChanged(val value: String) : ManualIncomeEvent
    data class DateChanged(val value: String) : ManualIncomeEvent
    data class NoteChanged(val value: String) : ManualIncomeEvent
    data object Save : ManualIncomeEvent
    data object RecordAnother : ManualIncomeEvent
}

/** Shared with JVM tests so form validation and exact event construction stay outside Compose. */
internal object ManualIncomeEventFactory {
    fun create(
        operationId: String,
        sourceId: String?,
        accountId: String?,
        amountText: String,
        dateText: String,
        noteText: String,
        zone: ZoneId,
        now: Instant,
        accounts: List<Account>,
        sources: List<IncomeSource>,
    ): FinancialTransaction {
        val source = requireNotNull(sourceId) { "Select an income source." }
        val account = requireNotNull(accountId) { "Select a receiving account." }
        require(accounts.any { it.id == account && it.asset == FiatCurrency.LKR && it.metadata.archivedAt == null }) {
            "Select an active LKR receiving account."
        }
        require(sources.any {
            it.id == source && it.metadata.archivedAt == null &&
                (it.defaultCurrency == null || it.defaultCurrency == FiatCurrency.LKR)
        }) { "Select an active LKR income source." }
        require(noteText.length <= 1000) { "Notes allow 1000 characters." }

        val money = Money.parse(amountText.trim(), FiatCurrency.LKR)
        require(money.minor > 0) { "Enter a positive LKR amount." }
        val date = LocalDate.parse(dateText.trim())
        val today = now.atZone(zone).toLocalDate()
        require(!date.isAfter(today)) { "Use today or an earlier receipt date." }
        val occurredAt = if (date == today) now else date.atStartOfDay(zone).toInstant()

        return FinancialTransaction(
            operationId = operationId,
            kind = TransactionKind.INCOME,
            status = TransactionStatus.POSTED,
            financialTime = FinancialTime(occurredAt, date, zone),
            clientCreatedAt = now,
            movements = listOf(AccountMovement(account, NativeAmount.Fiat(money))),
            components = listOf(
                ReportComponent(
                    nature = ComponentNature.EARNING,
                    lkrAmount = KnownLkrAmount.Known(money),
                    incomeSourceId = source,
                ),
            ),
            note = noteText.trim().takeIf { it.isNotEmpty() },
        )
    }
}
