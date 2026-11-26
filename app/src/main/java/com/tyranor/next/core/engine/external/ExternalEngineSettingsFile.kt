package com.tyranor.next.core.engine.external

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Reconciles a module's flat overrides without changing its unrelated settings. */
internal object ExternalEngineSettingsFile {
    enum class Result { READY, CONFLICT, UNAVAILABLE }

    private const val MAX_CONFIG_FILE_BYTES = 1024L * 1024L

    @Synchronized
    fun reconcile(
        file: File,
        settings: JSONObject,
        writable: Boolean = true,
        required: JSONObject = JSONObject(),
    ): Result = try {
        reconcileFile(file, settings, writable, required)
    } catch (_: IOException) {
        Result.UNAVAILABLE
    } catch (_: SecurityException) {
        Result.UNAVAILABLE
    }

    private fun reconcileFile(file: File, settings: JSONObject, writable: Boolean, required: JSONObject): Result {
        val exists = !Files.notExists(file.toPath())
        if (!exists && required.length() == 0) return Result.READY
        if (exists && (!file.isFile || file.length() > MAX_CONFIG_FILE_BYTES)) return Result.UNAVAILABLE

        val current = if (exists) {
            val text = file.readText()
            try {
                JSONObject(text)
            } catch (_: JSONException) {
                // The module discards malformed JSON before reading any overrides.
                return Result.READY
            }
        } else {
            JSONObject()
        }
        val conflicting = settings.keys().asSequence().any { key ->
            (current.has(key) && !matches(current, key, settings.get(key))) ||
                (required.has(key) && !current.has(key))
        }
        if (!conflicting) return Result.READY
        if (!writable) return Result.CONFLICT

        val valuesToWrite = if (exists) settings else required
        for (key in valuesToWrite.keys()) current.put(key, valuesToWrite.get(key))
        val target = file.toPath()
        val parent = target.parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, ".configuration-", ".tmp")
        try {
            temp.toFile().writeText(current.toString())
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        return Result.READY
    }

    private fun matches(file: JSONObject, key: String, expected: Any): Boolean {
        val value = file.opt(key)
        if (value == null || value === JSONObject.NULL) return false
        return when (expected) {
            is Int -> file.optInt(key, Int.MIN_VALUE) == expected
            is Boolean -> value == expected || (value is String && value.equals(expected.toString(), ignoreCase = true))
            else -> value.toString() == expected
        }
    }
}
