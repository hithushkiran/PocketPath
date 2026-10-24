package com.pocketpath.data.local

import android.content.Context
import android.util.AtomicFile
import com.pocketpath.data.firebase.FoundationCodec
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import com.pocketpath.domain.repository.CreateAccountCommand
import com.pocketpath.domain.repository.RecordExpenseCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class StoredTime(
    val instant: String,
    val date: String,
    val zone: String,
) {
    fun domain() = FinancialTime(
        Instant.parse(instant),
        LocalDate.parse(date),
        ZoneId.of(zone),
    )

    companion object {
        fun from(value: FinancialTime): StoredTime {
            val stored = FoundationCodec.storedTime(value)
            return StoredTime(
                stored.occurredAt.toString(),
                stored.occurredLocalDate.toString(),
                stored.recordedTimeZone.id,
            )
        }
    }
}

@Serializable
data class StoredExpense(
    val operationId: String,
    val accountId: String,
    val categoryId: String,
    val amountMinor: Long,
    val classification: String,
    val time: StoredTime,
    val clientCreatedAt: String,
    val note: String? = null,
    val merchantLabel: String? = null,
) {
    fun command() = RecordExpenseCommand(
        operationId = operationId,
        accountId = accountId,
        amount = EntryAmount(Money.lkr(amountMinor)),
        categoryId = categoryId,
        commitmentClass = CommitmentClass.valueOf(classification),
        financialTime = time.domain(),
        clientCreatedAt = Instant.parse(clientCreatedAt),
        note = note,
        merchantLabel = merchantLabel,
    )

    fun event(): FinancialTransaction {
        val command = command()

        return FinancialTransaction(
            operationId = command.operationId,
            kind = TransactionKind.EXPENSE,
            status = TransactionStatus.POSTED,
            financialTime = command.financialTime,
            clientCreatedAt = command.clientCreatedAt,
            movements = listOf(
                AccountMovement(
                    command.accountId,
                    NativeAmount.Fiat(-command.amount.money),
                ),
            ),
            components = listOf(
                ReportComponent(
                    nature = ComponentNature.SPENDING,
                    lkrAmount = KnownLkrAmount.Known(command.amount.money),
                    categoryId = command.categoryId,
                    commitmentClass = command.commitmentClass,
                ),
            ),
            note = command.note,
            merchantLabel = command.merchantLabel,
        )
    }

    companion object {
        fun from(command: RecordExpenseCommand): StoredExpense {
            require(command.amount.money.currency == FiatCurrency.LKR)
            require(command.historicalRate == null && command.rateSnapshotId == null)
            require(command.occurrenceId == null)

            return StoredExpense(
                operationId = command.operationId,
                accountId = command.accountId,
                categoryId = command.categoryId,
                amountMinor = command.amount.money.minor,
                classification = command.commitmentClass.name,
                time = StoredTime.from(command.financialTime),
                clientCreatedAt = FoundationCodec
                    .storedInstant(command.clientCreatedAt).toString(),
                note = command.note,
                merchantLabel = command.merchantLabel,
            )
        }
    }
}

@Serializable
data class StoredAccount(
    val operationId: String,
    val accountId: String,
    val name: String,
    val type: String,
    val includeInPool: Boolean,
    val openingMinor: Long,
    val time: StoredTime,
    val clientCreatedAt: String,
    val institutionLabel: String? = null,
) {
    fun command(): CreateAccountCommand {
        val financialTime = time.domain()

        return CreateAccountCommand(
            operationId = operationId,
            account = Account(
                id = accountId,
                name = name,
                type = AccountType.valueOf(type),
                asset = FiatCurrency.LKR,
                includeInEverydayPool = includeInPool,
                openingTransactionId = operationId,
                openedOn = financialTime.occurredLocalDate,
                institutionLabel = institutionLabel,
            ),
            openingAmount = NativeAmount.Fiat(Money.lkr(openingMinor)),
            financialTime = financialTime,
            clientCreatedAt = Instant.parse(clientCreatedAt),
        )
    }

    companion object {
        fun from(command: CreateAccountCommand): StoredAccount {
            val opening = command.openingAmount as NativeAmount.Fiat
            require(opening.money.currency == FiatCurrency.LKR)

            return StoredAccount(
                operationId = command.operationId,
                accountId = command.account.id,
                name = command.account.name,
                type = command.account.type.name,
                includeInPool = command.account.includeInEverydayPool,
                openingMinor = opening.money.minor,
                time = StoredTime.from(command.financialTime),
                clientCreatedAt = FoundationCodec
                    .storedInstant(command.clientCreatedAt).toString(),
                institutionLabel = command.account.institutionLabel,
            )
        }
    }
}

@Serializable
enum class ReceiptPhase {
    PREPARED,
    PENDING,
    CONFIRMED,
    REJECTED,
}

@Serializable
data class ExpenseReceipt(
    val posting: StoredExpense,
    val attempt: Int = 1,
    val phase: ReceiptPhase = ReceiptPhase.PREPARED,
    val message: String? = null,
    val reviewed: Boolean = false,
)

@Serializable
data class SavedDrafts(
    val cash: Map<String, String> = emptyMap(),
    val expense: Map<String, String> = emptyMap(),
    val cashSaved: Boolean = false,
    val expenseSaved: Boolean = false,
    val accountCommand: StoredAccount? = null,
    val expenseCommand: StoredExpense? = null,
)

@Serializable
data class QueryCoverage(
    val ids: Set<String>,
    val observedAt: String,
)

