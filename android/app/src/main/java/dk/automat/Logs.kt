package dk.automat

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

private fun now(): String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

/** salg.csv – én linje pr. betaling. Grundlag for afstemning mod MyShop og refundering. */
class SalesLog(private val file: File) {
    init {
        if (!file.exists()) file.writeText("tid;betaling;beloeb_oere;resultat;detaljer;klip\n")
    }

    @Synchronized
    fun add(key: String, amountOre: Long, result: String, detail: String, clip: String?) {
        val clean = { s: String -> s.replace(";", ",").replace("\n", " ") }
        file.appendText("${now()};${clean(key)};$amountOre;$result;${clean(detail)};${clip ?: ""}\n")
    }
}

/** notifikationer.log – rå MobilePay-notifikationer, til at sætte pakkenavn og regex rigtigt. */
class RawLog(private val file: File, private val maxBytes: Long = 1_000_000) {
    @Synchronized
    fun add(line: String) {
        if (file.length() > maxBytes) file.renameTo(File(file.path + ".1"))
        file.appendText("${now()} $line\n")
    }
}

/**
 * Push-besked til ejeren via ntfy.sh (gratis, ingen konto). Installer ntfy-appen og
 * abonnér på samme emne som `ntfyTopic` i config.json. Vælg et svært gætteligt emne.
 */
class Notifier(private val topic: String?) {
    private val lastSent = mutableMapOf<String, Long>()

    fun send(message: String) {
        if (topic == null) return
        synchronized(lastSent) {
            val now = System.currentTimeMillis()
            if (now - (lastSent[message] ?: 0) < 10 * 60_000) return
            lastSent[message] = now
        }
        thread(name = "ntfy") {
            try {
                val c = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.outputStream.use { it.write(message.toByteArray()) }
                c.responseCode
                c.disconnect()
            } catch (e: Exception) {
                Log.w("Notifier", "ntfy fejlede", e)
            }
        }
    }
}
