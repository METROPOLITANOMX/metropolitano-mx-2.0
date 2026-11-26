package com.example.data.remote

import android.os.SystemClock
import android.util.Log
import com.example.data.model.ActiveRoomRider
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
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class FirebaseRtdbClient {

    companion object {
        const val BASE_URL = "https://app-servidor-9bb38-default-rtdb.europe-west1.firebasedatabase.app/mx_pro"
        private const val TAG = "FirebaseRtdbClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val http = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val streamHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile
    var serverTimeOffsetMs: Long = 0L
        private set

    @Volatile
    var lastSyncRttMs: Long = 0L
        private set

    @Volatile
    var isOnline: Boolean = true
        private set

    fun syncedNowMs(): Long = System.currentTimeMillis() + serverTimeOffsetMs

    /**
     * Multi-sample NTP-style Firebase serverTimeOffset calibration:
     * Issues 3 probes with {".sv":"timestamp"} to Firebase RTDB and uses SystemClock.elapsedRealtime()
     * to measure round-trip time free from local wall-clock jitter, picking the lowest-RTT sample.
     */
    suspend fun syncServerTimeOffset(deviceId: String): Long = withContext(Dispatchers.IO) {
        var bestRtt = Long.MAX_VALUE
        var bestOffset = serverTimeOffsetMs
        var anySuccess = false

        val dev = deviceId.ifBlank { "dev" }
        val url = "$BASE_URL/_clock_sync/$dev.json"

        repeat(3) {
            try {
                val wallBefore = System.currentTimeMillis()
                val monoBefore = SystemClock.elapsedRealtime()
                val body = """{"ts":{".sv":"timestamp"}}""".toRequestBody(JSON_MEDIA)
                val req = Request.Builder().url(url).put(body).build()
                http.newCall(req).execute().use { resp ->
                    val monoAfter = SystemClock.elapsedRealtime()
                    val rtt = (monoAfter - monoBefore).coerceAtLeast(0L)
                    val raw = resp.body?.string()
                    if (resp.isSuccessful && !raw.isNullOrBlank()) {
                        val serverTs = JSONObject(raw).optLong("ts", 0L)
                        if (serverTs > 0L) {
                            anySuccess = true
                            val estimatedWallAtServerReply = wallBefore + (rtt / 2L)
                            val offset = serverTs - estimatedWallAtServerReply
                            if (rtt < bestRtt) {
                                bestRtt = rtt
                                bestOffset = offset
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Clock sync probe failed: ${e.message}")
            }
        }

        if (anySuccess) {
            serverTimeOffsetMs = bestOffset
            lastSyncRttMs = bestRtt
            isOnline = true
        } else {
            isOnline = false
        }
        serverTimeOffsetMs
    }

    /**
     * Opens a Firebase RTDB REST Server-Sent Events (SSE) stream on a sub-path
     * (e.g. "rooms/<circuitId>") so room phases, gate drops, flags, and antenna triggers
     * arrive immediately across all connected devices.
     */
    fun observePathEvents(path: String): Flow<String> = flow {
        while (currentCoroutineContext().isActive) {
            try {
                val req = Request.Builder()
                    .url("$BASE_URL/$path.json")
                    .header("Accept", "text/event-stream")
                    .build()
                streamHttp.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        isOnline = true
                        val bodyStream = resp.body?.byteStream()
                        if (bodyStream != null) {
                            val reader = BufferedReader(InputStreamReader(bodyStream))
                            var currentEvent = ""
                            while (currentCoroutineContext().isActive) {
                                val line = reader.readLine() ?: break
                                when {
                                    line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                                    line.startsWith("data:") -> {
                                        if (currentEvent == "put" || currentEvent == "patch") {
                                            emit(currentEvent)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "SSE stream reconnect for $path: ${e.message}")
                isOnline = false
            }
            delay(2000L)
        }
    }.flowOn(Dispatchers.IO)

    private suspend inline fun <reified T> getObject(path: String): T? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE_URL/$path.json").get().build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()
                if (!resp.isSuccessful || raw.isNullOrBlank() || raw == "null") {
                    if (resp.isSuccessful) isOnline = true
                    return@withContext null
                }
                isOnline = true
                moshi.adapter(T::class.java).fromJson(raw)
            }
        } catch (e: Exception) {
            isOnline = false
            null
        }
    }

    private suspend inline fun <reified T> getMap(path: String): Map<String, T> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE_URL/$path.json").get().build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()
                if (!resp.isSuccessful || raw.isNullOrBlank() || raw == "null") {
                    if (resp.isSuccessful) isOnline = true
                    return@withContext emptyMap()
                }
                isOnline = true
                val type = Types.newParameterizedType(Map::class.java, String::class.java, T::class.java)
                val adapter = moshi.adapter<Map<String, T>>(type)
                adapter.fromJson(raw) ?: emptyMap()
            }
        } catch (e: Exception) {
            isOnline = false
            emptyMap()
        }
    }

    private suspend inline fun <reified T> putObject(path: String, value: T): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = moshi.adapter(T::class.java).toJson(value)
            val req = Request.Builder()
                .url("$BASE_URL/$path.json")
                .put(json.toRequestBody(JSON_MEDIA))
                .build()
            http.newCall(req).execute().use { resp ->
                isOnline = resp.isSuccessful
                resp.isSuccessful
            }
        } catch (e: Exception) {
            isOnline = false
            false
        }
    }

    private suspend fun patchJson(path: String, jsonObjectString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE_URL/$path.json")
                .patch(jsonObjectString.toRequestBody(JSON_MEDIA))
                .build()
            http.newCall(req).execute().use { resp ->
                isOnline = resp.isSuccessful
                resp.isSuccessful
            }
        } catch (e: Exception) {
            isOnline = false
            false
        }
    }

    private suspend fun deletePath(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE_URL/$path.json").delete().build()
            http.newCall(req).execute().use { resp ->
                isOnline = resp.isSuccessful
                resp.isSuccessful
            }
        } catch (e: Exception) {
            isOnline = false
            false
        }
    }

    // --- Global Config ---
    suspend fun fetchGlobalConfig(): GlobalConfig = getObject<GlobalConfig>("config") ?: GlobalConfig()
    suspend fun saveGlobalConfig(config: GlobalConfig): Boolean = putObject("config", config)

    // --- Circuits ---
    suspend fun fetchCircuits(): List<Circuit> {
        val map = getMap<Circuit>("circuits")
        val official = Circuit()
        if (map.isEmpty()) {
            putObject("circuits/${official.id}", official)
            return listOf(official)
        }
        val list = map.values.toMutableList()
        if (list.none { it.id == official.id }) {
            list.add(0, official)
        }
        return list.sortedByDescending { it.isOfficial }
    }

    suspend fun saveCircuit(circuit: Circuit): Boolean = putObject("circuits/${circuit.id}", circuit)

    // --- Riders ---
    suspend fun fetchRiders(): List<RiderAccount> = getMap<RiderAccount>("riders").values.toList()
    suspend fun fetchRider(riderId: String): RiderAccount? = getObject("riders/$riderId")
    suspend fun saveRider(rider: RiderAccount): Boolean = putObject("riders/${rider.id}", rider)
    suspend fun deleteRider(riderId: String): Boolean = deletePath("riders/$riderId")

    // --- Bikes & World Paddock ---
    suspend fun fetchAllBikes(): List<BikeSetup> = getMap<BikeSetup>("bikes").values.toList()
    suspend fun saveBike(bike: BikeSetup): Boolean = putObject("bikes/${bike.id}", bike)
    suspend fun deleteBike(bikeId: String): Boolean = deletePath("bikes/$bikeId")

    // --- Room State ---
    suspend fun fetchRoom(circuitId: String): RoomState =
        getObject<RoomState>("rooms/$circuitId") ?: RoomState(circuitId = circuitId)

    suspend fun saveRoom(room: RoomState): Boolean = putObject("rooms/${room.circuitId}", room)

    suspend fun upsertRoomRider(circuitId: String, rider: ActiveRoomRider): Boolean =
        putObject("rooms/$circuitId/activeRiders/${rider.riderId}", rider)

    suspend fun deleteRoomRider(circuitId: String, riderId: String): Boolean =
        deletePath("rooms/$circuitId/activeRiders/$riderId")

    // --- Laps ---
    suspend fun fetchCircuitLaps(circuitId: String): List<LapRecord> =
        getMap<LapRecord>("laps/$circuitId").values.sortedByDescending { it.timestampMs }

    suspend fun pushLap(lap: LapRecord): Boolean =
        putObject("laps/${lap.circuitId}/${lap.id}", lap)

    suspend fun clearCircuitRoomLaps(circuitId: String): Boolean =
        deletePath("laps/$circuitId")

    // --- Antennas ---
    suspend fun fetchAntennas(circuitId: String): Map<String, AntennaConfig> {
        val map = getMap<AntennaConfig>("antennas/$circuitId")
        return map.ifEmpty {
            mapOf(
                AntennaStation.ANTENA_1.id to AntennaConfig(
                    antennaId = AntennaStation.ANTENA_1.id,
                    circuitId = circuitId
                ),
                AntennaStation.ANTENA_2.id to AntennaConfig(
                    antennaId = AntennaStation.ANTENA_2.id,
                    circuitId = circuitId
                )
            )
        }
    }

    suspend fun saveAntennaConfig(config: AntennaConfig): Boolean =
        putObject("antennas/${config.circuitId}/${config.antennaId}", config)

    suspend fun patchAntennaTelemetry(
        circuitId: String,
        antennaId: String,
        patchJsonStr: String
    ): Boolean = patchJson("antennas/$circuitId/$antennaId", patchJsonStr)

    // --- Warm-up Color Profiles & Multi-Capture Passes ---
    suspend fun fetchColorProfiles(circuitId: String): List<RiderColorProfile> =
        getMap<RiderColorProfile>("color_profiles/$circuitId").values.toList()

    suspend fun saveColorProfile(circuitId: String, profile: RiderColorProfile): Boolean =
        putObject("color_profiles/$circuitId/${profile.riderId}", profile)

    suspend fun deleteColorProfile(circuitId: String, riderId: String): Boolean =
        deletePath("color_profiles/$circuitId/$riderId")

    suspend fun fetchCapturedPasses(circuitId: String): List<CapturedPassSample> =
        getMap<CapturedPassSample>("color_captures/$circuitId").values
            .sortedByDescending { it.timestampMs }

    suspend fun pushCapturedPass(circuitId: String, capture: CapturedPassSample): Boolean =
        putObject("color_captures/$circuitId/${capture.id}", capture)

    // --- Championships ---
    suspend fun fetchChampionships(circuitId: String): List<Championship> =
        getMap<Championship>("championships/$circuitId").values
            .sortedByDescending { it.createdAtMs }

    suspend fun saveChampionship(champ: Championship): Boolean =
        putObject("championships/${champ.circuitId}/${champ.id}", champ)

    // --- Fan Interactions & PA ---
    suspend fun fetchFanInteraction(circuitId: String): FanInteractionState =
        getObject<FanInteractionState>("fan_live/$circuitId") ?: FanInteractionState()

    suspend fun saveFanInteraction(circuitId: String, state: FanInteractionState): Boolean =
        putObject("fan_live/$circuitId", state)

    // --- Reports Inbox ---
    suspend fun fetchReports(): List<AppReport> =
        getMap<AppReport>("reports").values.sortedByDescending { it.timestampMs }

    suspend fun submitReport(report: AppReport): Boolean =
        putObject("reports/${report.id}", report)
}
