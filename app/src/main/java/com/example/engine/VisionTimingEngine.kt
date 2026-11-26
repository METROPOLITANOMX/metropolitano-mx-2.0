package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Base64
import androidx.camera.core.ImageProxy
import com.example.data.model.AntennaConfig
import com.example.data.model.DetectionMode
import com.example.data.model.LabSample
import com.example.data.model.LineOrientation
import com.example.data.model.PassingDirection
import com.example.data.model.RiderColorProfile
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

data class VisionDetectionResult(
    val triggered: Boolean,
    val triggerTimestampMs: Long,
    val detectedColors: List<LabSample>,
    val matchedProfile: RiderColorProfile?,
    val matchConfidence: Float,
    val detectedDorsal: String,
    val estimatedFps: Int,
    val occupancyRatio: Float,
    val snapshotBase64: String,
    val photoFinishBase64: String?
)

/**
 * Real-time Computer Vision Engine 2.0 for RC 1/4 Finish Line Antennae:
 * 1. ROI "Tunnel Effect" clipping + directional motion gate (rejects reverse traffic).
 * 2. Adaptive EMA Background Model (automatically tracks gradual cloud/sunlight changes) + shadow suppression.
 * 3. Sub-frame linear velocity interpolation (reduces frame quantization error).
 * 4. Multi-frame fairing pixel accumulation matched in perceptual CIE Lab chroma space + confidence margin.
 * 5. Trigger-only HD crop (360x270) + synchronous on-device ML Kit Dorsal OCR fallback.
 */
class VisionTimingEngine {

    private var baselineR: FloatArray? = null
    private var baselineG: FloatArray? = null
    private var baselineB: FloatArray? = null
    private var baseW = 0
    private var baseH = 0

    private var consecutiveMotionFrames = 0
    private var prevOccupancy = 0f
    private var cooldownActive = false
    private var lastTriggerMs = 0L

    // FPS tracking & motion centroid for direction lock
    private var lastFrameMonoMs = 0L
    private var emaFrameIntervalMs = 33f
    private var entryCentroidX = -1f
    private var entryCentroidY = -1f
    private var lastCentroidX = -1f
    private var lastCentroidY = -1f

    // Trigger & Color Separation accumulation state
    private var accumulatingFairing = false
    private var frozenTriggerMs = 0L
    private var frozenPhotoFinishB64: String? = null
    private var frozenDorsalHint = ""
    private var accumulationFramesLeft = 0
    private val accumulatedRgb = mutableListOf<Int>()

    private var calibrationPending = false

