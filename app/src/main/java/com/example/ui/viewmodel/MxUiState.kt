package com.example.ui.viewmodel

import com.example.data.model.AiStewardVerdict
import com.example.data.model.AntennaConfig
import com.example.data.model.AntennaStation
import com.example.data.model.AppReport
import com.example.data.model.BikeSetup
import com.example.data.model.CapturedPassSample
import com.example.data.model.Championship
import com.example.data.model.Circuit
import com.example.data.model.FanInteractionState
import com.example.data.model.GlobalConfig
import com.example.data.model.LapRecord
import com.example.data.model.RiderAccount
import com.example.data.model.RiderColorProfile
import com.example.data.model.RoomState
import com.example.engine.GeoStatus

object MxRoutes {
    const val HOME = "home"
    const val ANTENNA = "antenna"
    const val PILOT = "pilot"
    const val DIRECTOR = "director"
    const val LIVE_FAN = "live_fan"
}

data class MxUiState(
    val isLoading: Boolean = true,
    val isOnline: Boolean = true,
    val serverTimeOffsetMs: Long = 0L,
    val lastSyncRttMs: Long = 0L,
    val sunMode: Boolean = false,
    val languageOverride: String? = null,
    val deviceId: String = "",
    val globalConfig: GlobalConfig = GlobalConfig(),
    val circuits: List<Circuit> = listOf(Circuit()),
    val activeCircuit: Circuit = Circuit(),
    val geoStatus: GeoStatus? = null,
    val riders: List<RiderAccount> = emptyList(),
    val currentRider: RiderAccount? = null,
    val rememberedName: String = "",
    val rememberedPass: String = "",
    val rememberMe: Boolean = false,
    val allBikes: List<BikeSetup> = emptyList(),
    val roomState: RoomState = RoomState(),
    val circuitLaps: List<LapRecord> = emptyList(),
    val antennas: Map<String, AntennaConfig> = emptyMap(),
    val colorProfiles: List<RiderColorProfile> = emptyList(),
    val capturedPasses: List<CapturedPassSample> = emptyList(),
    val championships: List<Championship> = emptyList(),
    val activeChampionshipId: String = "",
    val fanState: FanInteractionState = FanInteractionState(),
    val reports: List<AppReport> = emptyList(),
    val selectedAntennaStation: AntennaStation = AntennaStation.ANTENA_1,
    val antennaVoiceEnabled: Boolean = true,
    val paAudioMuted: Boolean = false,
    val directorUnlocked: Boolean = false,
    val activeRouletteChallenge: String? = null,
    val aiStewardBusy: Boolean = false,
    val aiStewardError: String? = null,
    val lastAiVerdict: AiStewardVerdict? = null,
    val statusBannerMessage: String? = null,
    val lastBackupJson: String? = null
) {
    val myBikes: List<BikeSetup>
        get() = currentRider?.let { r -> allBikes.filter { it.riderId == r.id } } ?: emptyList()

    val activeBike: BikeSetup?
        get() {
            val mine = myBikes
            val id = currentRider?.activeBikeId
            return mine.firstOrNull { it.id == id } ?: mine.firstOrNull()
        }

    val paddockBikes: List<BikeSetup>
        get() = allBikes.filter { it.publishedToPaddock }

    val isLiveActivityOnTrack: Boolean
        get() = roomState.activeRiders.isNotEmpty() ||
            roomState.sessionPhase.name != "IDLE" ||
            circuitLaps.any { System.currentTimeMillis() - it.timestampMs < 600_000L }
}
