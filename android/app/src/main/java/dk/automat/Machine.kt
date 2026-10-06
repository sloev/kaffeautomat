package dk.automat

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class Mood { NORMAL, GOOD, WARN, ERROR }

/** Det statusvinduet skal vise lige nu. */
data class Screen(val title: String, val lines: List<String>, val showQr: Boolean, val mood: Mood)

/**
 * Automatens hjerne: modtager betalinger, finder varer, kører motorstyringen,
 * holder lager, logger salg, optager klip og bestemmer hvad skærmen viser.
 */
object Machine {
    private const val TAG = "Automat"

    lateinit var config: AppConfig
        private set
    private var configError: String? = null

    private lateinit var app: Context
    private lateinit var files: File
    private lateinit var ledger: Ledger
    private lateinit var sales: SalesLog
    private lateinit var raw: RawLog
    private lateinit var notifier: Notifier
    private lateinit var stockPrefs: SharedPreferences
    private lateinit var link: DeviceLink
    @Volatile var recorder: ClipRecorder? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Payment>(Channel.UNLIMITED)
    private val linkLock = Mutex()

    @Volatile private var started = false
    @Volatile private var online = false
    @Volatile private var linkOk = false
    @Volatile private var listenerOk = false
    /** Fejl der kræver at ejeren åbner automaten (f.eks. fastkørt søjle). Nulstilles i admin-menuen. */
    @Volatile private var fault: String? = null
    @Volatile private var message: Screen? = null
    @Volatile private var messageUntil = 0L

    private val _screen = MutableStateFlow(Screen("Automat", listOf("Starter…"), false, Mood.NORMAL))
    val screen: StateFlow<Screen> = _screen

    @Synchronized
    fun start(context: Context) {
        if (started) return
        app = context.applicationContext
        files = app.getExternalFilesDir(null) ?: app.filesDir
        loadConfig()
        ledger = Ledger(File(files, "betalinger.txt"))
        sales = SalesLog(File(files, "salg.csv"))
        raw = RawLog(File(files, "notifikationer.log"))
        stockPrefs = app.getSharedPreferences("lager", Context.MODE_PRIVATE)
        watchNetwork()
        started = true
        scope.launch {
            for (p in queue) {
                try {
                    handle(p)
                } catch (e: Exception) {
                    Log.e(TAG, "Fejl ved betaling ${p.key}", e)
                    sales.add(p.key, p.amountOre, "REFUNDER", "intern fejl: $e", null)
                    recorder?.stopClipLater()
                }
            }
        }
        scope.launch { watchdog() }
        refresh()
    }

    /** config.json ligger i appens mappe på telefonen, så den kan rettes via USB uden ny app. */
    private fun loadConfig() {
        val file = File(files, "config.json")
        if (!file.exists()) app.assets.open("config.json").use { input -> file.outputStream().use { input.copyTo(it) } }
        config = try {
            AppConfig.parse(file.readText()).also { configError = null }
        } catch (e: Exception) {
            Log.e(TAG, "config.json fejl", e)
            configError = e.message ?: e.javaClass.simpleName
            AppConfig.parse(app.assets.open("config.json").bufferedReader().readText())
        }
        notifier = Notifier(config.ntfyTopic)
        link = if (config.link.type == "fake") FakeLink() else UsbSerialLink(app, config.link.baud)
    }

    fun reloadConfig() = scope.launch {
        linkLock.withLock {
            link.close()
            loadConfig()
            linkOk = false
        }
        refresh()
    }

    // ---- Betalinger ---------------------------------------------------------------------

    /** Kaldes af [PaymentListener] for hver notifikation på telefonen. */
    fun onNotification(pkg: String, key: String, whenMs: Long, title: String?, text: String?, isGroupSummary: Boolean) {
        if (!started) return
        if (config.logAllNotifications) raw.add("pkg=$pkg key=$key when=$whenMs summary=$isGroupSummary title=$title text=$text")
        if (isGroupSummary) return
        when (val r = PaymentParser(config.parser).parse(pkg, key, whenMs, title, text)) {
            is ParseResult.Ok ->
                if (ledger.markIfNew(r.payment.key)) queue.trySend(r.payment)
                else Log.i(TAG, "Allerede behandlet: ${r.payment.key}")
            is ParseResult.Unreadable -> {
                raw.add("ULÆSELIG pkg=$pkg text=${r.text}")
                notifier.send("${config.name}: MobilePay-notifikation uden beløb – tjek notifikationer.log")
            }
            ParseResult.NotMobilePay -> Unit
        }
    }

    /** Fra admin-menuen: kør hele kæden uden MobilePay. */
    fun simulatePayment(amountOre: Long) {
        queue.trySend(Payment("sim-${System.currentTimeMillis()}", amountOre))
    }

