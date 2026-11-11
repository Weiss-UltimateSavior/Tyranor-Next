package com.tyranor.next.core.game.scan

import com.core.impacto.ImpactoProfiles
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Non-executable declaration. Profile paths and arguments come only from shipped metadata. */
data class ImpactoDescriptor(val profile: String) {
    companion object {
        const val FILE_NAME = "impacto.json"
        const val MAX_BYTES = 4096

        /** Strict JSON subset for exactly two fields; no JSONObject coercion or duplicate keys. */
        fun parse(bytes: ByteArray): ImpactoDescriptor? {
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                return null
            }
            return Parser(text).parse()
        }

        /** Launch rereads the complete bounded file and refuses descriptor symlinks. */
        fun read(gameDirectory: File): ImpactoDescriptor? = try {
            val root = gameDirectory.canonicalFile
            val file = File(root, FILE_NAME)
            if (!root.isDirectory || !root.canRead() || file.canonicalFile != file || !file.isFile) {
                null
            } else {
                file.inputStream().use { input ->
                    val bytes = ByteArray(MAX_BYTES + 1)
                    var length = 0
                    while (length < bytes.size) {
                        val read = input.read(bytes, length, bytes.size - length)
                        if (read < 0) break
                        if (read == 0) continue
                        length += read
                    }
                    parse(bytes.copyOf(length))
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private class Parser(private val text: String) {
        private var position = 0

        fun parse(): ImpactoDescriptor? {
            if (!consume('{')) return null
            val keys = mutableSetOf<String>()
            var profile: String? = null
            repeat(2) { index ->
                val key = string() ?: return null
                if (!keys.add(key) || !consume(':')) return null
                when (key) {
                    "version" -> if (!consume('1')) return null
                    "profile" -> profile = string() ?: return null
                    else -> return null
                }
                if (!consume(if (index == 0) ',' else '}')) return null
            }
            whitespace()
            val id = profile ?: return null
            if (position != text.length || keys != setOf("version", "profile") || !ImpactoProfiles.isSupported(id)) {
                return null
            }
            return ImpactoDescriptor(id)
        }

        private fun whitespace() {
            while (position < text.length && text[position] in " \t\r\n") position++
        }

        private fun consume(expected: Char): Boolean {
            whitespace()
            if (position >= text.length || text[position] != expected) return false
            position++
            return true
        }

        private fun string(): String? {
            if (!consume('"')) return null
            val result = StringBuilder()
            while (position < text.length) {
                val ch = text[position++]
                when {
                    ch == '"' -> return result.toString()
                    ch < ' ' -> return null
                    ch == '\\' -> {
                        if (position >= text.length) return null
                        when (val escape = text[position++]) {
                            '"', '\\', '/' -> result.append(escape)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) return null
                                val hex = text.substring(position, position + 4)
                                if (hex.any { it !in "0123456789abcdefABCDEF" }) return null
                                result.append(hex.toInt(16).toChar())
                                position += 4
                            }
                            else -> return null
                        }
                    }
                    else -> result.append(ch)
                }
            }
            return null
        }
    }
}
