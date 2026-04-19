package dev.remoty.crypto

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Handles ECDH key exchange and AES-256-GCM encryption for the session.
 *
 * Flow:
 * 1. Both sides generate ephemeral ECDH key pairs
 * 2. Exchange public keys (over BLE during pairing)
 * 3. Derive shared secret via ECDH
 * 4. Derive AES-256 key from shared secret + pairing code salt
 * 5. All TCP traffic is encrypted with AES-256-GCM
 */
class SessionCrypto {

    private val ecKeyPair: KeyPair = generateEcKeyPair()
    private var sessionKey: SecretKey? = null

    val publicKeyBase64: String
        get() = Base64.getEncoder().encodeToString(ecKeyPair.public.encoded)

    /**
     * Derive the session encryption key from the remote's public key
     * and the 6-digit pairing code (used as additional salt).
     */
    fun deriveSessionKey(remotePublicKeyBase64: String, pairingCode: String) {
        val remoteKeyBytes = Base64.getDecoder().decode(remotePublicKeyBase64)
        val keySpec = X509EncodedKeySpec(remoteKeyBytes)
        val keyFactory = KeyFactory.getInstance("EC")
        val remotePublicKey: PublicKey = keyFactory.generatePublic(keySpec)

        // ECDH shared secret
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(ecKeyPair.private)
        keyAgreement.doPhase(remotePublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // KDF: SHA-256(sharedSecret || pairingCode)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sharedSecret)
        digest.update(pairingCode.toByteArray(Charsets.UTF_8))
        val derivedKey = digest.digest()

        sessionKey = SecretKeySpec(derivedKey, "AES")
    }

    /** Encrypt plaintext with AES-256-GCM. Returns iv (12 bytes) + ciphertext + tag. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = sessionKey ?: throw IllegalStateException("Session key not derived")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext // 12 bytes IV + ciphertext + 16 bytes GCM tag
    }

    /** Decrypt iv (12 bytes) + ciphertext + tag. */
    fun decrypt(data: ByteArray): ByteArray {
        val key = sessionKey ?: throw IllegalStateException("Session key not derived")
        val iv = data.copyOfRange(0, 12)
        val ciphertext = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    fun hasSessionKey(): Boolean = sessionKey != null

    companion object {
        private fun generateEcKeyPair(): KeyPair {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"))
            return gen.generateKeyPair()
        }

        /** Generate a random 6-digit pairing code. */
        fun generatePairingCode(): String {
            val code = SecureRandom().nextInt(1_000_000)
            return code.toString().padStart(6, '0')
        }
    }
}
