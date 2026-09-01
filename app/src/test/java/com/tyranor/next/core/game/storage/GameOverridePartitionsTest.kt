package com.tyranor.next.core.game.storage

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
        .put(PerGameSettingsStore.F_TY_SCOPED, false)
        .put(PerGameSettingsStore.F_RPG_MAKER_MOD_ENABLED, true)
        .put(PerGameSettingsStore.F_RENPY_VERSION, "8.5")
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
            assertEquals(blob.opt(key).toString(), GameOverridePartitions.assemble(row).opt(key).toString())
        }
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
        // 未识别键兜底归入 tyrano 分区
        assertEquals("keep-me", JSONObject(row.tyranoJson!!).getString("future_engine_key"))
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
        assertEquals(PerGameSettingsStore.F_ART_PATCH, GameOverridePartitions.KEY_ART_PATCH)
        assertEquals(PerGameSettingsStore.F_RPG_MAKER_MOD_ENABLED, GameOverridePartitions.KEY_RPG_MAKER_MOD_ENABLED)
        assertEquals(PerGameSettingsStore.F_TY_SCOPED, GameOverridePartitions.KEY_TY_SCOPED)
        assertEquals(PerGameSettingsStore.F_RENPY_VERSION, GameOverridePartitions.KEY_RENPY_VERSION)
        assertEquals(PerGameSettingsStore.ONS_KEY, GameOverridePartitions.ONS_OBJECT_KEY)
        // KR 分区必须覆盖 PerGameSettingsStore.KR_FIELDS 全部字段
        for (field in PerGameSettingsStore.KR_FIELDS) {
            assertTrue("KR 分区缺少字段 $field", field in GameOverridePartitions.KR_KEYS)
        }
    }
}
