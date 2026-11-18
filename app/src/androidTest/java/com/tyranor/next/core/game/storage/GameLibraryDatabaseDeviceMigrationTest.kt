package com.tyranor.next.core.game.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.scan.GameRecordCodec
import com.tyranor.next.core.game.scan.GameRootMatcher
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.AppStorageNames
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机/模拟器 SQLite 实库迁移验证：
 * 覆盖从真实 v2、v3（含存量 `runtime_module_package`）、v4（含存量 `bangumi_id`）、
 * v5（含存量 `hikarinagi_id`）四条起点迁移至 v6，验证 Room schema 校验通过、索引建立、
 * `getRecentGames` / `getQuickLaunchGames` / `updateCover` 读写正常。
 */
@RunWith(AndroidJUnit4::class)
class GameLibraryDatabaseDeviceMigrationTest {

    private val createdDbs = mutableListOf<String>()

    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        val ctx = targetContext()
        createdDbs.forEach { ctx.deleteDatabase(it) }
        createdDbs.clear()
    }

    @Test
    fun migrateV2ToV6PreservesExistingGamesRecentQuickLaunchAndAllowsBindingColumns() {
        val dbName = registerDb("test_game_library_migration_v2_to_v6.db")
        val context = targetContext()
        seedRawDatabase(
            context = context,
            dbName = dbName,
            version = 2,
            identityHash = "4e0b9494daafa4a8dbbe26bbfc4a60e7",
            extraGameColumnsSql = "",
            extraGameIndexSql = null,
            gameInsertSql = "INSERT INTO `games` (`uri`, `root_uri`, `title`, `engine`, `launch_target`, " +
                "`launch_file`, `cover_uri`, `cover_source`, `vndb_id`, `metadata_title`, " +
                "`external_module_alias`, `detected_renpy_version`, `last_opened_at`, " +
                "`discovered_at`, `updated_at`, `sort_title`, `sort_tag`) VALUES (" +
                "'content://games/v2_item', 'content://roots/1', 'Ever17', 'KIRIKIRI', " +
                "'data.xp3', NULL, NULL, NULL, 'v17', 'Ever17', NULL, NULL, 1000, 100, 200, 'ever17', '')",
            quickLaunchUri = "content://games/v2_item",
        )

        openMigratedDb(context, dbName) { db ->
            runBlocking {
                val dao = db.gameLibraryDao()
                val migrated = dao.getAllGames().single()
                assertEquals("content://games/v2_item", migrated.uri)
                assertEquals("v17", migrated.vndbId)
                assertNull(migrated.runtimeModulePackage)
                assertNull(migrated.bangumiId)
                assertNull(migrated.hikarinagiId)
                assertNull(migrated.nextmoeId)
                assertEquals(listOf("content://games/v2_item"), dao.getRecentGames(10).map { it.uri })
                assertEquals(listOf("content://games/v2_item"), dao.getQuickLaunchGames().map { it.uri })

                dao.updateCover(
                    uri = migrated.uri,
                    coverUri = null,
                    coverSource = null,
                    vndbId = "v17",
                    bangumiId = "4154",
                    hikarinagiId = "hkn-17",
                    nextmoeId = "207379",
                    metadataTitle = "Ever17",
                    updatedAt = 300L,
                )
                val updated = dao.getAllGames().single()
                assertEquals("4154", updated.bangumiId)
                assertEquals("hkn-17", updated.hikarinagiId)
                assertEquals("207379", updated.nextmoeId)
            }
        }
    }

    @Test
    fun migrateV3ToV6PreservesRuntimeModulePackageAndIndex() {
        val dbName = registerDb("test_game_library_migration_v3_to_v6.db")
        val context = targetContext()
        seedRawDatabase(
            context = context,
            dbName = dbName,
            version = 3,
            identityHash = "2bf5b4663d5a60f9d7d3fe2c3922a7a8",
            extraGameColumnsSql = ", `runtime_module_package` TEXT",
            extraGameIndexSql = "CREATE INDEX IF NOT EXISTS `index_games_runtime_module_package` ON `games` (`runtime_module_package`)",
            gameInsertSql = "INSERT INTO `games` (`uri`, `root_uri`, `title`, `engine`, `launch_target`, " +
                "`launch_file`, `cover_uri`, `cover_source`, `vndb_id`, `metadata_title`, " +
                "`external_module_alias`, `detected_renpy_version`, `runtime_module_package`, `last_opened_at`, " +
                "`discovered_at`, `updated_at`, `sort_title`, `sort_tag`) VALUES (" +
                "'content://games/v3_item', 'content://roots/1', 'PluginGame', 'RUNTIME_MODULE', " +
                "'DIR', NULL, NULL, NULL, 'v33', 'PluginGame', NULL, NULL, 'com.example.plugin', 2000, 100, 200, 'plugingame', '')",
            quickLaunchUri = "content://games/v3_item",
        )

        openMigratedDb(context, dbName) { db ->
            runBlocking {
                val dao = db.gameLibraryDao()
                val migrated = dao.getAllGames().single()
                assertEquals("com.example.plugin", migrated.runtimeModulePackage)
                assertEquals("v33", migrated.vndbId)
                assertNull(migrated.bangumiId)
                assertNull(migrated.hikarinagiId)
                assertNull(migrated.nextmoeId)
                assertEquals(listOf("content://games/v3_item"), dao.getRecentGames(10).map { it.uri })
                assertEquals(listOf("content://games/v3_item"), dao.getQuickLaunchGames().map { it.uri })
            }
            assertTrue(readGamesIndices(db).contains("index_games_runtime_module_package"))
        }
    }

    @Test
    fun migrateV4ToV6PreservesBangumiIdAndAddsHikarinagiAndNextMoe() {
        val dbName = registerDb("test_game_library_migration_v4_to_v6.db")
        val context = targetContext()
        seedRawDatabase(
            context = context,
            dbName = dbName,
            version = 4,
            identityHash = "76f2231337d640afdb301e00f6074c32",
            extraGameColumnsSql = ", `bangumi_id` TEXT, `runtime_module_package` TEXT",
            extraGameIndexSql = "CREATE INDEX IF NOT EXISTS `index_games_runtime_module_package` ON `games` (`runtime_module_package`)",
            gameInsertSql = "INSERT INTO `games` (`uri`, `root_uri`, `title`, `engine`, `launch_target`, " +
                "`launch_file`, `cover_uri`, `cover_source`, `vndb_id`, `bangumi_id`, `metadata_title`, " +
                "`external_module_alias`, `detected_renpy_version`, `runtime_module_package`, `last_opened_at`, " +
                "`discovered_at`, `updated_at`, `sort_title`, `sort_tag`) VALUES (" +
                "'content://games/v4_item', 'content://roots/1', 'CLANNAD', 'KIRIKIRI', " +
                "'data.xp3', NULL, NULL, NULL, 'v4', '4154', 'CLANNAD', NULL, NULL, NULL, 3000, 100, 200, 'clannad', '')",
            quickLaunchUri = "content://games/v4_item",
        )

        openMigratedDb(context, dbName) { db ->
            runBlocking {
                val dao = db.gameLibraryDao()
                val migrated = dao.getAllGames().single()
                assertEquals("4154", migrated.bangumiId)
                assertEquals("v4", migrated.vndbId)
                assertNull(migrated.hikarinagiId)
                assertNull(migrated.nextmoeId)
                assertEquals(listOf("content://games/v4_item"), dao.getRecentGames(10).map { it.uri })
                assertEquals(listOf("content://games/v4_item"), dao.getQuickLaunchGames().map { it.uri })
            }
        }
    }

    @Test
    fun migrateV5ToV6PreservesHikarinagiIdAndAddsNextMoe() {
        val dbName = registerDb("test_game_library_migration_v5_to_v6.db")
        val context = targetContext()
        seedRawDatabase(
            context = context,
            dbName = dbName,
            version = 5,
            identityHash = "27886b6e1425d8734b192b361b75780a",
            extraGameColumnsSql = ", `bangumi_id` TEXT, `hikarinagi_id` TEXT, `runtime_module_package` TEXT",
            extraGameIndexSql = "CREATE INDEX IF NOT EXISTS `index_games_runtime_module_package` ON `games` (`runtime_module_package`)",
            gameInsertSql = "INSERT INTO `games` (`uri`, `root_uri`, `title`, `engine`, `launch_target`, " +
                "`launch_file`, `cover_uri`, `cover_source`, `vndb_id`, `bangumi_id`, `hikarinagi_id`, `metadata_title`, " +
                "`external_module_alias`, `detected_renpy_version`, `runtime_module_package`, `last_opened_at`, " +
                "`discovered_at`, `updated_at`, `sort_title`, `sort_tag`) VALUES (" +
                "'content://games/v5_item', 'content://roots/1', 'Sakura', 'ARTEMIS', " +
                "'root.pfs', NULL, NULL, NULL, 'v562', '22459', 'hkn-562', 'Sakura', NULL, NULL, NULL, 4000, 100, 200, 'sakura', '')",
            quickLaunchUri = "content://games/v5_item",
        )

        openMigratedDb(context, dbName) { db ->
            runBlocking {
                val dao = db.gameLibraryDao()
                val migrated = dao.getAllGames().single()
                assertEquals("22459", migrated.bangumiId)
                assertEquals("hkn-562", migrated.hikarinagiId)
                assertNull(migrated.nextmoeId)
                assertEquals(listOf("content://games/v5_item"), dao.getRecentGames(10).map { it.uri })
                assertEquals(listOf("content://games/v5_item"), dao.getQuickLaunchGames().map { it.uri })
            }
        }
    }

    @Test
    fun migrateV1DestructiveAndDowngradeV99ToV6RecoverViaProductionEnsureMigrated() {
        val context = targetContext()
        runBlocking { GameLibraryRepository.flushPrefsMirrorForTest(context) }
        val prefs = context.getSharedPreferences(AppStorageNames.GAME_SCANNER_PREFS, Context.MODE_PRIVATE)
        val prevRoots = prefs.getString("scan_roots", null)
        val prevGames = prefs.getString("scan_games", null)
        val prevRecent = prefs.getString("recent_games", null)
        val prevQuick = prefs.getString("quick_launch", null)
        val prevFlag = prefs.getBoolean("storage_migration_v1_done", false)
        val prevMigrationDone = GameLibraryRepository.isMigrationDoneForTest()

        val sampleGame = ScanGame(
            title = "MirrorGame",
            uri = "file:///roots/main/mirror_game",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            coverUri = "file:///covers/mirror.webp",
            coverSource = AppSettingsStore.COVER_SOURCE_NEXTMOE,
            vndbId = "v100",
            bangumiId = "200",
            hikarinagiId = "hkn-300",
            nextmoeId = "400",
            metadataTitle = "MirrorMeta",
            externalModuleAlias = "alias_m",
            detectedRenpyVersion = "8.5.3",
            runtimeModulePackage = "com.example.rt",
            launchFile = "boot.xp3",
            openTime = 5000L,
        )
        val mirrorGames = GameRecordCodec.serialize(listOf(sampleGame))
        val mirrorRecent = GameRecordCodec.serialize(listOf(sampleGame))
        val mirrorQuick = GameRecordCodec.serialize(listOf(sampleGame))

        try {
            // 1) v1 → v6 破坏性升级：走生产 GameLibraryRepository.ensureMigrated 入口完成首次迁移并置位标记
            val v1DbName = registerDb("test_game_library_v1_to_v6.db")
            object : SQLiteOpenHelper(context, v1DbName, null, 1) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `legacy_v1` (`id` INTEGER PRIMARY KEY)")
                }
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }.writableDatabase.close()

            prefs.edit()
                .putString("scan_roots", "file:///roots/main")
                .putString("scan_games", mirrorGames)
                .putString("recent_games", mirrorRecent)
                .putString("quick_launch", mirrorQuick)
                .putBoolean("storage_migration_v1_done", false)
                .commit()

            openConfiguredDbAsSingleton(context, v1DbName) { db ->
                GameLibraryRepository.resetMigrationForTest(false)
                runBlocking {
                    GameLibraryRepository.ensureMigrated(context)
                    assertTrue(prefs.getBoolean("storage_migration_v1_done", false))
                    assertTrue(GameLibraryRepository.isMigrationDoneForTest())
                    val dao = db.gameLibraryDao()
                    val entity = dao.getAllGames().single()
                    assertEquals("file:///roots/main", entity.rootUri)
                    assertEquals(sampleGame, entity.toModel())
                    assertEquals(listOf(sampleGame), dao.getRecentGames(10).map { it.toModel() })
                    assertEquals(listOf(sampleGame), dao.getQuickLaunchGames().map { it.toModel() })
                }
            }

            // 2) 降级（v99 → v6）破坏性重建 + 「已置位但库为空」自愈：
            //    storage_migration_v1_done 已为 true 且 DB countGames() == 0 时，ensureMigrated 须触发 repairNeeded 幂等重导入
            val downgradeDbName = registerDb("test_game_library_v99_to_v6.db")
            object : SQLiteOpenHelper(context, downgradeDbName, null, 99) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `future_table` (`id` INTEGER PRIMARY KEY)")
                }
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }.writableDatabase.close()

            prefs.edit().putBoolean("storage_migration_v1_done", true).commit()
            openConfiguredDbAsSingleton(context, downgradeDbName) { db ->
                GameLibraryRepository.resetMigrationForTest(false)
                runBlocking {
                    val dao = db.gameLibraryDao()
                    assertEquals(0, dao.countGames())
                    GameLibraryRepository.ensureMigrated(context)
                    val entity = dao.getAllGames().single()
                    assertEquals("file:///roots/main", entity.rootUri)
                    assertEquals(sampleGame, entity.toModel())
                    assertEquals(listOf(sampleGame), dao.getRecentGames(10).map { it.toModel() })
                    assertEquals(listOf(sampleGame), dao.getQuickLaunchGames().map { it.toModel() })

                    // 再次触发 ensureMigrated 仍幂等无重复
                    GameLibraryRepository.resetMigrationForTest(false)
                    GameLibraryRepository.ensureMigrated(context)
                    assertEquals(1, dao.countGames())
                }
            }
        } finally {
            prefs.edit().apply {
                if (prevRoots == null) remove("scan_roots") else putString("scan_roots", prevRoots)
                if (prevGames == null) remove("scan_games") else putString("scan_games", prevGames)
                if (prevRecent == null) remove("recent_games") else putString("recent_games", prevRecent)
                if (prevQuick == null) remove("quick_launch") else putString("quick_launch", prevQuick)
                putBoolean("storage_migration_v1_done", prevFlag)
            }.commit()
            GameLibraryRepository.resetMigrationForTest(prevMigrationDone)
            GameLibraryFacade.resetCachesForTest()
        }
    }

    @Test
    fun facadeRepositoryAndPrefsMirrorRoundTripWithRealRoomPersistence() {
        val context = targetContext()
        runBlocking { GameLibraryRepository.flushPrefsMirrorForTest(context) }
        val prefs = context.getSharedPreferences(AppStorageNames.GAME_SCANNER_PREFS, Context.MODE_PRIVATE)
        val prevRoots = prefs.getString("scan_roots", null)
        val prevGames = prefs.getString("scan_games", null)
        val prevRecent = prefs.getString("recent_games", null)
        val prevQuick = prefs.getString("quick_launch", null)
        val prevFlag = prefs.getBoolean("storage_migration_v1_done", false)
        val prevSkipPersist = GameLibraryFacade.skipPersistForTest
        val prevMigrationDone = GameLibraryRepository.isMigrationDoneForTest()

        val testRoot = "file:///device_persist_test/root"
        val testGame = ScanGame(
            title = "PersistDeviceGame",
            uri = "$testRoot/game_1",
            engine = EngineType.ARTEMIS,
            launchTarget = "root.pfs",
            bangumiId = "4154",
            nextmoeId = "207379",
        )
        GameLibraryFacade.skipPersistForTest = false
        try {
            GameLibraryFacade.saveRoot(context, testRoot)
            GameLibraryFacade.updateGames(context) { current ->
                current.filterNot { it.uri == testGame.uri } + testGame
            }
            GameLibraryFacade.updateGameCoverMetadata(context, testGame.uri) { current ->
                current.copy(
                    coverUri = "file:///covers/persist_1.webp",
                    coverSource = AppSettingsStore.COVER_SOURCE_NEXTMOE,
                    vndbId = "v562",
                    hikarinagiId = "hkn-562",
                    metadataTitle = "PersistMeta",
                )
            }
            runBlocking {
                GameLibraryRepository.flushPrefsMirrorForTest(context)
            }

            // 丢弃内存缓存强制从 Room DB 回源，验证全部 16 字段真实落库且 prefs 镜像同步写入
            GameLibraryFacade.resetCachesForTest()
            val reloaded = GameLibraryFacade.loadGames(context).firstOrNull { it.uri == testGame.uri }
            assertEquals("file:///covers/persist_1.webp", reloaded?.coverUri)
            assertEquals("v562", reloaded?.vndbId)
            assertEquals("4154", reloaded?.bangumiId)
            assertEquals("hkn-562", reloaded?.hikarinagiId)
            assertEquals("207379", reloaded?.nextmoeId)
            assertEquals("PersistMeta", reloaded?.metadataTitle)

            val mirroredGames = GameRecordCodec.parse(prefs.getString("scan_games", null).orEmpty())
            assertEquals(reloaded, mirroredGames.firstOrNull { it.uri == testGame.uri })
            assertTrue(GameLibraryFacade.loadRoots(context).contains(testRoot))
        } finally {
            runCatching { GameLibraryFacade.removeRootAndGames(context, android.net.Uri.parse(testRoot)) }
            runCatching { GameLibraryFacade.removeGame(context, testGame.uri) }
            runCatching {
                runBlocking {
                    GameLibraryRepository.flushPrefsMirrorForTest(context)
                }
            }
            prefs.edit().apply {
                if (prevRoots == null) remove("scan_roots") else putString("scan_roots", prevRoots)
                if (prevGames == null) remove("scan_games") else putString("scan_games", prevGames)
                if (prevRecent == null) remove("recent_games") else putString("recent_games", prevRecent)
                if (prevQuick == null) remove("quick_launch") else putString("quick_launch", prevQuick)
                putBoolean("storage_migration_v1_done", prevFlag)
            }.commit()
            GameLibraryFacade.skipPersistForTest = prevSkipPersist
            GameLibraryRepository.resetMigrationForTest(prevMigrationDone)
            GameLibraryFacade.resetCachesForTest()
        }
    }

    private fun registerDb(name: String): String {
        createdDbs += name
        targetContext().deleteDatabase(name)
        return name
    }

    private fun openConfiguredDbAsSingleton(context: Context, dbName: String, block: (GameLibraryDatabase) -> Unit) {
        // 仅排空在途写并取消防抖 mirrorJob，不能写回 prefs 覆盖测试刚写入的迁移夹具
        runBlocking { GameLibraryRepository.cancelPendingMirrorAndAwaitWritesForTest() }
        val db = GameLibraryDatabase.buildWithProductionConfig(context, dbName)
        val previous = GameLibraryDatabase.replaceInstanceForTest(db)
        try {
            block(db)
        } finally {
            runCatching { runBlocking { GameLibraryRepository.cancelPendingMirrorAndAwaitWritesForTest() } }
            GameLibraryDatabase.replaceInstanceForTest(previous)
            db.close()
        }
    }

    private fun openMigratedDb(context: Context, dbName: String, block: (GameLibraryDatabase) -> Unit) {
        val db = Room.databaseBuilder(context, GameLibraryDatabase::class.java, dbName)
            .addMigrations(
                GameLibraryDatabase.MIGRATION_2_3,
                GameLibraryDatabase.MIGRATION_3_4,
                GameLibraryDatabase.MIGRATION_4_5,
                GameLibraryDatabase.MIGRATION_5_6,
            )
            .build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun readGamesIndices(db: GameLibraryDatabase): Set<String> {
        val indices = mutableSetOf<String>()
        db.openHelper.readableDatabase.query("PRAGMA index_list(`games`)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIdx >= 0) indices += cursor.getString(nameIdx)
            }
        }
        return indices
    }

    private fun seedRawDatabase(
        context: Context,
        dbName: String,
        version: Int,
        identityHash: String,
        extraGameColumnsSql: String,
        extraGameIndexSql: String?,
        gameInsertSql: String,
        quickLaunchUri: String,
    ) {
        object : SQLiteOpenHelper(context, dbName, null, version) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `games` (" +
                        "`uri` TEXT NOT NULL, `root_uri` TEXT, `title` TEXT NOT NULL, " +
                        "`engine` TEXT NOT NULL, `launch_target` TEXT NOT NULL, `launch_file` TEXT, " +
                        "`cover_uri` TEXT, `cover_source` TEXT, `vndb_id` TEXT, `metadata_title` TEXT, " +
                        "`external_module_alias` TEXT, `detected_renpy_version` TEXT" +
                        extraGameColumnsSql +
                        ", `last_opened_at` INTEGER NOT NULL, `discovered_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, `sort_title` TEXT NOT NULL, `sort_tag` TEXT NOT NULL, " +
                        "PRIMARY KEY(`uri`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_root_uri` ON `games` (`root_uri`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_engine` ON `games` (`engine`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_sort_title` ON `games` (`sort_title`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_last_opened_at` ON `games` (`last_opened_at`)")
                extraGameIndexSql?.let { db.execSQL(it) }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scan_roots` (" +
                        "`uri` TEXT NOT NULL, `display_name` TEXT, `added_at` INTEGER NOT NULL, " +
                        "`scan_depth` INTEGER, `last_scanned_at` INTEGER NOT NULL, PRIMARY KEY(`uri`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `quick_launch` (" +
                        "`game_uri` TEXT NOT NULL, `slot_index` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`game_uri`), FOREIGN KEY(`game_uri`) REFERENCES `games`(`uri`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_quick_launch_slot_index` ON `quick_launch` (`slot_index`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `game_overrides` (" +
                        "`game_uri` TEXT NOT NULL, `kr_json` TEXT, `artemis_json` TEXT, `ons_json` TEXT, " +
                        "`tyrano_json` TEXT, `renpy_json` TEXT, `touchpad_json` TEXT, `updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`game_uri`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `engine_detection_cache` (" +
                        "`game_uri` TEXT NOT NULL, `engine` TEXT NOT NULL, `fingerprint_hash` TEXT, " +
                        "`artemis_version` TEXT, `artemis_success_version` TEXT, `renpy_version` TEXT, " +
                        "`rpgm_runtime` TEXT, `mkxpz_supported` INTEGER, `confidence` INTEGER NOT NULL, " +
                        "`reason` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`game_uri`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_engine_detection_cache_engine` " +
                        "ON `engine_detection_cache` (`engine`)",
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                db.execSQL(
                    "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$identityHash')",
                )
                db.execSQL(gameInsertSql)
                db.execSQL(
                    "INSERT INTO `quick_launch` (`game_uri`, `slot_index`, `updated_at`) VALUES ('$quickLaunchUri', 0, 500)",
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }.writableDatabase.close()
    }
}
