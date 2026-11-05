package com.pocketpath.data.local

import android.content.Context
import com.pocketpath.data.firebase.FoundationCodec
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ReceiptRecoveryPhase { DRAFT, PREPARED, CONFIRMED, RESOLVED }

enum class ReceiptResolutionKind { IDENTICAL, DIFFERENT_OPERATION, PERMANENT_REJECTION }

@Serializable
data class StoredReceiptResolution(
    val kind: ReceiptResolutionKind,
    val inspectedAt: String,
    val reason: String,
    val serverOperation: String? = null,
    val serverPayment: String? = null,
)

/** The entire restricted linked-income command, not a ledger or a posting queue. */
@Serializable
data class StoredReceiptCommand(
    val paymentId: String,
    val originalRevision: Long,
    val operationId: String,
    val sourceId: String,
    val accountId: String,
    val amountMinor: Long,
    val occurredAt: String,
    val localDate: String,
    val zone: String,
    val clientCreatedAt: String,
) {
    fun event(): FinancialTransaction {
        listOf(paymentId, operationId, sourceId, accountId).forEach(FoundationCodec::checkId)
        require(originalRevision >= 0 && operationId == StableIds.expectedPaymentReceipt(paymentId, originalRevision))
        val amount = EntryAmount(Money.lkr(amountMinor)).money
        fun instant(text: String): Instant = Instant.parse(text).also {
            require(it == FoundationCodec.storedInstant(it)) { "Noncanonical receipt timestamp." }
            require(it.atZone(ZoneId.of("UTC")).year in 1..9999) { "Receipt timestamp out of bounds." }
        }
        val time = FinancialTime(instant(occurredAt), LocalDate.parse(localDate), ZoneId.of(zone))
        return FinancialTransaction(
            operationId = operationId, kind = TransactionKind.INCOME, status = TransactionStatus.POSTED,
            financialTime = time, clientCreatedAt = instant(clientCreatedAt),
            movements = listOf(AccountMovement(accountId, NativeAmount.Fiat(amount))),
            components = listOf(ReportComponent(ComponentNature.EARNING, KnownLkrAmount.Known(amount), incomeSourceId = sourceId)),
            expectedPaymentId = paymentId,
        )
    }

    companion object {
        fun from(event: FinancialTransaction, revision: Long): StoredReceiptCommand = StoredReceiptCommand(
            requireNotNull(event.expectedPaymentId), revision, event.id,
            requireNotNull(event.components.single().incomeSourceId), event.movements.single().accountId,
            (event.movements.single().amount as NativeAmount.Fiat).money.minor,
            event.financialTime.occurredAt.toString(), event.financialTime.occurredLocalDate.toString(),
            event.financialTime.recordedTimeZone.id, event.clientCreatedAt.toString(),
        ).also {
            require(FoundationCodec.sameExpectedPaymentReceipt(FoundationCodec.eventData(event), FoundationCodec.eventData(it.event())))
        }
    }
}

@Serializable
data class StoredReceipt(
    val paymentId: String,
    val projectLabel: String,
    val accountId: String? = null,
    val amountText: String = "",
    val phase: ReceiptRecoveryPhase = ReceiptRecoveryPhase.DRAFT,
    val command: StoredReceiptCommand? = null,
    val replayed: Boolean = false,
    val resolution: StoredReceiptResolution? = null,
) {
    fun validate() {
        FoundationCodec.checkId(paymentId)
        accountId?.let(FoundationCodec::checkId)
        require(projectLabel.isNotBlank() && projectLabel.length <= 1000 && amountText.length <= 128)
        require((phase == ReceiptRecoveryPhase.DRAFT) == (command == null))
        require(!replayed || phase == ReceiptRecoveryPhase.CONFIRMED)
        require((phase == ReceiptRecoveryPhase.RESOLVED) == (resolution != null && resolution.kind != ReceiptResolutionKind.IDENTICAL))
        resolution?.let {
            require(phase == ReceiptRecoveryPhase.CONFIRMED || phase == ReceiptRecoveryPhase.RESOLVED)
            require(Instant.parse(it.inspectedAt) == FoundationCodec.storedInstant(Instant.parse(it.inspectedAt)))
            require(it.reason.isNotBlank() && it.reason.length <= 4000)
            require((it.serverOperation?.length ?: 0) <= 16000 && (it.serverPayment?.length ?: 0) <= 8000)
            require(it.kind == ReceiptResolutionKind.PERMANENT_REJECTION || it.serverOperation != null)
            require(it.kind != ReceiptResolutionKind.PERMANENT_REJECTION || it.serverOperation == null && it.serverPayment != null)
        }
        command?.let {
            it.event()
            require(it.paymentId == paymentId && it.accountId == accountId)
            require(Money.parse(amountText.trim(), FiatCurrency.LKR).minor == it.amountMinor)
        }
    }
}

@Serializable
private data class ReceiptEnvelope(val schema: Int = 1, val owner: String, val receipt: StoredReceipt?)

/** Injectable file boundary: the same store/codec is exercised in JVM file-reopen tests. */
interface ReceiptFiles {
    fun read(owner: String): ByteArray?
    fun write(owner: String, bytes: ByteArray)
}

class PrivateReceiptFiles(private val directory: File) : ReceiptFiles {
    private fun file(owner: String): File {
        require(owner.isNotBlank())
        val hash = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }

    override fun read(owner: String): ByteArray? {
        val file = file(owner)
        // A missing file is known-empty. All other I/O/decode errors propagate visibly.
        return try {
            require(Files.size(file.toPath()) <= 64 * 1024) { "Receipt recovery file is too large." }
            Files.readAllBytes(file.toPath())
        } catch (_: java.nio.file.NoSuchFileException) {
            null
        }
    }

    override fun write(owner: String, bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create receipt recovery directory." }
        val target = file(owner)
        val temporary = File.createTempFile("receipt-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            // Same-directory atomic replacement: a crash retains either the old or new envelope.
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}

@Singleton
class ReceiptRecoveryStore(private val files: ReceiptFiles) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        PrivateReceiptFiles(File(context.noBackupFilesDir, "expected-payment-receipt-v1")),
    )

    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    private fun readLocked(owner: String): StoredReceipt? {
        require(owner.isNotBlank())
        val data = files.read(owner) ?: return null
        require(data.size <= 64 * 1024) { "Receipt recovery file is too large." }
        val envelope = json.decodeFromString<ReceiptEnvelope>(data.toString(Charsets.UTF_8))
        require(envelope.schema == 1 && envelope.owner == owner) { "Invalid receipt recovery owner/schema." }
        return envelope.receipt?.also { it.validate() }
    }

    suspend fun read(owner: String): StoredReceipt? = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock { readLocked(owner) }
    }

    /** Serialized compare-and-replace prevents stale session/autosave writes replacing commands. */
    suspend fun replace(owner: String, expected: StoredReceipt?, next: StoredReceipt?) =
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                next?.validate()
                val actual = readLocked(owner)
                check(actual == expected || actual == next) { "Receipt recovery changed; reload before continuing." }
                val bytes = json.encodeToString(ReceiptEnvelope(owner = owner, receipt = next)).toByteArray(Charsets.UTF_8)
                require(bytes.size <= 64 * 1024) { "Receipt recovery file is too large." }
                files.write(owner, bytes)
            }
        }
}
