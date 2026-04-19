package dev.remoty.crypto

import org.junit.Assert.*
import org.junit.Test

class SessionCryptoTest {

    @Test
    fun `generatePairingCode returns 6-digit string`() {
        repeat(100) {
            val code = SessionCrypto.generatePairingCode()
            assertEquals("Code must be 6 digits", 6, code.length)
            assertTrue("Code must be numeric", code.all { it.isDigit() })
            val value = code.toInt()
            assertTrue("Code value in range", value in 0..999999)
        }
    }

    @Test
    fun `publicKeyBase64 is non-empty and valid base64`() {
        val crypto = SessionCrypto()
        val pubKey = crypto.publicKeyBase64
        assertTrue("Public key should not be blank", pubKey.isNotBlank())
        // Should be decodable
        val decoded = java.util.Base64.getDecoder().decode(pubKey)
        assertTrue("Decoded key should have bytes", decoded.isNotEmpty())
    }

    @Test
    fun `two instances can derive same session key and encrypt-decrypt`() {
        val alice = SessionCrypto()
        val bob = SessionCrypto()
        val pairingCode = "123456"

        alice.deriveSessionKey(bob.publicKeyBase64, pairingCode)
        bob.deriveSessionKey(alice.publicKeyBase64, pairingCode)

        assertTrue(alice.hasSessionKey())
        assertTrue(bob.hasSessionKey())

        // Alice encrypts, Bob decrypts
        val plaintext = "Hello from Alice!".toByteArray()
        val encrypted = alice.encrypt(plaintext)
        val decrypted = bob.decrypt(encrypted)
        assertArrayEquals(plaintext, decrypted)

        // Bob encrypts, Alice decrypts
        val plaintext2 = "Hello from Bob!".toByteArray()
        val encrypted2 = bob.encrypt(plaintext2)
        val decrypted2 = alice.decrypt(encrypted2)
        assertArrayEquals(plaintext2, decrypted2)
    }

    @Test
    fun `wrong pairing code produces different keys`() {
        val alice = SessionCrypto()
        val bob = SessionCrypto()

        alice.deriveSessionKey(bob.publicKeyBase64, "111111")
        bob.deriveSessionKey(alice.publicKeyBase64, "222222")

        val plaintext = "Secret message".toByteArray()
        val encrypted = alice.encrypt(plaintext)

        try {
            bob.decrypt(encrypted)
            fail("Decryption should fail with wrong key")
        } catch (e: Exception) {
            // Expected: AEADBadTagException or similar
        }
    }

    @Test
    fun `encrypt produces different ciphertext each time (random IV)`() {
        val alice = SessionCrypto()
        val bob = SessionCrypto()
        val code = "999999"
        alice.deriveSessionKey(bob.publicKeyBase64, code)

        val plaintext = "Same message".toByteArray()
        val enc1 = alice.encrypt(plaintext)
        val enc2 = alice.encrypt(plaintext)

        assertFalse("Two encryptions should differ (random IV)", enc1.contentEquals(enc2))
    }

    @Test(expected = IllegalStateException::class)
    fun `encrypt before deriveSessionKey throws`() {
        val crypto = SessionCrypto()
        crypto.encrypt("test".toByteArray())
    }

    @Test(expected = IllegalStateException::class)
    fun `decrypt before deriveSessionKey throws`() {
        val crypto = SessionCrypto()
        crypto.decrypt(ByteArray(28)) // 12 IV + 16 tag
    }

    @Test
    fun `hasSessionKey returns false before derivation`() {
        val crypto = SessionCrypto()
        assertFalse(crypto.hasSessionKey())
    }

    @Test
    fun `encrypt-decrypt with empty plaintext`() {
        val alice = SessionCrypto()
        val bob = SessionCrypto()
        val code = "000000"
        alice.deriveSessionKey(bob.publicKeyBase64, code)
        bob.deriveSessionKey(alice.publicKeyBase64, code)

        val encrypted = alice.encrypt(ByteArray(0))
        val decrypted = bob.decrypt(encrypted)
        assertEquals(0, decrypted.size)
    }

    @Test
    fun `encrypt-decrypt with large payload`() {
        val alice = SessionCrypto()
        val bob = SessionCrypto()
        val code = "555555"
        alice.deriveSessionKey(bob.publicKeyBase64, code)
        bob.deriveSessionKey(alice.publicKeyBase64, code)

        val plaintext = ByteArray(1_000_000) { (it % 256).toByte() }
        val encrypted = alice.encrypt(plaintext)
        val decrypted = bob.decrypt(encrypted)
        assertArrayEquals(plaintext, decrypted)
    }
}
