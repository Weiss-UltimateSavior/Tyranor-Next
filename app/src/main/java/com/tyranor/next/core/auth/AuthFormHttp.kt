package com.tyranor.next.core.auth

import android.content.SharedPreferences
import com.tyranor.next.core.net.urlEncode

/**
 * 授权域共享的小型重复组下沉（OAuth 令牌刷新请求体 + 授权状态同值短路判定）：
 * Bangumi / Hikarinagi 两域服务此前各持一份等价私有实现，收敛于此单一事实源，
 * 行为与原实现逐字节等价。VNDB 侧（无 OAuth 刷新）不使用请求体；其 store 内
 * 同款短路与哨兵常量的消费语义归游玩状态域处置，暂不并入。
 */

/** OAuth 刷新令牌请求的 `application/x-www-form-urlencoded` 体：`k=v&k=v`，键值均 UTF-8 百分号编码。 */
internal fun authFormBody(vararg values: Pair<String, String>): String =
    values.joinToString("&") { (key, value) ->
        "${key.urlEncode()}=${value.urlEncode()}"
    }

/**
 * 授权状态同值短路判定：prefs 键当前值已等于 [expected]（调用方先自行 trim 归一）时返回 true，
 * 调用方跳过 prefs 写与 statusVersion bump，避免逐请求重放路径的写放大与无谓重组。
 */
internal fun SharedPreferences.prefValueEquals(key: String, expected: String): Boolean =
    getString(key, "").orEmpty() == expected
