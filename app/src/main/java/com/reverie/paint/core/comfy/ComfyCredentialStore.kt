/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.comfy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** ComfyUI 服务器的认证方式 */
internal enum class ComfyAuthMode { NONE, BASIC, CUSTOM_HEADER }

/**
 * ComfyUI 服务器配置的**非敏感**部分。
 *
 * 密码不进这里, 由 [ComfyCredentialStore.savePassword] 单独加密存储 —— 这样这份配置
 * 可以直接落 SharedPreferences, 也方便把「测试连接」与「已保存凭据」分开。
 */
internal data class ComfyServerConfig(
    val baseUrl: String = "",
    val authMode: ComfyAuthMode = ComfyAuthMode.NONE,
    val username: String = "",
    val extraHeaders: List<Pair<String, String>> = emptyList(),
    val trustSelfSigned: Boolean = false,
)

/**
 * ComfyUI 凭据存储。
 *
 * 与 [com.reverie.paint.core.sync.SyncCredentialStore] 形状一致但**完全独立**: 独立的
 * 加密 key 别名, 独立的 prefs key, 互不覆盖。非敏感项直接落 SharedPreferences,
 * 密码走 AndroidKeyStore + AES/GCM 加密后再存。
 */
internal object ComfyCredentialStore {
    private const val PREFS = "paint_prefs"
    private const val KEY_BASE_URL = "comfy_base_url"
    private const val KEY_AUTH_MODE = "comfy_auth_mode"
    private const val KEY_USERNAME = "comfy_username"
    private const val KEY_EXTRA_HEADERS = "comfy_extra_headers"
    private const val KEY_TRUST_SELF_SIGNED = "comfy_trust_self_signed"
    private const val KEY_ENCRYPTED = "comfy_password_enc"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "reverie_comfy_password"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    // 自定义 header 的序列化分隔符 (用控制字符, 避免与 header 值里的常见字符冲突)
    private const val PAIR_SEP = "\u0001"
    private const val ROW_SEP = "\u0002"

    fun save(
        context: Context,
        config: ComfyServerConfig,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs
            .edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_AUTH_MODE, config.authMode.name)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_EXTRA_HEADERS, encodeHeaders(config.extraHeaders))
            .putBoolean(KEY_TRUST_SELF_SIGNED, config.trustSelfSigned)
            .apply()
    }

    fun load(context: Context): ComfyServerConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val authMode =
            runCatching { ComfyAuthMode.valueOf(prefs.getString(KEY_AUTH_MODE, null) ?: "") }
                .getOrDefault(ComfyAuthMode.NONE)
        return ComfyServerConfig(
            baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
            authMode = authMode,
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            extraHeaders = decodeHeaders(prefs.getString(KEY_EXTRA_HEADERS, null)),
            trustSelfSigned = prefs.getBoolean(KEY_TRUST_SELF_SIGNED, false),
        )
    }

    fun clear(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_BASE_URL)
            .remove(KEY_AUTH_MODE)
            .remove(KEY_USERNAME)
            .remove(KEY_EXTRA_HEADERS)
            .remove(KEY_TRUST_SELF_SIGNED)
            .remove(KEY_ENCRYPTED)
            .apply()
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        } catch (_: Exception) {
        }
    }

    fun savePassword(
        context: Context,
        password: String,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (password.isEmpty()) {
            prefs.edit().remove(KEY_ENCRYPTED).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, packed, 0, iv.size)
            System.arraycopy(cipherText, 0, packed, iv.size, cipherText.size)
            prefs
                .edit()
                .putString(KEY_ENCRYPTED, Base64.encodeToString(packed, Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            // 刻意不打印明文, 只记录异常类型
            android.util.Log.e("ReverieComfy", "保存 ComfyUI 凭据失败", e)
        }
    }

    fun loadPassword(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encoded = prefs.getString(KEY_ENCRYPTED, null) ?: return ""
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= IV_LEN) return ""
            val iv = packed.copyOfRange(0, IV_LEN)
            val cipherText = packed.copyOfRange(IV_LEN, packed.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("ReverieComfy", "读取 ComfyUI 凭据失败", e)
            ""
        }
    }

    private fun encodeHeaders(headers: List<Pair<String, String>>): String =
        headers
            .filter { it.first.isNotBlank() }
            .joinToString(ROW_SEP) { it.first.replace(PAIR_SEP, " ").replace(ROW_SEP, " ") + PAIR_SEP + it.second }

    private fun decodeHeaders(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(ROW_SEP).mapNotNull { row ->
            if (row.isEmpty()) return@mapNotNull null
            val idx = row.indexOf(PAIR_SEP)
            if (idx < 0) {
                row to ""
            } else {
                row.substring(0, idx) to row.substring(idx + PAIR_SEP.length)
            }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec =
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
