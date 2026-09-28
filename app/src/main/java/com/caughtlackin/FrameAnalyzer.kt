package com.caughtlackin

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Runs the face model at ~10 fps and the pose model at ~5 fps on CameraX frames,
 * feeding [window]. [onFrame] is called after each processed frame, on the analyzer thread.
 */
class FrameAnalyzer(
    context: Context,
    private val window: FeatureWindow,
    private val onFrame: (now: Long) -> Unit,
) : ImageAnalysis.Analyzer {

    private val face = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .build(),
    )

    private val pose = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .build(),
    )

    private var lastFace = 0L
    private var lastPose = 0L

    override fun analyze(image: ImageProxy) {
        image.use {
            val now = SystemClock.uptimeMillis()
            val runFace = now - lastFace >= Thresholds.FACE_INTERVAL_MS
            val runPose = now - lastPose >= Thresholds.POSE_INTERVAL_MS
            if (!runFace && !runPose) return

            val mpImage = BitmapImageBuilder(image.toBitmap()).build()
            val options = ImageProcessingOptions.builder()
                .setRotationDegrees(image.imageInfo.rotationDegrees)
                .build()

            if (runFace) {
                lastFace = now
                window.addFace(now, faceSample(face.detectForVideo(mpImage, options, now)))
            }
            if (runPose) {
                lastPose = now
                window.addPose(now, poseSample(pose.detectForVideo(mpImage, options, now)))
            }
            onFrame(now)
        }
    }

    fun close() {
        face.close()
        pose.close()
    }

    private fun faceSample(r: FaceLandmarkerResult): FaceSample {
        val m = r.facialTransformationMatrixes().orElse(null)?.firstOrNull()
            ?: return FaceSample(present = false)
        // Column-major 4x4: r_ij = m[j * 4 + i]. The face's forward axis in camera space is
        // column 2 = (r02, r12, r22). Looking down gives negative pitch, turning gives |yaw| > 0.
        // If the live pitch reading has the wrong sign on the device, flip it here.
        val r02 = m[8]
        val r12 = m[9]
        val r22 = m[10]
        val pitch = Math.toDegrees(asin(r12.coerceIn(-1f, 1f)).toDouble()).toFloat()
        val yaw = Math.toDegrees(atan2(r02, r22).toDouble()).toFloat()
        val jaw = r.faceBlendshapes().orElse(null)?.firstOrNull()
            ?.firstOrNull { it.categoryName() == "jawOpen" }?.score() ?: 0f
        return FaceSample(present = true, pitch = pitch, yaw = yaw, jawOpen = jaw)
    }

    private fun poseSample(r: PoseLandmarkerResult): PoseSample {
        val lm = r.landmarks().firstOrNull() ?: return PoseSample(false, null, null)
        fun visible(i: Int) = lm[i].visibility().orElse(0f) >= Thresholds.LANDMARK_VISIBILITY

        val shoulderIdx = listOf(LEFT_SHOULDER, RIGHT_SHOULDER).filter(::visible)
        // Image y grows downward; "low" means below the shoulder line, or in the lower part of the frame.
        val lowLine = if (shoulderIdx.isEmpty()) 0.6f else shoulderIdx.map { lm[it].y() }.average().toFloat()

        fun wrist(i: Int): Pair<Float, Float>? {
            val p = lm[i]
            val lenient = p.visibility().orElse(0f) >= Thresholds.LANDMARK_VISIBILITY / 2
            return if (lenient && p.y() > lowLine) p.x() to p.y() else null
        }

        return PoseSample(
            shoulders = shoulderIdx.isNotEmpty(),
            leftWrist = wrist(LEFT_WRIST),
            rightWrist = wrist(RIGHT_WRIST),
        )
    }

    private companion object {
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
    }
}
