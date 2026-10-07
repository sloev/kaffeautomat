package dk.automat

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.Settings
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
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Mood { NORMAL, GOOD, WARN, ERROR }

/** Det statusvinduet skal vise lige nu. */
data class Screen(val title: String, val lines: List<String>, val showQr: Boolean, val mood: Mood)

/**
 * Automatens hjerne: modtager betalinger, finder varer, kører motorstyringen,
 * holder lager, logger salg, optager klip, melder til serveren og bestemmer
 * hvad skærmen viser.
 *
 * Lever i appens proces uafhængigt af skærmen: Android holder processen i live
 * (og genstarter den) for notifikationslytterens skyld, så betalinger behandles
 * selv hvis skærmen er lukket.
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
    private lateinit var outbox: Outbox
    private var server: ServerClient? = null
    private lateinit var prefs: SharedPreferences
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
    /** Fejl der kræver at ejeren åbner automaten (f.eks. fastkørt søjle). */
    @Volatile private var fault: String? = null
    @Volatile private var message: Screen? = null
    @Volatile private var messageUntil = 0L
    @Volatile private var uiVisible = false
    @Volatile private var uiHiddenSince = SystemClock.elapsedRealtime()
    private val startedAt = System.currentTimeMillis()
    private val counts = mutableMapOf("payments" to 0, "ok" to 0, "refund" to 0)
    /** Serveren telefonen melder til (fra parring eller config.json), eller null. */
    @Volatile var serverUrl: String? = null
        private set
    @Volatile private var heartbeatSec = 60
    /** Tidspunkt for sidste svar fra serveren (0 = aldrig). */
    @Volatile var lastServerContact = 0L
        private set

    private val _screen = MutableStateFlow(Screen("Automat", listOf("Starter…"), false, Mood.NORMAL))
    val screen: StateFlow<Screen> = _screen

    /** Tælles op når config.json er genindlæst, så skærmen kan bygge sig selv om. */
    private val _configReloads = MutableStateFlow(0)
    val configReloads: StateFlow<Int> = _configReloads

    @Synchronized
    fun start(context: Context) {
        if (started) return
        app = context.applicationContext
        files = app.getExternalFilesDir(null) ?: app.filesDir
        prefs = app.getSharedPreferences("automat", Context.MODE_PRIVATE)
        stockPrefs = app.getSharedPreferences("lager", Context.MODE_PRIVATE)
        outbox = Outbox(File(files, "udbakke.jsonl"))
        installCrashLog()
        loadConfig()
        ledger = Ledger(File(files, "betalinger.txt"))
        sales = SalesLog(File(files, "salg.csv"))
        raw = RawLog(File(files, "notifikationer.log"))
        watchNetwork()
        started = true

        val crash = File(files, "nedbrud.txt")
        report("start", "previousCrash" to crash.takeIf { it.exists() }?.readText()?.take(4000))
        crash.delete()
        configError?.let { report("configError", "error" to it) }

        scope.launch {
            for (p in queue) {
                try {
                    handle(p)
                } catch (e: Exception) {
                    Log.e(TAG, "Fejl ved betaling ${p.key}", e)
                    sales.add(p.key, p.amountOre, "REFUNDER", "intern fejl: $e", null)
                    report("refund", "payment" to p.key, "amountOre" to p.amountOre, "reason" to "intern fejl: $e")
                    recorder?.stopClipLater()
                }
            }
        }
        scope.launch { watchdog() }
        scope.launch { heartbeatLoop() }
        refresh()
    }

    /** Et nedbrud skrives til nedbrud.txt og sendes til serveren ved næste opstart. */
    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                File(files, "nedbrud.txt").writeText("${java.util.Date()} ${thread.name}\n${Log.getStackTraceString(e)}")
            } catch (_: Exception) {}
            previous?.uncaughtException(thread, e)
        }
    }

    /** config.json ligger i appens mappe på telefonen, så den kan rettes via USB eller fra serveren. */
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
        val serverConfig = pairedServer() ?: config.server
        server = serverConfig?.let { ServerClient(it, outbox) }
        serverUrl = serverConfig?.url
        heartbeatSec = serverConfig?.heartbeatSec ?: 60
        link = if (config.link.type == "fake") FakeLink() else UsbSerialLink(app, config.link.baud)
    }

    fun reloadConfig() = scope.launch {
        linkLock.withLock {
            link.close()
            loadConfig()
            linkOk = false
        }
        configError?.let { report("configError", "error" to it) }
        _configReloads.value++
        refresh()
    }

    /** Gemmer en ny config (fra serveren). Afvises hvis den ikke kan læses. */
    private fun writeConfig(json: JSONObject) {
        AppConfig.parse(json.toString())
        File(files, "config.json").writeText(json.toString(2))
        reloadConfig()
    }

    /** Serveren, telefonen er parret med (server.json), hvis nogen. */
    private fun pairedServer(): ServerConfig? = try {
        File(files, "server.json").takeIf { it.exists() }?.readText()?.let { JSONObject(it) }
            ?.let { ServerConfig(it.getString("url"), it.getString("token"), it.optInt("heartbeatSec", 60)) }
    } catch (e: Exception) {
        Log.w(TAG, "server.json kunne ikke læses", e)
        null
    }

    /** Hvilken config-version fra serveren telefonen kører (0 = ingen). */
    private var serverConfigVersion: Int
        get() = prefs.getInt("serverConfigVersion", 0)
        set(v) = prefs.edit().putInt("serverConfigVersion", v).apply()

    /**
     * Parrer telefonen med en server ud fra QR-koden fra dashboardet: henter et
     * enheds-token og automatens config. Blokerer – kald fra en baggrundstråd.
     */
    fun pair(apiUrl: String, code: String) {
        val url = apiUrl.trim().trimEnd('/')
        require(url.startsWith("https://")) { "Serveren skal bruge https" }
        val r = pairRequest(url, code)
        val config = r.getJSONObject("config")
        AppConfig.parse(config.toString())
        File(files, "server.json").writeText(
            JSONObject().put("url", url).put("token", r.getString("token")).put("heartbeatSec", 60).toString(2),
        )
        File(files, "config.json").writeText(config.toString(2))
        serverConfigVersion = r.optInt("configVersion", 0)
        report("paired", "automatId" to r.optString("automatId"))
        reloadConfig()
    }

    val deviceId: String
        get() = prefs.getString("deviceId", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }

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
                report("unreadable", "text" to r.text.take(300))
                notifier.send("${config.name}: MobilePay-notifikation uden beløb – tjek notifikationer.log")
            }
            ParseResult.NotMobilePay -> Unit
        }
    }

    /** Fra ejer-menuen: kør hele kæden uden MobilePay. */
    fun simulatePayment(amountOre: Long) {
        queue.trySend(Payment("sim-${System.currentTimeMillis()}", amountOre))
    }

    private suspend fun handle(p: Payment) {
        synchronized(counts) { counts["payments"] = counts["payments"]!! + 1 }
        val clip = recorder?.startClip(Integer.toHexString(p.key.hashCode()))
        val t = config.texts
        when (val m = match(p.amountOre, config.products, stock())) {
            is Match.Reject -> {
                show(Screen(config.name, listOf(t.wrongAmount), false, Mood.ERROR), 30_000)
                sales.add(p.key, p.amountOre, "REFUNDER", m.reason, clip)
                report("refund", "payment" to p.key, "amountOre" to p.amountOre, "reason" to m.reason, "clip" to clip)
                notifier.send("${config.name}: ${formatKr(p.amountOre)} afvist (${m.reason}) – refundér i MyShop")
                synchronized(counts) { counts["refund"] = counts["refund"]!! + 1 }
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
                    report("sale", "payment" to p.key, "amountOre" to p.amountOre, "items" to names, "clip" to clip)
                    synchronized(counts) { counts["ok"] = counts["ok"]!! + 1 }
                } else {
                    fault = error
                    val detail = "udleveret $done af ${m.total} ($names): $error"
                    show(Screen(config.name, listOf(t.failed), false, Mood.ERROR), 60_000)
                    sales.add(p.key, p.amountOre, "REFUNDER", detail, clip)
                    report("refund", "payment" to p.key, "amountOre" to p.amountOre, "reason" to detail, "clip" to clip)
                    report("fault", "error" to error)
                    notifier.send("${config.name}: FEJL $error – udleveret $done af ${m.total}. Refundér og tjek automaten.")
                    synchronized(counts) { counts["refund"] = counts["refund"]!! + 1 }
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
        report("refill")
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

    // ---- Server -------------------------------------------------------------------------

    /** Lægger en hændelse i udbakken; den sendes med næste heartbeat. */
    fun report(type: String, vararg data: Pair<String, Any?>) {
        if (!::outbox.isInitialized) return
        val o = JSONObject().put("ts", System.currentTimeMillis()).put("type", type)
        data.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        outbox.add(o)
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            try {
                val response = server?.heartbeat(status())
                if (response != null) {
                    lastServerContact = System.currentTimeMillis()
                    applyServerConfig(response)
                    parseCommands(response).forEach { runCommand(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Heartbeat fejlede", e)
            }
            delay(heartbeatSec * 1000L)
        }
    }

    /** Serveren sender config med, når telefonens version er bagud. */
    private fun applyServerConfig(response: JSONObject) {
        val config = response.optJSONObject("config") ?: return
        val version = response.optInt("configVersion", 0)
        try {
            writeConfig(config)
            report("configApplied", "configVersion" to version)
        } catch (e: Exception) {
            report("configError", "error" to "config v$version afvist: ${e.message}")
        }
        // Også ved fejl: ellers hentes den samme ugyldige config ved hvert heartbeat.
        serverConfigVersion = version
    }

    private fun runCommand(c: ServerCommand) {
        Log.i(TAG, "Kommando fra server: ${c.cmd}")
        val result = try {
            when (c.cmd) {
                "refill" -> refillAll()
                "clearFault" -> clearFault()
                "setStock" -> config.products.first { it.id == c.args.getString("productId") }
                    .let { setStock(it, c.args.getInt("count")) }.also { refresh() }
                "reloadConfig" -> reloadConfig()
                "simulate" -> simulatePayment(c.args.getLong("amountOre"))
                else -> throw IllegalArgumentException("ukendt kommando")
            }
            "ok"
        } catch (e: Exception) {
            "fejl: ${e.message}"
        }
        report("command", "id" to c.id, "cmd" to c.cmd, "result" to result)
    }

    /** Det telefonen fortæller serveren ved hvert heartbeat. */
    private fun status(): JSONObject {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        }
        val s = _screen.value
        return JSONObject()
            .put("deviceId", deviceId)
            .put("configVersion", serverConfigVersion)
            .put("name", config.name)
            .put("appVersion", app.packageManager.getPackageInfo(app.packageName, 0).versionName)
            .put("time", System.currentTimeMillis())
            .put("appStartedAt", startedAt)
            .put("phoneUptimeSec", SystemClock.elapsedRealtime() / 1000)
            .put("screen", JSONObject().put("title", s.title).put("lines", org.json.JSONArray(s.lines)).put("mood", s.mood.name))
            .put("health", JSONObject()
                .put("online", online)
                .put("motorLink", linkOk)
                .put("notificationAccess", listenerOk)
                .put("uiVisible", uiVisible)
                .put("canRelaunchUi", Settings.canDrawOverlays(app))
                .put("camera", recorder != null)
                .put("fault", fault ?: JSONObject.NULL)
                .put("configError", configError ?: JSONObject.NULL))
            .put("battery", JSONObject()
                .put("level", level ?: JSONObject.NULL)
                .put("charging", (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
                .put("temperatureC", (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0))
            .put("stock", JSONObject(stock() as Map<*, *>))
            .put("counts", JSONObject(synchronized(counts) { counts.toMap() } as Map<*, *>))
    }

    // ---- Sundhed ------------------------------------------------------------------------

    fun setListenerConnected(connected: Boolean) {
        if (connected != listenerOk) report("notificationAccess", "connected" to connected)
        listenerOk = connected
        refresh()
    }

    /** Ejeren er i Androids indstillinger – lad være med at trække skærmen frem imens. */
    fun pauseRelaunch(ms: Long) {
        uiHiddenSince = SystemClock.elapsedRealtime() + ms
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (!visible) uiHiddenSince = maxOf(uiHiddenSince, SystemClock.elapsedRealtime())
    }

    /**
     * Åbner skærmen igen, hvis den har været væk i 30 s (nedbrud, nogen har trykket "tilbage"…).
     * Android tillader kun det fra baggrunden, hvis appen må "vises over andre apps".
     */
    private fun relaunchUiIfHidden() {
        if (uiVisible || SystemClock.elapsedRealtime() - uiHiddenSince < 30_000) return
        if (!Settings.canDrawOverlays(app)) return
        try {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            uiHiddenSince = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            Log.w(TAG, "Kunne ikke åbne skærmen", e)
        }
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
                val was = linkOk
                linkOk = linkLock.withLock {
                    val ok = (link.connected || link.connect()) && link.command("PING", 2_000) == "PONG"
                    if (!ok) link.close()
                    ok
                }
                if (was != linkOk) report("motorLink", "connected" to linkOk)
                failures = if (linkOk) 0 else failures + 1
                if (failures == 3) notifier.send("${config.name}: ingen forbindelse til motorstyringen")
            }
            if (System.currentTimeMillis() - lastCleanup > 6 * 3_600_000L) {
                recorder?.deleteOld()
                lastCleanup = System.currentTimeMillis()
            }
            relaunchUiIfHidden()
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
