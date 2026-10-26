package org.extem.mesh

// Encryption for the payloads that are cached and relayed.
//
// Design: ECDH over P-256 between the two phones at handover time, HKDF-SHA256 to
// turn the shared secret into a key, AES-256-GCM for the message itself.
//
// IMPORTANT LIMITATION (state it out loud, do not hide it):
// this protects the CONTENT of a message from other phones in the mesh. It does NOT
// yet prove WHO sent it. An attacker inside the mesh can still create messages
// claiming to be someone else. Adding Ed25519 identity keys and signatures is
// future work, and it is listed as such on the poster.

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {

    fun generateIdentity(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    fun sharedSecret(mine: KeyPair, theirs: java.security.PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(mine.private)
        ka.doPhase(theirs, true)
        return ka.generateSecret()
    }

    /** HKDF-SHA256 (extract + expand), 32-byte key. */
    fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(secret)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01)
        return mac.doFinal().copyOf(32)
    }

    /** AES-256-GCM. Returns iv || ciphertext || tag. */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext)
        return iv + ct
    }

    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray? = try {
        val iv = blob.copyOfRange(0, 12)
        val ct = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.doFinal(ct)
    } catch (e: Exception) {
        null // tampered or wrong key
    }
}
