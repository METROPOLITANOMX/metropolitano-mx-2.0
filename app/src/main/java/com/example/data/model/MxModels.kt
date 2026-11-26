package com.example.data.model

import com.squareup.moshi.JsonClass
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

enum class SurfaceType { MX, SM }
enum class LayoutType { MIXED, FAST, TECHNICAL }
enum class RiderStatus { ACTIVE, BLOCKED }

enum class GameMode {
    NORMAL,
    TIME_BOMB_45,
    ELIMINATION_60,
    KING_OF_TRACK,
    CONSISTENCY_DUEL,
    TURBO_SPRINT_3,
    PURSUIT_HUNTER
}
enum class SessionPhase { IDLE, WARMUP, COUNTDOWN_30, RANDOM_GATE, RACING, PAUSED_YELLOW }
enum class FlagState { GREEN, YELLOW }

enum class AntennaStation(val id: String) {
    ANTENA_1("antena_1"),
    ANTENA_2("antena_2"),
    ANTENA_TEMP("antena_temp")
}

enum class DetectionMode { ANY_PASS, FAIRING_COLOR, SINGLE_COLOR }
enum class RoiSize(val widthFrac: Float, val heightFrac: Float) {
    S(0.36f, 0.34f),
    M(0.56f, 0.50f),
    L(0.76f, 0.68f)
}
enum class RoiHeight(val centerYFrac: Float) {
    LOW(0.68f),
    MEDIUM(0.50f),
    HIGH(0.32f)
}
enum class LineOrientation { VERTICAL, HORIZONTAL }
enum class PassingDirection { ANY, LEFT_TO_RIGHT, RIGHT_TO_LEFT, TOP_TO_BOTTOM, BOTTOM_TO_TOP }
enum class AntiVibrationLevel(val minOccupancy: Float, val requiredFrames: Int) {
    HIGH(0.05f, 1),
    MEDIUM(0.11f, 2),
    LOW_WIND(0.19f, 4)
}

@JsonClass(generateAdapter = true)
data class Circuit(
    val id: String = "metropolitano_madrid",
    val name: String = "Metropolitano Circuit (Madrid)",
    val countryCity: String = "España / Madrid",
    val surface: SurfaceType = SurfaceType.MX,
    val lengthMeters: Int = 185,
    val layoutType: LayoutType = LayoutType.MIXED,
    val lat: Double = 40.4495,
    val lng: Double = -3.7265,
    val timezone: String = "Europe/Madrid",
    val gpsRadiusMeters: Int = 450,
    val isOfficial: Boolean = true
)

@JsonClass(generateAdapter = true)
data class RiderAccount(
    val id: String = "",
    val name: String = "",
    val passwordHash: String = "",
    val pin4: String = "",
    val status: RiderStatus = RiderStatus.ACTIVE,
    val totalLaps: Int = 0,
    val bestLapMs: Long = 0L,
    val regularityIndex: Double = 0.0,
    val avgSpeedKmh: Double = 0.0,
    val eq200mMs: Long = 0L,
    val activeBikeId: String = "",
    val preferredSurface: SurfaceType = SurfaceType.MX,
    val createdAt: Long = 0L,
    val lastSeenAt: Long = 0L
)

