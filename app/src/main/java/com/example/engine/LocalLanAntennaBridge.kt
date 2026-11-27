package com.example.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Zero-permission Local Wi-Fi / Hotspot LAN Relay so Director & Antenna
 * sync camera frames, lap detections, and remote antenna settings directly on the same
 * Wi-Fi or Mobile Hotspot even if cloud Firebase RTDB returns HTTP 401.
 */
class LocalLanAntennaBridge {

    companion object {
        const val HTTP_PORT = 18765
        const val DISCOVERY_PORT = 18766
        private const val DISCOVERY_QUERY = "MXPRO_FIND_DIRECTOR"
        private const val DISCOVERY_REPLY = "MXPRO_DIRECTOR_HERE:"
        private const val TAG = "LocalLanBridge"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .writeTimeout(2000, TimeUnit.MILLISECONDS)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(350, TimeUnit.MILLISECONDS)
        .readTimeout(450, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var serverRunning = false
    private var httpServer: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null

    fun getLocalIpv4Address(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            val preferred = mutableListOf<String>()
            val fallback = mutableListOf<String>()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") || name.startsWith("ppp")) {
                    continue
                }
                val isLanIntf = name.startsWith("wlan") || name.startsWith("ap") ||
                    name.startsWith("swlan") || name.startsWith("rndis") || name.startsWith("eth")
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (isLanIntf) preferred.add(ip) else fallback.add(ip)
                    }
                }
            }
            preferred.firstOrNull() ?: fallback.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = linkedSetOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun")) continue
                for (ia in intf.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast is Inet4Address) {
                        list.add(bcast)
                    }
                }
            }
        } catch (_: Exception) {
        }
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {
        }
        return list.toList()
    }

    fun startDirectorServer(
        onAntennaTelemetry: (circuitId: String, antennaId: String, patch: JSONObject) -> Unit,
        onCaptureSample: (circuitId: String, payload: JSONObject) -> Unit,
        onRequestAntennaConfigJson: (circuitId: String, antennaId: String) -> JSONObject? = { _, _ -> null }
    ) {
        if (serverRunning) return
        serverRunning = true

        // HTTP Receiver Thread
        Thread {
            try {
                httpServer = ServerSocket(HTTP_PORT)
                while (serverRunning) {
                    val sock = httpServer?.accept() ?: break
                    Thread {
                        handleClientSocket(sock, onAntennaTelemetry, onCaptureSample, onRequestAntennaConfigJson)
                    }.apply { isDaemon = true; start() }
                }
            } catch (e: Exception) {
                if (serverRunning) Log.w(TAG, "LAN HTTP server stopped: ${e.message}")
            }
        }.apply { isDaemon = true; start() }

        // UDP Discovery Responder Thread
        Thread {
            try {
                udpSocket = DatagramSocket(DISCOVERY_PORT).apply { broadcast = true }
                val buf = ByteArray(256)
                while (serverRunning) {
                    val pkt = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(pkt) ?: break
                    val msg = String(pkt.data, 0, pkt.length).trim()
                    if (msg == DISCOVERY_QUERY) {
                        val localIp = getLocalIpv4Address() ?: continue
                        val replyBytes = "$DISCOVERY_REPLY$localIp".toByteArray()
                        val reply = DatagramPacket(replyBytes, replyBytes.size, pkt.address, pkt.port)
                        udpSocket?.send(reply)
                    }
                }
            } catch (e: Exception) {
                if (serverRunning) Log.w(TAG, "LAN UDP discovery stopped: ${e.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handleClientSocket(
        sock: Socket,
        onAntennaTelemetry: (String, String, JSONObject) -> Unit,
        onCaptureSample: (String, JSONObject) -> Unit,
        onRequestAntennaConfigJson: (String, String) -> JSONObject?
    ) {
        try {
            sock.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val reqLine = reader.readLine() ?: return
            var contentLen = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLen = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            if (reqLine.startsWith("GET /ping")) {
                val okBytes = "mxpro".toByteArray()
                val out = sock.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${okBytes.size}\r\n\r\n".toByteArray())
                out.write(okBytes)
                out.flush()
                return
            }

            val bodyChars = CharArray(contentLen.coerceIn(0, 512_000))
            var readTotal = 0
            while (readTotal < bodyChars.size) {
                val r = reader.read(bodyChars, readTotal, bodyChars.size - readTotal)
                if (r <= 0) break
                readTotal += r
            }
            val body = String(bodyChars, 0, readTotal)
            var replyJson: JSONObject? = null
            if (body.isNotBlank()) {
                val json = JSONObject(body)
                val kind = json.optString("kind", "telemetry")
                val cid = json.optString("circuitId", "metropolitano_madrid")
                val aid = json.optString("antennaId", "antena_1")
                if (kind == "capture") {
                    onCaptureSample(cid, json)
                } else {
                    onAntennaTelemetry(cid, aid, json)
                }
                replyJson = onRequestAntennaConfigJson(cid, aid)
            }
            val respBytes = (replyJson?.toString() ?: """{"ok":true}""").toByteArray(Charsets.UTF_8)
            val out = sock.getOutputStream()
            out.write(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${respBytes.size}\r\n\r\n"
                    .toByteArray(Charsets.UTF_8)
            )
            out.write(respBytes)
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    fun stopDirectorServer() {
        serverRunning = false
        try { httpServer?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        httpServer = null
        udpSocket = null
    }

    suspend fun pingDirectorLan(ip: String): Boolean = withContext(Dispatchers.IO) {
        val cleanIp = ip.trim().substringBefore(":")
        if (cleanIp.isBlank()) return@withContext false
        try {
            val req = Request.Builder().url("http://$cleanIp:$HTTP_PORT/ping").get().build()
            probeClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun discoverDirectorIp(): String? = withContext(Dispatchers.IO) {
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket().apply {
                broadcast = true
                soTimeout = 750
            }
            val probe = DISCOVERY_QUERY.toByteArray()
            for (bcast in getBroadcastAddresses()) {
                try {
                    sock.send(DatagramPacket(probe, probe.size, bcast, DISCOVERY_PORT))
                } catch (_: Exception) {
                }
            }
            val buf = ByteArray(256)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            val txt = String(resp.data, 0, resp.length).trim()
            if (txt.startsWith(DISCOVERY_REPLY)) {
                val found = txt.removePrefix(DISCOVERY_REPLY).trim().ifBlank { null }
                if (found != null) return@withContext found
            }
        } catch (_: Exception) {
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }

        // Hotspot/AP-isolation fallback: probe own gateway (.1) & common Android/iOS hotspot host IPs
        val candidates = linkedSetOf<String>()
        val ownIp = getLocalIpv4Address()
        if (!ownIp.isNullOrBlank() && ownIp.count { it == '.' } == 3) {
            val prefix = ownIp.substringBeforeLast('.')
            candidates.add("$prefix.1")
            candidates.add("$prefix.2")
        }
        candidates.addAll(
            listOf(
                "192.168.43.1",
                "192.168.49.1",
                "192.168.137.1",
                "192.168.181.1",
                "172.20.10.1",
                "192.168.1.1",
                "192.168.0.1"
            )
        )
        for (candidate in candidates) {
            if (candidate == ownIp) continue
            if (pingDirectorLan(candidate)) {
                return@withContext candidate
            }
        }
        null
    }

    suspend fun postToDirectorLan(directorIp: String, payload: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val cleanIp = directorIp.trim().substringBefore(":")
        if (cleanIp.isBlank()) return@withContext null
        try {
            val req = Request.Builder()
                .url("http://$cleanIp:$HTTP_PORT/sync")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val raw = resp.body?.string()
                if (!raw.isNullOrBlank() && raw.trim().startsWith("{")) {
                    JSONObject(raw)
                } else {
                    JSONObject().put("ok", true)
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
