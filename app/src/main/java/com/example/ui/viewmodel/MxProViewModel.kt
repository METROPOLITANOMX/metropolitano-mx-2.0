package com.example.ui.viewmodel

import android.app.Application
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.UserPreferences
import com.example.data.model.ActiveRoomRider
import com.example.data.model.AiStewardVerdict
import com.example.data.model.AntennaConfig
import com.example.data.model.AntennaStation
import com.example.data.model.AntiVibrationLevel
import com.example.data.model.AppReport
import com.example.data.model.BikeSetup
import com.example.data.model.CapturedPassSample
import com.example.data.model.Championship
import com.example.data.model.ChampionshipStanding
import com.example.data.model.CheerMessage
import com.example.data.model.Circuit
import com.example.data.model.DetectionMode
import com.example.data.model.FlagState
import com.example.data.model.GameMode
import com.example.data.model.GarageBackupPayload
import com.example.data.model.InviteDownloadCode
import com.example.data.model.LabSample
import com.example.data.model.LapRecord
import com.example.data.model.LayoutType
import com.example.data.model.LineOrientation
import com.example.data.model.MXGP_POINTS_TABLE
import com.example.data.model.PaAnnouncement
import com.example.data.model.PassingDirection
import com.example.data.model.RiderAccount
import com.example.data.model.RiderColorProfile
import com.example.data.model.RiderStatus
import com.example.data.model.RoiHeight
import com.example.data.model.RoiSize
import com.example.data.model.RoomState
import com.example.data.model.SessionPhase
import com.example.data.model.SurfaceType
import com.example.data.model.computeEq200mMs
import com.example.data.model.computeRegularityIndex
import com.example.data.model.computeSpeedKmh
import com.example.data.model.formatDeltaMs
import com.example.data.model.formatLapForSpeech
import com.example.data.model.formatLapMs
import com.example.data.model.sha256
import com.example.data.repository.MxProRepository
import com.example.engine.AudioOutputRoute
import com.example.engine.GeminiStewardEngine
import com.example.engine.LocalLanAntennaBridge
import com.example.engine.LocationGuard
import com.example.engine.PocketAudioService
import com.example.engine.VisionDetectionResult
import com.example.engine.VoiceAndAudioEngine
import com.example.i18n.StringsProvider
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

class MxProViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = MxProRepository(application)
    private val prefs = UserPreferences(application)
    val voiceEngine = VoiceAndAudioEngine(application)
    private val locationGuard = LocationGuard(application)
    private val aiSteward = GeminiStewardEngine()
    private val lanBridge = LocalLanAntennaBridge()

    private val _uiState = MutableStateFlow(MxUiState())
    val uiState: StateFlow<MxUiState> = _uiState.asStateFlow()

    private var lastOvationTenMilestone = 0
    private var lastSeenCheerId = ""
    private var lastSpokenPaAnnouncementId = ""
    private var lipoAlertFiredForSessionStart = 0L
    private var lastAutoEndedPhaseStartMs = 0L
    private var gateCountdownPhaseStartMs = 0L
    private var lastAnnouncedGateSec = Int.MAX_VALUE
    private var streamJob: Job? = null
    @Volatile
    private var discoveredDirectorLanIp: String? = null
    @Volatile
    private var lastLanProbeMonoMs: Long = 0L

    init {
        viewModelScope.launch {
            val devId = prefs.ensureDeviceId()
            val stored = prefs.prefsFlow.first()
            val cachedRiders = repo.getCachedRiders()
            val cachedLogged = cachedRiders.firstOrNull { it.id == stored.loggedInRiderId }
            _uiState.update {
                it.copy(
                    sunMode = stored.sunMode,
                    languageOverride = stored.languageOverride,
                    rememberedName = stored.rememberedRiderName,
                    rememberedPass = stored.rememberedPassword,
                    rememberMe = stored.rememberMe,
                    rememberedDirectorPass = stored.rememberedDirectorPass,
                    rememberDirectorPass = stored.rememberDirectorPass,
                    installCodeUnlocked = stored.installCodeUnlocked,
                    deviceId = devId,
                    riders = cachedRiders,
                    currentRider = cachedLogged
                )
            }
            voiceEngine.applyLocale(StringsProvider.current(stored.languageOverride).isSpanish)

            val offset = repo.syncClock(devId)
            val circuits = repo.getCircuits()
            val active = circuits.firstOrNull { it.id == stored.activeCircuitId } ?: circuits.first()
            val globalCfg = repo.getGlobalConfig()
            // Log visit cleanly and persist both locally and to Firebase RTDB
            val visitStamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
            val updatedCfg = globalCfg.copy(
                visitCount = globalCfg.visitCount + 1,
                lastVisits = (listOf("$visitStamp (${devId.take(6)})") + globalCfg.lastVisits).take(25)
            )
            repo.saveGlobalConfig(updatedCfg)

            val riders = repo.getRiders()
            val loggedRider = riders.firstOrNull { it.id == stored.loggedInRiderId } ?: cachedLogged

            _uiState.update {
                it.copy(
                    isLoading = false,
                    isOnline = repo.isOnline(),
                    serverTimeOffsetMs = offset,
                    lastSyncRttMs = repo.lastSyncRttMs(),
                    globalConfig = updatedCfg,
                    circuits = circuits,
                    activeCircuit = active,
                    riders = riders.ifEmpty { cachedRiders },
                    currentRider = loggedRider
                )
            }
            refreshLocation()
            syncCircuitData(active.id)
            startCircuitSseStream(active.id)
            repo.flushPendingLaps()
        }

        // Real-time reconciliation loop + periodic 60s clock drift recalibration
        viewModelScope.launch {
            var tick = 0
            while (isActive) {
                delay(3000L)
                tick++
                val state = _uiState.value
                if (tick % 20 == 0 && state.deviceId.isNotBlank()) {
                    val newOffset = repo.syncClock(state.deviceId)
                    _uiState.update {
                        it.copy(
                            serverTimeOffsetMs = newOffset,
                            lastSyncRttMs = repo.lastSyncRttMs()
                        )
                    }
                    repo.flushPendingLaps()
                }
                syncCircuitData(state.activeCircuit.id)
            }
        }

        // High-precision Phase Timer Watcher (Warm-Up 3:00 auto-finish + 30s Gate PA + Race end)
        viewModelScope.launch {
            while (isActive) {
                delay(200L)
                val state = _uiState.value
                val room = state.roomState
                val now = syncedNowMs()
                val es = StringsProvider.current(state.languageOverride).isSpanish
                val canSpeakOnDevice = !state.isAntennaRoleActive && !state.paAudioMuted && state.antennaVoiceEnabled

                // 30-second Start Light second-by-second announcements (20s + 10..5)
                if (room.sessionPhase == SessionPhase.COUNTDOWN_30 && room.phaseEndTimeMs > now) {
                    if (room.phaseStartTimeMs != gateCountdownPhaseStartMs) {
                        gateCountdownPhaseStartMs = room.phaseStartTimeMs
                        lastAnnouncedGateSec = Int.MAX_VALUE
                    }
                    val remSec = ((room.phaseEndTimeMs - now + 200L) / 1000L).toInt().coerceAtLeast(0)
                    if (remSec < lastAnnouncedGateSec) {
                        lastAnnouncedGateSec = remSec
                        val spokenGateCue: String? = when (remSec) {
                            20 -> if (es) "Veinte segundos" else "Twenty seconds"
                            10 -> if (es) "Diez" else "Ten"
                            9 -> if (es) "Nueve" else "Nine"
                            8 -> if (es) "Ocho" else "Eight"
                            7 -> if (es) "Siete" else "Seven"
                            6 -> if (es) "Seis" else "Six"
                            5 -> if (es) "Cinco" else "Five"
                            else -> null
                        }
                        if (spokenGateCue != null && canSpeakOnDevice) {
                            voiceEngine.speak(spokenGateCue, flush = true)
                        }
                    }
                }

                if (room.phaseEndTimeMs > 0L && now >= room.phaseEndTimeMs && room.phaseStartTimeMs != lastAutoEndedPhaseStartMs) {
                    when (room.sessionPhase) {
                        SessionPhase.WARMUP -> {
                            lastAutoEndedPhaseStartMs = room.phaseStartTimeMs
                            val ann = PaAnnouncement(
                                id = "pa_${now}_warmup_end",
                                textEs = "Final de warm-up, pilotos a parrilla",
                                textEn = "End of warm-up, riders to the grid",
                                timestampMs = now
                            )
                            lastSpokenPaAnnouncementId = ann.id
                            val finished = room.copy(
                                sessionPhase = SessionPhase.IDLE,
                                flagState = FlagState.GREEN,
                                phaseEndTimeMs = 0L,
                                friendlyModeActive = false,
                                lastPaAnnouncement = ann,
                                updatedAtMs = now
                            )
                            _uiState.update {
                                it.copy(
                                    roomState = finished,
                                    statusBannerMessage = if (es) "Final de warm-up — ¡Pilotos a parrilla!" else "End of warm-up — Riders to the grid!"
                                )
                            }
                            voiceEngine.playBeep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 450)
                            if (canSpeakOnDevice) {
                                voiceEngine.speak(if (es) ann.textEs else ann.textEn, flush = true)
                            }
                            repo.saveRoom(finished)
                        }
                        SessionPhase.COUNTDOWN_30 -> {
                            lastAutoEndedPhaseStartMs = room.phaseStartTimeMs
                            val ann = PaAnnouncement(
                                id = "pa_${now}_gatedrop",
                                textEs = "¡Salida! ¡Gas!",
                                textEn = "Start! Go!",
                                timestampMs = now
                            )
                            lastSpokenPaAnnouncementId = ann.id
                            val startedRace = room.copy(
                                sessionPhase = SessionPhase.RACING,
                                flagState = FlagState.GREEN,
                                phaseStartTimeMs = now,
                                phaseEndTimeMs = now + room.heatDurationSec * 1000L,
                                gateDropTimeMs = now,
                                lastPaAnnouncement = ann,
                                updatedAtMs = now
                            )
                            _uiState.update { it.copy(roomState = startedRace) }
                            voiceEngine.playBeep(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 450)
                            if (canSpeakOnDevice) {
                                voiceEngine.speak(if (es) ann.textEs else ann.textEn, flush = true)
                            }
                            repo.saveRoom(startedRace)
                        }
                        SessionPhase.RACING -> {
                            lastAutoEndedPhaseStartMs = room.phaseStartTimeMs
                            val ann = PaAnnouncement(
                                id = "pa_${now}_race_end",
                                textEs = "¡Fin de manga! Bandera a cuadros",
                                textEn = "End of heat! Checkered flag",
                                timestampMs = now
                            )
                            lastSpokenPaAnnouncementId = ann.id
                            val finishedRace = room.copy(
                                sessionPhase = SessionPhase.IDLE,
                                flagState = FlagState.GREEN,
                                phaseEndTimeMs = 0L,
                                friendlyModeActive = false,
                                lastPaAnnouncement = ann,
                                updatedAtMs = now
                            )
                            _uiState.update {
                                it.copy(
                                    roomState = finishedRace,
                                    statusBannerMessage = if (es) "¡Fin de manga! Bandera a cuadros" else "Heat finished! Checkered flag"
                                )
                            }
                            voiceEngine.playStadiumApplause()
                            if (canSpeakOnDevice) {
                                voiceEngine.speak(if (es) ann.textEs else ann.textEn, flush = true)
                            }
                            repo.saveRoom(finishedRace)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun startCircuitSseStream(circuitId: String) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            merge(
                repo.observeRoomEvents(circuitId),
                repo.observeAntennaEvents(circuitId)
            ).collect {
                syncCircuitData(circuitId)
            }
        }
    }

    fun recalibrateClock() {
        val devId = _uiState.value.deviceId.ifBlank { "dev" }
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            val offset = repo.syncClock(devId)
            val rtt = repo.lastSyncRttMs()
            _uiState.update {
                it.copy(
                    isOnline = repo.isOnline(),
                    serverTimeOffsetMs = offset,
                    lastSyncRttMs = rtt,
                    statusBannerMessage = if (repo.isOnline()) {
                        if (es) "Reloj sincronizado: offset ${if (offset >= 0) "+" else ""}${offset}ms (RTT ${rtt}ms)"
                        else "Clock synced: offset ${if (offset >= 0) "+" else ""}${offset}ms (RTT ${rtt}ms)"
                    } else {
                        if (es) "Sin conexión — modo caché local" else "Offline — local cache active"
                    }
                )
            }
            repo.flushPendingLaps()
        }
    }

    fun syncedNowMs(): Long = repo.syncedNowMs()

    private suspend fun syncCircuitData(circuitId: String) {
        val room = repo.getRoom(circuitId)
        val laps = repo.getCircuitLaps(circuitId)
        val antennas = repo.getAntennas(circuitId)
        val profiles = repo.getColorProfiles(circuitId)
        val capturedPasses = repo.getCapturedPasses(circuitId).take(8)
        val championships = repo.getChampionships(circuitId)
        val fan = repo.getFanInteraction(circuitId)
        val bikes = repo.getAllBikes()
        val riders = repo.getRiders()
        val globalCfg = repo.getGlobalConfig()

        // Check ovation every 10 taps -> stadium applause
        val tenBlock = fan.ovationCount / 10
        if (tenBlock > lastOvationTenMilestone && fan.ovationCount > 0) {
            lastOvationTenMilestone = tenBlock
            voiceEngine.playStadiumApplause()
        }

        // Check live PA clip (Direct Director Mic)
        if (fan.paClipBase64.isNotBlank() && !_uiState.value.paAudioMuted) {
            voiceEngine.playRemotePaClipIfNew(fan.paClipBase64, fan.paClipTimestampMs)
        }

        // Sync live multi-device PA TTS announcements across Pilots, Director & Fans
        // Dedicated finish-line Antennas stay silent during active heats/races (only transmit to Director)
        val paAnn = room.lastPaAnnouncement
        val suppressAntennaSpeech = _uiState.value.isAntennaRoleActive && room.sessionPhase != SessionPhase.IDLE
        if (paAnn != null && paAnn.id.isNotBlank() && paAnn.id != lastSpokenPaAnnouncementId &&
            syncedNowMs() - paAnn.timestampMs < 15_000L
        ) {
            lastSpokenPaAnnouncementId = paAnn.id
            val esAnn = StringsProvider.current(_uiState.value.languageOverride).isSpanish
            if (!suppressAntennaSpeech && !_uiState.value.paAudioMuted && _uiState.value.antennaVoiceEnabled) {
                if (paAnn.isHotlap) {
                    voiceEngine.playBeep(ToneGenerator.TONE_PROP_ACK, 220)
                }
                voiceEngine.speak(if (esAnn) paAnn.textEs else paAnn.textEn, flush = false)
            }
        }

        // Check newest fan cheer message for TTS
        val latestCheer = fan.cheerMessages.firstOrNull()
        if (latestCheer != null && latestCheer.id != lastSeenCheerId &&
            syncedNowMs() - latestCheer.timestampMs < 15_000L
        ) {
            lastSeenCheerId = latestCheer.id
            val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
            val target = if (latestCheer.targetRider.isNotBlank()) {
                if (es) " para ${latestCheer.targetRider}" else " for ${latestCheer.targetRider}"
            } else ""
            if (!_uiState.value.paAudioMuted) {
                voiceEngine.speak("${latestCheer.fanName}$target: ${latestCheer.text}", flush = false)
            }
        }

        // Check 12-min LiPo battery alert if pilot is riding
        val me = _uiState.value.currentRider
        if (me != null) {
            val activeEntry = room.activeRiders[me.id]
            if (activeEntry != null && activeEntry.sessionStartMs > 0L &&
                activeEntry.sessionStartMs != lipoAlertFiredForSessionStart
            ) {
                val elapsedMin = (syncedNowMs() - activeEntry.sessionStartMs) / 60_000L
                if (elapsedMin >= room.lipoWarnMinutes) {
                    lipoAlertFiredForSessionStart = activeEntry.sessionStartMs
                    val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
                    voiceEngine.playBeep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
                    voiceEngine.speak(
                        if (es) "Atención ${me.name}, doce minutos en pista, revisa la batería LiPo"
                        else "Attention ${me.name}, twelve minutes on track, check LiPo battery"
                    )
                }
            }
        }

        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        val currentBefore = _uiState.value.currentRider
        var forcedLogoutMsg: String? = null
        val refreshedLoggedRider = if (currentBefore != null) {
            val remoteMatch = riders.firstOrNull { it.id == currentBefore.id }
            when {
                remoteMatch != null && remoteMatch.status == RiderStatus.BLOCKED -> {
                    prefs.clearSession()
                    forcedLogoutMsg = if (es) "Cuenta bloqueada por Dirección" else "Account blocked by Director"
                    null
                }
                remoteMatch == null && repo.isOnline() && riders.isNotEmpty() -> {
                    prefs.clearSession()
                    forcedLogoutMsg = if (es) "Cuenta eliminada por Dirección" else "Account deleted by Director"
                    null
                }
                else -> remoteMatch ?: currentBefore
            }
        } else null

        val nowMsForAnt = syncedNowMs()
        val updatedMonoMap = updateAntennaMonoTracker(_uiState.value.antennas, antennas, _uiState.value.antennaLastChangeMonoMs, nowMsForAnt)
        val hasLocalLiveAnt = antennas.any { (id, cfg) ->
            isAntennaActuallyLive(cfg, updatedMonoMap[id] ?: 0L, nowMsForAnt)
        }
        val otherCircAnt = if (!hasLocalLiveAnt) findOtherCircuitLiveAntenna(circuitId, nowMsForAnt) else null

        _uiState.update { state ->
            val activeChampId = state.activeChampionshipId.takeIf { id ->
                championships.any { it.id == id }
            } ?: championships.firstOrNull()?.id.orEmpty()
            val safeGlobalCfg = globalCfg.copy(
                visitCount = maxOf(globalCfg.visitCount, state.globalConfig.visitCount),
                lastVisits = globalCfg.lastVisits.ifEmpty { state.globalConfig.lastVisits }
            )
            val safeRiders = riders.ifEmpty { state.riders }
            state.copy(
                isOnline = repo.isOnline(),
                roomState = room,
                circuitLaps = laps,
                antennas = antennas,
                antennaLastChangeMonoMs = updatedMonoMap,
                otherCircuitOnlineAntenna = otherCircAnt,
                colorProfiles = profiles,
                capturedPasses = capturedPasses,
                championships = championships,
                activeChampionshipId = activeChampId,
                fanState = fan,
                allBikes = bikes.ifEmpty { state.allBikes },
                riders = safeRiders,
                globalConfig = safeGlobalCfg,
                currentRider = refreshedLoggedRider,
                statusBannerMessage = forcedLogoutMsg ?: state.statusBannerMessage
            )
        }
    }

    private fun isAntennaActuallyLive(cfg: AntennaConfig, lastMonoMs: Long, nowMs: Long): Boolean {
        val nowMono = SystemClock.elapsedRealtime()
        val hbOk = cfg.lastHeartbeatMs > 0L && abs(nowMs - cfg.lastHeartbeatMs) < 35_000L
        val monoOk = lastMonoMs > 0L && (nowMono - lastMonoMs) in 0L..25_000L
        return hbOk || monoOk
    }

    private fun updateAntennaMonoTracker(
        prev: Map<String, AntennaConfig>,
        next: Map<String, AntennaConfig>,
        prevMono: Map<String, Long>,
        nowMs: Long
    ): Map<String, Long> {
        val nowMono = SystemClock.elapsedRealtime()
        val out = prevMono.toMutableMap()
        next.forEach { (id, cfg) ->
            val old = prev[id]
            val changed = old == null ||
                cfg.lastHeartbeatMs != old.lastHeartbeatMs ||
                cfg.lastSnapshotBase64 != old.lastSnapshotBase64 ||
                cfg.lastTriggerTimestampMs != old.lastTriggerTimestampMs
            if (changed && (cfg.lastHeartbeatMs > 0L || cfg.lastSnapshotBase64.isNotBlank())) {
                if (cfg.lastHeartbeatMs > 0L && abs(nowMs - cfg.lastHeartbeatMs) < 120_000L) {
                    out[id] = nowMono
                } else if (old != null && (cfg.lastHeartbeatMs != old.lastHeartbeatMs || cfg.lastSnapshotBase64 != old.lastSnapshotBase64)) {
                    out[id] = nowMono
                }
            }
        }
        return out
    }

    private suspend fun findOtherCircuitLiveAntenna(activeCircuitId: String, nowMs: Long): Pair<String, String>? {
        val all = repo.getAllCircuitAntennas()
        all.forEach { (cId, antMap) ->
            if (cId != activeCircuitId) {
                antMap.values.forEach { cfg ->
                    if (cfg.lastHeartbeatMs > 0L && abs(nowMs - cfg.lastHeartbeatMs) < 45_000L) {
                        return cId to cfg.antennaId
                    }
                }
            }
        }
        return null
    }

    fun refreshDirectorDashboard() {
        val circuitId = _uiState.value.activeCircuit.id
        viewModelScope.launch {
            val globalCfg = repo.getGlobalConfig()
            val riders = repo.getRiders()
            val antennas = repo.getAntennas(circuitId)
            val reports = repo.getReports()
            val capturedPasses = repo.getCapturedPasses(circuitId).take(12)
            val bikes = repo.getAllBikes()
            val nowMs = syncedNowMs()
            val updatedMono = updateAntennaMonoTracker(_uiState.value.antennas, antennas, _uiState.value.antennaLastChangeMonoMs, nowMs)
            val hasLocal = antennas.any { (id, cfg) -> isAntennaActuallyLive(cfg, updatedMono[id] ?: 0L, nowMs) }
            val otherCirc = if (!hasLocal) findOtherCircuitLiveAntenna(circuitId, nowMs) else null
            _uiState.update { st ->
                st.copy(
                    isOnline = repo.isOnline(),
                    globalConfig = globalCfg.copy(
                        visitCount = maxOf(globalCfg.visitCount, st.globalConfig.visitCount),
                        lastVisits = globalCfg.lastVisits.ifEmpty { st.globalConfig.lastVisits }
                    ),
                    riders = riders.ifEmpty { st.riders },
                    antennas = antennas,
                    antennaLastChangeMonoMs = updatedMono,
                    otherCircuitOnlineAntenna = otherCirc,
                    reports = reports,
                    capturedPasses = capturedPasses,
                    allBikes = bikes.ifEmpty { st.allBikes }
                )
            }
        }
    }

    fun refreshAntennasAndCaptures() {
        val circuitId = _uiState.value.activeCircuit.id
        viewModelScope.launch {
            val antennas = repo.getAntennas(circuitId)
            val capturedPasses = repo.getCapturedPasses(circuitId).take(12)
            val profiles = repo.getColorProfiles(circuitId)
            val nowMs = syncedNowMs()
            val updatedMono = updateAntennaMonoTracker(_uiState.value.antennas, antennas, _uiState.value.antennaLastChangeMonoMs, nowMs)
            val hasLocal = antennas.any { (id, cfg) -> isAntennaActuallyLive(cfg, updatedMono[id] ?: 0L, nowMs) }
            val otherCirc = if (!hasLocal) findOtherCircuitLiveAntenna(circuitId, nowMs) else null
            _uiState.update {
                it.copy(
                    isOnline = repo.isOnline(),
                    antennas = antennas,
                    antennaLastChangeMonoMs = updatedMono,
                    otherCircuitOnlineAntenna = otherCirc,
                    capturedPasses = capturedPasses,
                    colorProfiles = profiles
                )
            }
        }
    }

    fun clearBannerMessage() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }

    fun toggleSunMode() {
        val next = !_uiState.value.sunMode
        _uiState.update { it.copy(sunMode = next) }
        viewModelScope.launch { prefs.setSunMode(next) }
    }

    fun toggleLanguageOverride() {
        val currentIsEs = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        val nextLang = if (currentIsEs) "en" else "es"
        _uiState.update { it.copy(languageOverride = nextLang) }
        voiceEngine.applyLocale(!currentIsEs)
        viewModelScope.launch { prefs.setLanguageOverride(nextLang) }
    }

    fun refreshLocation() {
        viewModelScope.launch {
            val latLng = locationGuard.fetchCurrentLocation()
            val geo = locationGuard.evaluateCircuitAccess(_uiState.value.activeCircuit, latLng)
            _uiState.update { it.copy(geoStatus = geo) }
        }
    }

    fun selectCircuit(circuit: Circuit) {
        viewModelScope.launch {
            prefs.setActiveCircuitId(circuit.id)
            val geo = locationGuard.evaluateCircuitAccess(circuit, _uiState.value.geoStatus?.let {
                if (it.currentLat != null && it.currentLng != null) it.currentLat to it.currentLng else null
            })
            _uiState.update { it.copy(activeCircuit = circuit, geoStatus = geo) }
            syncCircuitData(circuit.id)
            startCircuitSseStream(circuit.id)
        }
    }

    fun createLocalCircuit(
        name: String,
        countryCity: String,
        surface: SurfaceType,
        lengthMeters: Int,
        layoutType: LayoutType
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val geo = _uiState.value.geoStatus
            val circuit = Circuit(
                id = "local_${UUID.randomUUID().toString().take(8)}",
                name = name.trim(),
                countryCity = countryCity.trim().ifBlank { "Local Track" },
                surface = surface,
                lengthMeters = lengthMeters.coerceIn(30, 1500),
                layoutType = layoutType,
                lat = geo?.currentLat ?: 0.0,
                lng = geo?.currentLng ?: 0.0,
                timezone = java.util.TimeZone.getDefault().id,
                gpsRadiusMeters = 500,
                isOfficial = false
            )
            repo.saveCircuit(circuit)
            val updated = repo.getCircuits()
            _uiState.update { it.copy(circuits = updated) }
            selectCircuit(circuit)
        }
    }

    // --- Rider Authentication & Account Recovery ---
    fun registerRider(name: String, passwordPlain: String, pin4: String, remember: Boolean, onSuccess: () -> Unit) {
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        if (!_uiState.value.globalConfig.registrationOpen) {
            _uiState.update {
                it.copy(statusBannerMessage = if (es) "Registros cerrados por Dirección" else "Registrations closed by Director")
            }
            return
        }
        if (name.isBlank() || passwordPlain.length < 3 || pin4.length != 4) {
            _uiState.update {
                it.copy(statusBannerMessage = if (es) "Completa nombre, clave y PIN de 4 dígitos" else "Enter name, password, and 4-digit PIN")
            }
            return
        }
        viewModelScope.launch {
            val existing = repo.getRiders().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            if (existing != null) {
                _uiState.update {
                    it.copy(statusBannerMessage = if (es) "Ese nombre de piloto ya existe" else "Rider name already exists")
                }
                return@launch
            }
            val riderId = "r_${UUID.randomUUID().toString().take(8)}"
            val bikeId = "b_${UUID.randomUUID().toString().take(8)}"
            val starterBike = BikeSetup.defaultsFor(_uiState.value.activeCircuit.surface).copy(
                id = bikeId,
                riderId = riderId,
                riderName = name.trim(),
                dorsal = (10 + Random.nextInt(89)).toString()
            )
            val account = RiderAccount(
                id = riderId,
                name = name.trim(),
                passwordHash = sha256(passwordPlain),
                pin4 = pin4,
                status = RiderStatus.ACTIVE,
                activeBikeId = bikeId,
                preferredSurface = _uiState.value.activeCircuit.surface,
                createdAt = syncedNowMs(),
                lastSeenAt = syncedNowMs()
            )
            repo.saveBike(starterBike)
            repo.saveRider(account)
            prefs.saveLoginState(account.id, account.name, passwordPlain, remember)
            val riders = repo.getRiders()
            val bikes = repo.getAllBikes()
            _uiState.update {
                it.copy(
                    riders = riders,
                    allBikes = bikes,
                    currentRider = account,
                    rememberedName = if (remember) account.name else "",
                    rememberedPass = if (remember) passwordPlain else "",
                    rememberMe = remember
                )
            }
            enrollRiderInActiveRoom(account, starterBike)
            onSuccess()
        }
    }

    fun signInRider(name: String, passwordPlain: String, remember: Boolean, onSuccess: () -> Unit) {
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            val riders = repo.getRiders()
            val match = riders.firstOrNull {
                it.name.equals(name.trim(), ignoreCase = true) && it.passwordHash == sha256(passwordPlain)
            }
            if (match == null) {
                _uiState.update {
                    it.copy(statusBannerMessage = if (es) "Credenciales incorrectas" else "Invalid credentials")
                }
                return@launch
            }
            if (match.status == RiderStatus.BLOCKED) {
                _uiState.update {
                    it.copy(statusBannerMessage = if (es) "Cuenta bloqueada por Dirección" else "Account blocked by Director")
                }
                return@launch
            }
            val updated = match.copy(lastSeenAt = syncedNowMs())
            repo.saveRider(updated)
            prefs.saveLoginState(updated.id, updated.name, passwordPlain, remember)
            val bikes = repo.getAllBikes()
            val bike = bikes.firstOrNull { it.id == updated.activeBikeId } ?: bikes.firstOrNull { it.riderId == updated.id }
            _uiState.update {
                it.copy(
                    riders = riders,
                    allBikes = bikes,
                    currentRider = updated,
                    rememberedName = if (remember) updated.name else "",
                    rememberedPass = if (remember) passwordPlain else "",
                    rememberMe = remember
                )
            }
            enrollRiderInActiveRoom(updated, bike)
            onSuccess()
        }
    }

    fun recoverPasswordWithPin(name: String, pin4: String, newPassword: String) {
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            val riders = repo.getRiders()
            val match = riders.firstOrNull {
                it.name.equals(name.trim(), ignoreCase = true) && it.pin4 == pin4.trim()
            }
            if (match == null || newPassword.length < 3) {
                _uiState.update {
                    it.copy(statusBannerMessage = if (es) "PIN incorrecto o clave muy corta" else "Wrong PIN or short password")
                }
                return@launch
            }
            val updated = match.copy(passwordHash = sha256(newPassword))
            repo.saveRider(updated)
            _uiState.update {
                it.copy(statusBannerMessage = if (es) "Clave actualizada. Inicia sesión." else "Password updated. Sign in now.")
            }
        }
    }

    fun logoutRider() {
        viewModelScope.launch {
            prefs.clearSession()
            _uiState.update { it.copy(currentRider = null) }
        }
    }

    fun enterTrackAsLoggedRider() {
        val rider = _uiState.value.currentRider ?: return
        val bike = _uiState.value.activeBike
        viewModelScope.launch {
            enrollRiderInActiveRoom(rider, bike)
        }
    }

    private suspend fun enrollRiderInActiveRoom(rider: RiderAccount, bike: BikeSetup?) {
        val circuitId = _uiState.value.activeCircuit.id
        val existing = _uiState.value.roomState.activeRiders[rider.id]
        val entry = existing?.copy(
            dorsal = bike?.dorsal ?: existing.dorsal,
            bikeModel = bike?.modelName ?: existing.bikeModel,
            discipline = bike?.discipline ?: existing.discipline,
            setupSummary = bike?.buildSetupSummary() ?: existing.setupSummary
        ) ?: ActiveRoomRider(
            riderId = rider.id,
            riderName = rider.name,
            dorsal = bike?.dorsal ?: "01",
            bikeModel = bike?.modelName ?: "Losi Promoto-MX",
            discipline = bike?.discipline ?: _uiState.value.activeCircuit.surface,
            setupSummary = bike?.buildSetupSummary() ?: "",
            sessionStartMs = syncedNowMs()
        )
        repo.upsertRoomRider(circuitId, entry)
        syncCircuitData(circuitId)
    }

    // --- Garage Pro, Community Votes & Maintenance ---
    fun saveBikeSetup(bike: BikeSetup, setActive: Boolean = true) {
        viewModelScope.launch {
            val rider = _uiState.value.currentRider ?: return@launch
            val saved = bike.copy(
                riderId = rider.id,
                riderName = rider.name,
                pinionSpur = bike.effectivePinionSpur(),
                tires = bike.frontTire.ifBlank { bike.tires }
            )
            repo.saveBike(saved)
            if (setActive) {
                val updatedRider = rider.copy(activeBikeId = saved.id, preferredSurface = saved.discipline)
                repo.saveRider(updatedRider)
                _uiState.update { it.copy(currentRider = updatedRider) }
                enrollRiderInActiveRoom(updatedRider, saved)
            }
            val bikes = repo.getAllBikes()
            _uiState.update { it.copy(allBikes = bikes) }
        }
    }

    fun voteBikeSetup(bike: BikeSetup) {
        val rider = _uiState.value.currentRider
        val voterKey = rider?.id ?: _uiState.value.deviceId.ifBlank { "guest" }
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        val alreadyVoted = voterKey in bike.votedByRiderIds
        val updatedVoters = if (alreadyVoted) bike.votedByRiderIds - voterKey else bike.votedByRiderIds + voterKey
        val updated = bike.copy(
            setupVotes = updatedVoters.size,
            votedByRiderIds = updatedVoters
        )
        viewModelScope.launch {
            repo.saveBike(updated)
            val bikes = repo.getAllBikes()
            _uiState.update {
                it.copy(
                    allBikes = bikes,
                    statusBannerMessage = if (alreadyVoted) {
                        if (es) "Voto retirado de ${bike.modelName}" else "Vote removed from ${bike.modelName}"
                    } else {
                        if (es) "¡Setup votado! (${updated.setupVotes} ★)" else "Setup voted! (${updated.setupVotes} ★)"
                    }
                )
            }
        }
    }

    fun incrementLipoCycle(bike: BikeSetup) {
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        val updated = bike.copy(lipoCycles = bike.lipoCycles + 1)
        viewModelScope.launch {
            repo.saveBike(updated)
            val bikes = repo.getAllBikes()
            _uiState.update {
                it.copy(
                    allBikes = bikes,
                    statusBannerMessage = if (es) "Ciclo LiPo registrado (#${updated.lipoCycles})" else "LiPo cycle logged (#${updated.lipoCycles})"
                )
            }
        }
    }

    fun resetBikeMaintenance(bike: BikeSetup, type: String) {
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        val updated = when (type) {
            "CHAIN" -> bike.copy(chainMaintAtLaps = bike.totalLaps)
            "GYRO" -> bike.copy(gyroMaintAtLaps = bike.totalLaps)
            "FORK" -> bike.copy(forkMaintAtLaps = bike.totalLaps)
            else -> bike.copy(bearingMaintAtLaps = bike.totalLaps)
        }
        viewModelScope.launch {
            repo.saveBike(updated)
            val bikes = repo.getAllBikes()
            _uiState.update {
                it.copy(
                    allBikes = bikes,
                    statusBannerMessage = if (es) "Mantenimiento registrado a ${bike.totalLaps} vueltas" else "Maintenance logged at ${bike.totalLaps} laps"
                )
            }
        }
    }

    fun deleteBikeSetup(bikeId: String) {
        viewModelScope.launch {
            repo.deleteBike(bikeId)
            val bikes = repo.getAllBikes()
            _uiState.update { it.copy(allBikes = bikes) }
        }
    }

    fun exportGarageBackupJson(): String {
        val rider = _uiState.value.currentRider
        val payload = GarageBackupPayload(
            version = 1,
            exportedAtMs = syncedNowMs(),
            riderName = rider?.name ?: "",
            bikes = _uiState.value.myBikes
        )
        val json = repo.rtdb.moshi.adapter(GarageBackupPayload::class.java).toJson(payload)
        _uiState.update { it.copy(lastBackupJson = json) }
        return json
    }

    fun restoreGarageBackupJson(json: String) {
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            try {
                val payload = repo.rtdb.moshi.adapter(GarageBackupPayload::class.java).fromJson(json)
                val rider = _uiState.value.currentRider
                if (payload == null || rider == null) return@launch
                payload.bikes.forEach { b ->
                    repo.saveBike(b.copy(riderId = rider.id, riderName = rider.name))
                }
                val bikes = repo.getAllBikes()
                _uiState.update {
                    it.copy(
                        allBikes = bikes,
                        statusBannerMessage = if (es) "Backup restaurado (${payload.bikes.size} motos)" else "Backup restored (${payload.bikes.size} bikes)"
                    )
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(statusBannerMessage = if (es) "JSON de backup inválido" else "Invalid backup JSON")
                }
            }
        }
    }

    // --- Lap Recording (Manual & Antenna) ---
    fun recordManualLapForCurrentRider() {
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        val rider = _uiState.value.currentRider
        if (rider == null) {
            _uiState.update {
                it.copy(statusBannerMessage = if (es) "Inicia sesión como piloto primero" else "Sign in as rider first")
            }
            return
        }
        recordLapForRider(rider.id, syncedNowMs(), "manual", emptyList())
    }

    fun stopManualChronoForCurrentRider() {
        val state = _uiState.value
        val rider = state.currentRider ?: return
        val es = StringsProvider.current(state.languageOverride).isSpanish
        val room = state.roomState
        val existing = room.activeRiders[rider.id]
        val now = syncedNowMs()
        if (existing == null || existing.lastPassTimestampMs <= 0L) {
            _uiState.update {
                it.copy(statusBannerMessage = if (es) "El crono manual ya está detenido" else "Manual timer already stopped")
            }
            return
        }
        val stoppedRider = existing.copy(lastPassTimestampMs = 0L)
        val updatedRoom = room.copy(
            activeRiders = room.activeRiders + (rider.id to stoppedRider),
            updatedAtMs = now
        )
        _uiState.update {
            it.copy(
                roomState = updatedRoom,
                statusBannerMessage = if (es) "Crono manual detenido" else "Manual timer stopped"
            )
        }
        if (!state.paAudioMuted && state.antennaVoiceEnabled) {
            voiceEngine.speak(if (es) "Cronómetro detenido" else "Timer stopped")
        }
        viewModelScope.launch {
            repo.saveRoom(updatedRoom)
            repo.upsertRoomRider(state.activeCircuit.id, stoppedRider)
        }
    }

    fun handleVisionDetection(station: AntennaStation, result: VisionDetectionResult) {
        val circuitId = _uiState.value.activeCircuit.id
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        val roomPhase = _uiState.value.roomState.sessionPhase
        val isRaceActive = roomPhase != SessionPhase.IDLE
        val antennaCfg = _uiState.value.antennas[station.id]

        viewModelScope.launch {
            val colorNames = result.detectedColors.map { it.colorName }
            val colorHexes = result.detectedColors.map { it.hex }
            val labArr = JSONArray().apply {
                result.detectedColors.forEach { s ->
                    put(
                        JSONObject().apply {
                            put("l", s.l)
                            put("a", s.a)
                            put("b", s.b)
                            put("hex", s.hex)
                            put("colorName", s.colorName)
                        }
                    )
                }
            }
            val patchObj = JSONObject().apply {
                put("kind", "telemetry")
                put("circuitId", circuitId)
                put("antennaId", station.id)
                put("lastSnapshotBase64", result.snapshotBase64)
                if (result.photoFinishBase64 != null) {
                    put("lastPhotoFinishBase64", result.photoFinishBase64)
                }
                put("lastDetectedColors", JSONArray(colorNames))
                put("lastDetectedHexes", JSONArray(colorHexes))
                put("lastDetectedLab", labArr)
                put("lastDetectedDorsal", result.detectedDorsal)
                put("lastConfidence", result.matchConfidence.toDouble())
                put("activeFps", result.estimatedFps)
                put("lastTriggerTimestampMs", result.triggerTimestampMs)
                put("matchedRiderId", result.matchedProfile?.riderId ?: "")
                put("matchedRiderName", result.matchedProfile?.riderName ?: "")
                put("lastHeartbeatMs", syncedNowMs())
                put("isCalibrated", true)
                put("status", "DETECTING")
            }
            val cloudOk = repo.patchAntennaTelemetry(circuitId, station.id, patchObj.toString())
            val lanOk = sendViaLanIfAvailable(patchObj)

            var capCloudOk = cloudOk
            if (result.detectedColors.isNotEmpty() && antennaCfg?.detectionMode != DetectionMode.ANY_PASS) {
                val capture = CapturedPassSample(
                    id = "cap_${result.triggerTimestampMs}_${station.id}",
                    timestampMs = result.triggerTimestampMs,
                    antennaId = station.id,
                    samples = result.detectedColors,
                    snapshotBase64 = result.photoFinishBase64 ?: result.snapshotBase64,
                    detectedDorsal = result.detectedDorsal
                )
                capCloudOk = repo.pushCapturedPass(circuitId, capture) || cloudOk
                sendViaLanIfAvailable(
                    JSONObject().apply {
                        put("kind", "capture")
                        put("circuitId", circuitId)
                        put("antennaId", station.id)
                        put("id", capture.id)
                        put("timestampMs", capture.timestampMs)
                        put("samples", labArr)
                        put("snapshotBase64", capture.snapshotBase64)
                        put("detectedDorsal", capture.detectedDorsal)
                    }
                )
            }

            val anySent = capCloudOk || lanOk
            val feedbackText = when {
                cloudOk && lanOk -> if (es) "✓ Captura enviada (Nube + Wi-Fi)" else "✓ Capture sent (Cloud + Wi-Fi)"
                cloudOk -> if (es) "✓ Captura enviada a Dirección" else "✓ Capture sent to Director"
                lanOk -> if (es) "✓ Captura enviada por Wi-Fi Directo" else "✓ Capture sent via Direct Wi-Fi"
                else -> if (es) "⚠ Captura sin envío: ${repo.lastRtdbError() ?: "sin red"}" else "⚠ Not sent: ${repo.lastRtdbError() ?: "offline"}"
            }
            _uiState.update {
                it.copy(
                    antennaSyncOk = anySent,
                    antennaUploadCount = if (anySent) it.antennaUploadCount + 1 else it.antennaUploadCount,
                    antennaLastSyncError = if (cloudOk) null else repo.lastRtdbError(),
                    lastCaptureFeedback = feedbackText,
                    lastCaptureFeedbackAtMs = SystemClock.elapsedRealtime(),
                    lastCaptureOk = anySent
                )
            }
            // Say "Captura enviada" ONLY outside an active race; never announce colors on trackside speaker during race!
            if (!isRaceActive && _uiState.value.antennaVoiceEnabled && !_uiState.value.paAudioMuted) {
                voiceEngine.speak(if (es) "Captura enviada" else "Capture sent")
            }

            val activeList = _uiState.value.roomState.activeRiders.values.toList()
            val ocrNorm = result.detectedDorsal.trimStart('0').ifBlank { result.detectedDorsal }
            val ocrMatchedRider = if (ocrNorm.isNotBlank()) {
                activeList.firstOrNull { r ->
                    val rd = r.dorsal.trimStart('0').ifBlank { r.dorsal }
                    rd.equals(ocrNorm, ignoreCase = true)
                }
            } else null

            val mode = antennaCfg?.detectionMode ?: DetectionMode.ANY_PASS
            val singleTarget = antennaCfg?.singleTargetColorName.orEmpty()
            val colorMatched = result.matchedProfile
            val singleColorMatchesTarget = if (mode == DetectionMode.SINGLE_COLOR && singleTarget.isNotBlank()) {
                result.detectedColors.any { it.colorName.equals(singleTarget, ignoreCase = true) } || colorMatched != null
            } else true

            val resolvedRiderId = when (mode) {
                DetectionMode.ANY_PASS -> {
                    when {
                        ocrMatchedRider != null -> ocrMatchedRider.riderId
                        colorMatched != null && result.matchConfidence >= 0.45f -> colorMatched.riderId
                        activeList.size == 1 -> activeList.first().riderId
                        else -> null
                    }
                }
                DetectionMode.SINGLE_COLOR -> {
                    if (!singleColorMatchesTarget) null
                    else when {
                        colorMatched != null && result.matchConfidence >= 0.40f -> colorMatched.riderId
                        ocrMatchedRider != null -> ocrMatchedRider.riderId
                        singleTarget.isNotBlank() && activeList.size == 1 -> activeList.first().riderId
                        else -> null
                    }
                }
                DetectionMode.FAIRING_COLOR -> {
                    when {
                        colorMatched != null && result.matchConfidence >= 0.42f -> colorMatched.riderId
                        ocrMatchedRider != null -> ocrMatchedRider.riderId
                        else -> null
                    }
                }
            }

            if (resolvedRiderId != null) {
                val profileForRider = _uiState.value.colorProfiles.firstOrNull { it.riderId == resolvedRiderId }
                if (profileForRider != null && !profileForRider.confirmed) {
                    repo.saveColorProfile(circuitId, profileForRider.copy(confirmed = true))
                }
                val existingEntry = _uiState.value.roomState.activeRiders[resolvedRiderId]
                if (existingEntry != null && !existingEntry.colorConfirmed) {
                    repo.upsertRoomRider(circuitId, existingEntry.copy(colorConfirmed = true))
                }
                recordLapForRider(resolvedRiderId, result.triggerTimestampMs, station.id, colorNames)
            } else if (mode != DetectionMode.SINGLE_COLOR || singleColorMatchesTarget) {
                val pfPhoto = result.photoFinishBase64 ?: result.snapshotBase64
                if (antennaCfg?.aiStewardEnabled != false && pfPhoto.isNotBlank() && activeList.isNotEmpty()) {
                    resolveDoubtfulPassWithAi(
                        circuitId = circuitId,
                        stationId = station.id,
                        photoBase64 = pfPhoto,
                        detectedColors = result.detectedColors,
                        ocrDorsalHint = result.detectedDorsal,
                        frozenTriggerMs = result.triggerTimestampMs
                    )
                }
            }
        }
    }

    private fun resolveDoubtfulPassWithAi(
        circuitId: String,
        stationId: String,
        photoBase64: String,
        detectedColors: List<LabSample>,
        ocrDorsalHint: String,
        frozenTriggerMs: Long
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(aiStewardBusy = true, aiStewardError = null) }
            val state = _uiState.value
            val es = StringsProvider.current(state.languageOverride).isSpanish
            val res = aiSteward.inspectPassPhoto(
                photoBase64 = photoBase64,
                activeRiders = state.roomState.activeRiders.values.toList(),
                colorProfiles = state.colorProfiles,
                detectedColorsHint = detectedColors.map { it.colorName },
                ocrDorsalHint = ocrDorsalHint,
                nowMs = syncedNowMs()
            )
            res.onSuccess { verdict ->
                _uiState.update { it.copy(aiStewardBusy = false, lastAiVerdict = verdict, aiStewardError = null) }
                val updatedRoom = _uiState.value.roomState.copy(lastAiVerdict = verdict)
                repo.saveRoom(updatedRoom)
                if (verdict.isRealPass && verdict.matchedRiderId.isNotBlank() && verdict.confidencePercent >= 55) {
                    recordLapForRider(
                        verdict.matchedRiderId,
                        frozenTriggerMs,
                        stationId,
                        detectedColors.map { it.colorName }
                    )
                    // Self-learning: add detected CIE Lab colors to rider profile so future laps resolve in 2ms locally!
                    if (detectedColors.isNotEmpty()) {
                        assignWarmupColorSampleToRider(
                            verdict.matchedRiderId,
                            verdict.riderName.ifBlank { verdict.matchedRiderId },
                            detectedColors
                        )
                    }
                    _uiState.update {
                        it.copy(
                            statusBannerMessage = if (es) "Comisario IA: ${verdict.riderName} (${verdict.confidencePercent}%)"
                            else "AI Steward: ${verdict.riderName} (${verdict.confidencePercent}%)"
                        )
                    }
                } else if (!verdict.isRealPass) {
                    _uiState.update {
                        it.copy(
                            statusBannerMessage = if (es) "Comisario IA descartó falso paso"
                            else "AI Steward dismissed false trigger"
                        )
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        aiStewardBusy = false,
                        aiStewardError = err.localizedMessage ?: "Error Comisario IA"
                    )
                }
            }
        }
    }

    fun inspectPhotoWithAiSteward(
        photoBase64: String,
        detectedSamples: List<LabSample> = emptyList(),
        ocrDorsalHint: String = "",
        autoAssignWarmupSample: Boolean = true
    ) {
        val state = _uiState.value
        val circuitId = state.activeCircuit.id
        val es = StringsProvider.current(state.languageOverride).isSpanish
        if (photoBase64.isBlank()) {
            _uiState.update {
                it.copy(
                    aiStewardError = if (es) "No hay imagen Photo-Finish para analizar."
                    else "No Photo-Finish image available to inspect."
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(aiStewardBusy = true, aiStewardError = null) }
            val res = aiSteward.inspectPassPhoto(
                photoBase64 = photoBase64,
                activeRiders = state.roomState.activeRiders.values.toList(),
                colorProfiles = state.colorProfiles,
                detectedColorsHint = detectedSamples.map { it.colorName },
                ocrDorsalHint = ocrDorsalHint,
                nowMs = syncedNowMs()
            )
            res.onSuccess { verdict ->
                _uiState.update {
                    it.copy(
                        aiStewardBusy = false,
                        lastAiVerdict = verdict,
                        aiStewardError = null,
                        statusBannerMessage = if (es) verdict.reasoningEs else verdict.reasoningEn
                    )
                }
                repo.saveRoom(_uiState.value.roomState.copy(lastAiVerdict = verdict))
                if (autoAssignWarmupSample && verdict.isRealPass && verdict.matchedRiderId.isNotBlank() && detectedSamples.isNotEmpty()) {
                    assignWarmupColorSampleToRider(
                        verdict.matchedRiderId,
                        verdict.riderName.ifBlank { verdict.matchedRiderId },
                        detectedSamples
                    )
                }
                syncCircuitData(circuitId)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        aiStewardBusy = false,
                        aiStewardError = err.localizedMessage ?: "Error en Comisario IA"
                    )
                }
            }
        }
    }

    fun updateSoloAntennaMode(mode: DetectionMode) {
        _uiState.update { it.copy(soloAntennaDetectionMode = mode) }
    }

    fun updateSoloTargetColor(sample: LabSample?) {
        _uiState.update { it.copy(soloTargetColor = sample) }
    }

    fun setAntennaRoleActive(active: Boolean) {
        _uiState.update { it.copy(isAntennaRoleActive = active) }
    }

    fun handleSoloPilotVisionDetection(result: VisionDetectionResult) {
        val state = _uiState.value
        val es = StringsProvider.current(state.languageOverride).isSpanish
        val currentRider = state.currentRider ?: return
        val matched = result.matchedProfile
        val detectedNames = result.detectedColors.map { it.colorName }

        val targetRiderId: String? = when (state.soloAntennaDetectionMode) {
            DetectionMode.ANY_PASS -> currentRider.id
            DetectionMode.SINGLE_COLOR -> {
                val soloColor = state.soloTargetColor
                when {
                    soloColor != null -> {
                        val matchesSolo = result.detectedColors.any {
                            it.colorName.equals(soloColor.colorName, ignoreCase = true)
                        }
                        if (matchesSolo) currentRider.id else null
                    }
                    matched != null && result.matchConfidence >= 0.40f -> matched.riderId
                    else -> null
                }
            }
            DetectionMode.FAIRING_COLOR -> {
                if (matched != null && result.matchConfidence >= 0.40f) matched.riderId else null
            }
        }

        if (targetRiderId == null) {
            val detLabel = detectedNames.firstOrNull() ?: (if (es) "sin color" else "no color")
            val expLabel = state.soloTargetColor?.colorName ?: ""
            _uiState.update {
                it.copy(
                    lastCaptureFeedback = if (expLabel.isNotBlank()) {
                        if (es) "Ignorado: pasó $detLabel (esperado $expLabel)" else "Ignored: $detLabel ≠ $expLabel"
                    } else {
                        if (es) "Color sin perfil asignado ($detLabel)" else "Unassigned color ($detLabel)"
                    },
                    lastCaptureFeedbackAtMs = SystemClock.elapsedRealtime(),
                    lastCaptureOk = false
                )
            }
            return
        }

        val targetName = state.riders.firstOrNull { it.id == targetRiderId }?.name ?: currentRider.name
        val circuitId = state.activeCircuit.id
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    lastCaptureFeedback = if (es) "✓ Captura registrada ($targetName)" else "✓ Capture logged ($targetName)",
                    lastCaptureFeedbackAtMs = SystemClock.elapsedRealtime(),
                    lastCaptureOk = true
                )
            }
            if (result.detectedColors.isNotEmpty()) {
                val capture = CapturedPassSample(
                    id = "cap_${result.triggerTimestampMs}_solo",
                    timestampMs = result.triggerTimestampMs,
                    antennaId = "solo_${targetRiderId.take(5)}",
                    samples = result.detectedColors,
                    snapshotBase64 = result.photoFinishBase64 ?: result.snapshotBase64
                )
                repo.pushCapturedPass(circuitId, capture)
            }
            if (result.photoFinishBase64 != null) {
                val patch = JSONObject().apply {
                    put("lastPhotoFinishBase64", result.photoFinishBase64)
                    put("lastTriggerTimestampMs", result.triggerTimestampMs)
                    put("matchedRiderId", targetRiderId)
                    put("matchedRiderName", targetName)
                }.toString()
                repo.patchAntennaTelemetry(circuitId, AntennaStation.ANTENA_TEMP.id, patch)
            }
            recordLapForRider(targetRiderId, result.triggerTimestampMs, "solo_phone", detectedNames)
        }
    }

    fun recordLapForRider(
        riderId: String,
        passTimestampMs: Long,
        antennaId: String,
        detectedColors: List<String>
    ) {
        viewModelScope.launch {
            val state = _uiState.value
            val circuit = state.activeCircuit
            val room = state.roomState
            val riderAccount = state.riders.firstOrNull { it.id == riderId }
                ?: state.currentRider?.takeIf { it.id == riderId }
                ?: return@launch
            val bike = state.allBikes.firstOrNull { it.id == riderAccount.activeBikeId }
                ?: state.allBikes.firstOrNull { it.riderId == riderId }
            val roomRider = room.activeRiders[riderId] ?: ActiveRoomRider(
                riderId = riderAccount.id,
                riderName = riderAccount.name,
                dorsal = bike?.dorsal ?: "01",
                bikeModel = bike?.modelName ?: "Losi Promoto-MX",
                discipline = bike?.discipline ?: circuit.surface,
                setupSummary = bike?.buildSetupSummary() ?: "",
                sessionStartMs = passTimestampMs
            )
            val suppressAntennaSpeech = state.isAntennaRoleActive && room.sessionPhase != SessionPhase.IDLE

            val prevPass = roomRider.lastPassTimestampMs
            if (prevPass <= 0L) {
                // First crossing starts the flying lap clock immediately (optimistic local update + sync)
                val nowAnn = syncedNowMs()
                val ann = PaAnnouncement(
                    id = "pa_${nowAnn}_${riderId.take(4)}",
                    textEs = "En pista ${riderAccount.name}, dorsal ${roomRider.dorsal}",
                    textEn = "On track ${riderAccount.name}, number ${roomRider.dorsal}",
                    timestampMs = nowAnn,
                    isHotlap = false
                )
                val started = roomRider.copy(
                    lastPassTimestampMs = passTimestampMs,
                    sessionStartMs = if (roomRider.sessionStartMs <= 0L) passTimestampMs else roomRider.sessionStartMs
                )
                val nextRoom = room.copy(
                    circuitId = circuit.id,
                    activeRiders = room.activeRiders + (riderId to started),
                    lastPaAnnouncement = ann,
                    updatedAtMs = passTimestampMs
                )
                val es = StringsProvider.current(state.languageOverride).isSpanish
                lastSpokenPaAnnouncementId = ann.id
                _uiState.update { st ->
                    st.copy(
                        roomState = nextRoom,
                        statusBannerMessage = if (es) "Crono en marcha (${riderAccount.name})" else "Timer running (${riderAccount.name})"
                    )
                }
                if (!suppressAntennaSpeech && !state.paAudioMuted && state.antennaVoiceEnabled) {
                    voiceEngine.speak(if (es) ann.textEs else ann.textEn, flush = false)
                }
                repo.saveRoom(nextRoom)
                repo.upsertRoomRider(circuit.id, started)
                return@launch
            }

            val lapMs = (passTimestampMs - prevPass).coerceAtLeast(1500L)
            val prevBest = roomRider.bestLapMs
            val deltaMs = if (prevBest > 0L) lapMs - prevBest else 0L
            val newBest = if (prevBest <= 0L || lapMs < prevBest) lapMs else prevBest
            val speedKmh = computeSpeedKmh(lapMs, circuit.lengthMeters)
            val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(passTimestampMs))

            val lap = LapRecord(
                id = "lap_${passTimestampMs}_${riderId.take(4)}",
                circuitId = circuit.id,
                riderId = riderId,
                riderName = riderAccount.name,
                dorsal = roomRider.dorsal,
                bikeModel = roomRider.bikeModel,
                discipline = roomRider.discipline,
                lapTimeMs = lapMs,
                deltaMs = deltaMs,
                speedKmh = speedKmh,
                timestampMs = passTimestampMs,
                antennaId = antennaId,
                detectedColors = detectedColors,
                dayKey = dayKey
            )
            repo.recordLap(lap)

            val updatedRoomRider = roomRider.copy(
                lapsCount = roomRider.lapsCount + 1,
                bestLapMs = newBest,
                lastLapMs = lapMs,
                lastPassTimestampMs = passTimestampMs
            )

            // Build synchronized PA announcement (Name + Dorsal + Spoken Lap Time) for Pilots & BOTH Antennas!
            val spokenEs = formatLapForSpeech(lapMs, true)
            val spokenEn = formatLapForSpeech(lapMs, false)
            val isPersonalBest = prevBest <= 0L || lapMs < prevBest
            val isWeeklyHotlap = room.weeklyHotlapMs <= 0L || lapMs < room.weeklyHotlapMs
            val isDisciplineRecord = if (roomRider.discipline == SurfaceType.MX) {
                room.mxRecordMs <= 0L || lapMs < room.mxRecordMs
            } else {
                room.smRecordMs <= 0L || lapMs < room.smRecordMs
            }
            val newLapCount = updatedRoomRider.lapsCount

            val riderLaps = (listOf(lap) + state.circuitLaps.filter { it.riderId == riderId }).map { it.lapTimeMs }
            val reg = computeRegularityIndex(riderLaps)
            val allBest = if (riderAccount.bestLapMs <= 0L || lapMs < riderAccount.bestLapMs) lapMs else riderAccount.bestLapMs
            val eq200 = computeEq200mMs(allBest, circuit.lengthMeters)
            val avgSpd = if (riderAccount.avgSpeedKmh <= 0.0) speedKmh else (riderAccount.avgSpeedKmh * 0.7 + speedKmh * 0.3)

            var finalRoomRider = updatedRoomRider
            val (paEs, paEn, isHotlap) = when {
                room.gameMode == GameMode.TIME_BOMB_45 && lapMs > 45_000L -> {
                    finalRoomRider = updatedRoomRider.copy(eliminated = true)
                    voiceEngine.playBeep(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 450)
                    Triple(
                        "${riderAccount.name}, dorsal ${roomRider.dorsal}, $spokenEs. ¡Bomba explotada!",
                        "${riderAccount.name}, number ${roomRider.dorsal}, $spokenEn. Time bomb exploded!",
                        false
                    )
                }
                room.gameMode == GameMode.TURBO_SPRINT_3 && newLapCount == 3 -> {
                    voiceEngine.playStadiumApplause()
                    Triple(
                        "${riderAccount.name}, dorsal ${roomRider.dorsal}, $spokenEs. ¡Meta Turbo Sprint!",
                        "${riderAccount.name}, number ${roomRider.dorsal}, $spokenEn. Turbo Sprint Finish!",
                        true
                    )
                }
                room.gameMode == GameMode.PURSUIT_HUNTER && isWeeklyHotlap -> {
                    Triple(
                        "${riderAccount.name}, dorsal ${roomRider.dorsal}, $spokenEs. ¡Cazaste al líder!",
                        "${riderAccount.name}, number ${roomRider.dorsal}, $spokenEn. Leader caught!",
                        true
                    )
                }
                isDisciplineRecord -> {
                    val catEs = if (roomRider.discipline == SurfaceType.SM) "Supermotard" else "MX"
                    Triple(
                        "${riderAccount.name}, dorsal ${roomRider.dorsal}, $spokenEs. ¡Récord $catEs!",
                        "${riderAccount.name}, number ${roomRider.dorsal}, $spokenEn. New ${roomRider.discipline.name} record!",
                        true
                    )
                }
                isPersonalBest -> {
                    Triple(
                        "${riderAccount.name}, dorsal ${roomRider.dorsal}, $spokenEs. ¡Mejor vuelta!",
                        "${riderAccount.name}, number ${roomRider.dorsal}, $spokenEn. Personal best!",
                        true
                    )
                }
                room.gameMode == GameMode.CONSISTENCY_DUEL -> {
                    Triple(
                        "${riderAccount.name}, dorsal ${roomRider.dorsal}, $spokenEs, regularidad ${String.format("%.0f", reg)} por ciento",
                        "${riderAccount.name}, number ${roomRider.dorsal}, $spokenEn, consistency ${String.format("%.0f", reg)} percent",
                        false
                    )
                }
                else -> {
                    Triple(
                        "${riderAccount.name}, dorsal ${roomRider.dorsal}, $spokenEs",
                        "${riderAccount.name}, number ${roomRider.dorsal}, $spokenEn",
                        false
                    )
                }
            }

            val ann = PaAnnouncement(
                id = "pa_${passTimestampMs}_${riderId.take(4)}",
                textEs = paEs,
                textEn = paEn,
                timestampMs = passTimestampMs,
                isHotlap = isHotlap
            )
            val updatedRoom = room.copy(
                circuitId = circuit.id,
                weeklyHotlapMs = if (isWeeklyHotlap) lapMs else room.weeklyHotlapMs,
                weeklyHotlapRider = if (isWeeklyHotlap) riderAccount.name else room.weeklyHotlapRider,
                mxRecordMs = if (roomRider.discipline == SurfaceType.MX && (room.mxRecordMs <= 0L || lapMs < room.mxRecordMs)) lapMs else room.mxRecordMs,
                mxRecordRider = if (roomRider.discipline == SurfaceType.MX && (room.mxRecordMs <= 0L || lapMs < room.mxRecordMs)) riderAccount.name else room.mxRecordRider,
                smRecordMs = if (roomRider.discipline == SurfaceType.SM && (room.smRecordMs <= 0L || lapMs < room.smRecordMs)) lapMs else room.smRecordMs,
                smRecordRider = if (roomRider.discipline == SurfaceType.SM && (room.smRecordMs <= 0L || lapMs < room.smRecordMs)) riderAccount.name else room.smRecordRider,
                lastPaAnnouncement = ann,
                activeRiders = room.activeRiders + (riderId to finalRoomRider),
                updatedAtMs = passTimestampMs
            )

            val es = StringsProvider.current(state.languageOverride).isSpanish
            lastSpokenPaAnnouncementId = ann.id
            _uiState.update { st ->
                st.copy(
                    roomState = updatedRoom,
                    circuitLaps = (listOf(lap) + st.circuitLaps).distinctBy { it.id },
                    statusBannerMessage = "${formatLapMs(lapMs)} (${riderAccount.name})"
                )
            }
            if (!suppressAntennaSpeech && !state.paAudioMuted && state.antennaVoiceEnabled) {
                if (isHotlap) {
                    voiceEngine.playBeep(ToneGenerator.TONE_PROP_ACK, 200)
                }
                voiceEngine.speak(if (es) ann.textEs else ann.textEn, flush = false)
            }

            repo.recordLap(lap)
            repo.saveRoom(updatedRoom)

            if (bike != null) {
                repo.saveBike(
                    bike.copy(
                        totalLaps = bike.totalLaps + 1,
                        usageMinutes = bike.usageMinutes + (lapMs / 60_000L).toInt().coerceAtLeast(1)
                    )
                )
            }

            repo.saveRider(
                riderAccount.copy(
                    totalLaps = riderAccount.totalLaps + 1,
                    bestLapMs = allBest,
                    regularityIndex = reg,
                    avgSpeedKmh = avgSpd,
                    eq200mMs = eq200,
                    lastSeenAt = passTimestampMs
                )
            )
            syncCircuitData(circuit.id)
        }
    }

    private fun explainGameModeForSpeech(mode: GameMode): Pair<String, String> = when (mode) {
        GameMode.NORMAL ->
            "Modo Carrera Normal activado. Todos los pilotos completan vueltas contra el crono hasta finalizar el tiempo de la manga." to
                "Normal Race mode active. Riders complete timed laps until the heat clock ends."
        GameMode.TIME_BOMB_45 ->
            "Modo Bomba Reloj activado. Cada vuelta debe ser menor de cuarenta y cinco segundos, si la superas la bomba explota y quedas eliminado." to
                "Time Bomb mode active. Complete every lap under forty five seconds or you are eliminated."
        GameMode.ELIMINATION_60 ->
            "Modo Eliminación activado. Cada sesenta segundos queda eliminado el piloto que vaya en última posición." to
                "Elimination mode active. Every sixty seconds the last place rider is eliminated."
        GameMode.KING_OF_TRACK ->
            "Modo Rey de la Pista activado. El piloto con la vuelta más rápida ostenta la corona hasta que otro piloto bata su tiempo." to
                "King of the Track mode active. Fastest lap holds the crown until someone beats it."
        GameMode.CONSISTENCY_DUEL ->
            "Modo Duelo Metrónomo activado. Gana el piloto más regular con menor diferencia entre todas sus vueltas." to
                "Metronome Consistency Duel active. Highest lap-to-lap regularity wins."
        GameMode.TURBO_SPRINT_3 ->
            "Modo Turbo Sprint activado. Manga explosiva al límite a solo tres vueltas." to
                "Turbo Sprint mode active. Explosive all-out race over three laps."
        GameMode.PURSUIT_HUNTER ->
            "Modo Caza al Líder activado. Persigue y supera el mejor tiempo del líder de la pista." to
                "Pursuit Hunter mode active. Chase down and beat the track leader's best time."
    }

    // --- Friendly Mode (No Director) ---
    fun runFriendly10sCalibration() {
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        if (_uiState.value.roomState.sessionPhase != SessionPhase.IDLE) {
            _uiState.update {
                it.copy(statusBannerMessage = if (es) "Sesión activa en curso: no se puede calibrar ni organizar manga" else "Session active: stop current session first")
            }
            return
        }
        val circuitId = _uiState.value.activeCircuit.id
        viewModelScope.launch {
            for (s in 10 downTo 1) {
                if (_uiState.value.roomState.sessionPhase != SessionPhase.IDLE) return@launch
                voiceEngine.speak(s.toString())
                delay(1000L)
            }
            voiceEngine.playBeep(ToneGenerator.TONE_PROP_ACK, 300)
            voiceEngine.speak(if (es) "Calibrando pista vacía" else "Calibrating empty track")
            triggerRemoteCalibration(AntennaStation.ANTENA_1.id)
            triggerRemoteCalibration(AntennaStation.ANTENA_2.id)
            repo.saveRoom(_uiState.value.roomState.copy(circuitId = circuitId, friendlyModeActive = true))
        }
    }

    fun startFriendlyWarmup() = startWarmup(friendly = true)
    fun startFriendly30sGate() = start30sCountdown(friendly = true)
    fun startFriendlyRandomGate() = startRandomMxGate(friendly = true)

    fun startFriendlyDirectHeat(heatDurationSec: Int) {
        val state = _uiState.value
        val es = StringsProvider.current(state.languageOverride).isSpanish
        if (state.roomState.sessionPhase != SessionPhase.IDLE) {
            _uiState.update {
                it.copy(statusBannerMessage = if (es) "Ya hay una sesión en curso — pulsa STOP primero" else "Session already active — press STOP first")
            }
            return
        }
        val now = syncedNowMs()
        val (modeEs, modeEn) = explainGameModeForSpeech(state.roomState.gameMode)
        val ann = PaAnnouncement(
            id = "pa_${now}_friendlyheat",
            textEs = "¡Salida! Manga libre de ${heatDurationSec / 60} minutos. $modeEs",
            textEn = "Start! Open group heat for ${heatDurationSec / 60} minutes. $modeEn",
            timestampMs = now
        )
        lastSpokenPaAnnouncementId = ann.id
        val room = state.roomState.copy(
            circuitId = state.activeCircuit.id,
            sessionPhase = SessionPhase.RACING,
            flagState = FlagState.GREEN,
            heatDurationSec = heatDurationSec,
            phaseStartTimeMs = now,
            phaseEndTimeMs = now + heatDurationSec * 1000L,
            gateDropTimeMs = now,
            friendlyModeActive = true,
            lastPaAnnouncement = ann,
            updatedAtMs = now
        )
        _uiState.update { it.copy(roomState = room) }
        voiceEngine.playBeep(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 400)
        if (!state.isAntennaRoleActive && !state.paAudioMuted) {
            voiceEngine.speak(if (es) ann.textEs else ann.textEn, flush = true)
        }
        viewModelScope.launch {
            repo.saveRoom(room)
        }
    }

    fun setPocketModeAudioActive(enabled: Boolean) {
        val app = getApplication<Application>()
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        if (enabled) {
            voiceEngine.setOutputRoute(_uiState.value.audioOutputRoute)
            PocketAudioService.start(
                app,
                if (es) "Modo Bolsillo — Audio de pista activo" else "Pocket Mode — Track audio active"
            )
        } else {
            PocketAudioService.stop(app)
        }
        _uiState.update { it.copy(pocketAudioServiceRunning = enabled) }
    }

    fun setAudioOutputRoute(route: AudioOutputRoute) {
        voiceEngine.setOutputRoute(route)
        _uiState.update { it.copy(audioOutputRoute = route) }
    }

    fun spinCrazyMxRoulette() {
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        val challenges = if (es) {
            listOf(
                "¡Vuelta a una mano en el aire libre!",
                "¡Sin freno delantero en toda la manga!",
                "¡Modo Gyro al 35% — puro drift!",
                "¡Salida desde última posición con remontada!",
                "¡Bomba Reloj: nadie baja de 45 segundos!",
                "¡Doble salto obligatorio sin cortar gas!"
            )
        } else {
            listOf(
                "Gyro down to 35% — full drift heat!",
                "Last-place grid start charge!",
                "No front brake allowed this lap!",
                "Time Bomb 45s survival sprint!",
                "Hole-shot challenge from dead engine!",
                "Cleanest rhythm section wins bonus!"
            )
        }
        val picked = challenges.random()
        _uiState.update { it.copy(activeRouletteChallenge = picked) }
        voiceEngine.speak(picked)
    }

    // --- Local Wi-Fi / Hotspot LAN Bridge + Antenna Sync ---
    fun startDirectorLanBridge() {
        val ip = lanBridge.getLocalIpv4Address()
        _uiState.update {
            it.copy(directorLanEndpoint = ip?.let { addr -> "$addr:${LocalLanAntennaBridge.HTTP_PORT}" })
        }
        lanBridge.startDirectorServer(
            onAntennaTelemetry = { cId, aId, patch ->
                val activeCid = _uiState.value.activeCircuit.id
                if (cId == activeCid) {
                    val cur = _uiState.value.antennas[aId] ?: AntennaConfig(antennaId = aId, circuitId = cId)
                    val merged = AntennaConfig(
                        antennaId = aId,
                        circuitId = cId,
                        status = patch.optString("status", cur.status),
                        isCalibrated = if (patch.has("isCalibrated")) patch.optBoolean("isCalibrated") else cur.isCalibrated,
                        calibrateCommandAtMs = cur.calibrateCommandAtMs,
                        detectionMode = cur.detectionMode,
                        roiSize = cur.roiSize,
                        roiHeight = cur.roiHeight,
                        lineOrientation = cur.lineOrientation,
                        linePosition = cur.linePosition,
                        colorZoneX = cur.colorZoneX,
                        colorZoneY = cur.colorZoneY,
                        singleTargetColorName = cur.singleTargetColorName,
                        directionFilter = cur.directionFilter,
                        aiStewardEnabled = cur.aiStewardEnabled,
                        antiVibration = cur.antiVibration,
                        lastSnapshotBase64 = patch.optString("lastSnapshotBase64", "").ifBlank { cur.lastSnapshotBase64 },
                        lastPhotoFinishBase64 = patch.optString("lastPhotoFinishBase64", "").ifBlank { cur.lastPhotoFinishBase64 },
                        activeFps = if (patch.has("activeFps")) patch.optInt("activeFps", cur.activeFps) else cur.activeFps,
                        lastDetectedDorsal = patch.optString("lastDetectedDorsal", "").ifBlank { cur.lastDetectedDorsal },
                        lastHeartbeatMs = syncedNowMs()
                    )
                    _uiState.update { st ->
                        val updatedMap = st.antennas + (aId to merged)
                        val updatedMono = st.antennaLastChangeMonoMs + (aId to SystemClock.elapsedRealtime())
                        st.copy(antennas = updatedMap, antennaLastChangeMonoMs = updatedMono)
                    }
                }
            },
            onCaptureSample = { cId, json ->
                if (cId == _uiState.value.activeCircuit.id) {
                    val parsed = repo.rtdb.parseAntennaConfigSafe(cId, json.optString("antennaId", "antena_1"), JSONObject().apply {
                        put("lastDetectedLab", json.optJSONArray("samples") ?: JSONArray())
                    })
                    val cap = CapturedPassSample(
                        id = json.optString("id", "lan_${syncedNowMs()}"),
                        timestampMs = json.optLong("timestampMs", syncedNowMs()),
                        antennaId = json.optString("antennaId", "antena_1"),
                        samples = parsed.lastDetectedLab,
                        snapshotBase64 = json.optString("snapshotBase64", ""),
                        detectedDorsal = json.optString("detectedDorsal", "")
                    )
                    _uiState.update { st ->
                        st.copy(capturedPasses = (listOf(cap) + st.capturedPasses).distinctBy { it.id }.take(12))
                    }
                }
            }
        )
    }

    fun stopDirectorLanBridge() {
        lanBridge.stopDirectorServer()
        _uiState.update { it.copy(directorLanEndpoint = null) }
    }

    private suspend fun sendViaLanIfAvailable(payload: JSONObject): Boolean {
        val nowMono = SystemClock.elapsedRealtime()
        if (discoveredDirectorLanIp == null && nowMono - lastLanProbeMonoMs > 8_000L) {
            lastLanProbeMonoMs = nowMono
            discoveredDirectorLanIp = lanBridge.discoverDirectorIp()
        }
        val ip = discoveredDirectorLanIp ?: return false
        val ok = lanBridge.postToDirectorLan(ip, payload)
        if (!ok) {
            discoveredDirectorLanIp = null
        }
        return ok
    }

    // --- Antenna Screen Controls ---
    fun selectAntennaStation(station: AntennaStation) {
        val circuitId = _uiState.value.activeCircuit.id
        val nowMs = syncedNowMs()
        val seeded = (_uiState.value.antennas[station.id] ?: AntennaConfig(antennaId = station.id, circuitId = circuitId))
            .copy(
                antennaId = station.id,
                circuitId = circuitId,
                status = "ONLINE_WAIT_CAL",
                lastHeartbeatMs = nowMs
            )
        _uiState.update { st ->
            st.copy(
                selectedAntennaStation = station,
                isAntennaRoleActive = true,
                antennas = st.antennas + (station.id to seeded)
            )
        }
        viewModelScope.launch {
            val ok = repo.saveAntennaConfig(seeded)
            val lanPayload = JSONObject().apply {
                put("kind", "telemetry")
                put("circuitId", circuitId)
                put("antennaId", station.id)
                put("status", "ONLINE_WAIT_CAL")
                put("isCalibrated", seeded.isCalibrated)
                put("lastHeartbeatMs", nowMs)
            }
            val lanOk = sendViaLanIfAvailable(lanPayload)
            _uiState.update {
                it.copy(
                    antennaSyncOk = ok || lanOk,
                    antennaLastSyncError = when {
                        ok -> null
                        lanOk -> "Wi-Fi Directo OK (${discoveredDirectorLanIp}) • Nube: ${repo.lastRtdbError()}"
                        else -> (repo.lastRtdbError() ?: "RTDB write error")
                    }
                )
            }
        }
    }

    fun toggleAntennaVoice() {
        val next = !_uiState.value.antennaVoiceEnabled
        _uiState.update { it.copy(antennaVoiceEnabled = next, paAudioMuted = !next) }
    }

    fun togglePaAudioMute() {
        val nextMuted = !_uiState.value.paAudioMuted
        _uiState.update { it.copy(paAudioMuted = nextMuted, antennaVoiceEnabled = !nextMuted) }
    }

    fun sendAntennaHeartbeat(station: AntennaStation, isCalibrated: Boolean, status: String) {
        val circuitId = _uiState.value.activeCircuit.id
        val nowMs = syncedNowMs()
        val effectiveStatus = if (isCalibrated) "ONLINE_READY" else status.ifBlank { "ONLINE_WAIT_CAL" }
        _uiState.update { st ->
            val cur = st.antennas[station.id] ?: AntennaConfig(antennaId = station.id, circuitId = circuitId)
            st.copy(
                antennas = st.antennas + (station.id to cur.copy(
                    isCalibrated = isCalibrated,
                    status = effectiveStatus,
                    lastHeartbeatMs = nowMs
                ))
            )
        }
        viewModelScope.launch {
            val patchObj = JSONObject().apply {
                put("kind", "telemetry")
                put("antennaId", station.id)
                put("circuitId", circuitId)
                put("isCalibrated", isCalibrated)
                put("status", effectiveStatus)
                put("lastHeartbeatMs", nowMs)
            }
            val ok = repo.patchAntennaTelemetry(circuitId, station.id, patchObj.toString())
            val lanOk = if (!ok) sendViaLanIfAvailable(patchObj) else false
            _uiState.update {
                it.copy(
                    isOnline = ok,
                    antennaSyncOk = ok || lanOk,
                    antennaUploadCount = if (ok || lanOk) it.antennaUploadCount + 1 else it.antennaUploadCount,
                    antennaLastSyncError = when {
                        ok -> null
                        lanOk -> "Wi-Fi Directo OK (${discoveredDirectorLanIp}) • Nube: ${repo.lastRtdbError()}"
                        else -> (repo.lastRtdbError() ?: "RTDB heartbeat failed")
                    }
                )
            }
        }
    }

    fun calibrateAntennaLocally(station: AntennaStation) {
        val circuitId = _uiState.value.activeCircuit.id
        val nowMs = syncedNowMs()
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        _uiState.update { st ->
            val cur = st.antennas[station.id] ?: AntennaConfig(antennaId = station.id, circuitId = circuitId)
            st.copy(
                antennas = st.antennas + (station.id to cur.copy(
                    isCalibrated = true,
                    status = "ONLINE_READY",
                    lastHeartbeatMs = nowMs
                )),
                statusBannerMessage = if (es) "${station.name} calibrada en pista" else "${station.name} calibrated locally"
            )
        }
        viewModelScope.launch {
            val patchObj = JSONObject().apply {
                put("kind", "telemetry")
                put("antennaId", station.id)
                put("circuitId", circuitId)
                put("isCalibrated", true)
                put("status", "ONLINE_READY")
                put("lastHeartbeatMs", nowMs)
            }
            val ok = repo.patchAntennaTelemetry(circuitId, station.id, patchObj.toString())
            val lanOk = if (!ok) sendViaLanIfAvailable(patchObj) else false
            _uiState.update {
                it.copy(
                    antennaSyncOk = ok || lanOk,
                    antennaLastSyncError = if (ok) null else repo.lastRtdbError()
                )
            }
        }
    }

    fun pushAntennaLiveSnapshot(station: AntennaStation, base64: String, fps: Int = 30, isCalibrated: Boolean = false) {
        val circuitId = _uiState.value.activeCircuit.id
        val nowMs = syncedNowMs()
        val nextStatus = if (isCalibrated) "ONLINE_READY" else "ONLINE_WAIT_CAL"
        _uiState.update { st ->
            val cur = st.antennas[station.id] ?: AntennaConfig(antennaId = station.id, circuitId = circuitId)
            st.copy(
                antennas = st.antennas + (station.id to cur.copy(
                    lastSnapshotBase64 = base64,
                    lastHeartbeatMs = nowMs,
                    activeFps = fps,
                    isCalibrated = isCalibrated,
                    status = nextStatus
                ))
            )
        }
        viewModelScope.launch {
            val patchObj = JSONObject().apply {
                put("kind", "telemetry")
                put("antennaId", station.id)
                put("circuitId", circuitId)
                put("lastSnapshotBase64", base64)
                put("lastHeartbeatMs", nowMs)
                put("activeFps", fps)
                put("isCalibrated", isCalibrated)
                put("status", nextStatus)
            }
            val ok = repo.patchAntennaTelemetry(circuitId, station.id, patchObj.toString())
            val lanOk = if (!ok) sendViaLanIfAvailable(patchObj) else false
            _uiState.update {
                it.copy(
                    isOnline = ok,
                    antennaSyncOk = ok || lanOk,
                    antennaUploadCount = if (ok || lanOk) it.antennaUploadCount + 1 else it.antennaUploadCount,
                    antennaLastSyncError = when {
                        ok -> null
                        lanOk -> "Wi-Fi Directo OK (${discoveredDirectorLanIp}) • Nube: ${repo.lastRtdbError()}"
                        else -> (repo.lastRtdbError() ?: "RTDB snapshot failed")
                    }
                )
            }
        }
    }

    // --- Director Controls ---
    fun unlockDirector(password: String, remember: Boolean = true): Boolean {
        val clean = password.trim()
        val hash = sha256(clean)
        val cfg = _uiState.value.globalConfig
        val ok = hash == cfg.directorPasswordHash || (!cfg.directorCustomPasswordSet && clean == "MXPRO2026")
        if (ok) {
            _uiState.update {
                it.copy(
                    directorUnlocked = true,
                    rememberDirectorPass = remember,
                    rememberedDirectorPass = if (remember) clean else ""
                )
            }
            viewModelScope.launch { prefs.saveDirectorCreds(clean, remember) }
        }
        return ok
    }

    fun lockDirector() {
        _uiState.update { it.copy(directorUnlocked = false) }
    }

    fun changeDirectorPassword(newPass: String) {
        val clean = newPass.trim()
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        if (clean.length < 4) {
            _uiState.update {
                it.copy(statusBannerMessage = if (es) "La clave debe tener al menos 4 caracteres" else "Password must be at least 4 characters")
            }
            return
        }
        viewModelScope.launch {
            val cfg = _uiState.value.globalConfig
            val updated = cfg.copy(
                directorPasswordHash = sha256(clean),
                directorCustomPasswordSet = true
            )
            repo.saveGlobalConfig(updated)
            prefs.saveDirectorCreds(clean, _uiState.value.rememberDirectorPass)
            _uiState.update {
                it.copy(
                    globalConfig = updated,
                    rememberedDirectorPass = if (it.rememberDirectorPass) clean else "",
                    statusBannerMessage = if (es) "Clave de Dirección actualizada" else "Director password updated"
                )
            }
        }
    }

    fun directorJoinRoomAsPilot(rider: RiderAccount) {
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            val bikes = repo.getAllBikes().ifEmpty { _uiState.value.allBikes }
            val bike = bikes.firstOrNull { it.id == rider.activeBikeId } ?: bikes.firstOrNull { it.riderId == rider.id }
            _uiState.update {
                it.copy(
                    currentRider = rider,
                    allBikes = bikes,
                    statusBannerMessage = if (es) "Director en parrilla como ${rider.name}" else "Director joined track as ${rider.name}"
                )
            }
            enrollRiderInActiveRoom(rider, bike)
        }
    }

    fun updateRoomSettings(
        gameMode: GameMode? = null,
        heatDurationSec: Int? = null,
        antiVibration: AntiVibrationLevel? = null,
        flagState: FlagState? = null
    ) {
        val state = _uiState.value
        val current = state.roomState
        val es = StringsProvider.current(state.languageOverride).isSpanish
        if (current.sessionPhase != SessionPhase.IDLE && (gameMode != null || heatDurationSec != null)) {
            _uiState.update {
                it.copy(statusBannerMessage = if (es) "Pulsa STOP antes de cambiar modo o duración" else "Press STOP before changing mode or duration")
            }
            return
        }
        val now = syncedNowMs()
        val ann: PaAnnouncement? = when {
            flagState != null && flagState != current.flagState -> PaAnnouncement(
                id = "pa_${now}_flag",
                textEs = if (flagState == FlagState.YELLOW) "Atención, bandera amarilla en pista" else "Bandera verde, pista libre",
                textEn = if (flagState == FlagState.YELLOW) "Caution, yellow flag on track" else "Green flag, track clear",
                timestampMs = now
            )
            gameMode != null -> {
                val (modeEs, modeEn) = explainGameModeForSpeech(gameMode)
                PaAnnouncement(
                    id = "pa_${now}_mode_${gameMode.name}",
                    textEs = modeEs,
                    textEn = modeEn,
                    timestampMs = now
                )
            }
            else -> current.lastPaAnnouncement
        }
        if (ann != null && ann.id != lastSpokenPaAnnouncementId && (flagState != null || gameMode != null)) {
            lastSpokenPaAnnouncementId = ann.id
            if (!state.isAntennaRoleActive && !state.paAudioMuted && state.antennaVoiceEnabled) {
                voiceEngine.speak(if (es) ann.textEs else ann.textEn, flush = true)
            }
        }
        val updated = current.copy(
            circuitId = state.activeCircuit.id,
            gameMode = gameMode ?: current.gameMode,
            heatDurationSec = heatDurationSec ?: current.heatDurationSec,
            antiVibration = antiVibration ?: current.antiVibration,
            flagState = flagState ?: current.flagState,
            sessionPhase = if (flagState == FlagState.YELLOW) SessionPhase.PAUSED_YELLOW
            else if (flagState == FlagState.GREEN && current.sessionPhase == SessionPhase.PAUSED_YELLOW) SessionPhase.RACING
            else current.sessionPhase,
            lastPaAnnouncement = ann,
            updatedAtMs = now
        )
        _uiState.update { it.copy(roomState = updated) }
        viewModelScope.launch {
            repo.saveRoom(updated)
            if (antiVibration != null) {
                _uiState.value.antennas.values.forEach { ant ->
                    repo.saveAntennaConfig(ant.copy(antiVibration = antiVibration))
                }
            }
        }
    }

    fun startWarmup(friendly: Boolean = false) {
        val state = _uiState.value
        val es = StringsProvider.current(state.languageOverride).isSpanish
        if (state.roomState.sessionPhase != SessionPhase.IDLE) {
            _uiState.update {
                it.copy(statusBannerMessage = if (es) "Ya hay una sesión en curso — pulsa STOP primero" else "Session already active — press STOP first")
            }
            return
        }
        val now = syncedNowMs()
        val ann = PaAnnouncement(
            id = "pa_${now}_warmup_start",
            textEs = "Warm-Up iniciado, tres minutos. Pilotos, pasad varias veces por meta para capturar vuestros colores.",
            textEn = "Warm-Up started, three minutes. Riders, pass through finish several times to capture your colors.",
            timestampMs = now
        )
        lastSpokenPaAnnouncementId = ann.id
        val room = state.roomState.copy(
            circuitId = state.activeCircuit.id,
            sessionPhase = SessionPhase.WARMUP,
            flagState = FlagState.GREEN,
            phaseStartTimeMs = now,
            phaseEndTimeMs = now + 180_000L,
            friendlyModeActive = friendly,
            lastPaAnnouncement = ann,
            updatedAtMs = now
        )
        _uiState.update {
            it.copy(
                roomState = room,
                statusBannerMessage = if (es) "Warm-Up 3:00 en marcha" else "Warm-Up 3:00 started"
            )
        }
        if (!state.isAntennaRoleActive && !state.paAudioMuted) {
            voiceEngine.speak(if (es) ann.textEs else ann.textEn, flush = true)
        }
        viewModelScope.launch {
            repo.saveRoom(room)
        }
    }

    fun start30sCountdown(friendly: Boolean = false) {
        val state = _uiState.value
        val es = StringsProvider.current(state.languageOverride).isSpanish
        if (state.roomState.sessionPhase != SessionPhase.IDLE) {
            _uiState.update {
                it.copy(statusBannerMessage = if (es) "Ya hay una sesión en curso — pulsa STOP primero" else "Session already active — press STOP first")
            }
            return
        }
        val now = syncedNowMs()
        val gateAt = now + 30_000L
        val ann = PaAnnouncement(
            id = "pa_${now}_gate30",
            textEs = "Pilotos a parrilla, treinta segundos para salida",
            textEn = "Riders to the gate, thirty seconds to start",
            timestampMs = now
        )
        lastSpokenPaAnnouncementId = ann.id
        gateCountdownPhaseStartMs = now
        lastAnnouncedGateSec = 30
        val room = state.roomState.copy(
            circuitId = state.activeCircuit.id,
            sessionPhase = SessionPhase.COUNTDOWN_30,
            flagState = FlagState.GREEN,
            phaseStartTimeMs = now,
            phaseEndTimeMs = gateAt,
            gateDropTimeMs = gateAt,
            friendlyModeActive = friendly,
            lastPaAnnouncement = ann,
            updatedAtMs = now
        )
        _uiState.update { it.copy(roomState = room) }
        if (!state.isAntennaRoleActive && !state.paAudioMuted) {
            voiceEngine.speak(if (es) ann.textEs else ann.textEn, flush = true)
        }
        viewModelScope.launch {
            repo.saveRoom(room)
        }
    }

    fun startRandomMxGate(friendly: Boolean = false) {
        val state = _uiState.value
        val es = StringsProvider.current(state.languageOverride).isSpanish
        if (state.roomState.sessionPhase != SessionPhase.IDLE) {
            _uiState.update {
                it.copy(statusBannerMessage = if (es) "Ya hay una sesión en curso — pulsa STOP primero" else "Session already active — press STOP first")
            }
            return
        }
        val now = syncedNowMs()
        val randomDelayMs = (4000L..11000L).random()
        val gateAt = now + randomDelayMs
        val readyAnn = PaAnnouncement(
            id = "pa_${now}_mxgate",
            textEs = "Pilotos a parrilla, atentos a la caída de valla",
            textEn = "Riders on the gate, watch the drop",
            timestampMs = now
        )
        lastSpokenPaAnnouncementId = readyAnn.id
        val room = state.roomState.copy(
            circuitId = state.activeCircuit.id,
            sessionPhase = SessionPhase.RANDOM_GATE,
            flagState = FlagState.GREEN,
            phaseStartTimeMs = now,
            phaseEndTimeMs = gateAt,
            gateDropTimeMs = gateAt,
            friendlyModeActive = friendly,
            lastPaAnnouncement = readyAnn,
            updatedAtMs = now
        )
        _uiState.update { it.copy(roomState = room) }
        if (!state.isAntennaRoleActive && !state.paAudioMuted) {
            voiceEngine.speak(if (es) readyAnn.textEs else readyAnn.textEn, flush = true)
        }
        viewModelScope.launch {
            repo.saveRoom(room)
            delay(randomDelayMs)
            // Abort if Director or Rider hit STOP before gate dropped
            val latestRoom = _uiState.value.roomState
            if (latestRoom.sessionPhase != SessionPhase.RANDOM_GATE || latestRoom.phaseStartTimeMs != now) {
                return@launch
            }
            val dropNow = syncedNowMs()
            val dropAnn = PaAnnouncement(
                id = "pa_${dropNow}_mxgatedrop",
                textEs = "¡Salida! ¡Valla abajo, gas!",
                textEn = "Start! Gate drop, go!",
                timestampMs = dropNow
            )
            lastSpokenPaAnnouncementId = dropAnn.id
            val startedRace = latestRoom.copy(
                sessionPhase = SessionPhase.RACING,
                flagState = FlagState.GREEN,
                phaseStartTimeMs = dropNow,
                phaseEndTimeMs = dropNow + latestRoom.heatDurationSec * 1000L,
                gateDropTimeMs = dropNow,
                lastPaAnnouncement = dropAnn,
                updatedAtMs = dropNow
            )
            _uiState.update { it.copy(roomState = startedRace) }
            voiceEngine.playBeep(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 450)
            if (!_uiState.value.isAntennaRoleActive && !_uiState.value.paAudioMuted) {
                voiceEngine.speak(if (es) dropAnn.textEs else dropAnn.textEn, flush = true)
            }
            repo.saveRoom(startedRace)
        }
    }

    fun stopSession(isDirector: Boolean = true) {
        val state = _uiState.value
        val room = state.roomState
        if (room.sessionPhase == SessionPhase.IDLE) return
        val es = StringsProvider.current(state.languageOverride).isSpanish
        if (!isDirector && !room.friendlyModeActive) {
            _uiState.update {
                it.copy(statusBannerMessage = if (es) "Solo Dirección puede detener esta manga" else "Only Director can stop this session")
            }
            return
        }
        val wasWarmup = room.sessionPhase == SessionPhase.WARMUP
        val now = syncedNowMs()
        val ann = PaAnnouncement(
            id = "pa_${now}_stop",
            textEs = if (wasWarmup) "Warm-Up detenido, pilotos a parrilla" else "Sesión detenida",
            textEn = if (wasWarmup) "Warm-Up stopped, riders to the grid" else "Session stopped",
            timestampMs = now
        )
        lastSpokenPaAnnouncementId = ann.id
        lastAutoEndedPhaseStartMs = room.phaseStartTimeMs
        val stopped = room.copy(
            circuitId = state.activeCircuit.id,
            sessionPhase = SessionPhase.IDLE,
            flagState = FlagState.GREEN,
            phaseEndTimeMs = 0L,
            friendlyModeActive = false,
            lastPaAnnouncement = ann,
            updatedAtMs = now
        )
        _uiState.update {
            it.copy(
                roomState = stopped,
                statusBannerMessage = if (wasWarmup) {
                    if (es) "Warm-Up detenido — Pilotos a parrilla" else "Warm-Up stopped — Riders to the grid"
                } else {
                    if (es) "Sesión detenida" else "Session stopped"
                }
            )
        }
        voiceEngine.playBeep(ToneGenerator.TONE_PROP_BEEP2, 300)
        if (!state.isAntennaRoleActive && !state.paAudioMuted) {
            voiceEngine.speak(if (es) ann.textEs else ann.textEn, flush = true)
        }
        viewModelScope.launch {
            repo.saveRoom(stopped)
        }
    }

    fun resetHeat() {
        val circuitId = _uiState.value.activeCircuit.id
        viewModelScope.launch {
            val resetRiders = _uiState.value.roomState.activeRiders.mapValues { (_, r) ->
                r.copy(lapsCount = 0, bestLapMs = 0L, lastLapMs = 0L, lastPassTimestampMs = 0L, eliminated = false)
            }
            repo.saveRoom(
                _uiState.value.roomState.copy(
                    circuitId = circuitId,
                    sessionPhase = SessionPhase.IDLE,
                    flagState = FlagState.GREEN,
                    phaseEndTimeMs = 0L,
                    activeRiders = resetRiders,
                    updatedAtMs = syncedNowMs()
                )
            )
            syncCircuitData(circuitId)
        }
    }

    fun lockActiveCircuitGpsHere() {
        val geo = _uiState.value.geoStatus ?: return
        val lat = geo.currentLat ?: return
        val lng = geo.currentLng ?: return
        val updated = _uiState.value.activeCircuit.copy(lat = lat, lng = lng)
        viewModelScope.launch {
            repo.saveCircuit(updated)
            _uiState.update { it.copy(activeCircuit = updated) }
            refreshLocation()
        }
    }

    fun updateRemoteAntennaConfig(
        antennaId: String,
        detectionMode: DetectionMode? = null,
        roiSize: RoiSize? = null,
        roiHeight: RoiHeight? = null,
        lineOrientation: LineOrientation? = null,
        linePosition: Float? = null,
        colorZoneX: Float? = null,
        colorZoneY: Float? = null,
        singleTargetColorName: String? = null,
        directionFilter: PassingDirection? = null,
        aiStewardEnabled: Boolean? = null,
        antiVibration: AntiVibrationLevel? = null
    ) {
        val circuitId = _uiState.value.activeCircuit.id
        val current = _uiState.value.antennas[antennaId] ?: AntennaConfig(antennaId = antennaId, circuitId = circuitId)
        val updated = current.copy(
            detectionMode = detectionMode ?: current.detectionMode,
            roiSize = roiSize ?: current.roiSize,
            roiHeight = roiHeight ?: current.roiHeight,
            lineOrientation = lineOrientation ?: current.lineOrientation,
            linePosition = linePosition ?: current.linePosition,
            colorZoneX = colorZoneX ?: current.colorZoneX,
            colorZoneY = colorZoneY ?: current.colorZoneY,
            singleTargetColorName = singleTargetColorName ?: current.singleTargetColorName,
            directionFilter = directionFilter ?: current.directionFilter,
            aiStewardEnabled = aiStewardEnabled ?: current.aiStewardEnabled,
            antiVibration = antiVibration ?: current.antiVibration
        )
        // Optimistic local state update so UI sliders & overlays respond immediately
        _uiState.update { st ->
            st.copy(antennas = st.antennas + (antennaId to updated))
        }
        viewModelScope.launch {
            val patch = JSONObject().apply {
                put("antennaId", antennaId)
                put("circuitId", circuitId)
                if (detectionMode != null) put("detectionMode", detectionMode.name)
                if (roiSize != null) put("roiSize", roiSize.name)
                if (roiHeight != null) put("roiHeight", roiHeight.name)
                if (lineOrientation != null) put("lineOrientation", lineOrientation.name)
                if (linePosition != null) put("linePosition", linePosition.toDouble())
                if (colorZoneX != null) put("colorZoneX", colorZoneX.toDouble())
                if (colorZoneY != null) put("colorZoneY", colorZoneY.toDouble())
                if (singleTargetColorName != null) put("singleTargetColorName", singleTargetColorName)
                if (directionFilter != null) put("directionFilter", directionFilter.name)
                if (aiStewardEnabled != null) put("aiStewardEnabled", aiStewardEnabled)
                if (antiVibration != null) put("antiVibration", antiVibration.name)
            }.toString()
            repo.patchAntennaTelemetry(circuitId, antennaId, patch)
        }
    }

    fun triggerRemoteCalibration(antennaId: String) {
        val circuitId = _uiState.value.activeCircuit.id
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            val patch = JSONObject().apply {
                put("antennaId", antennaId)
                put("circuitId", circuitId)
                put("calibrateCommandAtMs", syncedNowMs())
                put("status", "CALIBRATING")
            }.toString()
            repo.patchAntennaTelemetry(circuitId, antennaId, patch)
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (es) "Orden de calibración enviada a $antennaId"
                    else "Calibration command sent to $antennaId"
                )
            }
            refreshAntennasAndCaptures()
        }
    }

    fun assignWarmupColorSampleToRider(riderId: String, riderName: String, samples: List<LabSample>) {
        if (samples.isEmpty()) return
        val circuitId = _uiState.value.activeCircuit.id
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            val existing = _uiState.value.colorProfiles.firstOrNull { it.riderId == riderId }
            val mergedSamples = ((existing?.samples ?: emptyList()) + samples).takeLast(12)
            val profile = RiderColorProfile(
                riderId = riderId,
                riderName = riderName,
                samples = mergedSamples,
                confirmed = false
            )
            repo.saveColorProfile(circuitId, profile)
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (es) "Muestra añadida a $riderName (${mergedSamples.size} tonos CIE Lab)"
                    else "Sample added to $riderName (${mergedSamples.size} CIE Lab tones)"
                )
            }
            syncCircuitData(circuitId)
        }
    }

    fun assignSingleColorToRider(riderId: String, riderName: String, sample: LabSample) {
        val circuitId = _uiState.value.activeCircuit.id
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            val profile = RiderColorProfile(
                riderId = riderId,
                riderName = riderName,
                samples = listOf(sample),
                confirmed = true
            )
            repo.saveColorProfile(circuitId, profile)
            val existingEntry = _uiState.value.roomState.activeRiders[riderId]
            if (existingEntry != null) {
                repo.upsertRoomRider(circuitId, existingEntry.copy(colorConfirmed = true))
            }
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (es) "Color único (${sample.colorName}) fijado para $riderName"
                    else "Single color (${sample.colorName}) locked for $riderName"
                )
            }
            syncCircuitData(circuitId)
        }
    }

    fun clearRiderColorProfile(riderId: String, riderName: String) {
        val circuitId = _uiState.value.activeCircuit.id
        viewModelScope.launch {
            repo.saveColorProfile(circuitId, RiderColorProfile(riderId = riderId, riderName = riderName, samples = emptyList(), confirmed = false))
            syncCircuitData(circuitId)
        }
    }

    // --- Championships (MXGP Multi-Heat Points) ---
    fun createChampionship(name: String, totalHeats: Int) {
        if (name.isBlank()) return
        val circuit = _uiState.value.activeCircuit
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            val champ = Championship(
                id = "champ_${syncedNowMs()}",
                circuitId = circuit.id,
                name = name.trim(),
                discipline = circuit.surface,
                totalHeats = totalHeats.coerceIn(1, 12),
                completedHeats = 0,
                standings = emptyMap(),
                createdAtMs = syncedNowMs()
            )
            repo.saveChampionship(champ)
            _uiState.update {
                it.copy(
                    activeChampionshipId = champ.id,
                    statusBannerMessage = if (es) "Campeonato '${champ.name}' creado" else "Championship '${champ.name}' created"
                )
            }
            syncCircuitData(circuit.id)
        }
    }

    fun selectActiveChampionship(champId: String) {
        _uiState.update { it.copy(activeChampionshipId = champId) }
    }

    fun awardChampionshipHeatPoints() {
        val state = _uiState.value
        val champ = state.championships.firstOrNull { it.id == state.activeChampionshipId } ?: return
        val es = StringsProvider.current(state.languageOverride).isSpanish
        val roomRiders = state.roomState.activeRiders.values.toList()
        if (roomRiders.isEmpty()) return

        val ordered = if (state.roomState.gameMode == GameMode.CONSISTENCY_DUEL) {
            roomRiders.sortedByDescending { r ->
                val laps = state.circuitLaps.filter { it.riderId == r.riderId }.map { it.lapTimeMs }
                if (laps.size >= 2) computeRegularityIndex(laps) else 0.0
            }
        } else {
            roomRiders.sortedWith(
                compareBy<ActiveRoomRider> { it.eliminated }
                    .thenByDescending { it.lapsCount }
                    .thenBy { if (it.bestLapMs <= 0L) Long.MAX_VALUE else it.bestLapMs }
            )
        }

        viewModelScope.launch {
            val mutableStandings = champ.standings.toMutableMap()
            ordered.forEachIndexed { idx, r ->
                val pts = MXGP_POINTS_TABLE.getOrElse(idx) { 1 }
                val prev = mutableStandings[r.riderId]
                val bestEver = listOfNotNull(
                    prev?.bestLapMs?.takeIf { it > 0L },
                    r.bestLapMs.takeIf { it > 0L }
                ).minOrNull() ?: 0L
                mutableStandings[r.riderId] = ChampionshipStanding(
                    riderId = r.riderId,
                    riderName = r.riderName,
                    dorsal = r.dorsal,
                    points = (prev?.points ?: 0) + pts,
                    heatsRaced = (prev?.heatsRaced ?: 0) + 1,
                    wins = (prev?.wins ?: 0) + if (idx == 0) 1 else 0,
                    bestLapMs = bestEver
                )
            }
            val updated = champ.copy(
                completedHeats = champ.completedHeats + 1,
                standings = mutableStandings
            )
            repo.saveChampionship(updated)
            voiceEngine.playStadiumApplause()
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (es) "Puntos MXGP sumados (Manga ${updated.completedHeats}/${updated.totalHeats})"
                    else "MXGP points awarded (Heat ${updated.completedHeats}/${updated.totalHeats})"
                )
            }
            syncCircuitData(state.activeCircuit.id)
        }
    }

    fun broadcastPaAudioClip(base64Audio: String) {
        val circuitId = _uiState.value.activeCircuit.id
        viewModelScope.launch {
            val current = repo.getFanInteraction(circuitId)
            repo.saveFanInteraction(
                circuitId,
                current.copy(
                    paClipBase64 = base64Audio,
                    paClipTimestampMs = syncedNowMs()
                )
            )
        }
    }

    // --- Club Admin ---
    fun toggleRiderStatus(rider: RiderAccount) {
        val circuitId = _uiState.value.activeCircuit.id
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            val next = if (rider.status == RiderStatus.ACTIVE) RiderStatus.BLOCKED else RiderStatus.ACTIVE
            repo.saveRider(rider.copy(status = next))
            if (next == RiderStatus.BLOCKED) {
                repo.deleteRoomRider(circuitId, rider.id)
            }
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (next == RiderStatus.BLOCKED) {
                        if (es) "Piloto ${rider.name} bloqueado" else "Rider ${rider.name} blocked"
                    } else {
                        if (es) "Piloto ${rider.name} reactivado" else "Rider ${rider.name} activated"
                    }
                )
            }
            syncCircuitData(circuitId)
        }
    }

    fun deleteRiderAccount(riderId: String) {
        val circuitId = _uiState.value.activeCircuit.id
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            _uiState.value.allBikes.filter { it.riderId == riderId }.forEach { b ->
                repo.deleteBike(b.id)
            }
            repo.deleteRoomRider(circuitId, riderId)
            repo.deleteColorProfile(circuitId, riderId)
            repo.deleteRider(riderId)
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (es) "Cuenta eliminada definitivamente" else "Account permanently deleted"
                )
            }
            syncCircuitData(circuitId)
        }
    }

    fun toggleGlobalRegistrationOpen() {
        viewModelScope.launch {
            val cfg = _uiState.value.globalConfig
            val updated = cfg.copy(registrationOpen = !cfg.registrationOpen)
            repo.saveGlobalConfig(updated)
            _uiState.update { it.copy(globalConfig = updated) }
        }
    }

    fun toggleApkDownloadLocked() {
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            val cfg = _uiState.value.globalConfig
            val next = !cfg.apkDownloadLocked
            val updated = cfg.copy(apkDownloadLocked = next)
            repo.saveGlobalConfig(updated)
            _uiState.update {
                it.copy(
                    globalConfig = updated,
                    statusBannerMessage = if (next) {
                        if (es) "Descargas e instalaciones bloqueadas sin código" else "APK downloads & installs locked without code"
                    } else {
                        if (es) "Descargas e instalaciones abiertas" else "APK downloads & installs unlocked"
                    }
                )
            }
        }
    }

    fun createInviteDownloadCode(
        rawCode: String,
        circuitName: String,
        note: String,
        promoteOfficial: Boolean
    ) {
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        val cleanCode = rawCode.trim().uppercase().replace(" ", "").ifBlank {
            "MX-" + UUID.randomUUID().toString().take(6).uppercase()
        }
        viewModelScope.launch {
            val cfg = repo.getGlobalConfig()
            val entry = InviteDownloadCode(
                code = cleanCode,
                circuitName = circuitName.trim().ifBlank { "Circuito Oficial" },
                note = note.trim(),
                promoteOfficial = promoteOfficial,
                active = true,
                redeemedCount = 0,
                createdAtMs = syncedNowMs()
            )
            val updated = cfg.copy(accessCodes = cfg.accessCodes + (cleanCode to entry))
            repo.saveGlobalConfig(updated)
            _uiState.update {
                it.copy(
                    globalConfig = updated,
                    statusBannerMessage = if (es) "Código emitido: $cleanCode" else "Code issued: $cleanCode"
                )
            }
        }
    }

    fun toggleInviteCodeActive(code: String) {
        viewModelScope.launch {
            val cfg = _uiState.value.globalConfig
            val existing = cfg.accessCodes[code] ?: return@launch
            val updated = cfg.copy(
                accessCodes = cfg.accessCodes + (code to existing.copy(active = !existing.active))
            )
            repo.saveGlobalConfig(updated)
            _uiState.update { it.copy(globalConfig = updated) }
        }
    }

    fun deleteInviteCode(code: String) {
        viewModelScope.launch {
            val cfg = _uiState.value.globalConfig
            val updated = cfg.copy(accessCodes = cfg.accessCodes - code)
            repo.saveGlobalConfig(updated)
            _uiState.update { it.copy(globalConfig = updated) }
        }
    }

    fun redeemInstallOrDownloadCode(rawCode: String, onSuccess: (InviteDownloadCode) -> Unit) {
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        val clean = rawCode.trim().uppercase()
        if (clean.isBlank()) return
        viewModelScope.launch {
            val cfg = repo.getGlobalConfig()
            val match = cfg.accessCodes[clean] ?: cfg.accessCodes.values.firstOrNull {
                it.code.equals(clean, ignoreCase = true)
            }
            if (match == null || !match.active) {
                _uiState.update {
                    it.copy(
                        statusBannerMessage = if (es) "Código inválido o desactivado" else "Invalid or disabled code"
                    )
                }
                return@launch
            }
            val updatedMatch = match.copy(redeemedCount = match.redeemedCount + 1)
            val updatedCfg = cfg.copy(accessCodes = cfg.accessCodes + (match.code to updatedMatch))
            repo.saveGlobalConfig(updatedCfg)
            prefs.markInstallUnlocked(match.code)

            var updatedCircuits = _uiState.value.circuits
            var activeCirc = _uiState.value.activeCircuit
            if (match.promoteOfficial && match.circuitName.isNotBlank()) {
                val existingCirc = updatedCircuits.firstOrNull {
                    it.name.equals(match.circuitName, ignoreCase = true)
                }
                if (existingCirc != null) {
                    val official = existingCirc.copy(isOfficial = true)
                    repo.saveCircuit(official)
                    activeCirc = official
                } else {
                    val official = Circuit(
                        id = "official_${UUID.randomUUID().toString().take(8)}",
                        name = match.circuitName,
                        countryCity = if (match.note.isNotBlank()) match.note else "Circuito Oficial",
                        surface = SurfaceType.MX,
                        lengthMeters = 185,
                        layoutType = LayoutType.MIXED,
                        isOfficial = true
                    )
                    repo.saveCircuit(official)
                    activeCirc = official
                }
                updatedCircuits = repo.getCircuits()
            }

            _uiState.update {
                it.copy(
                    globalConfig = updatedCfg,
                    installCodeUnlocked = true,
                    circuits = updatedCircuits,
                    activeCircuit = activeCirc,
                    statusBannerMessage = if (es) {
                        "Código aceptado (${match.code})${if (match.promoteOfficial) " • Circuito Oficial activo" else ""}"
                    } else {
                        "Code accepted (${match.code})${if (match.promoteOfficial) " • Official Circuit active" else ""}"
                    }
                )
            }
            onSuccess(updatedMatch)
        }
    }

    fun loadAdminReports() {
        viewModelScope.launch {
            _uiState.update { it.copy(reports = repo.getReports()) }
        }
    }

    // --- Fan Live Interactions & Reports ---
    fun placePreRaceBet(fanName: String, riderName: String) {
        val circuitId = _uiState.value.activeCircuit.id
        viewModelScope.launch {
            val current = repo.getFanInteraction(circuitId)
            val updatedBets = current.bets.toMutableMap().apply {
                put(fanName.ifBlank { "Fan_${_uiState.value.deviceId.take(4)}" }, riderName)
            }
            repo.saveFanInteraction(circuitId, current.copy(bets = updatedBets))
            syncCircuitData(circuitId)
        }
    }

    fun voteMvp(riderName: String) {
        val circuitId = _uiState.value.activeCircuit.id
        viewModelScope.launch {
            val current = repo.getFanInteraction(circuitId)
            val updatedVotes = current.mvpVotes.toMutableMap()
            updatedVotes[riderName] = (updatedVotes[riderName] ?: 0) + 1
            repo.saveFanInteraction(circuitId, current.copy(mvpVotes = updatedVotes))
            syncCircuitData(circuitId)
        }
    }

    fun sendCheerMessage(fanName: String, targetRider: String, message: String) {
        if (message.isBlank()) return
        val circuitId = _uiState.value.activeCircuit.id
        viewModelScope.launch {
            val current = repo.getFanInteraction(circuitId)
            val msg = CheerMessage(
                id = "c_${syncedNowMs()}",
                fanName = fanName.ifBlank { "Afición" },
                targetRider = targetRider,
                text = message.trim(),
                timestampMs = syncedNowMs()
            )
            val updated = (listOf(msg) + current.cheerMessages).take(20)
            repo.saveFanInteraction(circuitId, current.copy(cheerMessages = updated))
            syncCircuitData(circuitId)
        }
    }

    fun tapStadiumOvation() {
        val circuitId = _uiState.value.activeCircuit.id
        viewModelScope.launch {
            val current = repo.getFanInteraction(circuitId)
            val nextCount = current.ovationCount + 1
            repo.saveFanInteraction(circuitId, current.copy(ovationCount = nextCount))
            if (nextCount % 10 == 0) {
                voiceEngine.playStadiumApplause()
            }
            syncCircuitData(circuitId)
        }
    }

    fun submitAppReport(senderName: String, type: String, message: String, circuitRequested: String = "") {
        if (message.isBlank()) return
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            val report = AppReport(
                id = "rep_${syncedNowMs()}",
                senderName = senderName.ifBlank { "Piloto" },
                type = type,
                message = message.trim(),
                circuitRequested = circuitRequested.trim(),
                timestampMs = syncedNowMs()
            )
            repo.submitReport(report)
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (es) "Mensaje enviado al Buzón de Dirección" else "Message sent to Director Inbox"
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        lanBridge.stopDirectorServer()
        PocketAudioService.stop(getApplication())
        voiceEngine.release()
    }
}
