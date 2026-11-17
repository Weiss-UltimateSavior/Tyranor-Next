package com.tyranor.next.core.cover

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyranor.next.core.auth.BangumiAuthError
import com.tyranor.next.core.auth.BangumiAuthService
import com.tyranor.next.core.auth.HikarinagiAuthError
import com.tyranor.next.core.auth.HikarinagiAuthService
import com.tyranor.next.core.auth.NextMoeAuthError
import com.tyranor.next.core.auth.NextMoeAuthService
import com.tyranor.next.core.auth.NextMoeLoopbackPageText
import com.tyranor.next.core.auth.SecureAuthStore
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.ui.cover.CoverScraperSettingsActivity
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机 AndroidKeyStore 与 ContentResolver 存储回归（不依赖 Compose Activity）：
 * 1. `VndbCoverService.saveCustomCover` 在真实 `Uri.fromFile` + `ContentResolver` 下同名覆盖并清理旧图；
 * 2. `SecureAuthStore` 使用真实 `AndroidKeyStore` 加解密，且密文损坏时返回空串但保留密文。
 *
 * 包归属注记（ARCH-9）：本类挂在 `core/cover` 包下、部分用例经
 * `ui.cover.CoverScraperSettingsActivity` 做 Activity 级 UI 验收（存储加密与令牌 UI 流），
 * 命名/包归属按「经封面设置页 UI 验证存储与授权加密」的归位理由保留——被测能力主体
 * （存储加密、封面缓存、四源 store）属 core/cover 域，UI 仅作为真机验收驱动入口；
 * 如后续拆分，可迁 `androidTest/.../ui/cover/` 并同步设备测试 README 索引。
 */
