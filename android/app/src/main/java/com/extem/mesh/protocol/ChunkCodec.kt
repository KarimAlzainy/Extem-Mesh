package com.extem.mesh.protocol

import java.io.ByteArrayOutputStream

/**
 * Bluetooth LE can only move small packets, so every frame is cut into chunks.
 *
 * Chunk format (4-byte header + a slice of the frame):
 *
 *   byte 0   version   always 1
 *   byte 1   frameId   0..255, counts frames sent on a link (wraps around)
 *   byte 2   index     0..total-1
 *   byte 3   total     how many chunks this frame was cut into
 *   bytes 4..n         payload slice
 *
 * Why not "just write the whole message"? Android has no public API to read a
 * characteristic in pieces, and long writes behave differently on different phones.
 * Small numbered chunks are boring, but they work everywhere.
 */
object ChunkCodec {

    const val VERSION = 1
    const val HEADER_SIZE = 4

    /** BLE packet size before MTU negotiation: 20 usable bytes. */
    const val DEFAULT_PACKET_SIZE = 20

    /**
     * Cuts [payload] into chunks of at most [maxPacketSize] bytes (header included).
     * Always returns at least one chunk, even for an empty payload.
     */
    fun encode(frameId: Int, payload: ByteArray, maxPacketSize: Int): List<ByteArray> {
        val packet = maxPacketSize.coerceIn(HEADER_SIZE + 1, 512)
        val room = packet - HEADER_SIZE
        val total = ((payload.size + room - 1) / room).coerceAtLeast(1)
        require(total <= 255) { "frame too large for one link: $total chunks" }

        val chunks = ArrayList<ByteArray>(total)
        for (index in 0 until total) {
            val from = index * room
            val to = minOf(from + room, payload.size)
            val sliceSize = (to - from).coerceAtLeast(0)
            val chunk = ByteArray(HEADER_SIZE + sliceSize)
            chunk[0] = VERSION.toByte()
            chunk[1] = (frameId and 0xFF).toByte()
            chunk[2] = index.toByte()
            chunk[3] = total.toByte()
            if (sliceSize > 0) payload.copyInto(chunk, HEADER_SIZE, from, to)
            chunks.add(chunk)
        }
        return chunks
    }
}

/**
 * Puts the chunks back together again.
 *
 * One assembler belongs to one link, and frames are sent strictly one after another,
 * so only a single frame can be "in progress" at any moment.
 */
class FrameAssembler {

    private var frameId = -1
    private var total = 0
    private val parts = arrayOfNulls<ByteArray>(255)

    /** Feeds one chunk. Returns the complete frame bytes when the last chunk arrives. */
    @Synchronized
    fun accept(chunk: ByteArray): ByteArray? {
        if (chunk.size < ChunkCodec.HEADER_SIZE) return null
        if ((chunk[0].toInt() and 0xFF) != ChunkCodec.VERSION) return null

        val id = chunk[1].toInt() and 0xFF
        val index = chunk[2].toInt() and 0xFF
        val count = chunk[3].toInt() and 0xFF
        if (count == 0 || index >= count) return null

        if (id != frameId) {
            // A different frame starts here: throw away anything half-received.
            frameId = id
            total = count
            parts.fill(null)
        }
        if (count != total) return null

        parts[index] = chunk.copyOfRange(ChunkCodec.HEADER_SIZE, chunk.size)

        for (i in 0 until total) {
            if (parts[i] == null) return null   // still waiting for a chunk
        }

        val out = ByteArrayOutputStream()
        for (i in 0 until total) out.write(parts[i]!!)
        reset()
        return out.toByteArray()
    }

    @Synchronized
    fun reset() {
        frameId = -1
        total = 0
        parts.fill(null)
    }
}
