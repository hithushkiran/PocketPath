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
        require(event.kind in setOf(TransactionKind.OPENING, TransactionKind.EXPENSE))
        require(event.status == TransactionStatus.POSTED && event.revision == 0L)
        require(event.movements.size == 1)
        require(event.reservationEffects.isEmpty() && event.valuations.isEmpty())
        require(event.occurrenceId == null && event.expectedPaymentId == null)
        require(event.originalTransactionId == null && event.goalPlanId == null)
        require(event.replacesTransactionId == null && event.cryptoTrade == null)
        require(event.merchantLabel == null || event.merchantLabel.length <= 1000)

        val movement = event.movements.single()
        checkId(movement.accountId)
        val amount = movement.amount as? NativeAmount.Fiat
            ?: error("Only fiat movements are supported by this adapter")
        require(amount.money.currency == FiatCurrency.LKR)

        val componentData = event.components.map { component ->
            require(component.nature == ComponentNature.SPENDING)
            require(component.originalAmount == null && component.valuationRate == null)
            require(component.rateSnapshotId == null && component.incomeSourceId == null)

            val amountLkr = component.lkrAmount as? KnownLkrAmount.Known
                ?: error("Ordinary spending must have a known amount")
            val categoryId = requireNotNull(component.categoryId).also(::checkId)

            mapOf(
                "nature" to component.nature.name,
                "amountLkrMinor" to amountLkr.amount.minor,
                "categoryId" to categoryId,
                "commitmentClass" to requireNotNull(component.commitmentClass).name,
            )
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
        data.checkKeys(eventRequired, setOf("note", "merchantLabel"))
        require(data.text("operationId") == id)
        require(data.integer("schemaVersion") == 1L)
        require(data.integer("revision") == 0L)
        require(data.text("status") == "POSTED")

        val kind = TransactionKind.valueOf(data.text("kind"))
        require(kind in setOf(TransactionKind.OPENING, TransactionKind.EXPENSE)) {
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
            component.checkKeys(
                setOf("nature", "amountLkrMinor", "categoryId", "commitmentClass"),
            )
            require(component.text("nature") == "SPENDING")

            ReportComponent(
                nature = ComponentNature.SPENDING,
                lkrAmount = KnownLkrAmount.Known(
                    Money.lkr(component.integer("amountLkrMinor")),
                ),
                categoryId = component.text("categoryId").also(::checkId),
                commitmentClass = CommitmentClass.valueOf(
                    component.text("commitmentClass"),
                ),
            )
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
            note = data.optionalText("note"),
            merchantLabel = data.optionalText("merchantLabel"),
            createdAt = if (pending && data["createdAt"] == null) {
                null
            } else {
                data.instant("createdAt")
            },
        )

        require(result.movements.size == 1)
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