@RunWith(AndroidJUnit4::class)
class DeviceStorageAndAuthCryptoTest {

    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        SecureAuthStore.testCipherDelegate = null
        SecureAuthStore.clearKeyCacheForTest()
    }

    @After
    fun tearDown() {
        try {
            GameLibraryFacade.skipPersistForTest = false
            GameLibraryFacade.seedGamesForTest(null)
            tempFiles.forEach { it.delete() }
            tempFiles.clear()
        } finally {
            SecureAuthStore.testCipherDelegate = null
            SecureAuthStore.clearKeyCacheForTest()
        }
    }

    @Test
    fun vndbSaveCustomCoverWithRealAndroidUriReplacesSameExtCoverSafely() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source1 = File(context.cacheDir, "pick_1.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
            tempFiles += this
        }
        val source2 = File(context.cacheDir, "pick_2.jpg").apply {
            writeBytes(byteArrayOf(5, 6, 7, 8))
            tempFiles += this
        }
        val baseGame = ScanGame(
            title = "DeviceCustomCover",
            uri = "content://games/device_custom_cover",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
        )
        GameLibraryFacade.skipPersistForTest = true
        GameLibraryFacade.seedGamesForTest(listOf(baseGame))

        val saved1 = VndbCoverService.saveCustomCover(context, baseGame, Uri.fromFile(source1))
        assertNotNull(saved1)
        val file1 = File(Uri.parse(saved1!!.coverUri!!).path!!)
        tempFiles += file1
        assertTrue(file1.exists())
        assertTrue(file1.parentFile == persistentCoverDir(context))

        val saved2 = VndbCoverService.saveCustomCover(context, saved1, Uri.fromFile(source2))
        assertNotNull(saved2)
        val file2 = File(Uri.parse(saved2!!.coverUri!!).path!!)
        tempFiles += file2
        assertNotEquals(saved1.coverUri, saved2.coverUri)
        assertEquals(AppSettingsStore.COVER_SOURCE_CUSTOM, saved2.coverSource)
        assertTrue(file2.exists())
        assertFalse(file1.exists())
    }

    @Test
    fun secureAuthStoreEncryptsWithAndroidKeyStoreAndPreservesCorruptedCiphertext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testPrefs = "test_secure_auth_device"
        val testAlias = "test_tyranor_device_key"
        val prefs = SecureAuthStore.prefs(context, testPrefs)
        prefs.edit().clear().commit()
        try {
            assertTrue(SecureAuthStore.encryptField(context, testPrefs, testAlias, "access_token", "real_secret_token"))
            val rawCipher = prefs.getString("access_token", null)
            assertNotNull(rawCipher)
            assertNotEquals("real_secret_token", rawCipher)
            assertEquals("real_secret_token", SecureAuthStore.decryptField(context, testPrefs, testAlias, "access_token"))

            prefs.edit().putString("access_token", "corrupted_not_valid_gcm").commit()
            assertEquals("", SecureAuthStore.decryptField(context, testPrefs, testAlias, "access_token"))
            assertEquals("corrupted_not_valid_gcm", prefs.getString("access_token", null))
        } finally {
            prefs.edit().clear().commit()
            SecureAuthStore.clearKeyCacheForTest()
            runCatching {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (keyStore.containsAlias(testAlias)) keyStore.deleteEntry(testAlias)
            }
        }
    }

    @Test
    fun vndbCustomCoverExtensionMapsMimeTypesAndSaveCustomCoverHandlesFileFallback() {
        assertEquals("png", VndbCoverService.customCoverExtension("image/png"))
        assertEquals("webp", VndbCoverService.customCoverExtension("IMAGE/WEBP"))
        assertEquals("gif", VndbCoverService.customCoverExtension("image/gif"))
        assertEquals("jpg", VndbCoverService.customCoverExtension("image/jpeg"))
        assertEquals("jpg", VndbCoverService.customCoverExtension(null))

        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceFile = File(baseContext.cacheDir, "mime_source_bin").apply {
            writeBytes(byteArrayOf(10, 20, 30, 40))
            tempFiles += this
        }
        val baseGame = ScanGame(
            title = "MimeCustomCover",
            uri = "content://games/mime_custom_cover",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
        )
        GameLibraryFacade.skipPersistForTest = true
        GameLibraryFacade.seedGamesForTest(listOf(baseGame))

        val saved = VndbCoverService.saveCustomCover(baseContext, baseGame, Uri.fromFile(sourceFile))
        assertNotNull(saved)
        val savedFile = File(Uri.parse(saved!!.coverUri!!).path!!)
        tempFiles += savedFile
        assertTrue(savedFile.name.endsWith(".jpg"))
        assertTrue(savedFile.exists())
    }

    @Test
    fun oauthStartAuthorizationReturnsClientIdMissingWithoutActivityLaunch() {
        val prevBgm = BangumiAuthService.testClientCredentials
        val prevHika = HikarinagiAuthService.testClientId
        val prevNm = NextMoeAuthService.testClientId
        try {
            BangumiAuthService.testClientCredentials = "" to ""
            HikarinagiAuthService.testClientId = ""
            NextMoeAuthService.testClientId = ""
            ActivityScenario.launch(CoverScraperSettingsActivity::class.java).use { scenario ->
                lateinit var hostActivity: CoverScraperSettingsActivity
                scenario.onActivity { hostActivity = it }
                runBlocking(Dispatchers.Main) {
                    assertEquals(
                        BangumiAuthError.CLIENT_ID_MISSING,
                        BangumiAuthService.startAuthorization(hostActivity, Intent()),
                    )
                    assertEquals(
                        HikarinagiAuthError.CLIENT_ID_MISSING,
                        HikarinagiAuthService.startAuthorization(hostActivity, Intent()),
                    )
                    assertEquals(
                        NextMoeAuthError.CLIENT_ID_MISSING,
                        NextMoeAuthService.startAuthorization(
                            hostActivity,
                            NextMoeLoopbackPageText("T", "S", "C", "E"),
                        ),
                    )
                }
            }
        } finally {
            BangumiAuthService.testClientCredentials = prevBgm
            HikarinagiAuthService.testClientId = prevHika
            NextMoeAuthService.testClientId = prevNm
        }
    }
}
