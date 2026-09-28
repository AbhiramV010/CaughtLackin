package com.caughtlackin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Foreground service that owns the camera, the models, the gravity sensor and the strike
 * logic, so a session keeps running after the activity stops.
 */
class SessionService : LifecycleService(), SensorEventListener {

    private lateinit var prefs: Prefs
    private lateinit var strikes: StrikeManager
    private lateinit var executor: ExecutorService
    private var analysis: ImageAnalysis? = null
    private var analyzer: FrameAnalyzer? = null
    private var sensors: SensorManager? = null
    private var csv: PrintWriter? = null
    private var running = false

    // Analyzer thread state
    private val window = FeatureWindow()
    private var lastClassify = 0L
    private var lastState: State? = null
    private var talkingSince = 0L
    private var awaySince = 0L

    // Sensor (main) thread state
    private var baseline: FloatArray? = null
    private var liftStart = 0L
    private var pickupRaised = false
    @Volatile private var tiltDeg = 0.0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        running = true

        createChannels(this)
        startForeground(
            SESSION_NOTIFICATION_ID,
            buildNotification(this, SESSION_CHANNEL, "Study session running", "Watching for distractions"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )

        prefs = Prefs(this)
        val calibration = prefs.calibrationMode
        strikes = StrikeManager(this, prefs, dryRun = calibration)
        SessionBus.strikes = strikes
        SessionBus.calibration.value = calibration
        SessionBus.status.value = LiveStatus()
        SessionBus.log.value = emptyList()
        SessionBus.active.value = true
        SessionBus.log(if (calibration) "Calibration session started" else "Session started")

        if (calibration) openCsv()
        startCamera()
        sensors = getSystemService(SensorManager::class.java).also { sm ->
            sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
        return START_NOT_STICKY
    }

    private fun startCamera() {
        executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            this.analysis = analysis
            analysis.setAnalyzer(executor) { image ->
                val a = analyzer ?: FrameAnalyzer(this, window, ::onFrame).also { analyzer = it }
                a.analyze(image)
            }
            try {
                val provider = providerFuture.get()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                SessionBus.log("Camera failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Analyzer thread. */
    private fun onFrame(now: Long) {
        if (now - lastClassify < Thresholds.CLASSIFY_INTERVAL_MS) return
        lastClassify = now
        val w = window.snapshot(now) ?: return
        val state = classify(w)

        if (state != lastState) {
            SessionBus.log("State: ${state.label}")
            lastState = state
        }
        track(state, State.TALKING, Violation.TALKING, "was talking to someone instead of studying", now,
            Thresholds.TALKING_STRIKE_MS, { talkingSince }, { talkingSince = it })
        track(state, State.AWAY, Violation.AWAY, "walked away from my desk", now,
            Thresholds.AWAY_STRIKE_MS, { awaySince }, { awaySince = it })

        SessionBus.status.value = LiveStatus(state, w, tiltDeg)
        csv?.let { out ->
            out.println(
                listOf(System.currentTimeMillis(), SessionBus.calibrationLabel, state.name, w.pitch, w.yaw,
                    w.jawVar, w.shoulders, w.wristMotion, w.faceFraction, "%.1f".format(tiltDeg)).joinToString(","),
            )
            out.flush()
        }
    }

    /** Raises [v] after [target] persists for [limitMs]; clears it as soon as the state changes. */
    private inline fun track(
        state: State, target: State, v: Violation, reason: String, now: Long, limitMs: Long,
        since: () -> Long, setSince: (Long) -> Unit,
    ) {
        if (state != target) {
            setSince(0L)
            strikes.clear(v)
            return
        }
        if (since() == 0L) setSince(now)
        if (now - since() >= limitMs && !strikes.isActive(v)) {
            strikes.raise(v, reason)
            setSince(now) // Keep going and it strikes again after another full period.
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val g = e.values
        val b = baseline ?: run { baseline = g.clone(); return }
        val cos = (g[0] * b[0] + g[1] * b[1] + g[2] * b[2]) / (norm(g) * norm(b))
        tiltDeg = Math.toDegrees(acos(cos.coerceIn(-1f, 1f)).toDouble())
        val now = SystemClock.uptimeMillis()
        if (tiltDeg > Thresholds.PICKUP_TILT_DEG) {
            if (liftStart == 0L) liftStart = now
            if (!pickupRaised && now - liftStart > Thresholds.PICKUP_HOLD_MS) {
                pickupRaised = true
                strikes.raise(Violation.PICKUP, "picked up my phone")
            }
        } else {
            liftStart = 0L
            pickupRaised = false
            strikes.clear(Violation.PICKUP)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun norm(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun openCsv() {
        val dir = getExternalFilesDir("calibration") ?: filesDir
        val file = File(dir, "calibration-${System.currentTimeMillis()}.csv")
        csv = PrintWriter(file).apply {
            println("time_ms,label,state,pitch,yaw,jaw_var,shoulders,wrist_motion,face_fraction,tilt_deg")
        }
        SessionBus.log("Logging to ${file.absolutePath}")
    }

    override fun onDestroy() {
        if (running) {
            sensors?.unregisterListener(this)
            analysis?.clearAnalyzer()
            strikes.shutdown()
            SessionBus.strikes = null
            executor.execute {
                analyzer?.close()
                csv?.close()
            }
            executor.shutdown()
            SessionBus.active.value = false
            SessionBus.log("Session ended")
        }
        super.onDestroy()
    }

    companion object {
        const val SESSION_CHANNEL = "session"
        const val STRIKE_CHANNEL = "strike"
        const val SESSION_NOTIFICATION_ID = 1
        const val STRIKE_NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.caughtlackin.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SessionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(SESSION_CHANNEL, "Study session", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(STRIKE_CHANNEL, "Strike alerts", NotificationManager.IMPORTANCE_HIGH))
        }

        fun buildNotification(context: Context, channel: String, title: String, text: String): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, SessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_eye)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(
                    if (channel == STRIKE_CHANNEL) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW,
                )
                .build()
        }
    }
}
