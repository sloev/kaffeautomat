package dk.automat

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Hele skærmen er sort; kun et lille statusvindue (placeret efter config.json) viser tekst,
 * så telefonen kan sidde bag plexiglas med en maske, hvor kun vinduet og kameraet er fri.
 * Langt tryk et vilkårligt sted åbner ejer-menuen (kræver at lågen er åben).
 */
class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var qr: ImageView

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Machine.start(this)
        val cfg = Machine.config.display

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = cfg.brightness }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        buildViews(cfg)
        setContentView(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Machine.screen.collect { render(it) }
            }
        }
        lifecycleScope.launch { antiBurnIn() }

        if (Machine.config.camera.enabled) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                askCamera.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        if (!granted) {
            Machine.setListenerConnected(false)
        } else {
            NotificationListenerService.requestRebind(ComponentName(this, PaymentListener::class.java))
        }
    }

    private fun startCamera() {
        val rec = ClipRecorder(applicationContext, Machine.config.camera)
        rec.bind(this)
        Machine.recorder = rec
    }

    private fun buildViews(cfg: DisplayConfig) {
        val density = resources.displayMetrics.density
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isLongClickable = true
            setOnLongClickListener { adminMenu(); true }
        }
        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = cfg.textSizeSp * 0.8f
            typeface = Typeface.DEFAULT_BOLD
        }
        body = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = cfg.textSizeSp
            setLineSpacing(0f, 1.15f)
        }
        qr = ImageView(this).apply { visibility = View.GONE }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(body)
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(qr)
        }
        root.addView(panel)
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) placePanel(cfg, r - l, b - t)
        }
    }

    private fun placePanel(cfg: DisplayConfig, w: Int, h: Int) {
        val pw = (w * cfg.width).toInt()
        val ph = (h * cfg.height).toInt()
        panel.layoutParams = FrameLayout.LayoutParams(pw, ph).apply {
            leftMargin = (w * cfg.x).toInt()
            topMargin = (h * cfg.y).toInt()
        }
        cfg.qrPayload?.let { payload ->
            val size = minOf(ph, pw / 3) - panel.paddingTop * 2
            if (size > 0) {
                qr.setImageBitmap(qrBitmap(payload, size))
                qr.layoutParams = LinearLayout.LayoutParams(size, size)
            }
        }
    }

    private fun render(s: Screen) {
        title.text = s.title
        body.text = s.lines.joinToString("\n")
        body.setTextColor(
            when (s.mood) {
                Mood.NORMAL -> Color.WHITE
                Mood.GOOD -> Color.rgb(0x7C, 0xE0, 0x9A)
                Mood.WARN -> Color.rgb(0xFF, 0xC8, 0x57)
                Mood.ERROR -> Color.rgb(0xFF, 0x6B, 0x6B)
            },
        )
        qr.visibility = if (s.showQr && qr.drawable != null) View.VISIBLE else View.GONE
    }

    /** Flytter vinduet et par pixels af og til, så OLED-skærme ikke brænder teksten fast. */
    private suspend fun antiBurnIn() {
        val px = 4 * resources.displayMetrics.density
        while (true) {
            delay(10 * 60_000L)
            panel.translationX = Random.nextFloat() * 2 * px - px
            panel.translationY = Random.nextFloat() * 2 * px - px
        }
    }

    private fun qrBitmap(payload: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    // ---- Ejer-menu ----------------------------------------------------------------------

    private fun adminMenu() {
        val stock = Machine.stock()
        val stockText = Machine.config.products.joinToString("\n") { "${it.name} (søjle ${it.slot}): ${stock[it.id]}/${it.capacity}" }
        val items = arrayOf(
            "Genopfyld alle søjler",
            "Nulstil fejl",
            "Simulér betaling",
            "Spol søjle tilbage (1 s)",
            "Notifikationsadgang",
            "Genindlæs config.json",
        )
        AlertDialog.Builder(this)
            .setTitle("Lager\n$stockText\n\nFiler: ${Machine.filesDir}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> Machine.refillAll()
                    1 -> Machine.clearFault()
                    2 -> askNumber("Beløb i øre", Machine.config.products.first().priceOre) { Machine.simulatePayment(it) }
                    3 -> askNumber("Søjle", 0) { Machine.rewind(it.toInt(), 1000) }
                    4 -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    5 -> lifecycleScope.launch {
                        Machine.reloadConfig().join()
                        recreate() // statusvindue og kamera bruger den nye config
                    }
                }
            }
            .show()
    }

    private fun askNumber(label: String, default: Long, then: (Long) -> Unit) {
        val input = EditText(this).apply {
            setText(default.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle(label)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> input.text.toString().toLongOrNull()?.let(then) }
            .setNegativeButton("Annullér", null)
            .show()
    }
}
