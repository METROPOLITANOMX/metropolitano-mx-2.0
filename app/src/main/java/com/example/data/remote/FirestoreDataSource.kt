package com.example.data.remote

import android.os.SystemClock
import android.util.Log
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
import com.example.data.model.FanInteractionState
import com.example.data.model.FlagState
import com.example.data.model.GameMode
import com.example.data.model.GlobalConfig
import com.example.data.model.InviteDownloadCode
import com.example.data.model.LabSample
import com.example.data.model.LapRecord
import com.example.data.model.LayoutType
import com.example.data.model.LineOrientation
import com.example.data.model.MxConstants
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
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class FirestoreDataSource(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    companion object {
        private const val TAG = "FirestoreDataSource"
        private const val ADMIN_BOOTSTRAP_EMAIL = "richibikergsx@gmail.com"
        private val ID_SANITIZE = Regex("[^a-zA-Z0-9_\\-]")
    }

    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
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

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun isCurrentUserAdmin(): Boolean {
        val user = auth.currentUser ?: return false
        return user.isEmailVerified && user.email.equals(ADMIN_BOOTSTRAP_EMAIL, ignoreCase = true)
    }

    private fun sanitizeId(raw: String, fallbackPrefix: String = "doc"): String {
        val cleaned = raw.trim().replace(ID_SANITIZE, "_").take(120)
        return cleaned.ifBlank { "${fallbackPrefix}_${System.currentTimeMillis()}" }
    }

    fun syncedNowMs(): Long = System.currentTimeMillis() + serverTimeOffsetMs

    private inline fun <reified T : Enum<T>> safeEnum(raw: Any?, default: T): T {
        val name = raw as? String ?: return default
        return enumValues<T>().firstOrNull { it.name == name } ?: default
    }

    private fun asLong(raw: Any?, default: Long = 0L): Long = (raw as? Number)?.toLong() ?: default
    private fun asInt(raw: Any?, default: Int = 0): Int = (raw as? Number)?.toInt() ?: default
    private fun asDouble(raw: Any?, default: Double = 0.0): Double = (raw as? Number)?.toDouble() ?: default
    private fun asFloat(raw: Any?, default: Float = 0f): Float = (raw as? Number)?.toFloat() ?: default
    private fun asBool(raw: Any?, default: Boolean = false): Boolean = raw as? Boolean ?: default

    private fun asStringList(raw: Any?): List<String> =
        (raw as? List<*>)?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) } ?: emptyList()

    private fun asStringMap(raw: Any?): Map<String, String> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        map.forEach { (k, v) ->
            val ks = k?.toString()
            val vs = v?.toString()
            if (!ks.isNullOrBlank() && vs != null) out[ks] = vs
        }
        return out
    }

    private fun asIntMap(raw: Any?): Map<String, Int> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        val out = linkedMapOf<String, Int>()
        map.forEach { (k, v) ->
            val ks = k?.toString()
            val vi = (v as? Number)?.toInt()
            if (!ks.isNullOrBlank() && vi != null) out[ks] = vi
        }
        return out
    }

    private fun asLabList(raw: Any?): List<LabSample> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            LabSample(
                l = asDouble(m["l"]),
                a = asDouble(m["a"]),
                b = asDouble(m["b"]),
                hex = (m["hex"] as? String)?.ifBlank { "#FFFFFF" } ?: "#FFFFFF",
                colorName = (m["colorName"] as? String)?.ifBlank { "Color" } ?: "Color"
            )
        }
    }

    private fun labListToMaps(samples: List<LabSample>): List<Map<String, Any>> =
        samples.take(12).map { s ->
            mapOf(
                "l" to s.l,
                "a" to s.a,
                "b" to s.b,
                "hex" to s.hex,
                "colorName" to s.colorName
            )
        }

    // --- Clock & Connectivity Probe ---
    suspend fun syncServerTimeOffset(deviceId: String): Long = withContext(Dispatchers.IO) {
        val uid = auth.currentUser?.uid ?: return@withContext serverTimeOffsetMs
        val ref = db.collection("app_config").document("telemetry")
        try {
            val wallBefore = System.currentTimeMillis()
            val monoBefore = SystemClock.elapsedRealtime()
            val snap = ref.get().await()
            val monoAfter = SystemClock.elapsedRealtime()
            val rtt = (monoAfter - monoBefore).coerceAtLeast(0L)
            val ts = snap.getTimestamp("updatedAt", ServerTimestampBehavior.NONE)?.toDate()?.time
            if (ts != null && ts > 0L) {
                val estimatedWall = wallBefore + (rtt / 2L)
                // Only adjust if drift is reasonable or measure server time via user doc touch
                lastSyncRttMs = rtt
                serverTimeOffsetMs = 0L
            } else {
                // Touch user profile timestamp for server clock calibration
                val userRef = db.collection("users").document(uid)
                val userSnap = userRef.get().await()
                if (userSnap.exists()) {
                    userRef.update("updatedAt", FieldValue.serverTimestamp()).await()
                }
                lastSyncRttMs = rtt
            }
            isOnline = true
            lastNetworkError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, ref.path)
            isOnline = false
            lastNetworkError = e.localizedMessage ?: "Firestore offline"
        }
        serverTimeOffsetMs
    }

    // --- User Profile (/users/{uid}) ---
    suspend fun ensureUserProfile(displayName: String, email: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            var path = "users/unauthenticated"
            try {
                val uid = requireUserId()
                val ref = db.collection("users").document(uid)
                path = ref.path
                val safeName = displayName.trim().ifBlank { "Piloto MX" }.take(80)
                val existing = ref.get().await()
                if (existing.exists()) {
                    val patch = mutableMapOf<String, Any>(
                        "displayName" to safeName,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (!email.isNullOrBlank()) patch["email"] = email.trim().take(160)
                    ref.update(patch).await()
                } else {
                    val payload = mutableMapOf<String, Any>(
                        "userId" to uid,
                        "displayName" to safeName,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (!email.isNullOrBlank()) payload["email"] = email.trim().take(160)
                    ref.set(payload).await()
                }
                isOnline = true
                lastNetworkError = null
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, path)
                Result.failure(e)
            }
        }

    // --- Global Config, Telemetry & Invite Codes ---
    suspend fun fetchGlobalConfig(): GlobalConfig? = withContext(Dispatchers.IO) {
        val globalPath = "app_config/global"
        try {
            requireUserId()
            val globalSnap = db.document(globalPath).get().await()
            val telemetrySnap = runCatching { db.document("app_config/telemetry").get().await() }.getOrNull()
            val codesMap = linkedMapOf<String, InviteDownloadCode>()
            if (isCurrentUserAdmin()) {
                val codesSnap = runCatching { db.collection("invite_codes").get().await() }.getOrNull()
                codesSnap?.documents?.forEach { doc ->
                    parseInviteCode(doc)?.let { codesMap[it.code] = it }
                }
            }

            val defaults = GlobalConfig()
            val visits = (telemetrySnap?.getLong("visitCount") ?: 0L).toInt()
            val lastVisits = asStringList(telemetrySnap?.get("lastVisits"))

            isOnline = true
            lastNetworkError = null

            if (!globalSnap.exists()) {
                val initial = defaults.copy(visitCount = visits, lastVisits = lastVisits, accessCodes = codesMap)
                if (isCurrentUserAdmin()) {
                    saveGlobalConfig(initial)
                }
                return@withContext initial
            }

            GlobalConfig(
                registrationOpen = globalSnap.getBoolean("registrationOpen") ?: true,
                directorPasswordHash = globalSnap.getString("directorPasswordHash")?.ifBlank { defaults.directorPasswordHash }
                    ?: defaults.directorPasswordHash,
                directorCustomPasswordSet = globalSnap.getBoolean("directorCustomPasswordSet") ?: false,
                apkDownloadLocked = globalSnap.getBoolean("apkDownloadLocked") ?: false,
                apkDownloadUrl = globalSnap.getString("apkDownloadUrl")?.ifBlank { MxConstants.DEFAULT_APK_URL }
                    ?: MxConstants.DEFAULT_APK_URL,
                accessCodes = codesMap,
                visitCount = visits,
                lastVisits = lastVisits
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, globalPath)
            isOnline = false
            lastNetworkError = e.localizedMessage
            null
        }
    }

    suspend fun saveGlobalConfig(config: GlobalConfig): Result<Unit> = withContext(Dispatchers.IO) {
        val path = "app_config/global"
        try {
            val uid = requireUserId()
            if (!isCurrentUserAdmin()) {
                return@withContext Result.failure(IllegalStateException("Admin permission required for global config"))
            }
            val ref = db.document(path)
            val snap = ref.get().await()
            if (snap.exists()) {
                ref.update(
                    mapOf(
                        "registrationOpen" to config.registrationOpen,
                        "apkDownloadLocked" to config.apkDownloadLocked,
                        "apkDownloadUrl" to config.apkDownloadUrl.ifBlank { MxConstants.DEFAULT_APK_URL }.take(300),
                        "directorPasswordHash" to config.directorPasswordHash,
                        "directorCustomPasswordSet" to config.directorCustomPasswordSet,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                ref.set(
                    mapOf(
                        "registrationOpen" to config.registrationOpen,
                        "apkDownloadLocked" to config.apkDownloadLocked,
                        "apkDownloadUrl" to config.apkDownloadUrl.ifBlank { MxConstants.DEFAULT_APK_URL }.take(300),
                        "directorPasswordHash" to config.directorPasswordHash,
                        "directorCustomPasswordSet" to config.directorCustomPasswordSet,
                        "ownerUid" to uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            isOnline = true
            lastNetworkError = null
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun recordVisitTelemetry(visitCount: Int, lastVisits: List<String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            val path = "app_config/telemetry"
            try {
                requireUserId()
                db.document(path).set(
                    mapOf(
                        "visitCount" to visitCount.coerceAtLeast(0),
                        "lastVisits" to lastVisits.take(25),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                isOnline = true
                lastNetworkError = null
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, path)
                Result.failure(e)
            }
        }

    suspend fun saveInviteCode(entry: InviteDownloadCode): Result<Unit> = withContext(Dispatchers.IO) {
        val codeId = sanitizeId(entry.code.uppercase(), "CODE")
        val path = "invite_codes/$codeId"
        try {
            val uid = requireUserId()
            val ref = db.document(path)
            val snap = ref.get().await()
            if (snap.exists()) {
                val updateMap = mutableMapOf<String, Any?>(
                    "circuitName" to entry.circuitName.ifBlank { "Circuito Oficial" }.take(100),
                    "note" to entry.note.take(200).ifBlank { null },
                    "promoteOfficial" to entry.promoteOfficial,
                    "active" to entry.active,
                    "redeemedCount" to entry.redeemedCount.coerceAtLeast(0),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                ref.update(updateMap).await()
            } else {
                val createMap = mutableMapOf<String, Any?>(
                    "code" to codeId,
                    "circuitName" to entry.circuitName.ifBlank { "Circuito Oficial" }.take(100),
                    "note" to entry.note.take(200).ifBlank { null },
                    "promoteOfficial" to entry.promoteOfficial,
                    "active" to entry.active,
                    "redeemedCount" to entry.redeemedCount.coerceAtLeast(0),
                    "ownerUid" to uid,
                    "createdAtMs" to entry.createdAtMs.coerceAtLeast(0L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                ref.set(createMap).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteInviteCode(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        val codeId = sanitizeId(code.uppercase(), "CODE")
        val path = "invite_codes/$codeId"
        try {
            requireUserId()
            db.document(path).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun fetchAndRedeemInviteCode(rawCode: String): Result<InviteDownloadCode?> =
        withContext(Dispatchers.IO) {
            val codeId = sanitizeId(rawCode.uppercase(), "CODE")
            val path = "invite_codes/$codeId"
            try {
                requireUserId()
                val ref = db.document(path)
                val snap = ref.get().await()
                val parsed = parseInviteCode(snap)
                if (parsed == null || !parsed.active) {
                    return@withContext Result.success(null)
                }
                val nextCount = parsed.redeemedCount + 1
                ref.update(
                    mapOf(
                        "redeemedCount" to nextCount,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                Result.success(parsed.copy(redeemedCount = nextCount))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, path)
                Result.failure(e)
            }
        }

    private fun parseInviteCode(doc: DocumentSnapshot): InviteDownloadCode? {
        if (!doc.exists()) return null
        val data = doc.data ?: return null
        val code = (data["code"] as? String)?.ifBlank { doc.id } ?: doc.id
        return InviteDownloadCode(
            code = code.uppercase(),
            circuitName = (data["circuitName"] as? String).orEmpty(),
            note = (data["note"] as? String).orEmpty(),
            promoteOfficial = asBool(data["promoteOfficial"], true),
            active = asBool(data["active"], true),
            redeemedCount = asInt(data["redeemedCount"], 0),
            createdAtMs = asLong(data["createdAtMs"], 0L)
        )
    }

    // --- Circuits ---
    suspend fun fetchCircuits(): List<Circuit> = withContext(Dispatchers.IO) {
        val path = "circuits"
        val official = Circuit()
        try {
            requireUserId()
            val snap = db.collection(path)
                .whereEqualTo("visibility", "PUBLIC")
                .get()
                .await()
            isOnline = true
            lastNetworkError = null
            val list = snap.documents.mapNotNull { doc -> parseCircuit(doc) }.toMutableList()
            if (list.none { it.id == official.id }) {
                if (isCurrentUserAdmin()) {
                    saveCircuit(official)
                }
                list.add(0, official)
            }
            list.sortedByDescending { it.isOfficial }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            isOnline = false
            lastNetworkError = e.localizedMessage
            listOf(official)
        }
    }

    suspend fun saveCircuit(circuit: Circuit, officialCodeUsed: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            val cid = sanitizeId(circuit.id, "circ")
            val path = "circuits/$cid"
            try {
                val uid = requireUserId()
                val ref = db.document(path)
                val existing = ref.get().await()
                if (existing.exists()) {
                    val updateMap = mutableMapOf<String, Any?>(
                        "visibility" to "PUBLIC",
                        "name" to circuit.name.trim().take(100).ifBlank { "Circuito Local" },
                        "countryCity" to circuit.countryCity.trim().take(100),
                        "surface" to circuit.surface.name,
                        "lengthMeters" to circuit.lengthMeters.coerceIn(10, 50000),
                        "layoutType" to circuit.layoutType.name,
                        "lat" to circuit.lat,
                        "lng" to circuit.lng,
                        "timezone" to circuit.timezone.take(80),
                        "gpsRadiusMeters" to circuit.gpsRadiusMeters.coerceIn(20, 50000),
                        "isOfficial" to circuit.isOfficial,
                        "officialCodeUsed" to officialCodeUsed?.take(40)?.ifBlank { null },
                        "updatedAt" to FieldValue.serverTimestamp()
                    ).filterValues { it != null } as Map<String, Any>
                    ref.update(updateMap).await()
                } else {
                    val createMap = mutableMapOf<String, Any?>(
                        "ownerUid" to uid,
                        "visibility" to "PUBLIC",
                        "name" to circuit.name.trim().take(100).ifBlank { "Circuito Local" },
                        "countryCity" to circuit.countryCity.trim().take(100),
                        "surface" to circuit.surface.name,
                        "lengthMeters" to circuit.lengthMeters.coerceIn(10, 50000),
                        "layoutType" to circuit.layoutType.name,
                        "lat" to circuit.lat,
                        "lng" to circuit.lng,
                        "timezone" to circuit.timezone.take(80),
                        "gpsRadiusMeters" to circuit.gpsRadiusMeters.coerceIn(20, 50000),
                        "isOfficial" to circuit.isOfficial,
                        "officialCodeUsed" to officialCodeUsed?.take(40)?.ifBlank { null },
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ).filterValues { it != null } as Map<String, Any>
                    ref.set(createMap).await()
                }
                isOnline = true
                lastNetworkError = null
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, path)
                Result.failure(e)
            }
        }

    private fun parseCircuit(doc: DocumentSnapshot): Circuit? {
        if (!doc.exists()) return null
        val d = doc.data ?: return null
        return Circuit(
            id = doc.id,
            name = (d["name"] as? String).orEmpty(),
            countryCity = (d["countryCity"] as? String).orEmpty(),
            surface = safeEnum(d["surface"], SurfaceType.MX),
            lengthMeters = asInt(d["lengthMeters"], 185),
            layoutType = safeEnum(d["layoutType"], LayoutType.MIXED),
            lat = asDouble(d["lat"], 40.4495),
            lng = asDouble(d["lng"], -3.7265),
            timezone = (d["timezone"] as? String).orEmpty().ifBlank { "Europe/Madrid" },
            gpsRadiusMeters = asInt(d["gpsRadiusMeters"], 450),
            isOfficial = asBool(d["isOfficial"], false)
        )
    }

    // --- Riders ---
    suspend fun fetchRiders(): List<RiderAccount> = withContext(Dispatchers.IO) {
        val path = "riders"
        try {
            val uid = requireUserId()
            val query = if (isCurrentUserAdmin()) {
                db.collection(path)
            } else {
                db.collection(path).whereEqualTo("status", RiderStatus.ACTIVE.name)
            }
            val snap = query.get().await()
            val byId = linkedMapOf<String, RiderAccount>()
            snap.documents.mapNotNull { parseRider(it) }.forEach { byId[it.id] = it }
            // Also fetch current user's own rider doc directly in case status == BLOCKED
            if (!byId.containsKey(uid)) {
                runCatching { db.collection(path).document(uid).get().await() }
                    .getOrNull()
                    ?.let { parseRider(it) }
                    ?.let { byId[it.id] = it }
            }
            isOnline = true
            lastNetworkError = null
            byId.values.toList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            isOnline = false
            lastNetworkError = e.localizedMessage
            emptyList()
        }
    }

    suspend fun fetchRider(riderId: String): RiderAccount? = withContext(Dispatchers.IO) {
        val cleanId = sanitizeId(riderId, "r")
        val path = "riders/$cleanId"
        try {
            requireUserId()
            val snap = db.document(path).get().await()
            isOnline = true
            parseRider(snap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            null
        }
    }

    suspend fun saveRider(rider: RiderAccount): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = requireUserId()
        val rid = sanitizeId(rider.id.ifBlank { uid }, "r")
        val path = "riders/$rid"
        // Non-admins can only mutate their own rider document
        if (rid != uid && !isCurrentUserAdmin()) {
            return@withContext Result.success(Unit)
        }
        try {
            val ref = db.document(path)
            val existing = ref.get().await()
            if (existing.exists()) {
                val updateMap = mutableMapOf<String, Any>(
                    "name" to rider.name.trim().ifBlank { "Piloto MX" }.take(60),
                    "totalLaps" to rider.totalLaps.coerceAtLeast(0),
                    "bestLapMs" to rider.bestLapMs.coerceAtLeast(0L),
                    "regularityIndex" to rider.regularityIndex.coerceIn(0.0, 100.0),
                    "avgSpeedKmh" to rider.avgSpeedKmh.coerceAtLeast(0.0),
                    "eq200mMs" to rider.eq200mMs.coerceAtLeast(0L),
                    "activeBikeId" to rider.activeBikeId.take(128),
                    "preferredSurface" to rider.preferredSurface.name,
                    "lastSeenAtMs" to rider.lastSeenAt.coerceAtLeast(0L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (isCurrentUserAdmin()) {
                    updateMap["status"] = rider.status.name
                }
                ref.update(updateMap).await()
            } else {
                val createMap = mapOf(
                    "ownerUid" to uid,
                    "name" to rider.name.trim().ifBlank { "Piloto MX" }.take(60),
                    "status" to RiderStatus.ACTIVE.name,
                    "totalLaps" to rider.totalLaps.coerceAtLeast(0),
                    "bestLapMs" to rider.bestLapMs.coerceAtLeast(0L),
                    "regularityIndex" to rider.regularityIndex.coerceIn(0.0, 100.0),
                    "avgSpeedKmh" to rider.avgSpeedKmh.coerceAtLeast(0.0),
                    "eq200mMs" to rider.eq200mMs.coerceAtLeast(0L),
                    "activeBikeId" to rider.activeBikeId.take(128),
                    "preferredSurface" to rider.preferredSurface.name,
                    "createdAtMs" to rider.createdAt.coerceAtLeast(0L),
                    "lastSeenAtMs" to rider.lastSeenAt.coerceAtLeast(0L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                ref.set(createMap).await()
            }
            isOnline = true
            lastNetworkError = null
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteRider(riderId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanId = sanitizeId(riderId, "r")
        val path = "riders/$cleanId"
        try {
            requireUserId()
            db.document(path).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    private fun parseRider(doc: DocumentSnapshot): RiderAccount? {
        if (!doc.exists()) return null
        val d = doc.data ?: return null
        return RiderAccount(
            id = doc.id,
            name = (d["name"] as? String).orEmpty(),
            status = safeEnum(d["status"], RiderStatus.ACTIVE),
            totalLaps = asInt(d["totalLaps"], 0),
            bestLapMs = asLong(d["bestLapMs"], 0L),
            regularityIndex = asDouble(d["regularityIndex"], 0.0),
            avgSpeedKmh = asDouble(d["avgSpeedKmh"], 0.0),
            eq200mMs = asLong(d["eq200mMs"], 0L),
            activeBikeId = (d["activeBikeId"] as? String).orEmpty(),
            preferredSurface = safeEnum(d["preferredSurface"], SurfaceType.MX),
            createdAt = asLong(d["createdAtMs"], 0L),
            lastSeenAt = asLong(d["lastSeenAtMs"], 0L)
        )
    }

    // --- Bikes & Public Paddock ---
    suspend fun fetchAllBikes(): List<BikeSetup> = withContext(Dispatchers.IO) {
        val path = "bikes"
        try {
            val uid = requireUserId()
            val byId = linkedMapOf<String, BikeSetup>()
            if (isCurrentUserAdmin()) {
                db.collection(path).get().await().documents
                    .mapNotNull { parseBike(it) }
                    .forEach { byId[it.id] = it }
            } else {
                db.collection(path).whereEqualTo("ownerUid", uid).get().await().documents
                    .mapNotNull { parseBike(it) }
                    .forEach { byId[it.id] = it }
                db.collection(path).whereEqualTo("publishedToPaddock", true).get().await().documents
                    .mapNotNull { parseBike(it) }
                    .forEach { byId[it.id] = it }
            }
            isOnline = true
            lastNetworkError = null
            byId.values.toList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            isOnline = false
            lastNetworkError = e.localizedMessage
            emptyList()
        }
    }

    suspend fun saveBike(bike: BikeSetup): Result<Unit> = withContext(Dispatchers.IO) {
        val bid = sanitizeId(bike.id, "b")
        val path = "bikes/$bid"
        try {
            val uid = requireUserId()
            val ref = db.document(path)
            val existing = ref.get().await()
            val common = mutableMapOf<String, Any>(
                "riderId" to (bike.riderId.ifBlank { uid }).take(128),
                "riderName" to bike.riderName.trim().ifBlank { "Piloto" }.take(60),
                "discipline" to bike.discipline.name,
                "modelName" to bike.modelName.trim().ifBlank { "Losi Promoto-MX" }.take(80),
                "dorsal" to bike.dorsal.trim().ifBlank { "01" }.take(16),
                "totalLaps" to bike.totalLaps.coerceAtLeast(0),
                "usageMinutes" to bike.usageMinutes.coerceAtLeast(0),
                "chainMaintAtLaps" to bike.chainMaintAtLaps.coerceAtLeast(0),
                "gyroMaintAtLaps" to bike.gyroMaintAtLaps.coerceAtLeast(0),
                "forkMaintAtLaps" to bike.forkMaintAtLaps.coerceAtLeast(0),
                "bearingMaintAtLaps" to bike.bearingMaintAtLaps.coerceAtLeast(0),
                "terrain" to bike.terrain,
                "forkOil" to bike.forkOil,
                "forkSpring" to bike.forkSpring,
                "shockOil" to bike.shockOil,
                "shockSpring" to bike.shockSpring,
                "rearSagMm" to bike.rearSagMm,
                "leanBarSetting" to bike.leanBarSetting,
                "frontBrakePercent" to bike.frontBrakePercent,
                "servoSaverSetting" to bike.servoSaverSetting,
                "gyroPercent" to bike.gyroPercent,
                "gyroMode" to bike.gyroMode,
                "escPunch" to bike.escPunch,
                "escDragBrake" to bike.escDragBrake,
                "escMaxBrake" to bike.escMaxBrake,
                "throttleExpo" to bike.throttleExpo,
                "lipoCutoff" to bike.lipoCutoff,
                "pinionTeeth" to bike.pinionTeeth,
                "spurTeeth" to bike.spurTeeth,
                "pinionSpur" to bike.pinionSpur,
                "frontTire" to bike.frontTire,
                "rearTire" to bike.rearTire,
                "tires" to bike.tires,
                "tireInsert" to bike.tireInsert,
                "lipoSpecs" to bike.lipoSpecs,
                "lipoCycles" to bike.lipoCycles,
                "notes" to bike.notes.take(600),
                "setupVotes" to bike.setupVotes.coerceAtLeast(0),
                "votedByRiderIds" to bike.votedByRiderIds.take(200),
                "photoBase64List" to bike.photoBase64List.take(3),
                "publishedToPaddock" to bike.publishedToPaddock,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (existing.exists()) {
                val existingOwner = existing.getString("ownerUid").orEmpty()
                if (existingOwner != uid && !isCurrentUserAdmin()) {
                    // Only vote updates are permitted on other riders' published Paddock bikes
                    ref.update(
                        mapOf(
                            "setupVotes" to bike.setupVotes.coerceAtLeast(0),
                            "votedByRiderIds" to bike.votedByRiderIds.take(200),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                } else {
                    ref.update(common).await()
                }
            } else {
                common["ownerUid"] = uid
                common["riderId"] = uid
                common["setupVotes"] = 0
                common["votedByRiderIds"] = emptyList<String>()
                common["createdAt"] = FieldValue.serverTimestamp()
                ref.set(common).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteBike(bikeId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val bid = sanitizeId(bikeId, "b")
        val path = "bikes/$bid"
        try {
            requireUserId()
            db.document(path).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    private fun parseBike(doc: DocumentSnapshot): BikeSetup? {
        if (!doc.exists()) return null
        val d = doc.data ?: return null
        return BikeSetup(
            id = doc.id,
            riderId = (d["riderId"] as? String).orEmpty(),
            riderName = (d["riderName"] as? String).orEmpty(),
            discipline = safeEnum(d["discipline"], SurfaceType.MX),
            modelName = (d["modelName"] as? String).orEmpty().ifBlank { "Losi Promoto-MX" },
            dorsal = (d["dorsal"] as? String).orEmpty().ifBlank { "01" },
            totalLaps = asInt(d["totalLaps"], 0),
            usageMinutes = asInt(d["usageMinutes"], 0),
            chainMaintAtLaps = asInt(d["chainMaintAtLaps"], 0),
            gyroMaintAtLaps = asInt(d["gyroMaintAtLaps"], 0),
            forkMaintAtLaps = asInt(d["forkMaintAtLaps"], 0),
            bearingMaintAtLaps = asInt(d["bearingMaintAtLaps"], 0),
            terrain = (d["terrain"] as? String).orEmpty(),
            forkOil = (d["forkOil"] as? String).orEmpty(),
            forkSpring = (d["forkSpring"] as? String).orEmpty(),
            shockOil = (d["shockOil"] as? String).orEmpty(),
            shockSpring = (d["shockSpring"] as? String).orEmpty(),
            rearSagMm = (d["rearSagMm"] as? String).orEmpty(),
            leanBarSetting = (d["leanBarSetting"] as? String).orEmpty(),
            frontBrakePercent = asInt(d["frontBrakePercent"], 75),
            servoSaverSetting = (d["servoSaverSetting"] as? String).orEmpty(),
            gyroPercent = asInt(d["gyroPercent"], 72),
            gyroMode = (d["gyroMode"] as? String).orEmpty(),
            escPunch = asInt(d["escPunch"], 5),
            escDragBrake = asInt(d["escDragBrake"], 10),
            escMaxBrake = asInt(d["escMaxBrake"], 75),
            throttleExpo = asInt(d["throttleExpo"], 0),
            lipoCutoff = (d["lipoCutoff"] as? String).orEmpty(),
            pinionTeeth = asInt(d["pinionTeeth"], 15),
            spurTeeth = asInt(d["spurTeeth"], 50),
            pinionSpur = (d["pinionSpur"] as? String).orEmpty(),
            frontTire = (d["frontTire"] as? String).orEmpty(),
            rearTire = (d["rearTire"] as? String).orEmpty(),
            tires = (d["tires"] as? String).orEmpty(),
            tireInsert = (d["tireInsert"] as? String).orEmpty(),
            lipoSpecs = (d["lipoSpecs"] as? String).orEmpty(),
            lipoCycles = asInt(d["lipoCycles"], 0),
            notes = (d["notes"] as? String).orEmpty(),
            setupVotes = asInt(d["setupVotes"], 0),
            votedByRiderIds = asStringList(d["votedByRiderIds"]),
            photoBase64List = asStringList(d["photoBase64List"]),
            publishedToPaddock = asBool(d["publishedToPaddock"], false)
        )
    }

    // --- Live Room & Active Riders ---
    fun observeRoomDoc(circuitId: String): Flow<RoomState?> = flow {
        val uid = requireUserId()
        val cid = sanitizeId(circuitId, "circ")
        val path = "circuits/$cid/live/room"
        emitAll(
            db.document(path).snapshots()
                .map { snap -> parseRoomDoc(cid, snap) }
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(fsErr ?: error, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    fun observeActiveRiders(circuitId: String): Flow<Map<String, ActiveRoomRider>> = flow {
        val uid = requireUserId()
        val cid = sanitizeId(circuitId, "circ")
        val path = "circuits/$cid/active_riders"
        emitAll(
            db.collection(path)
                .whereEqualTo("circuitId", cid)
                .snapshots()
                .map { qs ->
                    qs.documents.mapNotNull { parseActiveRoomRider(it) }
                        .associateBy { it.riderId }
                }
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(fsErr ?: error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun fetchRoom(circuitId: String): RoomState = withContext(Dispatchers.IO) {
        val cid = sanitizeId(circuitId, "circ")
        val roomPath = "circuits/$cid/live/room"
        try {
            requireUserId()
            val roomSnap = db.document(roomPath).get().await()
            val ridersSnap = db.collection("circuits").document(cid)
                .collection("active_riders")
                .whereEqualTo("circuitId", cid)
                .get()
                .await()
            val activeMap = ridersSnap.documents.mapNotNull { parseActiveRoomRider(it) }
                .associateBy { it.riderId }
            isOnline = true
            lastNetworkError = null
            (parseRoomDoc(cid, roomSnap) ?: RoomState(circuitId = cid)).copy(activeRiders = activeMap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, roomPath)
            isOnline = false
            lastNetworkError = e.localizedMessage
            RoomState(circuitId = cid)
        }
    }

    suspend fun saveRoom(room: RoomState): Result<Unit> = withContext(Dispatchers.IO) {
        val cid = sanitizeId(room.circuitId, "circ")
        val path = "circuits/$cid/live/room"
        try {
            requireUserId()
            val payload = mutableMapOf<String, Any?>(
                "circuitId" to cid,
                "gameMode" to room.gameMode.name,
                "sessionPhase" to room.sessionPhase.name,
                "flagState" to room.flagState.name,
                "heatDurationSec" to room.heatDurationSec.coerceIn(30, 7200),
                "phaseStartTimeMs" to room.phaseStartTimeMs.coerceAtLeast(0L),
                "phaseEndTimeMs" to room.phaseEndTimeMs.coerceAtLeast(0L),
                "gateDropTimeMs" to room.gateDropTimeMs.coerceAtLeast(0L),
                "friendlyModeActive" to room.friendlyModeActive,
                "lipoWarnMinutes" to room.lipoWarnMinutes.coerceIn(1, 120),
                "antiVibration" to room.antiVibration.name,
                "weeklyHotlapRider" to room.weeklyHotlapRider,
                "weeklyHotlapMs" to room.weeklyHotlapMs.coerceAtLeast(0L),
                "mxRecordRider" to room.mxRecordRider,
                "mxRecordMs" to room.mxRecordMs.coerceAtLeast(0L),
                "smRecordRider" to room.smRecordRider,
                "smRecordMs" to room.smRecordMs.coerceAtLeast(0L),
                "lastPaAnnouncement" to room.lastPaAnnouncement?.let { pa ->
                    mapOf(
                        "id" to pa.id,
                        "textEs" to pa.textEs,
                        "textEn" to pa.textEn,
                        "timestampMs" to pa.timestampMs,
                        "isHotlap" to pa.isHotlap
                    )
                },
                "lastAiVerdict" to room.lastAiVerdict?.let { v ->
                    mapOf(
                        "id" to v.id,
                        "matchedRiderId" to v.matchedRiderId,
                        "riderName" to v.riderName,
                        "detectedDorsal" to v.detectedDorsal,
                        "fairingSummary" to v.fairingSummary,
                        "confidencePercent" to v.confidencePercent,
                        "isRealPass" to v.isRealPass,
                        "reasoningEs" to v.reasoningEs,
                        "reasoningEn" to v.reasoningEn,
                        "timestampMs" to v.timestampMs
                    )
                },
                "updatedAtMs" to room.updatedAtMs.coerceAtLeast(0L),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>

            db.document(path).set(payload).await()
            isOnline = true
            lastNetworkError = null
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun upsertRoomRider(circuitId: String, rider: ActiveRoomRider): Result<Unit> =
        withContext(Dispatchers.IO) {
            val cid = sanitizeId(circuitId, "circ")
            val rid = sanitizeId(rider.riderId, "r")
            val path = "circuits/$cid/active_riders/$rid"
            try {
                val uid = requireUserId()
                val ref = db.document(path)
                val existing = ref.get().await()
                val ownerUid = existing.getString("ownerUid")?.ifBlank { uid } ?: uid
                if (rid != uid && ownerUid != uid && !isCurrentUserAdmin()) {
                    // Only owner or admin may update another rider's active room doc
                    return@withContext Result.success(Unit)
                }
                val payload = mapOf(
                    "circuitId" to cid,
                    "ownerUid" to ownerUid,
                    "riderId" to rid,
                    "riderName" to rider.riderName.trim().ifBlank { "Piloto" }.take(60),
                    "dorsal" to rider.dorsal.trim().ifBlank { "01" }.take(16),
                    "bikeModel" to rider.bikeModel.take(80),
                    "discipline" to rider.discipline.name,
                    "lapsCount" to rider.lapsCount.coerceAtLeast(0),
                    "bestLapMs" to rider.bestLapMs.coerceAtLeast(0L),
                    "lastLapMs" to rider.lastLapMs.coerceAtLeast(0L),
                    "lastPassTimestampMs" to rider.lastPassTimestampMs.coerceAtLeast(0L),
                    "setupSummary" to rider.setupSummary,
                    "colorConfirmed" to rider.colorConfirmed,
                    "eliminated" to rider.eliminated,
                    "sessionStartMs" to rider.sessionStartMs.coerceAtLeast(0L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                ref.set(payload).await()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, path)
                Result.failure(e)
            }
        }

    suspend fun deleteRoomRider(circuitId: String, riderId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val cid = sanitizeId(circuitId, "circ")
            val rid = sanitizeId(riderId, "r")
            val path = "circuits/$cid/active_riders/$rid"
            try {
                requireUserId()
                db.document(path).delete().await()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.DELETE, path)
                Result.failure(e)
            }
        }

    private fun parseRoomDoc(circuitId: String, doc: DocumentSnapshot): RoomState? {
        if (!doc.exists()) return null
        val d = doc.data ?: return null
        val paMap = d["lastPaAnnouncement"] as? Map<*, *>
        val pa = paMap?.let {
            PaAnnouncement(
                id = (it["id"] as? String).orEmpty(),
                textEs = (it["textEs"] as? String).orEmpty(),
                textEn = (it["textEn"] as? String).orEmpty(),
                timestampMs = asLong(it["timestampMs"], 0L),
                isHotlap = asBool(it["isHotlap"], false)
            )
        }
        val aiMap = d["lastAiVerdict"] as? Map<*, *>
        val ai = aiMap?.let {
            AiStewardVerdict(
                id = (it["id"] as? String).orEmpty(),
                matchedRiderId = (it["matchedRiderId"] as? String).orEmpty(),
                riderName = (it["riderName"] as? String).orEmpty(),
                detectedDorsal = (it["detectedDorsal"] as? String).orEmpty(),
                fairingSummary = (it["fairingSummary"] as? String).orEmpty(),
                confidencePercent = asInt(it["confidencePercent"], 0),
                isRealPass = asBool(it["isRealPass"], true),
                reasoningEs = (it["reasoningEs"] as? String).orEmpty(),
                reasoningEn = (it["reasoningEn"] as? String).orEmpty(),
                timestampMs = asLong(it["timestampMs"], 0L)
            )
        }
        return RoomState(
            circuitId = circuitId,
            gameMode = safeEnum(d["gameMode"], GameMode.NORMAL),
            sessionPhase = safeEnum(d["sessionPhase"], SessionPhase.IDLE),
            flagState = safeEnum(d["flagState"], FlagState.GREEN),
            heatDurationSec = asInt(d["heatDurationSec"], 300),
            phaseStartTimeMs = asLong(d["phaseStartTimeMs"], 0L),
            phaseEndTimeMs = asLong(d["phaseEndTimeMs"], 0L),
            gateDropTimeMs = asLong(d["gateDropTimeMs"], 0L),
            friendlyModeActive = asBool(d["friendlyModeActive"], false),
            lipoWarnMinutes = asInt(d["lipoWarnMinutes"], 12),
            antiVibration = safeEnum(d["antiVibration"], AntiVibrationLevel.MEDIUM),
            weeklyHotlapRider = (d["weeklyHotlapRider"] as? String).orEmpty(),
            weeklyHotlapMs = asLong(d["weeklyHotlapMs"], 0L),
            mxRecordRider = (d["mxRecordRider"] as? String).orEmpty(),
            mxRecordMs = asLong(d["mxRecordMs"], 0L),
            smRecordRider = (d["smRecordRider"] as? String).orEmpty(),
            smRecordMs = asLong(d["smRecordMs"], 0L),
            lastPaAnnouncement = pa,
            lastAiVerdict = ai,
            updatedAtMs = asLong(d["updatedAtMs"], 0L)
        )
    }

    private fun parseActiveRoomRider(doc: DocumentSnapshot): ActiveRoomRider? {
        if (!doc.exists()) return null
        val d = doc.data ?: return null
        return ActiveRoomRider(
            riderId = (d["riderId"] as? String)?.ifBlank { doc.id } ?: doc.id,
            riderName = (d["riderName"] as? String).orEmpty(),
            dorsal = (d["dorsal"] as? String).orEmpty().ifBlank { "01" },
            bikeModel = (d["bikeModel"] as? String).orEmpty(),
            discipline = safeEnum(d["discipline"], SurfaceType.MX),
            lapsCount = asInt(d["lapsCount"], 0),
            bestLapMs = asLong(d["bestLapMs"], 0L),
            lastLapMs = asLong(d["lastLapMs"], 0L),
            lastPassTimestampMs = asLong(d["lastPassTimestampMs"], 0L),
            setupSummary = (d["setupSummary"] as? String).orEmpty(),
            colorConfirmed = asBool(d["colorConfirmed"], false),
            eliminated = asBool(d["eliminated"], false),
            sessionStartMs = asLong(d["sessionStartMs"], 0L)
        )
    }

    // --- Laps ---
    fun observeLaps(circuitId: String): Flow<List<LapRecord>> = flow {
        val uid = requireUserId()
        val cid = sanitizeId(circuitId, "circ")
        val path = "circuits/$cid/laps"
        emitAll(
            db.collection(path)
                .whereEqualTo("circuitId", cid)
                .snapshots()
                .map { qs ->
                    qs.documents.mapNotNull { parseLap(it) }
                        .sortedByDescending { it.timestampMs }
                }
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(fsErr ?: error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun fetchCircuitLaps(circuitId: String): List<LapRecord> = withContext(Dispatchers.IO) {
        val cid = sanitizeId(circuitId, "circ")
        val path = "circuits/$cid/laps"
        try {
            requireUserId()
            val snap = db.collection(path)
                .whereEqualTo("circuitId", cid)
                .get()
                .await()
            isOnline = true
            lastNetworkError = null
            snap.documents.mapNotNull { parseLap(it) }.sortedByDescending { it.timestampMs }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            isOnline = false
            lastNetworkError = e.localizedMessage
            emptyList()
        }
    }

    suspend fun pushLap(lap: LapRecord): Result<Unit> = withContext(Dispatchers.IO) {
        val cid = sanitizeId(lap.circuitId, "circ")
        val lid = sanitizeId(lap.id, "lap")
        val path = "circuits/$cid/laps/$lid"
        try {
            val uid = requireUserId()
            val payload = mapOf(
                "ownerUid" to uid,
                "circuitId" to cid,
                "riderId" to sanitizeId(lap.riderId, "r"),
                "riderName" to lap.riderName.trim().ifBlank { "Piloto" }.take(60),
                "dorsal" to lap.dorsal.take(16),
                "bikeModel" to lap.bikeModel.take(80),
                "discipline" to lap.discipline.name,
                "lapTimeMs" to lap.lapTimeMs.coerceIn(0L, 3600000L),
                "deltaMs" to lap.deltaMs,
                "speedKmh" to lap.speedKmh.coerceAtLeast(0.0),
                "timestampMs" to lap.timestampMs.coerceAtLeast(0L),
                "antennaId" to lap.antennaId.take(64),
                "detectedColors" to lap.detectedColors.take(8),
                "dayKey" to lap.dayKey.take(20),
                "createdAt" to FieldValue.serverTimestamp()
            )
            db.document(path).set(payload).await()
            isOnline = true
            lastNetworkError = null
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun clearCircuitRoomLaps(circuitId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cid = sanitizeId(circuitId, "circ")
        val path = "circuits/$cid/laps"
        try {
            val uid = requireUserId()
            val snap = db.collection(path).whereEqualTo("circuitId", cid).get().await()
            for (doc in snap.documents) {
                val owner = doc.getString("ownerUid").orEmpty()
                if (owner == uid || isCurrentUserAdmin()) {
                    doc.reference.delete().await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    private fun parseLap(doc: DocumentSnapshot): LapRecord? {
        if (!doc.exists()) return null
        val d = doc.data ?: return null
        return LapRecord(
            id = doc.id,
            circuitId = (d["circuitId"] as? String).orEmpty(),
            riderId = (d["riderId"] as? String).orEmpty(),
            riderName = (d["riderName"] as? String).orEmpty(),
            dorsal = (d["dorsal"] as? String).orEmpty().ifBlank { "01" },
            bikeModel = (d["bikeModel"] as? String).orEmpty(),
            discipline = safeEnum(d["discipline"], SurfaceType.MX),
            lapTimeMs = asLong(d["lapTimeMs"], 0L),
            deltaMs = asLong(d["deltaMs"], 0L),
            speedKmh = asDouble(d["speedKmh"], 0.0),
            timestampMs = asLong(d["timestampMs"], 0L),
            antennaId = (d["antennaId"] as? String).orEmpty().ifBlank { "manual" },
            detectedColors = asStringList(d["detectedColors"]),
            dayKey = (d["dayKey"] as? String).orEmpty()
        )
    }

    // --- Antennas ---
    private fun defaultAntennasMap(circuitId: String): LinkedHashMap<String, AntennaConfig> = linkedMapOf(
        AntennaStation.ANTENA_1.id to AntennaConfig(antennaId = AntennaStation.ANTENA_1.id, circuitId = circuitId),
        AntennaStation.ANTENA_2.id to AntennaConfig(antennaId = AntennaStation.ANTENA_2.id, circuitId = circuitId),
        AntennaStation.ANTENA_TEMP.id to AntennaConfig(antennaId = AntennaStation.ANTENA_TEMP.id, circuitId = circuitId)
    )

    fun observeAntennas(circuitId: String): Flow<Map<String, AntennaConfig>> = flow {
        val uid = requireUserId()
        val cid = sanitizeId(circuitId, "circ")
        val path = "circuits/$cid/antennas"
        emitAll(
            db.collection(path)
                .whereEqualTo("circuitId", cid)
                .snapshots()
                .map { qs ->
                    val merged = defaultAntennasMap(cid)
                    qs.documents.mapNotNull { parseAntennaDoc(cid, it) }
                        .forEach { merged[it.antennaId] = it }
                    merged.toMap()
                }
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(fsErr ?: error, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun fetchAntennas(circuitId: String): Map<String, AntennaConfig> = withContext(Dispatchers.IO) {
        val cid = sanitizeId(circuitId, "circ")
        val merged = defaultAntennasMap(cid)
        val path = "circuits/$cid/antennas"
        try {
            requireUserId()
            val snap = db.collection(path).whereEqualTo("circuitId", cid).get().await()
            snap.documents.mapNotNull { parseAntennaDoc(cid, it) }
                .forEach { merged[it.antennaId] = it }
            isOnline = true
            lastNetworkError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            isOnline = false
            lastNetworkError = e.localizedMessage
        }
        merged
    }

    suspend fun fetchAllCircuitAntennas(circuitIds: List<String>): Map<String, Map<String, AntennaConfig>> =
        withContext(Dispatchers.IO) {
            val out = linkedMapOf<String, Map<String, AntennaConfig>>()
            for (cid in circuitIds.take(8)) {
                val ants = fetchAntennas(cid)
                if (ants.values.any { it.lastHeartbeatMs > 0L }) {
                    out[cid] = ants
                }
            }
            out
        }

    suspend fun saveAntennaConfig(config: AntennaConfig): Result<Unit> = withContext(Dispatchers.IO) {
        val cid = sanitizeId(config.circuitId, "circ")
        val aid = sanitizeId(config.antennaId, "antena")
        val path = "circuits/$cid/antennas/$aid"
        try {
            requireUserId()
            val payload = mapOf(
                "antennaId" to aid,
                "circuitId" to cid,
                "status" to config.status.take(60),
                "isCalibrated" to config.isCalibrated,
                "calibrateCommandAtMs" to config.calibrateCommandAtMs.coerceAtLeast(0L),
                "detectionMode" to config.detectionMode.name,
                "roiSize" to config.roiSize.name,
                "roiHeight" to config.roiHeight.name,
                "lineOrientation" to config.lineOrientation.name,
                "linePosition" to config.linePosition.coerceIn(0.1f, 0.9f),
                "colorZoneX" to config.colorZoneX.coerceIn(0.15f, 0.85f),
                "colorZoneY" to config.colorZoneY.coerceIn(0.15f, 0.85f),
                "singleTargetColorName" to config.singleTargetColorName,
                "directionFilter" to config.directionFilter.name,
                "aiStewardEnabled" to config.aiStewardEnabled,
                "antiVibration" to config.antiVibration.name,
                "lastSnapshotBase64" to config.lastSnapshotBase64.take(350000),
                "lastPhotoFinishBase64" to config.lastPhotoFinishBase64.take(350000),
                "lastDetectedColors" to config.lastDetectedColors.take(8),
                "lastDetectedHexes" to config.lastDetectedHexes.take(8),
                "lastDetectedLab" to labListToMaps(config.lastDetectedLab.take(8)),
                "lastDetectedDorsal" to config.lastDetectedDorsal,
                "lastConfidence" to config.lastConfidence,
                "activeFps" to config.activeFps,
                "lastTriggerTimestampMs" to config.lastTriggerTimestampMs.coerceAtLeast(0L),
                "matchedRiderId" to config.matchedRiderId,
                "matchedRiderName" to config.matchedRiderName,
                "lastHeartbeatMs" to config.lastHeartbeatMs.coerceAtLeast(0L),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.document(path).set(payload).await()
            isOnline = true
            lastNetworkError = null
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            lastNetworkError = e.localizedMessage
            Result.failure(e)
        }
    }

    suspend fun patchAntennaTelemetry(
        circuitId: String,
        antennaId: String,
        patchFields: Map<String, Any?>,
        fallbackConfig: AntennaConfig? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cid = sanitizeId(circuitId, "circ")
        val aid = sanitizeId(antennaId, "antena")
        val path = "circuits/$cid/antennas/$aid"
        try {
            requireUserId()
            val cleanPatch = patchFields.toMutableMap().apply {
                put("antennaId", aid)
                put("circuitId", cid)
                if (!containsKey("status")) put("status", fallbackConfig?.status ?: "ONLINE_WAIT_CAL")
                if (!containsKey("isCalibrated")) put("isCalibrated", fallbackConfig?.isCalibrated ?: false)
                if (!containsKey("detectionMode")) put("detectionMode", (fallbackConfig?.detectionMode ?: DetectionMode.ANY_PASS).name)
                if (!containsKey("lastHeartbeatMs")) put("lastHeartbeatMs", fallbackConfig?.lastHeartbeatMs ?: syncedNowMs())
                put("updatedAt", FieldValue.serverTimestamp())
            }.filterValues { it != null } as Map<String, Any>

            db.document(path).set(cleanPatch, SetOptions.merge()).await()
            isOnline = true
            lastNetworkError = null
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            lastNetworkError = e.localizedMessage
            Result.failure(e)
        }
    }

    private fun parseAntennaDoc(circuitId: String, doc: DocumentSnapshot): AntennaConfig? {
        if (!doc.exists()) return null
        val d = doc.data ?: return null
        return AntennaConfig(
            antennaId = (d["antennaId"] as? String)?.ifBlank { doc.id } ?: doc.id,
            circuitId = (d["circuitId"] as? String)?.ifBlank { circuitId } ?: circuitId,
            status = (d["status"] as? String).orEmpty().ifBlank { "WAITING_CALIBRATION" },
            isCalibrated = asBool(d["isCalibrated"], false),
            calibrateCommandAtMs = asLong(d["calibrateCommandAtMs"], 0L),
            detectionMode = safeEnum(d["detectionMode"], DetectionMode.ANY_PASS),
            roiSize = safeEnum(d["roiSize"], RoiSize.M),
            roiHeight = safeEnum(d["roiHeight"], RoiHeight.MEDIUM),
            lineOrientation = safeEnum(d["lineOrientation"], LineOrientation.VERTICAL),
            linePosition = asFloat(d["linePosition"], 0.42f).coerceIn(0.1f, 0.9f),
            colorZoneX = asFloat(d["colorZoneX"], 0.70f).coerceIn(0.15f, 0.85f),
            colorZoneY = asFloat(d["colorZoneY"], 0.50f).coerceIn(0.15f, 0.85f),
            singleTargetColorName = (d["singleTargetColorName"] as? String).orEmpty(),
            directionFilter = safeEnum(d["directionFilter"], PassingDirection.ANY),
            aiStewardEnabled = asBool(d["aiStewardEnabled"], true),
            antiVibration = safeEnum(d["antiVibration"], AntiVibrationLevel.MEDIUM),
            lastSnapshotBase64 = (d["lastSnapshotBase64"] as? String).orEmpty(),
            lastPhotoFinishBase64 = (d["lastPhotoFinishBase64"] as? String).orEmpty(),
            lastDetectedColors = asStringList(d["lastDetectedColors"]),
            lastDetectedHexes = asStringList(d["lastDetectedHexes"]),
            lastDetectedLab = asLabList(d["lastDetectedLab"]),
            lastDetectedDorsal = (d["lastDetectedDorsal"] as? String).orEmpty(),
            lastConfidence = asFloat(d["lastConfidence"], 0f),
            activeFps = asInt(d["activeFps"], 30),
            lastTriggerTimestampMs = asLong(d["lastTriggerTimestampMs"], 0L),
            matchedRiderId = (d["matchedRiderId"] as? String).orEmpty(),
            matchedRiderName = (d["matchedRiderName"] as? String).orEmpty(),
            lastHeartbeatMs = asLong(d["lastHeartbeatMs"], 0L)
        )
    }

    fun parseAntennaConfigFromLanJson(circuitId: String, key: String, obj: JSONObject): AntennaConfig {
        val id = obj.optString("antennaId", key).ifBlank { key }
        val cid = obj.optString("circuitId", circuitId).ifBlank { circuitId }
        val labSamples = mutableListOf<LabSample>()
        val labNode = obj.optJSONArray("lastDetectedLab")
        if (labNode != null) {
            for (i in 0 until labNode.length()) {
                labNode.optJSONObject(i)?.let { s ->
                    labSamples += LabSample(
                        l = s.optDouble("l", 0.0),
                        a = s.optDouble("a", 0.0),
                        b = s.optDouble("b", 0.0),
                        hex = s.optString("hex", "#FFFFFF").ifBlank { "#FFFFFF" },
                        colorName = s.optString("colorName", "Color")
                    )
                }
            }
        }
        val colors = mutableListOf<String>()
        obj.optJSONArray("lastDetectedColors")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(colors::add)
        }
        val hexes = mutableListOf<String>()
        obj.optJSONArray("lastDetectedHexes")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(hexes::add)
        }
        return AntennaConfig(
            antennaId = id,
            circuitId = cid,
            status = obj.optString("status", "WAITING_CALIBRATION"),
            isCalibrated = obj.optBoolean("isCalibrated", false),
            calibrateCommandAtMs = obj.optLong("calibrateCommandAtMs", 0L),
            detectionMode = safeEnum(obj.optString("detectionMode", "ANY_PASS"), DetectionMode.ANY_PASS),
            roiSize = safeEnum(obj.optString("roiSize", "M"), RoiSize.M),
            roiHeight = safeEnum(obj.optString("roiHeight", "MEDIUM"), RoiHeight.MEDIUM),
            lineOrientation = safeEnum(obj.optString("lineOrientation", "VERTICAL"), LineOrientation.VERTICAL),
            linePosition = obj.optDouble("linePosition", 0.42).toFloat().coerceIn(0.1f, 0.9f),
            colorZoneX = obj.optDouble("colorZoneX", 0.70).toFloat().coerceIn(0.15f, 0.85f),
            colorZoneY = obj.optDouble("colorZoneY", 0.50).toFloat().coerceIn(0.15f, 0.85f),
            singleTargetColorName = obj.optString("singleTargetColorName", ""),
            directionFilter = safeEnum(obj.optString("directionFilter", "ANY"), PassingDirection.ANY),
            aiStewardEnabled = obj.optBoolean("aiStewardEnabled", true),
            antiVibration = safeEnum(obj.optString("antiVibration", "MEDIUM"), AntiVibrationLevel.MEDIUM),
            lastSnapshotBase64 = obj.optString("lastSnapshotBase64", ""),
            lastPhotoFinishBase64 = obj.optString("lastPhotoFinishBase64", ""),
            lastDetectedColors = colors,
            lastDetectedHexes = hexes,
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

    // --- Color Profiles & Captured Passes ---
    suspend fun fetchColorProfiles(circuitId: String): List<RiderColorProfile> =
        withContext(Dispatchers.IO) {
            val cid = sanitizeId(circuitId, "circ")
            val path = "circuits/$cid/color_profiles"
            try {
                requireUserId()
                db.collection(path).whereEqualTo("circuitId", cid).get().await()
                    .documents.mapNotNull { doc ->
                        val d = doc.data ?: return@mapNotNull null
                        RiderColorProfile(
                            riderId = (d["riderId"] as? String)?.ifBlank { doc.id } ?: doc.id,
                            riderName = (d["riderName"] as? String).orEmpty(),
                            samples = asLabList(d["samples"]),
                            confirmed = asBool(d["confirmed"], false)
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.LIST, path)
                emptyList()
            }
        }

    suspend fun saveColorProfile(circuitId: String, profile: RiderColorProfile): Result<Unit> =
        withContext(Dispatchers.IO) {
            val cid = sanitizeId(circuitId, "circ")
            val rid = sanitizeId(profile.riderId, "r")
            val path = "circuits/$cid/color_profiles/$rid"
            try {
                requireUserId()
                db.document(path).set(
                    mapOf(
                        "circuitId" to cid,
                        "riderId" to rid,
                        "riderName" to profile.riderName.trim().ifBlank { "Piloto" }.take(60),
                        "samples" to labListToMaps(profile.samples),
                        "confirmed" to profile.confirmed,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, path)
                Result.failure(e)
            }
        }

    suspend fun deleteColorProfile(circuitId: String, riderId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val cid = sanitizeId(circuitId, "circ")
            val rid = sanitizeId(riderId, "r")
            val path = "circuits/$cid/color_profiles/$rid"
            try {
                requireUserId()
                db.document(path).delete().await()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.DELETE, path)
                Result.failure(e)
            }
        }

    suspend fun fetchCapturedPasses(circuitId: String): List<CapturedPassSample> =
        withContext(Dispatchers.IO) {
            val cid = sanitizeId(circuitId, "circ")
            val path = "circuits/$cid/color_captures"
            try {
                requireUserId()
                db.collection(path).whereEqualTo("circuitId", cid).get().await()
                    .documents.mapNotNull { doc ->
                        val d = doc.data ?: return@mapNotNull null
                        CapturedPassSample(
                            id = doc.id,
                            timestampMs = asLong(d["timestampMs"], 0L),
                            antennaId = (d["antennaId"] as? String).orEmpty().ifBlank { "antena_1" },
                            samples = asLabList(d["samples"]),
                            snapshotBase64 = (d["snapshotBase64"] as? String).orEmpty(),
                            detectedDorsal = (d["detectedDorsal"] as? String).orEmpty(),
                            aiSummary = (d["aiSummary"] as? String).orEmpty()
                        )
                    }.sortedByDescending { it.timestampMs }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.LIST, path)
                emptyList()
            }
        }

    suspend fun pushCapturedPass(circuitId: String, capture: CapturedPassSample): Result<Unit> =
        withContext(Dispatchers.IO) {
            val cid = sanitizeId(circuitId, "circ")
            val capId = sanitizeId(capture.id, "cap")
            val path = "circuits/$cid/color_captures/$capId"
            try {
                val uid = requireUserId()
                db.document(path).set(
                    mapOf(
                        "ownerUid" to uid,
                        "circuitId" to cid,
                        "timestampMs" to capture.timestampMs.coerceAtLeast(0L),
                        "antennaId" to capture.antennaId.take(64),
                        "samples" to labListToMaps(capture.samples.take(8)),
                        "snapshotBase64" to capture.snapshotBase64.take(350000),
                        "detectedDorsal" to capture.detectedDorsal,
                        "aiSummary" to capture.aiSummary,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, path)
                Result.failure(e)
            }
        }

    // --- Championships ---
    suspend fun fetchChampionships(circuitId: String): List<Championship> =
        withContext(Dispatchers.IO) {
            val cid = sanitizeId(circuitId, "circ")
            val path = "circuits/$cid/championships"
            try {
                requireUserId()
                db.collection(path).whereEqualTo("circuitId", cid).get().await()
                    .documents.mapNotNull { doc ->
                        val d = doc.data ?: return@mapNotNull null
                        val rawStandings = d["standings"] as? Map<*, *> ?: emptyMap<String, Any>()
                        val standings = linkedMapOf<String, ChampionshipStanding>()
                        rawStandings.forEach { (k, v) ->
                            val ks = k?.toString() ?: return@forEach
                            val m = v as? Map<*, *> ?: return@forEach
                            standings[ks] = ChampionshipStanding(
                                riderId = (m["riderId"] as? String)?.ifBlank { ks } ?: ks,
                                riderName = (m["riderName"] as? String).orEmpty(),
                                dorsal = (m["dorsal"] as? String).orEmpty().ifBlank { "01" },
                                points = asInt(m["points"], 0),
                                heatsRaced = asInt(m["heatsRaced"], 0),
                                wins = asInt(m["wins"], 0),
                                bestLapMs = asLong(m["bestLapMs"], 0L)
                            )
                        }
                        Championship(
                            id = doc.id,
                            circuitId = cid,
                            name = (d["name"] as? String).orEmpty(),
                            discipline = safeEnum(d["discipline"], SurfaceType.MX),
                            totalHeats = asInt(d["totalHeats"], 3),
                            completedHeats = asInt(d["completedHeats"], 0),
                            standings = standings,
                            createdAtMs = asLong(d["createdAtMs"], 0L)
                        )
                    }.sortedByDescending { it.createdAtMs }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.LIST, path)
                emptyList()
            }
        }

    suspend fun saveChampionship(champ: Championship): Result<Unit> = withContext(Dispatchers.IO) {
        val cid = sanitizeId(champ.circuitId, "circ")
        val chId = sanitizeId(champ.id, "champ")
        val path = "circuits/$cid/championships/$chId"
        try {
            val uid = requireUserId()
            val ref = db.document(path)
            val existing = ref.get().await()
            val standingsMap = champ.standings.mapValues { (_, s) ->
                mapOf(
                    "riderId" to s.riderId,
                    "riderName" to s.riderName,
                    "dorsal" to s.dorsal,
                    "points" to s.points,
                    "heatsRaced" to s.heatsRaced,
                    "wins" to s.wins,
                    "bestLapMs" to s.bestLapMs
                )
            }
            if (existing.exists()) {
                ref.update(
                    mapOf(
                        "name" to champ.name.trim().ifBlank { "Campeonato MX" }.take(100),
                        "discipline" to champ.discipline.name,
                        "totalHeats" to champ.totalHeats.coerceIn(1, 50),
                        "completedHeats" to champ.completedHeats.coerceIn(0, champ.totalHeats.coerceIn(1, 50)),
                        "standings" to standingsMap,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                ref.set(
                    mapOf(
                        "ownerUid" to uid,
                        "circuitId" to cid,
                        "name" to champ.name.trim().ifBlank { "Campeonato MX" }.take(100),
                        "discipline" to champ.discipline.name,
                        "totalHeats" to champ.totalHeats.coerceIn(1, 50),
                        "completedHeats" to champ.completedHeats.coerceIn(0, champ.totalHeats.coerceIn(1, 50)),
                        "standings" to standingsMap,
                        "createdAtMs" to champ.createdAtMs.coerceAtLeast(0L),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    // --- Fan Interactions ---
    fun observeFanInteraction(circuitId: String): Flow<FanInteractionState> = flow {
        val uid = requireUserId()
        val cid = sanitizeId(circuitId, "circ")
        val path = "circuits/$cid/live/fan"
        emitAll(
            db.document(path).snapshots()
                .map { snap -> parseFanDoc(snap) }
                .catch { error ->
                    val fsErr = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == uid) {
                        handleFirestoreError(fsErr ?: error, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    suspend fun fetchFanInteraction(circuitId: String): FanInteractionState =
        withContext(Dispatchers.IO) {
            val cid = sanitizeId(circuitId, "circ")
            val path = "circuits/$cid/live/fan"
            try {
                requireUserId()
                parseFanDoc(db.document(path).get().await())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.GET, path)
                FanInteractionState()
            }
        }

    suspend fun saveFanInteraction(circuitId: String, state: FanInteractionState): Result<Unit> =
        withContext(Dispatchers.IO) {
            val cid = sanitizeId(circuitId, "circ")
            val path = "circuits/$cid/live/fan"
            try {
                requireUserId()
                val cheersList = state.cheerMessages.take(25).map { c ->
                    mapOf(
                        "id" to c.id,
                        "fanName" to c.fanName,
                        "targetRider" to c.targetRider,
                        "text" to c.text,
                        "timestampMs" to c.timestampMs
                    )
                }
                db.document(path).set(
                    mapOf(
                        "circuitId" to cid,
                        "bets" to state.bets,
                        "mvpVotes" to state.mvpVotes,
                        "ovationCount" to state.ovationCount.coerceAtLeast(0),
                        "cheerMessages" to cheersList,
                        "paClipBase64" to state.paClipBase64.take(400000),
                        "paClipTimestampMs" to state.paClipTimestampMs.coerceAtLeast(0L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, path)
                Result.failure(e)
            }
        }

    private fun parseFanDoc(doc: DocumentSnapshot): FanInteractionState {
        if (!doc.exists()) return FanInteractionState()
        val d = doc.data ?: return FanInteractionState()
        val cheers = (d["cheerMessages"] as? List<*>)?.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            CheerMessage(
                id = (m["id"] as? String).orEmpty(),
                fanName = (m["fanName"] as? String).orEmpty(),
                targetRider = (m["targetRider"] as? String).orEmpty(),
                text = (m["text"] as? String).orEmpty(),
                timestampMs = asLong(m["timestampMs"], 0L)
            )
        } ?: emptyList()
        return FanInteractionState(
            bets = asStringMap(d["bets"]),
            mvpVotes = asIntMap(d["mvpVotes"]),
            ovationCount = asInt(d["ovationCount"], 0),
            cheerMessages = cheers,
            paClipBase64 = (d["paClipBase64"] as? String).orEmpty(),
            paClipTimestampMs = asLong(d["paClipTimestampMs"], 0L)
        )
    }

    // --- Reports Inbox ---
    suspend fun fetchReports(): List<AppReport> = withContext(Dispatchers.IO) {
        val path = "reports"
        try {
            val uid = requireUserId()
            val query = if (isCurrentUserAdmin()) {
                db.collection(path)
            } else {
                db.collection(path).whereEqualTo("ownerUid", uid)
            }
            query.get().await().documents.mapNotNull { doc ->
                val d = doc.data ?: return@mapNotNull null
                AppReport(
                    id = doc.id,
                    senderName = (d["senderName"] as? String).orEmpty(),
                    type = (d["type"] as? String).orEmpty().ifBlank { "BUG" },
                    message = (d["message"] as? String).orEmpty(),
                    circuitRequested = (d["circuitRequested"] as? String).orEmpty(),
                    timestampMs = asLong(d["timestampMs"], 0L)
                )
            }.sortedByDescending { it.timestampMs }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            emptyList()
        }
    }

    suspend fun submitReport(report: AppReport): Result<Unit> = withContext(Dispatchers.IO) {
        val rid = sanitizeId(report.id, "rep")
        val path = "reports/$rid"
        try {
            val uid = requireUserId()
            val payload = mutableMapOf<String, Any?>(
                "ownerUid" to uid,
                "senderName" to report.senderName.trim().ifBlank { "Piloto" }.take(60),
                "type" to report.type.trim().ifBlank { "BUG" }.take(40),
                "message" to report.message.trim().take(1200),
                "circuitRequested" to report.circuitRequested.trim().take(120).ifBlank { null },
                "timestampMs" to report.timestampMs.coerceAtLeast(0L),
                "createdAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>
            db.document(path).set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }
}
