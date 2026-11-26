package com.example.engine

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ExtendableBuilder

object CameraFpsHelper {
    /**
     * Queries back camera supported AE target FPS ranges and applies the highest smooth range
     * (preferring [60, 60] or [30, 60] when hardware supports 60fps capture).
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun <T> applyBestHighFpsRange(context: Context, builder: ExtendableBuilder<T>): Int {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return 30
            var bestRange: Range<Int>? = null
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: continue
                    bestRange = ranges
                        .filter { it.upper >= 30 }
                        .maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
                    break
                }
            }
            if (bestRange != null) {
                Camera2Interop.Extender(builder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange)
                bestRange.upper
            } else {
                30
            }
        } catch (_: Exception) {
            30
        }
    }
}
