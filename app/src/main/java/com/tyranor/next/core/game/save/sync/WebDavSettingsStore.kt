package com.tyranor.next.core.game.save.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One encrypted payload makes endpoint, credentials and mode changes an atomic settings update. */
object WebDavSettingsStore {
    private const val PREFS = "webdav_sync_secure"
    private const val ALIAS = "tyranor_webdav_sync"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ENABLED = "enabled"
    private const val CONFIG = "config"
    private val enabledState = MutableStateFlow(false)
    val enabled = enabledState.asStateFlow()

    @Synchronized
    fun isEnabled(context: Context): Boolean {
        val value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)
        enabledState.value = value
        return value
    }

    @Synchronized
    fun setEnabled(context: Context, value: Boolean) {
        if (!context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(ENABLED, value).commit()) throw SaveSyncException(SaveSyncError.LOCAL_IO)
        enabledState.value = value
        if (!value) SaveSyncRuntime.onFeatureDisabled(context.applicationContext)
    }

    @Synchronized
    fun load(context: Context): WebDavConfig {
        val enabled = isEnabled(context)
        val encoded = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CONFIG, null) ?: return WebDavConfig(enabled = enabled)
        try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size < 29) throw SaveSyncException(SaveSyncError.CREDENTIALS_UNAVAILABLE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes, 0, 12))
            val json = JSONObject(String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
            return validated(WebDavConfig(json.getString("url"), json.getString("username"), json.getString("password"),
                SaveSyncMode.valueOf(json.getString("mode")), SaveSyncRetention.fromLegacy(json.getInt("limit")),
                json.optBoolean("showSyncFeedback", true), enabled))
        } catch (failure: SaveSyncException) {
            throw failure
        } catch (failure: Exception) {
            // Retain the encrypted payload. A temporarily unavailable key must not erase it.
            throw SaveSyncException(SaveSyncError.CREDENTIALS_UNAVAILABLE, cause = failure)
        }
    }

    @Synchronized
    fun save(context: Context, config: WebDavConfig) {
        if (!isEnabled(context)) throw SaveSyncException(SaveSyncError.NOT_CONFIGURED)
        val value = validated(config)
        try {
            val json = JSONObject().put("url", value.serverUrl).put("username", value.username)
                .put("password", value.password).put("mode", value.mode.name).put("limit", value.remoteLimit)
                .put("showSyncFeedback", value.showSyncFeedback)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            if (cipher.iv.size != 12) throw SaveSyncException(SaveSyncError.CREDENTIALS_UNAVAILABLE)
            val data = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            if (!context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(CONFIG, Base64.encodeToString(data, Base64.NO_WRAP)).commit()
            ) throw SaveSyncException(SaveSyncError.LOCAL_IO)
        } catch (failure: SaveSyncException) {
            throw failure
        } catch (failure: Exception) {
            throw SaveSyncException(SaveSyncError.CREDENTIALS_UNAVAILABLE, cause = failure)
        }
    }

    /**
     * Discards only this feature's encrypted settings payload and Keystore alias. The master switch,
     * local version archives, checkpoints and cloud objects are owned elsewhere and stay untouched.
     */
    @Synchronized
    fun reset(context: Context) {
        if (!isEnabled(context)) throw SaveSyncException(SaveSyncError.NOT_CONFIGURED)
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw SaveSyncException(SaveSyncError.CREDENTIALS_UNAVAILABLE, cause = failure)
        }
        if (!context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(CONFIG).commit()
        ) throw SaveSyncException(SaveSyncError.LOCAL_IO)
    }

    internal fun validated(config: WebDavConfig): WebDavConfig {
        if (config.remoteLimit !in SaveSyncRetention.choices) throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        if (config.username.any { it == '\r' || it == '\n' } || ':' in config.username) throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        if (config.serverUrl.isBlank()) {
            return config.copy(serverUrl = "")
        }
        val url = config.serverUrl.trim().toHttpUrlOrNull() ?: throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            throw SaveSyncException(SaveSyncError.INVALID_CONFIG)
        }
        return config.copy(serverUrl = url.newBuilder().apply {
            if (!url.encodedPath.endsWith('/')) addPathSegment("")
        }.build().toString())
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) throw SaveSyncException(SaveSyncError.CREDENTIALS_UNAVAILABLE)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
