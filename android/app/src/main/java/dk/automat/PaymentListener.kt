package dk.automat

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Får kopi af alle notifikationer på telefonen. Kræver at brugeren har givet
 * "Notifikationsadgang" i Androids indstillinger (appen sender dig derhen).
 */
class PaymentListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Machine.start(this)
        Machine.setListenerConnected(true)
    }

    override fun onListenerDisconnected() {
        Machine.setListenerConnected(false)
        // Bed Android om at koble os på igen, f.eks. efter at processen blev dræbt.
        requestRebind(ComponentName(this, PaymentListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Machine.start(this)
        val n = sbn.notification
        val extras = n.extras
        // Titel og tekst er CharSequence (ofte formateret), ikke String. Lang tekst ligger i bigText.
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        val isSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        Machine.onNotification(sbn.packageName, sbn.key, n.`when`, title, text, isSummary)
    }
}
