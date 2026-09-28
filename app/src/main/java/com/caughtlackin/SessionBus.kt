package com.caughtlackin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class LiveStatus(val state: State? = null, val window: Window? = null, val tiltDeg: Double = 0.0)

data class StrikeCountdown(val reason: String, val secondsLeft: Int)

/** In-process link between [SessionService] and [SessionActivity]. */
object SessionBus {
    val active = MutableStateFlow(false)
    val calibration = MutableStateFlow(false)
    val status = MutableStateFlow(LiveStatus())
    val countdown = MutableStateFlow<StrikeCountdown?>(null)
    val log = MutableStateFlow<List<String>>(emptyList())

    /** Calibration CSV label, set from the session screen. */
    @Volatile var calibrationLabel: String = "locked_in"

    @Volatile var strikes: StrikeManager? = null

    fun log(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        log.update { (listOf("$stamp  $line") + it).take(30) }
    }
}
