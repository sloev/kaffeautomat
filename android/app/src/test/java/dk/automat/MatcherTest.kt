package dk.automat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {
    private val kaffeA = Product("a", "Kaffe", 6000, slot = 0, capacity = 3)
    private val kaffeB = Product("b", "Kaffe", 6000, slot = 1, capacity = 3)
    private val te = Product("te", "Te", 4000, slot = 2, capacity = 3)
    private val all = listOf(kaffeA, kaffeB, te)

    private fun dispense(amount: Long, stock: Map<String, Int>) = match(amount, all, stock) as Match.Dispense

    @Test fun exactPrice() {
        assertEquals(listOf(Pick(te, 1)), dispense(4000, mapOf("te" to 1)).picks)
    }

    @Test fun samePriceIsInterchangeable() {
        assertEquals(listOf(Pick(kaffeB, 1)), dispense(6000, mapOf("a" to 0, "b" to 2)).picks)
    }

    @Test fun multiplesSpreadOverSlots() {
        assertEquals(listOf(Pick(kaffeA, 1), Pick(kaffeB, 2)), dispense(18000, mapOf("a" to 1, "b" to 3)).picks)
    }

    @Test fun ambiguousMultipleIsRejected() {
        // 12000 = 2 x kaffe = 3 x te
        assertTrue(match(12000, all, mapOf("a" to 3, "te" to 3)) is Match.Reject)
    }

    @Test fun wrongAmountIsRejected() {
        assertTrue(match(5000, all, mapOf("a" to 3, "te" to 3)) is Match.Reject)
        assertTrue(match(0, all, mapOf("a" to 3)) is Match.Reject)
    }

    @Test fun notEnoughStockIsRejected() {
        assertTrue(match(6000, all, mapOf("a" to 0, "b" to 0)) is Match.Reject)
        assertTrue(match(12000, listOf(kaffeA), mapOf("a" to 1)) is Match.Reject)
    }
}
