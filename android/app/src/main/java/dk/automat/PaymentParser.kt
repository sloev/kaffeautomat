package dk.automat

/**
 * Læser beløbet ud af en MobilePay-notifikation.
 *
 * Pakkenavn og regex kommer fra config.json: tjek dem mod en rigtig notifikation
 * (se notifikationer.log), før automaten tages i brug. MobilePay kan ændre teksten.
 */
data class ParserConfig(
    val packageNames: Set<String>,
    /** Første gruppe skal fange beløbet, f.eks. "60,00" eller "1.060,00". */
    val amountRegex: Regex = DEFAULT_REGEX,
) {
    companion object {
        val DEFAULT_REGEX = Regex("""modtaget\s+([\d.]+,\d{2})\s*kr""", RegexOption.IGNORE_CASE)
    }
}

data class Payment(val key: String, val amountOre: Long)

sealed interface ParseResult {
    data class Ok(val payment: Payment) : ParseResult
    /** Fra MobilePay, men intet beløb – f.eks. reklame eller ændret tekstformat. */
    data class Unreadable(val text: String) : ParseResult
    /** Ikke fra MobilePay – ignoreres. */
    object NotMobilePay : ParseResult
}

class PaymentParser(private val config: ParserConfig) {

    /**
     * [key] og [whenMs] er notifikationens nøgle og dens eget tidsstempel (Notification.when).
     * De er stabile, når Android genposter/opdaterer samme notifikation, så samme betaling
     * får samme nøgle – men to betalinger får forskellige.
     */
    fun parse(packageName: String, key: String, whenMs: Long, title: String?, text: String?): ParseResult {
        if (packageName !in config.packageNames) return ParseResult.NotMobilePay
        val full = listOfNotNull(title, text).joinToString(" ")
        val match = config.amountRegex.find(full) ?: return ParseResult.Unreadable(full)
        val amountOre = toOre(match.groupValues[1]) ?: return ParseResult.Unreadable(full)
        return ParseResult.Ok(Payment("$key|$whenMs|$amountOre|${full.hashCode()}", amountOre))
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
