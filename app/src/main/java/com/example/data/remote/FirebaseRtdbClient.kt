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
import org.json.JSONArray
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
                isOnline = resp.isSuccessful
                if (!resp.isSuccessful || raw.isNullOrBlank() || raw == "null") {
                    return@withContext null
                }
                try {
                    moshi.adapter(T::class.java).lenient().fromJson(raw)
                } catch (e: Exception) {
                    Log.w(TAG, "JSON decode warning at $path: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network GET failed at $path: ${e.message}")
            isOnline = false
            null
        }
    }

    /**
     * Entry-by-entry fault-tolerant map reader: if any legacy/partial record in Firebase RTDB
     * fails Moshi validation, only that bad entry is skipped instead of discarding the entire list.
     */
    private suspend inline fun <reified T> getMap(path: String): Map<String, T> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE_URL/$path.json").get().build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()
                isOnline = resp.isSuccessful
                if (!resp.isSuccessful || raw.isNullOrBlank() || raw == "null") {
                    return@withContext emptyMap()
                }
                val adapter = moshi.adapter(T::class.java).lenient()
                val result = linkedMapOf<String, T>()
                val trimmed = raw.trim()
                when {
                    trimmed.startsWith("{") -> {
                        val obj = JSONObject(trimmed)
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val child = obj.opt(k)
                            if (child != null && child != JSONObject.NULL) {
                                try {
                                    adapter.fromJson(child.toString())?.let { parsed ->
                                        result[k] = parsed
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Skipping malformed entry '$k' at $path: ${e.message}")
                                }
                            }
                        }
                    }
                    trimmed.startsWith("[") -> {
                        val arr = JSONArray(trimmed)
                        for (i in 0 until arr.length()) {
                            val child = arr.opt(i)
                            if (child != null && child != JSONObject.NULL) {
                                try {
                                    adapter.fromJson(child.toString())?.let { parsed ->
                                        result[i.toString()] = parsed
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Skipping malformed array entry [$i] at $path: ${e.message}")
                                }
                            }
                        }
                    }
                }
                result
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network GET map failed at $path: ${e.message}")
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
                if (!resp.isSuccessful) {
                    Log.w(TAG, "PUT failed $path code=${resp.code}")
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network PUT failed at $path: ${e.message}")
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
                if (!resp.isSuccessful) {
                    Log.w(TAG, "PATCH failed $path code=${resp.code}")
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network PATCH failed at $path: ${e.message}")
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

    // --- Global Config (lenient parser handles legacy fields & RTDB arrays/maps) ---
    suspend fun fetchGlobalConfig(): GlobalConfig? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE_URL/config.json").get().build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()
                isOnline = resp.isSuccessful
                if (!resp.isSuccessful) return@withContext null
                if (raw.isNullOrBlank() || raw == "null") return@withContext GlobalConfig()
                val obj = JSONObject(raw)
                val regOpen = obj.optBoolean("registrationOpen", true)
                val pwdHash = obj.optString("directorPasswordHash", GlobalConfig().directorPasswordHash)
                    .ifBlank { GlobalConfig().directorPasswordHash }
                val visitsCount = obj.optInt("visitCount", 0)
                val visitsList = mutableListOf<String>()
                when (val lv = obj.opt("lastVisits")) {
                    is JSONArray -> {
                        for (i in 0 until lv.length()) {
                            lv.optString(i).takeIf { it.isNotBlank() }?.let { visitsList.add(it) }
                        }
                    }
                    is JSONObject -> {
                        val ks = lv.keys()
                        while (ks.hasNext()) {
                            val k = ks.next()
                            lv.optString(k).takeIf { it.isNotBlank() }?.let { visitsList.add(it) }
                        }
                    }
                }
                GlobalConfig(
                    registrationOpen = regOpen,
                    directorPasswordHash = pwdHash,
                    visitCount = visitsCount,
                    lastVisits = visitsList
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchGlobalConfig failed: ${e.message}")
            isOnline = false
            null
        }
    }

    suspend fun saveGlobalConfig(config: GlobalConfig): Boolean = putObject("config", config)

    // --- Circuits ---
    suspend fun fetchCircuits(): List<Circuit> {
        val map = getMap<Circuit>("circuits")
        val official = Circuit()
        if (map.isEmpty()) {
            if (isOnline) putObject("circuits/${official.id}", official)
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
        val fetched = getMap<AntennaConfig>("antennas/$circuitId")
        val merged = linkedMapOf(
            AntennaStation.ANTENA_1.id to AntennaConfig(
                antennaId = AntennaStation.ANTENA_1.id,
                circuitId = circuitId
            ),
            AntennaStation.ANTENA_2.id to AntennaConfig(
                antennaId = AntennaStation.ANTENA_2.id,
                circuitId = circuitId
            ),
            AntennaStation.ANTENA_TEMP.id to AntennaConfig(
                antennaId = AntennaStation.ANTENA_TEMP.id,
                circuitId = circuitId
            )
        )
        fetched.forEach { (key, value) ->
            val id = value.antennaId.ifBlank { key }
            merged[key] = value.copy(
                antennaId = id,
                circuitId = value.circuitId.ifBlank { circuitId }
            )
        }
        return merged
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
