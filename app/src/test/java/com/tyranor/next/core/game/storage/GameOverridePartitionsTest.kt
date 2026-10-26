package com.tyranor.next.core.game.storage

import com.tyranor.next.core.settings.GraphicsFamily
import com.tyranor.next.core.settings.GraphicsNode
import com.tyranor.next.core.settings.GraphicsOverride
import com.tyranor.next.core.settings.GraphicsSettingsCodec
import com.tyranor.next.core.settings.GraphicsSettings
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单游戏覆盖 blob ↔ DB 分区映射回归（迁移方案 4.4）：
 * 全字段无损往返、ONS 子对象不重嵌套、未识别键不丢失、键集与 PerGameSettingsStore 常量一致。
 */
class GameOverridePartitionsTest {

    private fun fullBlob(): JSONObject = JSONObject()
        .put(PerGameSettingsStore.F_ENGINE_VERSION, "1.3.9")
        .put(PerGameSettingsStore.F_RENDERER, "opengl")
        .put(PerGameSettingsStore.F_SKIP_STARTUP_DIALOGS, true)
        .put(PerGameSettingsStore.F_VCURSOR_SCALE, "80")
        .put(PerGameSettingsStore.F_ART_VERSION, "3")
        .put(PerGameSettingsStore.F_ART_ROTATE, true)
        .put(PerGameSettingsStore.F_ART_ANIME4K_ENABLED, true)
        .put(PerGameSettingsStore.F_ART_ANIME4K_MODE, "soft_m")
        .put(PerGameSettingsStore.F_TY_SCOPED, false)
        .put(PerGameSettingsStore.F_RPG_MAKER_MOD_ENABLED, true)
        .put(PerGameSettingsStore.F_RPG_LEGACY_RENDERER, true)
        .put(PerGameSettingsStore.F_RPG_SAVE_INTEROP, true)
        .put(PerGameSettingsStore.F_RPG_MV_VERSION, "v2")
        .put(PerGameSettingsStore.F_RPG_MZ_VERSION, "v2")
        .put(PerGameSettingsStore.F_INPUT_PAD_ENABLED, true)
        .put(PerGameSettingsStore.F_INPUT_GAMEPAD_ENABLED, false)
        .put(PerGameSettingsStore.F_INPUT_PROFILE_ID, "p1abc")
        .put(PerGameSettingsStore.F_RENPY_VERSION, "8.5")
        .put(PerGameSettingsStore.F_SIGLUS_LANGUAGE, "EN")
        .put(PerGameSettingsStore.F_FVP_NLS, "gbk")
        .put(PerGameSettingsStore.F_FVP_SYSTEM_FONT, true)
        .put(PerGameSettingsStore.F_FVP_TEXT_HIDPI, false)
        .put(PerGameSettingsStore.F_FVP_FONT, "/data/user/0/com.tyranor.next/files/fonts/a.ttf")
        .put(
            PerGameSettingsStore.ONS_KEY,
            JSONObject().put("scopedsavedir", true).put("encoding", "utf8"),
        )
        .put(GameOverridePartitions.TOUCH_PAD_CONFIG_KEY, JSONObject().put("buttons", "a,b"))
        .put(GameOverridePartitions.TOUCH_PAD_PRESETS_KEY, JSONObject().put("preset1", "{}"))
        .put("future_engine_key", "keep-me")

    @Test
    fun splitAssembleRoundTripIsLossless() {
        val blob = fullBlob()
        val row = GameOverridePartitions.split("/games/a", blob, 123L)

        assertEquals("/games/a", row.gameUri)
        assertEquals(123L, row.updatedAt)
        assertEquals(blob.length(), GameOverridePartitions.assemble(row).length())
        for (key in blob.keys()) {
            // opt 返回 Any?：显式安全调用（否则 Kotlin 报「nullable receiver 上只允许 ?. / !!.」）
            assertEquals(blob.opt(key)?.toString(), GameOverridePartitions.assemble(row).opt(key)?.toString())
        }
    }

    @Test
    fun artemisGraphicsOverridesUseArtemisPartitionAndSurviveRoundTrip() {
        val row = GameOverridePartitions.split("/games/artemis", fullBlob(), 1L)
        val artemis = JSONObject(row.artemisJson!!)
        val tyrano = JSONObject(row.tyranoJson!!)
        val assembled = GameOverridePartitions.assemble(row)
        val keys = listOf(
            PerGameSettingsStore.F_ART_ANIME4K_ENABLED,
            PerGameSettingsStore.F_ART_ANIME4K_MODE,
        )
        for (key in keys) {
            assertTrue(key in GameOverridePartitions.ARTEMIS_KEYS)
            assertTrue(artemis.has(key))
            assertTrue(!tyrano.has(key))
            assertEquals(artemis.get(key), assembled.get(key))
        }
        assertEquals(true, assembled.getBoolean(PerGameSettingsStore.F_ART_ANIME4K_ENABLED))
        assertEquals("soft_m", assembled.getString(PerGameSettingsStore.F_ART_ANIME4K_MODE))
    }

