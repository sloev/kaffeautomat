package dk.automat

import java.io.File

/**
 * Husker hvilke betalinger der allerede er behandlet, også efter genstart,
 * så samme notifikation aldrig udleverer to gange.
 */
class Ledger(private val file: File, private val keep: Int = 2000) {
    private val keys = LinkedHashSet<String>()

    init {
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.forEach { keys += it }
    }

    /** true hvis nøglen er ny (og gemmes nu), false hvis den er set før. */
    @Synchronized
    fun markIfNew(key: String): Boolean {
        if (key in keys) return false
        keys += key
        if (keys.size > keep) {
            val oldest = keys.iterator()
            repeat(keys.size - keep) { oldest.next(); oldest.remove() }
            file.writeText(keys.joinToString("\n", postfix = "\n"))
        } else {
            file.appendText(key + "\n")
        }
        return true
    }
}
