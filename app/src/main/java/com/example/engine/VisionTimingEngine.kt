package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.camera.core.ImageProxy
import com.example.data.model.AntennaConfig
import com.example.data.model.DetectionMode
import com.example.data.model.LabSample
import com.example.data.model.LineOrientation
import com.example.data.model.RiderColorProfile
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class VisionDetectionResult(
    val triggered: Boolean,
    val triggerTimestampMs: Long,
    val detectedColors: List<LabSample>,
    val matchedProfile: RiderColorProfile?,
    val occupancyRatio: Float,
    val snapshotBase64: String,
    val photoFinishBase64: String?
)

/**
 * Real-time Computer Vision Engine for RC 1/4 Finish Line Antennae.
 * Implements:
 * 1. ROI "Tunnel Effect" clipping (ignoring movement outside central tunnel).
 * 2. Empty-track calibration baseline subtraction.
 * 3. Anti-vibration consecutive-frame & occupancy gate.
 * 4. Trigger & Color Separation: locks exact front-wheel crossing timestamp while
 *    accumulating fairing + rider silhouette pixels across subsequent frames, then
 *    matches dominant colors in perceptual CIE Lab space (robust to bike lean/lighting).
 */
class VisionTimingEngine {

    private var baselineR: IntArray? = null
    private var baselineG: IntArray? = null
    private var baselineB: IntArray? = null
    private var baseW = 0
    private var baseH = 0

    private var consecutiveMotionFrames = 0
    private var cooldownActive = false
    private var lastTriggerMs = 0L

    // Trigger & Color Separation accumulation state
    private var accumulatingFairing = false
    private var frozenTriggerMs = 0L
    private var frozenPhotoFinishB64: String? = null
    private var accumulationFramesLeft = 0
    private val accumulatedRgb = mutableListOf<Int>()

    private var calibrationPending = false

    fun requestLocalCalibration() {
        calibrationPending = true
        consecutiveMotionFrames = 0
        cooldownActive = false
        accumulatingFairing = false
        accumulatedRgb.clear()
    }

    fun isCalibrated(): Boolean = baselineR != null

