package dk.automat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Starter automaten når telefonen er tændt, og når appen er opdateret.
 * Skærmen kommer normalt af sig selv, fordi appen er startskærm (HOME); dette er
 * reserven, så betalinger og heartbeat kører, selv hvis den ikke er.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Machine.start(context)
        Machine.report("boot", "action" to intent.action)
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                // Android afviste – Machine prøver igen fra sin vagthund.
            }
        }
    }
}