@JsonClass(generateAdapter = true)
data class BikeSetup(
    val id: String = "",
    val riderId: String = "",
    val riderName: String = "",
    val discipline: SurfaceType = SurfaceType.MX,
    val modelName: String = "Losi Promoto-MX",
    val dorsal: String = "01",
    val totalLaps: Int = 0,
    val usageMinutes: Int = 0,
    val chainMaintAtLaps: Int = 0,
    val gyroMaintAtLaps: Int = 0,
    val forkMaintAtLaps: Int = 0,
    val bearingMaintAtLaps: Int = 0,
    val terrain: String = "Tierra compacta / Hard-pack",
    // Sección 1: Suspensión y Geometría Losi Promoto
    val forkOil: String = "500 cSt / 40wt",
    val forkSpring: String = "Estándar (Medio)",
    val shockOil: String = "600 cSt / 45wt",
    val shockSpring: String = "Estándar (Medio)",
    val rearSagMm: String = "18mm",
    val leanBarSetting: String = "Medio (Estándar)",
    val frontBrakePercent: Int = 75,
    val servoSaverSetting: String = "Medio (3/5)",
    // Sección 2: Electrónica Spektrum MS6X y Firma 85A
    val gyroPercent: Int = 72,
    val gyroMode: String = "Off-Road",
    val escPunch: Int = 5,
    val escDragBrake: Int = 10,
    val escMaxBrake: Int = 75,
    val throttleExpo: Int = 0,
    val lipoCutoff: String = "3.4V/celda",
    // Sección 3: Desarrollo, Neumáticos y LiPo
    val pinionTeeth: Int = 15,
    val spurTeeth: Int = 50,
    val pinionSpur: String = "15T / 50T",
    val frontTire: String = "Dunlop MX53",
    val rearTire: String = "Dunlop MX53",
    val tires: String = "Dunlop MX53",
    val tireInsert: String = "Espuma media",
    val lipoSpecs: String = "2S 5000mAh 50C IC5",
    val lipoCycles: Int = 0,
    // Comunidad & Paddock
    val notes: String = "",
    val setupVotes: Int = 0,
    val votedByRiderIds: List<String> = emptyList(),
    val photoBase64List: List<String> = emptyList(),
    val publishedToPaddock: Boolean = false
) {
    fun gearRatioText(): String =
        String.format(Locale.US, "%.2f:1", spurTeeth.coerceAtLeast(1).toDouble() / pinionTeeth.coerceAtLeast(1))

    fun effectivePinionSpur(): String =
        if (pinionTeeth > 0 && spurTeeth > 0) "${pinionTeeth}T / ${spurTeeth}T (${gearRatioText()})"
        else pinionSpur

    fun buildSetupSummary(): String =
        "${discipline.name} • MS6X $gyroPercent% ($gyroMode) • $forkOil / $shockOil • ${effectivePinionSpur()} • $frontTire"

    companion object {
        fun defaultsFor(surface: SurfaceType): BikeSetup = if (surface == SurfaceType.SM) {
            BikeSetup(
                discipline = SurfaceType.SM,
                modelName = "Losi Promoto-SM",
                terrain = "Asfalto / Supermotard",
                forkOil = "650 cSt / 50wt",
                forkSpring = "Duro (Supermoto)",
                shockOil = "700 cSt / 55wt",
                shockSpring = "Duro (Asfalto)",
                rearSagMm = "14mm",
                leanBarSetting = "Bajo (Ángulo SM)",
                frontBrakePercent = 90,
                servoSaverSetting = "Rígido (4/5)",
                gyroPercent = 65,
                gyroMode = "On-Road / Wheelie",
                escPunch = 7,
                escDragBrake = 15,
                escMaxBrake = 85,
                throttleExpo = 5,
                lipoCutoff = "3.4V/celda",
                pinionTeeth = 16,
                spurTeeth = 50,
                pinionSpur = "16T / 50T",
                frontTire = "Pro-Line Supermoto Slick",
                rearTire = "Pro-Line Supermoto Slick",
                tires = "Pro-Line Supermoto Slick",
                tireInsert = "Espuma firme asfalto",
                lipoSpecs = "2S 5200mAh 60C IC5"
            )
        } else {
            BikeSetup(
                discipline = SurfaceType.MX,
                modelName = "Losi Promoto-MX",
                terrain = "Tierra compacta / Hard-pack",
                forkOil = "500 cSt / 40wt",
                forkSpring = "Estándar (Medio)",
                shockOil = "600 cSt / 45wt",
                shockSpring = "Estándar (Medio)",
                rearSagMm = "18mm",
                leanBarSetting = "Medio (Estándar)",
                frontBrakePercent = 75,
                servoSaverSetting = "Medio (3/5)",
                gyroPercent = 72,
                gyroMode = "Off-Road",
                escPunch = 5,
                escDragBrake = 10,
                escMaxBrake = 75,
                throttleExpo = 0,
                lipoCutoff = "3.4V/celda",
                pinionTeeth = 15,
                spurTeeth = 50,
                pinionSpur = "15T / 50T",
                frontTire = "Dunlop MX53",
                rearTire = "Dunlop MX53",
                tires = "Dunlop MX53",
                tireInsert = "Espuma media",
                lipoSpecs = "2S 5000mAh 50C IC5"
            )
        }
    }
}

@JsonClass(generateAdapter = true)
data class PaAnnouncement(
    val id: String = "",
    val textEs: String = "",
    val textEn: String = "",
    val timestampMs: Long = 0L,
    val isHotlap: Boolean = false
)

