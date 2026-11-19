package com.tyranor.next.core.net

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.tyranor.next.BuildConfig
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * 中性 HTTP 帮助层：`core/auth`、`core/cover`、`core/status` 三方共用的带状态码 JSON 请求、
 * 2xx 便捷封装、URL 编码与请求节流。独立于任何业务域，供各域单向引用
 * （此前落在 `core/cover` 域内，auth 反向引用形成了 auth⇄cover 包级环，故下沉于此）。
 *
 * 请求头与缓存语义（后加，调用方无需重复设置）：每个请求固定 `useCaches = false` 并发送
 * `Cache-Control: no-cache` / `Pragma: no-cache`，确保授权与状态读写不命中陈旧中间缓存；
 * 调用方传入的 headers 在默认头之后逐条覆盖写入。`httpJsonResult` 不吞协程取消：
 * `CancellationException` 原样上抛（其余传输异常归一为 `status = 0`），见函数体注释。
 */

/** HTTP 响应：状态码 + 已解析的 JSON 体（非 JSON 体时为 null）。 */
internal data class HttpJsonResult(val status: Int, val json: JSONObject?)

/** 仅用于 debug 单元测试替换 HTTP 往返；release 构建由断言禁止激活。 */
@VisibleForTesting
@Volatile
internal var testHttpRoundTrip: ((url: String, method: String, body: String?, headers: Map<String, String>) -> HttpJsonResult)? = null

/**
 * JSON 响应体上限：与 CoverImageCache 的图片上限同理，避免被超大响应打爆内存。
 * internal（原 private，BK-22）：core/auth 两服务的手写刷新请求（不走 [httpJsonResult]）
 * 复用 [readTextCapped] 限长读取，共用同一上限常量。
 */
internal const val MAX_JSON_CHARS = 4 * 1024 * 1024

/**
 * 带状态码的 JSON 请求：供需要区分 HTTP 状态的来源使用（如 NextMoe 的 429 限流）。
 * 传输异常统一返回 `status = 0`；已在读体前拿到的状态码不会因读体失败而丢失。
 */
internal fun httpJsonResult(
    url: String,
    method: String = "GET",
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
): HttpJsonResult {
    testHttpRoundTrip?.let { stub ->
        check(BuildConfig.DEBUG) { "test stub must be null in release" }
        return stub(url, method, body, headers)
    }
    var conn: HttpURLConnection? = null
    return try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TyranorNext/1.0")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                // Bangumi API 要求 Content-Type 精确为 application/json（带 charset 后缀返回
                // 415，设备日志+curl 双重实证）；JSON 本身按 RFC 8259 恒为 UTF-8，后缀冗余
                setRequestProperty("Content-Type", "application/json")
            }
        }
        conn = connection
        if (body != null) {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val status = connection.responseCode
        val text = runCatching {
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readTextCapped(MAX_JSON_CHARS) }
        }.getOrNull()
        HttpJsonResult(status, text?.let { runCatching { JSONObject(it) }.getOrNull() })
    } catch (e: CancellationException) {
        // 当前全为阻塞 IO、取消在 catch 之外抛出（现状不可达）；一旦引入挂起点，
        // 吞掉 CE 会把取消变成假失败（AGENTS.md CE 透传约束）
        throw e
    } catch (e: Exception) {
        // 传输异常（DNS/TLS/超时）以 status=0 返回；留日志便于设备侧排障（「网络错误」真身），脱敏 query/fragment 参数
        runCatching { Log.w("HttpJson", "request failed: ${sanitizeUrlForLog(url)}", e) }
        HttpJsonResult(0, null)
    } finally {
        conn?.disconnect()
    }
}

/** 日志 URL 脱敏：剥离 query 与 fragment 参数，防止路径外的凭据或临时参数写入 logcat。 */
internal fun sanitizeUrlForLog(rawUrl: String): String =
    rawUrl.substringBefore('?').substringBefore('#')

/** 限长读取：超过 [maxChars] 即抛 [IllegalStateException]，调用方按各自既有失败分支兜底（BK-22）。 */
internal fun Reader.readTextCapped(maxChars: Int): String {
    val buffer = CharArray(16 * 1024)
    val out = StringBuilder()
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (out.length + read > maxChars) throw IllegalStateException("json response too large")
        out.append(buffer, 0, read)
    }
    return out.toString()
}

/**
 * 请求最小间隔节流：VNDB / 未萌 NextMoe 都是「单机串行 + 服务端限流」的来源。
 * 必须在 IO 线程调用（内部阻塞）。
 */
internal class RequestThrottle(private val minIntervalMs: Long) {
    private val minIntervalNs = TimeUnit.MILLISECONDS.toNanos(minIntervalMs)
    private var lastRequestNanos = 0L
    private var hasLastRequest = false

    @Synchronized
    fun await() {
        if (testHttpRoundTrip != null) {
            check(BuildConfig.DEBUG) { "test stub must be null in release" }
            return
        }
        if (hasLastRequest) {
            val remainingNs = minIntervalNs - (System.nanoTime() - lastRequestNanos)
            if (remainingNs > 0L) {
                Thread.sleep(remainingNs / 1_000_000L, (remainingNs % 1_000_000L).toInt())
            }
        }
        lastRequestNanos = System.nanoTime()
        hasLastRequest = true
    }
}

/**
 * api.vndb.org 全局共享限流桶（`core/net` 中性层）：`core/cover`（VN 搜索）与 `core/status`
 * （authinfo / ulist 读写）共用同一条串行队列，避免超出 VNDB「200 请求/5 分钟」软上限，
 * 同时避免 `core/status` 反向引用 `VndbCoverService`。
 */
internal val vndbThrottle = RequestThrottle(1550L)

/** 仅成功（2xx）时返回 JSON，行为与各来源原有 httpJson 保持一致。 */
internal fun httpJson(
    url: String,
    method: String = "GET",
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
): JSONObject? = httpJsonResult(url, method, body, headers)
    .let { if (it.status in 200..299) it.json else null }

internal fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name())

/**
 * URL 路径段百分比编码：在 UTF-8 URL 编码基础上把表单空格 `+` 转为路径段标准的 `%20`，
 * 避免含空格的外部条目 ID 在路径段中保留 `+` 字面语义，并拒绝空白与 `.` / `..` 纯点段路径穿越。
 */
internal fun String.urlEncodePathSegment(): String {
    val trimmed = trim()
    require(trimmed.isNotEmpty() && trimmed != "." && trimmed != "..") {
        "invalid URL path segment: $this"
    }
    return URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

/**
 * 读取非空白 JSON 字符串字段：JSON `null`（`optString` 会返回字面量 `"null"`）、
 * 纯空白或缺失均返回 `null`，其余返回 `trim()` 后字符串。
 */
internal fun JSONObject.optNonBlankString(key: String): String? =
    if (isNull(key)) null else optString(key, "").trim().takeIf { it.isNotBlank() && it != "null" }
