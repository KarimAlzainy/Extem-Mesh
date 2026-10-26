package org.extem.mesh

// Foreground service that owns the radio and enforces the quorum schedule.
//
// Everything that costs battery is gated by QuorumSchedule.isActiveSecond(). The
// service also COUNTS how many seconds it actually listened, so the measured duty
// cycle can be compared with the 31% the simulator assumes. If those two numbers
// disagree, the simulator's constants are wrong and must be corrected — that is the
// whole point of building the phone version.

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class MeshService : Service() {

    companion object {
        const val CHANNEL_ID = "extem-mesh"
        const val NOTIFICATION_ID = 42

        @Volatile var running = false

        /** seconds the radio actually listened in this session */
        @Volatile var scanOnSeconds = 0L
        @Volatile var sessionSeconds = 0L
        @Volatile var delivered = 0
        @Volatile var relayed = 0

        fun measuredDuty(): Double =
            if (sessionSeconds == 0L) 0.0 else scanOnSeconds.toDouble() / sessionSeconds
    }

    private lateinit var node: MeshNode
    private var transport: BleTransport? = null
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private val quorumOffset = (0..34).random().toLong() // per-install phase

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // the UI can ask the service to originate a test message
        if (intent?.action == "org.extem.mesh.SEND") {
            val dst = intent.getStringExtra("dst") ?: "broadcast"
            val text = intent.getStringExtra("text") ?: "hello"
            node.create(dst, System.currentTimeMillis() / 1000, text.toByteArray())
            log("queued message for $dst")
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "EXTEM-Mesh", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("EXTEM-Mesh is running")
                .setContentText("Relaying messages without internet")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build()
        )

        node = MeshNode(selfId = installId())
        transport = BleTransport(
            context = this,
            selfId = installId(),
            node = node,
            // demo key: derived from a passphrase shared by the team's phones.
            // Replace with an ECDH handshake (Crypto.sharedSecret) for the real thing.
            key = Crypto.hkdf("extem-demo-passphrase".toByteArray(), ByteArray(16), "extem".toByteArray()),
            onDelivered = { text ->
                delivered++
                val nm2 = getSystemService(NotificationManager::class.java)
                nm2.notify(
                    NOTIFICATION_ID + 1,
                    Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("Message received")
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_dialog_email)
                        .build()
                )
            }
        ).also { it.onLog = { line -> log(line) } }

        running = true
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis() / 1000
            sessionSeconds++
            val shouldListen = QuorumSchedule.isActiveSecond(now, quorumOffset)

            if (shouldListen) scanOnSeconds++
            if (shouldListen != listening) {
                listening = shouldListen
                if (shouldListen) transport?.start() else transport?.stop()
            }

            node.dueForEviction(now)
            if (sessionSeconds % 10 == 0L) {
                log(
                    "t=${sessionSeconds}s duty=${"%.0f".format(100 * measuredDuty())}% " +
                        "buffered=${node.size()} delivered=$delivered battery=${batteryPercent()}%"
                )
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun batteryPercent(): Int {
        val bm = getSystemService(BatteryManager::class.java)
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun installId(): String =
        getSharedPreferences("mesh", Context.MODE_PRIVATE)
            .let { sp ->
                sp.getString("id", null) ?: java.util.UUID.randomUUID().toString().take(8).also {
                    sp.edit().putString("id", it).apply()
                }
            }

    private fun log(line: String) {
        android.util.Log.d("EXTEM", line)
        sendBroadcast(Intent("org.extem.mesh.LOG").putExtra("line", line))
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        transport?.stop()
        super.onDestroy()
    }
}
