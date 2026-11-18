package com.tyranor.next.core.game.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import java.io.File
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 游戏库 Room Migration（v2→v6）与实体映射的 JVM 校验：
 * 1. 验证 MIGRATION_2_3 / 3_4 / 4_5 / 5_6 精确执行新增绑定列与索引的 DDL（真机真实 SQLite 迁移见
 *    `androidTest/.../GameLibraryDatabaseDeviceMigrationTest.kt`）；
 * 2. 校验导出 schema (2/3/4/5/6.json) 列定义、索引、setupQueries identityHash 与版次；
 * 3. 验证 GameEntity.from() ↔ toModel() 双向无损保留全部远端绑定字段与元数据。
 */
class GameLibraryDatabaseMigrationTest {

    @Test
    fun explicitMigrationsCoverExportedSchemaDeltas() {
        val addColumnRegex = Regex("""ALTER\s+TABLE\s+games\s+ADD\s+COLUMN\s+([a-zA-Z0-9_]+)\b""", RegexOption.IGNORE_CASE)
        val createIndexRegex = Regex("""CREATE\s+INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?([a-zA-Z0-9_]+)\s+ON\s+games\b""", RegexOption.IGNORE_CASE)

        fun captureSql(migration: androidx.room.migration.Migration): List<String> {
            val sqls = mutableListOf<String>()
            val db = Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
            ) { _, method, args ->
                if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                    sqls.add(args[0] as String)
                }
                null
            } as SupportSQLiteDatabase
            migration.migrate(db)
            return sqls
        }

        val steps = listOf(
            Triple(GameLibraryDatabase.MIGRATION_2_3, readSchemaJson("2.json"), readSchemaJson("3.json")),
            Triple(GameLibraryDatabase.MIGRATION_3_4, readSchemaJson("3.json"), readSchemaJson("4.json")),
            Triple(GameLibraryDatabase.MIGRATION_4_5, readSchemaJson("4.json"), readSchemaJson("5.json")),
            Triple(GameLibraryDatabase.MIGRATION_5_6, readSchemaJson("5.json"), readSchemaJson("6.json")),
        )
        steps.forEach { (migration, fromSchema, toSchema) ->
            assertEquals(fromSchema.getJSONObject("database").getInt("version"), migration.startVersion)
            assertEquals(toSchema.getJSONObject("database").getInt("version"), migration.endVersion)
            val sqls = captureSql(migration)
            val addedColumns = sqls.mapNotNull { addColumnRegex.find(it)?.groupValues?.get(1) }.toSet()
            val addedIndices = sqls.mapNotNull { createIndexRegex.find(it)?.groupValues?.get(1) }.toSet()
            assertEquals(gamesColumnNames(toSchema) - gamesColumnNames(fromSchema), addedColumns)
            assertEquals(gamesIndexNames(toSchema) - gamesIndexNames(fromSchema), addedIndices)
        }
    }

    @Test
    fun exportedSchemasMatchMigrationColumnsAndEntityRoundTripsBindings() {
        val schema2 = readSchemaJson("2.json")
        val schema3 = readSchemaJson("3.json")
        val schema4 = readSchemaJson("4.json")
        val schema5 = readSchemaJson("5.json")
        val schema6 = readSchemaJson("6.json")

        val cols2 = gamesColumnNames(schema2)
        val cols3 = gamesColumnNames(schema3)
        val cols4 = gamesColumnNames(schema4)
        val cols5 = gamesColumnNames(schema5)
        val cols6 = gamesColumnNames(schema6)

        assertEquals(setOf("runtime_module_package"), cols3 - cols2)
        assertEquals(setOf("bangumi_id"), cols4 - cols3)
        assertEquals(setOf("hikarinagi_id"), cols5 - cols4)
        assertEquals(setOf("nextmoe_id"), cols6 - cols5)
        assertTrue(gamesIndexNames(schema3).contains("index_games_runtime_module_package"))
        assertTrue(gamesIndexNames(schema6).contains("index_games_runtime_module_package"))

        val hashes = listOf(schema2, schema3, schema4, schema5, schema6)
            .map { schema ->
                val db = schema.getJSONObject("database")
                val hash = db.getString("identityHash")
                val setupQueries = db.getJSONArray("setupQueries")
                val setupJoined = (0 until setupQueries.length()).joinToString("\n") { setupQueries.getString(it) }
                assertTrue("setupQueries must embed identityHash $hash", setupJoined.contains(hash))
                hash
            }
        assertTrue(hashes.all { it.isNotBlank() })
        assertEquals(hashes.size, hashes.toSet().size)

        assertEquals(6, schema6.getJSONObject("database").getInt("version"))
        assertTrue(cols6.contains("bangumi_id"))
        assertTrue(cols6.contains("hikarinagi_id"))
        assertTrue(cols6.contains("nextmoe_id"))
        assertTrue(cols6.contains("vndb_id"))
        assertTrue(cols6.contains("metadata_title"))
        assertEquals(4, schema4.getJSONObject("database").getInt("version"))
        assertEquals(5, schema5.getJSONObject("database").getInt("version"))

        val original = ScanGame(
            title = "サクラノ詩",
            uri = "content://games/sakura_uta",
            engine = EngineType.ARTEMIS,
            launchTarget = "root.pfs",
            coverUri = "file:///covers/sakura.webp",
            coverSource = "nextmoe",
            vndbId = "v562",
            bangumiId = "22459",
            hikarinagiId = "hkn-562",
            nextmoeId = "207379",
            metadataTitle = "樱之诗",
            externalModuleAlias = "alias_1",
            detectedRenpyVersion = null,
            runtimeModulePackage = "com.example.runtime",
            launchFile = "game.exe",
            openTime = 1_760_000_000_000L,
        )
        val entity = GameEntity.from(
            game = original,
            discoveredAt = 100L,
            updatedAt = 200L,
            rootUri = "content://roots/main",
        )
        assertEquals("content://roots/main", entity.rootUri)
        assertEquals(original, entity.toModel())
    }

    private fun gamesEntity(schema: JSONObject): JSONObject =
        schema.getJSONObject("database")
            .getJSONArray("entities")
            .let { arr ->
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .single { it.getString("tableName") == "games" }
            }

    private fun gamesColumnNames(schema: JSONObject): Set<String> {
        val fields = gamesEntity(schema).getJSONArray("fields")
        return (0 until fields.length()).mapTo(mutableSetOf()) { fields.getJSONObject(it).getString("columnName") }
    }

    private fun gamesIndexNames(schema: JSONObject): Set<String> {
        val indices = gamesEntity(schema).getJSONArray("indices")
        return (0 until indices.length()).mapTo(mutableSetOf()) { indices.getJSONObject(it).getString("name") }
    }

    private fun readSchemaJson(fileName: String): JSONObject {
        val relative = "schemas/com.tyranor.next.core.game.storage.GameLibraryDatabase/$fileName"
        val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("schema file not found: $relative (cwd=${File(".").absolutePath})")
        return JSONObject(file.readText())
    }
}
