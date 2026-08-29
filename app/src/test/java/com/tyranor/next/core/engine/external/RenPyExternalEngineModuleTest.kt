package com.tyranor.next.core.engine.external

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenPyExternalEngineModuleTest {
    @Test
    fun buildsRenPyGameJsonPayload() {
        val request = ExternalEngineLaunchRequest(
            game = ScanGame(
                title = "测试 RenPy",
                uri = "content://com.android.externalstorage.documents/tree/primary%3AGames%2FRenPy",
                engine = EngineType.RENPY,
                launchTarget = "[游戏目录]",
            ),
            gameDirectoryPath = "/storage/emulated/0/Games/RenPy/",
        )

        val json = parseFlatJson(RenPyExternalEngineModule.buildGameJson(request))

        assertEquals("测试 RenPy", json["title"])
        assertEquals("/storage/emulated/0/Games/RenPy", json["folder"])
        assertEquals("", json["execFile"])
        assertEquals("renpy", json["type"])
        assertTrue(json["id"].orEmpty().isNotBlank())
    }

    @Test
    fun escapesJsonStringValues() {
        val request = ExternalEngineLaunchRequest(
            game = ScanGame(
                title = "引号\"与换行\n",
                uri = "file:///storage/emulated/0/Games/RenPy",
                engine = EngineType.RENPY,
                launchTarget = "[游戏目录]",
            ),
            gameDirectoryPath = "/storage/emulated/0/Games/RenPy",
        )

        val payload = RenPyExternalEngineModule.buildGameJson(request)

        assertTrue(payload.contains("\"title\":\"引号\\\"与换行\\n\""))
    }

    private fun parseFlatJson(payload: String): Map<String, String> {
        val body = payload.removePrefix("{").removeSuffix("}")
        return body.split(',')
            .associate { entry ->
                val pair = entry.split("\":\"", limit = 2)
                pair[0].removePrefix("\"") to pair[1].removeSuffix("\"")
            }
    }
}
