package com.pragatijal.smsgateway

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var testPhoneInput: EditText
    private lateinit var simGroup: RadioGroup
    private lateinit var statusText: TextView
    private lateinit var logView: TextView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshLogsFromPrefs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(SmsGatewayService.PREFS_NAME, Context.MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "प्रगति जल — १००% अटो SIM SMS एप"
            textSize = 18f
        }
        statusText = TextView(this).apply {
            text = if (prefs.getBoolean(SmsGatewayService.KEY_RUNNING, false)) {
                "अवस्था: 🟢 चालु छ (127.0.0.1:${SmsGatewayService.LOCAL_BRIDGE_PORT})"
            } else {
                "अवस्था: 🔴 बन्द (पहिले Start थिच्नुहोस्)"
            }
            setPadding(0, 8, 0, 12)
        }

        keyInput = EditText(this).apply {
            hint = "Pairing Key (सफ्टवेयरको १२+ अक्षर पेस्ट गर्नुहोस्)"
            setText(prefs.getString(SmsGatewayService.KEY_PAIRING_KEY, ""))
        }
        urlInput = EditText(this).apply {
            hint = "Server URL (यही फोनबाट चलाउँदा खाली छोड्नुहोस्)"
            setText(prefs.getString(SmsGatewayService.KEY_SERVER_URL, ""))
        }

        val simLabel = TextView(this).apply {
            text = "कुन SIM बाट SMS पठाउने?"
            setPadding(0, 8, 0, 4)
        }
        simGroup = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        val savedSim = prefs.getInt(SmsGatewayService.KEY_SIM_CHOICE, 0)
        listOf(0 to "Auto SIM", 1 to "SIM 1", 2 to "SIM 2").forEach { (idVal, label) ->
            val rb = RadioButton(this).apply {
                id = 1000 + idVal
                text = label
                isChecked = savedSim == idVal
            }
            simGroup.addView(rb)
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val startBtn = Button(this).apply {
            text = "▶ Start Gateway"
            setOnClickListener { startGateway() }
        }
        val stopBtn = Button(this).apply {
            text = "⏹ Stop"
            setOnClickListener {
                stopService(Intent(this@MainActivity, SmsGatewayService::class.java))
                statusText.text = "अवस्था: 🔴 बन्द"
            }
        }
        btnRow.addView(startBtn)
        btnRow.addView(stopBtn)

        testPhoneInput = EditText(this).apply {
            hint = "सिधै टेस्ट गर्ने मोबाइल नं. (जस्तै 98XXXXXXXX)"
        }
        val testBtn = Button(this).apply {
            text = "📤 सिधै फोन SIM टेस्ट SMS पठाउनुहोस्"
            setOnClickListener { sendDirectTestSms() }
        }

        logView = TextView(this).apply {
            text = "लग:\n"
            textSize = 12f
            setPadding(0, 12, 0, 0)
        }
        val scroll = ScrollView(this).apply { addView(logView) }

        root.addView(title)
        root.addView(statusText)
        root.addView(keyInput)
        root.addView(urlInput)
        root.addView(simLabel)
        root.addView(simGroup)
        root.addView(btnRow)
        root.addView(testPhoneInput)
        root.addView(testBtn)
        root.addView(scroll)
        setContentView(root)

        requestNeededPermissions()
        refreshLogsFromPrefs()
    }

    override fun onStart() {
        super.onStart()
        refreshLogsFromPrefs()
        val filter = IntentFilter(SmsGatewayService.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        try { unregisterReceiver(logReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    private fun refreshLogsFromPrefs() {
        val prefs = getSharedPreferences(SmsGatewayService.PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(SmsGatewayService.KEY_LOGS, "") ?: ""
        logView.text = if (saved.isBlank()) {
            "लग (यही फोनबाट Save थिच्नासाथ यहाँ विवरण आउँछ):"
        } else {
            "लग:\n$saved"
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 200)
    }

    private fun getSelectedSimChoice(): Int {
        val checkedId = simGroup.checkedRadioButtonId
        return if (checkedId >= 1000) (checkedId - 1000) else 0
    }

    private fun startGateway(): Boolean {
        val url = urlInput.text.toString().trim().trimEnd('/')
        val key = keyInput.text.toString().trim()
        if (key.length < 12) {
            Toast.makeText(this, "कृपया सफ्टवेयरको १२+ अक्षरको Pairing Key राख्नुहोस्।", Toast.LENGTH_LONG).show()
            return false
        }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions()
            Toast.makeText(this, "पहिले SMS पठाउने अनुमति (Allow) दिनुहोस्।", Toast.LENGTH_LONG).show()
            return false
        }
        getSharedPreferences(SmsGatewayService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(SmsGatewayService.KEY_SERVER_URL, url)
            .putString(SmsGatewayService.KEY_PAIRING_KEY, key)
            .putInt(SmsGatewayService.KEY_SIM_CHOICE, getSelectedSimChoice())
            .apply()

        val svc = Intent(this, SmsGatewayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        statusText.text = if (url.isEmpty()) {
            "अवस्था: 🟢 चालु छ (यही फोन 127.0.0.1:${SmsGatewayService.LOCAL_BRIDGE_PORT})"
        } else {
            "अवस्था: 🟢 चालु छ (Local 127.0.0.1:${SmsGatewayService.LOCAL_BRIDGE_PORT} + Server)"
        }
        return true
    }

    private fun sendDirectTestSms() {
        val phone = testPhoneInput.text.toString().trim()
        if (phone.length < 10) {
            Toast.makeText(this, "१० अङ्कको मोबाइल नं. राख्नुहोस्।", Toast.LENGTH_SHORT).show()
            return
        }
        if (!startGateway()) return
        val key = keyInput.text.toString().trim()
        Thread {
            try {
                Thread.sleep(500)
                val url = URL("http://127.0.0.1:${SmsGatewayService.LOCAL_BRIDGE_PORT}/send")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 15000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-gateway-key", key)
                }
                val payload = JSONObject()
                    .put("to", phone)
                    .put("customerName", "App Test")
                    .put("message", "Pragati Jal Auto SIM Test OK!")
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
                val code = conn.responseCode
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                conn.disconnect()
                runOnUiThread {
                    refreshLogsFromPrefs()
                    Toast.makeText(
                        this,
                        if (code == 200) "✅ टेस्ट SMS पठाइयो!" else "❌ त्रुटि ($code): $resp",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "❌ त्रुटि: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
