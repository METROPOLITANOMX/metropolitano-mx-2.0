package com.example.data.repository

import android.content.Context
import com.example.data.local.CachedJsonEntity
import com.example.data.local.MxProDatabase
import com.example.data.local.PendingLapEntity
import com.example.data.model.ActiveRoomRider
import com.example.data.model.AntennaConfig
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
import com.example.data.remote.FirebaseRtdbClient
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MxProRepository(
    context: Context,
    val rtdb: FirebaseRtdbClient = FirebaseRtdbClient()
) {
    private val dao = MxProDatabase.getInstance(context).cacheDao()

    suspend fun syncClock(deviceId: String): Long = rtdb.syncServerTimeOffset(deviceId)
    fun syncedNowMs(): Long = rtdb.syncedNowMs()
    fun lastSyncRttMs(): Long = rtdb.lastSyncRttMs
    fun isOnline(): Boolean = rtdb.isOnline
    fun observeRoomEvents(circuitId: String) = rtdb.observePathEvents("rooms/$circuitId")
    fun observeAntennaEvents(circuitId: String) = rtdb.observePathEvents("antennas/$circuitId")

    suspend fun flushPendingLaps() {
        val pending = dao.getPendingLaps()
        val adapter = rtdb.moshi.adapter(LapRecord::class.java)
        for (item in pending) {
            val lap = adapter.fromJson(item.jsonPayload) ?: continue
            if (rtdb.pushLap(lap)) {
                dao.removePendingLap(item.lapId)
            }
        }
    }

    suspend fun getCircuits(): List<Circuit> {
        val remote = rtdb.fetchCircuits()
        if (rtdb.isOnline) {
            saveListCache("circuits", remote)
            return remote
        }
        return loadListCache<Circuit>("circuits").ifEmpty { listOf(Circuit()) }
    }

    suspend fun saveCircuit(circuit: Circuit): Boolean = rtdb.saveCircuit(circuit)

    suspend fun getGlobalConfig(): GlobalConfig = rtdb.fetchGlobalConfig()
    suspend fun saveGlobalConfig(config: GlobalConfig): Boolean = rtdb.saveGlobalConfig(config)

    suspend fun getRiders(): List<RiderAccount> {
        val remote = rtdb.fetchRiders()
        if (rtdb.isOnline) {
            saveListCache("riders", remote)
            return remote
        }
        return loadListCache("riders")
    }

    suspend fun getRider(riderId: String): RiderAccount? = rtdb.fetchRider(riderId)
    suspend fun saveRider(rider: RiderAccount): Boolean = rtdb.saveRider(rider)
    suspend fun deleteRider(riderId: String): Boolean = rtdb.deleteRider(riderId)

    suspend fun getAllBikes(): List<BikeSetup> {
        val remote = rtdb.fetchAllBikes()
        if (rtdb.isOnline) {
            saveListCache("bikes", remote)
            return remote
        }
        return loadListCache("bikes")
    }

    suspend fun saveBike(bike: BikeSetup): Boolean {
        val ok = rtdb.saveBike(bike)
        // Always update local cache too
        val current = loadListCache<BikeSetup>("bikes").toMutableList()
        current.removeAll { it.id == bike.id }
        current.add(bike)
        saveListCache("bikes", current)
        return ok
    }

    suspend fun deleteBike(bikeId: String): Boolean {
        val current = loadListCache<BikeSetup>("bikes").toMutableList()
        current.removeAll { it.id == bikeId }
        saveListCache("bikes", current)
        return rtdb.deleteBike(bikeId)
    }

    suspend fun getRoom(circuitId: String): RoomState = rtdb.fetchRoom(circuitId)
    suspend fun saveRoom(room: RoomState): Boolean = rtdb.saveRoom(room)
    suspend fun upsertRoomRider(circuitId: String, rider: ActiveRoomRider): Boolean =
        rtdb.upsertRoomRider(circuitId, rider)

    suspend fun deleteRoomRider(circuitId: String, riderId: String): Boolean =
        rtdb.deleteRoomRider(circuitId, riderId)

    suspend fun getCircuitLaps(circuitId: String): List<LapRecord> {
        val remote = rtdb.fetchCircuitLaps(circuitId)
        if (rtdb.isOnline) {
            saveListCache("laps_$circuitId", remote)
            return remote
        }
        return loadListCache("laps_$circuitId")
    }

    suspend fun recordLap(lap: LapRecord): Boolean {
        val localList = loadListCache<LapRecord>("laps_${lap.circuitId}").toMutableList()
        localList.add(0, lap)
        saveListCache("laps_${lap.circuitId}", localList)

        val ok = rtdb.pushLap(lap)
        if (!ok) {
            val json = rtdb.moshi.adapter(LapRecord::class.java).toJson(lap)
            dao.enqueuePendingLap(
                PendingLapEntity(
                    lapId = lap.id,
                    circuitId = lap.circuitId,
                    jsonPayload = json,
                    createdAtMs = lap.timestampMs
                )
            )
        }
        return ok
    }

    suspend fun clearCircuitLaps(circuitId: String): Boolean {
        saveListCache<LapRecord>("laps_$circuitId", emptyList())
        return rtdb.clearCircuitRoomLaps(circuitId)
    }

    suspend fun getAntennas(circuitId: String): Map<String, AntennaConfig> =
        rtdb.fetchAntennas(circuitId)

    suspend fun saveAntennaConfig(config: AntennaConfig): Boolean =
        rtdb.saveAntennaConfig(config)

    suspend fun patchAntennaTelemetry(circuitId: String, antennaId: String, patchJson: String): Boolean =
        rtdb.patchAntennaTelemetry(circuitId, antennaId, patchJson)

    suspend fun getColorProfiles(circuitId: String): List<RiderColorProfile> =
        rtdb.fetchColorProfiles(circuitId)

    suspend fun saveColorProfile(circuitId: String, profile: RiderColorProfile): Boolean =
        rtdb.saveColorProfile(circuitId, profile)

    suspend fun deleteColorProfile(circuitId: String, riderId: String): Boolean =
        rtdb.deleteColorProfile(circuitId, riderId)

    suspend fun getCapturedPasses(circuitId: String): List<CapturedPassSample> =
        rtdb.fetchCapturedPasses(circuitId)

    suspend fun pushCapturedPass(circuitId: String, capture: CapturedPassSample): Boolean =
        rtdb.pushCapturedPass(circuitId, capture)

    suspend fun getChampionships(circuitId: String): List<Championship> =
        rtdb.fetchChampionships(circuitId)

    suspend fun saveChampionship(champ: Championship): Boolean =
        rtdb.saveChampionship(champ)

    suspend fun getFanInteraction(circuitId: String): FanInteractionState =
        rtdb.fetchFanInteraction(circuitId)

    suspend fun saveFanInteraction(circuitId: String, state: FanInteractionState): Boolean =
        rtdb.saveFanInteraction(circuitId, state)

    suspend fun getReports(): List<AppReport> = rtdb.fetchReports()
    suspend fun submitReport(report: AppReport): Boolean = rtdb.submitReport(report)

    private suspend inline fun <reified T> saveListCache(key: String, list: List<T>) =
        withContext(Dispatchers.IO) {
            val type = Types.newParameterizedType(List::class.java, T::class.java)
            val json = rtdb.moshi.adapter<List<T>>(type).toJson(list)
            dao.putCache(CachedJsonEntity(key, json, System.currentTimeMillis()))
        }

    private suspend inline fun <reified T> loadListCache(key: String): List<T> =
        withContext(Dispatchers.IO) {
            val cached = dao.getCache(key) ?: return@withContext emptyList()
            val type = Types.newParameterizedType(List::class.java, T::class.java)
            rtdb.moshi.adapter<List<T>>(type).fromJson(cached.jsonPayload) ?: emptyList()
        }
}
