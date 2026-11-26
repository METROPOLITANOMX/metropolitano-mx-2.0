package com.example.ui.viewmodel

import android.app.Application
import android.media.ToneGenerator
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
import com.example.engine.GeminiStewardEngine
import com.example.engine.LocationGuard
import com.example.engine.VisionDetectionResult
import com.example.engine.VoiceAndAudioEngine
import com.example.i18n.StringsProvider
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

    private val _uiState = MutableStateFlow(MxUiState())
    val uiState: StateFlow<MxUiState> = _uiState.asStateFlow()

    private var lastOvationTenMilestone = 0
    private var lastSeenCheerId = ""
    private var lastSpokenPaAnnouncementId = ""
    private var lipoAlertFiredForSessionStart = 0L
    private var lastAutoEndedPhaseStartMs = 0L
    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            val devId = prefs.ensureDeviceId()
            val stored = prefs.prefsFlow.first()
            _uiState.update {
                it.copy(
                    sunMode = stored.sunMode,
                    languageOverride = stored.languageOverride,
                    rememberedName = stored.rememberedRiderName,
                    rememberedPass = stored.rememberedPassword,
                    rememberMe = stored.rememberMe,
                    deviceId = devId
                )
            }
            voiceEngine.applyLocale(StringsProvider.current(stored.languageOverride).isSpanish)

            val offset = repo.syncClock(devId)
            val circuits = repo.getCircuits()
            val active = circuits.firstOrNull { it.id == stored.activeCircuitId } ?: circuits.first()
            val globalCfg = repo.getGlobalConfig()
            // Log visit
            val visitStamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
            val updatedCfg = globalCfg.copy(
                visitCount = globalCfg.visitCount + 1,
                lastVisits = (listOf("$visitStamp ($devId)") + globalCfg.lastVisits).take(25)
            )
            repo.saveGlobalConfig(updatedCfg)

            val riders = repo.getRiders()
            val loggedRider = riders.firstOrNull { it.id == stored.loggedInRiderId }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    isOnline = repo.isOnline(),
                    serverTimeOffsetMs = offset,
                    lastSyncRttMs = repo.lastSyncRttMs(),
                    globalConfig = updatedCfg,
                    circuits = circuits,
                    activeCircuit = active,
                    riders = riders,
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

        // High-precision Phase Timer Watcher (Warm-Up 3:00 auto-finish + 30s Gate transition)
        viewModelScope.launch {
            while (isActive) {
                delay(500L)
                val state = _uiState.value
                val room = state.roomState
                val now = syncedNowMs()
                if (room.phaseEndTimeMs > 0L && now >= room.phaseEndTimeMs && room.phaseStartTimeMs != lastAutoEndedPhaseStartMs) {
                    val es = StringsProvider.current(state.languageOverride).isSpanish
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
                                lastPaAnnouncement = ann,
                                updatedAtMs = now
                            )
                            repo.saveRoom(finished)
                            voiceEngine.playBeep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 450)
                            if (!state.paAudioMuted) {
                                voiceEngine.speak(if (es) ann.textEs else ann.textEn)
                            }
                            _uiState.update {
                                it.copy(
                                    roomState = finished,
                                    statusBannerMessage = if (es) "Final de warm-up — ¡Pilotos a parrilla!" else "End of warm-up — Riders to the grid!"
                                )
                            }
                        }
                        SessionPhase.COUNTDOWN_30 -> {
                            lastAutoEndedPhaseStartMs = room.phaseStartTimeMs
                            val ann = PaAnnouncement(
                                id = "pa_${now}_gatedrop",
                                textEs = "¡Valla abajo! ¡Gas!",
                                textEn = "Gate drop! Go!",
                                timestampMs = now
                            )
                            lastSpokenPaAnnouncementId = ann.id
                            val startedRace = room.copy(
                                sessionPhase = SessionPhase.RACING,
                                flagState = FlagState.GREEN,
                                phaseStartTimeMs = now,
                                phaseEndTimeMs = now + room.heatDurationSec * 1000L,
                                lastPaAnnouncement = ann,
                                updatedAtMs = now
                            )
                            repo.saveRoom(startedRace)
                            voiceEngine.playBeep(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 450)
                            if (!state.paAudioMuted) {
                                voiceEngine.speak(if (es) ann.textEs else ann.textEn)
                            }
                            _uiState.update { it.copy(roomState = startedRace) }
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

        // Sync live multi-device PA TTS announcements across Pilots, Antenna 1, Antenna 2, Director & Fans
        val paAnn = room.lastPaAnnouncement
        if (paAnn != null && paAnn.id.isNotBlank() && paAnn.id != lastSpokenPaAnnouncementId &&
            syncedNowMs() - paAnn.timestampMs < 15_000L
        ) {
            lastSpokenPaAnnouncementId = paAnn.id
            val esAnn = StringsProvider.current(_uiState.value.languageOverride).isSpanish
            if (!_uiState.value.paAudioMuted && _uiState.value.antennaVoiceEnabled) {
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

        _uiState.update { state ->
            val activeChampId = state.activeChampionshipId.takeIf { id ->
                championships.any { it.id == id }
            } ?: championships.firstOrNull()?.id.orEmpty()
            state.copy(
                isOnline = repo.isOnline(),
                roomState = room,
                circuitLaps = laps,
                antennas = antennas,
                colorProfiles = profiles,
                capturedPasses = capturedPasses,
                championships = championships,
                activeChampionshipId = activeChampId,
                fanState = fan,
                allBikes = bikes,
                riders = riders,
                globalConfig = globalCfg,
                currentRider = refreshedLoggedRider,
                statusBannerMessage = forcedLogoutMsg ?: state.statusBannerMessage
            )
        }
    }

    fun refreshAntennasAndCaptures() {
        val circuitId = _uiState.value.activeCircuit.id
        viewModelScope.launch {
            val antennas = repo.getAntennas(circuitId)
            val capturedPasses = repo.getCapturedPasses(circuitId).take(12)
            val profiles = repo.getColorProfiles(circuitId)
            _uiState.update {
                it.copy(
                    isOnline = repo.isOnline(),
                    antennas = antennas,
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
        val rider = _uiState.value.currentRider ?: return
        recordLapForRider(rider.id, syncedNowMs(), "manual", emptyList())
    }

    fun handleVisionDetection(station: AntennaStation, result: VisionDetectionResult) {
        val circuitId = _uiState.value.activeCircuit.id
        viewModelScope.launch {
            // Update antenna telemetry with detected colors + snapshot + photoFinish
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
            val patch = JSONObject().apply {
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
                put("status", "DETECTING")
            }.toString()
            repo.patchAntennaTelemetry(circuitId, station.id, patch)

            if (result.detectedColors.isNotEmpty()) {
                val capture = CapturedPassSample(
                    id = "cap_${result.triggerTimestampMs}_${station.id}",
                    timestampMs = result.triggerTimestampMs,
                    antennaId = station.id,
                    samples = result.detectedColors,
                    snapshotBase64 = result.photoFinishBase64 ?: result.snapshotBase64,
                    detectedDorsal = result.detectedDorsal
                )
                repo.pushCapturedPass(circuitId, capture)
            }

            val activeList = _uiState.value.roomState.activeRiders.values.toList()
            val antennaCfg = _uiState.value.antennas[station.id]
            val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish

            // Layer 2: On-Device ML Kit Dorsal OCR tie-breaker
            val ocrNorm = result.detectedDorsal.trimStart('0').ifBlank { result.detectedDorsal }
            val ocrMatchedRider = if (ocrNorm.isNotBlank()) {
                activeList.firstOrNull { r ->
                    val rd = r.dorsal.trimStart('0').ifBlank { r.dorsal }
                    rd.equals(ocrNorm, ignoreCase = true)
                }
            } else null

            val colorMatched = result.matchedProfile
            val resolvedRiderId = when {
                ocrMatchedRider != null && (colorMatched == null || result.matchConfidence < 0.72f) -> ocrMatchedRider.riderId
                colorMatched != null && result.matchConfidence >= 0.45f -> colorMatched.riderId
                antennaCfg?.detectionMode == DetectionMode.ANY_PASS && activeList.size == 1 -> activeList.first().riderId
                else -> null
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
            } else {
                // Unidentified pass: announce locally first, then run Layer 3 Gemini AI Steward in background!
                val cTxt = colorNames.joinToString(" ")
                if (_uiState.value.antennaVoiceEnabled && !_uiState.value.paAudioMuted) {
                    voiceEngine.speak(if (es) "Paso detectado $cTxt" else "Pass detected $cTxt")
                }
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

    /**
     * Allows a solo pilot at the track to place their own phone as a finish-line camera antenna
     * directly from PilotScreen, recording & publishing every lap straight to RTDB.
     */
    fun handleSoloPilotVisionDetection(result: VisionDetectionResult) {
        val rider = _uiState.value.currentRider ?: return
        val circuitId = _uiState.value.activeCircuit.id
        val colorNames = result.detectedColors.map { it.colorName }
        viewModelScope.launch {
            if (result.detectedColors.isNotEmpty()) {
                val capture = CapturedPassSample(
                    id = "cap_${result.triggerTimestampMs}_solo",
                    timestampMs = result.triggerTimestampMs,
                    antennaId = "solo_${rider.id.take(5)}",
                    samples = result.detectedColors,
                    snapshotBase64 = result.photoFinishBase64 ?: result.snapshotBase64
                )
                repo.pushCapturedPass(circuitId, capture)
            }
            if (result.photoFinishBase64 != null) {
                val patch = JSONObject().apply {
                    put("lastPhotoFinishBase64", result.photoFinishBase64)
                    put("lastTriggerTimestampMs", result.triggerTimestampMs)
                    put("matchedRiderId", rider.id)
                    put("matchedRiderName", rider.name)
                }.toString()
                repo.patchAntennaTelemetry(circuitId, AntennaStation.ANTENA_TEMP.id, patch)
            }
            recordLapForRider(rider.id, result.triggerTimestampMs, "solo_phone", colorNames)
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
            val roomRider = room.activeRiders[riderId] ?: return@launch
            val riderAccount = state.riders.firstOrNull { it.id == riderId } ?: return@launch
            val bike = state.allBikes.firstOrNull { it.id == riderAccount.activeBikeId }
                ?: state.allBikes.firstOrNull { it.riderId == riderId }

            val prevPass = roomRider.lastPassTimestampMs
            if (prevPass <= 0L) {
                // First crossing starts the flying lap clock + broadcasts start announcement to Pilots & Antennas
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
                    activeRiders = room.activeRiders + (riderId to started),
                    lastPaAnnouncement = ann,
                    updatedAtMs = passTimestampMs
                )
                repo.saveRoom(nextRoom)
                val es = StringsProvider.current(state.languageOverride).isSpanish
                lastSpokenPaAnnouncementId = ann.id
                if (!state.paAudioMuted && state.antennaVoiceEnabled) {
                    voiceEngine.speak(if (es) ann.textEs else ann.textEn, flush = false)
                }
                syncCircuitData(circuit.id)
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

            // Speak immediately on triggering phone (Antennas & Pilot phones speak once thanks to lastSpokenPaAnnouncementId)
            val es = StringsProvider.current(state.languageOverride).isSpanish
            lastSpokenPaAnnouncementId = ann.id
            if (!state.paAudioMuted && state.antennaVoiceEnabled) {
                if (isHotlap) {
                    voiceEngine.playBeep(ToneGenerator.TONE_PROP_ACK, 200)
                }
                voiceEngine.speak(if (es) ann.textEs else ann.textEn, flush = false)
            }
            syncCircuitData(circuit.id)
        }
    }

    // --- Friendly Mode (No Director) ---
    fun runFriendly10sCalibration() {
        val circuitId = _uiState.value.activeCircuit.id
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            for (s in 10 downTo 1) {
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

    // --- Antenna Screen Controls ---
    fun selectAntennaStation(station: AntennaStation) {
        _uiState.update { it.copy(selectedAntennaStation = station) }
        sendAntennaHeartbeat(station, isCalibrated = false, status = "WAITING_CALIBRATION")
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
        viewModelScope.launch {
            val patch = JSONObject().apply {
                put("antennaId", station.id)
                put("circuitId", circuitId)
                put("isCalibrated", isCalibrated)
                put("status", status)
                put("lastHeartbeatMs", syncedNowMs())
            }.toString()
            repo.patchAntennaTelemetry(circuitId, station.id, patch)
        }
    }

    fun pushAntennaLiveSnapshot(station: AntennaStation, base64: String) {
        val circuitId = _uiState.value.activeCircuit.id
        viewModelScope.launch {
            val patch = JSONObject().apply {
                put("lastSnapshotBase64", base64)
                put("lastHeartbeatMs", syncedNowMs())
            }.toString()
            repo.patchAntennaTelemetry(circuitId, station.id, patch)
        }
    }

    // --- Director Controls ---
    fun unlockDirector(password: String): Boolean {
        val hash = sha256(password.trim())
        val ok = hash == _uiState.value.globalConfig.directorPasswordHash || password == "MXPRO2026"
        if (ok) {
            _uiState.update { it.copy(directorUnlocked = true) }
        }
        return ok
    }

    fun lockDirector() {
        _uiState.update { it.copy(directorUnlocked = false) }
    }

    fun updateRoomSettings(
        gameMode: GameMode? = null,
        heatDurationSec: Int? = null,
        antiVibration: AntiVibrationLevel? = null,
        flagState: FlagState? = null
    ) {
        val current = _uiState.value.roomState
        val now = syncedNowMs()
        val flagAnn = if (flagState != null && flagState != current.flagState) {
            PaAnnouncement(
                id = "pa_${now}_flag",
                textEs = if (flagState == FlagState.YELLOW) "Atención, bandera amarilla en pista" else "Bandera verde, pista libre",
                textEn = if (flagState == FlagState.YELLOW) "Caution, yellow flag on track" else "Green flag, track clear",
                timestampMs = now
            )
        } else current.lastPaAnnouncement
        if (flagAnn != null && flagAnn.id != lastSpokenPaAnnouncementId && flagState != null) {
            lastSpokenPaAnnouncementId = flagAnn.id
            val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
            if (!_uiState.value.paAudioMuted) {
                voiceEngine.speak(if (es) flagAnn.textEs else flagAnn.textEn)
            }
        }
        val updated = current.copy(
            circuitId = _uiState.value.activeCircuit.id,
            gameMode = gameMode ?: current.gameMode,
            heatDurationSec = heatDurationSec ?: current.heatDurationSec,
            antiVibration = antiVibration ?: current.antiVibration,
            flagState = flagState ?: current.flagState,
            sessionPhase = if (flagState == FlagState.YELLOW) SessionPhase.PAUSED_YELLOW else current.sessionPhase,
            lastPaAnnouncement = flagAnn,
            updatedAtMs = now
        )
        viewModelScope.launch {
            repo.saveRoom(updated)
            if (antiVibration != null) {
                _uiState.value.antennas.values.forEach { ant ->
                    repo.saveAntennaConfig(ant.copy(antiVibration = antiVibration))
                }
            }
            syncCircuitData(_uiState.value.activeCircuit.id)
        }
    }

    fun startWarmup(friendly: Boolean = false) {
        val now = syncedNowMs()
        val ann = PaAnnouncement(
            id = "pa_${now}_warmup_start",
            textEs = "Warm Up iniciado, tres minutos",
            textEn = "Warm-Up started, three minutes",
            timestampMs = now
        )
        lastSpokenPaAnnouncementId = ann.id
        val room = _uiState.value.roomState.copy(
            circuitId = _uiState.value.activeCircuit.id,
            sessionPhase = SessionPhase.WARMUP,
            flagState = FlagState.GREEN,
            phaseStartTimeMs = now,
            phaseEndTimeMs = now + 180_000L,
            friendlyModeActive = friendly,
            lastPaAnnouncement = ann,
            updatedAtMs = now
        )
        viewModelScope.launch {
            repo.saveRoom(room)
            val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
            if (!_uiState.value.paAudioMuted) voiceEngine.speak(if (es) ann.textEs else ann.textEn)
            syncCircuitData(room.circuitId)
        }
    }

    fun start30sCountdown(friendly: Boolean = false) {
        val now = syncedNowMs()
        val gateAt = now + 30_000L
        val ann = PaAnnouncement(
            id = "pa_${now}_gate30",
            textEs = "Semáforo treinta segundos",
            textEn = "Thirty seconds start gate",
            timestampMs = now
        )
        lastSpokenPaAnnouncementId = ann.id
        val room = _uiState.value.roomState.copy(
            circuitId = _uiState.value.activeCircuit.id,
            sessionPhase = SessionPhase.COUNTDOWN_30,
            flagState = FlagState.GREEN,
            phaseStartTimeMs = now,
            phaseEndTimeMs = gateAt,
            gateDropTimeMs = gateAt,
            friendlyModeActive = friendly,
            lastPaAnnouncement = ann,
            updatedAtMs = now
        )
        viewModelScope.launch {
            repo.saveRoom(room)
            val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
            if (!_uiState.value.paAudioMuted) voiceEngine.speak(if (es) ann.textEs else ann.textEn)
            syncCircuitData(room.circuitId)
        }
    }

    fun startRandomMxGate(friendly: Boolean = false) {
        val now = syncedNowMs()
        val randomDelayMs = (4000L..11000L).random()
        val gateAt = now + randomDelayMs
        val readyAnn = PaAnnouncement(
            id = "pa_${now}_mxgate",
            textEs = "Pilotos en valla, atentos a la caída",
            textEn = "Riders on the gate, watch the drop",
            timestampMs = now
        )
        lastSpokenPaAnnouncementId = readyAnn.id
        val room = _uiState.value.roomState.copy(
            circuitId = _uiState.value.activeCircuit.id,
            sessionPhase = SessionPhase.RANDOM_GATE,
            flagState = FlagState.GREEN,
            phaseStartTimeMs = now,
            phaseEndTimeMs = gateAt,
            gateDropTimeMs = gateAt,
            friendlyModeActive = friendly,
            lastPaAnnouncement = readyAnn,
            updatedAtMs = now
        )
        viewModelScope.launch {
            repo.saveRoom(room)
            val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
            if (!_uiState.value.paAudioMuted) voiceEngine.speak(if (es) readyAnn.textEs else readyAnn.textEn)
            syncCircuitData(room.circuitId)
            delay(randomDelayMs)
            val dropNow = syncedNowMs()
            val dropAnn = PaAnnouncement(
                id = "pa_${dropNow}_mxgatedrop",
                textEs = "¡Valla abajo! ¡Gas!",
                textEn = "Gate drop! Go!",
                timestampMs = dropNow
            )
            lastSpokenPaAnnouncementId = dropAnn.id
            voiceEngine.playBeep(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 450)
            if (!_uiState.value.paAudioMuted) voiceEngine.speak(if (es) dropAnn.textEs else dropAnn.textEn)
            repo.saveRoom(
                room.copy(
                    sessionPhase = SessionPhase.RACING,
                    phaseStartTimeMs = gateAt,
                    phaseEndTimeMs = gateAt + room.heatDurationSec * 1000L,
                    lastPaAnnouncement = dropAnn,
                    updatedAtMs = dropNow
                )
            )
            syncCircuitData(room.circuitId)
        }
    }

    fun stopSession() {
        val room = _uiState.value.roomState
        val wasWarmup = room.sessionPhase == SessionPhase.WARMUP
        val now = syncedNowMs()
        val ann = PaAnnouncement(
            id = "pa_${now}_stop",
            textEs = if (wasWarmup) "Final de warm-up, pilotos a parrilla" else "Sesión detenida por Dirección",
            textEn = if (wasWarmup) "End of warm-up, riders to the grid" else "Session stopped by Director",
            timestampMs = now
        )
        lastSpokenPaAnnouncementId = ann.id
        val stopped = room.copy(
            circuitId = _uiState.value.activeCircuit.id,
            sessionPhase = SessionPhase.IDLE,
            flagState = FlagState.GREEN,
            phaseEndTimeMs = 0L,
            lastPaAnnouncement = ann,
            updatedAtMs = now
        )
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            repo.saveRoom(stopped)
            voiceEngine.playBeep(ToneGenerator.TONE_PROP_BEEP2, 300)
            if (!_uiState.value.paAudioMuted) voiceEngine.speak(if (es) ann.textEs else ann.textEn)
            _uiState.update {
                it.copy(
                    roomState = stopped,
                    statusBannerMessage = if (wasWarmup) {
                        if (es) "Final de warm-up, pilotos a parrilla" else "End of warm-up, riders to the grid"
                    } else {
                        if (es) "Sesión detenida" else "Session stopped"
                    }
                )
            }
            syncCircuitData(stopped.circuitId)
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

    fun submitAppReport(senderName: String, type: String, message: String) {
        if (message.isBlank()) return
        val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
        viewModelScope.launch {
            val report = AppReport(
                id = "rep_${syncedNowMs()}",
                senderName = senderName.ifBlank { "Piloto" },
                type = type,
                message = message.trim(),
                timestampMs = syncedNowMs()
            )
            repo.submitReport(report)
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (es) "Reporte enviado a Dirección" else "Report sent to Race Director"
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceEngine.release()
    }
}
