package com.extem.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.extem.mesh.MainActivity
import com.extem.mesh.R
import com.extem.mesh.ble.BleAdvertiser
import com.extem.mesh.ble.BleGattServer
import com.extem.mesh.ble.BleLink
import com.extem.mesh.ble.BleScanner
import com.extem.mesh.core.EventLog
import com.extem.mesh.core.MeshRepository
import com.extem.mesh.core.NodeIdentity
import com.extem.mesh.protocol.FrameKind
import com.extem.mesh.protocol.MeshFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only part of the app that talks to Bluetooth.
 *
 * It is a foreground service because Android kills normal background work after a few
 * minutes: without a visible notification the radio would stop exactly when the phones
 * are far apart and the test matters. The notification is what buys us the right to keep
 * scanning and advertising.
 *
 * Two roles run at the same time on every phone:
 *   - server   : we advertise, and answer when somebody connects to us
 *   - client   : we scan, and connect to peers whose node ID is larger than ours
 * The "smaller ID connects" rule is the tie breaker that stops both phones from dialling
 * each other at the same moment.
 */
class MeshService : Service() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var advertiser: BleAdvertiser
    private lateinit var server: BleGattServer
    private lateinit var scanner: BleScanner
    private lateinit var link: BleLink

    private var selfId: String = ""
    private var active = false
    private val linkingNow = AtomicBoolean(false)
    private val lastLinkAt = ConcurrentHashMap<String, Long>()

    private val log: (String, String) -> Unit = { event, details ->
        EventLog.write(selfId, event, details)
    }

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMesh()
            stopSelf()
            return START_NOT_STICKY
        }

        // Must happen within a few seconds of being started, otherwise Android throws.
        startForegroundCompat()
        startMesh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopMesh()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ start / stop

    private fun startMesh() {
        if (active) return   // already running
        active = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        selfId = NodeIdentity.getOrCreate(this)
        MeshRepository.setSelfId(selfId)
        MeshRepository.setRunning(true)
        EventLog.write(selfId, "service", "start")

        advertiser = BleAdvertiser(this, NodeIdentity.toBytes(selfId), log)
        server = BleGattServer(this, ::onServerFrame, ::onServerConnection, log)
        scanner = BleScanner(this, ::onPeerSeen, log)
        link = BleLink(this, log)

        val advertiseError = advertiser.start()
        val serverError = server.start()
        val scanError = scanner.start()

        val problem = advertiseError ?: serverError ?: scanError
        if (problem != null) {
            EventLog.write(selfId, "error", problem)
            MeshRepository.setStatus(problem)
            updateNotification(problem)
        } else {
            MeshRepository.setStatus("Advertising and scanning")
            updateNotification("Advertising and scanning")
        }
    }

    private fun stopMesh() {
        if (!active) return
        active = false
        // selfId is deliberately kept: the advertising/scanning callbacks arrive a
        // moment later and must still be able to log which node stopped.
        EventLog.write(selfId, "service", "stop")
        scanner.stop()
        server.stop()
        advertiser.stop()
        scope.cancel()
        MeshRepository.setRunning(false)
        MeshRepository.setStatus("Stopped")
    }

    // ------------------------------------------------------------------ server side

    /** A peer connected to us (we are the GATT server for this link). */
    private fun onServerConnection(peer: String, connected: Boolean) {
        MeshRepository.setStatus(if (connected) "Peer connected" else "Advertising and scanning")
        if (connected) {
            MeshRepository.noteLink(peer)
            updateNotification("Peer connected")
        } else {
            updateNotification("Advertising and scanning")
        }
    }

    /** Frames written to us by a connected peer. */
    private fun onServerFrame(frame: MeshFrame) {
        when (frame.kind) {
            FrameKind.HELLO -> {
                EventLog.write(selfId, "link_in", "peer=${frame.senderId} hello")
                MeshRepository.setStatus("Linked with ${frame.senderId}")
            }

            FrameKind.TEXT -> {
                MeshRepository.addIncoming(frame)
                EventLog.write(selfId, "msg_in", "from=${frame.senderId}")
            }

            FrameKind.BYE -> {
                // The peer has finished sending, so it is our turn to answer.
                val messages = MeshRepository.drainOutgoing()
                val replies = ArrayList<MeshFrame>(messages.size + 2)
                replies.add(MeshFrame(FrameKind.HELLO, selfId))
                replies.addAll(messages)
                replies.add(MeshFrame(FrameKind.BYE, selfId))
                server.sendFrames(replies)
                EventLog.write(
                    selfId,
                    "link_out",
                    "peer=${frame.senderId} sent=${messages.size}"
                )
            }
        }
    }

    // ------------------------------------------------------------------ client side

    /** A phone running this app appeared during scanning. */
    private fun onPeerSeen(peerId: String, device: BluetoothDevice, rssi: Int) {
        if (peerId == selfId) return
        MeshRepository.notePeer(peerId, rssi)
        EventLog.write(selfId, "scan_peer", "peer=$peerId rssi=$rssi")

        // Only the phone with the smaller node ID dials. The other one waits.
        if (selfId >= peerId) return

        val now = System.currentTimeMillis()
        val last = lastLinkAt[peerId] ?: 0L
        if (now - last < LINK_COOLDOWN_MS) return
        if (!linkingNow.compareAndSet(false, true)) return
        lastLinkAt[peerId] = now

        scope.launch {
            try {
                val messages = MeshRepository.drainOutgoing()
                val frames = ArrayList<MeshFrame>(messages.size + 2)
                frames.add(MeshFrame(FrameKind.HELLO, selfId))
                frames.addAll(messages)
                frames.add(MeshFrame(FrameKind.BYE, selfId))

                MeshRepository.setStatus("Linking to $peerId")
                val received = link.exchange(device, frames)

                if (received.isEmpty()) {
                    // Nothing came back: the link did not complete, so keep the messages.
                    MeshRepository.returnToQueue(messages)
                    EventLog.write(selfId, "link_fail", "peer=$peerId")
                    MeshRepository.setStatus("Link failed, retrying later")
                } else {
                    var incoming = 0
                    for (frame in received) {
                        if (frame.kind == FrameKind.TEXT) {
                            MeshRepository.addIncoming(frame)
                            incoming++
                        }
                    }
                    MeshRepository.noteLink(peerId)
                    EventLog.write(
                        selfId,
                        "link_ok",
                        "peer=$peerId sent=${messages.size} received=$incoming"
                    )
                    MeshRepository.setStatus("Linked with $peerId")
                }
            } finally {
                linkingNow.set(false)
            }
        }
    }

    // ------------------------------------------------------------------ notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mesh link",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Bluetooth scanning and advertising alive"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EXTEM-Mesh · node $selfId")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("Starting mesh…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // The system refused the foreground start (for example a permission was
            // revoked). Log it instead of crashing the whole app.
            EventLog.write(selfId, "error", "foreground start failed: ${e.javaClass.simpleName}")
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "extem_mesh_link"
        private const val NOTIFICATION_ID = 42
        private const val LINK_COOLDOWN_MS = 15_000L

        const val ACTION_START = "com.extem.mesh.action.START"
        const val ACTION_STOP = "com.extem.mesh.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, MeshService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshService::class.java).setAction(ACTION_STOP)
            context.startForegroundService(intent)
        }
    }
}
