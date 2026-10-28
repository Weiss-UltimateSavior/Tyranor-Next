package com.tyranor.next.core.engine.external

import com.tyranor.next.core.settings.EngineSettingsStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Winlator 外置启动下发参数映射：空值不下发、box64Preset 走 overrides JSON、save 仅在开启时下发。
 */
class WinlatorContractTest {

    /** 未配置参数时保持空载荷，由接收端使用容器配置。 */
    @Test
    fun emptyOptionsProduceNoExtras() {
        assertTrue(WinlatorContract.extras(WinlatorContract.LaunchOptions()).isEmpty())
    }

    /** 空白字符串、零容器 ID 与关闭的 save 均不形成显式覆盖。 */
    @Test
    fun blankStringsAndZeroIdAreSkipped() {
        val extras = WinlatorContract.extras(
            WinlatorContract.LaunchOptions(
                containerId = 0,
                containerName = "   ",
                graphicsDriver = "",
                dxwrapper = " ",
                screenSize = "",
                lcAll = "",
                tz = "",
                box64Preset = "",
                save = false,
            ),
        )

        assertTrue(extras.isEmpty())
    }

    /** 已配置值映射到既有协议键，保留容器 ID 的整数类型。 */
    @Test
    fun valuesAreMappedToProtocolKeys() {
        val extras = WinlatorContract.extras(
            WinlatorContract.LaunchOptions(
                containerId = 3,
                containerName = "容器-1",
                graphicsDriver = "turnip,zink",
                dxwrapper = "dxvk",
                screenSize = "1600x900",
                lcAll = "ja_JP.UTF-8",
                tz = "Asia/Tokyo",
            ),
        )

        assertEquals(3, extras[WinlatorContract.EXTRA_CONTAINER_ID])
        assertEquals("容器-1", extras[WinlatorContract.EXTRA_CONTAINER_NAME])
        assertEquals("turnip,zink", extras[WinlatorContract.EXTRA_GRAPHICS_DRIVER])
        assertEquals("dxvk", extras[WinlatorContract.EXTRA_DXWRAPPER])
        assertEquals("1600x900", extras[WinlatorContract.EXTRA_SCREEN_SIZE])
        assertEquals("ja_JP.UTF-8", extras[WinlatorContract.EXTRA_LC_ALL])
        assertEquals("Asia/Tokyo", extras[WinlatorContract.EXTRA_TZ])
        assertFalse(extras.containsKey(WinlatorContract.EXTRA_SAVE))
    }

    /** Box64 预设放入 overrides JSON，而不是新增顶层 Intent 参数。 */
    @Test
    fun box64PresetIsSentThroughOverridesJson() {
        val extras = WinlatorContract.extras(
            WinlatorContract.LaunchOptions(box64Preset = "PERFORMANCE"),
        )

        assertEquals(
            """{"${WinlatorContract.OVERRIDE_BOX64_PRESET}":"PERFORMANCE"}""",
            extras[WinlatorContract.EXTRA_OVERRIDES],
        )
    }

    /** 仅在开启保存时下发 save=true，关闭时不发送该参数。 */
    @Test
    fun saveIsSentOnlyWhenEnabled() {
        assertEquals(true, WinlatorContract.extras(WinlatorContract.LaunchOptions(save = true))[WinlatorContract.EXTRA_SAVE])
        assertFalse(WinlatorContract.extras(WinlatorContract.LaunchOptions(save = false)).containsKey(WinlatorContract.EXTRA_SAVE))
    }

    /** 带 envVars 时不持久化：接收端 persistOverrides 会整体替换容器变量，避免用户配置被覆盖。 */
    @Test
    fun envVarsSuppressPersistence() {
        val extras = WinlatorContract.extras(
            WinlatorContract.LaunchOptions(save = true, envVars = "A=1"),
        )
        assertFalse(extras.containsKey(WinlatorContract.EXTRA_SAVE))
        assertTrue((extras[WinlatorContract.EXTRA_OVERRIDES] as String).contains("A=1"))
    }

    /** winlator-cn 按位置比较组件串：部分串会错位并污染缓存，只有九项齐全才下发。 */
    @Test
    fun partialWinComponentsAreNotSent() {
        val partial = WinlatorContract.extras(
            WinlatorContract.LaunchOptions(winComponents = "directshow=1"),
        )
        val partialOverrides = partial[WinlatorContract.EXTRA_OVERRIDES] as? String
        assertFalse(partialOverrides?.contains(WinlatorContract.OVERRIDE_WINCOMPONENTS) ?: false)

        val complete = com.tyranor.next.core.settings.EngineSettingsStore.encodeWinlatorWinComponents(
            com.tyranor.next.core.settings.EngineSettingsStore.WINLATOR_WIN_COMPONENT_KEYS.associateWith { "1" },
        )
        val full = WinlatorContract.extras(WinlatorContract.LaunchOptions(winComponents = complete))
        assertTrue((full[WinlatorContract.EXTRA_OVERRIDES] as String).contains(WinlatorContract.OVERRIDE_WINCOMPONENTS))
    }

