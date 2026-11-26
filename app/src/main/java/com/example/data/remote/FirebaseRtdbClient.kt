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

    @Volatile
    var lastNetworkError: String? = null
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
                    } else if (!resp.isSuccessful) {
                        lastNetworkError = "HTTP ${resp.code}"
                    }
                }
            } catch (e: Exception) {
                lastNetworkError = e.message
                Log.w(TAG, "Clock sync probe failed: ${e.message}")
            }
        }

        if (anySuccess) {
            serverTimeOffsetMs = bestOffset
            lastSyncRttMs = bestRtt
            isOnline = true
            lastNetworkError = null
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
                    if (!resp.isSuccessful) lastNetworkError = "GET $path HTTP ${resp.code}"
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
            lastNetworkError = e.message
            null
        }
    }

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
            lastNetworkError = e.message
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
                    lastNetworkError = "PUT $path HTTP ${resp.code}"
                    Log.w(TAG, "PUT failed $path code=${resp.code}")
                } else {
                    lastNetworkError = null
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network PUT failed at $path: ${e.message}")
            isOnline = false
            lastNetworkError = e.message
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
                    lastNetworkError = "PATCH $path HTTP ${resp.code}"
                    Log.w(TAG, "PATCH failed $path code=${resp.code}")
                } else {
                    lastNetworkError = null
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network PATCH failed at $path: ${e.message}")
            isOnline = false
            lastNetworkError = e.message
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
            lastNetworkError = e.message
            false
        }
    }

    // --- Global Config (lenient parser handles legacy fields, APK lock, & custom access codes) ---
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
                val customPwdSet = obj.optBoolean("directorCustomPasswordSet", false)
                val apkLocked = obj.optBoolean("apkDownloadLocked", false)
                val apkUrl = obj.optString("apkDownloadUrl", com.example.data.model.MxConstants.DEFAULT_APK_URL)
                    .ifBlank { com.example.data.model.MxConstants.DEFAULT_APK_URL }
                val visitsCount = obj.optInt("visitCount", 0)
                val visitsList = extractStringList(obj.opt("lastVisits"))

                val codesMap = linkedMapOf<String, com.example.data.model.InviteDownloadCode>()
                val acObj = obj.optJSONObject("accessCodes")
                if (acObj != null) {
                    val cKeys = acObj.keys()
                    while (cKeys.hasNext()) {
                        val ck = cKeys.next()
                        val item = acObj.optJSONObject(ck) ?: continue
                        val codeVal = item.optString("code", ck).ifBlank { ck }.uppercase()
                        codesMap[codeVal] = com.example.data.model.InviteDownloadCode(
                            code = codeVal,
                            circuitName = item.optString("circuitName", ""),
                            note = item.optString("note", ""),
                            promoteOfficial = item.optBoolean("promoteOfficial", true),
                            active = item.optBoolean("active", true),
                            redeemedCount = item.optInt("redeemedCount", 0),
                            createdAtMs = item.optLong("createdAtMs", 0L)
                        )
                    }
                }

                GlobalConfig(
                    registrationOpen = regOpen,
                    directorPasswordHash = pwdHash,
                    directorCustomPasswordSet = customPwdSet,
                    apkDownloadLocked = apkLocked,
                    apkDownloadUrl = apkUrl,
                    accessCodes = codesMap,
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

    // --- Antennas (Fault-tolerant parser so partial/null RTDB fields NEVER drop a connected camera!) ---
    suspend fun fetchAntennas(circuitId: String): Map<String, AntennaConfig> = withContext(Dispatchers.IO) {
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
        try {
            val req = Request.Builder().url("$BASE_URL/antennas/$circuitId.json").get().build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()
                isOnline = resp.isSuccessful
                if (!resp.isSuccessful || raw.isNullOrBlank() || raw == "null") {
                    return@withContext merged
                }
                val root = JSONObject(raw)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = root.optJSONObject(key) ?: continue
                    val parsed = parseAntennaConfigSafe(circuitId, key, child)
                    merged[parsed.antennaId] = parsed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchAntennas failed: ${e.message}")
            isOnline = false
            lastNetworkError = e.message
        }
        merged
    }

    /**
     * Scans all circuits under `antennas.json` (shallow telemetry check) so Director immediately notices
     * if a camera phone is broadcasting inside a different circuit ID.
     */
    suspend fun fetchAllCircuitAntennas(): Map<String, Map<String, AntennaConfig>> = withContext(Dispatchers.IO) {
        val out = linkedMapOf<String, Map<String, AntennaConfig>>()
        try {
            val req = Request.Builder().url("$BASE_URL/antennas.json").get().build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()
                if (!resp.isSuccessful || raw.isNullOrBlank() || raw == "null") return@withContext out
                val root = JSONObject(raw)
                val cKeys = root.keys()
                while (cKeys.hasNext()) {
                    val cid = cKeys.next()
                    val cObj = root.optJSONObject(cid) ?: continue
                    val subMap = linkedMapOf<String, AntennaConfig>()
                    val aKeys = cObj.keys()
                    while (aKeys.hasNext()) {
                        val aid = aKeys.next()
                        val aObj = cObj.optJSONObject(aid) ?: continue
                        subMap[aid] = parseAntennaConfigSafe(cid, aid, aObj)
                    }
                    out[cid] = subMap
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchAllCircuitAntennas failed: ${e.message}")
        }
        out
    }

    private fun parseAntennaConfigSafe(circuitId: String, key: String, obj: JSONObject): AntennaConfig {
        val id = obj.optString("antennaId", key).ifBlank { key }
        val cid = obj.optString("circuitId", circuitId).ifBlank { circuitId }
        val modeStr = obj.optString("detectionMode", "ANY_PASS")
        val roiSizeStr = obj.optString("roiSize", "M")
        val roiHeightStr = obj.optString("roiHeight", "MEDIUM")
        val orientStr = obj.optString("lineOrientation", "VERTICAL")
        val dirStr = obj.optString("directionFilter", "ANY")
        val antiVibStr = obj.optString("antiVibration", "MEDIUM")

        val labSamples = mutableListOf<com.example.data.model.LabSample>()
        when (val labNode = obj.opt("lastDetectedLab")) {
            is JSONArray -> {
                for (i in 0 until labNode.length()) {
                    labNode.optJSONObject(i)?.let { s ->
                        labSamples += com.example.data.model.LabSample(
                            l = s.optDouble("l", 0.0),
                            a = s.optDouble("a", 0.0),
                            b = s.optDouble("b", 0.0),
                            hex = s.optString("hex", "#FFFFFF").ifBlank { "#FFFFFF" },
                            colorName = s.optString("colorName", "Color")
                        )
                    }
                }
            }
            is JSONObject -> {
                val lk = labNode.keys()
                while (lk.hasNext()) {
                    labNode.optJSONObject(lk.next())?.let { s ->
                        labSamples += com.example.data.model.LabSample(
                            l = s.optDouble("l", 0.0),
                            a = s.optDouble("a", 0.0),
                            b = s.optDouble("b", 0.0),
                            hex = s.optString("hex", "#FFFFFF").ifBlank { "#FFFFFF" },
                            colorName = s.optString("colorName", "Color")
                        )
                    }
                }
            }
        }

        return AntennaConfig(
            antennaId = id,
            circuitId = cid,
            status = obj.optString("status", "WAITING_CALIBRATION"),
            isCalibrated = obj.optBoolean("isCalibrated", false),
            calibrateCommandAtMs = obj.optLong("calibrateCommandAtMs", 0L),
            detectionMode = runCatching { com.example.data.model.DetectionMode.valueOf(modeStr) }
                .getOrDefault(com.example.data.model.DetectionMode.ANY_PASS),
            roiSize = runCatching { com.example.data.model.RoiSize.valueOf(roiSizeStr) }
                .getOrDefault(com.example.data.model.RoiSize.M),
            roiHeight = runCatching { com.example.data.model.RoiHeight.valueOf(roiHeightStr) }
                .getOrDefault(com.example.data.model.RoiHeight.MEDIUM),
            lineOrientation = runCatching { com.example.data.model.LineOrientation.valueOf(orientStr) }
                .getOrDefault(com.example.data.model.LineOrientation.VERTICAL),
            linePosition = obj.optDouble("linePosition", 0.5).toFloat().coerceIn(0.1f, 0.9f),
            directionFilter = runCatching { com.example.data.model.PassingDirection.valueOf(dirStr) }
                .getOrDefault(com.example.data.model.PassingDirection.ANY),
            aiStewardEnabled = obj.optBoolean("aiStewardEnabled", true),
            antiVibration = runCatching { com.example.data.model.AntiVibrationLevel.valueOf(antiVibStr) }
                .getOrDefault(com.example.data.model.AntiVibrationLevel.MEDIUM),
            lastSnapshotBase64 = obj.optString("lastSnapshotBase64", ""),
            lastPhotoFinishBase64 = obj.optString("lastPhotoFinishBase64", ""),
            lastDetectedColors = extractStringList(obj.opt("lastDetectedColors")),
            lastDetectedHexes = extractStringList(obj.opt("lastDetectedHexes")),
            lastDetectedLab = labSamples,
            lastDetectedDorsal = obj.optString("lastDetectedDorsal", ""),
            lastConfidence = obj.optDouble("lastConfidence", 0.0).toFloat(),
            activeFps = obj.optInt("activeFps", 30),
            lastTriggerTimestampMs = obj.optLong("lastTriggerTimestampMs", 0L),
            matchedRiderId = obj.optString("matchedRiderId", ""),
            matchedRiderName = obj.optString("matchedRiderName", ""),
            lastHeartbeatMs = obj.optLong("lastHeartbeatMs", 0L)
        )
    }

    private fun extractStringList(raw: Any?): List<String> {
        val list = mutableListOf<String>()
        when (raw) {
            is JSONArray -> {
                for (i in 0 until raw.length()) {
                    raw.optString(i).takeIf { it.isNotBlank() }?.let { list.add(it) }
                }
            }
            is JSONObject -> {
                val ks = raw.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    raw.optString(k).takeIf { it.isNotBlank() }?.let { list.add(it) }
                }
            }
        }
        return list
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
