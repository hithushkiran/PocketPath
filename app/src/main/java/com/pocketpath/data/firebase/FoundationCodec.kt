package com.pocketpath.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.pocketpath.domain.model.*
import com.pocketpath.domain.money.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal typealias FirestoreFields = Map<String, Any?>

/**
 * Initial production mapping for LKR accounts and OPENING/EXPENSE events.
 * Unsupported records fail explicitly rather than disappearing from totals.
 */
internal object FoundationCodec {

    private val accountRequired = setOf(
        "name", "type", "currencyCode", "assetClass", "scale",
        "includeInEverydayPool", "openingTransactionId", "openedOn",
        "createdAt", "updatedAt", "revision",
    )

    private val eventRequired = setOf(
        "operationId", "kind", "status",
        "occurredAt", "occurredLocalDate", "recordedTimeZone",
        "clientCreatedAt", "createdAt", "schemaVersion", "revision",
        "accountIds", "categoryIds", "goalIds", "incomeSourceIds",
        "movements", "components", "reservationEffects", "valuations",
    )

    private val profileRequired = setOf(
        "baseCurrency", "timeZone", "weekStartsOn",
        "onboardingCompleted", "hideBalances", "remindersEnabled",
        "schemaVersion", "createdAt", "updatedAt",
    )

    private val incomeSourceRequired = setOf(
        "name", "kind", "createdAt", "updatedAt", "revision",
    )

    private val expectedPaymentRequired = setOf(
        "incomeSourceId", "projectLabel", "currencyCode", "amountDueMinor", "dueDate",
        "state", "createdAt", "updatedAt", "revision",
    )

    fun incomeSourceData(source: IncomeSource): Map<String, Any> {
        checkId(source.id)
        require(source.metadata == RecordMetadata()) {
            "New income sources cannot supply server metadata"
        }
        require(source.defaultCurrency == null || source.defaultCurrency == FiatCurrency.LKR) {
            "Income sources are LKR-only in the first increment"
        }
        source.defaultAccountId?.let(::checkId)

        return buildMap {
            put("name", source.name)
            put("kind", source.kind.name)
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
            put("revision", 0L)
            source.defaultAccountId?.let { put("defaultAccountId", it) }
            source.defaultCurrency?.let { put("defaultCurrencyCode", it.code) }
        }
    }

    fun incomeSource(
        id: String,
        data: FirestoreFields,
        pending: Boolean = false,
    ): IncomeSource {
        checkId(id)
        data.checkKeys(
            incomeSourceRequired,
            setOf("defaultAccountId", "defaultCurrencyCode", "archivedAt"),
        )

        val currency = data.optionalText("defaultCurrencyCode")?.let { FiatCurrency(it) }
        require(currency == null || currency == FiatCurrency.LKR) {
            "Income sources are LKR-only in the first increment"
        }

        return IncomeSource(
            id = id,
            name = data.text("name"),
            kind = IncomeSourceKind.valueOf(data.text("kind")),
            defaultAccountId = data.optionalText("defaultAccountId")?.also(::checkId),
            defaultCurrency = currency,
            metadata = RecordMetadata(
                createdAt = if (pending && data["createdAt"] == null) null else data.instant("createdAt"),
                updatedAt = if (pending && data["updatedAt"] == null) null else data.instant("updatedAt"),
                revision = data.integer("revision"),
                archivedAt = data.optionalInstant("archivedAt"),
            ),
        )
    }

    /**
     * Canonical expected-payment mapping from the shared P01 contract.
     *
     * This is deliberately a codec boundary only. Firestore Rules do not yet authorize
     * expected-payment creation, so callers must not treat encoding as persistence support.
     */
    fun expectedPaymentData(payment: ExpectedPayment): Map<String, Any> {
        checkId(payment.id)
        checkId(payment.incomeSourceId)
        require(payment.metadata == RecordMetadata()) {
            "New expected payments cannot supply server metadata"
        }
        require(payment.amountDue.money.currency in setOf(FiatCurrency.LKR, FiatCurrency.USD)) {
            "Expected-payment persistence currently supports only LKR and USD"
        }
        require(payment.receivedAmountMinor == 0L && payment.lastReceiptOperationId == null) {
            "New expected payments cannot contain receipt allocation state"
        }

        return buildMap {
            put("incomeSourceId", payment.incomeSourceId)
            put("projectLabel", payment.projectLabel)
            put("currencyCode", payment.amountDue.money.currency.code)
            put("amountDueMinor", payment.amountDue.money.minor)
            put("dueDate", payment.dueDate.toString())
            put("state", payment.state.name)
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
            put("revision", 0L)
            put("receivedAmountMinor", 0L)
            payment.milestoneLabel?.let { put("milestoneLabel", it) }
            payment.clientLabel?.let { put("clientLabel", it) }
            payment.cancelledAt?.let { put("cancelledAt", timestamp(it)) }
            payment.notes?.let { put("notes", it) }
        }
    }