@JsonClass(generateAdapter = true)
data class LapRecord(
    val id: String = "",
    val circuitId: String = "metropolitano_madrid",
    val riderId: String = "",
    val riderName: String = "",
    val dorsal: String = "01",
    val bikeModel: String = "Losi Promoto-MX",
    val discipline: SurfaceType = SurfaceType.MX,
    val lapTimeMs: Long = 0L,
    val deltaMs: Long = 0L,
    val speedKmh: Double = 0.0,
    val timestampMs: Long = 0L,
    val antennaId: String = "manual",
    val detectedColors: List<String> = emptyList(),
    val dayKey: String = ""
)

@JsonClass(generateAdapter = true)
data class RoomState(
    val circuitId: String = "metropolitano_madrid",
    val gameMode: GameMode = GameMode.NORMAL,
    val sessionPhase: SessionPhase = SessionPhase.IDLE,
    val flagState: FlagState = FlagState.GREEN,
    val heatDurationSec: Int = 300,
    val phaseStartTimeMs: Long = 0L,
    val phaseEndTimeMs: Long = 0L,
    val gateDropTimeMs: Long = 0L,
    val friendlyModeActive: Boolean = false,
    val lipoWarnMinutes: Int = 12,
    val antiVibration: AntiVibrationLevel = AntiVibrationLevel.MEDIUM,
    val weeklyHotlapRider: String = "",
    val weeklyHotlapMs: Long = 0L,
    val mxRecordRider: String = "",
    val mxRecordMs: Long = 0L,
    val smRecordRider: String = "",
    val smRecordMs: Long = 0L,
    val lastPaAnnouncement: PaAnnouncement? = null,
    val lastAiVerdict: AiStewardVerdict? = null,
    val activeRiders: Map<String, ActiveRoomRider> = emptyMap(),
    val updatedAtMs: Long = 0L
)

@JsonClass(generateAdapter = true)
data class AiStewardVerdict(
    val id: String = "",
    val matchedRiderId: String = "",
    val riderName: String = "",
    val detectedDorsal: String = "",
    val fairingSummary: String = "",
    val confidencePercent: Int = 0,
    val isRealPass: Boolean = true,
    val reasoningEs: String = "",
    val reasoningEn: String = "",
    val timestampMs: Long = 0L
)

@JsonClass(generateAdapter = true)
data class ActiveRoomRider(
    val riderId: String = "",
    val riderName: String = "",
    val dorsal: String = "01",
    val bikeModel: String = "Losi Promoto-MX",
    val discipline: SurfaceType = SurfaceType.MX,
    val lapsCount: Int = 0,
    val bestLapMs: Long = 0L,
    val lastLapMs: Long = 0L,
    val lastPassTimestampMs: Long = 0L,
    val setupSummary: String = "",
    val colorConfirmed: Boolean = false,
    val eliminated: Boolean = false,
    val sessionStartMs: Long = 0L
)

@JsonClass(generateAdapter = true)
data class AntennaConfig(
    val antennaId: String = AntennaStation.ANTENA_1.id,
    val circuitId: String = "metropolitano_madrid",
    val status: String = "WAITING_CALIBRATION",
    val isCalibrated: Boolean = false,
    val calibrateCommandAtMs: Long = 0L,
    val detectionMode: DetectionMode = DetectionMode.ANY_PASS,
    val roiSize: RoiSize = RoiSize.M,
    val roiHeight: RoiHeight = RoiHeight.MEDIUM,
    val lineOrientation: LineOrientation = LineOrientation.VERTICAL,
    val linePosition: Float = 0.42f,
    val colorZoneX: Float = 0.70f,
    val colorZoneY: Float = 0.50f,
    val singleTargetColorName: String = "",
    val directionFilter: PassingDirection = PassingDirection.ANY,
    val aiStewardEnabled: Boolean = true,
    val antiVibration: AntiVibrationLevel = AntiVibrationLevel.MEDIUM,
    val lastSnapshotBase64: String = "",
    val lastPhotoFinishBase64: String = "",
    val lastDetectedColors: List<String> = emptyList(),
    val lastDetectedHexes: List<String> = emptyList(),
    val lastDetectedLab: List<LabSample> = emptyList(),
    val lastDetectedDorsal: String = "",
    val lastConfidence: Float = 0f,
    val activeFps: Int = 30,
    val lastTriggerTimestampMs: Long = 0L,
    val matchedRiderId: String = "",
    val matchedRiderName: String = "",
    val lastHeartbeatMs: Long = 0L
)

