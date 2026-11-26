package com.pragatijal.smsgateway

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SmsGatewayService : Service() {

    companion object {
        const val CHANNEL_ID = "pragati_sms_gateway_channel"
        const val PREFS_NAME = "pj_sms_gw_prefs"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_PAIRING_KEY = "pairing_key"
        const val KEY_SIM_CHOICE = "sim_choice" // 0 = Auto, 1 = SIM 1, 2 = SIM 2
        const val KEY_RUNNING = "is_running"
        const val KEY_LOGS = "persistent_logs"
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
        emitLog("⏹ SIM Gateway बन्द भयो।")
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
            socket.soTimeout = 15000
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
                emitLog("🔒 Pairing Key मिलेन! (वेबको Key र एपको Key एउटै राख्नुहोस्)")
                sendJsonResponse(socket, 401, JSONObject().put("ok", false).put("error", "Pairing Key मिलेन! वेब र एपमा एउटै Key राख्नुहोस्।"))
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
                    emitLog("✅ SIM बाट पठाइयो: $cust ($target)")
                    sendJsonResponse(socket, 200, JSONObject().put("ok", true))
                } catch (ex: Exception) {
                    emitLog("❌ असफल ($cust): ${ex.message}")
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
                    emitLog("✅ Server Relay बाट पठाइयो: $cust ($to)")
                } catch (ex: Exception) {
                    ackJob(baseUrl, key, id, false, ex.message ?: "SIM Send Error")
                    emitLog("❌ असफल: $cust — ${ex.message}")
                }
            }
        } catch (ex: Exception) {
            Log.w("PjSmsGateway", "Poll error: ${ex.message}")
        }
    }

    private fun resolveSmsManager(): SmsManager {
        val simChoice = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_SIM_CHOICE, 0)
        try {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val subMgr = getSystemService(SubscriptionManager::class.java)
                val active = subMgr?.activeSubscriptionInfoList
                val pickedSubId: Int = when {
                    simChoice == 1 && active != null ->
                        active.firstOrNull { it.simSlotIndex == 0 }?.subscriptionId
                            ?: active.firstOrNull()?.subscriptionId
                            ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    simChoice == 2 && active != null ->
                        active.firstOrNull { it.simSlotIndex == 1 }?.subscriptionId
                            ?: active.lastOrNull()?.subscriptionId
                            ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    else -> {
                        val defId = SubscriptionManager.getDefaultSmsSubscriptionId()
                        if (defId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) defId
                        else active?.firstOrNull()?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    }
                }
                if (pickedSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        getSystemService(SmsManager::class.java).createForSubscriptionId(pickedSubId)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getSmsManagerForSubscriptionId(pickedSubId)
                    }
                }
            }
        } catch (_: Exception) {}

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private fun sendMultipartSms(phoneE164: String, message: String) {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("फोनमा SEND_SMS अनुमति (Allow) दिइएको छैन!")
        }
        synchronized(smsLock) {
            val smsManager = resolveSmsManager()
            val parts = smsManager.divideMessage(message)
            val partCount = parts.size.coerceAtLeast(1)
            val sentAction = "com.pragatijal.smsgateway.SMS_SENT_${UUID.randomUUID()}"
            val latch = CountDownLatch(partCount)
            val failedCode = AtomicInteger(0)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (resultCode != Activity.RESULT_OK) {
                        failedCode.compareAndSet(0, resultCode)
                    }
                    latch.countDown()
                }
            }

            val filter = IntentFilter(sentAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }

            try {
                val sentIntents = ArrayList<PendingIntent>(partCount)
                for (i in 0 until partCount) {
                    val pi = PendingIntent.getBroadcast(
                        this,
                        (System.currentTimeMillis() and 0xFFFFFF).toInt() + i,
                        Intent(sentAction).setPackage(packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    sentIntents.add(pi)
                }

                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(phoneE164, null, parts, sentIntents, null)
                } else {
                    smsManager.sendTextMessage(phoneE164, null, message, sentIntents[0], null)
                }

                val finished = latch.await(12, TimeUnit.SECONDS)
                val errCode = failedCode.get()
                if (errCode != 0) {
                    val reason = when (errCode) {
                        SmsManager.RESULT_ERROR_NO_SERVICE -> "SIM नेटवर्क/टावर छैन (No Service)"
                        SmsManager.RESULT_ERROR_RADIO_OFF -> "Airplane मोड अन वा SIM बन्द छ"
                        SmsManager.RESULT_ERROR_NULL_PDU -> "मेसेज फर्म्याट त्रुटि (NULL PDU)"
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "SIM बाट पठाउन अस्वीकार (ब्यालेन्स/Dual-SIM वा MIUI Background SMS अनुमति जाँच गर्नुहोस्)"
                        else -> "SIM त्रुटि कोड: $errCode"
                    }
                    throw IllegalStateException(reason)
                }
                if (!finished) {
                    Log.w("PjSmsGateway", "SMS sent callback timed out; carrier queue pending")
                }
            } finally {
                try { unregisterReceiver(receiver) } catch (_: Exception) {}
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
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$ts] $msg"
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prev = prefs.getString(KEY_LOGS, "") ?: ""
        val combined = ("$line\n$prev").lines().take(40).joinToString("\n")
        prefs.edit().putString(KEY_LOGS, combined).apply()
        sendBroadcast(Intent(ACTION_LOG).apply { putExtra(EXTRA_LOG, line) })
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
