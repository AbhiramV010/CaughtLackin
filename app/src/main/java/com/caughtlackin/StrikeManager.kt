package com.caughtlackin

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.telephony.SmsManager

enum class Violation { PICKUP, LEFT_APP, TALKING, AWAY }

/**
 * First violation starts a 15 s alarm countdown, cancelled once all violations clear.
 * On timeout, texts a random squad member (10 min cooldown). Thread-safe.
 */
class StrikeManager(
    private val context: Context,
    private val prefs: Prefs,
    /** Calibration: log strikes only. */
    private val dryRun: Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private val active = LinkedHashMap<Violation, String>()
    private var deadline = 0L
    private var tone: ToneGenerator? = null
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Synchronized
    fun isActive(v: Violation) = v in active

    @Synchronized
    fun raise(v: Violation, reason: String) {
        if (v in active) return
        if (dryRun) {
            SessionBus.log("(calibration) would strike: $reason")
            return
        }
        active[v] = reason
        SessionBus.log("Strike: $reason")
        if (active.size == 1) {
            deadline = SystemClock.uptimeMillis() + Thresholds.GRACE_MS
            tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
            main.post(tick)
        }
    }

    @Synchronized
    fun clear(v: Violation) {
        if (active.remove(v) == null || active.isNotEmpty()) return
        endCountdown()
        SessionBus.log("Strike cancelled")
    }

    @Synchronized
    fun shutdown() {
        active.clear()
        endCountdown()
    }

    private val tick = object : Runnable {
        override fun run(): Unit = synchronized(this@StrikeManager) {
            if (active.isEmpty()) return
            val left = deadline - SystemClock.uptimeMillis()
            if (left <= 0) {
                val reason = active.values.first()
                active.clear()
                endCountdown()
                SessionBus.log(sendShame(reason))
                return
            }
            val reason = active.values.first()
            val seconds = ((left + 999) / 1000).toInt()
            SessionBus.countdown.value = StrikeCountdown(reason, seconds)
            showAlertNotification(reason, seconds)
            tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 600)
            vibrate()
            main.postDelayed(this, 1_000)
        }
    }

    private fun endCountdown() {
        main.removeCallbacks(tick)
        tone?.release()
        tone = null
        SessionBus.countdown.value = null
        notifications.cancel(SessionService.STRIKE_NOTIFICATION_ID)
    }

    private fun sendShame(reason: String): String {
        val now = System.currentTimeMillis()
        val sinceLast = now - prefs.lastTextAt
        if (sinceLast < Thresholds.TEXT_COOLDOWN_MS) {
            val mins = (Thresholds.TEXT_COOLDOWN_MS - sinceLast + 59_999) / 60_000
            return "Countdown ran out ($reason), but texts are on cooldown for $mins more min"
        }
        val member = prefs.squad.randomOrNull() ?: return "Countdown ran out, but the squad is empty"
        val message = (prefs.messages.randomOrNull() ?: return "Countdown ran out, but no messages are set")
            .replace("{reason}", reason)
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "Countdown ran out, but SMS permission is missing"
        }
        return try {
            val sms = context.getSystemService(SmsManager::class.java)
            sms.sendMultipartTextMessage(member.phone, null, sms.divideMessage(message), null, null)
            prefs.lastTextAt = now
            "Texted ${member.name}: \"$message\""
        } catch (e: Exception) {
            "Failed to text ${member.name}: ${e.message}"
        }
    }

    private fun showAlertNotification(reason: String, seconds: Int) {
        notifications.notify(
            SessionService.STRIKE_NOTIFICATION_ID,
            SessionService.buildNotification(
                context,
                SessionService.STRIKE_CHANNEL,
                "Caught lackin: $reason",
                "Fix it in $seconds s or a text goes out",
            ),
        )
    }

    private fun vibrate() {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
