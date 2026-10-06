package dk.automat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LedgerTest {
    @Test fun remembersAcrossRestart() {
        val f = File.createTempFile("ledger", ".txt").apply { delete() }
        val l = Ledger(f)
        assertTrue(l.markIfNew("x"))
        assertFalse(l.markIfNew("x"))
        assertFalse(Ledger(f).markIfNew("x"))
        assertTrue(Ledger(f).markIfNew("y"))
    }

    @Test fun forgetsOldestBeyondLimit() {
        val f = File.createTempFile("ledger", ".txt").apply { delete() }
        val l = Ledger(f, keep = 2)
        l.markIfNew("a"); l.markIfNew("b"); l.markIfNew("c")
        val reloaded = Ledger(f, keep = 2)
        assertTrue(reloaded.markIfNew("a"))
        assertFalse(Ledger(f, keep = 2).markIfNew("c"))
    }
}
