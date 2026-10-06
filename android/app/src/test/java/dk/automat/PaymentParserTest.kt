package dk.automat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentParserTest {
    private val pkg = "mobilepay.test"
    private val parser = PaymentParser(ParserConfig(packageNames = setOf(pkg)))

    @Test fun readsAmount() {
        val r = parser.parse(pkg, "k", 1L, "MobilePay", "Du har modtaget 60,00 kr. fra Anna")
        assertEquals(6000L, (r as ParseResult.Ok).payment.amountOre)
    }

    @Test fun readsThousands() {
        val r = parser.parse(pkg, "k", 1L, null, "Du har modtaget 1.060,50 kr.")
        assertEquals(106050L, (r as ParseResult.Ok).payment.amountOre)
    }

    @Test fun ignoresOtherApps() {
        assertTrue(parser.parse("other.app", "k", 1L, null, "Du har modtaget 60,00 kr.") is ParseResult.NotMobilePay)
    }

    @Test fun flagsUnreadable() {
        assertTrue(parser.parse(pkg, "k", 1L, null, "Ny besked") is ParseResult.Unreadable)
    }

    @Test fun sameNotificationSameKey_differentPaymentsDifferentKeys() {
        val a = parser.parse(pkg, "k", 1L, null, "Du har modtaget 60,00 kr.") as ParseResult.Ok
        val again = parser.parse(pkg, "k", 1L, null, "Du har modtaget 60,00 kr.") as ParseResult.Ok
        val later = parser.parse(pkg, "k", 2L, null, "Du har modtaget 60,00 kr.") as ParseResult.Ok
        assertEquals(a.payment.key, again.payment.key)
        assertNotEquals(a.payment.key, later.payment.key)
    }
}