    /** WineD3D 渲染器只在 wrapper 为 wined3d 时下发到首个配置槽。 */
    @Test
    fun wined3dRendererIsSentOnlyForWineD3dWrapper() {
        val extras = WinlatorContract.extras(
            WinlatorContract.LaunchOptions(
                dxwrapper = WinlatorContract.DXWRAPPER_WINED3D,
                wined3dRenderer = "gdi",
            ),
        )
        val overrides = JSONObject(extras[WinlatorContract.EXTRA_OVERRIDES] as String)

        assertEquals("wined3d", extras[WinlatorContract.EXTRA_DXWRAPPER])
        assertEquals("renderer=gdi", overrides.getString(WinlatorContract.OVERRIDE_DXWRAPPER_CONFIG))
        assertEquals(1, overrides.length())
        assertFalse(extras.containsKey(WinlatorContract.EXTRA_SAVE))
    }

    /** dxvk / 空 wrapper 时渲染器不参与下发，避免覆盖容器的 DXVK 配置。 */
    @Test
    fun rendererIsIgnoredOutsideWineD3dWrapper() {
        for (wrapper in listOf("", "dxvk")) {
            val extras = WinlatorContract.extras(
                WinlatorContract.LaunchOptions(dxwrapper = wrapper, wined3dRenderer = "vulkan"),
            )
            assertFalse(extras.containsKey(WinlatorContract.EXTRA_OVERRIDES))
        }
    }

    /** 渲染器、Box64、组件、音频、环境变量共用一个 overrides 对象，save 保留持久化标志。 */
    private val WIN_COMPONENTS = EngineSettingsStore.encodeWinlatorWinComponents(
        EngineSettingsStore.WINLATOR_WIN_COMPONENT_KEYS.associateWith { "1" },
    )

    @Test
    fun allOverridesAreMergedIntoOneJsonObject() {
        val extras = WinlatorContract.extras(
            WinlatorContract.LaunchOptions(
                dxwrapper = WinlatorContract.DXWRAPPER_WINED3D,
                wined3dRenderer = "gl",
                box64Preset = "CUSTOM-3",
                winComponents = WIN_COMPONENTS,
                audioDriver = "pulseaudio",
                envVars = "DXVK_HUD=1",
                save = true,
            ),
        )
        val overrides = JSONObject(extras[WinlatorContract.EXTRA_OVERRIDES] as String)

        assertEquals("wined3d", extras[WinlatorContract.EXTRA_DXWRAPPER])
        assertEquals("renderer=gl", overrides.getString(WinlatorContract.OVERRIDE_DXWRAPPER_CONFIG))
        assertEquals("CUSTOM-3", overrides.getString(WinlatorContract.OVERRIDE_BOX64_PRESET))
        assertEquals(WIN_COMPONENTS, overrides.getString(WinlatorContract.OVERRIDE_WINCOMPONENTS))
        assertEquals("pulseaudio", overrides.getString(WinlatorContract.OVERRIDE_AUDIO_DRIVER))
        assertEquals("DXVK_HUD=1", overrides.getString(WinlatorContract.OVERRIDE_ENV_VARS))
        assertEquals(5, overrides.length())
        // 带 envVars 时暂不持久化（接收端会整体替换容器变量）。
        assertFalse(extras.containsKey(WinlatorContract.EXTRA_SAVE))
    }

    /** 旧选项不覆盖容器渲染器，即使同时指定 Box64 预设。 */
    @Test
    fun legacyWrappersDoNotOverrideTheContainerRenderer() {
        for (wrapper in listOf("", "dxvk", "wined3d")) {
            val extras = WinlatorContract.extras(WinlatorContract.LaunchOptions(dxwrapper = wrapper))
            assertFalse(extras.containsKey(WinlatorContract.EXTRA_OVERRIDES))
            if (wrapper.isEmpty()) assertFalse(extras.containsKey(WinlatorContract.EXTRA_DXWRAPPER))
            else assertEquals(wrapper, extras[WinlatorContract.EXTRA_DXWRAPPER])

            val withBox64 = WinlatorContract.extras(
                WinlatorContract.LaunchOptions(dxwrapper = wrapper, box64Preset = "STABILITY"),
            )
            val overrides = JSONObject(withBox64[WinlatorContract.EXTRA_OVERRIDES] as String)
            assertFalse(overrides.has(WinlatorContract.OVERRIDE_DXWRAPPER_CONFIG))
            assertEquals("STABILITY", overrides.getString(WinlatorContract.OVERRIDE_BOX64_PRESET))
        }
    }
}
