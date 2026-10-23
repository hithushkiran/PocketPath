package com.pocketpath.domain.money

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** NFR-18 caps individual fiat records, not the sum of an account's history. */
const val MAX_FIAT_MAJOR: Long = 1_000_000_000

/** BigInteger.longValueExact is API 31 on Android; range-check explicitly for API 26. */
fun BigInteger.toLongChecked(): Long {
    if (this < BigInteger.valueOf(Long.MIN_VALUE) || this > BigInteger.valueOf(Long.MAX_VALUE)) {
        throw ArithmeticException("Long overflow")
    }
    return toLong()
}

sealed interface Asset {
    val code: String
    val scale: Int
}

data class FiatCurrency(override val code: String, override val scale: Int = 2) : Asset {
    init {
        require(code.matches(Regex("[A-Z]{3}"))) { "Use an ISO-style currency code" }
        require(scale in 0..18)
        require(code !in setOf("LKR", "USD") || scale == 2)
    }
    companion object {
        val LKR = FiatCurrency("LKR")
        val USD = FiatCurrency("USD")
    }
}

data class CryptoAsset(override val code: String, override val scale: Int) : Asset {
    init {
        require(code.isNotBlank())
        require(scale in 0..18)
    }
}

private fun enteredDecimal(text: String, scale: Int): BigDecimal {
    require(text.matches(Regex("[+-]?[0-9]+(\\.[0-9]+)?"))) { "Enter a plain decimal without grouping or exponent" }
    val value = BigDecimal(text)
    // Even trailing zeroes beyond the asset scale are rejected, never silently truncated.
    require(value.scale() <= scale) { "Excessive entered precision" }
    return value
}

/** Signed bounded fiat value. EntryAmount supplies the separate strictly-positive input contract. */
data class Money(val currency: FiatCurrency, val minor: Long) {
    init {
        val bound = BigInteger.valueOf(MAX_FIAT_MAJOR).multiply(BigInteger.TEN.pow(currency.scale))
        require(BigInteger.valueOf(minor).abs() <= bound) { "Fiat amount exceeds SRS bound" }
    }
    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Currency mismatch" }
        return Money(currency, Math.addExact(minor, other.minor))
    }
    operator fun unaryMinus() = Money(currency, Math.negateExact(minor))
    fun major(): BigDecimal = BigDecimal.valueOf(minor, currency.scale)
    companion object {
        fun parse(text: String, currency: FiatCurrency): Money =
            Money(currency, enteredDecimal(text, currency.scale).movePointRight(currency.scale).longValueExact())
        fun lkr(minor: Long) = Money(FiatCurrency.LKR, minor)
    }
}

data class EntryAmount(val money: Money) {
    init { require(money.minor > 0) { "Entry amount must be positive" } }
}

data class CryptoQuantity(val asset: CryptoAsset, val atomic: BigInteger) {
    init { require(atomic.abs().toString().length <= 78) { "Crypto quantities are bounded to 78 digits" } }
    operator fun plus(other: CryptoQuantity): CryptoQuantity {
        require(asset == other.asset) { "Asset or scale mismatch" }
        return CryptoQuantity(asset, atomic + other.atomic)
    }
    fun major(): BigDecimal = BigDecimal(atomic, asset.scale)
    companion object {
        fun parse(text: String, asset: CryptoAsset) =
            CryptoQuantity(asset, enteredDecimal(text, asset.scale).movePointRight(asset.scale).toBigIntegerExact())
    }
}

/** A movement has exactly one representation; impossible to populate fiat and crypto units together. */
sealed interface NativeAmount {
    val asset: Asset
    fun atomicUnits(): BigInteger
    data class Fiat(val money: Money) : NativeAmount {
        override val asset get() = money.currency
        override fun atomicUnits(): BigInteger = BigInteger.valueOf(money.minor)
    }
    data class Crypto(val quantity: CryptoQuantity) : NativeAmount {
        override val asset get() = quantity.asset
        override fun atomicUnits(): BigInteger = quantity.atomic
    }
    fun major(): BigDecimal = BigDecimal(atomicUnits(), asset.scale)
}

data class ValuationRate(val lkrPerMajor: BigDecimal) {
    init { require(lkrPerMajor.signum() > 0) }
    /** Exact eventual DecimalString boundary; never a floating-point JSON/Firestore number. */
    fun decimalString(): String = lkrPerMajor.toPlainString()
    companion object { fun fromDecimalString(value: String) = ValuationRate(BigDecimal(value)) }
}

/** BR-11: round HALF_UP only once, after multiplication and conversion to LKR minor units. */
fun historicalLkrValue(amount: NativeAmount, rate: ValuationRate): Money = Money.lkr(
    amount.major().multiply(rate.lkrPerMajor).movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP).longValueExact(),
)

sealed interface KnownLkrAmount {
    data class Known(val amount: Money) : KnownLkrAmount {
        init { require(amount.currency == FiatCurrency.LKR) }
    }
    data class Unknown(val reason: String) : KnownLkrAmount {
        init { require(reason.isNotBlank()) }
    }
}
