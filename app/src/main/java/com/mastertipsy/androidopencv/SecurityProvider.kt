package com.mastertipsy.androidopencv

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

internal object SecurityProvider {
    private const val KEY_ALIAS = "secure_hardware_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
    private const val PREFS_NAME = "secure_prefs"
    private const val ENCRYPTED_AES_KEY = "enc_aes_key"

    private var aesKey: SecretKey? = null

    fun initialize(context: Context) {
        if (aesKey != null) return
        try {
            aesKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getOrCreateAesKey(context)
            } else {
                getLegacyAesKey(context)
            }
        } catch (_: Exception) {
        }
    }

    fun dispose() {
        aesKey = null
    }

    fun encrypt(data: String): String {
        val encryptedBytes = encrypt(data.toByteArray(Charsets.UTF_8))
        return if (encryptedBytes.isEmpty()) "" else
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
    }

    fun encrypt(data: ByteArray): ByteArray {
        val key = aesKey ?: return ByteArray(0)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                encryptApi23Plus(data, key)
            } else {
                encryptApi21(data, key)
            }
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    fun decrypt(encryptedData: String): String {
        return try {
            val decoded = Base64.decode(encryptedData, Base64.NO_WRAP)
            val decryptedBytes = decrypt(decoded)
            if (decryptedBytes.isEmpty()) "" else String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun decrypt(encryptedData: ByteArray): ByteArray {
        val key = aesKey ?: return ByteArray(0)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                decryptApi23Plus(encryptedData, key)
            } else {
                decryptApi21(encryptedData, key)
            }
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun encryptApi23Plus(data: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(data)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun decryptApi23Plus(data: ByteArray, key: SecretKey): ByteArray {
        if (data.size <= 12) return ByteArray(0)
        val iv = data.sliceArray(0..11)
        val encrypted = data.sliceArray(12 until data.size)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun encryptApi21(data: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(data)
    }

    private fun decryptApi21(data: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val ivSize = cipher.blockSize
        if (data.size <= ivSize) return ByteArray(0)
        val iv = data.sliceArray(0 until ivSize)
        val encrypted = data.sliceArray(ivSize until data.size)
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        return cipher.doFinal(encrypted)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getOrCreateAesKey(context: Context): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        val keyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec
            .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
                ) {
                    setIsStrongBoxBacked(true)
                }
            }.build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun getLegacyAesKey(context: Context): SecretKey {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedKeyBase64 = prefs.getString(ENCRYPTED_AES_KEY, null)
        return if (encryptedKeyBase64 == null) {
            val key = ByteArray(16)
            SecureRandom().nextBytes(key)
            val secretKey = SecretKeySpec(key, "AES")
            val rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION)
            rsaCipher.init(Cipher.ENCRYPT_MODE, getOrCreateRsaKeyPair(context).public)
            val encryptedKey = rsaCipher.doFinal(key)
            val value = Base64.encodeToString(encryptedKey, Base64.NO_WRAP)
            prefs.edit { putString(ENCRYPTED_AES_KEY, value) }
            secretKey
        } else {
            val encryptedKey = Base64.decode(encryptedKeyBase64, Base64.NO_WRAP)
            val rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION)
            rsaCipher.init(Cipher.DECRYPT_MODE, getOrCreateRsaKeyPair(context).private)
            SecretKeySpec(rsaCipher.doFinal(encryptedKey), "AES")
        }
    }

    private fun getOrCreateRsaKeyPair(context: Context): KeyPair {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            val privateKey = ks.getKey(KEY_ALIAS, null) as PrivateKey
            val publicKey = ks.getCertificate(KEY_ALIAS).publicKey
            return KeyPair(publicKey, privateKey)
        }
        val kpg = KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE)
        val spec = KeyPairGeneratorSpec.Builder(context)
            .setAlias(KEY_ALIAS)
            .setSubject(X500Principal("CN=$KEY_ALIAS"))
            .setSerialNumber(BigInteger.TEN)
            .setStartDate(Calendar.getInstance().time)
            .setEndDate(Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time)
            .build()
        kpg.initialize(spec)
        return kpg.generateKeyPair()
    }
}
