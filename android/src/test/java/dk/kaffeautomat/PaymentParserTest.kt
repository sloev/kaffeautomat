package dk.kaffeautomat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun bags() {
        assertEquals(1, bagsFor(6000, 6000, 5))
        assertEquals(2, bagsFor(12000, 6000, 5))
        assertNull(bagsFor(5000, 6000, 5))   // forkert beløb
        assertNull(bagsFor(12000, 6000, 1))  // ikke nok på lager
        assertNull(bagsFor(0, 6000, 5))
    }
}
