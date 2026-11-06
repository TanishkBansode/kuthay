package com.kuthay.p2p

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private val uiScope = CoroutineScope(Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val etServer = findViewById<EditText>(R.id.etServer)
        val etLabel = findViewById<EditText>(R.id.etLabel)
        val etCode = findViewById<EditText>(R.id.etCode)
        val etMsg = findViewById<EditText>(R.id.etMsg)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvLog = findViewById<TextView>(R.id.tvLog)
        val btnSend = findViewById<Button>(R.id.btnSend)

        btnSend.isEnabled = false
        requestNotificationPermission()

        findViewById<Button>(R.id.btnCreate).setOnClickListener {
            launch(etServer.text.toString().trim(), etLabel.text.toString().trim(), "host", "")
        }
        findViewById<Button>(R.id.btnJoin).setOnClickListener {
            launch(
                etServer.text.toString().trim(),
                etLabel.text.toString().trim(),
                "join",
                etCode.text.toString().trim().uppercase()
            )
        }
        btnSend.setOnClickListener {
            val msg = etMsg.text.toString()
            if (msg.isNotEmpty()) P2pBus.session?.sendChat(msg)
        }

        uiScope.launch {
            P2pBus.logs.collect { line ->
                tvLog.append("$line\n")
            }
        }
        uiScope.launch {
            P2pBus.status.collect { s ->
                tvStatus.text = s
                btnSend.isEnabled = s.startsWith("CONNECTED") || s.startsWith("RELAYED")
            }
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun launch(server: String, label: String, mode: String, code: String) {
        if (server.isEmpty()) {
            P2pBus.log("enter server url first")
            return
        }
        val i = Intent(this, P2pService::class.java)
            .putExtra(P2pService.EXTRA_SERVER, server)
            .putExtra(P2pService.EXTRA_LABEL, label)
            .putExtra(P2pService.EXTRA_MODE, mode)
            .putExtra(P2pService.EXTRA_CODE, code)
        ContextCompat.startForegroundService(this, i)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