    private suspend fun handle(p: Payment) {
        val clip = recorder?.startClip(Integer.toHexString(p.key.hashCode()))
        val t = config.texts
        when (val m = match(p.amountOre, config.products, stock())) {
            is Match.Reject -> {
                show(Screen(config.name, listOf(t.wrongAmount), false, Mood.ERROR), 30_000)
                sales.add(p.key, p.amountOre, "REFUNDER", m.reason, clip)
                notifier.send("${config.name}: ${formatKr(p.amountOre)} afvist (${m.reason}) – refundér i MyShop")
            }
            is Match.Dispense -> {
                show(Screen(config.name, listOf(t.busy), false, Mood.GOOD), config.link.dispenseTimeoutMs * m.total + 5_000)
                var done = 0
                var error: String? = null
                for (pick in m.picks) {
                    repeat(pick.count) {
                        if (error != null) return@repeat
                        val reply = linkLock.withLock {
                            link.command("DISPENSE ${pick.product.slot}", config.link.dispenseTimeoutMs)
                        }
                        if (reply == "OK") {
                            setStock(pick.product, stockOf(pick.product) - 1)
                            done++
                        } else {
                            error = "søjle ${pick.product.slot}: ${reply ?: "intet svar"}"
                        }
                    }
                }
                val names = m.picks.joinToString(", ") { "${it.count}x ${it.product.name}" }
                if (error == null) {
                    show(Screen(config.name, listOf(t.pickUp), false, Mood.GOOD), 30_000)
                    sales.add(p.key, p.amountOre, "OK", names, clip)
                } else {
                    fault = error
                    show(Screen(config.name, listOf(t.failed), false, Mood.ERROR), 60_000)
                    sales.add(p.key, p.amountOre, "REFUNDER", "udleveret $done af ${m.total} ($names): $error", clip)
                    notifier.send("${config.name}: FEJL $error – udleveret $done af ${m.total}. Refundér og tjek automaten.")
                }
            }
        }
        recorder?.stopClipLater()
        refresh()
    }

    // ---- Lager og admin -----------------------------------------------------------------

    private fun stockOf(p: Product) = stockPrefs.getInt(p.id, 0)
    private fun setStock(p: Product, n: Int) = stockPrefs.edit().putInt(p.id, maxOf(0, n)).apply()
    fun stock(): Map<String, Int> = config.products.associate { it.id to stockOf(it) }

    fun refillAll() {
        config.products.forEach { setStock(it, it.capacity) }
        fault = null
        refresh()
    }

    fun clearFault() {
        fault = null
        refresh()
    }

    fun rewind(slot: Int, ms: Int) = scope.launch {
        linkLock.withLock { link.command("REWIND $slot $ms", ms + 3_000L) }
    }

    val filesDir: File get() = files

    // ---- Sundhed ------------------------------------------------------------------------

    fun setListenerConnected(connected: Boolean) {
        listenerOk = connected
        refresh()
    }

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        online = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                refresh()
            }

            override fun onLost(network: Network) {
                online = false
                refresh()
            }
        })
    }

    private suspend fun watchdog() {
        var failures = 0
        var lastCleanup = 0L
        while (true) {
            if (!linkLock.isLocked) {
                linkOk = linkLock.withLock {
                    val ok = (link.connected || link.connect()) && link.command("PING", 2_000) == "PONG"
                    if (!ok) link.close()
                    ok
                }
                failures = if (linkOk) 0 else failures + 1
                if (failures == 3) notifier.send("${config.name}: ingen forbindelse til motorstyringen")
            }
            if (System.currentTimeMillis() - lastCleanup > 6 * 3_600_000L) {
                recorder?.deleteOld()
                lastCleanup = System.currentTimeMillis()
            }
            refresh()
            delay(10_000)
        }
    }

    // ---- Skærm --------------------------------------------------------------------------

    private fun show(s: Screen, ms: Long) {
        message = s
        messageUntil = System.currentTimeMillis() + ms
        refresh()
        scope.launch {
            delay(ms + 50)
            refresh()
        }
    }

    @Synchronized
    fun refresh() {
        if (!started) return
        val t = config.texts
        val name = config.name
        fun down(reason: String, mood: Mood = Mood.ERROR) = Screen(name, listOf(t.outOfService, reason), false, mood)
        val stock = stock()

        _screen.value = message?.takeIf { System.currentTimeMillis() < messageUntil } ?: when {
            configError != null -> down("Fejl i config.json: $configError")
            fault != null -> down(fault!!)
            !listenerOk -> down("Mangler notifikationsadgang")
            !online -> down("Ingen internetforbindelse", Mood.WARN)
            !linkOk -> down("Ingen forbindelse til motorstyringen", Mood.WARN)
            stock.values.sum() == 0 -> Screen(name, listOf(t.soldOut), false, Mood.WARN)
            else -> {
                val lines = config.products
                    .groupBy { it.name to it.priceOre }
                    .map { (k, ps) ->
                        val left = ps.sumOf { stock[it.id] ?: 0 }
                        "${k.first} · ${if (left > 0) formatKr(k.second) else t.soldOut}"
                    }
                Screen(name, lines + t.ready, config.display.qrPayload != null, Mood.NORMAL)
            }
        }
    }
}
