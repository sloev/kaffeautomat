package dk.automat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hændelser (salg, fejl, opstart…) der venter på at blive sendt til serveren.
 * Gemmes i en fil, så intet går tabt hvis WiFi er nede eller appen genstarter.
 */
class Outbox(private val file: File, private val maxEvents: Int = 1000) {

    @Synchronized
    fun add(event: JSONObject) {
        file.appendText(event.toString() + "\n")
        val lines = file.readLines()
        if (lines.size > maxEvents) file.writeText(lines.takeLast(maxEvents).joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun peek(max: Int): List<JSONObject> =
        if (!file.exists()) emptyList()
        else file.readLines().filter { it.isNotBlank() }.take(max).map { JSONObject(it) }

    /** Fjerner de [n] ældste – kaldes når serveren har modtaget dem. */
    @Synchronized
    fun drop(n: Int) {
        if (n <= 0 || !file.exists()) return
        val rest = file.readLines().filter { it.isNotBlank() }.drop(n)
        file.writeText(if (rest.isEmpty()) "" else rest.joinToString("\n", postfix = "\n"))
    }
}

/** En kommando fra serveren. Leveres højst én gang (serveren sletter den, når den er sendt). */
data class ServerCommand(val id: String, val cmd: String, val args: JSONObject)

fun parseCommands(response: JSONObject?): List<ServerCommand> {
    val arr = response?.optJSONArray("commands") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val cmd = o.optString("cmd").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ServerCommand(o.optString("id"), cmd, o)
    }
}

/**
 * Heartbeat: POST {url}/heartbeat med status + ventende hændelser, hvert `heartbeatSec`.
 * Svaret kan indeholde kommandoer. Telefonen kalder kun ud – ingen åbne porte på stedet.
 * Protokollen står i docs/design.md.
 */
class ServerClient(private val cfg: ServerConfig, private val outbox: Outbox) {

    /** Returnerer serverens svar, eller null hvis den ikke kunne nås. */
    fun heartbeat(status: JSONObject): JSONObject? {
        val events = outbox.peek(100)
        status.put("events", JSONArray(events))
        var c: HttpURLConnection? = null
        return try {
            c = URL("${cfg.url}/heartbeat").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            c.outputStream.use { it.write(status.toString().toByteArray()) }
            if (c.responseCode !in 200..299) return null
            outbox.drop(events.size)
            val text = c.inputStream.bufferedReader().readText()
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }
}
