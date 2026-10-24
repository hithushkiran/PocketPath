package com.pocketpath.feature.finance

import com.pocketpath.data.local.*
import com.pocketpath.domain.model.CommitmentClass
import com.pocketpath.domain.repository.CreateAccountCommand
import com.pocketpath.domain.repository.RecordExpenseCommand
import com.pocketpath.domain.repository.WriteState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class FinanceDraftController(
    private val uid: String,
    private val local: FinanceLocalStore,
    private val state: MutableStateFlow<FinanceUiState>,
    private val scope: CoroutineScope,
    private val nextRevision: () -> Long,
) {
    var accountCommand: CreateAccountCommand? = null
    var expenseCommand: RecordExpenseCommand? = null

    private var receipts: List<ExpenseReceipt> = emptyList()

    private fun current() = scope.isActive && state.value.userId == uid

    suspend fun initialize(today: String): Long {
        val file = local.read(uid)
        check(current())

        accountCommand = file.drafts.accountCommand?.command()
        expenseCommand = file.drafts.expenseCommand?.command()

        val cash = file.drafts.cash
        val expense = file.drafts.expense

        state.update {
            it.copy(
                draftsLoaded = true,
                accountForm = CashAccountForm(
                    name = cash["name"].orEmpty(),
                    opening = cash["opening"] ?: "0.00",
                    date = cash["date"] ?: today,
                    saved = file.drafts.cashSaved,
                    locked = file.drafts.cashSaved || accountCommand != null,
                    error = cash["error"]?.takeIf(String::isNotEmpty),
                ),
                expenseForm = ExpenseForm(
                    accountId = expense["account"]?.takeIf(String::isNotEmpty),
                    categoryId = expense["category"]?.takeIf(String::isNotEmpty),
                    amount = expense["amount"].orEmpty(),
                    date = expense["date"] ?: today,
                    note = expense["note"].orEmpty(),
                    classification = expense["classification"]
                        ?.let(CommitmentClass::valueOf)
                        ?: CommitmentClass.DISCRETIONARY,
                    saved = file.drafts.expenseSaved,
                    locked = file.drafts.expenseSaved || expenseCommand != null,
                    error = expense["error"]?.takeIf(String::isNotEmpty),
                ),
            )
        }

        receipts = file.receipts
        applyReceiptState()

        return file.draftRevision
    }

    fun startWatching() {
        scope.launch {
            local.observe(uid)
                .map { it.receipts }
                .distinctUntilChanged()
                .collect {
                    if (current()) {
                        receipts = it
                        applyReceiptState()
                    }
                }
        }

        scope.launch {
            state.map { encoded(it) }
                .distinctUntilChanged()
                .collect { drafts ->
                    if (!current()) return@collect

                    state.update { it.copy(draftSaving = true) }

                    try {
                        val file = local.saveDrafts(uid, nextRevision(), drafts)

                        if (current() && file.drafts == encoded(state.value)) {
                            state.update {
                                it.copy(draftSaving = false, localError = null)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (current()) {
                            state.update {
                                it.copy(
                                    draftSaving = false,
                                    localError = "Unable to retain this draft on the device.",
                                )
                            }
                        }
                    }
                }
        }
    }

    suspend fun flush() {
        check(current())
        val snapshot = encoded(state.value)

        state.update { it.copy(draftSaving = true) }
        local.saveDrafts(uid, nextRevision(), snapshot)

        if (current() && snapshot == encoded(state.value)) {
            state.update { it.copy(draftSaving = false, localError = null) }
        }
    }

    suspend fun refreshReceipts() {
        val file = local.read(uid)
        if (current()) {
            receipts = file.receipts
            applyReceiptState()
        }
    }

    private fun encoded(value: FinanceUiState) = SavedDrafts(
        cash = mapOf(
            "name" to value.accountForm.name,
            "opening" to value.accountForm.opening,
            "date" to value.accountForm.date,
            "error" to value.accountForm.error.orEmpty(),
        ),
        expense = mapOf(
            "account" to value.expenseForm.accountId.orEmpty(),
            "category" to value.expenseForm.categoryId.orEmpty(),
            "amount" to value.expenseForm.amount,
            "date" to value.expenseForm.date,
            "note" to value.expenseForm.note,
            "classification" to value.expenseForm.classification.name,
            "error" to value.expenseForm.error.orEmpty(),
        ),
        cashSaved = value.accountForm.saved,
        expenseSaved = value.expenseForm.saved,
        accountCommand = accountCommand?.let(StoredAccount::from),
        expenseCommand = expenseCommand?.let(StoredExpense::from),
    )

    fun applyReceiptState() {
        if (!current()) return

        val active = receipts.firstOrNull {
            it.posting.operationId == expenseCommand?.operationId
        }

        state.update { old ->
            var form = old.expenseForm

            if (active != null) {
                form = when (active.phase) {
                    ReceiptPhase.PREPARED -> form.copy(
                        saved = false,
                        locked = true,
                        writeState = WriteState.LOCAL_SAVED,
                    )

                    ReceiptPhase.PENDING -> form.copy(
                        busy = false,
                        saved = true,
                        locked = true,
                        error = null,
                        writeState = WriteState.PENDING,
                    )

                    ReceiptPhase.CONFIRMED -> form.copy(
                        busy = false,
                        saved = true,
                        locked = true,
                        error = null,
                        writeState = WriteState.CLOUD_CONFIRMED,
                    )

                    ReceiptPhase.REJECTED -> form.copy(
                        busy = false,
                        saved = false,
                        locked = false,
                        error = active.message ?: "Review this expense before retrying.",
                        writeState = WriteState.REJECTED,
                    )
                }
            }

            old.copy(
                expenseForm = form,
                receiptPendingIds = receipts.filter {
                    it.phase == ReceiptPhase.PENDING
                }.map { it.posting.operationId }.toSet(),
                uncertainSaveIds = receipts.filter {
                    it.phase == ReceiptPhase.PREPARED
                }.map { it.posting.operationId }.toSet(),
                rejectedExpenses = receipts.filter {
                    it.phase == ReceiptPhase.REJECTED && !it.reviewed
                }.map {
                    RejectedExpense(
                        it.posting.operationId,
                        it.message ?: "Expense needs review.",
                    )
                },
            )
        }
    }

    fun acknowledgeRejected(id: String) {
        scope.launch {
            try {
                flush()
                local.markReviewed(uid, id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current()) {
                    state.update {
                        it.copy(localError = "Unable to retain the reviewed draft.")
                    }
                }
            }
        }
    }

    fun reviewRejected(id: String) {
        val currentForm = state.value.expenseForm

        if (!currentForm.saved &&
            (
                currentForm.locked ||
                    currentForm.amount.isNotBlank() ||
                    currentForm.note.isNotBlank()
            )
        ) {
            state.update {
                it.copy(
                    expenseForm = it.expenseForm.copy(
                        error = "Finish or clear the current draft before reviewing another.",
                    ),
                )
            }
            return
        }

        val receipt = receipts.firstOrNull {
            it.posting.operationId == id && it.phase == ReceiptPhase.REJECTED
        } ?: return

        val command = receipt.posting.command()
        expenseCommand = command

        state.update {
            it.copy(
                expenseForm = ExpenseForm(
                    accountId = command.accountId,
                    categoryId = command.categoryId,
                    amount = command.amount.money.major().toPlainString(),
                    date = command.financialTime.occurredLocalDate.toString(),
                    note = command.note.orEmpty(),
                    classification = command.commitmentClass,
                    error = receipt.message,
                    writeState = WriteState.REJECTED,
                ),
            )
        }

        acknowledgeRejected(id)
    }
}
