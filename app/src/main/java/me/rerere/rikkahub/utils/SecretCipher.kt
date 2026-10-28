package me.rerere.rikkahub.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 中不可导出的 AES 密钥加解密少量敏感字符串 (如 SSH 密码、私钥)。
 *
 * 密文和密钥绑定在本机: 卸载重装或换设备恢复备份后无法解密, 调用方需提示用户重新填写。
 */
object SecretCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "franhub_secret_cipher"

    /** 0.2.5 及更早版本使用的别名。旧密文仍要用它解，不能丢。 */
    private const val LEGACY_KEY_ALIAS = "rikkahub_secret_cipher"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val VERSION_PREFIX = "v1:"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return VERSION_PREFIX + Base64.getEncoder().encodeToString(cipher.iv + ciphertext)
    }

    /**
     * 解密。先试新别名，失败再试旧别名，兼容 0.2.5 及更早版本写入的密文。
     * 两个别名都没有密钥时才新建，避免把旧密文判成坏数据。
     */
    fun decrypt(value: String): String {
        require(value.startsWith(VERSION_PREFIX)) { "Unsupported secret format" }
        val bytes = Base64.getDecoder().decode(value.removePrefix(VERSION_PREFIX))
        val candidates = listOfNotNull(existingKey(KEY_ALIAS), existingKey(LEGACY_KEY_ALIAS))
            .ifEmpty { listOf(secretKey()) }
        var failure: Exception? = null
        for (key in candidates) {
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
                return cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).toString(Charsets.UTF_8)
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IllegalStateException("No secret key available")
    }

    /** 只读查询，不存在时返回 null，不生成新密钥。 */
    private fun existingKey(alias: String): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        keyStore.getKey(alias, null) as? SecretKey
    }.getOrNull()

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }
}
