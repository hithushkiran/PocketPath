package com.pocketpath.domain

import com.pocketpath.domain.money.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class MoneyTest {
    @Test fun exactMinorUnitsAndPositiveEntryAreSeparate() {
        assertEquals(85000L, Money.parse("850", FiatCurrency.LKR).minor)
        assertEquals(10000L, Money.parse("100", FiatCurrency.USD).minor)
        assertEquals(-85000L, Money.parse("-850", FiatCurrency.LKR).minor)
        assertThrows(IllegalArgumentException::class.java) { EntryAmount(Money.lkr(-1)) }
        assertThrows(IllegalArgumentException::class.java) { EntryAmount(Money.lkr(0)) }
    }

    @Test fun enteredPrecisionIsRejectedEvenWhenExtraDigitsAreZero() {
        listOf("1.001", "1.000", "1e2", "1,000", "NaN", " 1").forEach {
            assertThrows(IllegalArgumentException::class.java) { Money.parse(it, FiatCurrency.LKR) }
        }
        assertThrows(IllegalArgumentException::class.java) { CryptoQuantity.parse("0.0001", CryptoAsset("ETH", 3)) }
    }

    @Test fun currenciesAndAssetScalesCannotBeMixed() {
        assertThrows(IllegalArgumentException::class.java) { Money.lkr(100) + Money(FiatCurrency.USD, 100) }
        assertThrows(IllegalArgumentException::class.java) {
            CryptoQuantity(CryptoAsset("ETH", 18), BigInteger.ONE) + CryptoQuantity(CryptoAsset("ETH", 8), BigInteger.ONE)
        }
    }

    @Test fun boundsAndLongOverflowAreChecked() {
        assertEquals(100_000_000_000L, Money.parse("1000000000", FiatCurrency.LKR).minor)
        assertThrows(IllegalArgumentException::class.java) { Money.parse("1000000000.01", FiatCurrency.LKR) }
        val scaledCurrency = FiatCurrency("XTS", 10)
        assertThrows(ArithmeticException::class.java) { Money(scaledCurrency, Long.MAX_VALUE) + Money(scaledCurrency, 1) }
        assertThrows(ArithmeticException::class.java) { Money.parse("9223372036.854775808", FiatCurrency("XTS", 9)) }
        assertThrows(IllegalArgumentException::class.java) { CryptoQuantity(CryptoAsset("ETH", 18), BigInteger.TEN.pow(78)) }
        assertThrows(IllegalArgumentException::class.java) { CryptoAsset("ETH", 19) }
    }

    @Test fun atomicQuantitiesAndRatesRemainExact() {
        assertEquals(BigInteger.ONE, CryptoQuantity.parse("0.000000000000000001", CryptoAsset("ETH", 18)).atomic)
        val rate = ValuationRate(BigDecimal("300.1234567890123456789"))
        assertEquals("300.1234567890123456789", rate.decimalString())
        assertEquals(rate, ValuationRate.fromDecimalString(rate.decimalString()))
        val value = historicalLkrValue(NativeAmount.Fiat(Money.parse("0.01", FiatCurrency.USD)), ValuationRate(BigDecimal("1.505")))
        assertEquals(2L, value.minor)
        assertEquals(-2L, historicalLkrValue(NativeAmount.Fiat(Money.parse("-0.01", FiatCurrency.USD)), ValuationRate(BigDecimal("1.505"))).minor)
    }

    @Test fun api26CompatibleCheckedConversionCoversBothLongBoundaries() {
        assertEquals(Long.MIN_VALUE, BigInteger.valueOf(Long.MIN_VALUE).toLongChecked())
        assertEquals(Long.MAX_VALUE, BigInteger.valueOf(Long.MAX_VALUE).toLongChecked())
        assertThrows(ArithmeticException::class.java) { (BigInteger.valueOf(Long.MIN_VALUE) - BigInteger.ONE).toLongChecked() }
        assertThrows(ArithmeticException::class.java) { (BigInteger.valueOf(Long.MAX_VALUE) + BigInteger.ONE).toLongChecked() }
    }
}
