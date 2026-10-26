package com.extem.mesh.protocol

/**
 * One logical message that travels between two phones.
 *
 * Wire format (this is what actually goes over Bluetooth, before chunking):
 *
 *   byte 0        kind      (1 = HELLO, 2 = TEXT, 3 = BYE)
 *   bytes 1..4    sender    (the 8-hex-character node ID, 4 raw bytes)
 *   bytes 5..n    text      (UTF-8, may be empty)
 *
 * Keeping the sender inside the frame means the receiving phone can label a message
 * even when the Bluetooth device address is random and unknown.
 */
enum class FrameKind(val code: Int) {
    /** "I am here and this link works." Never shown in the chat. */
    HELLO(1),

    /** A real user message. */
    TEXT(2),

    /** "I have nothing more to send, you can close the link." */
    BYE(3);

    companion object {
        fun fromCode(code: Int): FrameKind? = entries.firstOrNull { it.code == code }
    }
}

data class MeshFrame(
    val kind: FrameKind,
    val senderId: String,
    val text: String = ""
) {
    fun encode(): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val idBytes = com.extem.mesh.core.NodeIdentity.toBytes(senderId)
        val out = ByteArray(1 + idBytes.size + textBytes.size)
        out[0] = kind.code.toByte()
        idBytes.copyInto(out, 1)
        textBytes.copyInto(out, 1 + idBytes.size)
        return out
    }

    companion object {
        /** Returns null when the bytes are not a valid frame (never throws). */
        fun decode(bytes: ByteArray): MeshFrame? {
            if (bytes.size < 5) return null
            val kind = FrameKind.fromCode(bytes[0].toInt() and 0xFF) ?: return null
            val idBytes = bytes.copyOfRange(1, 5)
            val senderId = com.extem.mesh.core.NodeIdentity.toHex(idBytes)
            val text = if (bytes.size > 5) String(bytes, 5, bytes.size - 5, Charsets.UTF_8) else ""
            return MeshFrame(kind, senderId, text)
        }
    }
}
