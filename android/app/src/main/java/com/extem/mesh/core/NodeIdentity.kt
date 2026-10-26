package com.extem.mesh.core

import android.content.Context

/**
 * Every phone gets a short random ID (8 hex characters) the first time the app runs.
 * The ID is stored in SharedPreferences, so it stays the same after a restart.
 *
 * The ID is *metadata* about the node. It is never a secret and never a signature:
 * Stage 1 has no authentication at all, so an ID only says "some phone claims to be A1B2C3D4".
 * Real identity verification is discussed in Stage 4.
 */
object NodeIdentity {

    private const val PREFS = "extem_identity"
    private const val KEY_NODE_ID = "node_id"
    private const val ID_BYTES = 4      // 4 bytes -> 8 hex characters

    /** Returns the stored node ID, creating one if this is the first run. */
    @Synchronized
    fun getOrCreate(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_NODE_ID, null)
        if (saved != null && saved.length == ID_BYTES * 2) return saved

        val random = java.security.SecureRandom()
        val bytes = ByteArray(ID_BYTES)
        random.nextBytes(bytes)
        val id = toHex(bytes)
        prefs.edit().putString(KEY_NODE_ID, id).apply()
        return id
    }

    /** "A1B2C3D4" -> byte array, used for the advertisement manufacturer data. */
    fun toBytes(id: String): ByteArray {
        val clean = id.trim().uppercase()
        require(clean.length == ID_BYTES * 2) { "node id must be ${ID_BYTES * 2} hex chars" }
        return ByteArray(ID_BYTES) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
