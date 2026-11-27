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
        const val PRIMARY_BASE_URL = "https://app-servidor-9bb38-default-rtdb.europe-west1.firebasedatabase.app/mx_pro"
        const val SECONDARY_BASE_URL = "https://metropolitano-mx-circuit-default-rtdb.firebaseio.com/mx_pro"
        const val TERTIARY_BASE_URL = "https://metropolitano-mx-circuit-default-rtdb.europe-west1.firebasedatabase.app/mx_pro"
        val CANDIDATE_BASE_URLS = listOf(PRIMARY_BASE_URL, SECONDARY_BASE_URL, TERTIARY_BASE_URL)
        const val BASE_URL = PRIMARY_BASE_URL
        private const val TAG = "FirebaseRtdbClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    @Volatile
    var activeBaseUrl: String = PRIMARY_BASE_URL
        private set

    @Volatile
    private var lastFallbackProbeMonoMs: Long = 0L

    @Volatile
    private var fallbacksReturned404: Boolean = false

    private fun orderedCandidateUrls(): List<String> =
        listOf(activeBaseUrl) + CANDIDATE_BASE_URLS.filter { it != activeBaseUrl }

    private fun buildUrl(base: String, path: String): String {
        val clean = path.trimStart('/').split("/").joinToString("/") { android.net.Uri.encode(it) }
        return "$base/$clean.json"
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

        repeat(3) {
            try {
                val wallBefore = System.currentTimeMillis()
                val monoBefore = SystemClock.elapsedRealtime()
                val body = """{"ts":{".sv":"timestamp"}}""".toRequestBody(JSON_MEDIA)
                executeWithFailover { base ->
                    Request.Builder().url(buildUrl(base, "_clock_sync/$dev")).put(body).build()
                }.use { resp ->
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
                        lastNetworkError = if (resp.code == 401 || resp.code == 403) {
                            "HTTP ${resp.code} (Reglas Firebase RTDB bloqueadas o caducadas)"
                        } else {
                            "HTTP ${resp.code}"
                        }
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

    private fun formatHttpError(verb: String, path: String, code: Int): String =
        if (code == 401 || code == 403) {
            "HTTP $code (Reglas Firebase RTDB bloqueadas o caducadas)"
        } else {
            "$verb $path HTTP $code"
        }

    private fun executeWithFailover(buildRequest: (String) -> Request): okhttp3.Response {
        val nowMono = SystemClock.elapsedRealtime()
        val shouldSkipDeadFallbacks = fallbacksReturned404 && (nowMono - lastFallbackProbeMonoMs) < 60_000L
        val candidates = if (shouldSkipDeadFallbacks) listOf(activeBaseUrl) else orderedCandidateUrls()
        var authDeniedResp: okhttp3.Response? = null
        var lastResp: okhttp3.Response? = null
        var saw404OnFallback = false

        for ((idx, base) in candidates.withIndex()) {
            val req = buildRequest(base)
            val resp = http.newCall(req).execute()
            if (resp.isSuccessful) {
                authDeniedResp?.close()
                lastResp?.close()
                activeBaseUrl = base
                fallbacksReturned404 = false
                return resp
            }
            if (base != PRIMARY_BASE_URL && resp.code == 404) {
                saw404OnFallback = true
            }
            if ((resp.code == 401 || resp.code == 403) && authDeniedResp == null) {
                authDeniedResp = resp
            } else {
                lastResp?.close()
                lastResp = resp
            }
            val shouldFailover = (resp.code == 401 || resp.code == 403 || resp.code == 404) && idx < candidates.lastIndex
            if (!shouldFailover) {
                break
            }
        }
        if (candidates.size > 1) {
            lastFallbackProbeMonoMs = nowMono
            if (saw404OnFallback) fallbacksReturned404 = true
        }
        // Prefer returning the 401/403 response over an unused fallback 404 so UI error state never flickers
        if (authDeniedResp != null) {
            lastResp?.close()
            return authDeniedResp
        }
        return lastResp ?: http.newCall(buildRequest(activeBaseUrl)).execute()
    }

    fun observePathEvents(path: String): Flow<String> = flow {
        while (currentCoroutineContext().isActive) {
            var authOrRuleBlocked = false
            try {
                val req = Request.Builder()
                    .url(buildUrl(activeBaseUrl, path))
                    .header("Accept", "text/event-stream")
                    .build()
                streamHttp.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        isOnline = true
                        lastNetworkError = null
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
                    } else if (resp.code == 401 || resp.code == 403 || resp.code == 404) {
                        isOnline = false
                        authOrRuleBlocked = true
                        if (resp.code == 401 || resp.code == 403) {
                            lastNetworkError = formatHttpError("SSE", path, resp.code)
                        } else {
                            activeBaseUrl = PRIMARY_BASE_URL
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "SSE stream reconnect for $path: ${e.message}")
                isOnline = false
            }
            delay(if (authOrRuleBlocked) 15_000L else 3000L)
        }
    }.flowOn(Dispatchers.IO)

    private suspend inline fun <reified T> getObject(path: String): T? = withContext(Dispatchers.IO) {
        try {
            executeWithFailover { base ->
                Request.Builder().url(buildUrl(base, path)).get().build()
            }.use { resp ->
                val raw = resp.body?.string()
                isOnline = resp.isSuccessful
                if (!resp.isSuccessful || raw.isNullOrBlank() || raw == "null") {
                    if (!resp.isSuccessful) {
                        lastNetworkError = formatHttpError("GET", path, resp.code)
                    } else {
                        lastNetworkError = null
                    }
                    return@withContext null
                }
                lastNetworkError = null
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
            executeWithFailover { base ->
                Request.Builder().url(buildUrl(base, path)).get().build()
            }.use { resp ->
                val raw = resp.body?.string()
                isOnline = resp.isSuccessful
                if (!resp.isSuccessful || raw.isNullOrBlank() || raw == "null") {
                    if (!resp.isSuccessful) {
                        lastNetworkError = formatHttpError("GET", path, resp.code)
                    } else {
                        lastNetworkError = null
                    }
                    return@withContext emptyMap()
                }
                lastNetworkError = null
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
            executeWithFailover { base ->
                Request.Builder()
                    .url(buildUrl(base, path))
                    .put(json.toRequestBody(JSON_MEDIA))
                    .build()
            }.use { resp ->
                isOnline = resp.isSuccessful
                if (!resp.isSuccessful) {
                    lastNetworkError = if (resp.code == 401) "PUT $path HTTP 401 (Reglas RTDB bloqueadas)" else "PUT $path HTTP ${resp.code}"
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
            executeWithFailover { base ->
                Request.Builder()
                    .url(buildUrl(base, path))
                    .patch(jsonObjectString.toRequestBody(JSON_MEDIA))
                    .build()
            }.use { resp ->
                isOnline = resp.isSuccessful
                if (!resp.isSuccessful) {
                    lastNetworkError = if (resp.code == 401) {
                        "PATCH $path HTTP 401 (Reglas Firebase RTDB bloqueadas o caducadas)"
                    } else {
                        "PATCH $path HTTP ${resp.code}"
                    }
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
            executeWithFailover { base ->
                Request.Builder().url(buildUrl(base, path)).delete().build()
            }.use { resp ->
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
            executeWithFailover { base ->
                Request.Builder().url(buildUrl(base, "config")).get().build()
            }.use { resp ->
                val raw = resp.body?.string()
                isOnline = resp.isSuccessful
                if (!resp.isSuccessful) {
                    lastNetworkError = formatHttpError("GET", "config", resp.code)
                    return@withContext null
                }
                lastNetworkError = null
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
            executeWithFailover { base ->
                Request.Builder().url(buildUrl(base, "antennas/$circuitId")).get().build()
            }.use { resp ->
                val raw = resp.body?.string()
                isOnline = resp.isSuccessful
                if (!resp.isSuccessful || raw.isNullOrBlank() || raw == "null") {
                    if (!resp.isSuccessful) {
                        lastNetworkError = formatHttpError("GET", "antennas/$circuitId", resp.code)
                    }
                    return@withContext merged
                }
                lastNetworkError = null
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
            executeWithFailover { base ->
                Request.Builder().url(buildUrl(base, "antennas")).get().build()
            }.use { resp ->
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

    fun parseAntennaConfigSafe(circuitId: String, key: String, obj: JSONObject): AntennaConfig {
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
            linePosition = obj.optDouble("linePosition", 0.42).toFloat().coerceIn(0.1f, 0.9f),
            colorZoneX = obj.optDouble("colorZoneX", 0.70).toFloat().coerceIn(0.15f, 0.85f),
            colorZoneY = obj.optDouble("colorZoneY", 0.50).toFloat().coerceIn(0.15f, 0.85f),
            singleTargetColorName = obj.optString("singleTargetColorName", ""),
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
