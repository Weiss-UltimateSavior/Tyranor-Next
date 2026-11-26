package com.core.rpgmaker

import org.json.JSONObject

/**
 * 测试侧对 Storage 桥协议的解读。
 *
 * 生产协议刻意用「返回码 + JSON 报告」而不是异常：桥方法吞掉一切 Throwable，页面按返回值
 * 决定改不改镜像。测试里把它翻译成可读断言，避免每个用例都手写 JSONObject 取值。
 */
internal fun RpgMakerFsBridge.replayReport(): JSONObject = JSONObject(replayPendingStorage())

/** 本次重放实际落盘的条目数。 */
internal fun RpgMakerFsBridge.replayedCount(): Int = replayReport().getInt("applied")

/** 本次重放是否整体失败（队列本身不可读/写不回去）。 */
internal fun RpgMakerFsBridge.replayFatal(): Boolean = replayReport().getBoolean("fatal")

/** 仍留在队列里、等待下次重放的键名。 */
internal fun RpgMakerFsBridge.replayKept(): List<String> {
    val failed = replayReport().getJSONArray("failed")
    return (0 until failed.length()).map { failed.getJSONObject(it).getString("n") }
}

/** 接收一次修改，返回生产返回码（0=拒绝，1=已落盘，2=已接收待物化）。 */
internal fun RpgMakerFsBridge.commitCode(values: Map<String, String>, deletes: List<String> = emptyList(), clear: Boolean = false): Int {
    val payload = JSONObject()
    if (values.isNotEmpty()) payload.put("values", JSONObject(values as Map<*, *>))
    if (deletes.isNotEmpty()) payload.put("deletes", org.json.JSONArray(deletes))
    if (clear) payload.put("clear", true)
    return commitStorageMutation(payload.toString())
}
