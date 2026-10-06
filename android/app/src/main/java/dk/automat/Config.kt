package dk.automat

import org.json.JSONObject

/**
 * Alt der gør én automat forskellig fra en anden står i config.json:
 * varer, priser, tekster, statusvindue, kamera og forbindelse til motorstyringen.
 * Se docs/design.md for en beskrivelse af hvert felt.
 */
data class AppConfig(
    val name: String,
    val parser: ParserConfig,
    val logAllNotifications: Boolean,
    val products: List<Product>,
    val link: LinkConfig,
    val display: DisplayConfig,
    val camera: CameraConfig,
    val ntfyTopic: String?,
    val texts: Texts,
) {
    companion object {
        fun parse(json: String): AppConfig {
            val o = JSONObject(json)

            val pay = o.optJSONObject("payment") ?: JSONObject()
            val pkgs = pay.optJSONArray("packages")
            val parser = ParserConfig(
                packageNames = (0 until (pkgs?.length() ?: 0)).map { pkgs!!.getString(it) }.toSet(),
                amountRegex = pay.optString("amountRegex").takeIf { it.isNotBlank() }
                    ?.let { Regex(it, RegexOption.IGNORE_CASE) } ?: ParserConfig.DEFAULT_REGEX,
            )

            val arr = o.optJSONArray("products") ?: throw IllegalArgumentException("products mangler")
            val products = (0 until arr.length()).map {
                val p = arr.getJSONObject(it)
                Product(
                    id = p.getString("id"),
                    name = p.getString("name"),
                    priceOre = p.getLong("priceOre"),
                    slot = p.optInt("slot", 0),
                    capacity = p.optInt("capacity", 1),
                )
            }
            require(products.isNotEmpty()) { "products er tom" }
            require(products.all { it.priceOre > 0 }) { "priceOre skal være > 0" }
            require(products.map { it.id }.toSet().size == products.size) { "product id'er skal være unikke" }

            val l = o.optJSONObject("link") ?: JSONObject()
            val d = o.optJSONObject("display") ?: JSONObject()
            val c = o.optJSONObject("camera") ?: JSONObject()
            val t = o.optJSONObject("texts") ?: JSONObject()
            val def = Texts()

            return AppConfig(
                name = o.optString("name", "Automat"),
                parser = parser,
                logAllNotifications = pay.optBoolean("logAllNotifications", true),
                products = products,
                link = LinkConfig(
                    type = l.optString("type", "usb"),
                    baud = l.optInt("baud", 9600),
                    dispenseTimeoutMs = l.optLong("dispenseTimeoutMs", 15_000),
                ),
                display = DisplayConfig(
                    x = d.optDouble("x", 0.1).toFloat(),
                    y = d.optDouble("y", 0.05).toFloat(),
                    width = d.optDouble("width", 0.8).toFloat(),
                    height = d.optDouble("height", 0.3).toFloat(),
                    textSizeSp = d.optDouble("textSizeSp", 20.0).toFloat(),
                    brightness = d.optDouble("brightness", 0.4).toFloat(),
                    qrPayload = d.optString("qrPayload").takeIf { it.isNotBlank() },
                ),
                camera = CameraConfig(
                    enabled = c.optBoolean("enabled", true),
                    front = c.optString("lens", "front") != "back",
                    tailMs = c.optLong("tailMs", 20_000),
                    retentionDays = c.optInt("retentionDays", 30),
                ),
                ntfyTopic = o.optString("ntfyTopic").takeIf { it.isNotBlank() },
                texts = Texts(
                    ready = t.optString("ready", def.ready),
                    busy = t.optString("busy", def.busy),
                    pickUp = t.optString("pickUp", def.pickUp),
                    soldOut = t.optString("soldOut", def.soldOut),
                    wrongAmount = t.optString("wrongAmount", def.wrongAmount),
                    failed = t.optString("failed", def.failed),
                    outOfService = t.optString("outOfService", def.outOfService),
                ),
            )
        }
    }
}

/** En vare. Varer med samme pris regnes for ens og tages fra den første søjle med lager. */
data class Product(
    val id: String,
    val name: String,
    val priceOre: Long,
    /** Motorstyringens søjle-nummer (DISPENSE <slot>). */
    val slot: Int,
    /** Antal ved fuld genopfyldning. */
    val capacity: Int,
)

data class LinkConfig(val type: String, val baud: Int, val dispenseTimeoutMs: Long)

/** Statusvinduet: position og størrelse som brøkdel (0–1) af skærmen. Resten er sort. */
data class DisplayConfig(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val textSizeSp: Float,
    val brightness: Float,
    val qrPayload: String?,
)

data class CameraConfig(val enabled: Boolean, val front: Boolean, val tailMs: Long, val retentionDays: Int)

data class Texts(
    val ready: String = "Betal med MobilePay",
    val busy: String = "Tak! Din vare er på vej",
    val pickUp: String = "Tag din vare i skuffen",
    val soldOut: String = "Udsolgt",
    val wrongAmount: String = "Beløbet passer ikke – du får pengene retur",
    val failed: String = "Der skete en fejl – du får pengene retur",
    val outOfService: String = "Ude af drift",
)

/** 6000 -> "60 kr", 6050 -> "60,50 kr" */
fun formatKr(ore: Long): String =
    if (ore % 100 == 0L) "${ore / 100} kr" else "${ore / 100},${"%02d".format(ore % 100)} kr"
