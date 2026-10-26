package org.extem.mesh

// Pure protocol logic. No Android imports here on purpose: this class can be
// unit-tested on the JVM with `./gradlew test`, and it is a line-by-line port of
// src/core/policies.js and src/sim/engine.js, so the phone and the simulator
// cannot disagree about what the protocol does.

import java.util.UUID

data class MeshMessage(
    val id: String,
    val src: String,
    val dst: String,
    val createdAt: Long,
    val ttlSeconds: Int,
    val payload: ByteArray,
    var copies: Int
) {
    fun isExpired(nowSeconds: Long): Boolean = nowSeconds - createdAt > ttlSeconds
    fun isFor(me: String): Boolean = dst == me
}

/**
 * Store-and-forward buffer plus the two decisions the protocol makes.
 *
 *  1. WHAT to send  -> order by urgency (least time-to-live first), skip anything
 *                      the peer already has.
 *  2. HOW MANY copies -> binary Spray-and-Wait: keep half, hand over half, and at
 *                      one copy stop giving it away unless the peer IS the destination.
 */
class MeshNode(
    val selfId: String,
    private val initialCopies: Int = 8,
    private val bufferLimit: Int = 200
) {
    private val buffer = LinkedHashMap<String, MeshMessage>()
    val known = HashSet<String>()

    fun store(msg: MeshMessage) {
        known.add(msg.id)
        buffer[msg.id] = msg
        if (buffer.size > bufferLimit) {
            // evict the least urgent message (most lifetime remaining)
            val victim = buffer.values.maxByOrNull { it.ttlSeconds }
            if (victim != null) buffer.remove(victim.id)
        }
    }

    fun create(dst: String, nowSeconds: Long, payload: ByteArray): MeshMessage {
        val msg = MeshMessage(
            id = UUID.randomUUID().toString(),
            src = selfId,
            dst = dst,
            createdAt = nowSeconds,
            ttlSeconds = 1800,
            payload = payload,
            copies = initialCopies
        )
        store(msg)
        return msg
    }

    fun delivered(nowSeconds: Long): List<MeshMessage> =
        buffer.values.filter { it.isFor(selfId) }.also { list ->
            list.forEach { buffer.remove(it.id) }
        }

    fun dueForEviction(nowSeconds: Long): List<MeshMessage> =
        buffer.values.filter { it.isExpired(nowSeconds) }.also { list ->
            list.forEach { buffer.remove(it.id) }
        }

    /** Messages this node offers a peer that has the given set of known ids. */
    fun offersTo(peerKnown: Set<String>, nowSeconds: Long): List<MeshMessage> =
        buffer.values
            .asSequence()
            .filter { !peerKnown.contains(it.id) }
            .filter { !it.isExpired(nowSeconds) }
            .sortedBy { it.ttlSeconds - (nowSeconds - it.createdAt) }
            .toList()

    /** How many copies may be handed to this peer (0 = don't send). */
    fun copiesToGive(msg: MeshMessage, peerId: String): Int {
        if (msg.copies <= 1) return if (msg.dst == peerId) 1 else 0
        return msg.copies / 2
    }

    /** Apply the handover bookkeeping on the sender side. */
    fun spendCopies(msg: MeshMessage, peerId: String): Int {
        val give = copiesToGive(msg, peerId)
        if (give > 0 && msg.copies > 1) msg.copies -= give
        return give
    }

    fun size(): Int = buffer.size
    fun summary(): List<String> = buffer.keys.toList()
}

/**
 * Quorum scan schedule.
 *
 * A phone listens when (slot + offset) is a multiple of q1 OR of q2, with q1 and q2
 * coprime. For ANY two phones, whatever their offsets, there is at least one shared
 * listening slot inside q1*q2 slots. Duty cycle is about 1/q1 + 1/q2.
 *
 * A slot is 2 seconds, not 1, because a real BLE scan window needs ~2 s to reliably
 * catch an advertiser at a 1 s advertising interval and complete a connection. With
 * 5 and 7 slots that is ~31% of the time and a 35-slot (70 s) worst-case guarantee.
 * The simulator uses 1-second slots; that difference is documented in
 * docs/HOW_IT_WORKS.md and must be reported honestly.
 */
object QuorumSchedule {
    const val Q1 = 5
    const val Q2 = 7
    const val SLOT_SECONDS = 2L

    fun isActiveSecond(epochSecond: Long, offset: Long): Boolean {
        val slot = epochSecond / SLOT_SECONDS
        return (slot + offset) % Q1 == 0L || (slot + offset) % Q2 == 0L
    }

    fun dutyCycle(): Double = 1.0 / Q1 + 1.0 / Q2 - 1.0 / (Q1 * Q2)
}
