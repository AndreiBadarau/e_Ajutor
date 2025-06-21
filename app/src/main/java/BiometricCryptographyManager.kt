
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom // For IV generation
import javax.crypto.Cipher
import javax.crypto.KeyGenerator // For AES key generation
import javax.crypto.SecretKey // For AES key
import javax.crypto.spec.IvParameterSpec // For IV
import javax.crypto.spec.SecretKeySpec
object BiometricCryptographyManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding" // Or "RSA/ECB/OAEPWithSHA-256AndMGF1Padding" for better security
    private const val AES_TRANSFORMATION = "AES/CBC/PKCS5Padding" // Common AES mode
    private const val AES_KEY_SIZE_BITS = 256 // AES-256
    private const val TAG = "BioCryptoManager"

    /**
     * Data class to hold the result of hybrid encryption.
     */
    data class EncryptedPayload(
        val encryptedSymmetricKey: String, // Base64 encoded RSA-encrypted AES key
        val iv: String,                    // Base64 encoded IV for AES
        val encryptedData: String          // Base64 encoded AES-encrypted actual data
    )

    /**
     * Encrypts data using a hybrid approach:
     * 1. Generate a new AES key.
     * 2. Encrypt the plaintext data with this AES key.
     * 3. Encrypt the AES key with the provided RSA public key.
     * Returns an EncryptedPayload containing all necessary parts.
     */
    fun encryptDataHybrid(plaintext: String, rsaPublicKey: PublicKey): EncryptedPayload? {
        try {
            // 1. Generate a new AES symmetric key
            val keyGenerator = KeyGenerator.getInstance("AES")
            keyGenerator.init(AES_KEY_SIZE_BITS)
            val aesKey: SecretKey = keyGenerator.generateKey()

            // 2. Encrypt the actual data with the AES symmetric key
            val aesCipher = Cipher.getInstance(AES_TRANSFORMATION)
            val ivBytes = ByteArray(aesCipher.blockSize) // AES block size is typically 16 bytes
            SecureRandom().nextBytes(ivBytes)
            val ivSpec = IvParameterSpec(ivBytes)

            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, ivSpec)
            val encryptedDataBytes = aesCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // 3. Encrypt the AES symmetric key with the RSA public key
            val rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION)
            rsaCipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey)
            val encryptedAesKeyBytes = rsaCipher.doFinal(aesKey.encoded)

            return EncryptedPayload(
                encryptedSymmetricKey = Base64.encodeToString(encryptedAesKeyBytes, Base64.DEFAULT),
                iv = Base64.encodeToString(ivBytes, Base64.DEFAULT),
                encryptedData = Base64.encodeToString(encryptedDataBytes, Base64.DEFAULT)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Hybrid encryption failed", e)
            return null
        }
    }

    /**
     * Initializes an RSA Cipher for decrypting the symmetric key.
     * This cipher is intended to be used with BiometricPrompt.
     */
    fun getInitializedCipherForSymmetricKeyDecryption(keyAlias: String, privateKey: PrivateKey? = null): Cipher? {
        return try {
            val actualPrivateKey = privateKey ?: getPrivateKey(keyAlias) // getPrivateKey should fetch from Keystore
            if (actualPrivateKey == null) {
                Log.e(TAG, "RSA Private key not found for alias: $keyAlias for symmetric key decryption")
                return null
            }
            val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, actualPrivateKey)
            cipher
        } catch (e: Exception) {
            Log.e(TAG, "RSA Cipher initialization for symmetric key decryption failed", e)
            null
        }
    }

    /**
     * Decrypts data using a hybrid approach:
     * 1. Decrypt the AES key using the provided RSA Cipher (unlocked by biometrics).
     * 2. Decrypt the actual data using the decrypted AES key and IV.
     */
    fun decryptDataHybrid(
        encryptedPayload: EncryptedPayload,
        rsaCipherForSymmetricKey: Cipher // This cipher is unlocked by BiometricPrompt
    ): String? {
        try {
            Log.d(TAG, "decryptDataHybrid: Attempting to decrypt AES key. Encrypted symmetric key (Base64 from payload): ${encryptedPayload.encryptedSymmetricKey}")
            val encryptedAesKeyBytes = Base64.decode(encryptedPayload.encryptedSymmetricKey, Base64.DEFAULT)
            Log.d(TAG, "decryptDataHybrid: Encrypted AES key byte length: ${encryptedAesKeyBytes.size}")

            val decryptedAesKeyBytes: ByteArray
            try {
                // This is the RSA decryption of the AES key using the Cipher from BiometricPrompt
                decryptedAesKeyBytes = rsaCipherForSymmetricKey.doFinal(encryptedAesKeyBytes)
                Log.d(TAG, "decryptDataHybrid: RSA decryption of AES key successful. Decrypted AES key byte length: ${decryptedAesKeyBytes.size}")
                Log.d(TAG, "decryptDataHybrid: Decrypted AES key (first 4 bytes hex): ${decryptedAesKeyBytes.take(4).joinToString("") { "%02x".format(it) }}")
            } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
                Log.e(TAG, "decryptDataHybrid: RSA DECRYPTION OF AES KEY FAILED: Key permanently invalidated for alias.", e)
                return null
            } catch (e: javax.crypto.IllegalBlockSizeException) {
                Log.e(TAG, "decryptDataHybrid: RSA DECRYPTION OF AES KEY FAILED: IllegalBlockSizeException. Input data or RSA key is wrong.", e)
                return null
            } catch (e: javax.crypto.BadPaddingException) {
                Log.e(TAG, "decryptDataHybrid: RSA DECRYPTION OF AES KEY FAILED: BadPaddingException. Likely wrong RSA key or corrupted AES key data.", e)
                return null
            } catch (e: Exception) {
                Log.e(TAG, "decryptDataHybrid: RSA DECRYPTION OF AES KEY FAILED: Generic exception.", e)
                return null
            }

            if (decryptedAesKeyBytes.size != (AES_KEY_SIZE_BITS / 8)) {
                Log.e(TAG, "decryptDataHybrid: Decrypted AES key has unexpected size. Expected: ${AES_KEY_SIZE_BITS / 8}, Got: ${decryptedAesKeyBytes.size}")
                return null
            }

            val aesKey: SecretKey = SecretKeySpec(decryptedAesKeyBytes, 0, decryptedAesKeyBytes.size, "AES")

            Log.d(TAG, "decryptDataHybrid: IV from payload (Base64): ${encryptedPayload.iv}")
            val ivBytes = Base64.decode(encryptedPayload.iv, Base64.DEFAULT)
            Log.d(TAG, "decryptDataHybrid: Encrypted actual data from payload (Base64 prefix): ${encryptedPayload.encryptedData.take(32)}...")
            val encryptedDataBytes = Base64.decode(encryptedPayload.encryptedData, Base64.DEFAULT)

            val aesCipher = Cipher.getInstance(AES_TRANSFORMATION)
            val ivSpec = IvParameterSpec(ivBytes)
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, ivSpec)
            Log.d(TAG, "decryptDataHybrid: AES Cipher initialized for final data decryption.")

            val decryptedDataBytes = aesCipher.doFinal(encryptedDataBytes) // Original error point
            Log.d(TAG, "decryptDataHybrid: AES decryption of actual data successful.")

            return String(decryptedDataBytes, Charsets.UTF_8)

        } catch (e: javax.crypto.IllegalBlockSizeException) {
            Log.e(TAG, "decryptDataHybrid: AES DECRYPTION OF ACTUAL DATA FAILED: IllegalBlockSizeException.", e)
            // This is where your original error was being caught.
            // The logs above should tell us if the problem was with the AES key itself.
            return null
        } catch (e: javax.crypto.BadPaddingException) {
            Log.e(TAG, "decryptDataHybrid: AES DECRYPTION OF ACTUAL DATA FAILED: BadPaddingException.", e)
            return null
        }
        catch (e: Exception) {
            Log.e(TAG, "decryptDataHybrid: Hybrid decryption failed (outer most catch block).", e)
            return null
        }
    }

    // Generates a new key pair or retrieves an existing one
    private fun getOrCreateKeyPair(keyAlias: String): Pair<PublicKey, PrivateKey>? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (!keyStore.containsAlias(keyAlias)) {
                val keyPairGenerator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA,
                    ANDROID_KEYSTORE
                )

                val builder = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_ECB)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setUserAuthenticationRequired(true) // Require biometric auth to use the key

                // For API 23-29, set how long the key is valid after authentication
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    // builder.setUserAuthenticationValidityDurationSeconds(15) // e.g., 15 seconds
                    // For simplicity in this example, we won't set a short validity duration
                    // which means the key is usable as long as BiometricPrompt session is active
                    // or for a system-defined period after biometric auth if not tied to CryptoObject
                }
                // For API 30+, can set setUserAuthenticationParameters for more fine-grained control

                keyPairGenerator.initialize(builder.build())
                keyPairGenerator.generateKeyPair()
                Log.d(TAG, "New KeyPair generated for alias: $keyAlias")
            }

            val privateKey = keyStore.getKey(keyAlias, null) as PrivateKey
            val publicKey = keyStore.getCertificate(keyAlias).publicKey
            Pair(publicKey, privateKey)
        } catch (e: Exception) {
            Log.e(TAG, "Error with KeyPair for alias $keyAlias", e)
            null
        }
    }

    fun getPublicKey(keyAlias: String): PublicKey? {
        return getOrCreateKeyPair(keyAlias)?.first
    }

    fun getPrivateKey(keyAlias: String): PrivateKey? {
        return getOrCreateKeyPair(keyAlias)?.second
    }

    fun encryptData(plaintext: String, publicKey: PublicKey): String? {
        return try {
            val cipher = Cipher.getInstance(RSA_TRANSFORMATION) // <--- FIXED
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            null
        }
    }

    fun getInitializedCipherForDecryption(keyAlias: String, privateKey: PrivateKey? = null): Cipher? {
        // ...
        return try {
            val actualPrivateKey = privateKey ?: getPrivateKey(keyAlias)
            if (actualPrivateKey == null) {
                Log.e(TAG, "Private key not found for alias: $keyAlias")
                return null
            }
            val cipher = Cipher.getInstance(RSA_TRANSFORMATION) // <--- FIXED
            cipher.init(Cipher.DECRYPT_MODE, actualPrivateKey)
            cipher // Return initialized cipher
        } catch (e: Exception) {
            Log.e(TAG, "Cipher initialization for decryption failed", e)
            null
        }
    }


    fun decryptData(ciphertext: String, cipher: Cipher): String? {
        return try {
            val encryptedBytes = Base64.decode(ciphertext, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            null
        }
    }

    fun deleteInvalidKey(keyAlias: String) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
                Log.d(TAG, "Deleted Keystore entry for alias: $keyAlias")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting Keystore entry for $keyAlias", e)
        }
    }
}