    fun expectedPayment(
        id: String,
        data: FirestoreFields,
        pending: Boolean = false,
    ): ExpectedPayment {
        checkId(id)
        data.checkKeys(
            expectedPaymentRequired,
            setOf("milestoneLabel", "clientLabel", "cancelledAt", "notes", "archivedAt", "cancellationOperationId", "receivedAmountMinor", "lastReceiptOperationId"),
        )

        return ExpectedPayment(
            id = id,
            incomeSourceId = data.text("incomeSourceId").also(::checkId),
            projectLabel = data.text("projectLabel"),
            amountDue = EntryAmount(Money(expectedPaymentCurrency(data.text("currencyCode")), data.integer("amountDueMinor"))),
            dueDate = LocalDate.parse(data.text("dueDate")),
            milestoneLabel = data.optionalText("milestoneLabel"),
            clientLabel = data.optionalText("clientLabel"),
            state = ExpectedPaymentState.valueOf(data.text("state")),
            cancelledAt = data.optionalInstant("cancelledAt"),
            notes = data.optionalText("notes"),
            cancellationOperationId = data.optionalText("cancellationOperationId")?.also(::checkId),
            receivedAmountMinor = (data["receivedAmountMinor"] as? Number)?.toLong() ?: 0L,
            lastReceiptOperationId = data.optionalText("lastReceiptOperationId")?.also(::checkId),
            metadata = RecordMetadata(
                createdAt = if (pending && data["createdAt"] == null) null else data.instant("createdAt"),
                updatedAt = if (pending && data["updatedAt"] == null) null else data.instant("updatedAt"),
                revision = data.integer("revision"),
                archivedAt = data.optionalInstant("archivedAt"),
            ),
        )
    }

    fun categoryData(category: Category): Map<String, Any> {
        checkId(category.id)
        require(category.metadata == RecordMetadata())

        return buildMap {
            put("name", category.name)
            put("defaultCommitmentClass", category.defaultCommitmentClass.name)
            put("sortOrder", category.sortOrder.toLong())
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
            put("revision", 0L)
            category.iconKey?.let { put("iconKey", it) }
        }
    }

    fun category(id: String, data: FirestoreFields): Category {
        checkId(id)
        data.checkKeys(
            setOf(
                "name", "defaultCommitmentClass", "sortOrder",
                "createdAt", "updatedAt", "revision",
            ),
            setOf("iconKey", "archivedAt"),
        )

        val sortOrder = data.integer("sortOrder")
        require(sortOrder in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())

        return Category(
            id = id,
            name = data.text("name"),
            defaultCommitmentClass = CommitmentClass.valueOf(
                data.text("defaultCommitmentClass"),
            ),
            iconKey = data.optionalText("iconKey"),
            sortOrder = sortOrder.toInt(),
            metadata = RecordMetadata(
                createdAt = data.instant("createdAt"),
                updatedAt = data.instant("updatedAt"),
                revision = data.integer("revision"),
                archivedAt = data.optionalInstant("archivedAt"),
            ),
        )
    }

    fun checkId(id: String) {
        require(id.isNotBlank() && '/' !in id && id != "." && id != "..") {
            "Invalid document identity"
        }
    }

    // Firestore retains timestamp precision to microseconds.
    fun storedInstant(value: Instant): Instant =
        value.truncatedTo(ChronoUnit.MICROS)

    fun storedTime(value: FinancialTime): FinancialTime = FinancialTime(
        occurredAt = storedInstant(value.occurredAt),
        occurredLocalDate = value.occurredLocalDate,
        recordedTimeZone = value.recordedTimeZone,
    )

    private fun timestamp(value: Instant): Timestamp {
        val stored = storedInstant(value)
        return Timestamp(stored.epochSecond, stored.nano)
    }