    @Test
    fun onsSubObjectIsNotReNested() {
        val ons = JSONObject().put("scopedsavedir", true)
        val blob = JSONObject().put(PerGameSettingsStore.ONS_KEY, ons)
        val row = GameOverridePartitions.split("/games/ons", blob, 1L)

        // onsJson 保存的是含 ons 键的包装分区，组装后必须还原为单层 ons 子对象
        assertEquals(ons.toString(), JSONObject(row.onsJson!!).getJSONObject("ons").toString())
        val assembled = GameOverridePartitions.assemble(row)
        assertTrue(assembled.has(PerGameSettingsStore.ONS_KEY))
        assertEquals(ons.toString(), assembled.getJSONObject(PerGameSettingsStore.ONS_KEY).toString())
    }

    @Test
    fun touchpadAndUnknownKeysArePartitioned() {
        val row = GameOverridePartitions.split("/games/a", fullBlob(), 1L)

        val touchpad = JSONObject(row.touchpadJson!!)
        assertTrue(touchpad.has(GameOverridePartitions.TOUCH_PAD_CONFIG_KEY))
        assertTrue(touchpad.has(GameOverridePartitions.TOUCH_PAD_PRESETS_KEY))
        // 未识别键兜底归入 tyrano 分区；RPG Maker 会话键显式归入 tyrano 分区
        val tyranoPartition = JSONObject(row.tyranoJson!!)
        assertEquals("keep-me", tyranoPartition.getString("future_engine_key"))
        assertTrue(tyranoPartition.has(PerGameSettingsStore.F_RPG_LEGACY_RENDERER))
        assertTrue(tyranoPartition.has(PerGameSettingsStore.F_RPG_SAVE_INTEROP))
        assertTrue(tyranoPartition.has(PerGameSettingsStore.F_RPG_MV_VERSION))
        assertTrue(tyranoPartition.has(PerGameSettingsStore.F_RPG_MZ_VERSION))
        assertTrue(tyranoPartition.has(PerGameSettingsStore.F_SIGLUS_LANGUAGE))
        assertTrue(tyranoPartition.has(PerGameSettingsStore.F_FVP_NLS))
        assertTrue(tyranoPartition.has(PerGameSettingsStore.F_FVP_SYSTEM_FONT))
        assertTrue(tyranoPartition.has(PerGameSettingsStore.F_FVP_TEXT_HIDPI))
        assertTrue(tyranoPartition.has(PerGameSettingsStore.F_FVP_FONT))
    }

    @Test
    fun emptyPartitionsStoreNullAndAssembleToEmptyObject() {
        val row = GameOverridePartitions.split("/games/empty", JSONObject(), 1L)
        assertNullPartitions(row)
        assertEquals(0, GameOverridePartitions.assemble(row).length())
    }

    private fun assertNullPartitions(row: GameOverrideEntity) {
        assertEquals(null, row.krJson)
        assertEquals(null, row.artemisJson)
        assertEquals(null, row.onsJson)
        assertEquals(null, row.tyranoJson)
        assertEquals(null, row.renpyJson)
        assertEquals(null, row.touchpadJson)
    }

    @Test
    fun inputLegacyMigratedFlagIsPartitionedIntoTyrano() {
        // 迁移标记是 PerGameSettingsStore 内部字段，同样必须显式建模：
        // 落到「未识别键兜底」路径虽然当下也能持久化，但未来收紧分区时会静默丢键，
        // 丢键会让迁移在每次启动重跑
        assertEquals(PerGameSettingsStore.F_LEGACY_MIGRATED, GameOverridePartitions.KEY_INPUT_LEGACY_MIGRATED)
        assertTrue(
            "迁移标记必须显式属于 tyrano 分区",
            GameOverridePartitions.KEY_INPUT_LEGACY_MIGRATED in GameOverridePartitions.TYRANO_KEYS,
        )

        val blob = JSONObject().put(PerGameSettingsStore.F_LEGACY_MIGRATED, true)
        val row = GameOverridePartitions.split("/games/migrated", blob, 1L)
        assertTrue(JSONObject(row.tyranoJson!!).has(PerGameSettingsStore.F_LEGACY_MIGRATED))
        assertEquals(blob.length(), GameOverridePartitions.assemble(row).length())
    }

