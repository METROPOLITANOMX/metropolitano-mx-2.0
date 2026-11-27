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
import com.example.data.model.InviteDownloadCode
import com.example.data.model.LapRecord
import com.example.data.model.RiderAccount
import com.example.data.model.RiderColorProfile
import com.example.data.model.RoomState
import com.example.data.remote.FirestoreDataSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MxProRepository(
    context: Context,
    val cloud: FirestoreDataSource
) {
    constructor(context: Context, db: FirebaseFirestore, auth: FirebaseAuth) : this(
        context = context,
        cloud = FirestoreDataSource(db, auth)
    )

    private val dao = MxProDatabase.getInstance(context).cacheDao()
    val moshi: Moshi get() = cloud.moshi

    suspend fun syncClock(deviceId: String): Long = cloud.syncServerTimeOffset(deviceId)
    fun syncedNowMs(): Long = cloud.syncedNowMs()
    fun lastSyncRttMs(): Long = cloud.lastSyncRttMs
    fun isOnline(): Boolean = cloud.isOnline
    fun lastCloudError(): String? = cloud.lastNetworkError
    fun isCurrentUserAdmin(): Boolean = cloud.isCurrentUserAdmin()

    fun observeRoomDoc(circuitId: String): Flow<RoomState?> = cloud.observeRoomDoc(circuitId)
    fun observeActiveRiders(circuitId: String): Flow<Map<String, ActiveRoomRider>> = cloud.observeActiveRiders(circuitId)
    fun observeAntennas(circuitId: String): Flow<Map<String, AntennaConfig>> = cloud.observeAntennas(circuitId)
    fun observeLaps(circuitId: String): Flow<List<LapRecord>> = cloud.observeLaps(circuitId)
    fun observeFanInteraction(circuitId: String): Flow<FanInteractionState> = cloud.observeFanInteraction(circuitId)

    suspend fun ensureUserProfile(displayName: String, email: String?): Result<Unit> =
        cloud.ensureUserProfile(displayName, email)

    suspend fun recordVisitTelemetry(visitCount: Int, lastVisits: List<String>): Result<Unit> =
        cloud.recordVisitTelemetry(visitCount, lastVisits)

    suspend fun saveInviteCode(entry: InviteDownloadCode): Result<Unit> =
        cloud.saveInviteCode(entry)

    suspend fun deleteInviteCode(code: String): Result<Unit> =
        cloud.deleteInviteCode(code)

    suspend fun redeemInviteCode(rawCode: String): Result<InviteDownloadCode?> =
        cloud.fetchAndRedeemInviteCode(rawCode)

    fun parseAntennaConfigFromLanJson(circuitId: String, key: String, obj: JSONObject): AntennaConfig =
        cloud.parseAntennaConfigFromLanJson(circuitId, key, obj)

    suspend fun flushPendingLaps() {
        val pending = dao.getPendingLaps()
        val adapter = moshi.adapter(LapRecord::class.java)
        for (item in pending) {
            val lap = adapter.fromJson(item.jsonPayload) ?: continue
            if (cloud.pushLap(lap).isSuccess) {
                dao.removePendingLap(item.lapId)
            }
        }
    }

    suspend fun getCircuits(): List<Circuit> {
        val remote = cloud.fetchCircuits()
        if (cloud.isOnline) {
            saveListCache("circuits", remote)
            return remote
        }
        return loadListCache<Circuit>("circuits").ifEmpty { listOf(Circuit()) }
    }

    suspend fun saveCircuit(circuit: Circuit, officialCodeUsed: String? = null): Boolean =
        cloud.saveCircuit(circuit, officialCodeUsed).isSuccess

    suspend fun getGlobalConfig(): GlobalConfig {
        val remote = cloud.fetchGlobalConfig()
        val cached = loadObjectCache<GlobalConfig>("global_config")
        if (remote != null && cloud.isOnline) {
            val merged = if (cached != null && cached.visitCount > remote.visitCount) {
                remote.copy(
                    visitCount = cached.visitCount,
                    lastVisits = cached.lastVisits.ifEmpty { remote.lastVisits }
                )
            } else {
                remote
            }
            saveObjectCache("global_config", merged)
            return merged
        }
        return cached ?: GlobalConfig()
    }

    suspend fun saveGlobalConfig(config: GlobalConfig): Boolean {
        saveObjectCache("global_config", config)
        return cloud.saveGlobalConfig(config).isSuccess
    }

    suspend fun getCachedRiders(): List<RiderAccount> = loadListCache("riders")

    suspend fun getRiders(): List<RiderAccount> {
        val remote = cloud.fetchRiders()
        val cached = loadListCache<RiderAccount>("riders")
        if (cloud.isOnline) {
            saveListCache("riders", remote)
            return remote
        }
        return cached
    }

    suspend fun getRider(riderId: String): RiderAccount? =
        cloud.fetchRider(riderId) ?: loadListCache<RiderAccount>("riders").firstOrNull { it.id == riderId }

    suspend fun saveRider(rider: RiderAccount): Boolean {
        val current = loadListCache<RiderAccount>("riders").toMutableList()
        current.removeAll { it.id == rider.id }
        current.add(rider)
        saveListCache("riders", current)
        return cloud.saveRider(rider).isSuccess
    }

    suspend fun deleteRider(riderId: String): Boolean {
        val current = loadListCache<RiderAccount>("riders").toMutableList()
        current.removeAll { it.id == riderId }
        saveListCache("riders", current)
        return cloud.deleteRider(riderId).isSuccess
    }

    suspend fun getAllBikes(): List<BikeSetup> {
        val remote = cloud.fetchAllBikes()
        if (cloud.isOnline) {
            saveListCache("bikes", remote)
            return remote
        }
        return loadListCache("bikes")
    }

    suspend fun saveBike(bike: BikeSetup): Boolean {
        val ok = cloud.saveBike(bike).isSuccess
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
        return cloud.deleteBike(bikeId).isSuccess
    }

    suspend fun getRoom(circuitId: String): RoomState {
        val remote = cloud.fetchRoom(circuitId)
        val cached = loadObjectCache<RoomState>("room_$circuitId")
        if (cloud.isOnline) {
            if (cached != null && cached.updatedAtMs > remote.updatedAtMs) {
                return cached
            }
            saveObjectCache("room_$circuitId", remote)
            return remote
        }
        return cached ?: remote
    }

    suspend fun saveRoom(room: RoomState): Boolean {
        saveObjectCache("room_${room.circuitId}", room)
        return cloud.saveRoom(room).isSuccess
    }

    suspend fun upsertRoomRider(circuitId: String, rider: ActiveRoomRider): Boolean {
        val cached = loadObjectCache<RoomState>("room_$circuitId") ?: RoomState(circuitId = circuitId)
        val merged = cached.copy(
            activeRiders = cached.activeRiders + (rider.riderId to rider),
            updatedAtMs = maxOf(cached.updatedAtMs, cloud.syncedNowMs())
        )
        saveObjectCache("room_$circuitId", merged)
        return cloud.upsertRoomRider(circuitId, rider).isSuccess
    }

    suspend fun deleteRoomRider(circuitId: String, riderId: String): Boolean {
        val cached = loadObjectCache<RoomState>("room_$circuitId")
        if (cached != null) {
            saveObjectCache(
                "room_$circuitId",
                cached.copy(
                    activeRiders = cached.activeRiders - riderId,
                    updatedAtMs = maxOf(cached.updatedAtMs, cloud.syncedNowMs())
                )
            )
        }
        return cloud.deleteRoomRider(circuitId, riderId).isSuccess
    }

    suspend fun getCircuitLaps(circuitId: String): List<LapRecord> {
        val remote = cloud.fetchCircuitLaps(circuitId)
        if (cloud.isOnline) {
            saveListCache("laps_$circuitId", remote)
            return remote
        }
        return loadListCache("laps_$circuitId")
    }

    suspend fun recordLap(lap: LapRecord): Boolean {
        val localList = loadListCache<LapRecord>("laps_${lap.circuitId}").toMutableList()
        localList.add(0, lap)
        saveListCache("laps_${lap.circuitId}", localList)

        val ok = cloud.pushLap(lap).isSuccess
        if (!ok) {
            val json = moshi.adapter(LapRecord::class.java).toJson(lap)
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
        return cloud.clearCircuitRoomLaps(circuitId).isSuccess
    }

    suspend fun getAntennas(circuitId: String): Map<String, AntennaConfig> =
        cloud.fetchAntennas(circuitId)

    suspend fun getAllCircuitAntennas(circuitIds: List<String>): Map<String, Map<String, AntennaConfig>> =
        cloud.fetchAllCircuitAntennas(circuitIds)

    suspend fun saveAntennaConfig(config: AntennaConfig): Boolean =
        cloud.saveAntennaConfig(config).isSuccess

    suspend fun patchAntennaTelemetry(
        circuitId: String,
        antennaId: String,
        patchFields: Map<String, Any?>,
        fallbackConfig: AntennaConfig? = null
    ): Boolean = cloud.patchAntennaTelemetry(circuitId, antennaId, patchFields, fallbackConfig).isSuccess

    suspend fun getColorProfiles(circuitId: String): List<RiderColorProfile> =
        cloud.fetchColorProfiles(circuitId)

    suspend fun saveColorProfile(circuitId: String, profile: RiderColorProfile): Boolean =
        cloud.saveColorProfile(circuitId, profile).isSuccess

    suspend fun deleteColorProfile(circuitId: String, riderId: String): Boolean =
        cloud.deleteColorProfile(circuitId, riderId).isSuccess

    suspend fun getCapturedPasses(circuitId: String): List<CapturedPassSample> =
        cloud.fetchCapturedPasses(circuitId)

    suspend fun pushCapturedPass(circuitId: String, capture: CapturedPassSample): Boolean =
        cloud.pushCapturedPass(circuitId, capture).isSuccess

    suspend fun getChampionships(circuitId: String): List<Championship> =
        cloud.fetchChampionships(circuitId)

    suspend fun saveChampionship(champ: Championship): Boolean =
        cloud.saveChampionship(champ).isSuccess

    suspend fun getFanInteraction(circuitId: String): FanInteractionState =
        cloud.fetchFanInteraction(circuitId)

    suspend fun saveFanInteraction(circuitId: String, state: FanInteractionState): Boolean =
        cloud.saveFanInteraction(circuitId, state).isSuccess

    suspend fun getReports(): List<AppReport> = cloud.fetchReports()
    suspend fun submitReport(report: AppReport): Boolean = cloud.submitReport(report).isSuccess

    private suspend inline fun <reified T> saveObjectCache(key: String, value: T) =
        withContext(Dispatchers.IO) {
            val json = moshi.adapter(T::class.java).toJson(value)
            dao.putCache(CachedJsonEntity(key, json, System.currentTimeMillis()))
        }

    private suspend inline fun <reified T> loadObjectCache(key: String): T? =
        withContext(Dispatchers.IO) {
            val cached = dao.getCache(key) ?: return@withContext null
            try {
                moshi.adapter(T::class.java).lenient().fromJson(cached.jsonPayload)
            } catch (_: Exception) {
                null
            }
        }

    private suspend inline fun <reified T> saveListCache(key: String, list: List<T>) =
        withContext(Dispatchers.IO) {
            val type = Types.newParameterizedType(List::class.java, T::class.java)
            val json = moshi.adapter<List<T>>(type).toJson(list)
            dao.putCache(CachedJsonEntity(key, json, System.currentTimeMillis()))
        }

    private suspend inline fun <reified T> loadListCache(key: String): List<T> =
        withContext(Dispatchers.IO) {
            val cached = dao.getCache(key) ?: return@withContext emptyList()
            try {
                val type = Types.newParameterizedType(List::class.java, T::class.java)
                moshi.adapter<List<T>>(type).fromJson(cached.jsonPayload) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
}
