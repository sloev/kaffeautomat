package dk.automat

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Scanner parrings-QR-koden fra dashboardet. Bruger frontkameraet, som kigger ud gennem
 * plexiglasset – hold skærmen med QR-koden foran automaten. Falder tilbage til bagkameraet.
 */
class ScanActivity : ComponentActivity() {
    private lateinit var analyzer: ExecutorService
    private val reader = QRCodeReader()
    @Volatile private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preview = PreviewView(this)
        val hint = TextView(this).apply {
            text = "Hold parrings-QR-koden fra dashboardet foran kameraet"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview)
            addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })

        analyzer = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analyzer, ::decode) }
            val selector = listOf(CameraSelector.DEFAULT_FRONT_CAMERA, CameraSelector.DEFAULT_BACK_CAMERA)
                .firstOrNull { provider.hasCamera(it) }
            if (selector == null) {
                finish()
                return@addListener
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, p, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun decode(image: ImageProxy) {
        image.use {
            if (done) return
            // Y-planet er et gråtonebillede – det er alt en QR-læser behøver.
            val plane = it.planes[0]
            val data = ByteArray(plane.buffer.remaining()).also { bytes -> plane.buffer.get(bytes) }
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
            val text = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            if (text != null && parsePairPayload(text) != null) {
                done = true
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analyzer.shutdown()
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
