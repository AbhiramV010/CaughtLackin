package com.caughtlackin

import kotlin.math.abs
import kotlin.math.hypot

enum class State(val label: String) {
    LOCKED_IN("Locked in"),
    WRITING("Writing"),
    TALKING("Talking"),
    AWAY("Away"),
}

/** Initial guesses; tune them from calibration CSVs recorded at the real desk. */
object Thresholds {
    const val WINDOW_MS = 5_000L
    const val FACE_INTERVAL_MS = 100L      // ~10 fps
    const val POSE_INTERVAL_MS = 200L      // ~5 fps
    const val CLASSIFY_INTERVAL_MS = 500L

    const val YAW_DEG = 30f
    const val PITCH_DOWN_DEG = -15f
    const val JAW_VAR = 0.01f
    const val WRIST_MOTION = 0.02f
    const val FACE_FRACTION = 0.5f         // min share of frames with a face
    const val SHOULDER_FRACTION = 0.3f     // min share of pose frames with shoulders
    const val LANDMARK_VISIBILITY = 0.5f

    const val TALKING_STRIKE_MS = 60_000L
    const val AWAY_STRIKE_MS = 10 * 60_000L
    const val PICKUP_TILT_DEG = 20.0
    const val PICKUP_HOLD_MS = 1_000L

    const val GRACE_MS = 15_000L
    const val TEXT_COOLDOWN_MS = 10 * 60_000L
}

/** Rolling-window features; pitch/yaw are NaN if no face was seen. */
data class Window(
    val pitch: Float,
    val yaw: Float,
    val jawVar: Float,
    val shoulders: Boolean,
    val wristMotion: Float,
    val faceFraction: Float,
)

fun classify(w: Window): State {
    val faceSeen = w.faceFraction >= Thresholds.FACE_FRACTION
    return when {
        !w.shoulders -> State.AWAY
        // Facing forward with a moving mouth is reading aloud, so require a turned head.
        faceSeen && abs(w.yaw) > Thresholds.YAW_DEG && w.jawVar > Thresholds.JAW_VAR -> State.TALKING
        // Writing often hides the face, so no face counts as looking down.
        (!faceSeen || w.pitch < Thresholds.PITCH_DOWN_DEG) && w.wristMotion > Thresholds.WRIST_MOTION -> State.WRITING
        else -> State.LOCKED_IN
    }
}

class FaceSample(val present: Boolean, val pitch: Float = Float.NaN, val yaw: Float = Float.NaN, val jawOpen: Float = 0f)

/** Wrists are normalized coords, null unless visible and low in frame. */
class PoseSample(val shoulders: Boolean, val leftWrist: Pair<Float, Float>?, val rightWrist: Pair<Float, Float>?)

/** Rolling sample buffer; analyzer thread only. */
class FeatureWindow(private val spanMs: Long = Thresholds.WINDOW_MS) {
    private val faces = ArrayDeque<Pair<Long, FaceSample>>()
    private val poses = ArrayDeque<Pair<Long, PoseSample>>()

    fun addFace(t: Long, s: FaceSample) {
        faces.addLast(t to s)
        trim(t)
    }

    fun addPose(t: Long, s: PoseSample) {
        poses.addLast(t to s)
        trim(t)
    }

    private fun trim(now: Long) {
        while (faces.isNotEmpty() && now - faces.first().first > spanMs) faces.removeFirst()
        while (poses.isNotEmpty() && now - poses.first().first > spanMs) poses.removeFirst()
    }

    /** Null until a pose sample exists. */
    fun snapshot(now: Long): Window? {
        trim(now)
        if (poses.isEmpty()) return null

        val seen = faces.map { it.second }.filter { it.present }
        val faceFraction = if (faces.isEmpty()) 0f else seen.size.toFloat() / faces.size
        val pitch = if (seen.isEmpty()) Float.NaN else seen.map { it.pitch }.average().toFloat()
        val yaw = if (seen.isEmpty()) Float.NaN else seen.map { it.yaw }.average().toFloat()
        val jawVar = variance(seen.map { it.jawOpen })

        val poseSamples = poses.map { it.second }
        val shoulderFraction = poseSamples.count { it.shoulders }.toFloat() / poseSamples.size
        val wristMotion = maxOf(
            motion(poseSamples.map { it.leftWrist }),
            motion(poseSamples.map { it.rightWrist }),
        )

        return Window(
            pitch = pitch,
            yaw = yaw,
            jawVar = jawVar,
            shoulders = shoulderFraction >= Thresholds.SHOULDER_FRACTION,
            wristMotion = wristMotion,
            faceFraction = faceFraction,
        )
    }

    private fun variance(xs: List<Float>): Float {
        if (xs.size < 3) return 0f
        val mean = xs.average()
        return xs.sumOf { (it - mean) * (it - mean) }.div(xs.size).toFloat()
    }

    /** Mean wrist displacement per frame; missing wrists count as still. */
    private fun motion(points: List<Pair<Float, Float>?>): Float {
        if (points.size < 2) return 0f
        var total = 0f
        for (i in 1 until points.size) {
            val a = points[i - 1] ?: continue
            val b = points[i] ?: continue
            total += hypot(b.first - a.first, b.second - a.second)
        }
        return total / (points.size - 1)
    }
}
