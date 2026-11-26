package com.pragatijal.smsgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class SmsGatewayService : Service() {

    companion object {
        const val CHANNEL_ID = "pragati_sms_gateway_channel"
        const val PREFS_NAME = "pj_sms_gw_prefs"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_PAIRING_KEY = "pairing_key"
        const val KEY_RUNNING = "is_running"
        const val LOCAL_BRIDGE_PORT = 8765
        const val ACTION_LOG = "com.pragatijal.smsgateway.LOG"
        const val EXTRA_LOG = "log_msg"
    }

    private var scheduler: ScheduledExecutorService? = null
    private var localServer: ServerSocket? = null
    private var localThread: Thread? = null
    @Volatile private var running = false
    private val smsLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        val notification = buildNotification("Pragati Jal 100% Auto SIM (127.0.0.1:$LOCAL_BRIDGE_PORT) चल्दैछ...")
        startForeground(101, notification)

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, true).apply()

        startLocalBridgeServer()

        if (scheduler == null || scheduler!!.isShutdown) {
            scheduler = Executors.newSingleThreadScheduledExecutor()
            scheduler?.scheduleWithFixedDelay({
                pollAndSendSms()
            }, 0, 4, TimeUnit.SECONDS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { localServer?.close() } catch (_: Exception) {}
        localServer = null
        localThread?.interrupt()
        scheduler?.shutdownNow()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, false).apply()
        super.onDestroy()
    }

    private fun startLocalBridgeServer() {
        if (localThread != null && localThread!!.isAlive) return
        localThread = Thread {
            try {
                val srv = ServerSocket(LOCAL_BRIDGE_PORT, 32, InetAddress.getByName("127.0.0.1"))
                localServer = srv
                emitLog("⚡ Local Auto-SIM तयार छ: http://127.0.0.1:$LOCAL_BRIDGE_PORT")
                while (running && !srv.isClosed) {
                    val client = try {
                        srv.accept()
                    } catch (_: Exception) {
                        break
                    }
                    Thread {
                        handleLocalHttpClient(client)
                    }.start()
                }
            } catch (ex: Exception) {
                emitLog("⚠️ Local पोर्ट $LOCAL_BRIDGE_PORT त्रुटि: ${ex.message}")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private data class HttpReq(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun readHttpRequest(input: InputStream): HttpReq? {
        val headerBuf = ByteArrayOutputStream()
        var prev3 = 0
        var prev2 = 0
        var prev1 = 0
        var count = 0
        while (count < 16384) {
            val b = input.read()
            if (b == -1) break
            headerBuf.write(b)
            count++
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                break
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }
        val rawHeader = headerBuf.toString(Charsets.UTF_8.name())
        if (rawHeader.isBlank()) return null

        val lines = rawHeader.split("\r\n")
        val firstLine = lines.firstOrNull()?.trim().orEmpty()
        val parts = firstLine.split(" ")
        if (parts.size < 2) return null

        val method = parts[0].uppercase()
        val path = parts[1].substringBefore("?")
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                headers[name] = value
            }
        }

        val contentLength = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 65536)
        val bodyBytes = ByteArray(contentLength)
        var readTotal = 0
        while (readTotal < contentLength) {
            val r = input.read(bodyBytes, readTotal, contentLength - readTotal)
            if (r == -1) break
            readTotal += r
        }
        val body = String(bodyBytes, 0, readTotal, Charsets.UTF_8)
        return HttpReq(method, path, headers, body)
    }

    private fun handleLocalHttpClient(socket: Socket) {
        try {
            socket.soTimeout = 10000
            val req = readHttpRequest(socket.getInputStream())
            if (req == null) {
                socket.close()
                return
            }

            if (req.method == "OPTIONS") {
                sendHttpResponse(socket, 204, "No Content", "")
                return
            }

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedKey = (prefs.getString(KEY_PAIRING_KEY, "") ?: "").trim()
            val gotKey = (req.headers["x-gateway-key"] ?: "").trim()
            val authOk = savedKey.length >= 12 &&
                MessageDigest.isEqual(savedKey.toByteArray(Charsets.UTF_8), gotKey.toByteArray(Charsets.UTF_8))

            if (!authOk) {
                sendJsonResponse(socket, 401, JSONObject().put("ok", false).put("error", "Invalid pairing key"))
                return
            }

            if (req.method == "GET" && req.path == "/health") {
                sendJsonResponse(
                    socket,
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("mode", "android-apk-local")
                        .put("port", LOCAL_BRIDGE_PORT)
                )
                return
            }

            if (req.method == "POST" && req.path == "/send") {
                val json = try {
                    JSONObject(req.body.ifBlank { "{}" })
                } catch (_: Exception) {
                    sendJsonResponse(socket, 400, JSONObject().put("ok", false).put("error", "Invalid JSON"))
                    return
                }
                val toRaw = json.optString("to", json.optString("local10", "")).trim()
                val msg = json.optString("message", "").trim()
                val cust = json.optString("customerName", toRaw)
                val target = normalizePhone(toRaw)
                if (target.isEmpty() || msg.isEmpty()) {
                    sendJsonResponse(socket, 400, JSONObject().put("ok", false).put("error", "Missing phone/message"))
                    return
                }

                try {
                    sendMultipartSms(target, msg)
                    emitLog("⚡ अटो SIM पठाइयो: $cust ($target)")
                    sendJsonResponse(socket, 200, JSONObject().put("ok", true))
                } catch (ex: Exception) {
                    emitLog("❌ असफल: $cust — ${ex.message}")
                    sendJsonResponse(socket, 500, JSONObject().put("ok", false).put("error", ex.message ?: "SIM Send Failed"))
                }
                return
            }

            sendJsonResponse(socket, 404, JSONObject().put("ok", false).put("error", "Not found"))
        } catch (ex: Exception) {
            Log.w("PjSmsGateway", "Local bridge err: ${ex.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        val local = when {
            digits.startsWith("00977") && digits.length >= 15 -> digits.substring(5)
            digits.startsWith("977") && digits.length >= 13 -> digits.substring(3)
            digits.startsWith("0") && digits.length == 11 -> digits.substring(1)
            else -> digits
        }
        return if (local.length == 10 && local.startsWith("9")) "+977$local" else raw.trim()
    }

    private fun sendJsonResponse(socket: Socket, code: Int, json: JSONObject) {
        val statusText = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        sendHttpResponse(socket, code, statusText, json.toString())
    }

    private fun sendHttpResponse(socket: Socket, code: Int, reason: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, x-gateway-key, Access-Control-Request-Private-Network\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            append("Access-Control-Max-Age: 86400\r\n")
            if (code != 204) {
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(headers.toByteArray(Charsets.UTF_8))
        if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
        out.flush()
    }

    private fun pollAndSendSms() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val baseUrl = (prefs.getString(KEY_SERVER_URL, "") ?: "").trim().trimEnd('/')
        val key = (prefs.getString(KEY_PAIRING_KEY, "") ?: "").trim()
        if (baseUrl.isEmpty() || key.length < 12) return

        try {
            val devName = URLEncoder.encode(Build.MODEL ?: "Android", "UTF-8")
            val pullUrl = URL("$baseUrl/api/sms-gateway/pull?limit=5&device=$devName")
            val conn = (pullUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("x-gateway-key", key)
            }
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            if (code !in 200..299) return

            val json = JSONObject(body)
            val jobs = json.optJSONArray("jobs") ?: return
            for (i in 0 until jobs.length()) {
                val job = jobs.getJSONObject(i)
                val id = job.getString("id")
                val to = job.getString("e164")
                val message = job.getString("message")
                val cust = job.optString("customerName", to)
                try {
                    sendMultipartSms(to, message)
                    ackJob(baseUrl, key, id, true, null)
                    emitLog("✅ पठाइयो: $cust ($to)")
                } catch (ex: Exception) {
                    ackJob(baseUrl, key, id, false, ex.message ?: "SIM Send Error")
                    emitLog("❌ असफल: $cust — ${ex.message}")
                }
            }
        } catch (ex: Exception) {
            Log.w("PjSmsGateway", "Poll error: ${ex.message}")
        }
    }

    private fun sendMultipartSms(phoneE164: String, message: String) {
        synchronized(smsLock) {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneE164, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneE164, null, message, null, null)
            }
        }
    }

    private fun ackJob(baseUrl: String, key: String, id: String, ok: Boolean, error: String?) {
        val url = URL("$baseUrl/api/sms-gateway/ack")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-gateway-key", key)
        }
        val payload = JSONObject().apply {
            put("id", id)
            put("ok", ok)
            if (!error.isNullOrBlank()) put("error", error)
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        conn.responseCode
        conn.disconnect()
    }

    private fun emitLog(msg: String) {
        sendBroadcast(Intent(ACTION_LOG).apply { putExtra(EXTRA_LOG, msg) })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "Pragati Jal SIM SMS Gateway",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("प्रगति जल Auto SIM Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setOngoing(true)
            .build()
    }
}
