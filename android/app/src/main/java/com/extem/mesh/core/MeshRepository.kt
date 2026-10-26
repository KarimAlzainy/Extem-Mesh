package com.extem.mesh.core

import com.extem.mesh.protocol.FrameKind
import com.extem.mesh.protocol.MeshFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single place where the state of the app lives.
 *
 * The service writes into it, the UI reads from it. Keeping it in one object means the
 * screen can be closed and reopened without losing the chat, and the service keeps
 * working while the screen is off.
 */
object MeshRepository {

    data class ChatMessage(
        val id: Long,
        val from: String,
        val text: String,
        val at: Long,
        val mine: Boolean
    )

    data class Peer(
        val nodeId: String,
        val rssi: Int,
        val lastSeen: Long,
        val links: Int
    )

    private val _selfId = MutableStateFlow("--------")
    val selfId: StateFlow<String> = _selfId.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow("Stopped")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    /** Messages typed by the user that have not been sent over a link yet. */
    private val outgoing = ArrayDeque<String>()

    private var nextMessageId = 1L

    fun setSelfId(id: String) {
        _selfId.value = id
    }

    fun setRunning(running: Boolean) {
        _running.value = running
        _status.value = if (running) "Running" else "Stopped"
    }

    fun setStatus(text: String) {
        _status.value = text
    }

    /** Adds the user's message to the chat and to the send queue. */
    fun queueMessage(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        synchronized(outgoing) { outgoing.addLast(clean) }
        appendChat(
            ChatMessage(
                id = nextMessageId++,
                from = _selfId.value,
                text = clean,
                at = System.currentTimeMillis(),
                mine = true
            )
        )
        return true
    }

    fun pendingCount(): Int = synchronized(outgoing) { outgoing.size }

    /** Takes everything waiting to be sent. The queue is empty afterwards. */
    fun drainOutgoing(): List<MeshFrame> {
        val sender = _selfId.value
        val texts = synchronized(outgoing) {
            val copy = outgoing.toList()
            outgoing.clear()
            copy
        }
        return texts.map { MeshFrame(FrameKind.TEXT, sender, it) }
    }

    /** Puts the messages back when a link failed, so nothing is silently lost. */
    fun returnToQueue(frames: List<MeshFrame>) {
        val texts = frames.filter { it.kind == FrameKind.TEXT }.map { it.text }
        if (texts.isEmpty()) return
        synchronized(outgoing) { texts.asReversed().forEach { outgoing.addFirst(it) } }
    }

    fun addIncoming(frame: MeshFrame) {
        if (frame.kind != FrameKind.TEXT) return
        appendChat(
            ChatMessage(
                id = nextMessageId++,
                from = frame.senderId,
                text = frame.text,
                at = System.currentTimeMillis(),
                mine = false
            )
        )
    }

    fun notePeer(nodeId: String, rssi: Int) {
        _peers.value = _peers.value.map { peer ->
            if (peer.nodeId == nodeId) {
                peer.copy(rssi = rssi, lastSeen = System.currentTimeMillis())
            } else {
                peer
            }
        }.let { list ->
            if (list.any { it.nodeId == nodeId }) {
                list
            } else {
                list + Peer(nodeId, rssi, System.currentTimeMillis(), 0)
            }
        }
    }

    fun noteLink(nodeId: String) {
        _peers.value = _peers.value.map { peer ->
            if (peer.nodeId == nodeId) {
                peer.copy(lastSeen = System.currentTimeMillis(), links = peer.links + 1)
            } else {
                peer
            }
        }
    }

    fun reset() {
        _chat.value = emptyList()
        _peers.value = emptyList()
        synchronized(outgoing) { outgoing.clear() }
        nextMessageId = 1L
    }

    private fun appendChat(message: ChatMessage) {
        _chat.value = (_chat.value + message).takeLast(200)
    }

    /** Convenience for tests / debugging: "12:04:31" style timestamp. */
    fun formatTime(at: Long): String {
        val format = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        return format.format(java.util.Date(at))
    }
}
