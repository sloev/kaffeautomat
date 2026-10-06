package dk.kaffeautomat

/**
 * Læser beløbet ud af en MobilePay-notifikation.
 *
 * Pakkenavn og regex er konfiguration: tjek dem mod en rigtig notifikation i
 * log-tilstand, før automaten tages i brug. MobilePay kan ændre teksten.
 */
data class ParserConfig(
    val packageNames: Set<String>,
    // Første gruppe skal fange beløbet, f.eks. "60,00" eller "1.060,00".
    val amountRegex: Regex = Regex("""modtaget\s+([\d.]+,\d{2})\s*kr""", RegexOption.IGNORE_CASE),
)

data class Payment(val key: String, val amountOre: Long)

sealed interface ParseResult {
    data class Ok(val payment: Payment) : ParseResult
    /** Fra MobilePay, men teksten kunne ikke læses – automaten bør gå "ude af drift". */
    data class Unreadable(val text: String) : ParseResult
    /** Ikke fra MobilePay – ignoreres. */
    object NotMobilePay : ParseResult
}

class PaymentParser(private val config: ParserConfig) {

    fun parse(packageName: String, key: String, postTime: Long, title: String?, text: String?): ParseResult {
        if (packageName !in config.packageNames) return ParseResult.NotMobilePay
        val full = listOfNotNull(title, text).joinToString(" ")
        val match = config.amountRegex.find(full) ?: return ParseResult.Unreadable(full)
        val amountOre = toOre(match.groupValues[1]) ?: return ParseResult.Unreadable(full)
        return ParseResult.Ok(Payment("$key|$postTime|$amountOre|${full.hashCode()}", amountOre))
    }

    /** "1.060,50" -> 106050 */
    private fun toOre(s: String): Long? {
        val parts = s.replace(".", "").split(",")
        if (parts.size != 2) return null
        val kr = parts[0].toLongOrNull() ?: return null
        val ore = parts[1].toLongOrNull() ?: return null
        return kr * 100 + ore
    }
}

/** Hvor mange poser et beløb giver: kun hvis det går lige op og der er nok på lager. */
fun bagsFor(amountOre: Long, priceOre: Long, stock: Int): Int? {
    if (amountOre <= 0 || amountOre % priceOre != 0L) return null
    val n = amountOre / priceOre
    return if (n <= stock) n.toInt() else null
}
