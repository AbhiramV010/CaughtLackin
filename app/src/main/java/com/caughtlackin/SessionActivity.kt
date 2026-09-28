package com.caughtlackin

import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.caughtlackin.databinding.ActivitySessionBinding
import kotlinx.coroutines.launch

class SessionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionBinding
    private lateinit var prefs: Prefs
    private var ending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.content)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)

        binding.stop.setOnClickListener { endSession() }
        // Back just backgrounds the app; only End session stops it.
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        binding.labels.setOnCheckedChangeListener { _, id ->
            SessionBus.calibrationLabel = when (id) {
                R.id.labelWriting -> "writing"
                R.id.labelTalking -> "talking"
                R.id.labelAway -> "away"
                else -> "locked_in"
            }
        }
        SessionBus.calibrationLabel = "locked_in"

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { SessionBus.status.collect(::renderStatus) }
                launch { SessionBus.log.collect { binding.log.text = it.joinToString("\n") } }
                launch {
                    SessionBus.calibration.collect {
                        binding.calibrationPanel.visibility = if (it) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    SessionBus.countdown.collect { c ->
                        binding.strikeOverlay.visibility = if (c == null) View.GONE else View.VISIBLE
                        if (c != null) {
                            binding.strikeReason.text = "You ${c.reason}"
                            binding.strikeSeconds.text = c.secondsLeft.toString()
                        }
                    }
                }
                launch {
                    // Finish once the service goes active then inactive.
                    var seenActive = false
                    SessionBus.active.collect { active ->
                        if (active) seenActive = true else if (seenActive) finish()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        SessionBus.strikes?.clear(Violation.LEFT_APP)
    }

    override fun onStop() {
        super.onStop()
        if (ending || isChangingConfigurations || !SessionBus.active.value) return
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        when {
            screenOn -> SessionBus.strikes?.raise(Violation.LEFT_APP, "left the app")
            prefs.lockEndsSession -> SessionService.stop(this)
            else -> SessionBus.strikes?.raise(Violation.LEFT_APP, "locked my phone")
        }
    }

    private fun endSession() {
        ending = true
        SessionService.stop(this)
        finish()
    }

    private fun renderStatus(s: LiveStatus) {
        binding.state.text = s.state?.label ?: "Warming up…"
        val w = s.window ?: return
        binding.metrics.text = buildString {
            appendLine("pitch      %6.1f°   yaw  %6.1f°".format(w.pitch, w.yaw))
            appendLine("jawVar     %8.4f   face %4.0f%%".format(w.jawVar, w.faceFraction * 100))
            appendLine("shoulders  %-8s   wrist %.4f".format(if (w.shoulders) "yes" else "no", w.wristMotion))
            append("phone tilt %6.1f°".format(s.tiltDeg))
        }
    }
}
