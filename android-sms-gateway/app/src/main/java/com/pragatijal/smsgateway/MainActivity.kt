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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var statusText: TextView
    private lateinit var logView: TextView

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(SmsGatewayService.EXTRA_LOG) ?: return
            logView.text = "$msg\n${logView.text}".take(3000)
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
                "अवस्था: 🟢 चालु छ (127.0.0.1:${SmsGatewayService.LOCAL_BRIDGE_PORT} + Server)"
            } else {
                "अवस्था: 🔴 बन्द"
            }
            setPadding(0, 12, 0, 20)
        }
        keyInput = EditText(this).apply {
            hint = "Pairing Key (सफ्टवेयरबाट कपी गरिएको १२+ अक्षर)"
            setText(prefs.getString(SmsGatewayService.KEY_PAIRING_KEY, ""))
        }
        urlInput = EditText(this).apply {
            hint = "Server URL (यही फोनबाट चलाउँदा खाली छोड्नुहोस्)"
            setText(prefs.getString(SmsGatewayService.KEY_SERVER_URL, ""))
        }

        val startBtn = Button(this).apply {
            text = "▶ अटो SIM Gateway सुरु गर्नुहोस्"
            setOnClickListener { startGateway() }
        }
        val stopBtn = Button(this).apply {
            text = "⏹ रोक्नुहोस्"
            setOnClickListener {
                stopService(Intent(this@MainActivity, SmsGatewayService::class.java))
                statusText.text = "अवस्था: 🔴 बन्द"
            }
        }

        logView = TextView(this).apply {
            text = "लग (यही फोन वा Server बाट Save थिच्नासाथ यहाँ SMS देखिन्छ):\n"
            textSize = 12f
        }
        val scroll = ScrollView(this).apply { addView(logView) }

        root.addView(title)
        root.addView(statusText)
        root.addView(keyInput)
        root.addView(urlInput)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(scroll)
        setContentView(root)

        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
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

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 200)
    }

    private fun startGateway() {
        val url = urlInput.text.toString().trim().trimEnd('/')
        val key = keyInput.text.toString().trim()
        if (key.length < 12) {
            Toast.makeText(this, "कृपया सफ्टवेयरको १२+ अक्षरको Pairing Key राख्नुहोस्।", Toast.LENGTH_LONG).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions()
            Toast.makeText(this, "पहिले SMS पठाउने अनुमति (Allow) दिनुहोस्।", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences(SmsGatewayService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(SmsGatewayService.KEY_SERVER_URL, url)
            .putString(SmsGatewayService.KEY_PAIRING_KEY, key)
            .apply()

        val svc = Intent(this, SmsGatewayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        statusText.text = if (url.isEmpty()) {
            "अवस्था: 🟢 चालु छ (यही फोन 127.0.0.1:${SmsGatewayService.LOCAL_BRIDGE_PORT} — Termux नचाहिने)"
        } else {
            "अवस्था: 🟢 चालु छ (Local 127.0.0.1:${SmsGatewayService.LOCAL_BRIDGE_PORT} + Server)"
        }
    }
}
