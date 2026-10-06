package dk.kaffeautomat

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Får kopi af alle notifikationer på telefonen (kræver "Notifikationsadgang").
 * Betalinger fra MobilePay sendes videre til [Dispenser], som står for kø,
 * deduplikering, lager og kommunikation med Arduinoen.
 */
class PaymentListener : NotificationListenerService() {

    private val parser by lazy { PaymentParser(Config.parser) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()

        if (Config.logMode) {
            // Log-tilstand: gem alt rå, så pakkenavn og regex kan sættes korrekt.
            Log.i(TAG, "pkg=${sbn.packageName} key=${sbn.key} title=$title text=$text")
        }

        when (val r = parser.parse(sbn.packageName, sbn.key, sbn.postTime, title, text)) {
            is ParseResult.Ok -> Dispenser.onPayment(applicationContext, r.payment)
            is ParseResult.Unreadable -> Dispenser.onUnreadable(applicationContext, r.text)
            ParseResult.NotMobilePay -> Unit
        }
    }

    companion object {
        private const val TAG = "Kaffeautomat"
    }
}

/**
 * Udfyldes i den rigtige app. Pakkenavnet skal bekræftes med log-tilstand
 * på telefonen, der kører MobilePay MyShop.
 */
object Config {
    var logMode = true
    var priceOre = 6000L
    var parser = ParserConfig(packageNames = setOf("UDFYLD.MED.MOBILEPAY.PAKKENAVN"))
}

/**
 * Skitse – implementeres i appen:
 *  - onPayment: slå payment.key op i en lokal database (Room/SharedPreferences);
 *    hvis set før: ignorer. Ellers gem, beregn bagsFor(...), og læg i kø.
 *  - Kø-arbejder: send "DISPENSE\n" over USB-serial (usb-serial-for-android),
 *    vent på "OK" / "ERR ...", tæl lager ned, opdater skærmen.
 *  - Forkert beløb / ERR: log til manuel refundering og giv ejeren besked (ntfy.sh).
 *  - onUnreadable: gå "ude af drift" og giv ejeren besked.
 */
object Dispenser {
    fun onPayment(context: android.content.Context, payment: Payment) {
        TODO("Se kommentar ovenfor og docs/betaling.md")
    }

    fun onUnreadable(context: android.content.Context, text: String) {
        TODO("Se kommentar ovenfor og docs/betaling.md")
    }
}
