package dk.automat

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

/**
 * Tekstlinje-forbindelse til motorstyringen (Arduino/ESP32). Protokol: docs/design.md.
 * Kaldes kun fra én tråd ad gangen (Machine holder en lås).
 */
interface DeviceLink {
    val connected: Boolean
    fun connect(): Boolean
    /** Sender én kommando og returnerer svarlinjen, eller null ved timeout/fejl. */
    fun command(line: String, timeoutMs: Long): String?
    fun close() {}
}

/** Til test uden hardware: alle udleveringer lykkes efter 1,5 s. */
class FakeLink : DeviceLink {
    override val connected = true
    override fun connect() = true
    override fun command(line: String, timeoutMs: Long): String = when {
        line == "PING" -> "PONG"
        line.startsWith("DISPENSE") -> { Thread.sleep(1500); "OK" }
        else -> "OK"
    }
}

class UsbSerialLink(private val context: Context, private val baud: Int) : DeviceLink {
    private var port: UsbSerialPort? = null
    private val buffer = StringBuilder()
    private var lastPermissionRequest = 0L

    override val connected get() = port?.isOpen == true

    override fun connect(): Boolean {
        close()
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull() ?: return false
        if (!manager.hasPermission(driver.device)) {
            // Android spørger brugeren én gang. Vælg "Brug altid" – så sker det automatisk derefter.
            val now = System.currentTimeMillis()
            if (now - lastPermissionRequest < 60_000) return false
            lastPermissionRequest = now
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            manager.requestPermission(
                driver.device,
                PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE),
            )
            return false
        }
        val connection = manager.openDevice(driver.device) ?: return false
        return try {
            val p = driver.ports[0]
            p.open(connection)
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            p.dtr = true
            port = p
            buffer.clear()
            // De fleste Arduinoer genstarter, når porten åbnes. Vent på "READY".
            readLine(3000)
            true
        } catch (e: IOException) {
            Log.w(TAG, "USB open fejlede", e)
            close()
            false
        }
    }

    override fun command(line: String, timeoutMs: Long): String? {
        val p = port ?: return null
        return try {
            p.write((line + "\n").toByteArray(), 1000)
            val deadline = System.currentTimeMillis() + timeoutMs
            var reply: String?
            do {
                val left = deadline - System.currentTimeMillis()
                reply = if (left > 0) readLine(left) else null
            } while (reply != null && (reply.isEmpty() || reply == "READY"))
            reply
        } catch (e: IOException) {
            Log.w(TAG, "USB fejl", e)
            close()
            null
        }
    }

    private fun readLine(timeoutMs: Long): String? {
        val p = port ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        val bytes = ByteArray(64)
        while (System.currentTimeMillis() < deadline) {
            val nl = buffer.indexOf("\n")
            if (nl >= 0) {
                val line = buffer.substring(0, nl).trim()
                buffer.delete(0, nl + 1)
                return line
            }
            val n = p.read(bytes, 200)
            if (n > 0) buffer.append(String(bytes, 0, n))
        }
        return null
    }

    override fun close() {
        try { port?.close() } catch (_: IOException) {}
        port = null
    }

    companion object {
        private const val TAG = "UsbSerialLink"
        const val ACTION_USB_PERMISSION = "dk.automat.USB_PERMISSION"
    }
}