    fun processFrame(
        image: ImageProxy,
        config: AntennaConfig,
        profiles: List<RiderColorProfile>,
        syncedNowMs: Long,
        isSpanish: Boolean,
        captureLiveSnapshot: Boolean
    ): VisionDetectionResult? {
        val rgbBitmap = imageProxyToScaledBitmap(image, targetW = 160, targetH = 120) ?: return null
        val w = rgbBitmap.width
        val h = rgbBitmap.height
        val pixels = IntArray(w * h)
        rgbBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        if (calibrationPending || baselineR == null || baseW != w || baseH != h) {
            if (calibrationPending) {
                baselineR = IntArray(w * h) { Color.red(pixels[it]) }
                baselineG = IntArray(w * h) { Color.green(pixels[it]) }
                baselineB = IntArray(w * h) { Color.blue(pixels[it]) }
                baseW = w
                baseH = h
                calibrationPending = false
            }
            val snap = if (captureLiveSnapshot) encodeBitmapBase64(rgbBitmap, 52) else ""
            return VisionDetectionResult(
                triggered = false,
                triggerTimestampMs = 0L,
                detectedColors = emptyList(),
                matchedProfile = null,
                occupancyRatio = 0f,
                snapshotBase64 = snap,
                photoFinishBase64 = null
            )
        }

        val bR = baselineR!!
        val bG = baselineG!!
        val bB = baselineB!!

        // Compute ROI Tunnel Rect
        val roiW = (w * config.roiSize.widthFrac).toInt().coerceAtLeast(24)
        val roiH = (h * config.roiSize.heightFrac).toInt().coerceAtLeast(24)
        val roiLeft = ((w - roiW) / 2).coerceIn(0, w - 1)
        val roiRight = (roiLeft + roiW).coerceIn(roiLeft + 1, w)
        val centerY = (h * config.roiHeight.centerYFrac).toInt()
        val roiTop = (centerY - roiH / 2).coerceIn(0, h - roiH)
        val roiBottom = (roiTop + roiH).coerceIn(roiTop + 1, h)

        // Trigger line strip inside ROI
        val lineStripHalf = 3
        val linePixelsTotal: Int
        var lineForegroundCount = 0

        if (config.lineOrientation == LineOrientation.VERTICAL) {
            val lineX = (roiLeft + roiW * config.linePosition).toInt().coerceIn(roiLeft, roiRight - 1)
            val x0 = (lineX - lineStripHalf).coerceAtLeast(roiLeft)
            val x1 = (lineX + lineStripHalf).coerceAtMost(roiRight - 1)
            linePixelsTotal = (x1 - x0 + 1) * (roiBottom - roiTop)
            for (y in roiTop until roiBottom) {
                for (x in x0..x1) {
                    val idx = y * w + x
                    if (isForeground(pixels[idx], bR[idx], bG[idx], bB[idx])) {
                        lineForegroundCount++
                    }
                }
            }
        } else {
            val lineY = (roiTop + roiH * config.linePosition).toInt().coerceIn(roiTop, roiBottom - 1)
            val y0 = (lineY - lineStripHalf).coerceAtLeast(roiTop)
            val y1 = (lineY + lineStripHalf).coerceAtMost(roiBottom - 1)
            linePixelsTotal = (y1 - y0 + 1) * (roiRight - roiLeft)
            for (y in y0..y1) {
                for (x in roiLeft until roiRight) {
                    val idx = y * w + x
                    if (isForeground(pixels[idx], bR[idx], bG[idx], bB[idx])) {
                        lineForegroundCount++
                    }
                }
            }
        }

        val occupancy = if (linePixelsTotal > 0) lineForegroundCount.toFloat() / linePixelsTotal else 0f

        // Collect silhouette foreground pixels inside ROI when accumulating fairing/rider body
        if (accumulatingFairing) {
            for (y in roiTop until roiBottom step 2) {
                for (x in roiLeft until roiRight step 2) {
                    val idx = y * w + x
                    if (isForeground(pixels[idx], bR[idx], bG[idx], bB[idx])) {
                        accumulatedRgb.add(pixels[idx])
                    }
                }
            }
            accumulationFramesLeft--
            if (accumulationFramesLeft <= 0) {
                accumulatingFairing = false
                val colors = extractDominantLabColors(accumulatedRgb, isSpanish)
                val matched = matchRiderProfile(colors, profiles)
                val snap = encodeBitmapBase64(rgbBitmap, 55)
                accumulatedRgb.clear()
                return VisionDetectionResult(
                    triggered = true,
                    triggerTimestampMs = frozenTriggerMs,
                    detectedColors = colors,
                    matchedProfile = matched,
                    occupancyRatio = occupancy,
                    snapshotBase64 = snap,
                    photoFinishBase64 = frozenPhotoFinishB64
                )
            }
        }

        // Refractory cooldown until line clears
        if (cooldownActive) {
            if (occupancy < config.antiVibration.minOccupancy * 0.45f && syncedNowMs - lastTriggerMs > 900L) {
                cooldownActive = false
            }
            val snap = if (captureLiveSnapshot) encodeBitmapBase64(rgbBitmap, 48) else ""
            return VisionDetectionResult(
                triggered = false,
                triggerTimestampMs = 0L,
                detectedColors = emptyList(),
                matchedProfile = null,
                occupancyRatio = occupancy,
                snapshotBase64 = snap,
                photoFinishBase64 = null
            )
        }

        if (occupancy >= config.antiVibration.minOccupancy) {
            consecutiveMotionFrames++
        } else {
            consecutiveMotionFrames = 0
        }

        val thresholdFrames = config.antiVibration.requiredFrames
        if (consecutiveMotionFrames >= thresholdFrames && !accumulatingFairing) {
            consecutiveMotionFrames = 0
            cooldownActive = true
            lastTriggerMs = syncedNowMs

            // Extract immediate ROI foreground pixels
            val roiFg = mutableListOf<Int>()
            for (y in roiTop until roiBottom step 2) {
                for (x in roiLeft until roiRight step 2) {
                    val idx = y * w + x
                    if (isForeground(pixels[idx], bR[idx], bG[idx], bB[idx])) {
                        roiFg.add(pixels[idx])
                    }
                }
            }

            if (config.detectionMode == DetectionMode.FAIRING_COLOR) {
                // Freeze exact front-wheel crossing time and accumulate central body silhouette
                frozenTriggerMs = syncedNowMs
                frozenPhotoFinishB64 = encodeBitmapBase64(rgbBitmap, 60)
                accumulatedRgb.clear()
                accumulatedRgb.addAll(roiFg)
                accumulatingFairing = true
                accumulationFramesLeft = 3
            } else {
                val colors = extractDominantLabColors(roiFg, isSpanish)
                val matched = matchRiderProfile(colors, profiles)
                val photoFinish = encodeBitmapBase64(rgbBitmap, 60)
                return VisionDetectionResult(
                    triggered = true,
                    triggerTimestampMs = syncedNowMs,
                    detectedColors = colors,
                    matchedProfile = matched,
                    occupancyRatio = occupancy,
                    snapshotBase64 = photoFinish,
                    photoFinishBase64 = photoFinish
                )
            }
        }

        val snap = if (captureLiveSnapshot) encodeBitmapBase64(rgbBitmap, 48) else ""
        return VisionDetectionResult(
            triggered = false,
            triggerTimestampMs = 0L,
            detectedColors = emptyList(),
            matchedProfile = null,
            occupancyRatio = occupancy,
            snapshotBase64 = snap,
            photoFinishBase64 = null
        )
    }