    @Test
    fun winlatorModeIsPartitionedIntoTyrano() {
        // 运行模式没有独立分区，必须显式建模进 tyrano；
        // 只靠「未识别键兜底」会在未来收紧分区时静默丢键，把玩家的模式选择重置成推断值。
        assertEquals(PerGameSettingsStore.F_WINLATOR_MODE, GameOverridePartitions.KEY_WINLATOR_MODE)
        assertEquals(PerGameSettingsStore.F_WINLATOR_CONTAINER_ID, GameOverridePartitions.KEY_WINLATOR_CONTAINER_ID)
        assertEquals(PerGameSettingsStore.F_WINLATOR_CONTAINER_NAME, GameOverridePartitions.KEY_WINLATOR_CONTAINER_NAME)
        assertEquals(PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER, GameOverridePartitions.KEY_WINLATOR_GRAPHICS_DRIVER)
        assertEquals(PerGameSettingsStore.F_WINLATOR_DXWRAPPER, GameOverridePartitions.KEY_WINLATOR_DXWRAPPER)
        assertEquals(PerGameSettingsStore.F_WINLATOR_SCREEN_SIZE, GameOverridePartitions.KEY_WINLATOR_SCREEN_SIZE)
        assertEquals(PerGameSettingsStore.F_WINLATOR_LC_ALL, GameOverridePartitions.KEY_WINLATOR_LC_ALL)
        assertEquals(PerGameSettingsStore.F_WINLATOR_TZ, GameOverridePartitions.KEY_WINLATOR_TZ)
        assertEquals(PerGameSettingsStore.F_WINLATOR_BOX64_PRESET, GameOverridePartitions.KEY_WINLATOR_BOX64_PRESET)
        assertEquals(PerGameSettingsStore.F_WINLATOR_SAVE, GameOverridePartitions.KEY_WINLATOR_SAVE)
        assertTrue(
            "全部 Winlator 覆盖键必须显式属于 tyrano 分区",
            GameOverridePartitions.TYRANO_KEYS.containsAll(PerGameSettingsStore.CURRENT_OVERRIDE_FIELDS) &&
                GameOverridePartitions.KEY_WINLATOR_CONTAINER_ID in GameOverridePartitions.TYRANO_KEYS &&
                GameOverridePartitions.KEY_WINLATOR_CONTAINER_NAME in GameOverridePartitions.TYRANO_KEYS &&
                GameOverridePartitions.KEY_WINLATOR_SAVE in GameOverridePartitions.TYRANO_KEYS,
        )
        assertTrue(
            "运行模式必须显式属于 tyrano 分区",
            GameOverridePartitions.KEY_WINLATOR_MODE in GameOverridePartitions.TYRANO_KEYS,
        )

        val blob = JSONObject().put(PerGameSettingsStore.F_WINLATOR_MODE, PerGameSettingsStore.WINLATOR_MODE_CONTAINER)
        val row = GameOverridePartitions.split("/games/winlator", blob, 1L)
        val tyrano = JSONObject(row.tyranoJson!!)
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CONTAINER, tyrano.optString(PerGameSettingsStore.F_WINLATOR_MODE))
        val restored = GameOverridePartitions.assemble(row)
        assertEquals(PerGameSettingsStore.WINLATOR_MODE_CONTAINER, restored.optString(PerGameSettingsStore.F_WINLATOR_MODE))
        assertEquals(blob.length(), restored.length())
    }

    @Test
    fun winlatorOverridesRoundTripWithTypesIntact() {
        // 十个逐游戏 Winlator 键一起走 split → assemble → toWinlatorOverride：
        // 既覆盖「全部键都在 tyrano 分区」，也锁住 save 的 Boolean 类型不退化。
        val blob = JSONObject().apply {
            put(PerGameSettingsStore.F_WINLATOR_MODE, PerGameSettingsStore.WINLATOR_MODE_CUSTOM)
            put(PerGameSettingsStore.F_WINLATOR_CONTAINER_ID, "5")
            put(PerGameSettingsStore.F_WINLATOR_CONTAINER_NAME, "常規容器")
            put(PerGameSettingsStore.F_WINLATOR_GRAPHICS_DRIVER, "vulkan")
            put(PerGameSettingsStore.F_WINLATOR_DXWRAPPER, "wined3d")
            put(PerGameSettingsStore.F_WINLATOR_SCREEN_SIZE, "1280x720")
            put(PerGameSettingsStore.F_WINLATOR_LC_ALL, "ja_JP.UTF-8")
            put(PerGameSettingsStore.F_WINLATOR_TZ, "Asia/Tokyo")
            put(PerGameSettingsStore.F_WINLATOR_BOX64_PRESET, "PERFORMANCE")
            put(PerGameSettingsStore.F_WINLATOR_SAVE, true)
        }

        val row = GameOverridePartitions.split("/games/winlator-roundtrip", blob, 1L)
        val restored = GameOverridePartitions.assemble(row)

        assertEquals(blob.length(), restored.length())
        for (key in blob.keys()) {
            assertEquals("键 $key", blob.get(key), restored.get(key))
        }

        val override = PerGameSettingsStore.toWinlatorOverride(restored)!!
        assertEquals("5", override.containerId)
        assertEquals("常規容器", override.containerName)
        assertEquals("vulkan", override.graphicsDriver)
        assertEquals("wined3d", override.dxwrapper)
        assertEquals("1280x720", override.screenSize)
        assertEquals("ja_JP.UTF-8", override.lcAll)
        assertEquals("Asia/Tokyo", override.tz)
        assertEquals("PERFORMANCE", override.box64Preset)
        assertEquals(true, override.save)
    }

    @Test
    fun partitionKeysMatchPerGameSettingsStoreConstants() {
        // 字面量键集与 settings 层常量的契约约束（避免漂移）
        assertEquals(PerGameSettingsStore.F_ENGINE_VERSION, GameOverridePartitions.KEY_ENGINE_VERSION)
        assertEquals(PerGameSettingsStore.F_ENGINE_KERNEL, GameOverridePartitions.KEY_ENGINE_KERNEL)
        assertEquals(PerGameSettingsStore.F_SCOPED_SAVE_DIR, GameOverridePartitions.KEY_SCOPED_SAVE_DIR)
        assertEquals(PerGameSettingsStore.F_DEFAULT_FONT, GameOverridePartitions.KEY_DEFAULT_FONT)
        assertEquals(PerGameSettingsStore.F_FORCE_DEFAULT_FONT, GameOverridePartitions.KEY_FORCE_DEFAULT_FONT)
        assertEquals(PerGameSettingsStore.F_PATCH_OVERLAY_MODE, GameOverridePartitions.KEY_PATCH_OVERLAY_MODE)
        assertEquals(PerGameSettingsStore.F_SKIP_STARTUP_DIALOGS, GameOverridePartitions.KEY_SKIP_STARTUP_DIALOGS)
        assertEquals(PerGameSettingsStore.F_RENDERER, GameOverridePartitions.KEY_RENDERER)
        assertEquals(PerGameSettingsStore.F_SOFTWARE_DRAW_THREAD, GameOverridePartitions.KEY_SOFTWARE_DRAW_THREAD)
        assertEquals(PerGameSettingsStore.F_SOFTWARE_COMPRESS_TEX, GameOverridePartitions.KEY_SOFTWARE_COMPRESS_TEX)
        assertEquals(PerGameSettingsStore.F_OGL_COMPRESS_TEX, GameOverridePartitions.KEY_OGL_COMPRESS_TEX)
        assertEquals(PerGameSettingsStore.F_MEM_USAGE, GameOverridePartitions.KEY_MEM_USAGE)
        assertEquals(PerGameSettingsStore.F_OGL_MAX_TEXSIZE, GameOverridePartitions.KEY_OGL_MAX_TEXSIZE)
        assertEquals(PerGameSettingsStore.F_OGL_ACCURATE_RENDER, GameOverridePartitions.KEY_OGL_ACCURATE_RENDER)
        assertEquals(PerGameSettingsStore.F_FPS_LIMIT, GameOverridePartitions.KEY_FPS_LIMIT)
        assertEquals(PerGameSettingsStore.F_VCURSOR_SCALE, GameOverridePartitions.KEY_VCURSOR_SCALE)
        assertEquals(PerGameSettingsStore.F_MENU_HANDLER_OPA, GameOverridePartitions.KEY_MENU_HANDLER_OPA)
        assertEquals(PerGameSettingsStore.F_ART_VERSION, GameOverridePartitions.KEY_ART_VERSION)
        assertEquals(PerGameSettingsStore.F_ART_ROTATE, GameOverridePartitions.KEY_ART_ROTATE)
        assertEquals(PerGameSettingsStore.F_ART_ANIME4K_ENABLED, GameOverridePartitions.KEY_ART_ANIME4K_ENABLED)
        assertEquals(PerGameSettingsStore.F_ART_ANIME4K_MODE, GameOverridePartitions.KEY_ART_ANIME4K_MODE)
        assertEquals(PerGameSettingsStore.F_ART_PATCH, GameOverridePartitions.KEY_ART_PATCH)
        assertEquals(PerGameSettingsStore.F_RPG_MAKER_MOD_ENABLED, GameOverridePartitions.KEY_RPG_MAKER_MOD_ENABLED)
        assertEquals(PerGameSettingsStore.F_RPG_LEGACY_RENDERER, GameOverridePartitions.KEY_RPG_LEGACY_RENDERER)
        assertEquals(PerGameSettingsStore.F_RPG_SAVE_INTEROP, GameOverridePartitions.KEY_RPG_SAVE_INTEROP)
        assertEquals(PerGameSettingsStore.F_RPG_MV_VERSION, GameOverridePartitions.KEY_RPG_MV_VERSION)
        assertEquals(PerGameSettingsStore.F_RPG_MZ_VERSION, GameOverridePartitions.KEY_RPG_MZ_VERSION)
        // 输入重映射键：app 分区常量 ↔ settings 常量 ↔ engine InputConfigStore 常量三处锚定
        assertEquals(PerGameSettingsStore.F_INPUT_PAD_ENABLED, GameOverridePartitions.KEY_INPUT_PAD_ENABLED)
        assertEquals(PerGameSettingsStore.F_INPUT_GAMEPAD_ENABLED, GameOverridePartitions.KEY_INPUT_GAMEPAD_ENABLED)
        assertEquals(PerGameSettingsStore.F_INPUT_PROFILE_ID, GameOverridePartitions.KEY_INPUT_PROFILE_ID)
        assertEquals(com.core.input.InputConfigStore.KEY_PAD_ENABLED, GameOverridePartitions.KEY_INPUT_PAD_ENABLED)
        assertEquals(com.core.input.InputConfigStore.KEY_GAMEPAD_ENABLED, GameOverridePartitions.KEY_INPUT_GAMEPAD_ENABLED)
        assertEquals(com.core.input.InputConfigStore.KEY_PROFILE_ID, GameOverridePartitions.KEY_INPUT_PROFILE_ID)
        // 第四处：全局键（写路径 EngineSettingsStore）与 engine 读取常量必须同名，
        // 漂移会导致「设置页关了开关但引擎仍按默认启用」
        assertEquals(EngineSettingsStore.KEY_INPUT_PAD_ENABLED, com.core.input.InputConfigStore.KEY_PAD_ENABLED)
        assertEquals(EngineSettingsStore.KEY_INPUT_GAMEPAD_ENABLED, com.core.input.InputConfigStore.KEY_GAMEPAD_ENABLED)
        assertEquals(EngineSettingsStore.KEY_INPUT_PROFILE_ID, com.core.input.InputConfigStore.KEY_PROFILE_ID)
        assertEquals(PerGameSettingsStore.F_TY_SCOPED, GameOverridePartitions.KEY_TY_SCOPED)
        assertEquals(PerGameSettingsStore.F_RENPY_VERSION, GameOverridePartitions.KEY_RENPY_VERSION)
        assertEquals(PerGameSettingsStore.F_SIGLUS_LANGUAGE, GameOverridePartitions.KEY_SIGLUS_LANGUAGE)
        assertEquals(PerGameSettingsStore.F_FVP_NLS, GameOverridePartitions.KEY_FVP_NLS)
        assertEquals(PerGameSettingsStore.F_FVP_SYSTEM_FONT, GameOverridePartitions.KEY_FVP_SYSTEM_FONT)
        assertEquals(PerGameSettingsStore.F_FVP_TEXT_HIDPI, GameOverridePartitions.KEY_FVP_TEXT_HIDPI)
        assertEquals(PerGameSettingsStore.F_FVP_FONT, GameOverridePartitions.KEY_FVP_FONT)
        assertEquals(PerGameSettingsStore.ONS_KEY, GameOverridePartitions.ONS_OBJECT_KEY)
        // KR 分区必须覆盖 PerGameSettingsStore.KR_FIELDS 全部字段
        for (field in PerGameSettingsStore.KR_FIELDS) {
            assertTrue("KR 分区缺少字段 $field", field in GameOverridePartitions.KR_KEYS)
        }
    }

    @Test
    fun rpgMakerRgssKeysArePartitionedIntoTyrano() {
        val pairs = listOf(
            GameOverridePartitions.KEY_RPG_USE_RUBY18 to PerGameSettingsStore.F_RPG_USE_RUBY18,
            GameOverridePartitions.KEY_RPG_DEBUG to PerGameSettingsStore.F_RPG_DEBUG,
            GameOverridePartitions.KEY_RPG_SMOOTH_SCALING to PerGameSettingsStore.F_RPG_SMOOTH_SCALING,
            GameOverridePartitions.KEY_RPG_VSYNC to PerGameSettingsStore.F_RPG_VSYNC,
            GameOverridePartitions.KEY_RPG_FRAME_SKIP to PerGameSettingsStore.F_RPG_FRAME_SKIP,
            GameOverridePartitions.KEY_RPG_SOLID_FONTS to PerGameSettingsStore.F_RPG_SOLID_FONTS,
            GameOverridePartitions.KEY_RPG_PATH_CACHE to PerGameSettingsStore.F_RPG_PATH_CACHE,
            GameOverridePartitions.KEY_RPG_PREBUILT_PATH_CACHE to PerGameSettingsStore.F_RPG_PREBUILT_PATH_CACHE,
            GameOverridePartitions.KEY_RPG_FAST_PATH_ENUM to PerGameSettingsStore.F_RPG_FAST_PATH_ENUM,
            GameOverridePartitions.KEY_RPG_COPY_TEXT to PerGameSettingsStore.F_RPG_COPY_TEXT,
            GameOverridePartitions.KEY_RPG_CHEATS to PerGameSettingsStore.F_RPG_CHEATS,
            GameOverridePartitions.KEY_RPG_USE_CJK_FONT to PerGameSettingsStore.F_RPG_USE_CJK_FONT,
            GameOverridePartitions.KEY_RPG_ENABLE_POSTLOAD_SCRIPTS to PerGameSettingsStore.F_RPG_ENABLE_POSTLOAD_SCRIPTS,
            GameOverridePartitions.KEY_RPG_CUSTOM_FONT to PerGameSettingsStore.F_RPG_CUSTOM_FONT,
            GameOverridePartitions.KEY_RPG_VERTICAL_SCREEN_ALIGN to PerGameSettingsStore.F_RPG_VERTICAL_SCREEN_ALIGN,
            GameOverridePartitions.KEY_RPG_WINDOW_SIZE to PerGameSettingsStore.F_RPG_WINDOW_SIZE,
            GameOverridePartitions.KEY_RPG_SPEED_UP to PerGameSettingsStore.F_RPG_SPEED_UP,
            GameOverridePartitions.KEY_RPG_FONT_SCALE to PerGameSettingsStore.F_RPG_FONT_SCALE,
        )
        val blob = JSONObject()
        pairs.forEach { (partitionKey, perGameKey) ->
            assertEquals(perGameKey, partitionKey)
            assertTrue("RPG 分区缺少字段 $perGameKey", perGameKey in GameOverridePartitions.TYRANO_KEYS)
            blob.put(perGameKey, true)
        }

        val row = GameOverridePartitions.split("/games/rpgm", blob, 1L)
        val tyranoPartition = JSONObject(row.tyranoJson!!)
        pairs.forEach { (_, perGameKey) ->
            assertTrue(tyranoPartition.has(perGameKey))
        }
        assertEquals(blob.length(), GameOverridePartitions.assemble(row).length())
    }

    @Test
    fun system35KeysArePartitionedIntoTyrano() {
        val pairs = listOf(
            GameOverridePartitions.KEY_XSYS35_SCOPED_SAVE_DIR to PerGameSettingsStore.F_XSYS35_SCOPED_SAVE_DIR,
            GameOverridePartitions.KEY_XSYS35_RENDERER to PerGameSettingsStore.F_XSYS35_RENDERER,
            GameOverridePartitions.KEY_XSYS35_INTEGER_SCALE to PerGameSettingsStore.F_XSYS35_INTEGER_SCALE,
            GameOverridePartitions.KEY_XSYS35_MUTE_UNFOCUS to PerGameSettingsStore.F_XSYS35_MUTE_UNFOCUS,
        )
        pairs.forEach { (partitionKey, perGameKey) ->
            assertEquals(perGameKey, partitionKey)
            assertTrue("System35 分区缺少字段 $perGameKey", perGameKey in GameOverridePartitions.TYRANO_KEYS)
        }

        val blob = JSONObject()
            .put(PerGameSettingsStore.F_XSYS35_SCOPED_SAVE_DIR, false)
            .put(PerGameSettingsStore.F_XSYS35_RENDERER, "software")
            .put(PerGameSettingsStore.F_XSYS35_INTEGER_SCALE, true)
            .put(PerGameSettingsStore.F_XSYS35_MUTE_UNFOCUS, true)

        val row = GameOverridePartitions.split("/games/xsys35", blob, 1L)
        val tyranoPartition = JSONObject(row.tyranoJson!!)
        for (key in blob.keys()) {
            assertTrue("System35 键 $key 未落入 tyrano 分区", tyranoPartition.has(key))
            assertEquals(blob.opt(key)?.toString(), GameOverridePartitions.assemble(row).opt(key)?.toString())
        }
        assertEquals(blob.length(), GameOverridePartitions.assemble(row).length())
    }

    @Test
    fun system4KeysArePartitionedIntoTyrano() {
        val pairs = listOf(
            GameOverridePartitions.KEY_XSYS4_SCOPED_SAVE_DIR to PerGameSettingsStore.F_XSYS4_SCOPED_SAVE_DIR,
            GameOverridePartitions.KEY_XSYS4_ENCODING to PerGameSettingsStore.F_XSYS4_ENCODING,
            GameOverridePartitions.KEY_XSYS4_FONT_GOTHIC_PATH to PerGameSettingsStore.F_XSYS4_FONT_GOTHIC_PATH,
            GameOverridePartitions.KEY_XSYS4_FONT_MINCHO_PATH to PerGameSettingsStore.F_XSYS4_FONT_MINCHO_PATH,
            GameOverridePartitions.KEY_XSYS4_JOYPAD to PerGameSettingsStore.F_XSYS4_JOYPAD,
            GameOverridePartitions.KEY_XSYS4_MSGSKIP_DELAY to PerGameSettingsStore.F_XSYS4_MSGSKIP_DELAY,
        )
        pairs.forEach { (partitionKey, perGameKey) ->
            assertEquals(perGameKey, partitionKey)
            assertTrue("System4 分区缺少字段 $perGameKey", perGameKey in GameOverridePartitions.TYRANO_KEYS)
        }

        // 字面量锚定：与全局设置键、引擎侧契约的键名一致（三处同值，改名需同步）
        assertEquals("xsys4_scoped_save_dir", EngineSettingsStore.KEY_XSYS4_SCOPED_SAVE_DIR)
        assertEquals("xsys4_encoding", EngineSettingsStore.KEY_XSYS4_ENCODING)
        assertEquals("xsys4_font_gothic_path", EngineSettingsStore.KEY_XSYS4_FONT_GOTHIC_PATH)
        assertEquals("xsys4_font_mincho_path", EngineSettingsStore.KEY_XSYS4_FONT_MINCHO_PATH)
        assertEquals("xsys4_joypad", EngineSettingsStore.KEY_XSYS4_JOYPAD)
        assertEquals("xsys4_msgskip_delay", EngineSettingsStore.KEY_XSYS4_MSGSKIP_DELAY)

        val blob = JSONObject()
            .put(PerGameSettingsStore.F_XSYS4_SCOPED_SAVE_DIR, false)
            .put(PerGameSettingsStore.F_XSYS4_ENCODING, "gbk")
            .put(PerGameSettingsStore.F_XSYS4_FONT_GOTHIC_PATH, "")
            .put(PerGameSettingsStore.F_XSYS4_FONT_MINCHO_PATH, "/data/user/0/app/files/fonts/a.ttf")
            .put(PerGameSettingsStore.F_XSYS4_JOYPAD, "on")
            .put(PerGameSettingsStore.F_XSYS4_MSGSKIP_DELAY, "200")

        val row = GameOverridePartitions.split("/games/xsys4", blob, 1L)
        val tyranoPartition = JSONObject(row.tyranoJson!!)
        for (key in blob.keys()) {
            assertTrue("System4 键 $key 未落入 tyrano 分区", tyranoPartition.has(key))
            assertEquals(blob.opt(key)?.toString(), GameOverridePartitions.assemble(row).opt(key)?.toString())
        }
        assertEquals(blob.length(), GameOverridePartitions.assemble(row).length())
    }

    @Test
    fun renPyModuleKeysArePartitionedIntoRenpy() {
        val pairs = listOf(
            GameOverridePartitions.KEY_RENPY_CHEATS to PerGameSettingsStore.F_RENPY_CHEATS,
            GameOverridePartitions.KEY_RENPY_HW_VIDEO to PerGameSettingsStore.F_RENPY_HW_VIDEO,
            GameOverridePartitions.KEY_RENPY_AUTOSAVE to PerGameSettingsStore.F_RENPY_AUTOSAVE,
            GameOverridePartitions.KEY_RENPY_PHONE_SMALL_VARIANT to PerGameSettingsStore.F_RENPY_PHONE_SMALL_VARIANT,
            GameOverridePartitions.KEY_RENPY_VSYNC to PerGameSettingsStore.F_RENPY_VSYNC,
            GameOverridePartitions.KEY_RENPY_LESS_MEMORY to PerGameSettingsStore.F_RENPY_LESS_MEMORY,
            GameOverridePartitions.KEY_RENPY_LESS_UPDATES to PerGameSettingsStore.F_RENPY_LESS_UPDATES,
            GameOverridePartitions.KEY_RENPY_DONT_USE_GL2 to PerGameSettingsStore.F_RENPY_DONT_USE_GL2,
            GameOverridePartitions.KEY_RENPY_RECOMPILE to PerGameSettingsStore.F_RENPY_RECOMPILE,
        )
        val blob = JSONObject()
        pairs.forEach { (partitionKey, perGameKey) ->
            assertEquals(perGameKey, partitionKey)
            assertTrue("Ren'Py 分区缺少字段 $perGameKey", perGameKey in GameOverridePartitions.RENPY_KEYS)
            blob.put(perGameKey, true)
        }

        val row = GameOverridePartitions.split("/games/renpy", blob, 1L)
        val renpyPartition = JSONObject(row.renpyJson!!)
        pairs.forEach { (_, perGameKey) ->
            assertTrue(renpyPartition.has(perGameKey))
        }
        assertEquals(blob.length(), GameOverridePartitions.assemble(row).length())
    }
    @Test
    fun graphicsFamilyMarkersAndOrderedListsSurviveOwnedPartitionsWithoutLosingLegacyKeys() {
        val blob = JSONObject().put("anime4k_mode", "soft_l").put("art_graphics_monitor", true)
            .put("future_setting", "keep")
        for (family in GraphicsFamily.entries) {
            blob.put(family.overrideKey, GraphicsSettingsCodec.encode(GraphicsOverride(
                chain = listOf(GraphicsNode("deblur", false), GraphicsNode("s"), GraphicsNode("s")),
            )))
        }
        val row = GameOverridePartitions.split("game", blob, 1L)
        assertTrue(JSONObject(row.krJson!!).has(GraphicsFamily.KR.overrideKey))
        assertTrue(JSONObject(row.artemisJson!!).has(GraphicsFamily.ARTEMIS.overrideKey))
        assertTrue(JSONObject(row.tyranoJson!!).has(GraphicsFamily.SIGLUS.overrideKey))
        val restored = GameOverridePartitions.assemble(row)
        assertEquals("soft_l", restored.getString("anime4k_mode"))
        assertTrue(restored.getBoolean("art_graphics_monitor"))
        assertEquals("keep", restored.getString("future_setting"))
        for (family in GraphicsFamily.entries) {
            assertEquals(listOf(GraphicsNode("deblur", false), GraphicsNode("s"), GraphicsNode("s")),
                GraphicsSettingsCodec.readOverride(restored, family).chain)
            restored.put(family.overrideKey, GraphicsSettingsCodec.encode(GraphicsOverride()))
        }
        val inherited = GameOverridePartitions.assemble(GameOverridePartitions.split("game", restored, 2L))
        for (family in GraphicsFamily.entries) assertEquals(GraphicsOverride(), GraphicsSettingsCodec.readOverride(inherited, family))
        assertEquals(GraphicsFamily.KR.overrideKey, GameOverridePartitions.KEY_KR_GRAPHICS)
        assertEquals(GraphicsFamily.ARTEMIS.overrideKey, GameOverridePartitions.KEY_ART_GRAPHICS)
        assertEquals(GraphicsFamily.SIGLUS.overrideKey, GameOverridePartitions.KEY_SIGLUS_GRAPHICS)
    }

    @Test fun legacyKrModeHasAnOwnedPartitionAndFamilyMarkerFreezesInheritance() {
        assertEquals(PerGameSettingsStore.F_ANIME4K_MODE, GameOverridePartitions.KEY_KR_LEGACY_ANIME4K_MODE)
        assertTrue(PerGameSettingsStore.F_ANIME4K_MODE in GameOverridePartitions.KR_KEYS)
        val before = JSONObject().put(PerGameSettingsStore.F_ANIME4K_MODE, "soft_l")
            .put(PerGameSettingsStore.F_RPG_MV_VERSION, "v2")
        val legacyRow = GameOverridePartitions.split("/games/legacy-kr", before, 1L)
        assertEquals("soft_l", JSONObject(legacyRow.krJson!!).getString(PerGameSettingsStore.F_ANIME4K_MODE))
        assertTrue(!JSONObject(legacyRow.tyranoJson!!).has(PerGameSettingsStore.F_ANIME4K_MODE))
        val loaded = GameOverridePartitions.assemble(legacyRow)
        assertEquals(GraphicsOverride(enabled = true, chain = listOf(GraphicsNode("soft_l"))),
            GraphicsSettingsCodec.readOverride(loaded, GraphicsFamily.KR))

        // Saving the family marker means absent fields now inherit; the legacy value is retained for provenance.
        loaded.put(GraphicsFamily.KR.overrideKey, GraphicsSettingsCodec.encode(GraphicsOverride()))
        val saved = GameOverridePartitions.assemble(GameOverridePartitions.split("/games/legacy-kr", loaded, 2L))
        assertEquals("soft_l", saved.getString(PerGameSettingsStore.F_ANIME4K_MODE))
        assertEquals(GraphicsOverride(), GraphicsSettingsCodec.readOverride(saved, GraphicsFamily.KR))
        val global = GraphicsSettings(false, listOf(GraphicsNode("m")), false)
        assertEquals(global, GraphicsSettingsCodec.resolve(global, GraphicsSettingsCodec.readOverride(saved, GraphicsFamily.KR)))
        saved.put(PerGameSettingsStore.F_ANIME4K_MODE, "l")
        assertEquals(global, GraphicsSettingsCodec.resolve(global, GraphicsSettingsCodec.readOverride(saved, GraphicsFamily.KR)))
        assertEquals("v2", saved.getString(PerGameSettingsStore.F_RPG_MV_VERSION))
    }


    /** GL/GDI 预设经存储分区拆装后仍能还原成同一单游戏覆盖值。 */
    @Test
    fun winlatorRendererPresetsSurvivePartitionAndOverrideRoundTrip() {
        for (preset in listOf("wined3d_gl", "wined3d_gdi")) {
            val blob = JSONObject().put(PerGameSettingsStore.F_WINLATOR_DXWRAPPER, preset)
            val row = GameOverridePartitions.split("/games/winlator", blob, 1L)
            val restored = GameOverridePartitions.assemble(row)

            assertEquals(1, restored.length())
            assertEquals(preset, restored.getString(PerGameSettingsStore.F_WINLATOR_DXWRAPPER))
            assertEquals(preset, PerGameSettingsStore.toWinlatorOverride(restored)?.dxwrapper)
        }
    }
}
