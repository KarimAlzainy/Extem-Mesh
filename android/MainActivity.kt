package org.extem.mesh

// Minimal UI: start the service, queue a test message, watch the log.
// Layout is built in code so the project has no XML resources to keep in sync.

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var dstField: EditText
    private lateinit var textField: EditText

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val line = intent?.getStringExtra("line") ?: return
            logView.append(line + "\n")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "EXTEM-Mesh\nnode ${MeshService.measuredDuty()}"
            textSize = 18f
        })

        dstField = EditText(this).apply { hint = "destination node id (or 'broadcast')" }
        textField = EditText(this).apply { hint = "message" }
        root.addView(dstField)
        root.addView(textField)

        root.addView(Button(this).apply {
            text = "Start mesh"
            setOnClickListener {
                val i = Intent(this@MainActivity, MeshService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            }
        })

        root.addView(Button(this).apply {
            text = "Queue message"
            setOnClickListener {
                val i = Intent(this@MainActivity, MeshService::class.java).apply {
                    action = "org.extem.mesh.SEND"
                    putExtra("dst", dstField.text.toString().ifBlank { "broadcast" })
                    putExtra("text", textField.text.toString().ifBlank { "hello" })
                }
                startService(i)
            }
        })

        logView = TextView(this).apply {
            textSize = 11f
            setPadding(0, 16, 0, 0)
        }
        root.addView(ScrollView(this).apply { addView(logView) }, ViewGroup.LayoutParams.MATCH_PARENT, 0)

        setContentView(root)
        registerReceiver(receiver, IntentFilter("org.extem.mesh.LOG"), RECEIVER_NOT_EXPORTED)
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .takeIf { it.isNotEmpty() }
            ?.let { requestPermissions(it.toTypedArray(), 1) }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}
