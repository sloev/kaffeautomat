package dk.automat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigTest {
    @Test fun bundledConfigParses() {
        val c = AppConfig.parse(File("src/main/assets/config.json").readText())
        assertEquals("Kaffeautomaten", c.name)
        assertEquals(6000L, c.products.single().priceOre)
        assertTrue(c.parser.amountRegex.containsMatchIn("Du har modtaget 60,00 kr."))
    }

    @Test fun examplesParse() {
        File("../examples").listFiles { f -> f.name.endsWith(".json") }!!.forEach {
            AppConfig.parse(it.readText())
        }
    }

    @Test fun defaults() {
        val c = AppConfig.parse("""{"products":[{"id":"x","name":"X","priceOre":1000}]}""")
        assertEquals("usb", c.link.type)
        assertEquals("Udsolgt", c.texts.soldOut)
        assertEquals(null, c.display.qrPayload)
    }

    @Test fun formatsKroner() {
        assertEquals("60 kr", formatKr(6000))
        assertEquals("60,50 kr", formatKr(6050))
        assertEquals("0,05 kr", formatKr(5))
    }
}