    private fun isForeground(pixel: Int, baseR: Int, baseG: Int, baseB: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        val diff = abs(r - baseR) + abs(g - baseG) + abs(b - baseB)
        return diff > 72
    }

    private fun extractDominantLabColors(rgbPixels: List<Int>, isSpanish: Boolean): List<LabSample> {
        if (rgbPixels.isEmpty()) return emptyList()
        val buckets = mutableMapOf<String, MutableList<Int>>()
        for (c in rgbPixels) {
            val lab = rgbToLab(c)
            val label = classifyLabColorName(lab.first, lab.second, lab.third, isSpanish)
            buckets.getOrPut(label) { mutableListOf() }.add(c)
        }
        return buckets.entries
            .sortedByDescending { it.value.size }
            .take(3)
            .map { (name, list) ->
                val avgR = list.map { Color.red(it) }.average().toInt().coerceIn(0, 255)
                val avgG = list.map { Color.green(it) }.average().toInt().coerceIn(0, 255)
                val avgB = list.map { Color.blue(it) }.average().toInt().coerceIn(0, 255)
                val avgColor = Color.rgb(avgR, avgG, avgB)
                val (l, a, b) = rgbToLab(avgColor)
                val hex = String.format("#%02X%02X%02X", avgR, avgG, avgB)
                LabSample(l = l, a = a, b = b, hex = hex, colorName = name)
            }
    }

    private fun matchRiderProfile(
        detected: List<LabSample>,
        profiles: List<RiderColorProfile>
    ): RiderColorProfile? {
        if (detected.isEmpty() || profiles.isEmpty()) return null
        var bestProfile: RiderColorProfile? = null
        var bestScore = Double.MAX_VALUE
        for (profile in profiles) {
            if (profile.samples.isEmpty()) continue
            var totalDist = 0.0
            for (d in detected) {
                val minSampleDist = profile.samples.minOf { s ->
                    // Weight chrominance (a,b) higher than lightness (L) to ignore bike lean shadow
                    val dl = (d.l - s.l) * 0.35
                    val da = d.a - s.a
                    val db = d.b - s.b
                    sqrt(dl * dl + da * da + db * db)
                }
                totalDist += minSampleDist
            }
            val avgDist = totalDist / detected.size
            if (avgDist < bestScore && avgDist < 34.0) {
                bestScore = avgDist
                bestProfile = profile
            }
        }
        return bestProfile
    }

