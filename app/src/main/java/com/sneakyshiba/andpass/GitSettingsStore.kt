package com.sneakyshiba.andpass

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class GitSettingsStore(context: Context) {

    private val normalPrefs: SharedPreferences =
        context.getSharedPreferences("git_settings", Context.MODE_PRIVATE)

    private val encryptedPrefs: SharedPreferences =
        context.getSharedPreferences("git_password_encrypted", Context.MODE_PRIVATE)

    fun saveRepositoryUrl(repositoryUrl: String) {
        normalPrefs.edit()
            .putString("repository_url", repositoryUrl)
            .apply()
    }

    fun saveUsername(username: String) {
        normalPrefs.edit()
            .putString("username", username)
            .apply()
    }

    fun savePassword(password: String) {
        val encrypted = encrypt(password)

        encryptedPrefs.edit()
            .putString("password_ciphertext", encrypted.ciphertextBase64)
            .putString("password_iv", encrypted.ivBase64)
            .apply()
    }

    fun getRepositoryUrl(): String {
        return normalPrefs.getString("repository_url", "") ?: ""
    }

    fun getUsername(): String {
        return normalPrefs.getString("username", "") ?: ""
    }

    fun hasPassword(): Boolean {
        return !encryptedPrefs.getString("password_ciphertext", "").isNullOrEmpty() &&
                !encryptedPrefs.getString("password_iv", "").isNullOrEmpty()
    }

    fun getPassword(): String {
        val ciphertextBase64 = encryptedPrefs.getString("password_ciphertext", "") ?: ""
        val ivBase64 = encryptedPrefs.getString("password_iv", "") ?: ""

        if (ciphertextBase64.isBlank() || ivBase64.isBlank()) {
            return ""
        }

        return decrypt(
            EncryptedValue(
                ciphertextBase64 = ciphertextBase64,
                ivBase64 = ivBase64
            )
        )
    }

    fun clear() {
        normalPrefs.edit().clear().apply()
        encryptedPrefs.edit().clear().apply()
    }

    private fun encrypt(plaintext: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return EncryptedValue(
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        )
    }

    private fun decrypt(encryptedValue: EncryptedValue): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)

        val iv = Base64.decode(encryptedValue.ivBase64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(encryptedValue.ciphertextBase64, Base64.NO_WRAP)

        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )

        val plaintext = cipher.doFinal(ciphertext)
        return plaintext.toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }

        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    private data class EncryptedValue(
        val ciphertextBase64: String,
        val ivBase64: String
    )

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "andpass_git_password_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
