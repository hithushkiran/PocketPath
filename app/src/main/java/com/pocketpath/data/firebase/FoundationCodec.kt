package com.pocketpath.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.pocketpath.domain.model.*
import com.pocketpath.domain.calculation.manualCryptoAssets
import com.pocketpath.domain.calculation.requireManualCryptoQuantity
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
        "state", "receivedAmountMinor", "createdAt", "updatedAt", "revision",
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
            setOf("milestoneLabel", "clientLabel", "cancelledAt", "notes", "archivedAt", "cancellationOperationId", "lastReceiptOperationId"),
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
            receivedAmountMinor = data.integer("receivedAmountMinor"),
            lastReceiptOperationId = data.optionalText("lastReceiptOperationId")?.also(::checkId),
            metadata = RecordMetadata(
                createdAt = if (pending && data["createdAt"] == null) null else data.instant("createdAt"),
                updatedAt = if (pending && data["updatedAt"] == null) null else data.instant("updatedAt"),
                revision = data.integer("revision"),
                archivedAt = data.optionalInstant("archivedAt"),
            ),
        )
    }

    private val budgetRequired = setOf(
        "scope", "periodType", "limitLkrMinor", "effectiveFrom",
        "rolloverEnabled", "state", "createdAt", "updatedAt", "revision",
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
        require(account.asset == FiatCurrency.LKR || account.asset in manualCryptoAssets)
        require(account.metadata == RecordMetadata()) {
            "New accounts cannot supply server metadata"
        }
        require(account.institutionLabel == null || account.institutionLabel.isNotBlank())

        return buildMap {
            put("name", account.name)
            put("type", account.type.name)
            put("currencyCode", account.asset.code)
            put("assetClass", if (account.asset is CryptoAsset) "CRYPTO" else "FIAT")
            put("scale", account.asset.scale.toLong())
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
            setOf("institutionLabel", "archivedAt", "lastGoalReserveId"),
        )
        val asset = if (data.text("assetClass") == "CRYPTO") {
            cryptoAsset(data)
        } else {
            require(data.text("currencyCode") == "LKR" && data.text("assetClass") == "FIAT" && data.integer("scale") == 2L)
            FiatCurrency.LKR
        }

        return Account(
            id = id,
            name = data.text("name"),
            type = AccountType.valueOf(data.text("type")),
            asset = asset,
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

    fun goalData(goal: Goal): Map<String, Any> {
        checkId(goal.id)
        require(goal.state == GoalState.ACTIVE && goal.metadata == RecordMetadata())
        require(goal.name.length <= 80)
        val first = requireNotNull(goal.firstContributionDate)
        require(goal.startDate.year in 0..9999 && goal.deadline.year in 0..9999)
        return mapOf(
            "name" to goal.name,
            "targetLkrMinor" to goal.target.money.minor,
            "startDate" to goal.startDate.toString(),
            "deadline" to goal.deadline.toString(),
            "firstContributionDate" to first.toString(),
            "state" to goal.state.name,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "revision" to 0L,
        )
    }

    fun goal(id: String, data: FirestoreFields): Goal {
        checkId(id)
        data.checkKeys(setOf(
            "name", "targetLkrMinor", "startDate", "deadline", "firstContributionDate",
            "state", "createdAt", "updatedAt", "revision",
        ))
        require(data.text("state") == GoalState.ACTIVE.name && data.integer("revision") == 0L)
        val name = data.text("name")
        require(name.length <= 80)
        return Goal(
            id = id,
            name = name,
            target = EntryAmount(Money.lkr(data.integer("targetLkrMinor"))),
            startDate = LocalDate.parse(data.text("startDate")),
            deadline = LocalDate.parse(data.text("deadline")),
            metadata = RecordMetadata(data.instant("createdAt"), data.instant("updatedAt")),
            firstContributionDate = LocalDate.parse(data.text("firstContributionDate")),
        )
    }

    private val incomeComponentKeys = setOf("nature", "amountLkrMinor", "incomeSourceId")
    private val convertedUsdKeys = setOf("originalCurrency", "originalAmountMinor", "valuationRate", "rateSource")

    private fun cryptoAsset(data: FirestoreFields): CryptoAsset = manualCryptoAssets.single {
        it.code == data.text("currencyCode") && it.scale.toLong() == data.integer("scale")
    }

    private fun cryptoQuantity(asset: CryptoAsset, atomic: String): CryptoQuantity {
        require(atomic.matches(Regex("0|-?[1-9][0-9]{0,11}")))
        return CryptoQuantity(asset, atomic.toBigInteger())
    }

    fun cryptoPosition(id: String, data: FirestoreFields): CryptoPosition {
        checkId(id)
        data.checkKeys(setOf("currencyCode", "scale", "quantityAtomic", "costLkrMinor",
            "lastTransactionId", "version", "costMethod", "lastTradeAt", "updatedAt"))
        require(data.text("costMethod") == CostMethod.MOVING_AVERAGE_V1.name)
        val position = CryptoPosition(
            id, cryptoQuantity(cryptoAsset(data), data.text("quantityAtomic")),
            KnownLkrAmount.Known(Money.lkr(data.integer("costLkrMinor"))),
            data.text("lastTransactionId").also(::checkId), data.integer("version"),
        )
        require(position.version > 0L)
        data.instant("lastTradeAt")
        data.instant("updatedAt")
        return position
    }

    fun cryptoPositionData(position: CryptoPosition, occurredAt: Instant): Map<String, Any> {
        requireManualCryptoQuantity(position.quantity)
        return mapOf(
            "currencyCode" to position.quantity.asset.code,
            "scale" to position.quantity.asset.scale.toLong(),
            "quantityAtomic" to position.quantity.atomic.toString(),
            "costLkrMinor" to (position.cost as KnownLkrAmount.Known).amount.minor,
            "lastTransactionId" to position.lastTransactionId,
            "version" to position.version,
            "costMethod" to position.costMethod.name,
            "lastTradeAt" to timestamp(occurredAt),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
    }

    private val supportedEventKinds = setOf(
        TransactionKind.OPENING, TransactionKind.EXPENSE, TransactionKind.INCOME, TransactionKind.GOAL_RESERVE,
        TransactionKind.CRYPTO_BUY, TransactionKind.CRYPTO_SELL,
    )

    fun eventData(event: FinancialTransaction): Map<String, Any> {
        checkId(event.id)
        require(event.kind in supportedEventKinds)
        require(event.status == TransactionStatus.POSTED && event.revision == 0L)
        require(event.voidMetadata == null)
        val isReserve = event.kind == TransactionKind.GOAL_RESERVE
        val trade = event.cryptoTrade
        require(event.movements.size == when {
            isReserve -> 0
            trade != null -> 2
            else -> 1
        })
        require(event.reservationEffects.size == if (isReserve) 1 else 0)
        require(event.valuations.isEmpty())
        if (isReserve) {
            require(event.merchantLabel == null && event.replacesTransactionId == null)
            val effect = event.reservationEffects.single()
            checkId(effect.goalId)
            checkId(effect.backingAccountId)
        }
        event.occurrenceId?.let {
            checkId(it)
            require(event.kind == TransactionKind.EXPENSE)
        }
        require(event.originalTransactionId == null && event.goalPlanId == null)
        require(trade == null || event.merchantLabel == null && event.replacesTransactionId == null)
        require(event.merchantLabel == null || event.merchantLabel.length <= 1000)
        event.replacesTransactionId?.let { checkId(it) }

        val movementData = event.movements.map { movement ->
            checkId(movement.accountId)
            when (val amount = movement.amount) {
                is NativeAmount.Fiat -> {
                    require(amount.money.currency == FiatCurrency.LKR)
                    mapOf("accountId" to movement.accountId, "currencyCode" to "LKR",
                        "assetClass" to "FIAT", "amountMinor" to amount.money.minor)
                }
                is NativeAmount.Crypto -> {
                    require(trade != null || event.kind == TransactionKind.OPENING && amount.quantity.atomic.signum() == 0)
                    requireManualCryptoQuantity(amount.quantity)
                    mapOf("accountId" to movement.accountId, "currencyCode" to amount.asset.code,
                        "assetClass" to "CRYPTO", "scale" to amount.asset.scale.toLong(),
                        "quantityAtomic" to amount.quantity.atomic.toString())
                }
            }
        }

        val componentData = when (event.kind) {
            TransactionKind.INCOME -> {
                val amount = event.movements.single().amount as NativeAmount.Fiat
                require(event.components.size == 1 && amount.money.minor > 0)
                require(event.merchantLabel == null)
                val component = event.components.single()
                require(component.nature == ComponentNature.EARNING)
                require(component.categoryId == null && component.commitmentClass == null)
                require(component.rateSnapshotId == null)
                val sourceId = requireNotNull(component.incomeSourceId).also(::checkId)
                val amountLkr = component.lkrAmount as? KnownLkrAmount.Known
                    ?: error("Manual income must have a known LKR amount")
                require(amountLkr.amount == amount.money)
                listOf(buildMap<String, Any> {
                    put("nature", component.nature.name)
                    put("amountLkrMinor", amountLkr.amount.minor)
                    put("incomeSourceId", sourceId)
                    component.originalAmount?.let { original ->
                        require(event.expectedPaymentId == null)
                        require(original is NativeAmount.Fiat && original.money.currency == FiatCurrency.USD)
                        require(original.money.minor > 0)
                        put("originalCurrency", FiatCurrency.USD.code)
                        put("originalAmountMinor", original.money.minor)
                        put("valuationRate", usdReceiptRateText(requireNotNull(component.valuationRate)))
                        put("rateSource", ValuationSource.MANUAL_ACTUAL.name)
                    }
                })
            }
            else -> event.components.map { component ->
                if (component.nature == ComponentNature.REALIZED_CRYPTO) {
                    require(event.kind == TransactionKind.CRYPTO_SELL)
                    return@map mapOf("nature" to component.nature.name,
                        "amountLkrMinor" to (component.lkrAmount as KnownLkrAmount.Known).amount.minor)
                }
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
            put("movements", movementData)
            put("components", componentData)
            put("reservationEffects", event.reservationEffects.map { effect ->
                mapOf(
                    "goalId" to effect.goalId,
                    "backingAccountId" to effect.backingAccountId,
                    "amountLkrMinor" to effect.amountLkr.minor,
                )
            })
            put("valuations", emptyList<Any>())
            trade?.let {
                val gross = (it.grossQuoteAmount as NativeAmount.Fiat).money
                require(gross.currency == FiatCurrency.LKR && it.proceedsOrAcquisitionLkr == gross)
                require(it.fees.size <= 1)
                val fee = it.fees.singleOrNull()
                require(fee == null || (fee.accountId == it.quoteAccountId && fee.amount == NativeAmount.Fiat(fee.lkrAmount)))
                requireManualCryptoQuantity(it.quantity)
                put("cryptoTrade", buildMap<String, Any> {
                    put("assetAccountId", it.assetAccountId)
                    put("quoteAccountId", it.quoteAccountId)
                    put("quantityAtomic", it.quantity.atomic.toString())
                    put("grossQuoteMinor", gross.minor)
                    put("feeLkrMinor", fee?.lkrAmount?.minor ?: 0L)
                    put("costMethod", it.costMethod.name)
                    it.allocatedCost?.let { cost ->
                        put("allocatedCostLkrMinor", (cost as KnownLkrAmount.Known).amount.minor)
                    }
                    it.realizedPnl?.let { pnl ->
                        put("realizedPnlLkrMinor", (pnl as KnownLkrAmount.Known).amount.minor)
                    }
                })
            }
            event.occurrenceId?.let { put("occurrenceId", it) }
            event.expectedPaymentId?.let { put("expectedPaymentId", it) }
            event.note?.let { put("note", it) }
            event.merchantLabel?.let { put("merchantLabel", it) }
            event.replacesTransactionId?.let { put("replacesTransactionId", it) }
            event.voidMetadata?.let { voidMeta ->
                put(
                    "voidMetadata",
                    mapOf(
                        "voidedAt" to timestamp(voidMeta.voidedAt),
                        "voidReason" to voidMeta.voidReason,
                    ),
                )
            }
        }
    }

    fun event(
        id: String,
        data: FirestoreFields,
        pending: Boolean = false,
    ): FinancialTransaction {
        checkId(id)
        data.checkKeys(eventRequired, setOf("note", "merchantLabel", "occurrenceId", "expectedPaymentId", "voidMetadata", "updatedAt", "replacesTransactionId", "cryptoTrade"))
        require(data.text("operationId") == id)
        require(data.integer("schemaVersion") == 1L)
        val revision = data.integer("revision")

        val status = TransactionStatus.valueOf(data.text("status"))
        require(status in setOf(TransactionStatus.POSTED, TransactionStatus.VOIDED))

        val voidMetaMap = data.optionalMap("voidMetadata")
        val voidMetadata = if (status == TransactionStatus.VOIDED) {
            require(data.text("kind") == "EXPENSE" && revision == 1L)
            data.instant("updatedAt")
            requireNotNull(voidMetaMap) { "Voided transactions require voidMetadata" }
            voidMetaMap.checkKeys(setOf("voidedAt", "voidReason"))
            val voidedAt = voidMetaMap.instant("voidedAt")
            val voidReason = voidMetaMap.text("voidReason")
            require(voidReason.isNotBlank() && voidReason.length <= 1000)
            VoidMetadata(voidedAt, voidReason)
        } else {
            require(revision == 0L && "updatedAt" !in data)
            require(voidMetaMap == null) { "Posted transactions must not have voidMetadata" }
            null
        }

        val replacesTransactionId = data.optionalText("replacesTransactionId")?.also(::checkId)

        val kind = TransactionKind.valueOf(data.text("kind"))
        require(kind in supportedEventKinds) {
            "Unsupported transaction kind: $kind"
        }
        val effects = data.maps("reservationEffects").map { effect ->
            effect.checkKeys(setOf("goalId", "backingAccountId", "amountLkrMinor"))
            ReservationEffect(
                effect.text("goalId").also(::checkId),
                effect.text("backingAccountId").also(::checkId),
                Money.lkr(effect.integer("amountLkrMinor")),
            )
        }
        val isReserve = kind == TransactionKind.GOAL_RESERVE
        require(effects.size == if (isReserve) 1 else 0)
        require(data.list("valuations").isEmpty())

        val movements = data.maps("movements").map { movement ->
            val amount = if (movement.text("assetClass") == "CRYPTO") {
                movement.checkKeys(setOf("accountId", "currencyCode", "assetClass", "scale", "quantityAtomic"))
                val quantity = cryptoQuantity(cryptoAsset(movement), movement.text("quantityAtomic"))
                require(kind == TransactionKind.CRYPTO_BUY || kind == TransactionKind.CRYPTO_SELL ||
                    kind == TransactionKind.OPENING && quantity.atomic.signum() == 0)
                NativeAmount.Crypto(quantity)
            } else {
                movement.checkKeys(setOf("accountId", "currencyCode", "assetClass", "amountMinor"))
                require(movement.text("currencyCode") == "LKR" && movement.text("assetClass") == "FIAT")
                NativeAmount.Fiat(Money.lkr(movement.integer("amountMinor")))
            }
            AccountMovement(movement.text("accountId").also(::checkId), amount)
        }

        val trade = data.optionalMap("cryptoTrade")?.let { fields ->
            val required = setOf("assetAccountId", "quoteAccountId", "quantityAtomic", "grossQuoteMinor", "feeLkrMinor", "costMethod")
            val sell = kind == TransactionKind.CRYPTO_SELL
            require(sell || kind == TransactionKind.CRYPTO_BUY)
            fields.checkKeys(if (sell) required + setOf("allocatedCostLkrMinor", "realizedPnlLkrMinor") else required)
            require(fields.text("costMethod") == CostMethod.MOVING_AVERAGE_V1.name)
            val wallet = fields.text("assetAccountId").also(::checkId)
            val quote = fields.text("quoteAccountId").also(::checkId)
            val asset = (movements.single { it.accountId == wallet }.amount as NativeAmount.Crypto).quantity.asset
            val gross = EntryAmount(Money.lkr(fields.integer("grossQuoteMinor"))).money
            val fee = Money.lkr(fields.integer("feeLkrMinor"))
            require(fee.minor >= 0L && (!sell || fee.minor < gross.minor))
            CryptoTrade(
                wallet, quote, cryptoQuantity(asset, fields.text("quantityAtomic")), NativeAmount.Fiat(gross),
                if (fee.minor == 0L) emptyList() else listOf(TradeFee(quote, NativeAmount.Fiat(fee), fee)), gross,
                if (sell) KnownLkrAmount.Known(Money.lkr(fields.integer("allocatedCostLkrMinor"))) else null,
                if (sell) KnownLkrAmount.Known(Money.lkr(fields.integer("realizedPnlLkrMinor"))) else null,
            )
        }

        val components = data.maps("components").map { component ->
            val amountLkr = KnownLkrAmount.Known(Money.lkr(component.integer("amountLkrMinor")))
            if (kind == TransactionKind.INCOME) {
                component.checkKeys(incomeComponentKeys, convertedUsdKeys)
                require(component.text("nature") == "EARNING")
                val hasConversion = component.keys.any { it in convertedUsdKeys }
                val original = if (hasConversion) {
                    require("expectedPaymentId" !in data)
                    require(component.text("originalCurrency") == FiatCurrency.USD.code)
                    require(component.text("rateSource") == ValuationSource.MANUAL_ACTUAL.name)
                    NativeAmount.Fiat(EntryAmount(Money(FiatCurrency.USD, component.integer("originalAmountMinor"))).money)
                } else null
                val rate = if (hasConversion) {
                    val stored = component.text("valuationRate")
                    parseUsdReceiptRate(stored).also { require(usdReceiptRateText(it) == stored) }
                } else null
                ReportComponent(
                    ComponentNature.EARNING, amountLkr,
                    incomeSourceId = component.text("incomeSourceId").also(::checkId),
                    originalAmount = original, valuationRate = rate,
                )
            } else if (kind == TransactionKind.CRYPTO_SELL && component.text("nature") == "REALIZED_CRYPTO") {
                component.checkKeys(setOf("nature", "amountLkrMinor"))
                ReportComponent(ComponentNature.REALIZED_CRYPTO, amountLkr)
            } else {
                component.checkKeys(setOf("nature", "amountLkrMinor", "categoryId", "commitmentClass"))
                require(component.text("nature") == "SPENDING")
                ReportComponent(ComponentNature.SPENDING, amountLkr, categoryId = component.text("categoryId").also(::checkId), commitmentClass = CommitmentClass.valueOf(component.text("commitmentClass")))
            }
        }

        val result = FinancialTransaction(
            operationId = id,
            kind = kind,
            status = status,
            financialTime = FinancialTime(
                occurredAt = data.instant("occurredAt"),
                occurredLocalDate = LocalDate.parse(data.text("occurredLocalDate")),
                recordedTimeZone = ZoneId.of(data.text("recordedTimeZone")),
            ),
            clientCreatedAt = data.instant("clientCreatedAt"),
            movements = movements,
            components = components,
            reservationEffects = effects,
            occurrenceId = data.optionalText("occurrenceId")?.also(::checkId)?.also {
                require(kind == TransactionKind.EXPENSE)
            },
            expectedPaymentId = data.optionalText("expectedPaymentId")?.also(::checkId),
            note = data.optionalText("note"),
            merchantLabel = data.optionalText("merchantLabel"),
            replacesTransactionId = replacesTransactionId,
            cryptoTrade = trade,
            voidMetadata = voidMetadata,
            revision = revision,
            createdAt = if (pending && data["createdAt"] == null) {
                null
            } else {
                data.instant("createdAt")
            },
        )

        require(result.movements.size == when {
            isReserve -> 0
            trade != null -> 2
            else -> 1
        })
        if (isReserve) require(result.merchantLabel == null && result.replacesTransactionId == null)
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

    /** Update only lifecycle fields; creation and financial payload remain immutable. */
    fun expenseVoidData(event: FinancialTransaction, reason: String, voidedAt: Instant): Map<String, Any> {
        require(event.kind == TransactionKind.EXPENSE && event.status == TransactionStatus.POSTED)
        require(event.revision == 0L)
        require(reason.isNotBlank() && reason.length <= 1000)
        return mapOf(
            "status" to TransactionStatus.VOIDED.name,
            "voidMetadata" to mapOf("voidedAt" to timestamp(voidedAt), "voidReason" to reason.trim()),
            "updatedAt" to FieldValue.serverTimestamp(),
            "revision" to event.revision + 1L,
        )
    }

    /** The first fixed monthly LKR bill and its first due occurrence share one immutable save. */
    fun firstMonthlyBillData(schedule: Schedule): Pair<Map<String, Any>, Map<String, Any>> {
        checkId(schedule.id)
        val categoryId = requireNotNull(schedule.categoryId).also(::checkId)
        val date = schedule.recurrence.anchorDate
        require(schedule.kind == ScheduleKind.COMMITMENT && schedule.recurrence.frequency == Frequency.MONTHLY)
        require(schedule.activeFrom == date && date.year in 0..9999 && schedule.endDate == null)
        require(schedule.title.isNotBlank() && schedule.title.length <= 80)
        require(schedule.expectedAmount.money.currency == FiatCurrency.LKR && schedule.amountMode == AmountMode.FIXED)
        require(schedule.timeZone.id == "Asia/Colombo" && schedule.commitmentClass == CommitmentClass.COMMITTED)
        require(schedule.metadata == RecordMetadata() && !schedule.paused)
        require(schedule.preferredAccountId == null && schedule.remindLocalTime == null && schedule.reminderLeadDays == 0)

        val shared = mapOf(
            "kind" to ScheduleKind.COMMITMENT.name,
            "timeZone" to schedule.timeZone.id,
            "expectedAmountMinor" to schedule.expectedAmount.money.minor,
            "currencyCode" to FiatCurrency.LKR.code,
            "categoryId" to categoryId,
            "commitmentClass" to CommitmentClass.COMMITTED.name,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "revision" to 0L,
        )
        val scheduleData = shared + mapOf(
            "title" to schedule.title,
            "frequency" to Frequency.MONTHLY.name,
            "interval" to 1L,
            "anchorDate" to date.toString(),
            "activeFrom" to date.toString(),
            "amountMode" to AmountMode.FIXED.name,
            "paused" to false,
            "reminderLeadDays" to 0L,
        )
        return scheduleData to monthlyBillOccurrenceData(Occurrence(
            scheduleId = schedule.id,
            nominalDate = date,
            effectiveDueDate = date,
            kind = ScheduleKind.COMMITMENT,
            titleSnapshot = schedule.title,
            expectedAmount = schedule.expectedAmount,
            timeZone = schedule.timeZone,
            categoryId = categoryId,
            commitmentClass = CommitmentClass.COMMITTED,
        ))
    }

    fun monthlyBillOccurrenceData(bill: Occurrence): Map<String, Any> = mapOf(
        "scheduleId" to bill.scheduleId,
        "nominalDate" to bill.nominalDate.toString(),
        "effectiveDueDate" to bill.effectiveDueDate.toString(),
        "kind" to bill.kind.name,
        "titleSnapshot" to bill.titleSnapshot,
        "expectedAmountMinor" to bill.expectedAmount.money.minor,
        "currencyCode" to FiatCurrency.LKR.code,
        "timeZone" to bill.timeZone.id,
        "categoryId" to requireNotNull(bill.categoryId),
        "commitmentClass" to CommitmentClass.COMMITTED.name,
        "state" to OccurrenceState.OPEN.name,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
        "revision" to 0L,
    )

    fun monthlyBill(id: String, data: FirestoreFields): MonthlyBill = MonthlyBill(
        billOccurrence(id, data),
        if ("paidAmountMinor" in data) data.integer("paidAmountMinor") else 0L,
    )

    private fun billOccurrence(id: String, data: FirestoreFields): Occurrence {
        data.checkKeys(setOf(
            "scheduleId", "nominalDate", "effectiveDueDate", "kind", "titleSnapshot",
            "expectedAmountMinor", "currencyCode", "timeZone", "categoryId", "commitmentClass",
            "state", "createdAt", "updatedAt", "revision",
        ), setOf("paidAmountMinor", "lastBillExpenseId"))
        val scheduleId = data.text("scheduleId").also(::checkId)
        val nominal = LocalDate.parse(data.text("nominalDate"))
        require(id == StableIds.occurrence(scheduleId, nominal))
        require(nominal.year in 0..9999 && data.text("effectiveDueDate") == nominal.toString())
        require(data.text("kind") == ScheduleKind.COMMITMENT.name && data.text("state") == OccurrenceState.OPEN.name)
        require(data.text("currencyCode") == FiatCurrency.LKR.code && data.text("timeZone") == "Asia/Colombo")
        require(data.text("commitmentClass") == CommitmentClass.COMMITTED.name)
        val revision = data.integer("revision")
        if (revision == 0L) {
            require("paidAmountMinor" !in data && "lastBillExpenseId" !in data)
        } else {
            require(revision > 0 && "paidAmountMinor" in data)
            checkId(data.text("lastBillExpenseId"))
        }
        val title = data.text("titleSnapshot")
        require(title.isNotBlank() && title.length <= 80)
        return Occurrence(
            scheduleId = scheduleId,
            nominalDate = nominal,
            effectiveDueDate = nominal,
            kind = ScheduleKind.COMMITMENT,
            titleSnapshot = title,
            expectedAmount = EntryAmount(Money.lkr(data.integer("expectedAmountMinor"))),
            timeZone = ZoneId.of(data.text("timeZone")),
            categoryId = data.text("categoryId").also(::checkId),
            commitmentClass = CommitmentClass.COMMITTED,
            metadata = RecordMetadata(data.instant("createdAt"), data.instant("updatedAt"), revision),
        )
    }

    fun budgetData(budget: Budget): Map<String, Any> {
        checkId(budget.id)
        require(budget.metadata == RecordMetadata() && budget.state == BudgetState.ACTIVE)
        require(budget.limit.money.currency == FiatCurrency.LKR)
        require(budget.limit.money.minor > 0L)
        require(!budget.rolloverEnabled)
        if (budget.scope == BudgetScope.CATEGORY_DISCRETIONARY) {
            require(!budget.categoryId.isNullOrBlank()) {
                "Category discretionary budget requires a category ID"
            }
            checkId(budget.categoryId)
        } else {
            require(budget.categoryId == null)
        }

        return buildMap {
            put("scope", budget.scope.name)
            put("periodType", budget.periodType.name)
            put("limitLkrMinor", budget.limit.money.minor)
            put("effectiveFrom", budget.effectiveFrom.toString())
            put("rolloverEnabled", false)
            put("state", budget.state.name)
            put("createdAt", FieldValue.serverTimestamp())
            put("updatedAt", FieldValue.serverTimestamp())
            put("revision", budget.metadata.revision)
            budget.effectiveTo?.let { put("effectiveTo", it.toString()) }
            budget.categoryId?.let { put("categoryId", it) }
        }
    }

    fun budget(id: String, data: FirestoreFields): Budget {
        checkId(id)
        data.checkKeys(
            budgetRequired,
            setOf("effectiveTo", "categoryId"),
        )
        val limitMinor = data.integer("limitLkrMinor")
        require(limitMinor > 0L)

        val scope = BudgetScope.valueOf(data.text("scope"))
        val periodType = BudgetPeriod.valueOf(data.text("periodType"))
        val categoryId = data.optionalText("categoryId")
        if (scope == BudgetScope.CATEGORY_DISCRETIONARY) {
            require(!categoryId.isNullOrBlank())
            checkId(categoryId)
        } else {
            require(categoryId == null)
        }

        val effectiveFrom = LocalDate.parse(data.text("effectiveFrom"))
        val effectiveTo = data.optionalText("effectiveTo")?.let(LocalDate::parse)

        return Budget(
            id = id,
            scope = scope,
            periodType = periodType,
            limit = EntryAmount(Money.lkr(limitMinor)),
            effectiveFrom = effectiveFrom,
            effectiveTo = effectiveTo,
            categoryId = categoryId,
            rolloverEnabled = data.boolean("rolloverEnabled"),
            state = BudgetState.valueOf(data.text("state")),
            metadata = RecordMetadata(
                createdAt = data.instant("createdAt"),
                updatedAt = data.instant("updatedAt"),
                revision = data.integer("revision"),
            ),
        )
    }

    fun sameAccount(actual: FirestoreFields, expected: FirestoreFields): Boolean {
        val mutableFields = setOf("createdAt", "updatedAt", "revision", "lastGoalReserveId")
        return actual.filterKeys { it !in mutableFields } == expected.filterKeys { it !in mutableFields }
    }

    fun sameEvent(actual: FirestoreFields, expected: FirestoreFields): Boolean =
        actual.filterKeys { it != "createdAt" } ==
            expected.filterKeys { it != "createdAt" }

    fun sameIncomeSource(actual: FirestoreFields, expected: FirestoreFields): Boolean =
        actual.filterKeys { it !in setOf("createdAt", "updatedAt") } ==
            expected.filterKeys { it !in setOf("createdAt", "updatedAt") }

    /** Compare original creation intent; later receipts/cancellation must not invalidate replay. */
    fun sameExpectedPayment(actual: FirestoreFields, expected: FirestoreFields): Boolean {
        val creationFields = setOf(
            "incomeSourceId", "projectLabel", "currencyCode", "amountDueMinor", "dueDate",
            "milestoneLabel", "clientLabel", "notes",
        )
        return actual.filterKeys { it in creationFields } ==
            expected.filterKeys { it in creationFields }
    }

    /** Only the server audit timestamp is outside retained receipt command identity. */
    fun sameExpectedPaymentReceipt(actual: FirestoreFields, expected: FirestoreFields): Boolean =
        actual.filterKeys { it != "createdAt" } == expected.filterKeys { it != "createdAt" }

    private fun expectedPaymentCurrency(code: String): FiatCurrency {
        val currency = FiatCurrency(code)
        require(currency in setOf(FiatCurrency.LKR, FiatCurrency.USD)) {
            "Expected-payment persistence currently supports only LKR and USD"
        }
        return currency
    }
    fun sameCategory(actual: FirestoreFields, expected: FirestoreFields): Boolean =
        actual.filterKeys { it !in setOf("createdAt", "updatedAt") } ==
            expected.filterKeys { it !in setOf("createdAt", "updatedAt") }

    fun sameBudget(actual: FirestoreFields, expected: FirestoreFields): Boolean =
        actual.filterKeys { it !in setOf("createdAt", "updatedAt") } ==
            expected.filterKeys { it !in setOf("createdAt", "updatedAt") }

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

    private fun FirestoreFields.optionalInteger(name: String): Long? {
        if (name !in this) return null
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

    private fun FirestoreFields.optionalMap(name: String): FirestoreFields? {
        if (name !in this || this[name] == null) return null
        val value = this[name]
        require(value is Map<*, *>) { "Expected a map field: $name" }
        return value.entries.associate { entry ->
            val key = entry.key
            require(key is String)
            key to entry.value
        }
    }
}
