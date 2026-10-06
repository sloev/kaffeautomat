package dk.automat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Optager et kort videoklip af kunden ved hvert køb – fra betalingen kommer, til
 * [CameraConfig.tailMs] efter udleveringen. Uden lyd. Klippets filnavn står i salg.csv,
 * så en klage kan slås op. Klip ældre end [CameraConfig.retentionDays] slettes.
 */
class ClipRecorder(private val context: Context, private val cfg: CameraConfig) {
    private val main = Handler(Looper.getMainLooper())
    private val dir = File(context.getExternalFilesDir(null), "klip").apply { mkdirs() }
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    @Volatile private var currentClip: String? = null
    private val stop = Runnable {
        recording?.stop()
        recording = null
        currentClip = null
    }

    fun bind(owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            listOf(Quality.SD, Quality.HD),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                        ),
                    )
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                val selector = if (cfg.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, vc)
                videoCapture = vc
            } catch (e: Exception) {
                Log.w(TAG, "Kamera kunne ikke startes", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Starter (eller forlænger) et klip. Returnerer filnavnet, eller null hvis kameraet ikke kører. */
    @Synchronized
    fun startClip(tag: String): String? {
        if (videoCapture == null) return null
        main.removeCallbacks(stop)
        currentClip?.let { return it }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val name = "${stamp}_${tag.filter { it.isLetterOrDigit() }.take(12)}.mp4"
        currentClip = name
        main.post {
            val vc = videoCapture ?: return@post
            if (recording != null) return@post
            recording = vc.output
                .prepareRecording(context, FileOutputOptions.Builder(File(dir, name)).build())
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize && event.hasError()) {
                        Log.w(TAG, "Klip fejlede: ${event.error}")
                    }
                }
        }
        return name
    }

    @Synchronized
    fun stopClipLater() {
        main.removeCallbacks(stop)
        main.postDelayed(stop, cfg.tailMs)
    }

    fun deleteOld() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(cfg.retentionDays.toLong())
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "ClipRecorder"
    }
}
