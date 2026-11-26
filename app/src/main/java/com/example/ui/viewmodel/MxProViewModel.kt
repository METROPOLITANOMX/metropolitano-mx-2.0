package com.example.ui.viewmodel

import android.app.Application
import android.media.ToneGenerator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.UserPreferences
import com.example.data.model.ActiveRoomRider
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
import com.example.data.model.RiderAccount
import com.example.data.model.RiderColorProfile
import com.example.data.model.RiderStatus
import com.example.data.model.RoiHeight
import com.example.data.model.RoiSize
import com.example.data.model.SessionPhase
import com.example.data.model.SurfaceType
import com.example.data.model.computeEq200mMs
import com.example.data.model.computeRegularityIndex
import com.example.data.model.computeSpeedKmh
import com.example.data.model.formatDeltaMs
import com.example.data.model.formatLapMs
import com.example.data.model.sha256
import com.example.data.repository.MxProRepository
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

    private val _uiState = MutableStateFlow(MxUiState())
    val uiState: StateFlow<MxUiState> = _uiState.asStateFlow()

    private var lastOvationTenMilestone = 0
    private var lastSeenCheerId = ""
    private var lipoAlertFiredForSessionStart = 0L
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

        // Check live PA clip
        if (fan.paClipBase64.isNotBlank()) {
            voiceEngine.playRemotePaClipIfNew(fan.paClipBase64, fan.paClipTimestampMs)
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
            voiceEngine.speak("${latestCheer.fanName}$target: ${latestCheer.text}", flush = false)
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

        _uiState.update { state ->
            val refreshedMe = state.currentRider?.let { cur ->
                riders.firstOrNull { it.id == cur.id } ?: cur
            }
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
                currentRider = refreshedMe
            )
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
            val starterBike = BikeSetup(
                id = bikeId,
                riderId = riderId,
                riderName = name.trim(),
                discipline = _uiState.value.activeCircuit.surface,
                modelName = if (_uiState.value.activeCircuit.surface == SurfaceType.SM) "Losi Promoto-SM" else "Losi Promoto-MX",
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
        val entry = existing ?: ActiveRoomRider(
            riderId = rider.id,
            riderName = rider.name,
            dorsal = bike?.dorsal ?: "01",
            bikeModel = bike?.modelName ?: "Losi Promoto-MX",
            discipline = bike?.discipline ?: _uiState.value.activeCircuit.surface,
            setupSummary = bike?.let {
                "${it.terrain} • ${it.forkOil}/${it.shockOil} • ${it.pinionSpur} • Gyro ${it.gyroPercent}%"
            } ?: "",
            sessionStartMs = syncedNowMs()
        )
        repo.upsertRoomRider(circuitId, entry)
        syncCircuitData(circuitId)
    }

    // --- Garage Pro ---
    fun saveBikeSetup(bike: BikeSetup, setActive: Boolean = true) {
        viewModelScope.launch {
            val rider = _uiState.value.currentRider ?: return@launch
            val saved = bike.copy(riderId = rider.id, riderName = rider.name)
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
                    snapshotBase64 = result.photoFinishBase64 ?: result.snapshotBase64
                )
                repo.pushCapturedPass(circuitId, capture)
            }

            val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
            val matched = result.matchedProfile
            if (matched != null) {
                // Confirm color profile match with green checkmark
                if (!matched.confirmed) {
                    repo.saveColorProfile(circuitId, matched.copy(confirmed = true))
                }
                val existingEntry = _uiState.value.roomState.activeRiders[matched.riderId]
                if (existingEntry != null && !existingEntry.colorConfirmed) {
                    repo.upsertRoomRider(circuitId, existingEntry.copy(colorConfirmed = true))
                }
                recordLapForRider(matched.riderId, result.triggerTimestampMs, station.id, colorNames)
                if (_uiState.value.antennaVoiceEnabled) {
                    voiceEngine.speak(
                        if (es) "Paso ${matched.riderName}" else "Lap ${matched.riderName}"
                    )
                }
            } else {
                // Any Pass mode fallback: attribute to sole active rider or announce colors
                val activeList = _uiState.value.roomState.activeRiders.values.toList()
                val antennaCfg = _uiState.value.antennas[station.id]
                if (antennaCfg?.detectionMode == DetectionMode.ANY_PASS && activeList.size == 1) {
                    val onlyRider = activeList.first()
                    recordLapForRider(onlyRider.riderId, result.triggerTimestampMs, station.id, colorNames)
                }
                if (_uiState.value.antennaVoiceEnabled) {
                    val cTxt = colorNames.joinToString(" ")
                    voiceEngine.speak(if (es) "Paso detectado $cTxt" else "Pass detected $cTxt")
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
                // First crossing starts the flying lap clock
                val started = roomRider.copy(
                    lastPassTimestampMs = passTimestampMs,
                    sessionStartMs = if (roomRider.sessionStartMs <= 0L) passTimestampMs else roomRider.sessionStartMs
                )
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

            // Update RoomRider
            val updatedRoomRider = roomRider.copy(
                lapsCount = roomRider.lapsCount + 1,
                bestLapMs = newBest,
                lastLapMs = lapMs,
                lastPassTimestampMs = passTimestampMs
            )
            repo.upsertRoomRider(circuit.id, updatedRoomRider)

            // Update Weekly Hotlap if beaten
            if (room.weeklyHotlapMs <= 0L || lapMs < room.weeklyHotlapMs) {
                repo.saveRoom(
                    room.copy(
                        weeklyHotlapMs = lapMs,
                        weeklyHotlapRider = riderAccount.name,
                        updatedAtMs = passTimestampMs
                    )
                )
            }

            // Update Bike counters & Rider global ranking metrics
            if (bike != null) {
                repo.saveBike(
                    bike.copy(
                        totalLaps = bike.totalLaps + 1,
                        usageMinutes = bike.usageMinutes + (lapMs / 60_000L).toInt().coerceAtLeast(1)
                    )
                )
            }

            val riderLaps = (listOf(lap) + state.circuitLaps.filter { it.riderId == riderId }).map { it.lapTimeMs }
            val reg = computeRegularityIndex(riderLaps)
            val allBest = if (riderAccount.bestLapMs <= 0L || lapMs < riderAccount.bestLapMs) lapMs else riderAccount.bestLapMs
            val eq200 = computeEq200mMs(allBest, circuit.lengthMeters)
            val avgSpd = if (riderAccount.avgSpeedKmh <= 0.0) speedKmh else (riderAccount.avgSpeedKmh * 0.7 + speedKmh * 0.3)

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

            // Spoken lap + delta + fun game-mode callout for pilot
            val es = StringsProvider.current(state.languageOverride).isSpanish
            val newLapCount = updatedRoomRider.lapsCount
            if (room.gameMode == GameMode.TIME_BOMB_45 && lapMs > 45_000L) {
                repo.upsertRoomRider(circuit.id, updatedRoomRider.copy(eliminated = true))
                voiceEngine.playBeep(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 450)
                if (state.currentRider?.id == riderId) {
                    voiceEngine.speak(if (es) "¡Bomba explotada! Superaste 45 segundos" else "Time bomb exploded! Over 45 seconds")
                }
            } else if (room.gameMode == GameMode.TURBO_SPRINT_3 && newLapCount == 3) {
                voiceEngine.playStadiumApplause()
                if (state.currentRider?.id == riderId) {
                    voiceEngine.speak(if (es) "¡Meta Turbo Sprint! Tres vueltas completadas en ${formatLapMs(lapMs)}" else "Turbo Sprint Finish! Three laps completed")
                }
            } else if (room.gameMode == GameMode.PURSUIT_HUNTER && room.weeklyHotlapMs > 0L && lapMs < room.weeklyHotlapMs) {
                if (state.currentRider?.id == riderId) {
                    voiceEngine.speak(if (es) "¡Cazaste al líder! Nuevo récord ${formatLapMs(lapMs)}" else "Leader caught! New record ${formatLapMs(lapMs)}")
                }
            } else if (state.currentRider?.id == riderId) {
                val secStr = formatLapMs(lapMs)
                val deltaTxt = formatDeltaMs(deltaMs)
                if (room.gameMode == GameMode.CONSISTENCY_DUEL) {
                    voiceEngine.speak(
                        if (es) "Vuelta $secStr, regularidad ${String.format("%.0f", reg)} por ciento"
                        else "Lap $secStr, consistency ${String.format("%.0f", reg)} percent"
                    )
                } else {
                    voiceEngine.speak(
                        if (es) "Vuelta $secStr, diferencia $deltaTxt"
                        else "Lap $secStr, delta $deltaTxt"
                    )
                }
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
        _uiState.update { it.copy(antennaVoiceEnabled = !it.antennaVoiceEnabled) }
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
        val updated = current.copy(
            circuitId = _uiState.value.activeCircuit.id,
            gameMode = gameMode ?: current.gameMode,
            heatDurationSec = heatDurationSec ?: current.heatDurationSec,
            antiVibration = antiVibration ?: current.antiVibration,
            flagState = flagState ?: current.flagState,
            sessionPhase = if (flagState == FlagState.YELLOW) SessionPhase.PAUSED_YELLOW else current.sessionPhase,
            updatedAtMs = syncedNowMs()
        )
        viewModelScope.launch {
            repo.saveRoom(updated)
            // Also propagate antiVibration to antennas
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
        val room = _uiState.value.roomState.copy(
            circuitId = _uiState.value.activeCircuit.id,
            sessionPhase = SessionPhase.WARMUP,
            flagState = FlagState.GREEN,
            phaseStartTimeMs = now,
            phaseEndTimeMs = now + 180_000L,
            friendlyModeActive = friendly,
            updatedAtMs = now
        )
        viewModelScope.launch {
            repo.saveRoom(room)
            val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
            voiceEngine.speak(if (es) "Warm Up iniciado, tres minutos" else "Warm-Up started, three minutes")
            syncCircuitData(room.circuitId)
        }
    }

    fun start30sCountdown(friendly: Boolean = false) {
        val now = syncedNowMs()
        val gateAt = now + 30_000L
        val room = _uiState.value.roomState.copy(
            circuitId = _uiState.value.activeCircuit.id,
            sessionPhase = SessionPhase.COUNTDOWN_30,
            flagState = FlagState.GREEN,
            phaseStartTimeMs = now,
            phaseEndTimeMs = gateAt,
            gateDropTimeMs = gateAt,
            friendlyModeActive = friendly,
            updatedAtMs = now
        )
        viewModelScope.launch {
            repo.saveRoom(room)
            val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
            voiceEngine.speak(if (es) "Semáforo treinta segundos" else "Thirty seconds start gate")
            syncCircuitData(room.circuitId)
        }
    }

    fun startRandomMxGate(friendly: Boolean = false) {
        val now = syncedNowMs()
        val randomDelayMs = (4000L..11000L).random()
        val gateAt = now + randomDelayMs
        val room = _uiState.value.roomState.copy(
            circuitId = _uiState.value.activeCircuit.id,
            sessionPhase = SessionPhase.RANDOM_GATE,
            flagState = FlagState.GREEN,
            phaseStartTimeMs = now,
            phaseEndTimeMs = gateAt,
            gateDropTimeMs = gateAt,
            friendlyModeActive = friendly,
            updatedAtMs = now
        )
        viewModelScope.launch {
            repo.saveRoom(room)
            val es = StringsProvider.current(_uiState.value.languageOverride).isSpanish
            voiceEngine.speak(if (es) "Pilotos en valla, atentos a la caída" else "Riders on the gate, watch the drop")
            syncCircuitData(room.circuitId)
            delay(randomDelayMs)
            voiceEngine.playBeep(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 450)
            voiceEngine.speak(if (es) "¡Valla abajo! ¡Gas!" else "Gate drop! Go!")
            repo.saveRoom(
                room.copy(
                    sessionPhase = SessionPhase.RACING,
                    phaseStartTimeMs = gateAt,
                    phaseEndTimeMs = gateAt + room.heatDurationSec * 1000L,
                    updatedAtMs = syncedNowMs()
                )
            )
            syncCircuitData(room.circuitId)
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
            antiVibration = antiVibration ?: current.antiVibration
        )
        viewModelScope.launch {
            repo.saveAntennaConfig(updated)
            syncCircuitData(circuitId)
        }
    }

    fun triggerRemoteCalibration(antennaId: String) {
        val circuitId = _uiState.value.activeCircuit.id
        viewModelScope.launch {
            val patch = JSONObject().apply {
                put("calibrateCommandAtMs", syncedNowMs())
                put("status", "CALIBRATING")
            }.toString()
            repo.patchAntennaTelemetry(circuitId, antennaId, patch)
            syncCircuitData(circuitId)
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
        viewModelScope.launch {
            val next = if (rider.status == RiderStatus.ACTIVE) RiderStatus.BLOCKED else RiderStatus.ACTIVE
            repo.saveRider(rider.copy(status = next))
            _uiState.update { it.copy(riders = repo.getRiders()) }
        }
    }

    fun deleteRiderAccount(riderId: String) {
        viewModelScope.launch {
            repo.deleteRider(riderId)
            _uiState.update { it.copy(riders = repo.getRiders()) }
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