    private fun rgbToLab(color: Int): Triple<Double, Double, Double> {
        fun linearize(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = linearize(Color.red(color))
        val g = linearize(Color.green(color))
        val b = linearize(Color.blue(color))

        val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
        val y = (r * 0.2126 + g * 0.7152 + b * 0.0722) / 1.00000
        val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883

        fun f(t: Double): Double = if (t > 0.008856) t.pow(1.0 / 3.0) else (7.787 * t) + (16.0 / 116.0)
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)

        val l = 116.0 * fy - 16.0
        val a = 500.0 * (fx - fy)
        val bVal = 200.0 * (fy - fz)
        return Triple(l, a, bVal)
    }

    private fun classifyLabColorName(l: Double, a: Double, b: Double, es: Boolean): String {
        val chroma = sqrt(a * a + b * b)
        if (chroma < 14.0) {
            return when {
                l < 26.0 -> if (es) "Negro" else "Black"
                l > 76.0 -> if (es) "Blanco" else "White"
                else -> if (es) "Gris" else "Gray"
            }
        }
        return when {
            a > 28 && b > 18 -> if (es) "Rojo" else "Red"
            a > 16 && b > 35 -> if (es) "Naranja" else "Orange"
            a in -14.0..18.0 && b > 42 -> if (es) "Amarillo" else "Yellow"
            a < -24 && b > 20 -> if (es) "Verde Flúor" else "Neon Green"
            a < -16 && b in -15.0..20.0 -> if (es) "Verde" else "Green"
            a < -8 && b < -18 -> if (es) "Cian" else "Cyan"
            a > 24 && b < -12 -> if (es) "Rosa" else "Pink"
            b < -24 -> if (es) "Azul" else "Blue"
            else -> if (es) "Gris" else "Gray"
        }
    }

    private fun imageProxyToScaledBitmap(image: ImageProxy, targetW: Int, targetH: Int): Bitmap? {
        return try {
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val srcW = image.width
            val srcH = image.height
            val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val row = IntArray(targetW)

            for (ty in 0 until targetH) {
                val sy = (ty * srcH) / targetH
                for (tx in 0 until targetW) {
                    val sx = (tx * srcW) / targetW
                    val yIdx = sy * yPlane.rowStride + sx * yPlane.pixelStride
                    val uvY = sy / 2
                    val uvX = sx / 2
                    val uIdx = uvY * uPlane.rowStride + uvX * uPlane.pixelStride
                    val vIdx = uvY * vPlane.rowStride + uvX * vPlane.pixelStride

                    val yVal = (yBuffer.get(min(yIdx, yBuffer.limit() - 1)).toInt() and 0xFF)
                    val uVal = (uBuffer.get(min(uIdx, uBuffer.limit() - 1)).toInt() and 0xFF) - 128
                    val vVal = (vBuffer.get(min(vIdx, vBuffer.limit() - 1)).toInt() and 0xFF) - 128

                    val r = (yVal + 1.370705f * vVal).toInt().coerceIn(0, 255)
                    val g = (yVal - 0.337633f * uVal - 0.698001f * vVal).toInt().coerceIn(0, 255)
                    val b = (yVal + 1.732446f * uVal).toInt().coerceIn(0, 255)
                    row[tx] = Color.rgb(r, g, b)
                }
                out.setPixels(row, 0, targetW, 0, ty, targetW, 1)
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun encodeBitmapBase64(bitmap: Bitmap, quality: Int = 60): String {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }
    }
}
