package dk.automat

data class Pick(val product: Product, val count: Int)

sealed interface Match {
    data class Dispense(val picks: List<Pick>) : Match {
        val total get() = picks.sumOf { it.count }
    }
    data class Reject(val reason: String) : Match
}

/**
 * Hvilke varer giver et beløb?
 *
 * 1. Beløbet er præcis prisen på en vare med lager → én af den.
 * 2. Ellers: beløbet er et helt antal gange præcis én pris → så mange af den pris.
 * 3. Ellers afvises betalingen (logges til refundering).
 *
 * Varer med samme pris er ombyttelige; der tages fra søjlerne i den rækkefølge
 * de står i config.json.
 */
fun match(amountOre: Long, products: List<Product>, stock: Map<String, Int>): Match {
    if (amountOre <= 0) return Match.Reject("beløb <= 0")
    fun left(p: Product) = stock[p.id] ?: 0

    val prices = products.map { it.priceOre }.distinct()
    val price = when {
        amountOre in prices -> amountOre
        else -> prices.filter { amountOre % it == 0L }.singleOrNull()
            ?: return Match.Reject("beløbet passer ikke til en pris")
    }
    var need = (amountOre / price).toInt()

    val sameprice = products.filter { it.priceOre == price }
    if (sameprice.sumOf { left(it) } < need) return Match.Reject("ikke nok på lager")

    val picks = mutableListOf<Pick>()
    for (p in sameprice) {
        if (need == 0) break
        val take = minOf(need, left(p))
        if (take > 0) {
            picks += Pick(p, take)
            need -= take
        }
    }
    return Match.Dispense(picks)
}
