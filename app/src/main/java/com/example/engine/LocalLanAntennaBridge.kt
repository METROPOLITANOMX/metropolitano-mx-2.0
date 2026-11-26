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
 * Lightweight zero-permission Local Wi-Fi / Hotspot LAN Relay so Director & Antenna
 * can still link directly on the same Wi-Fi/Hotspot even if cloud RTDB returns HTTP 401.
 * Over Internet (4G/5G), Firebase RTDB handles remote sync without requiring same Wi-Fi.
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
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var serverRunning = false
    private var httpServer: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null

    fun getLocalIpv4Address(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        if (!ip.isNullOrBlank()) return ip
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun startDirectorServer(
        onAntennaTelemetry: (circuitId: String, antennaId: String, patch: JSONObject) -> Unit,
        onCaptureSample: (circuitId: String, payload: JSONObject) -> Unit
    ) {
        if (serverRunning) return
        serverRunning = true

        // HTTP Receiver Thread
        Thread {
            try {
                httpServer = ServerSocket(HTTP_PORT)
                while (serverRunning) {
                    val sock = httpServer?.accept() ?: break
                    handleClientSocket(sock, onAntennaTelemetry, onCaptureSample)
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
        onCaptureSample: (String, JSONObject) -> Unit
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
            val bodyChars = CharArray(contentLen.coerceIn(0, 512_000))
            var readTotal = 0
            while (readTotal < bodyChars.size) {
                val r = reader.read(bodyChars, readTotal, bodyChars.size - readTotal)
                if (r <= 0) break
                readTotal += r
            }
            val body = String(bodyChars, 0, readTotal)
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
            }
            val out = sock.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
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

    suspend fun discoverDirectorIp(): String? = withContext(Dispatchers.IO) {
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket().apply {
                broadcast = true
                soTimeout = 900
            }
            val probe = DISCOVERY_QUERY.toByteArray()
            val pkt = DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)
            sock.send(pkt)
            val buf = ByteArray(256)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            val txt = String(resp.data, 0, resp.length).trim()
            if (txt.startsWith(DISCOVERY_REPLY)) {
                txt.removePrefix(DISCOVERY_REPLY).trim().ifBlank { null }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    suspend fun postToDirectorLan(directorIp: String, payload: JSONObject): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("http://$directorIp:$HTTP_PORT/sync")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}