    fun defaultProfileData(): Map<String, Any> = mapOf(
        "baseCurrency" to "LKR",
        "timeZone" to "Asia/Colombo",
        "weekStartsOn" to 1L,
        "onboardingCompleted" to false,
        "hideBalances" to false,
        "remindersEnabled" to true,
        "schemaVersion" to 1L,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    fun profile(id: String, data: FirestoreFields): UserProfile {
        checkId(id)
        data.checkKeys(
            profileRequired,
            setOf("displayName", "primaryGoalId", "preferredAccountId"),
        )
        require(data.text("baseCurrency") == "LKR")
        require(data.integer("schemaVersion") == 1L)

        val weekStartsOn = data.integer("weekStartsOn")
        require(weekStartsOn in 1L..7L)

        return UserProfile(
            id = id,
            displayName = data.optionalText("displayName"),
            timeZone = ZoneId.of(data.text("timeZone")),
            weekStartsOn = weekStartsOn.toInt(),
            primaryGoalId = data.optionalText("primaryGoalId"),
            preferredAccountId = data.optionalText("preferredAccountId"),
            onboardingCompleted = data.boolean("onboardingCompleted"),
            hideBalances = data.boolean("hideBalances"),
            remindersEnabled = data.boolean("remindersEnabled"),
            metadata = RecordMetadata(
                createdAt = data.instant("createdAt"),
                updatedAt = data.instant("updatedAt"),
            ),
        )
    }

    fun accountData(account: Account): Map<String, Any> {
        checkId(account.id)
        checkId(account.openingTransactionId)
        require(account.asset == FiatCurrency.LKR)
        require(account.type != AccountType.CRYPTO_WALLET)
        require(account.metadata == RecordMetadata()) {
            "New accounts cannot supply server metadata"
        }
        require(account.institutionLabel == null || account.institutionLabel.isNotBlank())

        return buildMap {
            put("name", account.name)
            put("type", account.type.name)
            put("currencyCode", "LKR")
            put("assetClass", "FIAT")
            put("scale", 2L)
            put("includeInEverydayPool", account.includeInEverydayPool)
            put("openingTransactionId", account.openingTransactionId)
            put("openedOn", account.openedOn.toString())
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
            put("revision", 0L)
            account.institutionLabel?.let { put("institutionLabel", it) }
        }
    }

    fun account(id: String, data: FirestoreFields): Account {
        checkId(id)
        data.checkKeys(
            accountRequired,
            setOf("institutionLabel", "archivedAt"),
        )
        require(data.text("currencyCode") == "LKR")
        require(data.text("assetClass") == "FIAT")
        require(data.integer("scale") == 2L)

        return Account(
            id = id,
            name = data.text("name"),
            type = AccountType.valueOf(data.text("type")),
            asset = FiatCurrency.LKR,
            includeInEverydayPool = data.boolean("includeInEverydayPool"),
            openingTransactionId = data.text("openingTransactionId").also(::checkId),
            openedOn = LocalDate.parse(data.text("openedOn")),
            institutionLabel = data.optionalText("institutionLabel"),
            metadata = RecordMetadata(
                createdAt = data.instant("createdAt"),
                updatedAt = data.instant("updatedAt"),
                revision = data.integer("revision"),
                archivedAt = data.optionalInstant("archivedAt"),
            ),
        )
    }

    fun eventData(event: FinancialTransaction): Map<String, Any> {
        checkId(event.id)
        require(event.kind in setOf(TransactionKind.OPENING, TransactionKind.EXPENSE, TransactionKind.INCOME))
        require(event.status == TransactionStatus.POSTED && event.revision == 0L)
        require(event.movements.size == 1)
        require(event.reservationEffects.isEmpty() && event.valuations.isEmpty())
        require(event.occurrenceId == null)
        require(event.originalTransactionId == null && event.goalPlanId == null)
        require(event.replacesTransactionId == null && event.cryptoTrade == null)
        require(event.merchantLabel == null || event.merchantLabel.length <= 1000)

        val movement = event.movements.single()
        checkId(movement.accountId)
        val amount = movement.amount as? NativeAmount.Fiat
            ?: error("Only fiat movements are supported by this adapter")
        require(amount.money.currency == FiatCurrency.LKR)

        val componentData = when (event.kind) {
            TransactionKind.INCOME -> {
                require(event.components.size == 1 && amount.money.minor > 0)
                require(event.merchantLabel == null)
                val component = event.components.single()
                require(component.nature == ComponentNature.EARNING)
                require(component.categoryId == null && component.commitmentClass == null)
                require(component.originalAmount == null && component.valuationRate == null && component.rateSnapshotId == null)
                val sourceId = requireNotNull(component.incomeSourceId).also(::checkId)
                val amountLkr = component.lkrAmount as? KnownLkrAmount.Known
                    ?: error("Manual income must have a known LKR amount")
                require(amountLkr.amount == amount.money)
                listOf(mapOf("nature" to component.nature.name, "amountLkrMinor" to amountLkr.amount.minor, "incomeSourceId" to sourceId))
            }
            else -> event.components.map { component ->
                require(component.nature == ComponentNature.SPENDING)
                require(component.originalAmount == null && component.valuationRate == null)
                require(component.rateSnapshotId == null && component.incomeSourceId == null)
                val amountLkr = component.lkrAmount as? KnownLkrAmount.Known
                    ?: error("Ordinary spending must have a known amount")
                val categoryId = requireNotNull(component.categoryId).also(::checkId)
                mapOf("nature" to component.nature.name, "amountLkrMinor" to amountLkr.amount.minor, "categoryId" to categoryId, "commitmentClass" to requireNotNull(component.commitmentClass).name)
            }
        }

        return buildMap {
            put("operationId", event.id)
            put("kind", event.kind.name)
            put("status", event.status.name)
            put("occurredAt", timestamp(event.financialTime.occurredAt))
            put("occurredLocalDate", event.financialTime.occurredLocalDate.toString())
            put("recordedTimeZone", event.financialTime.recordedTimeZone.id)
            put("clientCreatedAt", timestamp(event.clientCreatedAt))
            put("createdAt", FieldValue.serverTimestamp())
            put("schemaVersion", 1L)
            put("revision", 0L)
            put("accountIds", event.accountIds)
            put("categoryIds", event.categoryIds)
            put("goalIds", event.goalIds)
            put("incomeSourceIds", event.incomeSourceIds)
            put(
                "movements",
                listOf(
                    mapOf(
                        "accountId" to movement.accountId,
                        "currencyCode" to "LKR",
                        "assetClass" to "FIAT",
                        "amountMinor" to amount.money.minor,
                    ),
                ),
            )
            put("components", componentData)
            put("reservationEffects", emptyList<Any>())
            put("valuations", emptyList<Any>())
            event.expectedPaymentId?.let { put("expectedPaymentId", it) }
            event.note?.let { put("note", it) }
            event.merchantLabel?.let { put("merchantLabel", it) }
        }
    }

    fun event(
        id: String,
        data: FirestoreFields,
        pending: Boolean = false,
    ): FinancialTransaction {
        checkId(id)
        data.checkKeys(eventRequired, setOf("note", "merchantLabel", "expectedPaymentId"))
        require(data.text("operationId") == id)
        require(data.integer("schemaVersion") == 1L)
        require(data.integer("revision") == 0L)
        require(data.text("status") == "POSTED")

        val kind = TransactionKind.valueOf(data.text("kind"))
        require(kind in setOf(TransactionKind.OPENING, TransactionKind.EXPENSE, TransactionKind.INCOME)) {
            "Unsupported transaction kind: $kind"
        }
        require(data.list("reservationEffects").isEmpty())
        require(data.list("valuations").isEmpty())

        val movements = data.maps("movements").map { movement ->
            movement.checkKeys(
                setOf("accountId", "currencyCode", "assetClass", "amountMinor"),
            )
            require(movement.text("currencyCode") == "LKR")
            require(movement.text("assetClass") == "FIAT")

            AccountMovement(
                accountId = movement.text("accountId").also(::checkId),
                amount = NativeAmount.Fiat(Money.lkr(movement.integer("amountMinor"))),
            )
        }

        val components = data.maps("components").map { component ->
            val amountLkr = KnownLkrAmount.Known(Money.lkr(component.integer("amountLkrMinor")))
            if (kind == TransactionKind.INCOME) {
                component.checkKeys(setOf("nature", "amountLkrMinor", "incomeSourceId"))
                require(component.text("nature") == "EARNING")
                ReportComponent(ComponentNature.EARNING, amountLkr, incomeSourceId = component.text("incomeSourceId").also(::checkId))
            } else {
                component.checkKeys(setOf("nature", "amountLkrMinor", "categoryId", "commitmentClass"))
                require(component.text("nature") == "SPENDING")
                ReportComponent(ComponentNature.SPENDING, amountLkr, categoryId = component.text("categoryId").also(::checkId), commitmentClass = CommitmentClass.valueOf(component.text("commitmentClass")))
            }
        }

        val result = FinancialTransaction(
            operationId = id,
            kind = kind,
            status = TransactionStatus.POSTED,
            financialTime = FinancialTime(
                occurredAt = data.instant("occurredAt"),
                occurredLocalDate = LocalDate.parse(data.text("occurredLocalDate")),
                recordedTimeZone = ZoneId.of(data.text("recordedTimeZone")),
            ),
            clientCreatedAt = data.instant("clientCreatedAt"),
            movements = movements,
            components = components,
            expectedPaymentId = data.optionalText("expectedPaymentId")?.also(::checkId),
            note = data.optionalText("note"),
            merchantLabel = data.optionalText("merchantLabel"),
            createdAt = if (pending && data["createdAt"] == null) {
                null
            } else {
                data.instant("createdAt")
            },
        )

        require(result.movements.size == 1)
        if (kind == TransactionKind.INCOME) {
            val movement = result.movements.single().amount as NativeAmount.Fiat
            val component = result.components.single()
            require(movement.money.currency == FiatCurrency.LKR && movement.money.minor > 0)
            require(component.nature == ComponentNature.EARNING && (component.lkrAmount as KnownLkrAmount.Known).amount == movement.money)
            require(result.merchantLabel == null)
        }
        require(result.merchantLabel == null || result.merchantLabel.length <= 1000)
        require(result.accountIds == data.strings("accountIds"))
        require(result.categoryIds == data.strings("categoryIds"))
        require(result.goalIds == data.strings("goalIds"))
        require(result.incomeSourceIds == data.strings("incomeSourceIds"))

        return result
    }

    fun sameAccount(actual: FirestoreFields, expected: FirestoreFields): Boolean =
        actual.filterKeys { it !in setOf("createdAt", "updatedAt") } ==
            expected.filterKeys { it !in setOf("createdAt", "updatedAt") }

    fun sameEvent(actual: FirestoreFields, expected: FirestoreFields): Boolean =
        actual.filterKeys { it != "createdAt" } ==
            expected.filterKeys { it != "createdAt" }

    fun sameIncomeSource(actual: FirestoreFields, expected: FirestoreFields): Boolean =
        actual.filterKeys { it !in setOf("createdAt", "updatedAt") } ==
            expected.filterKeys { it !in setOf("createdAt", "updatedAt") }

    fun sameExpectedPayment(actual: FirestoreFields, expected: FirestoreFields): Boolean =
        actual.filterKeys { it !in setOf("createdAt", "updatedAt") } ==
            expected.filterKeys { it !in setOf("createdAt", "updatedAt") }

    private fun expectedPaymentCurrency(code: String): FiatCurrency {
        val currency = FiatCurrency(code)
        require(currency in setOf(FiatCurrency.LKR, FiatCurrency.USD)) {
            "Expected-payment persistence currently supports only LKR and USD"
        }
        return currency
    }

    private fun FirestoreFields.checkKeys(
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        require(keys.containsAll(required)) { "Missing required document fields" }
        require((keys - required - optional).isEmpty()) { "Unexpected document fields" }
    }

    private fun FirestoreFields.text(name: String): String {
        val value = this[name]
        require(value is String && value.isNotBlank()) { "Invalid text field: $name" }
        return value
    }

    private fun FirestoreFields.optionalText(name: String): String? {
        if (name !in this) return null
        val value = this[name]
        require(value is String) { "Invalid optional text field: $name" }
        return value
    }

    private fun FirestoreFields.integer(name: String): Long {
        val value = this[name]
        require(value is Long) { "Expected an integer field: $name" }
        return value
    }

    private fun FirestoreFields.boolean(name: String): Boolean {
        val value = this[name]
        require(value is Boolean) { "Invalid boolean field: $name" }
        return value
    }

    private fun FirestoreFields.instant(name: String): Instant {
        val value = this[name]
        require(value is Timestamp) { "Invalid timestamp field: $name" }
        return Instant.ofEpochSecond(value.seconds, value.nanoseconds.toLong())
    }

    private fun FirestoreFields.optionalInstant(name: String): Instant? =
        if (name in this) instant(name) else null

    private fun FirestoreFields.list(name: String): List<*> {
        val value = this[name]
        require(value is List<*>) { "Invalid list field: $name" }
        return value
    }

    private fun FirestoreFields.strings(name: String): List<String> =
        list(name).map {
            require(it is String)
            it
        }

    private fun FirestoreFields.maps(name: String): List<FirestoreFields> =
        list(name).map { value ->
            require(value is Map<*, *>)
            value.entries.associate { entry ->
                val key = entry.key
                require(key is String)
                key to entry.value
            }
        }
}