    private val ocrClient by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun requestLocalCalibration() {
        calibrationPending = true
        consecutiveMotionFrames = 0
        prevOccupancy = 0f
        cooldownActive = false
        accumulatingFairing = false
        entryCentroidX = -1f
        entryCentroidY = -1f
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
        val nowMono = SystemClock.elapsedRealtime()
        if (lastFrameMonoMs > 0L) {
            val dt = (nowMono - lastFrameMonoMs).coerceIn(8L, 250L).toFloat()
            emaFrameIntervalMs = emaFrameIntervalMs * 0.85f + dt * 0.15f
        }
        lastFrameMonoMs = nowMono
        val currentFps = (1000f / emaFrameIntervalMs).toInt().coerceIn(5, 120)

        val rgbBitmap = imageProxyToScaledBitmap(image, maxDim = 240) ?: return null
        val w = rgbBitmap.width
        val h = rgbBitmap.height
        val pixels = IntArray(w * h)
        rgbBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        if (calibrationPending || baselineR == null || baseW != w || baseH != h) {
            if (calibrationPending) {
                baselineR = FloatArray(w * h) { Color.red(pixels[it]).toFloat() }
                baselineG = FloatArray(w * h) { Color.green(pixels[it]).toFloat() }
                baselineB = FloatArray(w * h) { Color.blue(pixels[it]).toFloat() }
                baseW = w
                baseH = h
                calibrationPending = false
            }
            val snap = if (captureLiveSnapshot) encodeBitmapBase64(rgbBitmap, 55) else ""
            return VisionDetectionResult(
                triggered = false,
                triggerTimestampMs = 0L,
                detectedColors = emptyList(),
                matchedProfile = null,
                matchConfidence = 0f,
                detectedDorsal = "",
                estimatedFps = currentFps,
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

        // Trigger line strip inside ROI + motion centroid
        val lineStripHalf = 3
        val linePixelsTotal: Int
        var lineForegroundCount = 0
        var sumX = 0f
        var sumY = 0f

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
                        sumX += x
                        sumY += y
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
                        sumX += x
                        sumY += y
                    }
                }
            }
        }

        val occupancy = if (linePixelsTotal > 0) lineForegroundCount.toFloat() / linePixelsTotal else 0f
        if (lineForegroundCount > 0) {
            val cx = sumX / lineForegroundCount
            val cy = sumY / lineForegroundCount
            if (entryCentroidX < 0f) {
                entryCentroidX = cx
                entryCentroidY = cy
            }
            lastCentroidX = cx
            lastCentroidY = cy
        }

        // Accumulate rider body / fairing pixels across subsequent frames after crossing line
        if (accumulatingFairing) {
            var roiSumX = 0f
            var roiSumY = 0f
            var roiFgCount = 0
            for (y in roiTop until roiBottom step 2) {
                for (x in roiLeft until roiRight step 2) {
                    val idx = y * w + x
                    if (isForeground(pixels[idx], bR[idx], bG[idx], bB[idx])) {
                        accumulatedRgb.add(pixels[idx])
                        roiSumX += x
                        roiSumY += y
                        roiFgCount++
                    }
                }
            }
            if (roiFgCount > 0) {
                lastCentroidX = roiSumX / roiFgCount
                lastCentroidY = roiSumY / roiFgCount
            }
            accumulationFramesLeft--
            if (accumulationFramesLeft <= 0) {
                accumulatingFairing = false
                val singleOnly = config.detectionMode == DetectionMode.SINGLE_COLOR
                val colors = extractDominantLabColors(accumulatedRgb, isSpanish, singleOnly = singleOnly)
                val (matched, confidence) = matchRiderProfileWithConfidence(colors, profiles)
                val snap = encodeBitmapBase64(rgbBitmap, 58)
                val dorsalOut = frozenDorsalHint
                val pfB64 = frozenPhotoFinishB64
                accumulatedRgb.clear()
                entryCentroidX = -1f
                entryCentroidY = -1f
                return VisionDetectionResult(
                    triggered = true,
                    triggerTimestampMs = frozenTriggerMs,
                    detectedColors = colors,
                    matchedProfile = matched,
                    matchConfidence = confidence,
                    detectedDorsal = dorsalOut,
                    estimatedFps = currentFps,
                    occupancyRatio = occupancy,
                    snapshotBase64 = snap,
                    photoFinishBase64 = pfB64
                )
            }
        }

        // Adaptive EMA background update when track is calm (handles passing clouds & light drift)
        if (!accumulatingFairing && !cooldownActive && occupancy < config.antiVibration.minOccupancy * 0.35f) {
            entryCentroidX = -1f
            entryCentroidY = -1f
            val alpha = 0.025f
            val inv = 1f - alpha
            for (y in roiTop until roiBottom) {
                val rowOff = y * w
                for (x in roiLeft until roiRight) {
                    val i = rowOff + x
                    val p = pixels[i]
                    bR[i] = bR[i] * inv + Color.red(p) * alpha
                    bG[i] = bG[i] * inv + Color.green(p) * alpha
                    bB[i] = bB[i] * inv + Color.blue(p) * alpha
                }
            }
        }

        // Refractory cooldown until line clears
        if (cooldownActive) {
            if (occupancy < config.antiVibration.minOccupancy * 0.45f && syncedNowMs - lastTriggerMs > 900L) {
                cooldownActive = false
            }
            prevOccupancy = occupancy
            val snap = if (captureLiveSnapshot) encodeBitmapBase64(rgbBitmap, 48) else ""
            return VisionDetectionResult(
                triggered = false,
                triggerTimestampMs = 0L,
                detectedColors = emptyList(),
                matchedProfile = null,
                matchConfidence = 0f,
                detectedDorsal = "",
                estimatedFps = currentFps,
                occupancyRatio = occupancy,
                snapshotBase64 = snap,
                photoFinishBase64 = null
            )
        }

        if (occupancy >= config.antiVibration.minOccupancy) {
            consecutiveMotionFrames++
        } else {
            consecutiveMotionFrames = 0
            if (occupancy < config.antiVibration.minOccupancy * 0.3f) {
                entryCentroidX = -1f
                entryCentroidY = -1f
            }
        }

        val thresholdFrames = config.antiVibration.requiredFrames
        if (consecutiveMotionFrames >= thresholdFrames && !accumulatingFairing) {
            // Directional sanity check
            if (!passesDirectionFilter(config.directionFilter)) {
                consecutiveMotionFrames = 0
                prevOccupancy = occupancy
                val snap = if (captureLiveSnapshot) encodeBitmapBase64(rgbBitmap, 48) else ""
                return VisionDetectionResult(
                    triggered = false,
                    triggerTimestampMs = 0L,
                    detectedColors = emptyList(),
                    matchedProfile = null,
                    matchConfidence = 0f,
                    detectedDorsal = "",
                    estimatedFps = currentFps,
                    occupancyRatio = occupancy,
                    snapshotBase64 = snap,
                    photoFinishBase64 = null
                )
            }

            consecutiveMotionFrames = 0
            cooldownActive = true
            lastTriggerMs = syncedNowMs

            // Sub-frame crossing timestamp interpolation (~4ms accuracy)
            val occSlope = (occupancy - prevOccupancy).coerceAtLeast(0.05f)
            val overshootFrac = ((occupancy - config.antiVibration.minOccupancy) / occSlope).coerceIn(0f, 1f)
            val subFrameOffsetMs = ((1f - overshootFrac) * (emaFrameIntervalMs * 0.55f)).toLong().coerceIn(0L, 26L)
            val exactCrossingMs = syncedNowMs - subFrameOffsetMs

            // Extract HD Photo-Finish & run fast local ML Kit dorsal OCR ONLY on trigger
            val hdBitmap = imageProxyToScaledBitmap(image, maxDim = 420) ?: rgbBitmap
            val dorsalDetected = extractDorsalOnDevice(hdBitmap)
            val hdPhotoFinishB64 = encodeBitmapBase64(hdBitmap, 74)

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

            when (config.detectionMode) {
                DetectionMode.FAIRING_COLOR, DetectionMode.SINGLE_COLOR -> {
                    frozenTriggerMs = exactCrossingMs
                    frozenPhotoFinishB64 = hdPhotoFinishB64
                    frozenDorsalHint = dorsalDetected
                    accumulatedRgb.clear()
                    accumulatedRgb.addAll(roiFg)
                    accumulatingFairing = true
                    accumulationFramesLeft = if (config.detectionMode == DetectionMode.SINGLE_COLOR) 2 else 3
                }
                DetectionMode.ANY_PASS -> {
                    val colors = extractDominantLabColors(roiFg, isSpanish, singleOnly = false)
                    val (matched, confidence) = matchRiderProfileWithConfidence(colors, profiles)
                    prevOccupancy = occupancy
                    return VisionDetectionResult(
                        triggered = true,
                        triggerTimestampMs = exactCrossingMs,
                        detectedColors = colors,
                        matchedProfile = matched,
                        matchConfidence = confidence,
                        detectedDorsal = dorsalDetected,
                        estimatedFps = currentFps,
                        occupancyRatio = occupancy,
                        snapshotBase64 = hdPhotoFinishB64,
                        photoFinishBase64 = hdPhotoFinishB64
                    )
                }
            }
        }

        prevOccupancy = occupancy
        val snap = if (captureLiveSnapshot) encodeBitmapBase64(rgbBitmap, 55) else ""
        return VisionDetectionResult(
            triggered = false,
            triggerTimestampMs = 0L,
            detectedColors = emptyList(),
            matchedProfile = null,
            matchConfidence = 0f,
            detectedDorsal = "",
            estimatedFps = currentFps,
            occupancyRatio = occupancy,
            snapshotBase64 = snap,
            photoFinishBase64 = null
        )
    }

    private fun passesDirectionFilter(filter: PassingDirection): Boolean {
        if (filter == PassingDirection.ANY || entryCentroidX < 0f) return true
        val dx = lastCentroidX - entryCentroidX
        val dy = lastCentroidY - entryCentroidY
        return when (filter) {
            PassingDirection.ANY -> true
            PassingDirection.LEFT_TO_RIGHT -> dx >= -2.5f
            PassingDirection.RIGHT_TO_LEFT -> dx <= 2.5f
            PassingDirection.TOP_TO_BOTTOM -> dy >= -2.5f
            PassingDirection.BOTTOM_TO_TOP -> dy <= 2.5f
        }
    }

    /**
     * Shadow-resistant foreground detector:
     * Rejects mild luminance-only shadows where R, G, B darken equally unless total change is large.
     */
    private fun isForeground(pixel: Int, baseR: Float, baseG: Float, baseB: Float): Boolean {
        val dr = Color.red(pixel) - baseR
        val dg = Color.green(pixel) - baseG
        val db = Color.blue(pixel) - baseB
        val totalDiff = abs(dr) + abs(dg) + abs(db)
        if (totalDiff > 108f) return true
        if (totalDiff < 62f) return false
        val chromaShift = abs(dr - dg) + abs(dg - db)
        return chromaShift > 22f
    }

    private fun extractDorsalOnDevice(bitmap: Bitmap): String {
        return try {
            val input = InputImage.fromBitmap(bitmap, 0)
            val text = Tasks.await(ocrClient.process(input), 110L, TimeUnit.MILLISECONDS)
            val regex = Regex("""\b\d{1,3}\b""")
            val candidates = regex.findAll(text.text).map { it.value }.toList()
            candidates.firstOrNull { it.isNotBlank() } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractDominantLabColors(
        rgbPixels: List<Int>,
        isSpanish: Boolean,
        singleOnly: Boolean = false
    ): List<LabSample> {
        if (rgbPixels.isEmpty()) return emptyList()
        val buckets = mutableMapOf<String, MutableList<Int>>()
        for (c in rgbPixels) {
            val lab = rgbToLab(c)
            val label = classifyLabColorName(lab.first, lab.second, lab.third, isSpanish)
            buckets.getOrPut(label) { mutableListOf() }.add(c)
        }
        val neutralLabels = setOf("Negro", "Black", "Gris", "Gray", "Blanco", "White")
        val ordered = if (singleOnly) {
            val vibrant = buckets.entries.filter { it.key !in neutralLabels && it.value.size >= 6 }
            if (vibrant.isNotEmpty()) {
                vibrant.sortedByDescending { it.value.size }.take(1)
            } else {
                buckets.entries.sortedByDescending { it.value.size }.take(1)
            }
        } else {
            buckets.entries.sortedByDescending { it.value.size }.take(3)
        }
        return ordered.map { (name, list) ->
            val avgR = list.map { Color.red(it) }.average().toInt().coerceIn(0, 255)
            val avgG = list.map { Color.green(it) }.average().toInt().coerceIn(0, 255)
            val avgB = list.map { Color.blue(it) }.average().toInt().coerceIn(0, 255)
            val avgColor = Color.rgb(avgR, avgG, avgB)
            val (l, a, b) = rgbToLab(avgColor)
            val hex = String.format("#%02X%02X%02X", avgR, avgG, avgB)
            LabSample(l = l, a = a, b = b, hex = hex, colorName = name)
        }
    }

    private fun matchRiderProfileWithConfidence(
        detected: List<LabSample>,
        profiles: List<RiderColorProfile>
    ): Pair<RiderColorProfile?, Float> {
        if (detected.isEmpty() || profiles.isEmpty()) return null to 0f
        val scored = mutableListOf<Pair<RiderColorProfile, Double>>()
        for (profile in profiles) {
            if (profile.samples.isEmpty()) continue
            var totalDist = 0.0
            for (d in detected) {
                val minSampleDist = profile.samples.minOf { s ->
                    val dl = (d.l - s.l) * 0.35
                    val da = d.a - s.a
                    val db = d.b - s.b
                    sqrt(dl * dl + da * da + db * db)
                }
                totalDist += minSampleDist
            }
            scored += profile to (totalDist / detected.size)
        }
        if (scored.isEmpty()) return null to 0f
        scored.sortBy { it.second }
        val (bestProfile, bestDist) = scored.first()
        if (bestDist >= 36.0) return null to 0f

        val closeness = ((36.0 - bestDist) / 36.0).toFloat().coerceIn(0f, 1f)
        val marginBoost = if (scored.size >= 2) {
            val secondDist = scored[1].second
            ((secondDist - bestDist) / 20.0).toFloat().coerceIn(0f, 0.35f)
        } else 0.28f
        val confidence = (closeness * 0.65f + marginBoost).coerceIn(0.1f, 0.99f)
        return bestProfile to confidence
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

    private fun imageProxyToScaledBitmap(image: ImageProxy, maxDim: Int): Bitmap? {
        return try {
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val srcW = image.width
            val srcH = image.height
            if (srcW <= 0 || srcH <= 0) return null
            val scale = maxDim.toFloat() / maxOf(srcW, srcH).toFloat()
            val targetW = (srcW * scale).toInt().coerceAtLeast(32)
            val targetH = (srcH * scale).toInt().coerceAtLeast(32)

            val rawBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
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

                    val yVal = (yBuffer.get(yIdx.coerceIn(0, yBuffer.limit() - 1)).toInt() and 0xFF)
                    val uVal = (uBuffer.get(uIdx.coerceIn(0, uBuffer.limit() - 1)).toInt() and 0xFF) - 128
                    val vVal = (vBuffer.get(vIdx.coerceIn(0, vBuffer.limit() - 1)).toInt() and 0xFF) - 128

                    val r = (yVal + 1.370705f * vVal).toInt().coerceIn(0, 255)
                    val g = (yVal - 0.337633f * uVal - 0.698001f * vVal).toInt().coerceIn(0, 255)
                    val b = (yVal + 1.732446f * uVal).toInt().coerceIn(0, 255)
                    row[tx] = Color.rgb(r, g, b)
                }
                rawBmp.setPixels(row, 0, targetW, 0, ty, targetW, 1)
            }

            val rot = image.imageInfo.rotationDegrees
            if (rot != 0) {
                val matrix = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
                rawBmp.recycle()
                rotated
            } else {
                rawBmp
            }
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