@JsonClass(generateAdapter = true)
data class LabSample(
    val l: Double = 0.0,
    val a: Double = 0.0,
    val b: Double = 0.0,
    val hex: String = "#FFFFFF",
    val colorName: String = "Blanco"
)

fun predefinedMotoColors(isSpanish: Boolean = true): List<LabSample> = listOf(
    LabSample(l = 53.2, a = 80.1, b = 67.2, hex = "#E53935", colorName = if (isSpanish) "Rojo" else "Red"),
    LabSample(l = 48.0, a = 24.0, b = -62.0, hex = "#1E88E5", colorName = if (isSpanish) "Azul" else "Blue"),
    LabSample(l = 48.5, a = -46.0, b = 36.0, hex = "#2E7D32", colorName = if (isSpanish) "Verde" else "Green"),
    LabSample(l = 87.7, a = -79.2, b = 80.9, hex = "#76FF03", colorName = if (isSpanish) "Verde Flúor" else "Neon Green"),
    LabSample(l = 86.0, a = -6.0, b = 82.0, hex = "#FDD835", colorName = if (isSpanish) "Amarillo" else "Yellow"),
    LabSample(l = 67.0, a = 42.0, b = 72.0, hex = "#FB8C00", colorName = if (isSpanish) "Naranja" else "Orange"),
    LabSample(l = 59.0, a = 63.0, b = -6.0, hex = "#EC407A", colorName = if (isSpanish) "Rosa" else "Pink"),
    LabSample(l = 39.0, a = 54.0, b = -46.0, hex = "#8E24AA", colorName = if (isSpanish) "Morado" else "Purple"),
    LabSample(l = 64.0, a = -36.0, b = -22.0, hex = "#00ACC1", colorName = if (isSpanish) "Cian" else "Cyan"),
    LabSample(l = 96.0, a = 0.0, b = 0.0, hex = "#F8FAFC", colorName = if (isSpanish) "Blanco" else "White"),
    LabSample(l = 12.0, a = 0.0, b = 0.0, hex = "#18181B", colorName = if (isSpanish) "Negro" else "Black"),
    LabSample(l = 62.0, a = 0.0, b = 0.0, hex = "#94A3B8", colorName = if (isSpanish) "Gris" else "Gray")
)

@JsonClass(generateAdapter = true)
data class RiderColorProfile(
    val riderId: String = "",
    val riderName: String = "",
    val samples: List<LabSample> = emptyList(),
    val confirmed: Boolean = false
)

@JsonClass(generateAdapter = true)
data class CapturedPassSample(
    val id: String = "",
    val timestampMs: Long = 0L,
    val antennaId: String = "antena_1",
    val samples: List<LabSample> = emptyList(),
    val snapshotBase64: String = "",
    val detectedDorsal: String = "",
    val aiSummary: String = ""
)

@JsonClass(generateAdapter = true)
data class ChampionshipStanding(
    val riderId: String = "",
    val riderName: String = "",
    val dorsal: String = "01",
    val points: Int = 0,
    val heatsRaced: Int = 0,
    val wins: Int = 0,
    val bestLapMs: Long = 0L
)

@JsonClass(generateAdapter = true)
data class Championship(
    val id: String = "",
    val circuitId: String = "metropolitano_madrid",
    val name: String = "",
    val discipline: SurfaceType = SurfaceType.MX,
    val totalHeats: Int = 3,
    val completedHeats: Int = 0,
    val standings: Map<String, ChampionshipStanding> = emptyMap(),
    val createdAtMs: Long = 0L
)

val MXGP_POINTS_TABLE = listOf(25, 22, 20, 18, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1)

@JsonClass(generateAdapter = true)
data class FanInteractionState(
    val bets: Map<String, String> = emptyMap(),
    val mvpVotes: Map<String, Int> = emptyMap(),
    val ovationCount: Int = 0,
    val cheerMessages: List<CheerMessage> = emptyList(),
    val paClipBase64: String = "",
    val paClipTimestampMs: Long = 0L
)

@JsonClass(generateAdapter = true)
data class CheerMessage(
    val id: String = "",
    val fanName: String = "",
    val targetRider: String = "",
    val text: String = "",
    val timestampMs: Long = 0L
)

