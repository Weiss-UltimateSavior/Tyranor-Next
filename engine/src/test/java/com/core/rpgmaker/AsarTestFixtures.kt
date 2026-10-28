package com.core.rpgmaker

import java.io.DataOutputStream
import java.io.File
import org.json.JSONObject

/** Synthetic archives only; flat keys also exercise existing implicit-directory semantics. */
internal fun writeTestAsar(
    file: File,
    packed: Map<String, String> = mapOf("other.txt" to "WRONG-PACKED-CONTENT"),
    unpacked: Map<String, String> = emptyMap(),
    declaredSizes: Map<String, Long> = emptyMap(),
    emptyDirectories: Set<String> = emptySet(),
) {
    val files = JSONObject()
    var offset = 0
    val payload = packed.mapValues { it.value.toByteArray(Charsets.UTF_8) }
    payload.forEach { (key, bytes) ->
        files.put(key, JSONObject().put("size", bytes.size).put("offset", offset.toString()))
        offset += bytes.size
    }
    unpacked.forEach { (key, content) ->
        files.put(key, JSONObject().put("size", declaredSizes[key] ?: content.toByteArray().size.toLong()).put("unpacked", true))
        // Deliberately invalid paths are metadata only; tests create any outside target separately.
        if (key.split('/').none { it == ".." }) {
            File(file.path + ".unpacked", key).apply { requireNotNull(parentFile).mkdirs(); writeText(content) }
        }
    }
    emptyDirectories.forEach { files.put(it, JSONObject().put("files", JSONObject())) }
    val json = JSONObject().put("files", files).toString().toByteArray(Charsets.UTF_8)
    val headerSize = ((8 + json.size + 3) / 4) * 4
    DataOutputStream(file.outputStream()).use { out ->
        fun little(value: Int) { repeat(4) { out.write(value ushr (8 * it) and 255) } }
        little(4); little(headerSize); little(4); little(json.size)
        out.write(json)
        repeat(8 + headerSize - 16 - json.size) { out.write(0) }
        payload.values.forEach { out.write(it) }
    }
}
