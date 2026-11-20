package com.tyranor.next.core.auth

import android.content.SharedPreferences
import android.util.Log
import com.tyranor.next.core.net.MAX_JSON_CHARS
import com.tyranor.next.core.net.optNonBlankString
import com.tyranor.next.core.net.readTextCapped
import com.tyranor.next.core.net.urlEncode
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * 授权域共享的小型重复组下沉（OAuth 令牌表单请求、OIDC ID Token nonce 校验与授权状态同值短路判定）。
 */

/** OAuth 刷新令牌请求的 `application/x-www-form-urlencoded` 体：`k=v&k=v`，键值均 UTF-8 百分号编码。 */
internal fun authFormBody(vararg values: Pair<String, String>): String =
    values.joinToString("&") { (key, value) ->
        "${key.urlEncode()}=${value.urlEncode()}"
    }

/** OAuth 令牌端点 `application/x-www-form-urlencoded` POST 传输（15s/20s 超时、JSON body 限长读取）。 */
internal fun postAuthForm(tokenUrl: String, formBody: String): Pair<Int, String>? {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(tokenUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            useCaches = false
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TyranorNext/1.0")
        }
        conn.outputStream.use { it.write(formBody.toByteArray(StandardCharsets.UTF_8)) }
        val status = conn.responseCode
        val body = if (status in 200..299) {
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readTextCapped(MAX_JSON_CHARS) }
        } else {
            conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readTextCapped(MAX_JSON_CHARS) }.orEmpty()
        }
        status to body
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 与 HttpJson 同口径：脱敏 Log.w 留排障线索（不含表单体与令牌内容），调用方收敛为类型化失败
        Log.w("AuthFormHttp", "token endpoint request failed", e)
        null
    } finally {
        conn?.disconnect()
    }
}

internal enum class OidcNonceFailure {
    INVALID_FORMAT,
    PARSE_FAILED,
    NONCE_MISMATCH,
}

/**
 * OIDC `id_token` JWT `nonce` 校验：仅当 [expectedNonce] 为空（未请求 OIDC nonce）时跳过；
 * 若期望 nonce 非空，则缺失或非法的 [rawIdToken] 同样按 [OidcNonceFailure.INVALID_FORMAT] 拒绝。
 */
internal fun validateOidcIdTokenNonce(rawIdToken: String?, expectedNonce: String?): OidcNonceFailure? {
    val nonce = expectedNonce?.trim().orEmpty()
    if (nonce.isBlank()) return null
    val idToken = rawIdToken?.trim().orEmpty()
    if (idToken.isBlank()) return OidcNonceFailure.INVALID_FORMAT
    val parts = idToken.split(".")
    if (parts.size != 3) return OidcNonceFailure.INVALID_FORMAT
    return try {
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
        val actual = JSONObject(payload).optNonBlankString("nonce").orEmpty()
        if (MessageDigest.isEqual(actual.toByteArray(StandardCharsets.UTF_8), nonce.toByteArray(StandardCharsets.UTF_8))) {
            null
        } else {
            OidcNonceFailure.NONCE_MISMATCH
        }
    } catch (_: Exception) {
        OidcNonceFailure.PARSE_FAILED
    }
}

/**
 * 授权状态同值短路判定：prefs 键当前值已等于 [expected]（调用方先自行 trim 归一）时返回 true，
 * 调用方跳过 prefs 写与 statusVersion bump，避免逐请求重放路径的写放大与无谓重组。
 */
internal fun SharedPreferences.prefValueEquals(key: String, expected: String): Boolean =
    getString(key, "").orEmpty() == expected