object MxConstants {
    /**
     * Correo oficial del circuito fijado de forma inmutable en el código compilado
     * (no visible ni modificable desde la app ni desde Dirección).
     */
    const val OFFICIAL_CLUB_EMAIL = "circuito@metropolitanomx.es"
    const val DEFAULT_APK_URL = "https://metropolitanomx.github.io/metropolitano-mx-2.0/"
}

@JsonClass(generateAdapter = true)
data class AppReport(
    val id: String = "",
    val senderName: String = "",
    val type: String = "BUG",
    val message: String = "",
    val circuitRequested: String = "",
    val timestampMs: Long = 0L
)

@JsonClass(generateAdapter = true)
data class InviteDownloadCode(
    val code: String = "",
    val circuitName: String = "",
    val note: String = "",
    val promoteOfficial: Boolean = true,
    val active: Boolean = true,
    val redeemedCount: Int = 0,
    val createdAtMs: Long = 0L
)

@JsonClass(generateAdapter = true)
data class GlobalConfig(
    val registrationOpen: Boolean = true,
    val directorPasswordHash: String = sha256("MXPRO2026"),
    val directorCustomPasswordSet: Boolean = false,
    val apkDownloadLocked: Boolean = false,
    val apkDownloadUrl: String = MxConstants.DEFAULT_APK_URL,
    val accessCodes: Map<String, InviteDownloadCode> = emptyMap(),
    val visitCount: Int = 0,
    val lastVisits: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class GarageBackupPayload(
    val version: Int = 2,
    val exportedAtMs: Long = 0L,
    val riderName: String = "",
    val riderProfile: RiderAccount? = null,
    val bikes: List<BikeSetup> = emptyList()
)

fun sha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

fun formatLapMs(ms: Long): String {
    if (ms <= 0L) return "--.--"
    val totalSec = ms / 1000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    val millis = (ms % 1000L) / 10L
    return if (mins > 0) {
        String.format(Locale.US, "%d:%02d.%02d", mins, secs, millis)
    } else {
        String.format(Locale.US, "%02d.%02d", secs, millis)
    }
}

fun formatLapForSpeech(ms: Long, isSpanish: Boolean): String {
    if (ms <= 0L) return ""
    val totalSec = ms / 1000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    val hundredths = (ms % 1000L) / 10L
    return if (isSpanish) {
        if (mins > 0) {
            val minWord = if (mins == 1L) "minuto" else "minutos"
            "$mins $minWord $secs con ${String.format(Locale.US, "%02d", hundredths)}"
        } else {
            "$secs segundos ${String.format(Locale.US, "%02d", hundredths)}"
        }
    } else {
        if (mins > 0) {
            val minWord = if (mins == 1L) "minute" else "minutes"
            "$mins $minWord $secs point ${String.format(Locale.US, "%02d", hundredths)}"
        } else {
            "$secs point ${String.format(Locale.US, "%02d", hundredths)} seconds"
        }
    }
}

fun formatDeltaMs(deltaMs: Long): String {
    if (deltaMs == 0L) return "0.0s"
    val sign = if (deltaMs > 0) "+" else "-"
    val absSec = abs(deltaMs) / 1000.0
    return String.format(Locale.US, "%s%.2fs", sign, absSec)
}

fun computeEq200mMs(bestLapMs: Long, trackLengthMeters: Int): Long {
    if (bestLapMs <= 0L || trackLengthMeters <= 0) return 0L
    return (bestLapMs * 200.0 / trackLengthMeters.toDouble()).toLong()
}

fun computeSpeedKmh(lapMs: Long, trackLengthMeters: Int): Double {
    if (lapMs <= 0L || trackLengthMeters <= 0) return 0.0
    val seconds = lapMs / 1000.0
    return (trackLengthMeters / seconds) * 3.6
}

fun computeRegularityIndex(laps: List<Long>): Double {
    if (laps.size < 2) return if (laps.size == 1) 100.0 else 0.0
    val avg = laps.average()
    if (avg <= 0.0) return 0.0
    val variance = laps.map { (it - avg) * (it - avg) }.average()
    val stdDev = kotlin.math.sqrt(variance)
    val cv = (stdDev / avg) * 100.0
    return (100.0 - cv).coerceIn(0.0, 100.0)
}

fun computeRankTitle(totalLaps: Int, isSpanish: Boolean): String = when {
    totalLaps >= 500 -> if (isSpanish) "Leyenda MX" else "MX Legend"
    totalLaps >= 150 -> "Pro Rider"
    totalLaps >= 40 -> "Amateur"
    else -> "Rookie"
}