@Serializable
data class LocalFinanceFile(
    val userId: String,
    val formatVersion: Int = 1,
    val draftRevision: Long = 0,
    val drafts: SavedDrafts = SavedDrafts(),
    val receipts: List<ExpenseReceipt> = emptyList(),
    val coverage: Map<String, QueryCoverage> = emptyMap(),
)

/**
 * Stores drafts, receipts and query-coverage IDs.
 * It is not a financial ledger or a posting queue.
 */
@Singleton
class FinanceLocalStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val directory = File(context.noBackupFilesDir, "finance-local")
    private val mutex = Mutex()
    private val changes = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val json = Json { encodeDefaults = true }

    private fun atomicFile(uid: String): AtomicFile {
        require(uid.isNotBlank())

        val key = MessageDigest.getInstance("SHA-256")
            .digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        check(directory.exists() || directory.mkdirs())
        return AtomicFile(File(directory, "$key.json"))
    }

    private fun readUnlocked(uid: String): LocalFinanceFile {
        val file = atomicFile(uid)

        val bytes = try {
            file.readFully()
        } catch (missing: FileNotFoundException) {
            if (file.baseFile.exists() ||
                File(file.baseFile.path + ".bak").exists()
            ) {
                throw missing
            }
            return LocalFinanceFile(userId = uid)
        }

        val result = json.decodeFromString<LocalFinanceFile>(
            bytes.toString(Charsets.UTF_8),
        )

        require(result.userId == uid && result.formatVersion == 1)
        return result
    }

    suspend fun read(uid: String): LocalFinanceFile =
        withContext(Dispatchers.IO) {
            mutex.withLock { readUnlocked(uid) }
        }

    fun observe(uid: String): Flow<LocalFinanceFile> =
        changes.map { it[uid] ?: 0L }
            .distinctUntilChanged()
            .map { read(uid) }

    private suspend fun edit(
        uid: String,
        change: (LocalFinanceFile) -> LocalFinanceFile,
    ): LocalFinanceFile = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = readUnlocked(uid)
            val next = change(old)
            require(next.userId == uid)

            if (next != old) {
                val file = atomicFile(uid)
                val stream = file.startWrite()

                try {
                    stream.write(json.encodeToString(next).toByteArray(Charsets.UTF_8))
                    file.finishWrite(stream)
                } catch (error: Throwable) {
                    file.failWrite(stream)
                    throw error
                }

                changes.update {
                    it + (uid to ((it[uid] ?: 0L) + 1L))
                }
            }

            next
        }
    }

    suspend fun saveDrafts(
        uid: String,
        revision: Long,
        drafts: SavedDrafts,
    ): LocalFinanceFile = edit(uid) {
        if (revision <= it.draftRevision) {
            it
        } else {
            it.copy(draftRevision = revision, drafts = drafts)
        }
    }

    suspend fun saveCoverage(
        uid: String,
        key: String,
        ids: Set<String>,
    ) {
        edit(uid) {
            it.copy(
                coverage = it.coverage + (
                    key to QueryCoverage(ids, Instant.now().toString())
                ),
            )
        }
    }

    suspend fun beginReceipt(
        uid: String,
        posting: StoredExpense,
    ): ExpenseReceipt {
        val file = edit(uid) { old ->
            val existing = old.receipts.firstOrNull {
                it.posting.operationId == posting.operationId
            }

            require(existing == null || existing.posting == posting) {
                "Operation identity has different content"
            }

            val receipt = when {
                existing == null -> ExpenseReceipt(posting)
                existing.phase == ReceiptPhase.REJECTED -> existing.copy(
                    attempt = Math.addExact(existing.attempt, 1),
                    phase = ReceiptPhase.PREPARED,
                    message = null,
                    reviewed = false,
                )
                else -> existing
            }

            old.copy(
                receipts = old.receipts.filterNot {
                    it.posting.operationId == posting.operationId
                } + receipt,
            )
        }

        return file.receipts.first {
            it.posting.operationId == posting.operationId
        }
    }

    suspend fun markReceipt(
        uid: String,
        id: String,
        attempt: Int,
        phase: ReceiptPhase,
        message: String? = null,
    ) {
        edit(uid) { old ->
            val updated = old.receipts.map { receipt ->
                if (receipt.posting.operationId != id ||
                    receipt.attempt != attempt ||
                    receipt.phase == ReceiptPhase.CONFIRMED ||
                    (
                        receipt.phase == ReceiptPhase.REJECTED &&
                            phase != ReceiptPhase.CONFIRMED
                    )
                ) {
                    receipt
                } else {
                    receipt.copy(phase = phase, message = message)
                }
            }

            val retainedCompleted = updated.filter {
                it.phase == ReceiptPhase.CONFIRMED ||
                    (it.phase == ReceiptPhase.REJECTED && it.reviewed)
            }.takeLast(100).map { it.posting.operationId }.toSet()

            val currentId = old.drafts.expenseCommand?.operationId

            old.copy(
                receipts = updated.filter {
                    it.phase == ReceiptPhase.PREPARED ||
                        it.phase == ReceiptPhase.PENDING ||
                        (it.phase == ReceiptPhase.REJECTED && !it.reviewed) ||
                        it.posting.operationId in retainedCompleted ||
                        it.posting.operationId == currentId
                },
            )
        }
    }

    suspend fun markReviewed(uid: String, id: String) {
        edit(uid) { old ->
            old.copy(
                receipts = old.receipts.map {
                    if (it.posting.operationId == id) {
                        it.copy(reviewed = true)
                    } else {
                        it
                    }
                },
            )
        }
    }
}
